package com.quizgate.spike.update

import org.json.JSONObject

object ManifestParser {
    fun parse(text: String, source: String, debug: Boolean): UpdateManifest {
        require(text.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "更新清单过大" }
        val json = JSONObject(text)
        fun integer(name: String): Long {
            val value = json.get(name)
            require(value is Int || value is Long) { "更新字段 $name 必须是整数" }
            return (value as Number).toLong()
        }
        fun string(name: String): String = (json.get(name) as? String) ?: error("更新字段 $name 必须是文本")
        require(integer("schemaVersion") == 1L) { "不支持的更新清单版本" }
        val sdk = integer("minSdk"); require(sdk in 26..100)
        return UpdateManifest(integer("versionCode"), string("versionName"), sdk.toInt(), integer("sizeBytes"),
            string("sha256"), string("apkUrl"), string("notes")).validate(source, debug)
    }
}
