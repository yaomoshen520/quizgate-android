package com.quizgate.spike.monitor

/**
 * 娱乐会话的连续时长累计与触发判定，纯 JVM 逻辑。
 *
 * 口径：目标应用之间可以互相切换（抖音切游戏算同一段），离开全部目标应用不足宽限期
 * 仍算连续（切出去几十秒再回来不能重置计时），灭屏则本轮作废。
 *
 * 两个时钟分工：单调时钟只做**增量**，绝对时间只用来判断快照有多旧、以及识别重启。
 * 状态走 [SessionStore] 落盘，因此进程被杀或服务重绑之后累计时长不会归零——
 * 否则"重启手机"就成了比卸载便宜得多的绕过路径。
 */
class SessionTracker(
    private val targetPackages: Set<String>,
    private val thresholdMs: Long,
    private val graceMs: Long = DEFAULT_GRACE_MS,
    private val maxStepMs: Long = DEFAULT_MAX_STEP_MS,
    private val monotonicMs: () -> Long,
    private val wallMs: () -> Long = monotonicMs,
    private val store: SessionStore? = null,
    private val report: (String) -> Unit = {},
    private val recordPlayed: (String, Long, Long) -> Unit = { _, _, _ -> }
) {

    data class Trigger(val packageUnderQuota: String, val accumulatedMs: Long)

    private var foregroundPackage: String? = null
    private var awaySinceMs: Long? = null
    private var accumulatedMs = 0L
    private var lastAdvanceMs = monotonicMs()
    private var lastPersistedMs = 0L

    /** 非空表示"已触发、等答题解决"；用它取代旧的布尔位，好让超时能被观测到。 */
    private var awaitingSinceMs: Long? = null

    /** 抢屏失败后的退避时刻，避免每 tick 重试形成风暴。 */
    private var retryAllowedAfterMs = 0L

    private val bootId: Long
        get() = wallMs() - monotonicMs()

    init {
        restore()
    }

    val sessionMs: Long
        get() = accumulatedMs

    val foreground: String?
        get() = foregroundPackage

    fun onWindowPackage(packageName: String?): Trigger? {
        val now = monotonicMs()
        advanceTo(now)
        if (packageName == null) {
            return evaluate(now)
        }
        if (targetPackages.contains(packageName)) {
            foregroundPackage = packageName
            awaySinceMs = null
        } else {
            leaveTarget(now)
        }
        persist()
        return evaluate(now)
    }

    fun onTick(): Trigger? {
        val now = monotonicMs()
        advanceTo(now)
        return evaluate(now)
    }

    /**
     * 抢屏没成功时必须调用：否则"等答题"状态永远无人解除，闸门会静默卡死。
     * 时长不清零——目的不是惩罚孩子，而是冷却一段时间后重新尝试拦截。
     */
    fun onFireFailed() {
        if (awaitingSinceMs == null) {
            return
        }
        awaitingSinceMs = null
        retryAllowedAfterMs = monotonicMs() + FIRE_RETRY_BACKOFF_MS
        report("抢屏失败，${FIRE_RETRY_BACKOFF_MS / 1000}s 后重试")
    }

    fun onScreenOff(recordFinalStep: Boolean = true) {
        if (recordFinalStep) advanceTo(monotonicMs())
        reset()
    }

    /** 答对放行：本轮作废，重新累计下一个阈值周期。前台应用保持不变，等下一个事件续上计时。 */
    fun onQuizCleared() {
        accumulatedMs = 0L
        awaySinceMs = null
        awaitingSinceMs = null
        lastAdvanceMs = monotonicMs()
        persist()
    }

    private fun leaveTarget(now: Long) {
        if (foregroundPackage != null) {
            foregroundPackage = null
            awaySinceMs = now
        }
    }

    private fun advanceTo(now: Long) {
        val delta = now - lastAdvanceMs
        lastAdvanceMs = now
        if (foregroundPackage == null || delta <= 0L || awaitingSinceMs != null) {
            return
        }
        if (delta > maxStepMs) {
            // 单调时钟含深睡时间，tick 停摆期间的空白不能算成游戏时长。
            report("计时断档 ${delta}ms 超过单步上限 ${maxStepMs}ms，丢弃不累计")
            return
        }
        accumulatedMs += delta
        recordPlayed(requireNotNull(foregroundPackage), delta, wallMs())
        if (accumulatedMs - lastPersistedMs >= PERSIST_INTERVAL_MS) {
            persist()
        }
    }

    private fun evaluate(now: Long): Trigger? {
        val away = awaySinceMs
        if (foregroundPackage == null && away != null && now - away >= graceMs) {
            reset()
            return null
        }
        val pkg = foregroundPackage ?: return null
        val awaiting = awaitingSinceMs
        if (awaiting != null) {
            if (now - awaiting < AWAITING_TIMEOUT_MS) {
                return null
            }
            report("待答题已挂起 ${now - awaiting}ms 未解决，重新允许触发")
            awaitingSinceMs = null
        }
        if (accumulatedMs < thresholdMs) {
            return null
        }
        if (now < retryAllowedAfterMs) {
            return null
        }
        awaitingSinceMs = now
        persist()
        return Trigger(pkg, accumulatedMs)
    }

    private fun reset() {
        foregroundPackage = null
        awaySinceMs = null
        accumulatedMs = 0L
        awaitingSinceMs = null
        lastAdvanceMs = monotonicMs()
        lastPersistedMs = 0L
        store?.clear()
    }

    private fun persist() {
        lastPersistedMs = accumulatedMs
        store?.save(
            SessionStore.Snapshot(
                foregroundPackage = foregroundPackage,
                accumulatedMs = accumulatedMs,
                wallMs = wallMs(),
                monotonicMs = lastAdvanceMs,
                bootId = bootId
            )
        )
    }

    private fun restore() {
        val snapshot = store?.load() ?: return
        val pkg = snapshot.foregroundPackage
        if (pkg == null || !targetPackages.contains(pkg)) {
            store.clear()
            return
        }
        val nowWall = wallMs()
        val ageMs = nowWall - snapshot.wallMs
        if (ageMs < 0L) {
            report("系统时间比上次写盘更早 ${-ageMs}ms，丢弃快照")
            store.clear()
            return
        }
        if (ageMs > RESUME_WINDOW_MS) {
            report("快照距今 ${ageMs}ms，超过恢复窗口 ${RESUME_WINDOW_MS}ms，丢弃")
            store.clear()
            return
        }
        foregroundPackage = pkg
        accumulatedMs = snapshot.accumulatedMs
        if (snapshot.bootId == bootId) {
            // 同一次开机：单调时钟可比，断档交给 maxStepMs 判定，不虚增也不白送。
            // 快照之前的用量已经写入独立队列。恢复时不能把同一段时间再上报一次，
            // 进程不在的那段也不是观测到的娱乐时长。
            lastAdvanceMs = monotonicMs()
        } else {
            // 重启过或改过时间：离线期间不计入，只接回已累计的量。
            lastAdvanceMs = monotonicMs()
            report("检测到开机/时间变更，离线时段不计入，已累计 ${accumulatedMs}ms")
        }
    }

    companion object {
        const val DEFAULT_GRACE_MS = 120_000L
        const val DEFAULT_MAX_STEP_MS = 6_000L
        const val AWAITING_TIMEOUT_MS = 120_000L
        const val RESUME_WINDOW_MS = 10 * 60 * 1000L

        /** 抢屏失败后的冷却：每 2s 无脑重试会和系统抢窗口，反而更容易被 ROM 掐掉。 */
        const val FIRE_RETRY_BACKOFF_MS = 15_000L

        /** 写盘节流：累计每前进这么多才落一次盘。 */
        const val PERSIST_INTERVAL_MS = 10_000L
    }
}
