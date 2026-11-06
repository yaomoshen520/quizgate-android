package com.quizgate.spike.net

import com.quizgate.spike.monitor.GateEvent
import com.quizgate.spike.monitor.GateEventKind as LocalKind
import com.quizgate.proto.GateEventKind as WireKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 上报报文的两条不变量：编号可重算、类型不错位。
 * 这两条都在这里跑，是因为它们只有到了服务端才暴露——
 * 到那时候家长的告警列表已经成刷屏或者错标签了，手机上看不出来任何东西。
 *
 * 两边枚举同名，所以本地那份叫 LocalKind、线上那份叫 WireKind：
 * 少这一层别名的话，写错的那份照样能编译过，只是断言在比它自己。
 */
class ReportPayloadsTest {

    @Test
    fun `同一个时刻同一类型无论重算多少次都是同一个编号`() {
        val first = ReportPayloads.eventIdFor(AT, LocalKind.ACCESSIBILITY_OFF)
        val again = ReportPayloads.eventIdFor(AT, LocalKind.ACCESSIBILITY_OFF)
        assertEquals("编号必须可重算，否则重传在服务端就是两条新告警", first, again)
        assertEquals("服务端那一列按 36 字符的 UUID 收", 36, first.length)
    }

    @Test
    fun `换一个时刻或换一个类型就是另一件事`() {
        val base = ReportPayloads.eventIdFor(AT, LocalKind.ACCESSIBILITY_OFF)
        assertNotEquals(base, ReportPayloads.eventIdFor(AT + 1, LocalKind.ACCESSIBILITY_OFF))
        assertNotEquals(base, ReportPayloads.eventIdFor(AT, LocalKind.OVERLAY_OFF))
    }

    /**
     * 机械地过一遍所有本地类型：漏一个映射编译期会报（when 不穷尽），但顺序错位编译期不报；
     * 而 UNSPECIFIED 更阴——它表示"这条我不知道是什么"，服务端照单入库的话，
     * 家长列表里就多出一条谁也不认识的告警。
     */
    @Test
    fun `每个本地事件类型都有唯一的线上类型且没有一个是未知`() {
        val mapped = LocalKind.entries.map(ReportPayloads::toProtoKind)
        assertEquals("映射数量必须和本地类型一致", LocalKind.entries.size, mapped.size)
        assertEquals("不允许出现未知类型", emptyList<WireKind>(), mapped.filter { it == WireKind.GATE_EVENT_UNSPECIFIED })
        assertEquals("两种本地类型不能报成同一个线上类型", mapped.size, mapped.toSet().size)
    }

    /** 逐条钉死两端对应关系，而不是只钉首尾：中间任何一格串了位，上面那条查不出来（两边都还是"合法值"）。 */
    @Test
    fun `七种类型逐条对应到同名那一格`() {
        val expected = mapOf(
            LocalKind.ACCESSIBILITY_OFF to WireKind.ACCESSIBILITY_OFF,
            LocalKind.ACCESSIBILITY_RESTORED to WireKind.ACCESSIBILITY_RESTORED,
            LocalKind.OVERLAY_OFF to WireKind.OVERLAY_OFF,
            LocalKind.OVERLAY_RESTORED to WireKind.OVERLAY_RESTORED,
            LocalKind.NOTIFICATIONS_OFF to WireKind.NOTIFICATIONS_OFF,
            LocalKind.NOTIFICATIONS_RESTORED to WireKind.NOTIFICATIONS_RESTORED,
            LocalKind.GATE_INTERRUPTED to WireKind.GATE_INTERRUPTED,
        )
        assertEquals(LocalKind.entries.size, expected.size)
        expected.forEach { (local, wire) -> assertEquals(wire, ReportPayloads.toProtoKind(local)) }
    }

    @Test
    fun `整批打包时每条都带着自己的编号和时刻`() {
        val events = listOf(
            GateEvent(AT, LocalKind.ACCESSIBILITY_OFF, "无障碍被关"),
            GateEvent(AT + 500, LocalKind.NOTIFICATIONS_OFF, "通知被关"),
        )
        val request = ReportPayloads.toRequest(events)
        assertEquals(2, request.eventsCount)
        assertEquals(ReportPayloads.eventIdFor(AT, LocalKind.ACCESSIBILITY_OFF), request.eventsList[0].eventId)
        assertEquals(AT + 500, request.eventsList[1].occurredAtMs)
        assertEquals(WireKind.NOTIFICATIONS_OFF, request.eventsList[1].kind)
        assertEquals("通知被关", request.eventsList[1].detail)
    }

    /** 空批不发：发一条空消息只会让服务端回一个 0/0，家长那边什么都看不到，日志里还多一次"成功"。 */
    @Test
    fun `空台账打出来的请求里没有事件`() {
        assertEquals(0, ReportPayloads.toRequest(emptyList()).eventsCount)
    }

    private companion object {
        const val AT = 1_700_000_000_000L
    }
}
