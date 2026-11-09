package com.quizgate.spike.update

import java.net.URI

data class UpdateManifest(val versionCode: Long, val versionName: String, val minSdk: Int,
    val sizeBytes: Long, val sha256: String, val apkUrl: String, val notes: String) {
    fun validate(manifestUrl: String, debug: Boolean): UpdateManifest {
        require(versionCode > 0 && versionName.isNotBlank() && versionName.length <= 80) { "更新版本信息无效" }
        require(minSdk in 26..100 && sizeBytes in 1..MAX_APK_BYTES && sha256.matches(Regex("[0-9a-f]{64}"))) { "更新大小、摘要或系统要求无效" }
        require(notes.length <= 4000) { "更新说明过长" }
        val source = UpdatePolicy.url(manifestUrl, debug); val apk = UpdatePolicy.url(apkUrl, debug)
        require(source.scheme == apk.scheme && source.host.equals(apk.host, true) && source.port == apk.port) { "本版仅接受与更新清单同源的安装包" }
        return this
    }
    companion object { const val MAX_APK_BYTES = 200L * 1024 * 1024 }
}

object UpdatePolicy {
    fun identity(spec: UpdateManifest, currentPackage: String, actualPackage: String, actualVersion: Long, currentSigners: Set<String>, actualSigners: Set<String>) {
        require(currentPackage == actualPackage && spec.versionCode == actualVersion) { "安装包应用身份或版本与清单不一致" }
        require(currentSigners.isNotEmpty() && actualSigners == currentSigners) { "安装包签名与当前应用不一致" }
    }
    fun url(value: String, debug: Boolean): URI {
        val u = URI(value)
        require(!u.host.isNullOrBlank() && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null && u.port in -1..65535) { "更新地址无效，不接受账号、查询参数或片段" }
        val local = u.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "192.168.1.76")
        require(u.scheme == "https" || debug && u.scheme == "http" && local) { "更新地址需要 HTTPS；调试版仅允许已声明的本机地址" }
        return u
    }
    fun eligible(manifest: UpdateManifest, installedCode: Long, sdk: Int) {
        require(manifest.versionCode > installedCode) { "安装包版本没有高于当前版本" }
        require(manifest.minSdk <= sdk) { "此更新不支持当前 Android 版本" }
    }
}
