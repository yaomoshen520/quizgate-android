package com.quizgate.spike.config

import android.content.Context

/** M0 要对比的三种抢屏方式。 */
enum class TriggerMode {
    HOME_START,
    START_ONLY,
    OVERLAY
}

/**
 * M0 只保留会影响验证结果的几个配置，M1 换成 DataStore 并给每个字段默认值。
 */
class SpikeSettings(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var thresholdMs: Long
        get() = prefs.getLong(KEY_THRESHOLD_MS, SPIKE_THRESHOLD_MS)
        set(value) {
            prefs.edit().putLong(KEY_THRESHOLD_MS, value).apply()
        }

    var grade: Int
        get() = prefs.getInt(KEY_GRADE, 2)
        set(value) {
            prefs.edit().putInt(KEY_GRADE, value).apply()
        }

    val targetPackages: Set<String>
        get() = prefs.getStringSet(KEY_TARGET_PACKAGES, DEFAULT_TARGET_PACKAGES) ?: DEFAULT_TARGET_PACKAGES

    var triggerMode: TriggerMode
        get() = runCatching { TriggerMode.valueOf(prefs.getString(KEY_TRIGGER_MODE, TriggerMode.HOME_START.name)!!) }
            .getOrDefault(TriggerMode.HOME_START)
        set(value) {
            prefs.edit().putString(KEY_TRIGGER_MODE, value.name).apply()
        }

    fun cycleTriggerMode(): TriggerMode {
        val values = TriggerMode.values()
        val next = values[(triggerMode.ordinal + 1) % values.size]
        triggerMode = next
        return next
    }

    fun toggleThreshold() {
        thresholdMs = if (thresholdMs == SPIKE_THRESHOLD_MS) REAL_THRESHOLD_MS else SPIKE_THRESHOLD_MS
    }

    /**
     * 服务端地址。默认指向开发局域网里那台跑后端的机器：
     * 明文 HTTP 只在 network_security_config 里对这几个地址放行，
     * 填别的地址会连不上，这是有意的——不给"随手填个 http 域名就一路明文跑"留口子。
     */
    var serverBaseUrl: String
        get() = prefs.getString(KEY_SERVER_BASE_URL, DEFAULT_SERVER_BASE_URL) ?: DEFAULT_SERVER_BASE_URL
        set(value) {
            val trimmed = value.trim().trimEnd('/')
            // 收下之前就规范化：带不带 scheme、结尾有没有斜杠，都会让后面的路径拼成 //api 或 api。
            val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
            prefs.edit().putString(KEY_SERVER_BASE_URL, normalized).apply()
        }

    /**
     * 要不要让服务端阈值压过本机阈值。默认关：M0 那套 20 秒自检验证的是抢屏链路，
     * 一旦开机就被服务端的 30 分钟覆盖，自检就再也测不出东西了，而且没人会怀疑是这里动的。
     */
    var followServerRule: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_SERVER_RULE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FOLLOW_SERVER_RULE, value).apply()
        }

    /** 上报通道的总开关。关掉之后事件照样落盘，只是不发——留给"这台机器只本地跑"的场景。 */
    var reportingEnabled: Boolean
        get() = prefs.getBoolean(KEY_REPORTING_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_REPORTING_ENABLED, value).apply()
        }

    companion object {

        /** 验证链路用的短阈值。 */
        const val SPIKE_THRESHOLD_MS = 20_000L

        /** 真实产品口径：连续 30 分钟。 */
        const val REAL_THRESHOLD_MS = 30 * 60 * 1000L

        /** 后端开发机。换网络环境要在实验台里改，别让这个常量变成唯一出路。 */
        const val DEFAULT_SERVER_BASE_URL = "http://192.168.1.76:8080"

        private const val FILE = "spike_settings"
        private const val KEY_THRESHOLD_MS = "threshold_ms"
        private const val KEY_GRADE = "grade"
        private const val KEY_TARGET_PACKAGES = "target_packages"
        private const val KEY_TRIGGER_MODE = "trigger_mode"
        private const val KEY_SERVER_BASE_URL = "server_base_url"
        private const val KEY_FOLLOW_SERVER_RULE = "follow_server_rule"
        private const val KEY_REPORTING_ENABLED = "reporting_enabled"

        private val DEFAULT_TARGET_PACKAGES = setOf(
            "com.ss.android.ugc.aweme",
            "com.xunmeng.pinduoduo",
            "com.taptap",
            "com.tencent.tmgp.sgame",
            "com.tencent.mobileqq"
        )
    }
}
