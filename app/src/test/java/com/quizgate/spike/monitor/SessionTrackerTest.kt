package com.quizgate.spike.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrackerTest {

    private var mono = 0L
    private var wall = 1_000_000_000L
    private val store = MemorySessionStore()
    private val reports = mutableListOf<String>()

    private fun newTracker(
        thresholdMs: Long = 30_000,
        graceMs: Long = 5_000,
        maxStepMs: Long = 6_000,
        persist: Boolean = true
    ) = SessionTracker(
        targetPackages = setOf(VIDEO, GAME),
        thresholdMs = thresholdMs,
        graceMs = graceMs,
        maxStepMs = maxStepMs,
        monotonicMs = { mono },
        wallMs = { wall },
        store = if (persist) store else null,
        report = { reports += it }
    )

    /** 按真实 tick 节奏推进时间；一步跨太大是被禁止的，那正是被修掉的洞。 */
    private fun SessionTracker.play(ms: Long): SessionTracker.Trigger? {
        var left = ms
        while (left > 0) {
            val step = minOf(STEP_MS, left)
            mono += step
            wall += step
            onTick()?.let { return it }
            left -= step
        }
        return null
    }

    @Test
    fun `reaching threshold triggers exactly once`() {
        val session = newTracker()
        assertNull(session.onWindowPackage(VIDEO))
        assertNull(session.play(20_000))

        val trigger = session.play(10_000)

        assertNotNull(trigger)
        assertEquals(VIDEO, trigger?.packageUnderQuota)
        assertNull(session.play(20_000))
    }

    @Test
    fun `never triggers outside target apps`() {
        val session = newTracker()
        session.onWindowPackage(LAUNCHER)
        assertNull(session.play(100_000))
    }

    @Test
    fun `a short break away does not reset the session`() {
        val session = newTracker()
        session.onWindowPackage(VIDEO)
        session.play(20_000)

        session.onWindowPackage(LAUNCHER)
        session.play(3_000)
        session.onWindowPackage(VIDEO)

        assertNotNull(session.play(10_000))
    }

    @Test
    fun `a break longer than grace breaks the session`() {
        val session = newTracker()
        session.onWindowPackage(VIDEO)
        session.play(20_000)

        session.onWindowPackage(LAUNCHER)
        assertNull(session.play(6_000))

        session.onWindowPackage(VIDEO)
        assertNull(session.play(20_000))
        assertNotNull(session.play(10_000))
    }

    @Test
    fun `screen off resets the session`() {
        val session = newTracker()
        session.onWindowPackage(GAME)
        assertNull(session.play(25_000))

        session.onScreenOff()

        session.onWindowPackage(GAME)
        assertNull(session.play(25_000))
    }

    @Test
    fun `switching between two target apps counts as one session`() {
        val session = newTracker()
        session.onWindowPackage(VIDEO)
        session.play(15_000)

        session.onWindowPackage(GAME)

        assertEquals(GAME, session.play(15_000)?.packageUnderQuota)
    }

    @Test
    fun `answering correctly starts a fresh quota window`() {
        val session = newTracker()
        session.onWindowPackage(VIDEO)
        assertNotNull(session.play(30_000))

        session.onQuizCleared()

        assertNull(session.play(20_000))
        assertNotNull(session.play(10_000))
    }

    @Test
    fun `a failed interception retries after the backoff but not before`() {
        val session = newTracker()
        session.onWindowPackage(VIDEO)
        assertNotNull(session.play(30_000))

        // 抢屏失败：以前这里没人解除"待答题"，闸门会永久静默。
        session.onFireFailed()

        assertNull(session.play(SessionTracker.FIRE_RETRY_BACKOFF_MS - STEP_MS))
        assertNotNull(session.play(STEP_MS * 2))
    }

    @Test
    fun `a persistently failing interception retries at the backoff cadence, not every tick`() {
        val session = newTracker()
        session.onWindowPackage(VIDEO)
        assertNotNull(session.play(30_000))

        var fires = 0
        // 60 秒内抢屏一直失败，每 tick 上报一次。
        repeat(30) {
            session.onFireFailed()
            session.play(STEP_MS)?.let { fires++ }
        }

        // 退避 15s → 60 秒里重试三次左右；没有退避就是每 2 秒撞一次窗口。
        assertTrue("重试 $fires 次不在退避节奏上", fires in 2..4)
    }

    @Test
    fun `an unresolved interception retries by itself after a timeout`() {
        val session = newTracker()
        session.onWindowPackage(VIDEO)
        assertNotNull(session.play(30_000))

        assertNull(session.play(60_000))

        assertNotNull(session.play(60_000))
        assertTrue(reports.any { it.contains("超时") || it.contains("未解决") })
    }

    @Test
    fun `a stalled clock gap is not credited as play time`() {
        val session = newTracker(maxStepMs = 6_000)
        session.onWindowPackage(VIDEO)
        session.play(10_000)

        // tick 停摆 25 分钟（深睡、进程冻结），醒来这一步必须被丢弃。
        mono += 25 * 60 * 1000L
        wall += 25 * 60 * 1000L
        assertNull(session.onTick())

        assertTrue(reports.any { it.contains("断档") })
        // 只丢弃断档，之后照常累计：再玩 20 秒就该触发。
        assertNotNull(session.play(20_000))
    }

    @Test
    fun `accumulated time survives a process restart`() {
        val first = newTracker()
        first.onWindowPackage(VIDEO)
        assertNull(first.play(20_000))

        // 进程被杀又活过来，但仍是同一次开机。
        val second = newTracker()
        assertEquals(20_000L, second.sessionMs)
        assertEquals(VIDEO, second.foreground)
        assertNotNull(second.play(10_000))
    }

    @Test
    fun `a reboot keeps the accumulated time but does not credit the offline gap`() {
        val first = newTracker()
        first.onWindowPackage(VIDEO)
        assertNull(first.play(20_000))

        // 重启：单调时钟归零，绝对时间往前走了三分钟（关机/换电的时间）。
        mono = 5_000
        wall += 3 * 60 * 1000L

        val afterReboot = newTracker()
        assertEquals(20_000L, afterReboot.sessionMs)
        // 离线的三分钟不算玩，所以还差 10 秒。
        assertNull(afterReboot.play(9_000))
        assertNotNull(afterReboot.play(1_000))
    }

    @Test
    fun `a snapshot older than the resume window is dropped`() {
        val first = newTracker()
        first.onWindowPackage(VIDEO)
        assertNull(first.play(20_000))

        mono = 1_000
        wall += 60 * 60 * 1000L

        val stale = newTracker()
        assertEquals(0L, stale.sessionMs)
        assertTrue(reports.any { it.contains("恢复窗口") })
    }

    @Test
    fun `turning the clock backwards does not resurrect a stale session`() {
        val first = newTracker()
        first.onWindowPackage(VIDEO)
        assertNull(first.play(20_000))

        wall -= 60 * 60 * 1000L

        assertEquals(0L, newTracker().sessionMs)
        assertTrue(reports.any { it.contains("回拨") || it.contains("更早") })
    }

    @Test
    fun `screen off also clears the persisted snapshot`() {
        val session = newTracker()
        session.onWindowPackage(VIDEO)
        session.play(20_000)

        session.onScreenOff()

        assertNull(store.loaded)
    }

    private class MemorySessionStore : SessionStore {
        var loaded: SessionStore.Snapshot? = null

        override fun load(): SessionStore.Snapshot? = loaded

        override fun save(snapshot: SessionStore.Snapshot) {
            loaded = snapshot
        }

        override fun clear() {
            loaded = null
        }
    }

    private companion object {
        const val VIDEO = "com.ss.android.ugc.aweme"
        const val GAME = "com.tencent.tmgp.sgame"
        const val LAUNCHER = "com.android.launcher"
        const val STEP_MS = 2_000L
    }
}
