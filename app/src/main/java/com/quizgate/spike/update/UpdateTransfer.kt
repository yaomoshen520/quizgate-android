package com.quizgate.spike.update

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.security.MessageDigest

/** No redirects, cookies, application tokens or automatic retries to another origin. */
class UpdateTransfer(private val debug: Boolean) {
    fun manifest(url: String): String = connect(url) { connection ->
        require(connection.getHeaderFieldLong("Content-Length", -1) <= 16384) { "更新清单过大" }
        connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 16384) { "更新清单过大" }; output.write(buffer, 0, n) }
            val bytes = output.toByteArray()
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
    }
    fun download(spec: UpdateManifest, part: File, progress: (Long) -> Unit) {
        part.parentFile?.mkdirs()
        try {
            connect(spec.apkUrl) { connection ->
                val length = connection.getHeaderFieldLong("Content-Length", -1)
                require(length == -1L || length == spec.sizeBytes) { "安装包长度与清单不一致" }
                val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                connection.inputStream.use { input -> FileOutputStream(part).use { output ->
                    val buffer = ByteArray(32768); var lastProgress = 0L
                    while (true) {
                        val read = input.read(buffer); if (read < 0) break
                        count += read; require(count <= spec.sizeBytes) { "安装包超出声明大小" }
                        output.write(buffer, 0, read); digest.update(buffer, 0, read)
                        if (count - lastProgress >= 262144) { progress(count); lastProgress = count }
                    }
                    output.fd.sync()
                } }
                require(count == spec.sizeBytes && hex(digest.digest()) == spec.sha256) { "安装包大小或 SHA-256 校验失败" }
                progress(count)
            }
        } catch (error: Exception) { part.delete(); throw error }
    }
    private fun <T> connect(url: String, read: (HttpURLConnection) -> T): T {
        val connection = UpdatePolicy.url(url, debug).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 10000; connection.readTimeout = 15000
            connection.setRequestProperty("Accept-Encoding", "identity")
            require(connection.responseCode == 200) { "更新服务返回 HTTP ${connection.responseCode}" }
            return read(connection)
        } finally { connection.disconnect() }
    }
    companion object {
        fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(32768); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return hex(digest.digest())
        }
    }
}
