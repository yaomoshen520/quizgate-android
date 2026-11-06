package com.quizgate.spike.question

import kotlin.random.Random

/**
 * 数学题的规则生成器：题干、答案、解析都由同一份算式草稿导出，保证答案唯一。
 */
class MathQuestionGenerator(private val random: Random = Random.Default) {

    private data class Draft(
        val stem: String,
        val answer: Int,
        val distractors: List<Int>,
        val explanation: String
    )

    fun knowledgePointFor(grade: Int): String = when (grade) {
        1 -> POINT_ADD_SUBTRACT
        2 -> POINT_MULTIPLICATION
        else -> POINT_TWO_STEP
    }

    fun create(knowledgePoint: String): Question {
        val draft = when (knowledgePoint) {
            POINT_MULTIPLICATION -> multiplication()
            POINT_TWO_STEP -> twoStep()
            else -> addSubtract()
        }
        val options = buildOptions(draft.answer, draft.distractors)
        return Question(
            subject = Subject.MATH,
            knowledgePoint = knowledgePoint,
            stem = draft.stem,
            options = options.map(Int::toString),
            correctIndex = options.indexOf(draft.answer),
            explanation = draft.explanation
        )
    }

    private fun addSubtract(): Draft {
        val first = random.nextInt(3, 20)
        val second = random.nextInt(1, first)
        return if (random.nextBoolean()) {
            val answer = first + second
            Draft(
                stem = "$first + $second = ?",
                answer = answer,
                distractors = listOf(answer + 1, answer - 1, answer + 10, first + second + 3),
                explanation = "$first + $second 等于 $answer。可以从 $first 往后数 $second 个数。"
            )
        } else {
            val answer = first - second
            Draft(
                stem = "$first − $second = ?",
                answer = answer,
                distractors = listOf(answer + 1, answer - 1, answer + 10, first + second),
                explanation = "$first 减掉 $second，剩下 $answer。可以用 $second + $answer 验算，等于 $first 就对了。"
            )
        }
    }

    private fun multiplication(): Draft {
        val first = random.nextInt(2, 10)
        val second = random.nextInt(2, 10)
        val answer = first * second
        return Draft(
            stem = "$first × $second = ?",
            answer = answer,
            distractors = listOf(answer + first, answer - first, answer + second, first * (second + 1)),
            explanation = "$first × $second 表示 $second 个 $first 相加，口诀算出 $answer。"
        )
    }

    private fun twoStep(): Draft {
        val first = random.nextInt(2, 10)
        val second = random.nextInt(2, 10)
        val offset = random.nextInt(2, 30)
        val product = first * second
        return if (random.nextBoolean()) {
            val answer = product + offset
            Draft(
                stem = "$first × $second + $offset = ?",
                answer = answer,
                distractors = listOf(product, answer + 1, answer - 1, answer + offset),
                explanation = "先算乘法 $first × $second = $product，再加 $offset，得 $answer。"
            )
        } else {
            val minuend = product + offset
            val answer = minuend - offset
            Draft(
                stem = "$minuend − $first × $second = ?",
                answer = answer,
                distractors = listOf(product, minuend - product + 1, answer + 1, answer - 1),
                explanation = "先算乘法 $first × $second = $product，再用 $minuend 减 $product，得 $answer。"
            )
        }
    }

    private fun buildOptions(answer: Int, distractors: List<Int>): List<Int> {
        val picked = LinkedHashSet<Int>()
        picked.add(answer)
        for (value in distractors.shuffled(random)) {
            if (value >= 0 && value != answer) {
                picked.add(value)
            }
            if (picked.size == OPTION_COUNT) {
                break
            }
        }
        while (picked.size < OPTION_COUNT) {
            val extra = answer + random.nextInt(2, 12)
            if (extra != answer && !picked.contains(extra)) {
                picked.add(extra)
            }
        }
        return picked.toList().shuffled(random)
    }

    companion object {
        const val POINT_ADD_SUBTRACT = "20以内加减法"
        const val POINT_MULTIPLICATION = "表内乘法"
        const val POINT_TWO_STEP = "两步混合运算"
        private const val OPTION_COUNT = 4
    }
}
