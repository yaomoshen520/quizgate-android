package com.quizgate.spike.diagnostics

/** Rolling observations, not a frame-rate or battery estimate. */
class ResourceWindow(private val capacity: Int = 20) {
    private data class Sample(val cpu: Double?, val pss: Double, val heap: Double)
    private val values = java.util.ArrayDeque<Sample>()
    init { require(capacity > 0) }
    @Synchronized fun add(cpu: Double?, pss: Double, heap: Double) {
        require(pss.isFinite() && heap.isFinite() && pss >= 0 && heap >= 0 && (cpu == null || cpu.isFinite() && cpu >= 0))
        if (values.size == capacity) values.removeFirst()
        values.addLast(Sample(cpu, pss, heap))
    }
    @Synchronized fun summary(): String {
        if (values.isEmpty()) return "资源窗口：暂无样本"
        val samples = values.toList(); val cpus = samples.mapNotNull { it.cpu }
        val cpu = if (cpus.isEmpty()) "等待 CPU 增量" else String.format(java.util.Locale.US, "CPU 平均 %.1f%% / 最大 %.1f%%", cpus.average(), cpus.maxOrNull())
        return String.format(java.util.Locale.US, "最近 %d 个资源样本（最多 %d 个）：%s；PSS 平均 %.1f / 最大 %.1f MiB；堆最大 %.1f MiB", samples.size, capacity, cpu, samples.map { it.pss }.average(), samples.maxOf { it.pss }, samples.maxOf { it.heap })
    }
}
