package com.quizgate.spike.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.quizgate.spike.util.SpikeLog

/**
 * 开机自启。这一条不为了抢屏——无障碍服务系统本来就会自己重绑；
 * 它要的是看门狗回来，否则重启到家长打开后台之间那一段没人记账。
 */
class GateBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        SpikeLog.attach(context)
        SpikeLog.record("开机完成，拉起看门狗")
        GateWatchdogService.ensure(context)
    }
}
