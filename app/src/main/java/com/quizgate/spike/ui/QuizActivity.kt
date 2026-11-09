package com.quizgate.spike.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import com.quizgate.spike.service.GateAccessibilityService
import com.quizgate.spike.util.SpikeLog
import com.quizgate.spike.util.TriggerLedger
import com.quizgate.spike.net.QuestionSource

class QuizActivity : Activity() {

    private var panel: QuizPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = java.lang.ref.WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Fullscreen 主题在这台机上会把状态栏/挖孔那一条留成黑色，让窗口按整屏布局铺过去。
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = window.attributes
        }
        SpikeLog.attach(applicationContext)

        val triggerAt = intent.getLongExtra(EXTRA_TRIGGER_AT, -1L)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: "?"
        val round = intent.getIntExtra(EXTRA_ROUND, -1)
        if (triggerAt > 0) {
            SpikeLog.record("答题页到前台 round=${round + 1} pkg=$packageName 抢屏延迟=${SystemClock.elapsedRealtime() - triggerAt}ms")
        }
        if (round >= 0) {
            TriggerLedger.markResumed()?.let {
                SpikeLog.record("第 ${round + 1} 轮成功 ${it.latencyMs}ms；${TriggerLedger.summary()}")
            }
        }

        val quizPanel = QuizPanel(this, QuestionSource.create(applicationContext, packageName == "self-test")) {
            GateAccessibilityService.instance?.onQuizCleared()
            finish()
        }
        panel = quizPanel
        quizPanel.start(packageName)
        // FLAG_LAYOUT_IN_SCREEN 只让窗口背景铺到整屏，内容区仍旧从状态栏下面开始；
        // 不补这一条，答对后的确认层就在状态栏那一条留出一道没盖住的缝。
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        setContentView(quizPanel.view)

        if (intent.getBooleanExtra(EXTRA_SELF_TEST, false)) {
            quizPanel.view.postDelayed({
                SpikeLog.record("自检轮结束，答题页自动关闭")
                finish()
            }, SELF_TEST_DISMISS_MS)
        }
    }

    override fun onBackPressed() {
        panel?.onBackPressed()
    }

    override fun onDestroy() {
        if(active?.get() === this) active=null
        panel?.close()
        panel = null
        super.onDestroy()
    }

    companion object {
        private var active: java.lang.ref.WeakReference<QuizActivity>? = null
        fun blocksUpdateInstallation(): Boolean = active?.get()?.let { !it.isFinishing && !it.isDestroyed } == true
        fun dismissForParentApproval() { active?.get()?.finish() }
        const val EXTRA_TRIGGER_AT = "trigger_at"
        const val EXTRA_PACKAGE = "package_under_quota"
        const val EXTRA_ROUND = "round"
        const val EXTRA_SELF_TEST = "self_test"

        private const val SELF_TEST_DISMISS_MS = 5_000L
    }
}
