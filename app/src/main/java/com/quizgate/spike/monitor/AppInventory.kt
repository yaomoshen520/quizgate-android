package com.quizgate.spike.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import com.quizgate.proto.AppInventoryRequest
import com.quizgate.proto.InstalledApp

object AppInventory {
    @Suppress("DEPRECATION")
    fun collect(context: Context, consent: Boolean): AppInventoryRequest {
        val request = AppInventoryRequest.newBuilder().setConsent(consent)
            .setCoverage(if (consent) "个人资料下系统允许查询的应用" else "尚未同意采集")
        if (!consent) return request.build()
        val pm = context.packageManager
        pm.getInstalledApplications(0).sortedBy { it.packageName }.forEach { info ->
            request.addApps(InstalledApp.newBuilder().setPackageName(info.packageName)
                .setLabel(pm.getApplicationLabel(info).toString().take(160))
                .setSystemGame(info.category == ApplicationInfo.CATEGORY_GAME))
        }
        return request.build()
    }
}
