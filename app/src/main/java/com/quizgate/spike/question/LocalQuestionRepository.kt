package com.quizgate.spike.question

import kotlin.random.Random

/**
 * M0 的本地题库实现：数学走规则生成器，语文和英语走内置小题库。
 * 家长上传题库后，这里换成读本地缓存 + 后端同步的实现，接口不变。
 */
class LocalQuestionRepository(
    private val random: Random = Random.Default
) : QuestionRepository {

    private val math = MathQuestionGenerator(random)

    override fun next(subject: Subject, grade: Int): Question = when (subject) {
        Subject.MATH -> math.create(math.knowledgePointFor(grade))
        Subject.CHINESE -> pick(CHINESE_POOL, excludeStem = null)
        Subject.ENGLISH -> pick(ENGLISH_POOL, excludeStem = null)
    }

    override fun variant(question: Question, grade: Int): Question = when (question.subject) {
        Subject.MATH -> math.create(question.knowledgePoint)
        Subject.CHINESE -> pick(CHINESE_POOL, excludeStem = question.stem)
        Subject.ENGLISH -> pick(ENGLISH_POOL, excludeStem = question.stem)
    }

    private fun pick(pool: List<Question>, excludeStem: String?): Question {
        val candidates = pool.filter { it.stem != excludeStem }.ifEmpty { pool }
        return candidates.random(random)
    }

    companion object {

        val SUBJECTS = listOf(Subject.MATH, Subject.CHINESE, Subject.ENGLISH)

        private const val POINT_POEM = "古诗接龙"
        private const val POINT_WORD = "单词含义"

        private val CHINESE_POOL = listOf(
            Question(
                subject = Subject.CHINESE,
                knowledgePoint = POINT_POEM,
                stem = "「白日依山尽」的下一句是？",
                options = listOf("黄河入海流", "疑是地上霜", "春眠不觉晓", "红掌拨清波"),
                correctIndex = 0,
                explanation = "出自王之涣《登鹳雀楼》：白日依山尽，黄河入海流。"
            ),
            Question(
                subject = Subject.CHINESE,
                knowledgePoint = POINT_POEM,
                stem = "「锄禾日当午」的下一句是？",
                options = listOf("汗滴禾下土", "粒粒皆辛苦", "举头望明月", "低头思故乡"),
                correctIndex = 0,
                explanation = "出自李绅《悯农》：锄禾日当午，汗滴禾下土。"
            ),
            Question(
                subject = Subject.CHINESE,
                knowledgePoint = POINT_POEM,
                stem = "「床前明月光」的下一句是？",
                options = listOf("疑是地上霜", "举头望明月", "低头思故乡", "春眠不觉晓"),
                correctIndex = 0,
                explanation = "出自李白《静夜思》：床前明月光，疑是地上霜。"
            ),
            Question(
                subject = Subject.CHINESE,
                knowledgePoint = POINT_POEM,
                stem = "「春眠不觉晓」的下一句是？",
                options = listOf("处处闻啼鸟", "夜来风雨声", "花落知多少", "红掌拨清波"),
                correctIndex = 0,
                explanation = "出自孟浩然《春晓》：春眠不觉晓，处处闻啼鸟。"
            )
        )

        private val ENGLISH_POOL = listOf(
            Question(
                subject = Subject.ENGLISH,
                knowledgePoint = POINT_WORD,
                stem = "apple 的意思是？",
                options = listOf("苹果", "香蕉", "书本", "铅笔"),
                correctIndex = 0,
                explanation = "apple 是苹果。例句：I eat an apple every day."
            ),
            Question(
                subject = Subject.ENGLISH,
                knowledgePoint = POINT_WORD,
                stem = "banana 的意思是？",
                options = listOf("香蕉", "橙子", "葡萄", "西瓜"),
                correctIndex = 0,
                explanation = "banana 是香蕉。例句：The monkey likes banana."
            ),
            Question(
                subject = Subject.ENGLISH,
                knowledgePoint = POINT_WORD,
                stem = "book 的意思是？",
                options = listOf("书", "桌子", "窗户", "书包"),
                correctIndex = 0,
                explanation = "book 是书。bag 才是书包，别混淆。"
            ),
            Question(
                subject = Subject.ENGLISH,
                knowledgePoint = POINT_WORD,
                stem = "teacher 的意思是？",
                options = listOf("老师", "学生", "医生", "司机"),
                correctIndex = 0,
                explanation = "teacher 是老师，学生是 student。"
            )
        )
    }
}
