package com.quizgate.spike.monitor

/** A round freezes this policy; changes are read only when the next round starts. */
data class CycleRule(
    val thresholdMs: Long,
    val watchedPackages: Set<String>,
    val graceMs: Long = 120_000L,
    val enabled: Boolean = true,
    val version: Long = 0,
    val gamePackages: Set<String> = emptySet(),
    val warningMs: Long = 0,
    val extensionMs: Long = 0,
    val maxExtensions: Int = 0
) {
    val gameEnabled: Boolean get() = warningMs > 0 && extensionMs in 60_000L..900_000L && maxExtensions in 0..2
    fun isGame(pkg: String?) = pkg != "com.quizgate.spike" && gameEnabled && pkg in gamePackages
    fun watches(pkg: String?) = pkg != "com.quizgate.spike" && enabled && (pkg in watchedPackages || isGame(pkg))
}
