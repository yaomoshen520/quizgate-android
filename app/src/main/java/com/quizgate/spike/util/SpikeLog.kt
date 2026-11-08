package com.quizgate.spike.util

import android.content.Context
import android.util.Log
import com.quizgate.spike.diagnostics.BoundedLogStore
import com.quizgate.spike.diagnostics.LogSanitizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object SpikeLog {

    const val TAG = "QuizGateSpike"

    private const val MAX_ENTRIES = 80
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val entries = ArrayDeque<String>()
    @Volatile private var store: BoundedLogStore? = null
    private val dropped = AtomicLong()
    private val writeFailures = AtomicLong()
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(256), { task -> Thread(task, "quizgate-log").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

    /** 部分定制 ROM 会吞掉第三方应用的 logcat，所以同时往应用私有文件写一份。 */
    @Synchronized
    fun attach(context: Context) {
        if (store == null) store = BoundedLogStore(File(context.filesDir, "diagnostics"))
    }

    @Synchronized
    fun record(message: String) {
        val line = "${timeFormat.format(Date())} ${LogSanitizer.clean(message)}"
        Log.i(TAG, line)
        enqueue { store?.append(line) }
        if (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
        }
        entries.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    private fun enqueue(action: () -> Unit) {
        try {
            writer.execute { runCatching(action).onFailure { writeFailures.incrementAndGet() } }
        } catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
    }

    /** Crash path bypasses the queue. Throwable messages can contain private data. */
    fun recordCrash(thread: Thread, error: Throwable) {
        val trace = buildString {
            append("uncaught threadId=${thread.id} type=${error.javaClass.name}")
            error.stackTrace.take(24).forEach { append(" | at ").append(it) }
        }
        runCatching { store?.append("${System.currentTimeMillis()} ${LogSanitizer.clean(trace)}") }
            .onFailure { writeFailures.incrementAndGet() }
    }

    fun health(): String = "日志：3 × 256 KiB 轮转；队列溢出丢弃 ${dropped.get()} 条，写入失败 ${writeFailures.get()} 次"

    /** Queued after existing writes. Callback runs on the writer; callers switch to UI. */
    fun exportSnapshot(callback: (Result<ByteArray>) -> Unit) {
        try {
            writer.execute { callback(runCatching { store?.snapshot() ?: byteArrayOf() }) }
        } catch (error: RejectedExecutionException) { callback(Result.failure(error)) }
    }
}
