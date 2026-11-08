package com.quizgate.spike.monitor

import org.junit.Assert.*
import org.junit.Test

class GameSessionTrackerTest {
    private var mono = 0L
    private var wall = 1_000_000L
    private var snapshot: SessionStore.Snapshot? = null
    private val store = object : SessionStore {
        override fun load() = snapshot
        override fun save(snapshot: SessionStore.Snapshot) { this@GameSessionTrackerTest.snapshot = snapshot }
        override fun clear() { snapshot = null }
    }
    private var policy = CycleRule(120_000, emptySet(), version=1, gamePackages=setOf("game.a","game.b"),
        warningMs=10_000, extensionMs=900_000, maxExtensions=2)
    private fun tracker() = SessionTracker(emptySet(),20_000,monotonicMs={mono},wallMs={wall},store=store,ruleProvider={policy})
    private fun advance(tracker: SessionTracker, ms: Long): SessionTracker.Trigger? {
        var left=ms
        while(left>0) {
            val step=minOf(2_000,left);mono+=step;wall+=step;left-=step
            tracker.onTick()?.let { return it }
        }
        return null
    }
    @Test fun `warn before threshold and no response triggers at deadline`() {
        val t=tracker();t.onWindowPackage("game.a")
        assertNull(advance(t,108_000));assertFalse(t.warningVisible)
        assertNull(advance(t,2_000));assertTrue(t.warningVisible)
        assertNotNull(advance(t,10_000));assertNull(t.onTick())
    }
    @Test fun `two full fifteen minute extensions then forced quiz`() {
        val t=tracker();t.onWindowPackage("game.a");advance(t,110_000)
        assertTrue(t.extend());assertFalse(t.extend());assertEquals(910_000L,t.remainingMs)
        advance(t,900_000);assertTrue(t.extend());assertEquals(2,t.extensionsUsed)
        advance(t,900_000);assertFalse(t.extend());assertNull(advance(t,9_999))
        assertNotNull(advance(t,1))
    }
    @Test fun `switching games and screen off do not replenish credits`() {
        val t=tracker();t.onWindowPackage("game.a");advance(t,110_000);t.extend()
        t.onWindowPackage("game.b");assertEquals(1,t.extensionsUsed)
        t.onScreenOff();advance(t,1_000_000);assertEquals(1,t.extensionsUsed)
        assertEquals(110_000L,t.sessionMs);t.onWindowPackage("game.b");assertEquals(1,t.extensionsUsed)
    }
    @Test fun `restart and wall clock changes retain frozen rule and credit usage`() {
        val first=tracker();first.onWindowPackage("game.a");advance(first,110_000);first.extend()
        policy=policy.copy(version=2,extensionMs=60_000,maxExtensions=0)
        mono=0;wall-=5_000_000
        val restored=tracker();restored.onWindowPackage("game.b")
        assertEquals(1,restored.extensionsUsed);assertEquals(1L,restored.appliedRuleVersion)
        assertEquals(15,restored.extensionMinutes);assertEquals(910_000L,restored.remainingMs)
    }
    @Test fun `changed rule waits until quiz passed`() {
        val t=tracker();t.onWindowPackage("game.a");advance(t,110_000)
        policy=policy.copy(version=2,maxExtensions=0)
        assertTrue(t.extend());assertEquals(1L,t.appliedRuleVersion)
        t.onQuizCleared();t.onWindowPackage("game.b")
        assertEquals(0,t.extensionsUsed);assertEquals(2L,t.appliedRuleVersion)
        advance(t,110_000);assertFalse(t.extend())
    }
    @Test fun `non game does not receive game extension`() {
        policy=policy.copy(watchedPackages=setOf("video.app"))
        val t=tracker();t.onWindowPackage("video.app");advance(t,110_000)
        assertFalse(t.warningVisible);assertFalse(t.extend());assertNotNull(advance(t,10_000))
        assertFalse(policy.copy(watchedPackages=setOf("com.quizgate.spike")).watches("com.quizgate.spike"))
    }
    @Test fun `restart retains non game round until next quiz too`() {
        policy=policy.copy(watchedPackages=setOf("video.app"))
        val first=tracker();first.onWindowPackage("video.app");advance(first,10_000)
        policy=policy.copy(version=2,thresholdMs=60_000)
        val restored=tracker();restored.onWindowPackage("video.app")
        assertEquals(1L,restored.appliedRuleVersion);assertEquals(110_000L,restored.remainingMs)
    }
    @Test fun `invalid remote limits never enable game extension`() {
        for (p in listOf(policy.copy(extensionMs=900_001),policy.copy(maxExtensions=3))) {
            policy=p;val t=tracker();assertNull(t.onWindowPackage("game.a"));assertFalse(t.warningVisible);assertFalse(t.extend())
        }
    }
    @Test fun `failed persistence cannot grant extra time`() {
        var fail=false
        val failing=object : SessionStore {
            override fun load():SessionStore.Snapshot?=null
            override fun clear()=Unit
            override fun save(snapshot:SessionStore.Snapshot) { if(fail) throw java.io.IOException("disk full") }
        }
        val t=SessionTracker(emptySet(),20_000,monotonicMs={mono},store=failing,ruleProvider={policy})
        t.onWindowPackage("game.a");advance(t,110_000);fail=true
        assertTrue(runCatching { t.extend() }.isFailure);assertEquals(0,t.extensionsUsed);assertEquals(10_000L,t.remainingMs)
    }
}
