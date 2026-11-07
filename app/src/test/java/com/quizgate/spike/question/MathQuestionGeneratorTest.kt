package com.quizgate.spike.question

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MathQuestionGeneratorTest {

    private val points = listOf(
        MathQuestionGenerator.POINT_ADD_SUBTRACT,
        MathQuestionGenerator.POINT_MULTIPLICATION,
        MathQuestionGenerator.POINT_TWO_STEP
    )

    @Test
    fun `every question has four distinct options and a valid correct index`() {
        val generator = MathQuestionGenerator(Random(20261104))
        repeat(300) {
            val question = generator.create(points[it % points.size])
            assertEquals(4, question.options.size)
            assertEquals("选项不能重复", 4, question.options.distinct().size)
            assertTrue(question.correctIndex in 0..3)
            assertTrue("答案必须非负", question.correctAnswer.toLong() >= 0)
            assertTrue(question.explanation.isNotBlank())
        }
    }

    @Test
    fun `multiplication answer matches the stem`() {
        val generator = MathQuestionGenerator(Random(11))
        val pattern = Regex("""(\d+) × (\d+) = \?""")
        repeat(100) {
            val question = generator.create(MathQuestionGenerator.POINT_MULTIPLICATION)
            val groups = pattern.matchEntire(question.stem)?.groupValues
                ?: error("题干格式不对：${question.stem}")
            val expected = groups[1].toInt() * groups[2].toInt()
            assertEquals(expected.toString(), question.correctAnswer)
        }
    }

    @Test
    fun `seeded generator is reproducible`() {
        assertEquals(
            MathQuestionGenerator(Random(7)).create(MathQuestionGenerator.POINT_TWO_STEP),
            MathQuestionGenerator(Random(7)).create(MathQuestionGenerator.POINT_TWO_STEP)
        )
    }

    @Test
    fun `both mixed operation branches match arithmetic in their stems`() {
        val generator = MathQuestionGenerator(Random(21))
        val addition = Regex("""(\d+) × (\d+) \+ (\d+) = \?""")
        val subtraction = Regex("""(\d+) − (\d+) × (\d+) = \?""")
        var additions = 0
        var subtractions = 0
        repeat(200) {
            val question = generator.create(MathQuestionGenerator.POINT_TWO_STEP)
            val plus = addition.matchEntire(question.stem)?.groupValues
            val minus = subtraction.matchEntire(question.stem)?.groupValues
            val expected = when {
                plus != null -> {
                    additions++
                    plus[1].toInt() * plus[2].toInt() + plus[3].toInt()
                }
                minus != null -> {
                    subtractions++
                    minus[1].toInt() - minus[2].toInt() * minus[3].toInt()
                }
                else -> error("Unexpected stem: ${question.stem}")
            }
            assertEquals(question.stem, expected.toString(), question.correctAnswer)
        }
        assertTrue(additions > 0 && subtractions > 0)
    }

    @Test
    fun `variant on the same knowledge point is a different stem`() {
        val generator = MathQuestionGenerator(Random(3))
        val first = generator.create(MathQuestionGenerator.POINT_ADD_SUBTRACT)
        var differentSeen = false
        repeat(20) {
            val next = generator.create(first.knowledgePoint)
            if (next.stem != first.stem) {
                differentSeen = true
            }
        }
        assertTrue("同知识点要能出变式题", differentSeen)
    }

    @Test
    fun `local repository always returns a variant that is not the original chinese english item`() {
        val repository = LocalQuestionRepository(Random(5))
        repeat(30) {
            val original = repository.next(Subject.CHINESE, 2)
            val variant = repository.variant(original, 2)
            assertEquals(original.knowledgePoint, variant.knowledgePoint)
            assertNotEquals(original.stem, variant.stem)
        }
    }

    @Test
    fun `every pool item points at a real option index`() {
        val repository = LocalQuestionRepository(Random(5))
        repeat(30) {
            listOf(Subject.CHINESE, Subject.ENGLISH).forEach { subject ->
                val question = repository.next(subject, 2)
                assertTrue(question.correctIndex in question.options.indices)
                assertTrue(question.options.distinct().size == question.options.size)
            }
        }
    }
}
