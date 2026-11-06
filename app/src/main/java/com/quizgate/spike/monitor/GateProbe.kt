package com.quizgate.spike.monitor

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import com.quizgate.spike.service.GateAccessibilityService

/**
 * 闸门健康状况的唯一探测入口。首页和看门狗必须读同一份判断，
 * 否则会出现"界面上写着已开启、看门狗却在报已关闭"这种两边都对不上的告警。
 */
object GateProbe {

    private const val SERVICE_PACKAGE = "com.quizgate.spike"
    private const val SERVICE_CLASS = "GateAccessibilityService"

    /** 无障碍开关是否还勾着我们。读的是系统设置，不是我们自己记的状态。 */
    fun accessibilityEnabled(context: Context): Boolean =
        enabledServices(context).let { it.contains(SERVICE_PACKAGE) && it.contains(SERVICE_CLASS) }

    /** 开关勾着 ≠ 服务真的绑进了进程，这一条只有服务自己知道。 */
    fun serviceBound(): Boolean = GateAccessibilityService.instance != null

    fun overlayGranted(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    /**
     * 家长在设置里把本应用的通知一勾关，孩子就再也看不到"闸门还在盯着"那条常驻提示——
     * 拦人不受影响，但威慑没了，所以这一跳要单独留痕。
     * 读的是 areNotificationsEnabled（就是那个总开关的真实状态），不是我们自己记的布尔；
     * 33 起它还要求运行时授权，所以首页那一行 + onCreate 的那次申请都缺一不可。
     */
    fun notificationsGranted(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.areNotificationsEnabled()
    }.getOrDefault(false)

    fun health(context: Context): GateHealth = GateHealth(
        accessibilityOn = accessibilityEnabled(context),
        serviceBound = serviceBound(),
        overlayOn = overlayGranted(context)
    )

    private fun enabledServices(context: Context): String = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    }.getOrDefault("") ?: ""
}
