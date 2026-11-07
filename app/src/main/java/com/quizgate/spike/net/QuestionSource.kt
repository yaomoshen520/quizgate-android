package com.quizgate.spike.net

import android.content.Context
import com.quizgate.spike.config.SpikeSettings
import com.quizgate.spike.question.QuestionRepository
import com.quizgate.spike.question.LocalQuestionRepository
import com.quizgate.spike.util.SpikeLog

object QuestionSource {
    fun create(context: Context, localOnly: Boolean = false): QuestionRepository {
        if (localOnly) return LocalQuestionRepository()
        val settings = SpikeSettings(context)
        val identity = DeviceIdentity(context)
        // 一次答题始终使用取题时的地址和身份，实验台切地址不能把旧题提交给另一套库。
        val token = identity.deviceToken
        val client = ApiClient(settings.serverBaseUrl)
        return RemoteQuestionRepository(post = { path, body ->
            check(settings.reportingEnabled && token.isNotBlank()) { "设备尚未联网登记" }
            try {
                client.post(path, token, body)
            } catch (e: ApiClient.ReportUnavailable) {
                if (e.needsReenroll && identity.deviceToken == token) identity.forgetToken()
                throw e
            }
        }, report = { SpikeLog.record(it) }, rememberGrade = { settings.grade = it })
    }
}
