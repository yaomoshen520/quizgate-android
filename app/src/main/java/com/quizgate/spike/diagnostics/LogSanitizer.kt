package com.quizgate.spike.diagnostics

/** Apply before logcat, memory or disk. Bodies must never be logged. */
object LogSanitizer {
    private val url = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
    private val bearer = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val secret = Regex(
        "(?i)((?:X-Quizgate-Token|device[_-]?token|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|bind[_-]?code|绑定码|密码)[\\\"']?\\s*[:=：]?\\s*[\\\"']?)([^\\s,;，；\\\"'}]+)"
    )

    fun clean(message: String): String {
        var safe = message.take(8_192).replace(url, "[url]").replace(bearer, "Bearer [redacted]")
        safe = secret.replace(safe) { it.groupValues[1] + "[redacted]" }
        return safe.replace("\r", "\\r").replace("\n", "\\n").take(2_048)
    }
}
