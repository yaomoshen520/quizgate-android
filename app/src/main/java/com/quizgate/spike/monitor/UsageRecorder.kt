package com.quizgate.spike.monitor

import android.content.Context
import com.quizgate.spike.util.SpikeLog
import java.io.File
import java.util.concurrent.Executors

/** 无障碍主线程只提交观测值，磁盘同步与队列整理在后台完成。所有实例共用一个写入线程。 */
class UsageRecorder(context: Context) {
    private val store = UsageEventStore(File(context.filesDir, UsageEventStore.FILE_NAME))

    fun record(atMs: Long, appPackage: String, playedMs: Long = 0,
        correctCount: Int = 0, wrongCount: Int = 0, interceptionFailed: Boolean = false
    ) {
        writer.execute {
            runCatching {
                store.record(atMs, appPackage, playedMs, correctCount, wrongCount, interceptionFailed)
            }.onFailure { SpikeLog.record("用量落盘失败：${it.message}") }
        }
    }

    private companion object {
        val writer = Executors.newSingleThreadExecutor()
    }
}
