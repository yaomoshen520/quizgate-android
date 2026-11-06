package com.quizgate.spike.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.quizgate.spike.config.PrefsSessionStore
import com.quizgate.spike.config.SpikeSettings
import com.quizgate.spike.config.TriggerMode
import com.quizgate.spike.monitor.SessionTracker
import com.quizgate.spike.ui.QuizActivity
import com.quizgate.spike.ui.QuizOverlay
import com.quizgate.spike.util.SpikeLog
import com.quizgate.spike.util.TriggerLedger
import java.io.File

class GateAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: SpikeSettings
    private lateinit var overlay: QuizOverlay
    private var tracker: SessionTracker? = null
    private var lastReportedPackage: String? = null
    private var fireCounter = 0

    private val ticker = object : Runnable {
        override fun run() {
            pollCommandFile()
            tracker?.onTick()?.let { fire(it.packageUnderQuota, "quota") }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                overlay.hide()
                tracker?.onScreenOff()
                lastReportedPackage = null
                SpikeLog.record("灭屏，本轮计时作废")
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        SpikeLog.attach(applicationContext)
        settings = SpikeSettings(applicationContext)
        overlay = QuizOverlay(applicationContext)
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
        val packageName = current.packageName?.toString()
        if (packageName != null && packageName != lastReportedPackage) {
            lastReportedPackage = packageName
            SpikeLog.record("前台 -> $packageName")
        }
        tracker?.onWindowPackage(packageName)?.let { fire(it.packageUnderQuota, "quota") }
    }

    override fun onInterrupt() {
        SpikeLog.record("服务被中断")
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        runCatching { overlay.hide() }
        runCatching { unregisterReceiver(screenOffReceiver) }
        SpikeLog.record("服务已断开")
    }

    fun rebuildTracker() {
        tracker = SessionTracker(
            targetPackages = settings.targetPackages,
            thresholdMs = settings.thresholdMs,
            graceMs = SessionTracker.DEFAULT_GRACE_MS,
            maxStepMs = TICK_MS * MAX_STEP_TICKS,
            monotonicMs = { SystemClock.elapsedRealtime() },
            wallMs = { System.currentTimeMillis() },
            store = PrefsSessionStore(applicationContext),
            report = { SpikeLog.record("计时：$it") }
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
        tracker?.onQuizCleared()
        SpikeLog.record("答题通过，计时清零")
    }

    fun currentSessionMs(): Long = tracker?.sessionMs ?: 0L

    fun currentForeground(): String = tracker?.foreground ?: "无"

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
                }
            }
            TriggerMode.START_ONLY -> startActivity(round, triggerAt, packageUnderQuota)
            TriggerMode.HOME_START -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({ startActivity(round, triggerAt, packageUnderQuota) }, HOME_SETTLE_MS)
            }
        }
        handler.postDelayed({
            val failed = TriggerLedger.failPending(round)
            if (failed != null) {
                SpikeLog.record("第 ${round + 1} 轮抢屏失败：${RESUME_TIMEOUT_MS}ms 内答题页没到前台")
                tracker?.onFireFailed()
            }
        }, HOME_SETTLE_MS + RESUME_TIMEOUT_MS)
    }

    private fun startActivity(round: Int, triggerAt: Long, packageUnderQuota: String) {
        val intent = Intent(this, QuizActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(QuizActivity.EXTRA_TRIGGER_AT, triggerAt)
            putExtra(QuizActivity.EXTRA_PACKAGE, packageUnderQuota)
            putExtra(QuizActivity.EXTRA_ROUND, round)
        }
        try {
            startActivity(intent)
        } catch (error: Exception) {
            SpikeLog.record("startActivity 抛异常 ${error.javaClass.simpleName}: ${error.message}")
            TriggerLedger.failPending(round)
            tracker?.onFireFailed()
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
