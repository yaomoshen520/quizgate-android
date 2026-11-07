package com.quizgate.spike.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import com.quizgate.spike.util.SpikeLog
import com.quizgate.spike.net.QuestionSource

/**
 * 抢屏方式之一：用悬浮窗盖住整个屏幕，不依赖后台拉起 Activity 是否被放行。
 */
class QuizOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var panel: QuizPanel? = null

    val isShowing: Boolean
        get() = root != null

    /** 返回屏幕是否真的被盖住——调用方据此决定这次抢屏算不算成功。 */
    fun show(packageName: String, autoDismissMs: Long, onResolved: () -> Unit): Boolean {
        if (root != null) {
            SpikeLog.record("悬浮窗已在显示，忽略重复拉起")
            return true
        }
        val panel = QuizPanel(context, QuestionSource.create(context, packageName == "self-test"), onResolved = {
            hide()
            onResolved()
        })
        panel.start(packageName)
        val view = panel.view.apply {
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    panel.onBackPressed()
                    true
                } else {
                    false
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.OPAQUE
        )
        params.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                this.panel = panel
                root = view
                view.requestFocus()
                SpikeLog.record("悬浮窗答题页已显示 pkg=$packageName")
                if (autoDismissMs > 0) {
                    view.postDelayed({
                        SpikeLog.record("自检轮结束，悬浮窗自动关闭")
                        hide()
                    }, autoDismissMs)
                }
            }
            .onFailure { error ->
                panel.close()
                SpikeLog.record("悬浮窗添加失败 ${error.javaClass.simpleName}: ${error.message}")
            }
        return isShowing
    }

    fun hide() {
        val view = root ?: return
        panel?.close()
        panel = null
        root = null
        runCatching { windowManager.removeView(view) }
            .onFailure { error -> SpikeLog.record("悬浮窗移除失败 ${error.message}") }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
