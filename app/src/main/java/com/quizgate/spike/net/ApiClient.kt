package com.quizgate.spike.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设备端的网络出口（取题、判分、上报）。只走 protobuf：后端没有 JSON 层，
 * 这里想发 JSON 只会拿到 415，而且是在孩子把闸门弄掉之后才发现，代价太大。
 *
 * 所有失败都收敛成 [ReportUnavailable]：调用方唯一的正确反应是"这批先留着，下次再试"，
 * 所以不值得把 IOException/HTTP 状态码这些区别往上摊。唯一的例外是 401——
 * 令牌被服务端认定无效，重试一万次也不会好，只能重新登记。
 */
class ApiClient(private val baseUrl: String) {

    /** @param token 设备令牌；enroll 这一步还没有令牌，传空串就不带头。 */
    fun post(path: String, token: String, body: ByteArray): ByteArray {
        val connection = URL("$baseUrl/api/device/$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", CONTENT_TYPE)
            connection.setRequestProperty("Accept", CONTENT_TYPE)
            if (token.isNotBlank()) {
                connection.setRequestProperty(TOKEN_HEADER, token)
            }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            when {
                status in 200..299 -> connection.inputStream.use { it.readBytes() }
                status == 401 || status == 403 -> throw ReportUnavailable("$path 认定令牌无效（http=$status）", true)
                else -> throw ReportUnavailable("$path 被拒（http=$status）", false)
            }
        } catch (e: ReportUnavailable) {
            throw e
        } catch (e: IOException) {
            // 包括"该地址不允许明文 HTTP"：network_security_config 拦下来时抛的就是 IOException，
            // 所以这一句必须带上地址，否则现场只看到一句未连接而没人想到是安全配置。
            throw ReportUnavailable("连不上 $baseUrl：${e.message}", false)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * @param needsReenroll 重试没有意义，必须先重新 enroll。单独标出来而不是再开一个子类：
     *   401 一直重试的结局是"看起来像网络很差"，这是现场最难查的一种失败。
     */
    class ReportUnavailable(message: String, val needsReenroll: Boolean) : Exception(message)

    companion object {
        const val CONTENT_TYPE = "application/x-protobuf"
        const val TOKEN_HEADER = "X-Quizgate-Token"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 8_000
    }
}
