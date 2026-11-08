package com.quizgate.spike

import android.app.Application
import android.os.Process
import com.quizgate.spike.diagnostics.AppDiagnostics
import com.quizgate.spike.util.SpikeLog
import kotlin.system.exitProcess

class QuizgateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SpikeLog.attach(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { runCatching { SpikeLog.recordCrash(thread, error) } }
            finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else { Process.killProcess(Process.myPid()); exitProcess(10) }
            }
        }
        runCatching { AppDiagnostics.start(this) }
            .onFailure { SpikeLog.record("diagnostics_init_failed type=${it.javaClass.simpleName}") }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        SpikeLog.record("memory_trim level=$level")
    }
}
