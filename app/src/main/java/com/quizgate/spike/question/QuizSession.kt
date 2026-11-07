package com.quizgate.spike.question

import java.util.concurrent.Executor

/** 工作线程负责网络，主线程串行变更状态。销毁后的响应和连续点击都不能再次放行。 */
class QuizSession(
    private val repository: QuestionRepository,
    private val grade: Int,
    private val worker: Executor,
    private val main: Executor,
    private val render: (State) -> Unit,
    private val recordAnswer: (Boolean) -> Unit,
    private val local: QuestionRepository = LocalQuestionRepository()
) {
    sealed class State {
        object Loading : State()
        data class Ready(val question: Question, val notice: String = "") : State()
        data class Wrong(val question: Question, val index: Int, val explanation: String) : State()
        object Correct : State()
    }

    private var current: Question? = null
    private var busy = false
    private var answered = false
    private var closed = false

    fun start(subject: Subject) = load { repository.next(subject, grade) }

    fun variant() {
        val question = current ?: return
        if (!answered) return
        load { repository.variant(question, grade) }
    }

    private fun load(fetch: () -> Question) {
        if (closed || busy) return
        busy = true
        render(State.Loading)
        worker.execute {
            val question = fetch()
            main.execute {
                if (!closed) {
                    current = question
                    busy = false
                    answered = false
                    render(State.Ready(question))
                }
            }
        }
    }

    fun answer(index: Int) {
        val question = current ?: return
        if (closed || busy || answered || index !in question.options.indices) return
        busy = true
        render(State.Loading)
        worker.execute {
            val result = runCatching { repository.answer(question, index) }
            // 原远端题没有本地答案。请求超时即使服务端已收下，也必须重新答一道本地题。
            val fallback = if (result.isFailure)
                local.next(question.subject, question.grade.takeIf { it > 0 } ?: grade) else null
            // 已实际完成的判分即使窗口被关闭也要落账，UI 回调则必须作废。
            result.getOrNull()?.let { recordAnswer(it.correct) }
            main.execute {
                if (!closed) {
                    busy = false
                    val verdict = result.getOrNull()
                    if (verdict == null) {
                        current = fallback
                        answered = false
                        render(State.Ready(requireNotNull(fallback), "暂时连不上，请完成这道题后继续。"))
                    } else {
                        answered = true
                        render(if (verdict.correct) State.Correct
                            else State.Wrong(question, index, verdict.explanation))
                    }
                }
            }
        }
    }

    fun close() {
        closed = true
    }
}
