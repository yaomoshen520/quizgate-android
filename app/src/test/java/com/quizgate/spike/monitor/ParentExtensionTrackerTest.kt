package com.quizgate.spike.monitor

import org.junit.Assert.*
import org.junit.Test
import com.quizgate.spike.monitor.ParentExtension.Stage

class ParentExtensionTrackerTest {
    private var mono=0L
    private var wall=1_000_000L
    private var saved: SessionStore.Snapshot?=null
    private var fail=false
    private var attempts=0
    private val reports=mutableListOf<String>()
    private val policy=CycleRule(60_000, setOf("video.app"), version=12, gamePackages=setOf("game.a","game.b"),
        warningMs=20_000,extensionMs=60_000,maxExtensions=2)
    private val store=object:SessionStore {
        override fun load()=saved
        override fun clear(){ saved=null }
        override fun save(snapshot: SessionStore.Snapshot) { attempts++; if(fail) error("disk full");saved=snapshot }
    }
    private fun tracker()=SessionTracker(emptySet(),60_000,monotonicMs={mono},wallMs={wall},store=store,
        ruleProvider={policy},report={reports.add(it)})
    private fun advance(t:SessionTracker,ms:Long):SessionTracker.Trigger? {
        var left=ms
        while(left>0){val step=minOf(left,2_000);mono+=step;wall+=step;left-=step;t.onTick()?.let{return it}}
        return null
    }
    private fun exhausted():SessionTracker {
        val t=tracker();t.onWindowPackage("game.a");advance(t,40_000);assertTrue(t.extend())
        advance(t,60_000);assertTrue(t.extend());advance(t,60_000)
        assertEquals(2,t.extensionsUsed);assertEquals(20_000L,t.remainingMs);return t
    }
    private fun pending(t:SessionTracker) {
        assertTrue(t.beginParentRequest())
        assertTrue(t.receiveParentDecision(t.cycleId,"request-A",Stage.PENDING,1))
    }
    @Test fun `only confirmed pending allows playing past deadline and approval adds full duration once`() {
        val t=exhausted();assertTrue(t.beginParentRequest());assertFalse(t.waitingForParent)
        assertNotNull(advance(t,20_000));assertTrue(t.awaitingQuiz)
        assertTrue(t.receiveParentDecision(t.cycleId,"request-A",Stage.PENDING,1))
        assertFalse(t.awaitingQuiz);assertTrue(t.waitingForParent);assertNull(advance(t,120_000))
        assertEquals(0L,t.remainingMs)
        assertTrue(t.receiveParentDecision(t.cycleId,"request-A",Stage.GRANTED,1))
        assertEquals(60_000L,t.remainingMs);assertFalse(t.waitingForParent)
        assertFalse(t.receiveParentDecision(t.cycleId,"request-A",Stage.GRANTED,1))
        assertEquals(2,t.extensionsUsed);assertFalse(t.canRequestParent)
        assertNull(advance(t,59_999));assertNotNull(advance(t,1))
    }
    @Test fun `approval before original deadline preserves remaining time and cannot request a fourth`() {
        val t=exhausted();pending(t)
        assertTrue(t.receiveParentDecision(t.cycleId,"request-A",Stage.GRANTED,1))
        assertEquals(80_000L,t.remainingMs)
        advance(t,60_000);assertTrue(t.warningVisible)
        assertFalse(t.canRequestParent);assertFalse(t.beginParentRequest());assertFalse(t.extend())
    }
    @Test fun `refusal preserves previously granted time and forces quiz after original deadline`() {
        val t=exhausted();pending(t)
        assertTrue(t.receiveParentDecision(t.cycleId,"request-A",Stage.REJECTED,1))
        assertEquals(20_000L,t.remainingMs);assertFalse(t.beginParentRequest())
        assertNull(advance(t,19_999));assertNotNull(advance(t,1))
        t.onQuizCleared();t.onWindowPackage("game.a")
        advance(t,40_000);t.extend();advance(t,60_000);t.extend();advance(t,60_000);pending(t)
        assertNull(advance(t,60_000))
        t.receiveParentDecision(t.cycleId,"request-A",Stage.REJECTED,1)
        assertNotNull(t.onTick())
    }
    @Test fun `pending and applied approval survive reboot without replaying the grant`() {
        val first=exhausted();pending(first);val cycle=first.cycleId
        advance(first,120_000);first.onScreenOff()
        mono=0;wall-=99_000_000
        val restored=tracker();restored.onWindowPackage("game.b")
        assertEquals(cycle,restored.cycleId);assertTrue(restored.waitingForParent)
        assertNull(advance(restored,10_000))
        restored.receiveParentDecision(cycle,"request-A",Stage.GRANTED,1)
        assertEquals(60_000L,restored.remainingMs)
        val again=tracker();again.onWindowPackage("game.a")
        assertEquals(60_000L,again.remainingMs)
        assertFalse(again.receiveParentDecision(cycle,"request-A",Stage.GRANTED,1))
        assertFalse(again.beginParentRequest())
    }
    @Test fun `old cycle wrong request and wrong duration cannot alter current round`() {
        val t=exhausted();pending(t);val old=t.cycleId
        assertFalse(t.receiveParentDecision("other-cycle","request-A",Stage.GRANTED,1))
        assertFalse(t.receiveParentDecision(old,"request-B",Stage.GRANTED,1))
        assertFalse(t.receiveParentDecision(old,"request-A",Stage.GRANTED,16))
        assertFalse(t.receiveParentDecision(old,"request-A",Stage.GRANTED,2))
        t.onQuizCleared();t.onWindowPackage("game.a")
        assertNotEquals(old,t.cycleId);assertEquals(0,t.extensionsUsed);assertNull(t.parentExtension)
        assertFalse(t.receiveParentDecision(old,"request-A",Stage.GRANTED,1))
    }
    @Test fun `choosing quiz while waiting closes the application and ignores late approval`() {
        val t=exhausted();pending(t)
        assertNotNull(t.quizNow());assertEquals(Stage.CLOSED,t.parentExtension?.stage)
        assertTrue(t.awaitingQuiz);assertFalse(t.waitingForParent)
        assertFalse(t.receiveParentDecision(t.cycleId,"request-A",Stage.GRANTED,1))
    }
    /**
     * 磁盘写不进去时"这一笔不算数"要回滚，同时异常绝不能冒出 persist()：
     * 计时和触发都跑在主线程（无障碍回调 + tick），冒出去进程就没了，
     * 而累计时长只在内存里——孩子只要让写盘一直失败就能白拿一段从头计时的娱乐。
     */
    @Test fun `failed writes never release quiz or grant extra time`() {
        val t=exhausted();fail=true
        assertFalse(t.beginParentRequest());assertNull(t.parentExtension)
        // 裸计时路径没人 try/catch：这里必须是"照常往下走"，不是抛出。
        // Advance 够跨过落盘水位，让 onTick 真的走到 save()，否则这条断言是空的。
        val before=t.sessionMs
        val beforeAttempts=attempts
        assertNull(runCatching{ repeat(6){ mono+=2_000; wall+=2_000; t.onTick() } }.exceptionOrNull())
        assertEquals(before+12_000L,t.sessionMs)
        // 没有这一条，"没抛出异常"可能只是因为这段时间压根没走到落盘水位。
        assertTrue(attempts>beforeAttempts)
        // 一秒一次的 tick 会一直失败，只报第一次，否则这一句会把日志挤满、把要紧的顶掉。
        assertEquals(1,reports.count{it.contains("落盘失败")})
        fail=false;pending(t);advance(t,60_000);fail=true
        assertFalse(t.receiveParentDecision(t.cycleId,"request-A",Stage.GRANTED,1))
        assertEquals(Stage.PENDING,t.parentExtension?.stage);assertEquals(0L,t.remainingMs)
        fail=false;assertTrue(t.receiveParentDecision(t.cycleId,"request-A",Stage.GRANTED,1))
        assertEquals(60_000L,t.remainingMs)
    }
    @Test fun `sending failure gives no waiting time and retry reuses the same cycle`() {
        val t=exhausted();val cycle=t.cycleId;assertTrue(t.beginParentRequest())
        t.parentRequestFailed(cycle);assertEquals(Stage.FAILED,t.parentExtension?.stage)
        assertFalse(t.waitingForParent);assertTrue(t.beginParentRequest());assertEquals(cycle,t.cycleId)
        assertNotNull(advance(t,20_000))
    }
    @Test fun `reconciling a lost submit response can restore confirmed waiting after deadline`() {
        val t=exhausted();assertTrue(t.beginParentRequest());t.parentRequestFailed(t.cycleId)
        assertNotNull(advance(t,20_000));assertTrue(t.awaitingQuiz)
        assertTrue(t.receiveParentDecision(t.cycleId,"request-A",Stage.PENDING,1))
        assertTrue(t.waitingForParent);assertFalse(t.awaitingQuiz);assertNull(advance(t,30_000))
    }
    @Test fun `waiting permission does not exempt a different non game app from its limit`() {
        val t=exhausted();pending(t);advance(t,30_000)
        assertNotNull(t.onWindowPackage("video.app"));assertTrue(t.awaitingQuiz)
        t.receiveParentDecision(t.cycleId,"request-A",Stage.GRANTED,1)
        assertTrue(t.awaitingQuiz)
    }
}
