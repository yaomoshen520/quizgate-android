package com.quizgate.spike.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `secrets and URLs are removed before writing and records remain single line`() {
        val input = "X-Quizgate-Token: abcXYZ password=p4ss Bearer abc.def 绑定码 ABC123 " +
            "{\"device_token\":\"my-private-token\"} https://user:pwd@example.test/path?token=private\nforged log"
        val safe = LogSanitizer.clean(input)
        for (secret in listOf("abcXYZ", "p4ss", "abc.def", "ABC123", "my-private-token", "example.test", "user:pwd")) {
            assertFalse("Unexpected secret $secret in $safe", safe.contains(secret))
        }
        assertFalse(safe.contains('\n'))
        assertTrue(safe.contains("\\n"))
        assertEquals(safe, LogSanitizer.clean(safe))
        assertTrue(LogSanitizer.clean("a".repeat(20_000)).length <= 2_048)
    }

    @Test fun `rotation bounds all segments and keeps newest records across reopen`() {
        val folder = temporary.newFolder()
        val store = BoundedLogStore(folder, segmentBytes = 96, segmentCount = 3)
        repeat(80) { store.append("record-$it 中文测试 " + "x".repeat(20)) }
        val files = folder.listFiles()!!
        assertTrue(files.size <= 3)
        assertTrue(files.all { it.length() <= 96 })
        assertTrue(files.sumOf { it.length() } <= 288)
        val text = BoundedLogStore(folder, 96, 3).snapshot().toString(Charsets.UTF_8)
        assertTrue(text.contains("record-79"))
        assertFalse(text.contains("record-0 "))
        assertFalse(text.contains('\uFFFD'))
        assertTrue(text.indexOf("record-78") < text.indexOf("record-79"))
    }

    @Test fun `concurrent crash and normal writes retain complete bounded lines`() {
        val folder = temporary.newFolder()
        val store = BoundedLogStore(folder, 4_096, 3)
        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { writer -> pool.execute { repeat(30) { store.append("writer-$writer-record-$it") } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        val lines = store.snapshot().toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        assertEquals(120, lines.size)
        assertEquals(120, lines.distinct().size)
        assertTrue(lines.all { it.matches(Regex("writer-[0-3]-record-[0-9]+")) })
    }

    @Test fun `filesystem failures remain observable instead of claiming log persisted`() {
        val file = temporary.newFile()
        assertTrue(runCatching { BoundedLogStore(file).append("event") }.isFailure)
    }

    @Test fun `snapshot never reads unrelated private files and caps tampered segment length`() {
        val folder = temporary.newFolder()
        File(folder, "credentials.txt").writeText("never-export-this")
        File(folder, "app-0.log").writeText("x".repeat(1_000))
        val text = BoundedLogStore(folder, 64, 1).snapshot().toString(Charsets.UTF_8)
        assertEquals(64, text.length)
        assertFalse(text.contains("never-export-this"))
    }

    @Test fun `CPU uses elapsed deltas handles resets and permits multiple cores`() {
        val cpu = CpuSampler()
        assertNull(cpu.sample(1_000, 50))
        assertEquals(5.0, cpu.sample(2_000, 100)!!, 0.001)
        assertEquals(200.0, cpu.sample(3_000, 2_100)!!, 0.001)
        assertNull(cpu.sample(3_000, 2_100))
        assertNull(cpu.sample(4_000, 10))
        assertEquals(0.0, cpu.sample(5_000, 10)!!, 0.001)
    }

    @Test fun `network metrics count failures slow requests and fixed route labels only`() {
        val metrics = NetworkMetrics()
        metrics.record("answer", 100, true)
        metrics.record("answer", 2_100, false)
        metrics.record("private-child-id?token=private-value", 5, false)
        val text = metrics.summary()
        assertTrue(text.contains("answer：2 次，失败 1，≥2秒 1，平均 1100.0 ms，最长 2100 ms"))
        assertTrue(text.contains("other：1 次"))
        assertFalse(text.contains("private-child-id"))
        assertFalse(text.contains("private-value"))
    }
}
