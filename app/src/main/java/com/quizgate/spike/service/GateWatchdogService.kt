package com.quizgate.spike.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.pm.ServiceInfo
import com.quizgate.spike.R
import com.quizgate.spike.config.SpikeSettings
import com.quizgate.spike.monitor.GateEventKind
import com.quizgate.spike.monitor.GateEventStore
import com.quizgate.spike.monitor.GateHealth
import com.quizgate.spike.monitor.GateProbe
import com.quizgate.spike.net.ReportChannel
import com.quizgate.spike.util.SpikeLog
import java.io.File

/**
 * 常驻看门狗。存在的唯一理由：无障碍开关一旦被关，服务自己就没了，
 * 那时如果没有任何代码还活着，"关的那一刻"就永远没人知道。
 *
 * 它只负责发现与落盘，不负责拦人——拦人是闸门自己的事。
 */
class GateWatchdogService : Service() {

    private lateinit var events: GateEventStore
    private lateinit var reporter: ReportChannel
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var previous: GateHealth? = null
    private var previousNotifications: Boolean? = null

    private val ticker = object : Runnable {
        override fun run() {
            checkOnce()
            // 紧跟着探测：探测刚把"闸门被关了"写进台账，同一轮就把它发出去。
            // 隔一轮再发的话，孩子关完开关立刻长按电源键，家长收到的时间会偏一整周期。
            reporter.tick()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        events = GateEventStore(File(filesDir, GateEventStore.FILE_NAME))
        reporter = ReportChannel(applicationContext, events, SpikeSettings(applicationContext))
        prefs = getSharedPreferences(FILE, Context.MODE_PRIVATE)
        startForegroundInternal()
        detectGap()
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): Nothing? = null

    /**
     * 心跳断了一个洞 = 进程这段时间根本不在。force-stop、崩溃、被清理都会留下这个洞，
     * 而它们恰恰都是孩子拆闸门时会用的动作。
     */
    private fun detectGap() {
        val lastAlive = prefs.getLong(KEY_LAST_ALIVE, 0L)
        val now = System.currentTimeMillis()
        if (lastAlive <= 0L || now - lastAlive < STALE_MS) {
            return
        }
        val gapMs = now - lastAlive
        record(GateEventKind.GATE_INTERRUPTED, "闸门有 ${describe(gapMs)} 没有心跳，可能被清理或强制停止")
        SpikeLog.record("看门狗发现心跳空洞 ${gapMs}ms")
    }

    private fun checkOnce() {
        val current = GateProbe.health(this)
        val before = previous
        previous = current
        prefs.edit().putLong(KEY_LAST_ALIVE, System.currentTimeMillis()).apply()
        val baseline = before ?: lastKnownHealth() ?: return adopt(current)
        transition(baseline, current)
        prefs.edit().putString(KEY_LAST_HEALTH, current.encode()).apply()
        updateNotification(current)
        watchNotifications()
    }

    /**
     * 通知栏这一条是孩子唯一能看见的"闸门还在盯着"，所以它被关掉本身就是一次动手。
     * 关通知拦不住抢屏，但它让孩子确认自己拆掉了提示——
     * 而那个确认动作恰好就是我们要报给家长的。
     */
    private fun watchNotifications() {
        val granted = GateProbe.notificationsGranted(this)
        val before = previousNotifications
        previousNotifications = granted
        if (before == null || before == granted) {
            return
        }
        if (granted) {
            record(GateEventKind.NOTIFICATIONS_RESTORED, "通知权限恢复，掉线提示重新可见")
        } else {
            record(GateEventKind.NOTIFICATIONS_OFF, "通知被关了：闸门掉线时孩子那边不会有任何提示")
            SpikeLog.record("通知被关，常驻提示消失")
        }
    }

    /** 第一次采样：不报"掉了"，只把当前状态记下来当基线，否则每次开机都刷一条假告警。 */
    private fun adopt(current: GateHealth) {
        prefs.edit().putString(KEY_LAST_HEALTH, current.encode()).apply()
        updateNotification(current)
    }

    private fun lastKnownHealth(): GateHealth? = GateHealth.decode(prefs.getString(KEY_LAST_HEALTH, null))

    private fun transition(before: GateHealth, after: GateHealth) {
        if (before.accessibilityOn && !after.accessibilityOn) {
            record(GateEventKind.ACCESSIBILITY_OFF, "看时长的无障碍服务被关掉了，闸门已经拦不住玩")
            SpikeLog.record("无障碍被关，闸门失效")
        }
        if (!before.accessibilityOn && after.accessibilityOn) {
            record(GateEventKind.ACCESSIBILITY_RESTORED, "无障碍服务恢复，闸门重新生效")
        }
        if (before.accessibilityOn && after.accessibilityOn && !after.serviceBound) {
            record(GateEventKind.ACCESSIBILITY_OFF, "无障碍开关还勾着但服务没绑上，闸门拦不住")
        }
        if (before.overlayOn && !after.overlayOn) {
            record(GateEventKind.OVERLAY_OFF, "悬浮窗权限被撤掉，题目盖不住屏幕")
            SpikeLog.record("悬浮窗权限被撤")
        }
        if (!before.overlayOn && after.overlayOn) {
            record(GateEventKind.OVERLAY_RESTORED, "悬浮窗权限恢复")
        }
    }

    private fun record(kind: GateEventKind, detail: String) {
        events.append(System.currentTimeMillis(), kind, detail)
    }

    private fun updateNotification(health: GateHealth) {
        val text = when {
            health.accessibilityOn && health.serviceBound && health.overlayOn ->
                getString(R.string.watchdog_ok)
            !health.accessibilityOn || !health.serviceBound ->
                getString(R.string.watchdog_accessibility_off)
            else -> getString(R.string.watchdog_overlay_off)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, build(text))
    }

    private fun startForegroundInternal() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.watchdog_channel), NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                build(getString(R.string.watchdog_ok)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, build(getString(R.string.watchdog_ok)))
        }
    }

    private fun build(text: String): Notification =
        Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_app_mark)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun describe(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes >= 60 -> "${minutes / 60} 小时"
            minutes >= 1 -> "$minutes 分钟"
            else -> "${ms / 1000L} 秒"
        }
    }

    companion object {
        private const val FILE = "gate_watchdog"
        private const val CHANNEL = "gate_status"
        private const val KEY_LAST_ALIVE = "last_alive_ms"
        private const val KEY_LAST_HEALTH = "last_health"
        private const val NOTIFICATION_ID = 4201

        /** 30 秒一查：孩子关开关到系统真正拦不住之间本来就有一截，再密也只是多耗电。 */
        private const val CHECK_MS = 30_000L

        /** 心跳比这个间隔还久没出现，就认定中间进程不在过。 */
        private const val STALE_MS = 120_000L

        /** 各处叫它起来；已经在跑就是空操作。 */
        fun ensure(context: Context) {
            val intent = Intent(context, GateWatchdogService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
