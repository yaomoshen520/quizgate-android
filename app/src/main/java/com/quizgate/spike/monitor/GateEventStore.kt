package com.quizgate.spike.monitor

import java.io.File

/**
 * 闸门事件的落盘台账。落盘是硬要求：孩子把闸门弄掉的那一刻进程很可能马上就没了，
 * 只记在内存里的话，家长永远看不到"曾经掉过"。
 *
 * 一行一条 `时刻|类型|是否已上报|说明`，说明里的分隔符会被吃掉，
 * 因为这一层不想为了转义引一个 JSON 库。
 *
 * 写的人（看门狗）和读的人（首页、将来的上报通道）各拿一个实例，
 * 所以文件本身才是唯一事实源：每次读写都重新加载。
 * 早先这里缓存过一份内存列表，实测首页永远看不到看门狗刚写进去的那条。
 */
class GateEventStore(private val file: File) {
    private val lock = file.canonicalPath.intern()

    @Synchronized
    fun append(atMs: Long, kind: GateEventKind, detail: String) = synchronized(lock) {
        val current = read()
        current += "$atMs|${kind.name}|0|${detail.replace('|', ' ')}"
        write(current.takeLast(MAX_LINES))
    }

    @Synchronized
    fun recent(limit: Int): List<GateEvent> = synchronized(lock) {
        read().takeLast(limit).mapNotNull { parse(it)?.event }.reversed()
    }

    @Synchronized
    fun all(): List<GateEvent> = synchronized(lock) { read().mapNotNull { parse(it)?.event } }

    /** 还没上报出去的那些；上报通道接上之前，这里会一直堆着（上限 MAX_LINES）。 */
    @Synchronized
    fun pending(): List<GateEvent> = synchronized(lock) { read().mapNotNull(::parse)
        .filter { !it.flushed }
        .map { it.event }
    }

    @Synchronized
    fun markFlushed(sent: List<GateEvent> = pending()) = synchronized(lock) {
        val delivered = sent.toSet()
        val rewritten = read().map { line ->
            val parts = line.split('|')
            if (parts.size == 4 && parts[2] == "0" && parse(line)?.event in delivered)
                "${parts[0]}|${parts[1]}|1|${parts[3]}" else line
        }
        write(rewritten)
    }

    private fun parse(line: String): Flushed? {
        val parts = line.split('|')
        if (parts.size != 4) {
            return null
        }
        val kind = runCatching { GateEventKind.valueOf(parts[1]) }.getOrNull() ?: return null
        return Flushed(GateEvent(parts[0].toLongOrNull() ?: return null, kind, parts[3]), parts[2] == "1")
    }

    private fun read(): MutableList<String> = runCatching {
            if (file.exists()) file.readLines().filterTo(mutableListOf()) { it.isNotBlank() } else mutableListOf()
        }.getOrDefault(mutableListOf())

    private fun write(lines: List<String>) {
        runCatching { file.writeText(lines.joinToString("\n") + "\n") }
    }

    private data class Flushed(val event: GateEvent, val flushed: Boolean)

    companion object {
        /** 看门狗写、首页读，两边必须用同一个文件名。 */
        const val FILE_NAME = "gate_events.txt"

        private const val MAX_LINES = 500
    }
}
