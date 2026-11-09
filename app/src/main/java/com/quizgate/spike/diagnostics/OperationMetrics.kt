package com.quizgate.spike.diagnostics

/** Fixed labels and bounded counters: never retain request bodies, file paths or credentials. */
object OperationMetrics {
    private val allowed = setOf("session_save", "usage_record", "usage_ack", "app_label", "home_render", "update_check", "update_download", "update_verify")
    private data class Stats(var count: Long = 0, var failed: Long = 0, var totalNs: Long = 0, var maxNs: Long = 0, var slow: Long = 0)
    private val rows = linkedMapOf<String, Stats>()
    fun <T> measure(name: String, block: () -> T): T {
        val start = System.nanoTime(); var success = false
        try { return block().also { success = true } }
        finally { runCatching { record(name, System.nanoTime() - start, success) } }
    }
    @Synchronized fun record(name: String, ns: Long, success: Boolean) {
        if (name !in allowed) return
        val time = ns.coerceAtLeast(0); val s = rows.getOrPut(name) { Stats() }
        s.count++; if (!success) s.failed++; s.totalNs += time; s.maxNs = maxOf(s.maxNs, time)
        if (time >= 16_000_000) s.slow++
    }
    @Synchronized fun summary(): String = if (rows.isEmpty()) "关键操作：暂无样本" else rows.entries.joinToString("\n") { (name, s) ->
        java.lang.String.format(java.util.Locale.US, "%s：%d 次，失败 %d，平均 %.2fms，最大 %.2fms，≥16ms %d 次", name, s.count, s.failed, s.totalNs / s.count / 1e6, s.maxNs / 1e6, s.slow)
    }
}
