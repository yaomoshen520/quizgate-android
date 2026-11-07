package com.quizgate.spike.net

import com.quizgate.proto.UsageReportReply
import com.quizgate.proto.UsageReportRequest
import com.quizgate.spike.monitor.UsageEventStore

class UsageUploader(private val store: UsageEventStore, private val post: (ByteArray) -> ByteArray) {
    fun flush(): UsageReportReply {
        val batch = store.pending()
        if (batch.isEmpty()) return UsageReportReply.getDefaultInstance()
        val reply = UsageReportReply.parseFrom(post(UsageReportRequest.newBuilder()
            .addAllEvents(batch).build().toByteArray()))
        check(reply.accepted >= 0 && reply.duplicated >= 0 &&
            reply.accepted + reply.duplicated == batch.size) { "用量确认条数不一致，保留待重传" }
        store.acknowledge(batch)
        return reply
    }
}
