package com.quizgate.spike.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.quizgate.spike.BuildConfig
import com.quizgate.spike.update.AppUpdater
import com.quizgate.spike.update.UpdateState

class UpdateActivity : Activity() {
    private lateinit var updater: AppUpdater
    private lateinit var status: TextView
    private lateinit var address: EditText
    private lateinit var check: Button
    private lateinit var download: Button
    private lateinit var install: Button
    private val observer: (UpdateState) -> Unit = { render(it) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); title = "应用整包更新"; updater = AppUpdater.get(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        body.addView(TextView(this).apply { text = "当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n手动检查、下载与安装。正式更新渠道尚未配置。"; textSize = 17f })
        address = EditText(this).apply { setSingleLine(true); hint = "调试更新清单地址"; setText(updater.address()); visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE }
        body.addView(address); status = TextView(this).apply { textSize = 15f; setPadding(0, 24, 0, 24); setTextIsSelectable(true) }; body.addView(status)
        fun button(label: String, click: () -> Unit) = Button(this).apply { text = label; setOnClickListener { click() }; body.addView(this) }
        check = button("手动检查更新") { updater.check(if (BuildConfig.DEBUG) address.text.toString() else updater.address()) }
        download = button("下载 / 重新下载") { updater.download() }
        install = button("校验并确认安装") { install() }
        button("返回") { finish() }; setContentView(ScrollView(this).apply { addView(body) })
    }
    override fun onStart() { super.onStart(); updater.observe(observer); updater.restore() }
    override fun onStop() { updater.remove(observer); super.onStop() }
    private fun render(state: UpdateState) {
        if (isDestroyed) return
        status.text = state.message + (state.manifest?.let { "\n\n新版本 ${it.versionName}（${it.versionCode}）\n大小 ${it.sizeBytes} 字节；已下载 ${state.downloaded}\n\n${it.notes}" } ?: "")
        address.isEnabled = !state.busy; check.isEnabled = !state.busy
        download.isEnabled = !state.busy && state.manifest != null
        install.isEnabled = !state.busy && state.phase == "ready"
    }
    private fun install() {
        updater.installationBlockReason()?.let { status.text = it; return }
        if (!packageManager.canRequestPackageInstalls()) {
            android.app.AlertDialog.Builder(this).setMessage("需要在系统设置中允许学习闸门安装更新。授权后请返回，再次手动确认安装。")
                .setNegativeButton("稍后", null).setPositiveButton("前往设置") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        .onFailure { status.text = "无法打开安装授权设置，请在系统设置中手动开启。" }
                }.show(); return
        }
        updater.prepareInstall { result ->
            if (isDestroyed || isFinishing || !hasWindowFocus()) return@prepareInstall
            updater.installationBlockReason()?.let { status.text = it; return@prepareInstall }
            result.onSuccess { file ->
                val uri = Uri.Builder().scheme("content").authority("$packageName.updates").appendPath(file.name).build()
                runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("安装更新", uri) }) }
                    .onFailure { status.text = "系统安装器不可用，原应用仍可继续使用。" }
            }.onFailure { status.text = "安装前校验失败，请重新下载。" }
        }
    }
}
