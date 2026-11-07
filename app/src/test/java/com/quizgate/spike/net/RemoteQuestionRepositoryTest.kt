package com.quizgate.spike.net

import com.quizgate.proto.AnswerReply
import com.quizgate.proto.AnswerRequest
import com.quizgate.proto.NextQuestionReply
import com.quizgate.proto.NextQuestionRequest
import com.quizgate.spike.question.Subject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteQuestionRepositoryTest {
    private fun reply(id: String = "42"): ByteArray = NextQuestionReply.newBuilder().setQuestion(
        com.quizgate.proto.Question.newBuilder().setQuestionId(id).setSubject(com.quizgate.proto.Subject.MATH)
            .setGrade(3).setKnowledgePoint("乘法").setStem("2×3=?").addOptions("6").addOptions("8")
    ).build().toByteArray()

    @Test fun `server question carries no local answer and uses child grade`() {
        val repository = RemoteQuestionRepository({ path, body ->
            assertEquals("next-question", path)
            assertEquals(0, NextQuestionRequest.parseFrom(body).grade)
            reply()
        })
        val question = repository.next(Subject.ENGLISH, 2)
        assertEquals("42", question.serverId)
        assertEquals(-1, question.correctIndex)
        assertEquals(Subject.MATH, question.subject)
        assertEquals(3, question.grade)
    }

    @Test fun `variant preserves point grade and exclusion and server alone judges`() {
        var count = 0
        val repository = RemoteQuestionRepository({ path, body ->
            when (path) {
                "next-question" -> {
                    if (count++ > 0) {
                        val request = NextQuestionRequest.parseFrom(body)
                        assertEquals("乘法", request.knowledgePoint)
                        assertEquals("42", request.excludeQuestionId)
                        assertEquals(3, request.grade)
                    }
                    reply(if (count == 1) "42" else "43")
                }
                else -> {
                    val request = AnswerRequest.parseFrom(body)
                    assertEquals("42", request.questionId)
                    assertEquals(0, request.selectedIndex)
                    AnswerReply.newBuilder().setCorrect(false).setExplanation("再想一想").build().toByteArray()
                }
            }
        })
        val question = repository.next(Subject.MATH, 2)
        assertFalse(repository.answer(question, 0).correct)
        assertEquals("43", repository.variant(question, 2).serverId)
    }

    @Test fun `network empty and malformed replies produce usable local questions`() {
        for (response in listOf<ByteArray?>(null, ByteArray(0), byteArrayOf(-1))) {
            val repository = RemoteQuestionRepository({ _, _ -> response ?: throw IOException("offline") })
            val q = repository.next(Subject.MATH, 2)
            assertEquals("", q.serverId)
            assertTrue(repository.answer(q, q.correctIndex).correct)
        }
    }

    @Test(expected = IOException::class)
    fun `remote answer failure never guesses an answer locally`() {
        var online = true
        val repository = RemoteQuestionRepository({ _, _ -> if (online) reply() else throw IOException("offline") })
        val q = repository.next(Subject.MATH, 2)
        online = false
        repository.answer(q, 0)
    }
}
