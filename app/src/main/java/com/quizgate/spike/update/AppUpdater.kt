package com.quizgate.spike.update

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.quizgate.spike.BuildConfig
import com.quizgate.spike.config.PrefsSessionStore
import com.quizgate.spike.diagnostics.OperationMetrics
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

data class UpdateState(val phase: String = "idle", val message: String = "仅手动检查、下载和确认安装；尚未配置正式更新服务。",
    val manifest: UpdateManifest? = null, val downloaded: Long = 0, val busy: Boolean = false)

class AppUpdater private constructor(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(UpdateState) -> Unit>()
    private val directory = File(context.filesDir, "updates").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val verifier = ApkVerifier(context)
    private val transfer = UpdateTransfer(BuildConfig.DEBUG)
    @Volatile var state = UpdateState(); private set
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean()
    fun address(): String = if (BuildConfig.DEBUG) prefs.getString("debug_manifest", BuildConfig.UPDATE_MANIFEST_URL) ?: "" else BuildConfig.UPDATE_MANIFEST_URL
    fun observe(listener: (UpdateState) -> Unit) { listeners.add(listener); listener(state) }
    fun remove(listener: (UpdateState) -> Unit) { listeners.remove(listener) }
    private fun publish(value: UpdateState) {
        state = value
        main.post { listeners.forEach { listener -> runCatching { listener(state) } } }
    }
    private fun run(phase: String, operation: () -> UpdateState) {
        if (!inFlight.compareAndSet(false, true)) return
        publish(state.copy(phase = phase, busy = true, message = if (phase == "checking") "正在检查更新…" else "正在处理…"))
        worker.execute {
            try { publish(operation()) }
            catch (e: Exception) { publish(state.copy(phase = "failed", busy = false, message = "本次操作未完成：${safeError(e)}")) }
            finally { inFlight.set(false) }
        }
    }
    fun check(debugAddress: String = address()) = run("checking") {
        val source = if (BuildConfig.DEBUG) debugAddress.trim() else address()
        require(source.isNotEmpty()) { "更新服务尚未配置" }; UpdatePolicy.url(source, BuildConfig.DEBUG)
        if (BuildConfig.DEBUG) check(prefs.edit().putString("debug_manifest", source).commit()) { "更新地址保存失败" }
        val text = OperationMetrics.measure("update_check") { transfer.manifest(source) }
        val spec = ManifestParser.parse(text, source, BuildConfig.DEBUG)
        if (spec.versionCode <= verifier.currentCode()) return@run UpdateState("current", "已是当前版本或清单版本更旧。")
        UpdatePolicy.eligible(spec, verifier.currentCode(), Build.VERSION.SDK_INT)
        persist(source, text)
        val cached = file(spec)
        val ready = cached.exists() && runCatching { verifier.verify(cached, spec) }.isSuccess
        UpdateState(if (ready) "ready" else "available", if (ready) "已恢复校验通过的安装包，可手动确认安装。" else "发现新版本，请查看说明后手动下载。", spec, if (ready) spec.sizeBytes else 0)
    }
    fun restore() = run("restoring") {
        val saved = File(directory, "candidate.json")
        if (!saved.exists()) return@run UpdateState()
        require(saved.length() <= 32768) { "本地更新记录过大" }
        val json = JSONObject(saved.readText()); val spec = ManifestParser.parse(json.getString("manifest"), json.getString("source"), BuildConfig.DEBUG)
        if (spec.versionCode <= verifier.currentCode()) return@run UpdateState("current", "当前安装版本已更新。")
        UpdatePolicy.eligible(spec, verifier.currentCode(), Build.VERSION.SDK_INT)
        val ready = file(spec).exists() && runCatching { verifier.verify(file(spec), spec) }.isSuccess
        UpdateState(if (ready) "ready" else "available", if (ready) "已恢复下载记录并重新校验安装包。" else "有待下载更新，可重试下载。", spec, if (ready) spec.sizeBytes else 0)
    }
    fun download() = run("downloading") {
        val spec = state.manifest ?: error("请先检查更新")
        UpdatePolicy.eligible(spec, verifier.currentCode(), Build.VERSION.SDK_INT)
        val part = File(directory, "download.part.apk")
        try {
            OperationMetrics.measure("update_download") { transfer.download(spec, part) { bytes -> publish(UpdateState("downloading", "正在下载…", spec, bytes, true)) } }
            OperationMetrics.measure("update_verify") { verifier.verify(part, spec) }
            Files.move(part.toPath(), file(spec).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}\\.apk")) && it.name != file(spec).name }
                ?.sortedByDescending { it.lastModified() }?.drop(1)?.forEach { it.delete() }
            UpdateState("ready", "包名、版本、大小、摘要和当前签名已校验；系统安装器还会再次验证。", spec, spec.sizeBytes)
        } finally { part.delete() }
    }
    fun prepareInstall(result: (Result<File>) -> Unit) {
        if (!inFlight.compareAndSet(false, true)) return
        publish(state.copy(phase = "verifying", busy = true, message = "正在重新校验待安装文件…"))
        worker.execute {
            val verified = runCatching { val spec = state.manifest ?: error("请先下载更新"); OperationMetrics.measure("update_verify") { verifier.verify(file(spec), spec) }; file(spec) }
            publish(state.copy(phase = if (verified.isSuccess) "ready" else "failed", busy = false, message = if (verified.isSuccess) "等待手动确认安装。" else "安装前重新校验失败，请重新下载。"))
            inFlight.set(false); main.post { result(verified) }
        }
    }
    fun installationBlockReason(): String? = runCatching {
        if (com.quizgate.spike.service.GateAccessibilityService.instance?.blocksUpdateInstallation() == true || com.quizgate.spike.ui.QuizActivity.blocksUpdateInstallation()) return@runCatching "游戏周期或答题进行中，请完成本轮后再安装。"
        val snapshot = PrefsSessionStore(context).load()
        if (snapshot?.gameCycle == true) "本轮游戏尚未结束，请先完成本轮答题再安装。" else null
    }.getOrDefault("无法确认本轮状态，暂缓安装。")
    fun verifiedForProvider(hash: String): File {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val spec = state.manifest ?: run {
            val saved = File(directory, "candidate.json"); require(saved.length() <= 32768)
            val json = JSONObject(saved.readText()); ManifestParser.parse(json.getString("manifest"), json.getString("source"), BuildConfig.DEBUG)
        }
        require(spec.sha256 == hash); val file = file(spec); verifier.verify(file, spec); return file
    }
    private fun file(spec: UpdateManifest): File = File(directory, spec.sha256 + ".apk")
    private fun persist(source: String, manifest: String) {
        val temp = File(directory, "candidate.tmp")
        FileOutputStream(temp).use { it.write(JSONObject().put("source", source).put("manifest", manifest).toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temp.toPath(), File(directory, "candidate.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun safeError(e: Exception): String = when (e) {
        is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf { !it.contains(Regex("https?://|token|password", RegexOption.IGNORE_CASE)) }?.take(120) ?: "数据校验失败"
        else -> "网络、文件或系统操作失败，请稍后重试。"
    }
    companion object {
        @Volatile private var instance: AppUpdater? = null
        fun get(context: Context): AppUpdater = instance ?: synchronized(this) { instance ?: AppUpdater(context.applicationContext).also { instance = it } }
    }
}
