package com.quizgate.spike.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.net.Uri
import android.view.ViewTreeObserver
import com.quizgate.spike.util.SpikeLog
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Local diagnostics for Quizgate's process. Does not inspect other apps or upload data. */
object AppDiagnostics {
    private val started = AtomicBoolean()
    private val firstDrawPending = AtomicBoolean(true)
    private val pingPending = AtomicBoolean()
    private val exporting = AtomicBoolean()
    private val cpu = CpuSampler()
    private val window = ResourceWindow()
    private val main = Handler(Looper.getMainLooper())
    private val processStartedAt = SystemClock.elapsedRealtime()
    @Volatile private var metadata = "诊断尚未初始化"
    @Volatile private var resourceSummary = "CPU／内存：等待采样"
    @Volatile private var mainSummary = "主线程延迟：等待采样"
    @Volatile private var startupSummary = "首次绘制：等待样本"
    @Volatile private var exitsSummary = "历史退出：当前系统不支持"
    private var delayedPings = 0L
    private var longestPing = 0L

    fun start(application: Application) {
        if (!started.compareAndSet(false, true)) return
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        metadata = "Quizgate ${info.versionName} ($code) · Android API ${Build.VERSION.SDK_INT}"
        SpikeLog.record("process_start $metadata")
        val worker = Handler(HandlerThread("quizgate-diagnostics", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
        worker.post { readExits(application) }
        val sampler = object : Runnable {
            override fun run() {
                runCatching { sampleResources() }.onFailure { resourceSummary = "CPU／内存：本次采样失败" }
                if (pingPending.compareAndSet(false, true)) {
                    val posted = SystemClock.uptimeMillis()
                    main.post {
                        val delay = (SystemClock.uptimeMillis() - posted).coerceAtLeast(0)
                        longestPing = maxOf(longestPing, delay)
                        if (delay >= 250) delayedPings++
                        mainSummary = "主线程探测：最近 ${delay}ms，最长 ${longestPing}ms，≥250ms 共 $delayedPings 次"
                        if (delay >= 250) SpikeLog.record("main_queue_delay_ms=$delay")
                        pingPending.set(false)
                    }
                }
                worker.postDelayed(this, 30_000)
            }
        }
        worker.postDelayed(sampler, 5_000)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (!firstDrawPending.compareAndSet(true, false)) return
                val view = activity.window.decorView
                val listener = object : ViewTreeObserver.OnDrawListener {
                    private var drawn = false
                    override fun onDraw() {
                        if (drawn) return
                        drawn = true
                        val elapsed = SystemClock.elapsedRealtime() - processStartedAt
                        startupSummary = "Application 初始化至首次绘制：${elapsed}ms（${activity.javaClass.simpleName}）"
                        SpikeLog.record("application_to_first_draw_ms=$elapsed screen=${activity.javaClass.simpleName}")
                        view.post { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this) }
                    }
                }
                view.viewTreeObserver.addOnDrawListener(listener)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun sampleResources() {
        val now = SystemClock.elapsedRealtime()
        val percent = cpu.sample(now, Process.getElapsedCpuTime())
        val runtime = Runtime.getRuntime()
        val heapMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576.0
        val pssMb = Debug.getPss() / 1_024.0
        window.add(percent, pssMb, heapMb)
        val cpuText = percent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "等待第二次采样"
        resourceSummary = String.format(Locale.US, "本进程 CPU：%s（单核口径）\nPSS：%.1f MiB；Java 堆已用：%.1f MiB\n采样时进程已运行：%d 秒",
            cpuText, pssMb, heapMb, (now - processStartedAt) / 1_000)
        SpikeLog.record("resource_sample cpu=$cpuText pss_mib=${String.format(Locale.US, "%.1f", pssMb)} heap_mib=${String.format(Locale.US, "%.1f", heapMb)}")
    }

    private fun readExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        exitsSummary = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (records.isEmpty()) "历史退出：系统暂无记录" else records.joinToString("\n") {
                // No description or trace streams: those may include sensitive exception messages.
                "退出 timestamp=${it.timestamp} reason=${it.reason} status=${it.status} pss_kib=${it.pss}"
            }
        }.getOrDefault("历史退出：读取失败")
        SpikeLog.record(exitsSummary)
    }

    fun summary(): String = listOf(metadata, startupSummary, resourceSummary, mainSummary,
        "资源与主线程每 30 秒采样；主线程延迟不等同 ANR，不能据此计算游戏帧率。",
        window.summary(), OperationMetrics.summary(), NetworkMetrics.process.summary(), exitsSummary, SpikeLog.health()).joinToString("\n\n")

    /** Explicit user export only. A slow document provider never blocks the logger or sampler. */
    fun export(context: Context, destination: Uri, finished: (Boolean) -> Unit) {
        if (!exporting.compareAndSet(false, true)) { finished(false); return }
        val application = context.applicationContext
        val header = "Quizgate 本机诊断\n导出时间：${java.time.Instant.now()}\n\n${summary()}\n\n--- 最近日志（可能包含排障用应用包名）---\n"
        SpikeLog.exportSnapshot { result ->
            if (result.isFailure) {
                exporting.set(false)
                main.post { finished(false) }
            } else Thread({
                val success = runCatching {
                    val output = application.contentResolver.openOutputStream(destination, "wt")
                        ?: error("No export stream")
                    output.use {
                        it.write(header.toByteArray(Charsets.UTF_8))
                        it.write(result.getOrThrow())
                    }
                }.isSuccess
                exporting.set(false)
                main.post { finished(success) }
            }, "quizgate-export").start()
        }
    }
}
