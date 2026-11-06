package com.quizgate.spike.config

import android.content.Context
import com.quizgate.spike.monitor.SessionStore

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
            bootId = prefs.getLong(KEY_BOOT_ID, Long.MIN_VALUE)
        )
    }

    override fun save(snapshot: SessionStore.Snapshot) {
        prefs.edit()
            .putString(KEY_FOREGROUND, snapshot.foregroundPackage ?: NO_PACKAGE)
            .putLong(KEY_ACCUMULATED_MS, snapshot.accumulatedMs)
            .putLong(KEY_WALL_MS, snapshot.wallMs)
            .putLong(KEY_MONOTONIC_MS, snapshot.monotonicMs)
            .putLong(KEY_BOOT_ID, snapshot.bootId)
            .apply()
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
