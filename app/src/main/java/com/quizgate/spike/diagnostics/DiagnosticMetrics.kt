package com.quizgate.spike.diagnostics

import java.util.Locale

/** Fixed routes only: no URLs, credentials, bodies or child identifiers. */
class NetworkMetrics {
    private data class Counters(var count: Long = 0, var failed: Long = 0, var slow: Long = 0,
                                var totalMs: Long = 0, var maxMs: Long = 0)
    private val routes = setOf("enroll", "heartbeat", "gate-event", "usage", "next-question", "answer", "app-inventory",
        "game-extension", "game-extension-status")
    private val network = linkedMapOf<String, Counters>()

    @Synchronized
    fun record(path: String, elapsedMs: Long, success: Boolean) {
        val key = if (path in routes) path else "other"
        val value = network.getOrPut(key) { Counters() }
        val duration = elapsedMs.coerceAtLeast(0)
        value.count++
        if (!success) value.failed++
        if (duration >= 2_000) value.slow++
        value.totalMs += duration
        value.maxMs = maxOf(value.maxMs, duration)
    }

    @Synchronized
    fun summary(): String = if (network.isEmpty()) "接口：本进程暂无请求样本" else
        network.entries.joinToString("\n") { (route, value) ->
            String.format(Locale.US, "%s：%d 次，失败 %d，≥2秒 %d，平均 %.1f ms，最长 %d ms",
                route, value.count, value.failed, value.slow, value.totalMs.toDouble() / value.count, value.maxMs)
        }

    companion object { val process = NetworkMetrics() }
}

/** Process CPU as one-core equivalent; multiple busy cores can exceed 100%. */
class CpuSampler {
    private var previous: Pair<Long, Long>? = null
    fun sample(elapsedMs: Long, cpuMs: Long): Double? {
        val old = previous
        previous = elapsedMs to cpuMs
        if (old == null || elapsedMs <= old.first || cpuMs < old.second) return null
        return (cpuMs - old.second) * 100.0 / (elapsedMs - old.first)
    }
}
