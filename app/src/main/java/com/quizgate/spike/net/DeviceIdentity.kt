package com.quizgate.spike.net

import android.content.Context
import java.util.UUID

/**
 * 这台设备在服务端的身份。
 *
 * install_id 必须比进程活得久、也必须比 app 更新活得久，所以落 SharedPreferences：
 * 每次冷启动换一个新 install_id 的话，服务端会攒出一堆"从未绑定"的设备行，
 * 家长认领的那台反倒越来越旧。
 *
 * device_token 不写死、不进日志：它是这台设备的凭证，孩子拿到 root 也只想看到它不存在。
 */
class DeviceIdentity(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 首次读取时生成并固化。 */
    val installId: String
        get() {
            val existing = prefs.getString(KEY_INSTALL_ID, null)
            if (existing != null) {
                return existing
            }
            val created = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, created).apply()
            return created
        }

    val deviceToken: String
        get() = prefs.getString(KEY_DEVICE_TOKEN, "") ?: ""

    /** 家长在后台输入这六个字符，服务端才把这台设备划到他家孩子名下。 */
    val bindCode: String
        get() = prefs.getString(KEY_BIND_CODE, "") ?: ""

    /**
     * 家长有没有把这台设备认领过去。设备自己看不见这件事——enroll 只在第一次登记时跑，
     * 之后没有任何人再告诉它，所以这个事实只从心跳的 device_bound 落进来。
     *
     * 默认 false：老服务端不回这个字段时，手机继续显示绑定码，也就是修复前的样子。
     * 反过来默认 true 会把码藏掉，而家长手上再也没有第二个入口。
     */
    val bound: Boolean
        get() = prefs.getBoolean(KEY_BOUND, false)

    /** @return 这一次是不是第一次翻成"已认领"，调用方用它决定要不要记一行日志 */
    fun markBound(): Boolean {
        if (bound) {
            return false
        }
        prefs.edit().putBoolean(KEY_BOUND, true).apply()
        return true
    }

    /** enroll 换回令牌才算登记过。没登记就没有 token，后面的调用连门都不用敲。 */
    val enrolled: Boolean
        get() = deviceToken.isNotBlank()

    /**
     * 最后一次从服务端看到的规则版本。存在这里而不是每次现算，
     * 因为"我已知版本"必须是跨进程稳定的——进程一重启就退回 0，
     * 服务端就会认为客户端不知道，每次心跳都回传一份快照。
     */
    var knownRuleVersion: Long
        get() = prefs.getLong(KEY_RULE_VERSION, 0L)
        set(value) {
            prefs.edit().putLong(KEY_RULE_VERSION, value).apply()
        }

    /** Received while local-only does not mean applied. Force the next heartbeat to return its snapshot. */
    fun requestRuleRefresh() {
        knownRuleVersion = -1L
    }

    fun saveEnrollment(deviceToken: String, bindCode: String) {
        prefs.edit()
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_BIND_CODE, bindCode)
            .apply()
    }

    /**
     * 作废令牌，下次 tick 重新走 enroll。install_id 刻意保留：
     * 那是"同一台设备"的唯一依据，换了它服务端就会多出一台新设备，
     * 家长认领过的那条反倒成了孤儿。
     *
     * bound 跟着一起清：丢令牌最常见的原因之一就是服务端换了库，
     * 重新 enroll 会建出一条没被认领的新设备。留着 true 的话首页把绑定码藏掉，
     * 家长再也抄不到码，这台机器就永久卡在"显示已绑定、服务端没人认领"。
     * 清掉的代价只是家长在第一次心跳之前会重新看到那六个字符。
     */
    fun forgetToken() {
        prefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_BIND_CODE)
            .putBoolean(KEY_BOUND, false)
            .putLong(KEY_RULE_VERSION, -1L)
            .apply()
    }

    private companion object {
        const val FILE = "gate_device"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_BIND_CODE = "bind_code"
        const val KEY_BOUND = "device_bound"
        const val KEY_RULE_VERSION = "known_rule_version"
    }
}
