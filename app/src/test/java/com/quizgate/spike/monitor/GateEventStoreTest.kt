package com.quizgate.spike.monitor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateEventStoreTest {

    private fun tempFile() = File.createTempFile("gate_events", ".txt").apply { deleteOnExit() }

    @Test
    fun `落盘之后换一个实例也读得回来`() {
        val file = tempFile()
        GateEventStore(file).append(1_000L, GateEventKind.ACCESSIBILITY_OFF, "无障碍被关了")

        val reloaded = GateEventStore(file)
        assertEquals(1, reloaded.all().size)
        assertEquals(GateEventKind.ACCESSIBILITY_OFF, reloaded.all().first().kind)
        assertEquals(1_000L, reloaded.all().first().atMs)
        assertEquals("无障碍被关了", reloaded.all().first().detail)
    }

    @Test
    fun `说明里带竖线也不会把行格式冲垮`() {
        val file = tempFile()
        GateEventStore(file).append(2_000L, GateEventKind.OVERLAY_OFF, "a|b|c")

        val event = GateEventStore(file).all().single()
        assertEquals("a b c", event.detail)
    }

    @Test
    fun `recent 从新到旧`() {
        val file = tempFile()
        val store = GateEventStore(file)
        store.append(1L, GateEventKind.ACCESSIBILITY_OFF, "一")
        store.append(2L, GateEventKind.ACCESSIBILITY_RESTORED, "二")

        assertEquals(listOf(2L, 1L), store.recent(5).map { it.atMs })
    }

    @Test
    fun `上报成功后不再算待发，但记录本身留着`() {
        val file = tempFile()
        val store = GateEventStore(file)
        store.append(3L, GateEventKind.GATE_INTERRUPTED, "断了一分钟")
        assertEquals(1, store.pending().size)

        store.markFlushed()
        val reloaded = GateEventStore(file)
        assertTrue("标记必须真的落盘", reloaded.pending().isEmpty())
        assertEquals(1, reloaded.all().size)
    }

    @Test
    fun `反例校验：没调 markFlushed 时 pending 必须非空`() {
        val file = tempFile()
        GateEventStore(file).append(4L, GateEventKind.OVERLAY_RESTORED, "恢复了")
        assertTrue(GateEventStore(file).pending().isNotEmpty())
    }

    /**
     * 首页和看门狗各持一个实例、同时活着。这条就是当初那个 bug 的反例：
     * 缓存了内存列表的话，读的一方永远看不到写的一方刚加的那条。
     */
    @Test
    fun `两个都还活着的实例之间也必须看得见`() {
        val file = tempFile()
        val writer = GateEventStore(file)
        val reader = GateEventStore(file)
        assertTrue("读的一方一开始确实为空", reader.recent(5).isEmpty())

        writer.append(5L, GateEventKind.NOTIFICATIONS_OFF, "通知被关")
        assertEquals(GateEventKind.NOTIFICATIONS_OFF, reader.recent(5).single().kind)
    }

    @Test
    fun `超过上限只丢最老的，最新的必须还在`() {
        val file = tempFile()
        val store = GateEventStore(file)
        repeat(600) { store.append(it.toLong(), GateEventKind.OVERLAY_OFF, "$it") }

        val all = store.all()
        assertEquals(500, all.size)
        assertEquals(599L, all.last().atMs)
        assertEquals(100L, all.first().atMs)
    }

    @Test
    fun `发送期间新增的告警不能被旧批次标成已发`() {
        val file = tempFile()
        val store = GateEventStore(file)
        store.append(1L, GateEventKind.OVERLAY_OFF, "旧记录")
        val sent = store.pending()
        GateEventStore(file).append(2L, GateEventKind.ACCESSIBILITY_OFF, "新记录")
        store.markFlushed(sent)
        assertEquals(listOf(2L), store.pending().map { it.atMs })
    }
}
