package com.quizgate.spike.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.LinearLayout
import com.quizgate.spike.R
import com.quizgate.spike.databinding.ActivityQuizBinding
import com.quizgate.spike.config.SpikeSettings
import com.quizgate.spike.monitor.UsageRecorder
import com.quizgate.spike.net.QuestionSource
import com.quizgate.spike.question.LocalQuestionRepository
import com.quizgate.spike.question.Question
import com.quizgate.spike.question.QuestionRepository
import com.quizgate.spike.question.Subject
import com.quizgate.spike.question.QuizSession
import com.quizgate.spike.util.SpikeLog
import java.util.concurrent.Executors

/**
 * 答题界面本体。Activity 和悬浮窗两种宿主共用这一份，
 * 因为抢屏方式还没定，UI 不能绑死在 Activity 上。
 */
class QuizPanel(
    context: Context,
    private val repository: QuestionRepository = QuestionSource.create(context.applicationContext),
    private val onResolved: () -> Unit
) {

    private val appContext = context

    // 悬浮窗宿主拿到的是应用上下文，没有 Activity 主题，这里显式套一层浅色主题，
    // 否则默认深色文字会落在深色底上看不见。
    private val binding = ActivityQuizBinding.inflate(
        LayoutInflater.from(ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Light))
    )
    private val density = context.resources.displayMetrics.density
    private val res = context.resources
    private val handler = Handler(Looper.getMainLooper())
    private val usage = UsageRecorder(context.applicationContext)
    private var packageUnderQuota = ""
    private var closed = false
    private val session = QuizSession(repository, SpikeSettings(context).grade, worker,
        java.util.concurrent.Executor { handler.post(it) }, ::render,
        recordAnswer = { correct ->
            if (packageUnderQuota != "self-test") {
                runCatching {
                    usage.record(System.currentTimeMillis(), packageUnderQuota,
                        correctCount = if (correct) 1 else 0, wrongCount = if (correct) 0 else 1)
                }.onFailure { SpikeLog.record("答题用量落盘失败：${it.message}") }
            }
        })

    val view: View
        get() = binding.root

    init {
        // 悬浮窗铺满整屏、答题 Activity 又是 Fullscreen 主题，两边都会顶到状态栏底下，
        // 所以题目内容自己让出状态栏高度。让位加在滚动区而不是根布局上，
        // 否则答对后的整屏确认层会在状态栏那一条留出一道没盖住的缝。
        binding.quizScroll.setPadding(0, statusBarHeight(), 0, 0)
    }

    fun start(packageName: String) {
        packageUnderQuota = packageName
        // 解析不出应用名（对照组、包已卸载）就用通用文案，别把 "control" 这种调试串露给孩子。
        val label = appLabel(packageName)
        binding.headerText.text = if (label == packageName) {
            res.getString(R.string.quiz_header_generic)
        } else {
            res.getString(R.string.quiz_header, label)
        }
        session.start(SUBJECTS[subjectCursor++ % SUBJECTS.size])
    }

    fun onBackPressed() {
        SpikeLog.record("返回键被拦下，必须先答题")
    }

    fun close() {
        closed = true
        session.close()
        handler.removeCallbacksAndMessages(null)
    }

    private fun render(state: QuizSession.State) {
        when (state) {
            QuizSession.State.Loading -> {
                if (binding.optionContainer.childCount == 0) binding.stemText.text = "正在准备题目…"
                for (i in 0 until binding.optionContainer.childCount) {
                    binding.optionContainer.getChildAt(i).isEnabled = false
                }
                binding.variantButton.isEnabled = false
            }
            is QuizSession.State.Ready -> {
                show(state.question)
                if (state.notice.isNotEmpty()) {
                    binding.explanationText.text = state.notice
                    binding.explanationText.visibility = View.VISIBLE
                }
            }
            is QuizSession.State.Wrong -> {
                binding.optionContainer.getChildAt(state.index)?.let { wrong ->
                    wrong.setBackgroundResource(R.drawable.bg_option_wrong)
                    wrong.startAnimation(AnimationUtils.loadAnimation(wrong.context, R.anim.shake))
                }
                binding.explanationText.text = "${res.getString(R.string.quiz_wrong)}${state.explanation}"
                binding.explanationText.visibility = View.VISIBLE
                binding.variantButton.visibility = View.VISIBLE
                binding.variantButton.isEnabled = true
            }
            QuizSession.State.Correct -> showSuccess()
        }
    }

    private fun show(question: Question) {
        binding.successLayer.visibility = View.GONE
        binding.subjectChip.text = question.subject.label
        binding.pointText.text = question.knowledgePoint
        binding.stemText.text = question.stem
        binding.optionContainer.removeAllViews()
        question.options.forEachIndexed { index, option ->
            binding.optionContainer.addView(optionButton(index, option, question.options.size))
        }
        binding.explanationText.visibility = View.GONE
        binding.variantButton.visibility = View.GONE
        binding.variantButton.isEnabled = true
        binding.variantButton.setOnClickListener { session.variant() }
    }

    private fun optionButton(index: Int, option: String, total: Int) =
        Button(binding.root.context).apply {
            text = "${OPTION_MARKS.getOrElse(index) { (index + 1).toString() }}  $option"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.text_option))
            isAllCaps = false
            stateListAnimator = null
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setTextColor(context.getColor(R.color.ink))
            setBackgroundResource(R.drawable.bg_option)
            setPadding(dp(18), dp(6), dp(18), dp(6))
            minimumHeight = dp(58)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index < total - 1) {
                    bottomMargin = res.getDimensionPixelSize(R.dimen.space_3)
                }
            }
            setOnClickListener { session.answer(index) }
        }

    /**
     * 答对先给一秒多确认再收场：直接 finish 的话孩子只看到屏幕闪了一下，
     * 学不到"答对了才放行"这条因果。
     */
    private fun showSuccess() {
        binding.successLayer.visibility = View.VISIBLE
        binding.successLayer.startAnimation(
            AnimationUtils.loadAnimation(binding.successLayer.context, R.anim.success_pop)
        )
        handler.postDelayed({ if (!closed) onResolved() }, SUCCESS_HOLD_MS)
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = appContext.packageManager.getApplicationInfo(packageName, 0)
        appContext.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun statusBarHeight(): Int {
        val id = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) appContext.resources.getDimensionPixelSize(id) else dp(24)
    }

    private companion object {
        val OPTION_MARKS = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨")
        val SUBJECTS = LocalQuestionRepository.SUBJECTS
        const val SUCCESS_HOLD_MS = 1_200L
        var subjectCursor = 0
        val worker = Executors.newFixedThreadPool(2)
    }
}
