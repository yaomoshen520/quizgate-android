package com.quizgate.spike.question

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

class QuizSessionTest {
    private val local = LocalQuestionRepository()
    private val question = Question(Subject.MATH, "乘法", "2×3=?", listOf("6", "8"), -1, "", "42", 3)
    private val worker = QueueExecutor()
    private val main = QueueExecutor()
    private val states = mutableListOf<QuizSession.State>()
    private val answers = mutableListOf<Boolean>()
    private var submissions = 0
    private var variantCalls = 0
    private var fail = false
    private var correct = false
    private val repository = object : QuestionRepository {
        override fun next(subject: Subject, grade: Int) = question
        override fun variant(question: Question, grade: Int): Question { variantCalls++; return question }
        override fun answer(question: Question, selectedIndex: Int): AnswerResult {
            submissions++
            if (fail) throw IOException("lost response")
            return AnswerResult(correct, "先算乘法")
        }
    }
    private fun session() = QuizSession(repository, 2, worker, main, { states += it }, { answers += it }, local)
    private fun finish() { worker.drain(); main.drain() }

    @Test fun `double tap sends one request and wrong answer requires a variant`() {
        val session = session()
        session.start(Subject.MATH); finish()
        session.answer(0); session.answer(1)
        assertEquals(0, submissions)
        finish()
        assertEquals(1, submissions)
        assertEquals(listOf(false), answers)
        assertTrue(states.last() is QuizSession.State.Wrong)
        session.answer(1); finish()
        assertEquals(1, submissions)
        session.variant(); finish()
        assertEquals(1, variantCalls)
        correct = true
        session.answer(0); finish()
        assertEquals(QuizSession.State.Correct, states.last())
    }

    @Test fun `lost answer response switches to local question and still requires a correct answer`() {
        val session = session()
        session.start(Subject.MATH); finish()
        fail = true
        session.answer(0); finish()
        val fallback = states.last() as QuizSession.State.Ready
        assertEquals("", fallback.question.serverId)
        assertTrue(fallback.notice.isNotEmpty())
        assertTrue(answers.isEmpty())
        assertFalse(states.contains(QuizSession.State.Correct))
        // 用实际可本地判分的 repository 验证 fallback 题有完整答案。
        assertTrue(local.answer(fallback.question, fallback.question.correctIndex).correct)
    }

    @Test fun `closed window ignores late response and cannot release quota`() {
        val session = session()
        session.start(Subject.MATH); finish()
        correct = true
        session.answer(0)
        session.close()
        finish()
        assertEquals(listOf(true), answers)
        assertFalse(states.contains(QuizSession.State.Correct))
    }

    @Test fun `closing before question arrives ignores result`() {
        val session = session()
        session.start(Subject.MATH)
        session.close(); finish()
        assertEquals(listOf(QuizSession.State.Loading), states)
    }

    @Test fun `real remote repository switches offline and only local correct answer releases`() {
        val remote = com.quizgate.spike.net.RemoteQuestionRepository({ path, _ ->
            if (path == "answer") throw IOException("response lost")
            com.quizgate.proto.NextQuestionReply.newBuilder().setQuestion(
                com.quizgate.proto.Question.newBuilder().setQuestionId("42")
                    .setSubject(com.quizgate.proto.Subject.MATH).setGrade(3).setStem("2x3=?")
                    .setKnowledgePoint("乘法").addOptions("6").addOptions("8")
            ).build().toByteArray()
        })
        val session = QuizSession(remote, 2, worker, main, { states += it }, { answers += it })
        session.start(Subject.MATH); finish()
        session.answer(0); finish()
        val fallback = (states.last() as QuizSession.State.Ready).question
        assertEquals("", fallback.serverId)
        assertFalse(states.contains(QuizSession.State.Correct))
        session.answer(fallback.correctIndex); finish()
        assertEquals(QuizSession.State.Correct, states.last())
        assertEquals(listOf(true), answers)
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
