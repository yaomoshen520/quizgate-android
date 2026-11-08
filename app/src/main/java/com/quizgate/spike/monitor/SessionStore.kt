package com.quizgate.spike.monitor

/**
 * 会话状态的落盘接缝。纯 JVM 接口，便于单测用假实现，Android 侧由 SharedPreferences 实现。
 */
interface SessionStore {

    fun load(): Snapshot?

    fun save(snapshot: Snapshot)

    fun clear()

    /**
     * @param foregroundPackage 上次已知的前台目标包；null 表示上次不在目标应用里
     * @param accumulatedMs 已累计的娱乐时长
     * @param wallMs 写盘时的绝对时间，用于判断这段状态有多"旧"
     * @param monotonicMs 写盘时的单调时钟读数，同一次开机内可直接比差值
     * @param bootId 开机标识（绝对时间减单调时间）；变了就说明重启过或改过系统时间
     */
    data class Snapshot(
        val foregroundPackage: String?,
        val accumulatedMs: Long,
        val wallMs: Long,
        val monotonicMs: Long,
        val bootId: Long,
        val cycleRule: CycleRule? = null,
        val extensionsUsed: Int = 0,
        val extendedMs: Long = 0,
        val gameCycle: Boolean = false,
        val cycleId: String = "",
        val parentExtension: ParentExtension? = null
    )
}
