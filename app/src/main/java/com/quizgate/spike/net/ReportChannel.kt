package com.quizgate.spike.net

import android.content.Context
import android.os.Build
import com.quizgate.proto.EnrollRequest
import com.quizgate.proto.EnrollReply
import com.quizgate.proto.GateEventReportReply
import com.quizgate.proto.HeartbeatRequest
import com.quizgate.proto.HeartbeatReply
import com.quizgate.spike.config.SpikeSettings
import com.quizgate.spike.monitor.GateEventStore
import com.quizgate.spike.monitor.GateProbe
import com.quizgate.spike.service.GateAccessibilityService
import com.quizgate.spike.util.SpikeLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 设备往服务端说话的那张嘴。三件事，一个顺序：
 * 先登记拿到令牌，再打心跳让服务端知道"这台设备此刻还活着、闸门还在"，
 * 最后把攒着的闸门事件发走。
 *
 * 之所以由看门狗来驱动而不是由无障碍服务驱动：孩子关掉的正是无障碍服务。
 * 那一刻最需要发出去的消息，恰好不能只有"还在被关着的代码"才发得出去。
 *
 * 失败一律不丢数据：发不出去的记录留在台账里，等下一次心跳周期再试。
 */
class ReportChannel(
    private val context: Context,
    private val events: GateEventStore,
    private val settings: SpikeSettings,
    private val identity: DeviceIdentity = DeviceIdentity(context),
    private val state: ReportState = ReportState(context)
) {

    private val lastHeartbeatAt = AtomicLong(0L)

    /**
     * 主线程调这个：已经在跑就直接返回。上报一次可能耗好几秒（弱网加超时），
     * 而看门狗的 tick 是 30 秒一次——不挡住的话会排出一串线程互相抢同一份台账。
     * <p>
     * 挡的是整个进程而不是这一个实例：首页那个"立刻上报"和看门狗的定时 tick 是两套实例，
     * 撞在一起的话同一批会被发两遍。同一条事件在服务端只认一次编号，
     * 所以后果不是数据错，而是家长看到一堆 duplicated 数字没人解释得了。
     *
     * @param force 忽略心跳节流。家长按"立刻上报"就是要现在通一次，不是等下一个周期。
     */
    fun tick(force: Boolean = false) {
        if (!settings.reportingEnabled) {
            return
        }
        if (force) {
            lastHeartbeatAt.set(0L)
        }
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        executor.execute {
            try {
                runOnce()
            } catch (e: Throwable) {
                // 包括 OutOfMemoryError 这类：这条线程死了，看门狗就永远不再上报，
                // 而家长看到的只是"设备失联"——最不该发生的事是静默地停。
                SpikeLog.record("上报线程异常：${e.javaClass.simpleName} ${e.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun runOnce() {
        val client = ApiClient(settings.serverBaseUrl)
        val now = System.currentTimeMillis()
        if (!identity.enrolled && !enroll(client)) {
            // enroll 已经把自己那条失败原因写进状态了，这里不能补一句更泛的话：
            // 现场排查靠的就是那句原因，被"还没登记上"盖掉之后只剩一句废话。
            return
        }
        if (!heartbeatIfNeeded(client, now)) {
            return
        }
        flush(client)
    }

    /** @return 登记成功、令牌可用 */
    private fun enroll(client: ApiClient): Boolean {
        val request = EnrollRequest.newBuilder()
            .setInstallId(identity.installId)
            .setDeviceModel(Build.MODEL ?: "unknown")
            .setAndroidApiLevel(Build.VERSION.SDK_INT)
            .build()
        return try {
            val reply = EnrollReply.parseFrom(client.post("enroll", "", request.toByteArray()))
            if (reply.deviceToken.isBlank()) {
                // 200 却回空令牌：服务端那条路径有问题，不能当成功存下来，
                // 否则后面每一步都带着空令牌去敲门，看起来像"网络一直不稳"。
                state.recordFailed("服务端回了空令牌")
                SpikeLog.record("登记返回空令牌，未保存")
                false
            } else {
                identity.saveEnrollment(reply.deviceToken, reply.bindCode)
                SpikeLog.record("登记完成，绑定码 ${reply.bindCode}")
                true
            }
        } catch (e: Exception) {
            state.recordFailed(e.message ?: "登记失败")
            SpikeLog.record("登记失败：${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** @return 这一轮还可以接着往外发东西 */
    private fun heartbeatIfNeeded(client: ApiClient, now: Long): Boolean {
        val previous = lastHeartbeatAt.get()
        if (previous != 0L && now - previous < HEARTBEAT_MS) {
            return true
        }
        val health = GateProbe.health(context)
        val request = HeartbeatRequest.newBuilder()
            // 开关勾着但服务没绑上时闸门同样拦不住，所以这里要的是"两个都成立"，不是开关本身。
            .setAccessibilityConnected(health.accessibilityOn && health.serviceBound)
            .setAccumulatedMs(GateAccessibilityService.instance?.currentSessionMs() ?: 0L)
            .setKnownRuleVersion(identity.knownRuleVersion)
            .build()
        try {
            val reply = HeartbeatReply.parseFrom(client.post("heartbeat", identity.deviceToken, request.toByteArray()))
            // 只有真成功才占住这 60 秒的节流窗口：先记下时间再发的话，一次失败会让后面
            // 两个 tick 直接跳过心跳，而 flush 却照样跑，把那句失败原因冲成「连接正常」。
            lastHeartbeatAt.set(now)
            if (reply.ruleChanged) {
                applyRule(reply)
            }
            return true
        } catch (e: ApiClient.ReportUnavailable) {
            // 失败原因先记下来，别让后面那次 flush 用"没有待发的事件"把它盖掉：
            // 真机上就出现过令牌被服务端拒掉、首页却写着「连接正常」的那一分钟。
            if (e.needsReenroll) {
                // 令牌没了就是这台设备在服务端不存在了（清库、或者换过环境）。
                // 不重登记的话会一直 401 下去，而且看起来像"网络不好"。
                identity.forgetToken()
                SpikeLog.record("服务端不认这个令牌，下次重新登记")
                state.recordFailed("令牌不被服务端认，正在重新登记")
            } else {
                state.recordFailed(e.message ?: "心跳失败")
            }
            return false
        }
    }

    private fun applyRule(reply: HeartbeatReply) {
        val rule = reply.rule
        identity.knownRuleVersion = rule.version
        if (!settings.followServerRule) {
            return
        }
        // 0 分钟不是"家长把阈值调成 0"，是这条规则压根没设过阈值（服务端默认回的快照）。
        // 当成 0 存下来的话计时器每一秒都会判超限，孩子寸步难行而家长看不到任何原因。
        if (rule.thresholdMinutes <= 0) {
            return
        }
        val thresholdMs = rule.thresholdMinutes * 60_000L
        if (thresholdMs == settings.thresholdMs) {
            return
        }
        settings.thresholdMs = thresholdMs
        GateAccessibilityService.instance?.rebuildTracker()
        SpikeLog.record("按家长设定把阈值改成 ${rule.thresholdMinutes} 分钟")
    }

    private fun flush(client: ApiClient) {
        val pending = events.pending()
        if (pending.isEmpty()) {
            state.recordIdle()
            return
        }
        try {
            val reply = GateEventReportReply.parseFrom(
                client.post("gate-event", identity.deviceToken, ReportPayloads.toRequest(pending).toByteArray())
            )
            // 只在"这一整批发出去了"之后才标记已上报。反过来先标记再发，
            // 一旦中途失败，那几条证据就永远只躺在手机里，家长看到的是一片空白。
            events.markFlushed()
            state.recordSent(pending.size, reply.accepted, reply.duplicated)
        } catch (e: Exception) {
            state.recordFailed(e.message ?: "闸门事件上报失败")
        }
    }

    private companion object {
        /** 心跳间隔。服务端把"超过 3 分钟没心跳"判成失联，60 秒留了两次重试的余量。 */
        const val HEARTBEAT_MS = 60_000L

        /** 整个进程只有一个上报动作在跑，见 [tick]。 */
        val inFlight = AtomicBoolean(false)

        /**
         * 线程也只有一个：每次首页新建一个 executor 的话，
         * 家长反复进出首页就会攒下一堆永不退出的空闲线程。
         */
        val executor = Executors.newSingleThreadExecutor()
    }
}
