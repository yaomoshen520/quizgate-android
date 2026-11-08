package com.quizgate.spike.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A small non-focusable reminder: the game keeps focus and the rest of the screen remains usable. */
class GameWarningOverlay(private val context: Context) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var message: TextView? = null
    private var extendButton: Button? = null
    val isShowing: Boolean get() = root != null
    fun show(seconds: Long, used: Int, maximum: Int, minutes: Int, parentStage: String?, canRequest: Boolean,
             onExtend: () -> Unit, onRequest: () -> Unit, onQuiz: () -> Unit): Boolean {
        if (root == null) {
            val density = context.resources.displayMetrics.density
            fun dp(n: Int) = (n*density).toInt()
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16),dp(10),dp(16),dp(10))
                background = GradientDrawable().apply { setColor(Color.rgb(245,248,255)); cornerRadius=dp(16).toFloat() }
                elevation=dp(8).toFloat()
            }
            message=TextView(context).apply { textSize=16f; setTextColor(Color.rgb(25,42,74)) }
            panel.addView(message)
            val actions=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
            extendButton=Button(context)
            actions.addView(extendButton,LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(Button(context).apply { text="现在答题"; setOnClickListener { onQuiz() } },LinearLayout.LayoutParams(0,-2,1f))
            panel.addView(actions)
            val params=WindowManager.LayoutParams(dp(320),-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL; y=dp(36) }
            try { manager.addView(panel,params); root=panel } catch (_: Exception) { return false }
        }
        message?.text=if(parentStage=="PENDING") "已向家长申请，等待回复\n等待期间可以继续玩" else
            "还有 ${seconds/60}:${(seconds%60).toString().padStart(2,'0')} 就该答题了\n自主延期已用 $used/$maximum 次" +
                when(parentStage) { "GRANTED"->"\n家长已批准本轮额外延期"; "REJECTED"->"\n家长未同意，按原时限答题";
                    "FAILED"->"\n申请状态未确认，可重试"; else->"" }
        extendButton?.apply {
            text=when { used<maximum->"延长 $minutes 分钟"; parentStage=="SENDING"->"提交申请中…"; else->"向家长申请 $minutes 分钟" }
            isEnabled=used<maximum || canRequest
            visibility=if(used<maximum || canRequest || parentStage=="SENDING") android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener { if(used<maximum) onExtend() else onRequest() }
        }
        return true
    }
    fun hide() {
        val view=root ?: return
        root=null;message=null;extendButton=null
        runCatching { manager.removeView(view) }
    }
}
