package com.quizgate.spike.monitor

import com.quizgate.proto.UsageReportReply
import com.quizgate.proto.UsageReportRequest
import com.quizgate.spike.net.UsageUploader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UsageEventStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file() = File(temp.root, "usage.txt")

    @Test fun `restart preserves ids payload and unsent data`() {
        val first = UsageEventStore(file())
        first.record(1234, "app", playedMs = 2000, correctCount = 1, wrongCount = 2, interceptionFailed = true)
        val before = first.pending().single()
        val after = UsageEventStore(file()).pending().single()
        assertEquals(before, after)
        assertEquals(2000L, after.playedMs)
        assertEquals(1, after.correctCount)
        assertEquals(2, after.wrongCount)
        assertTrue(after.interceptionFailed)
    }

    @Test fun `acknowledgement cannot delete events appended during transmission`() {
        val store = UsageEventStore(file())
        store.record(1, "a", playedMs = 2)
        val sent = store.pending()
        UsageEventStore(file()).record(2, "b", correctCount = 1)
        store.acknowledge(sent)
        assertEquals("b", store.pending().single().appPackage)
    }

    @Test fun `lost response retries exact same ids and removes server duplicates`() {
        val store = UsageEventStore(file())
        store.record(1, "a", wrongCount = 1)
        val ids = mutableListOf<String>()
        var fail = true
        val uploader = UsageUploader(store) { body ->
            val events = UsageReportRequest.parseFrom(body).eventsList
            ids += events.single().eventId
            if (fail) throw IOException("response lost after server committed")
            UsageReportReply.newBuilder().setDuplicated(events.size).build().toByteArray()
        }
        assertTrue(runCatching { uploader.flush() }.isFailure)
        assertEquals(1, store.pending().size)
        fail = false
        uploader.flush()
        assertEquals(ids[0], ids[1])
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `partial acknowledgement retains whole batch and batch size is bounded`() {
        val store = UsageEventStore(file())
        repeat(105) { store.record(it.toLong(), "a", playedMs = 2) }
        val uploader = UsageUploader(store) { body ->
            assertEquals(100, UsageReportRequest.parseFrom(body).eventsCount)
            UsageReportReply.newBuilder().setAccepted(99).build().toByteArray()
        }
        assertTrue(runCatching { uploader.flush() }.isFailure)
        assertEquals(105, store.pending(200).size)
    }

    @Test fun `crash in append does not poison previous records or next append`() {
        val store = UsageEventStore(file())
        store.record(1, "a", playedMs = 2)
        file().appendText("partial")
        assertEquals(1, UsageEventStore(file()).pending().size)
        store.record(2, "b", playedMs = 2)
        assertEquals(2, store.pending().size)
    }

    @Test fun `zero usage does not manufacture report rows`() {
        val store = UsageEventStore(file())
        store.record(1, "a")
        assertTrue(store.pending().isEmpty())
    }
}
