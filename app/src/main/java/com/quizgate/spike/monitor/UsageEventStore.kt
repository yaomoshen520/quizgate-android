package com.quizgate.spike.monitor

import com.quizgate.proto.UsageEvent
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

/** 持久待发队列。编号在写入时固定，重试不换号；确认只删除这一批，保留发送期间的新记录。 */
class UsageEventStore(private val file: File) {
    private val lock = file.canonicalPath.intern()

    fun record(atMs: Long, appPackage: String, playedMs: Long = 0,
        correctCount: Int = 0, wrongCount: Int = 0, interceptionFailed: Boolean = false
    ) = synchronized(lock) {
        require(playedMs >= 0 && correctCount >= 0 && wrongCount >= 0)
        if (playedMs == 0L && correctCount == 0 && wrongCount == 0 && !interceptionFailed) return@synchronized
        val event = UsageEvent.newBuilder().setEventId(UUID.randomUUID().toString())
            .setOccurredAtMs(atMs).setAppPackage(appPackage).setPlayedMs(playedMs)
            .setCorrectCount(correctCount).setWrongCount(wrongCount)
            .setInterceptionFailed(interceptionFailed).build()
        file.parentFile?.mkdirs()
        recoverIncompleteTail()
        FileOutputStream(file, true).use {
            it.write((encode(event) + "\n").toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    }

    fun pending(limit: Int = 100): List<UsageEvent> = synchronized(lock) {
        recoverIncompleteTail()
        if (!file.exists()) emptyList() else file.useLines { lines ->
            lines.filter { it.isNotBlank() }.take(limit).map(::decode).toList()
        }
    }

    fun acknowledge(sent: List<UsageEvent>) = synchronized(lock) {
        if (!file.exists() || sent.isEmpty()) return@synchronized
        recoverIncompleteTail()
        val ids = sent.map { it.eventId }.toSet()
        val temp = File(file.path + ".tmp")
        // 同目录临时文件 + 原子替换。中途退出最多重传，不会先清队列再发现写不出来。
        FileOutputStream(temp).use { output ->
            file.useLines { lines -> lines.filter { it.isNotBlank() }.forEach { line ->
                if (decode(line).eventId !in ids) output.write((line + "\n").toByteArray(Charsets.UTF_8))
            } }
            output.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }

    private fun encode(event: UsageEvent) = Base64.getEncoder().encodeToString(event.toByteArray())
    private fun decode(line: String) = UsageEvent.parseFrom(Base64.getDecoder().decode(line))

    /** 换行是提交边界。进程在一次 append 中途退出，只回收尚未提交的尾巴，保留此前完整记录。 */
    private fun recoverIncompleteTail() {
        if (!file.exists() || file.length() == 0L) return
        RandomAccessFile(file, "rw").use { data ->
            var position = data.length() - 1
            data.seek(position)
            if (data.readByte().toInt() == 10) return
            while (position >= 0) {
                data.seek(position)
                if (data.readByte().toInt() == 10) break
                position--
            }
            data.setLength(position + 1)
            data.fd.sync()
        }
    }

    companion object {
        const val FILE_NAME = "usage_events.txt"
    }
}
