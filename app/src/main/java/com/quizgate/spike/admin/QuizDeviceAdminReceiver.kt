package com.quizgate.spike.admin

import android.app.admin.DeviceAdminReceiver

/**
 * 只为回答"M0 的一个问题"：这台 ROM 允不允许把本应用设成 Device Owner。
 * 故意不含任何策略调用——DO 身份本身不会改动设备，改设备的是 API 调用。
 */
class QuizDeviceAdminReceiver : DeviceAdminReceiver()
