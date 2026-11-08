package com.quizgate.spike.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.quizgate.spike.config.PrefsSessionStore
import com.quizgate.spike.config.SpikeSettings
import com.quizgate.spike.config.TriggerMode
import com.quizgate.spike.monitor.SessionTracker
import com.quizgate.spike.monitor.UsageRecorder
import com.quizgate.spike.ui.QuizActivity
import com.quizgate.spike.ui.QuizOverlay
import com.quizgate.spike.util.SpikeLog
import com.quizgate.spike.util.TriggerLedger
import java.io.File

class GateAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: SpikeSettings
    private lateinit var overlay: QuizOverlay
    private lateinit var gameWarning: com.quizgate.spike.ui.GameWarningOverlay
    private lateinit var parentReporter: com.quizgate.spike.net.ReportChannel
    private var warningKey: String? = null
    private val gameEvents by lazy { com.quizgate.spike.monitor.GateEventStore(File(filesDir, com.quizgate.spike.monitor.GateEventStore.FILE_NAME)) }
    private var tracker: SessionTracker? = null
    private var lastReportedPackage: String? = null
    private var fireCounter = 0
    private val usage by lazy { UsageRecorder(applicationContext) }
    private val power by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }

    private val ticker = object : Runnable {
        override fun run() {
            pollCommandFile()
            if (!power.isInteractive) {
                // 服务可能在灭屏期间重绑，收不到此前的灭屏广播；恢复的快照也不能继续累计。
                if (tracker?.foreground != null || (tracker?.sessionMs ?: 0L) > 0L) {
                    tracker?.onScreenOff(recordFinalStep = false)
                }
            } else {
                val foreground = probeForegroundPackage()
                if (foreground != null) observePackage(foreground)
                else tracker?.onTick()?.let { fire(it.packageUnderQuota, "quota") }
                updateGameWarning()
            }
            syncParentExtension()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                overlay.hide()
                gameWarning.hide()
                tracker?.onScreenOff()
                lastReportedPackage = null
                SpikeLog.record("灭屏：游戏周期保留，停止累计")
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        SpikeLog.attach(applicationContext)
        settings = SpikeSettings(applicationContext)
        overlay = QuizOverlay(applicationContext)
        gameWarning = com.quizgate.spike.ui.GameWarningOverlay(this)
        parentReporter = com.quizgate.spike.net.ReportChannel(applicationContext,gameEvents,settings)
        rebuildTracker()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
        handler.postDelayed(ticker, TICK_MS)
        // 服务在跑就必须让看门狗也在跑：孩子关掉开关的瞬间，只有它还活着。
        GateWatchdogService.ensure(applicationContext)
        SpikeLog.record(
            "服务已连接，阈值 ${settings.thresholdMs / 1000}s，抢屏方式 ${settings.triggerMode}"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val current = event ?: return
        if (current.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }
        // A non-focusable reminder emits our own package in WINDOW_STATE_CHANGED.
        // Read the focused application behind it so showing the reminder cannot pause the game.
        observePackage(probeForegroundPackage() ?: current.packageName?.toString())
    }

    private fun observePackage(packageName: String?) {
        if (!power.isInteractive) return
        if (packageName != null && packageName != lastReportedPackage) {
            lastReportedPackage = packageName
            SpikeLog.record("前台 -> $packageName")
        }
        tracker?.onWindowPackage(packageName)?.let { fire(it.packageUnderQuota, "quota") }
        updateGameWarning()
    }

    /** 浮窗消失或服务重绑时不保证有窗口切换事件。只读根节点包名，不遍历节点或读取控件文字。 */
    private fun probeForegroundPackage(): String? = runCatching {
        if (!power.isInteractive) return@runCatching null
        val focusedApplication = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        }
        val root = focusedApplication?.root ?: rootInActiveWindow ?: return@runCatching null
        try {
            root.packageName?.toString()
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }.getOrNull()

    override fun onInterrupt() {
        SpikeLog.record("服务被中断")
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        runCatching { overlay.hide() }
        runCatching { gameWarning.hide() }
        runCatching { unregisterReceiver(screenOffReceiver) }
        SpikeLog.record("服务已断开")
    }

    fun rebuildTracker() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { if (instance === this) rebuildTracker() }
            return
        }
        tracker = SessionTracker(
            targetPackages = settings.targetPackages,
            thresholdMs = settings.thresholdMs,
            graceMs = SessionTracker.DEFAULT_GRACE_MS,
            maxStepMs = TICK_MS * MAX_STEP_TICKS,
            monotonicMs = { SystemClock.elapsedRealtime() },
            wallMs = { System.currentTimeMillis() },
            store = PrefsSessionStore(applicationContext),
            report = { SpikeLog.record("计时：$it") },
            ruleProvider = { settings.cycleRule() },
            recordPlayed = { pkg, ms, at ->
                runCatching { usage.record(at, pkg, playedMs = ms) }
                    .onFailure { SpikeLog.record("娱乐用量落盘失败：${it.message}") }
            }
        )
        lastReportedPackage = null
    }

    fun startSelfTest(rounds: Int) {
        SpikeLog.record("抢屏自检开始：$rounds 轮，方式 ${settings.triggerMode}")
        for (index in 0 until rounds) {
            handler.postDelayed({ fire(SELF_TEST_PACKAGE, "self-test") }, index * SELF_TEST_INTERVAL_MS)
        }
    }

    fun onQuizCleared() {
        gameWarning.hide()
        warningKey = null
        tracker?.onQuizCleared()
        parentReporter.tick(force=true)
        SpikeLog.record("答题通过，计时清零")
    }

    fun currentSessionMs(): Long = tracker?.sessionMs ?: 0L

    fun currentForeground(): String = tracker?.foreground ?: "无"
    fun observedForeground(): String = if (power.isInteractive) lastReportedPackage ?: "" else ""
    fun appliedRuleVersion(): Long = tracker?.appliedRuleVersion ?: 0
    fun extensionsUsed(): Int = tracker?.extensionsUsed ?: 0
    fun gameState(): String = tracker?.gameState ?: "未开始"
    fun gameCycleId(): String = tracker?.cycleId ?: ""
    fun waitingForParent(): Boolean = tracker?.waitingForParent == true
    fun remainingMs(): Long = tracker?.remainingMs ?: settings.thresholdMs

    private fun recordGame(kind: com.quizgate.spike.monitor.GateEventKind, detail: String) {
        gameEvents.append(System.currentTimeMillis(), kind, detail.take(200))
        SpikeLog.record(detail)
    }

    private fun updateGameWarning() {
        val current = tracker ?: return
        if (!current.warningVisible || !power.isInteractive) { gameWarning.hide(); return }
        val key = "${current.appliedRuleVersion}:${current.extensionsUsed}:${current.parentExtension?.stage}"
        val expectedUsed = current.extensionsUsed
        val shown = gameWarning.show((current.remainingMs + 999) / 1000, current.extensionsUsed,
            current.maxExtensions, current.extensionMinutes, current.parentExtension?.stage?.name, current.canRequestParent, onExtend = {
                runCatching {
                    if (current.extensionsUsed == expectedUsed && current.extend()) {
                        recordGame(com.quizgate.spike.monitor.GateEventKind.GAME_EXTENDED,
                            "游戏延期 ${current.extensionsUsed}/${current.maxExtensions} 次，每次 ${current.extensionMinutes} 分钟")
                        // Remove the old button immediately; a queued second tap cannot consume another credit.
                        gameWarning.hide()
                        updateGameWarning()
                    }
                }.onFailure { SpikeLog.record("延期状态保存失败：${it.message}") }
            }, onRequest = {
                runCatching { if(current.beginParentRequest()) { updateGameWarning();syncParentExtension() } }
                    .onFailure { SpikeLog.record("申请状态保存失败：${it.javaClass.simpleName}") }
            }, onQuiz = { current.quizNow()?.let { fire(it.packageUnderQuota, "game-now") } })
        if (key != warningKey) {
            warningKey = key
            recordGame(if (shown) com.quizgate.spike.monitor.GateEventKind.GAME_WARNING
                else com.quizgate.spike.monitor.GateEventKind.GAME_WARNING_FAILED,
                if (shown) "游戏即将到时，已延期 ${current.extensionsUsed}/${current.maxExtensions} 次"
                else "游戏倒计时浮窗未能显示")
        }
    }

    private fun syncParentExtension() {
        val current=tracker ?: return
        val request=current.parentExtension ?: return
        val cycle=current.cycleId
        parentReporter.syncParentExtension(cycle,request) deliver@{ result ->
            if(instance !== this || tracker !== current || current.cycleId != cycle) return@deliver
            result.onSuccess { reply ->
                val stage=when(reply.state) {
                    com.quizgate.proto.GameExtensionState.GAME_EXTENSION_PENDING -> com.quizgate.spike.monitor.ParentExtension.Stage.PENDING
                    com.quizgate.proto.GameExtensionState.GAME_EXTENSION_APPROVED,
                    com.quizgate.proto.GameExtensionState.GAME_EXTENSION_APPLIED -> com.quizgate.spike.monitor.ParentExtension.Stage.GRANTED
                    com.quizgate.proto.GameExtensionState.GAME_EXTENSION_REJECTED -> com.quizgate.spike.monitor.ParentExtension.Stage.REJECTED
                    com.quizgate.proto.GameExtensionState.GAME_EXTENSION_CLOSED -> com.quizgate.spike.monitor.ParentExtension.Stage.CLOSED
                    else -> return@onSuccess
                }
                runCatching {
                    val wasAwaiting=current.awaitingQuiz
                    if(current.receiveParentDecision(cycle,reply.requestId,stage,reply.extensionMinutes)) {
                        if(wasAwaiting && !current.awaitingQuiz) {
                            overlay.hide()
                            QuizActivity.dismissForParentApproval()
                            probeForegroundPackage()?.let { current.onWindowPackage(it) }
                        }
                        if(stage==com.quizgate.spike.monitor.ParentExtension.Stage.GRANTED)
                            recordGame(com.quizgate.spike.monitor.GateEventKind.GAME_EXTENDED,"家长批准额外延期 ${reply.extensionMinutes} 分钟，本轮仅一次")
                        else SpikeLog.record("家长申请状态：${stage.name}")
                        current.onTick()?.let { fire(it.packageUnderQuota,"parent-decision") }
                        updateGameWarning()
                    }
                }.onFailure { SpikeLog.record("审批结果保存失败：${it.javaClass.simpleName}") }
            }.onFailure { error ->
                runCatching { current.parentRequestFailed(cycle) }
                SpikeLog.record("家长申请同步失败：${error.message}")
                if(request.stage==com.quizgate.spike.monitor.ParentExtension.Stage.SENDING)
                    android.widget.Toast.makeText(this,error.message ?: "申请未提交，请检查联网和绑定",android.widget.Toast.LENGTH_LONG).show()
                updateGameWarning()
            }
        }
    }

    /**
     * adb 驱动实验用的命令通道：这台 ROM 会把已运行 Activity 的新 intent 丢掉，
     * 所以改成往应用私有目录写一个文件，服务每 2 秒读一次。
     */
    private fun pollCommandFile() {
        val file = File(filesDir, "cmd.txt")
        if (!file.exists()) {
            return
        }
        val command = runCatching { file.readText().trim() }.getOrDefault("")
        file.delete()
        if (command.isEmpty()) {
            return
        }
        when {
            command == "home" -> settings.triggerMode = TriggerMode.HOME_START
            command == "start" -> settings.triggerMode = TriggerMode.START_ONLY
            command == "overlay" -> settings.triggerMode = TriggerMode.OVERLAY
            command.startsWith("test") -> {
                SpikeLog.record("抢屏方式 ${settings.triggerMode}，开始自检")
                startSelfTest(command.substringAfter(":", "5").toIntOrNull() ?: 5)
            }
            else -> SpikeLog.record("未知命令 $command")
        }
        SpikeLog.record("执行命令 $command，当前方式 ${settings.triggerMode}")
    }

    /**
     * 三种抢屏方式都要能跑，M0 的结论取决于哪一种在这台 ROM 上真的把题目送到了孩子眼前。
     */
    private fun fire(packageUnderQuota: String, reason: String) {
        gameWarning.hide()
        if (reason != "self-test" && tracker?.isGameForeground == true)
            recordGame(com.quizgate.spike.monitor.GateEventKind.GAME_FORCED, "游戏到时，进入答题；已延期 ${extensionsUsed()} 次")
        val mode = settings.triggerMode
        val round = fireCounter++
        val triggerAt = SystemClock.elapsedRealtime()
        TriggerLedger.beginPending(round)
        SpikeLog.record(
            "第 ${round + 1} 轮拉起 reason=$reason mode=$mode " +
                "overlay=${Settings.canDrawOverlays(this)} pkg=$packageUnderQuota"
        )
        when (mode) {
            TriggerMode.OVERLAY -> {
                val autoDismiss = if (reason == "self-test") SELF_TEST_DISMISS_MS else 0L
                val shown = overlay.show(packageUnderQuota, autoDismiss) { onQuizCleared() }
                if (shown) {
                    TriggerLedger.markResumed()
                } else {
                    SpikeLog.record("第 ${round + 1} 轮抢屏失败：悬浮窗没能铺上屏")
                    TriggerLedger.failPending(round)
                    tracker?.onFireFailed()
                    recordInterceptionFailure(packageUnderQuota, reason)
                }
            }
            TriggerMode.START_ONLY -> startActivity(round, triggerAt, packageUnderQuota, reason)
            TriggerMode.HOME_START -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({ startActivity(round, triggerAt, packageUnderQuota, reason) }, HOME_SETTLE_MS)
            }
        }
        handler.postDelayed({
            val failed = TriggerLedger.failPending(round)
            if (failed != null) {
                SpikeLog.record("第 ${round + 1} 轮抢屏失败：${RESUME_TIMEOUT_MS}ms 内答题页没到前台")
                tracker?.onFireFailed()
                recordInterceptionFailure(packageUnderQuota, reason)
            }
        }, HOME_SETTLE_MS + RESUME_TIMEOUT_MS)
    }

    private fun recordInterceptionFailure(pkg: String, reason: String) {
        if (reason == "self-test") return
        runCatching { usage.record(System.currentTimeMillis(), pkg, interceptionFailed = true) }
            .onFailure { SpikeLog.record("抢屏失败用量落盘失败：${it.message}") }
    }

    private fun startActivity(round: Int, triggerAt: Long, packageUnderQuota: String, reason: String) {
        val intent = Intent(this, QuizActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(QuizActivity.EXTRA_TRIGGER_AT, triggerAt)
            putExtra(QuizActivity.EXTRA_PACKAGE, packageUnderQuota)
            putExtra(QuizActivity.EXTRA_ROUND, round)
            putExtra(QuizActivity.EXTRA_SELF_TEST, reason == "self-test")
        }
        try {
            startActivity(intent)
        } catch (error: Exception) {
            SpikeLog.record("startActivity 抛异常 ${error.javaClass.simpleName}: ${error.message}")
            TriggerLedger.failPending(round)
            tracker?.onFireFailed()
            recordInterceptionFailure(packageUnderQuota, reason)
        }
    }

    companion object {

        @Volatile
        var instance: GateAccessibilityService? = null
            private set

        private const val TICK_MS = 2_000L

        /** 单步最多容忍几个 tick 的断档；超过说明中间有一段无法观测，不计入。 */
        private const val MAX_STEP_TICKS = 3L
        private const val HOME_SETTLE_MS = 100L
        private const val RESUME_TIMEOUT_MS = 2_000L
        private const val SELF_TEST_INTERVAL_MS = 6_000L
        private const val SELF_TEST_PACKAGE = "self-test"
        private const val SELF_TEST_DISMISS_MS = 5_000L
    }
}
