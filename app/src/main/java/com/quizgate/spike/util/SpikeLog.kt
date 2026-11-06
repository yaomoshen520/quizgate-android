package com.quizgate.spike.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SpikeLog {

    const val TAG = "QuizGateSpike"

    private const val MAX_ENTRIES = 80
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val entries = ArrayDeque<String>()
    private var file: File? = null

    /** 部分定制 ROM 会吞掉第三方应用的 logcat，所以同时往应用私有文件写一份。 */
    fun attach(context: Context) {
        if (file != null) {
            return
        }
        file = File(context.filesDir, "spike_log.txt")
    }

    @Synchronized
    fun record(message: String) {
        val line = "${timeFormat.format(Date())} $message"
        Log.i(TAG, line)
        runCatching { file?.appendText("$line\n") }
        if (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
        }
        entries.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()
}
