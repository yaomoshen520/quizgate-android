package com.quizgate.spike.monitor

/**
 * 闸门此刻的健康状况。三个信号分开看是有原因的：
 * 无障碍开关被关（accessibilityOn=false）和开关还勾着但服务没绑上（serviceBound=false）
 * 是两种不同的失效，家长看到的解释不一样，上报的严重程度也一样。
 */
data class GateHealth(
    val accessibilityOn: Boolean,
    val serviceBound: Boolean,
    val overlayOn: Boolean
) {
    /** 只要有一条不满足，抢屏就随时可能失灵。 */
    val degraded: Boolean
        get() = !(accessibilityOn && serviceBound && overlayOn)

    /** 三个布尔压成三位字符，够看门狗把"上次是什么状态"存进 prefs。 */
    fun encode(): String =
        "${accessibilityOn.bit()}${serviceBound.bit()}${overlayOn.bit()}"

    companion object {
        fun decode(value: String?): GateHealth? =
            if (value == null || value.length != 3) {
                null
            } else {
                GateHealth(value[0] == '1', value[1] == '1', value[2] == '1')
            }
    }
}

private fun Boolean.bit(): Int = if (this) 1 else 0

enum class GateEventKind {
    GAME_WARNING,
    GAME_EXTENDED,
    GAME_FORCED,
    GAME_WARNING_FAILED,
    ACCESSIBILITY_OFF,
    ACCESSIBILITY_RESTORED,
    OVERLAY_OFF,
    OVERLAY_RESTORED,
    NOTIFICATIONS_OFF,
    NOTIFICATIONS_RESTORED,
    GATE_INTERRUPTED
}

/**
 * 一条要落盘、将来要发给家长的事件。atMs 用墙上时钟，因为家长看的就是这个时间；
 * 单调时钟只适合算时长，不适合当证据给别人看。
 */
data class GateEvent(
    val atMs: Long,
    val kind: GateEventKind,
    val detail: String
)
