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
     */
    fun forgetToken() {
        prefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_BIND_CODE)
            .apply()
    }

    private companion object {
        const val FILE = "gate_device"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_BIND_CODE = "bind_code"
        const val KEY_RULE_VERSION = "known_rule_version"
    }
}
