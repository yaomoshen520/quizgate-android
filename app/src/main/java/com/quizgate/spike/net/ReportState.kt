package com.quizgate.spike.net

import android.content.Context

/**
 * 上报通道最后一次尝试的结果，写进 SharedPreferences 供首页读。
 *
 * 为什么不直接读内存里的字段：首页和看门狗是两套生命周期，
 * 进程被清理之后内存归零，而"上次是什么时候成功的"恰恰是那时候最需要看的一条。
 */
class ReportState(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun recordSent(attempted: Int, accepted: Int, duplicated: Int) {
        write("已发 $attempted 条（新收 $accepted，重复 $duplicated）", true)
    }

    /** 没有待发记录也是"通道的状态"：它让首页能区分"没问题"和"还没试过"。 */
    fun recordIdle() {
        write("连接正常，没有待发的事件", true)
    }

    fun recordFailed(reason: String) {
        write("没发出去：$reason", false)
    }

    private fun write(message: String, ok: Boolean) {
        prefs.edit()
            .putLong(KEY_ATTEMPT_MS, System.currentTimeMillis())
            .putString(KEY_MESSAGE, message)
            .putBoolean(KEY_OK, ok)
            .apply()
    }

    /** 首页那一行。没尝试过的时候要说"还没试过"，不能说成成功。 */
    fun describe(): String =
        prefs.getString(KEY_MESSAGE, null) ?: "还没试过上报"

    /** 让界面按状态取色，而不是靠读那句人话的开头猜——文案一改配色就悄悄错了，这种事没人会去测。 */
    fun lastAttemptFailed(): Boolean = !prefs.getBoolean(KEY_OK, true)

    private companion object {
        const val FILE = "gate_report_state"
        const val KEY_ATTEMPT_MS = "last_attempt_ms"
        const val KEY_MESSAGE = "message"
        const val KEY_OK = "ok"
    }
}
