package com.quizgate.spike.net

import com.quizgate.proto.AnswerReply
import com.quizgate.proto.AnswerRequest
import com.quizgate.proto.NextQuestionReply
import com.quizgate.proto.NextQuestionRequest
import com.quizgate.spike.question.AnswerResult
import com.quizgate.spike.question.LocalQuestionRepository
import com.quizgate.spike.question.Question
import com.quizgate.spike.question.QuestionRepository
import com.quizgate.spike.question.Subject

/** 同步接口只在答题工作线程调用。取不到题就出本地题；远端判分失败由答题流程换本地新题。 */
class RemoteQuestionRepository(
    private val post: (String, ByteArray) -> ByteArray,
    private val local: QuestionRepository = LocalQuestionRepository(),
    private val report: (String) -> Unit = {},
    private val rememberGrade: (Int) -> Unit = {}
) : QuestionRepository {
    override fun next(subject: Subject, grade: Int): Question = fetchOrLocal(
        // 0 让服务端按绑定孩子的年级出题，不能用实验机的固定年级覆盖家长档案。
        NextQuestionRequest.getDefaultInstance()
    ) { local.next(subject, grade) }

    override fun variant(question: Question, grade: Int): Question {
        if (question.serverId.isEmpty()) return local.variant(question, grade)
        return fetchOrLocal(NextQuestionRequest.newBuilder()
            .setGrade(question.grade)
            .setKnowledgePoint(question.knowledgePoint)
            .setExcludeQuestionId(question.serverId).build()
        ) { local.next(question.subject, question.grade.takeIf { it > 0 } ?: grade) }
    }

    override fun answer(question: Question, selectedIndex: Int): AnswerResult {
        if (question.serverId.isEmpty()) return local.answer(question, selectedIndex)
        require(selectedIndex in question.options.indices)
        val reply = AnswerReply.parseFrom(post("answer", AnswerRequest.newBuilder()
            .setQuestionId(question.serverId).setSelectedIndex(selectedIndex).build().toByteArray()))
        return AnswerResult(reply.correct, reply.explanation)
    }

    private fun fetchOrLocal(request: NextQuestionRequest, fallback: () -> Question): Question = try {
        val reply = NextQuestionReply.parseFrom(post("next-question", request.toByteArray()))
        require(reply.hasQuestion()) { "服务端暂无可用题" }
        val q = reply.question
        require(q.questionId.isNotBlank() && q.stem.isNotBlank() && q.optionsCount >= 2)
        val subject = when (q.subject) {
            com.quizgate.proto.Subject.MATH -> Subject.MATH
            com.quizgate.proto.Subject.CHINESE -> Subject.CHINESE
            com.quizgate.proto.Subject.ENGLISH -> Subject.ENGLISH
            else -> error("服务端返回未知学科")
        }
        if (q.grade in 1..12) rememberGrade(q.grade)
        Question(subject, q.knowledgePoint, q.stem, q.optionsList.toList(), -1, "", q.questionId, q.grade)
    } catch (e: Exception) {
        report("取题不可用，使用本地题：${e.message}")
        fallback()
    }
}
