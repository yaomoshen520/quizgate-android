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
    val explanation: String
) {
    val correctAnswer: String
        get() = options[correctIndex]
}

/**
 * 出题入口。M0 用本地实现，后续家长上传题库时换成远端实现，调用方不变。
 */
interface QuestionRepository {

    fun next(subject: Subject, grade: Int): Question

    /** 答错后在同知识点上换一道变式题，不直接重复原题。 */
    fun variant(question: Question, grade: Int): Question
}
