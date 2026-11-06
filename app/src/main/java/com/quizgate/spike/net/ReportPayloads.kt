package com.quizgate.spike.net

import com.quizgate.spike.monitor.GateEvent
import com.quizgate.spike.monitor.GateEventKind
import com.quizgate.proto.GateEvent as ProtoGateEvent
import com.quizgate.proto.GateEventKind as ProtoGateEventKind
import com.quizgate.proto.GateEventReportRequest
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 落盘台账里的那一行 → 要发出去的那条消息。单独一个文件、且不含任何 android 依赖，
 * 因为"同一件事重传两次必须是同一个编号"这一条只能靠单元测试钉死：
 * 一旦编号变，服务端认不出重复，家长每断一次网就多看一条假告警，
 * 而这种东西只会在现场出现，编译期什么都不会说。
 */
object ReportPayloads {

    /**
     * 由"发生时刻 + 类型"推出的 V3 UUID，不含任何随机量。
     * 进程被杀、app 升级、重装（只要台账还在）都能重算出同一个编号，
     * 于是重传天然落在服务端的去重上。
     */
    fun eventIdFor(atMs: Long, kind: GateEventKind): String =
        UUID.nameUUIDFromBytes("gate:$atMs:${kind.name}".toByteArray(StandardCharsets.UTF_8)).toString()

    /**
     * 逐个显式映射，不靠 ordinal 对齐。两边枚举现在顺序一致是巧合：
     * 哪天任何一边插一个值，靠顺序就会把"关无障碍"报成"关通知"，
     * 而写 when 的话编译期直接报"分支没覆盖全"。
     */
    fun toProtoKind(kind: GateEventKind): ProtoGateEventKind = when (kind) {
        GateEventKind.ACCESSIBILITY_OFF -> ProtoGateEventKind.ACCESSIBILITY_OFF
        GateEventKind.ACCESSIBILITY_RESTORED -> ProtoGateEventKind.ACCESSIBILITY_RESTORED
        GateEventKind.OVERLAY_OFF -> ProtoGateEventKind.OVERLAY_OFF
        GateEventKind.OVERLAY_RESTORED -> ProtoGateEventKind.OVERLAY_RESTORED
        GateEventKind.NOTIFICATIONS_OFF -> ProtoGateEventKind.NOTIFICATIONS_OFF
        GateEventKind.NOTIFICATIONS_RESTORED -> ProtoGateEventKind.NOTIFICATIONS_RESTORED
        GateEventKind.GATE_INTERRUPTED -> ProtoGateEventKind.GATE_INTERRUPTED
    }

    fun toRequest(events: List<GateEvent>): GateEventReportRequest {
        val builder = GateEventReportRequest.newBuilder()
        for (event in events) {
            builder.addEvents(
                ProtoGateEvent.newBuilder()
                    .setEventId(eventIdFor(event.atMs, event.kind))
                    .setKind(toProtoKind(event.kind))
                    .setOccurredAtMs(event.atMs)
                    .setDetail(event.detail)
            )
        }
        return builder.build()
    }
}
