package com.quizgate.spike.util

import android.os.SystemClock

data class RoundResult(
    val round: Int,
    val resumed: Boolean,
    val latencyMs: Long
)

/**
 * 抢屏自检的记账器：一次触发对应一轮，只有 QuizActivity 真的到前台才算成功。
 */
object TriggerLedger {

    private var pendingRound: Int? = null
    private var pendingStartElapsed = 0L

    val results = mutableListOf<RoundResult>()

    fun beginPending(round: Int) {
        pendingRound = round
        pendingStartElapsed = SystemClock.elapsedRealtime()
    }

    fun markResumed(): RoundResult? {
        val round = pendingRound ?: return null
        val latency = SystemClock.elapsedRealtime() - pendingStartElapsed
        pendingRound = null
        val result = RoundResult(round, resumed = true, latencyMs = latency)
        results.add(result)
        return result
    }

    fun failPending(round: Int): RoundResult? {
        if (pendingRound != round) {
            return null
        }
        pendingRound = null
        val result = RoundResult(round, resumed = false, latencyMs = SystemClock.elapsedRealtime() - pendingStartElapsed)
        results.add(result)
        return result
    }

    fun summary(): String {
        val total = results.size
        val success = results.count { it.resumed }
        val latencies = results.filter { it.resumed }.map { it.latencyMs }
        val average = if (latencies.isEmpty()) 0 else latencies.average().toLong()
        val max = latencies.maxOrNull() ?: 0
        return "自检 $success/$total 成功，平均延迟 ${average}ms，最大 ${max}ms"
    }
}
