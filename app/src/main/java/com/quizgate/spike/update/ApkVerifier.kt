package com.quizgate.spike.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

class ApkVerifier(private val context: Context) {
    @Suppress("DEPRECATION") private val flags: Int get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION") private fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION") private fun signers(info: PackageInfo): Set<String> {
        val values = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return values?.map { UpdateTransfer.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet() ?: emptySet()
    }
    fun currentCode(): Long = code(context.packageManager.getPackageInfo(context.packageName, flags))
    fun verify(file: File, spec: UpdateManifest) {
        UpdatePolicy.eligible(spec, currentCode(), Build.VERSION.SDK_INT)
        require(file.length() == spec.sizeBytes && UpdateTransfer.sha256(file) == spec.sha256) { "缓存安装包完整性校验失败" }
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("安装包无法解析")
        require(archive.applicationInfo?.minSdkVersion == spec.minSdk) { "安装包最低系统版本与清单不一致" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        UpdatePolicy.identity(spec, context.packageName, archive.packageName, code(archive), signers(installed), signers(archive))
        // Android's installer remains the final APK signature verifier; this gate never enables a different signer.
    }
}
