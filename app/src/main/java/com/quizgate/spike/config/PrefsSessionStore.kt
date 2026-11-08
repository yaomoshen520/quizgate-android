package com.quizgate.spike.config

import android.content.Context
import com.quizgate.spike.monitor.SessionStore
import com.quizgate.spike.monitor.CycleRule
import com.quizgate.spike.monitor.ParentExtension
import org.json.JSONObject
import org.json.JSONArray

/**
 * M0 阶段用 SharedPreferences 落盘会话快照，M1 换成 DataStore。
 * 空前台包用占位串表示，因为 SharedPreferences 存不了 null。
 */
class PrefsSessionStore(context: Context) : SessionStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): SessionStore.Snapshot? {
        if (!prefs.contains(KEY_ACCUMULATED_MS)) {
            return null
        }
        val storedPackage = prefs.getString(KEY_FOREGROUND, null)
        return SessionStore.Snapshot(
            foregroundPackage = storedPackage?.takeIf { it != NO_PACKAGE },
            accumulatedMs = prefs.getLong(KEY_ACCUMULATED_MS, 0L),
            wallMs = prefs.getLong(KEY_WALL_MS, 0L),
            monotonicMs = prefs.getLong(KEY_MONOTONIC_MS, 0L),
            bootId = prefs.getLong(KEY_BOOT_ID, Long.MIN_VALUE),
            cycleRule = prefs.getString("cycle_rule", null)?.let { text ->
                runCatching {
                    val rule = JSONObject(text)
                    fun packages(key: String): Set<String> {
                        val array = rule.getJSONArray(key)
                        return (0 until array.length()).map { array.getString(it) }.toSet()
                    }
                    CycleRule(rule.getLong("threshold"), packages("watched"), rule.getLong("grace"),
                        rule.getBoolean("enabled"), rule.getLong("version"), packages("games"),
                        rule.getLong("warning"), rule.getLong("extension"), rule.getInt("max"))
                }.getOrNull()
            },
            extensionsUsed = prefs.getInt("extensions_used", 0),
            extendedMs = prefs.getLong("extended_ms", 0),
            gameCycle = prefs.getBoolean("game_cycle", false),
            cycleId = prefs.getString("cycle_id", "") ?: "",
            parentExtension = prefs.getString("parent_stage", null)?.let { stage ->
                runCatching { ParentExtension(prefs.getString("parent_request_id", "") ?: "", ParentExtension.Stage.valueOf(stage),
                    prefs.getString("parent_package", "") ?: "") }.getOrNull()
            }
        )
    }

    override fun save(snapshot: SessionStore.Snapshot) {
        val rule = snapshot.cycleRule?.let {
            JSONObject().put("threshold", it.thresholdMs).put("watched", JSONArray(it.watchedPackages.toList()))
                .put("grace", it.graceMs).put("enabled", it.enabled).put("version", it.version)
                .put("games", JSONArray(it.gamePackages.toList())).put("warning", it.warningMs)
                .put("extension", it.extensionMs).put("max", it.maxExtensions).toString()
        }
        check(prefs.edit()
            .putString(KEY_FOREGROUND, snapshot.foregroundPackage ?: NO_PACKAGE)
            .putLong(KEY_ACCUMULATED_MS, snapshot.accumulatedMs)
            .putLong(KEY_WALL_MS, snapshot.wallMs)
            .putLong(KEY_MONOTONIC_MS, snapshot.monotonicMs)
            .putLong(KEY_BOOT_ID, snapshot.bootId)
            .putString("cycle_rule", rule)
            .putInt("extensions_used", snapshot.extensionsUsed)
            .putLong("extended_ms", snapshot.extendedMs)
            .putBoolean("game_cycle", snapshot.gameCycle)
            .putString("cycle_id", snapshot.cycleId)
            .putString("parent_stage", snapshot.parentExtension?.stage?.name)
            .putString("parent_request_id", snapshot.parentExtension?.requestId)
            .putString("parent_package", snapshot.parentExtension?.packageName)
            .commit()) { "会话状态保存失败" }
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE = "spike_session"
        private const val NO_PACKAGE = "-"
        private const val KEY_FOREGROUND = "foreground"
        private const val KEY_ACCUMULATED_MS = "accumulated_ms"
        private const val KEY_WALL_MS = "wall_ms"
        private const val KEY_MONOTONIC_MS = "monotonic_ms"
        private const val KEY_BOOT_ID = "boot_id"
    }
}
