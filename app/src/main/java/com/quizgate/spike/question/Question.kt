package com.quizgate.spike.question

enum class Subject {
    MATH,
    CHINESE,
    ENGLISH;

    val label: String
        get() = when (this) {
            MATH -> "数学"
            CHINESE -> "语文"
            ENGLISH -> "英语"
        }
}

data class Question(
    val subject: Subject,
    val knowledgePoint: String,
    val stem: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    /** 远端题只有编号，correctIndex 为 -1，必须交给服务端判分。 */
    val serverId: String = "",
    val grade: Int = 0
) {
    val correctAnswer: String
        get() = options[correctIndex]
}

/**
 * 出题与判分入口。联网实现只在工作线程调用，远端题没有本地正确答案。
 */
interface QuestionRepository {

    fun next(subject: Subject, grade: Int): Question

    /** 答错后优先在同知识点上换题；服务端只剩原题时允许重发。 */
    fun variant(question: Question, grade: Int): Question

    fun answer(question: Question, selectedIndex: Int): AnswerResult {
        require(question.serverId.isEmpty()) { "远端题不能在本地判分" }
        require(selectedIndex in question.options.indices)
        return AnswerResult(selectedIndex == question.correctIndex, question.explanation)
    }
}

data class AnswerResult(val correct: Boolean, val explanation: String = "")
