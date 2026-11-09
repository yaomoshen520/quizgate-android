package com.quizgate.spike.diagnostics

import org.junit.Assert.*
import org.junit.Test

class OperationMetricsTest {
    @Test fun observationPreservesResultAndException() {
        assertEquals(42, OperationMetrics.measure("session_save") { 42 })
        val original = IllegalStateException("expected")
        try { OperationMetrics.measure("session_save") { throw original }; fail() } catch (actual: IllegalStateException) { assertSame(original, actual) }
        assertTrue(OperationMetrics.summary().contains("session_save"))
    }
    @Test fun unknownLabelsCannotGrowMetricsOrLeakSecrets() {
        OperationMetrics.record("https://secret.example/password", 50_000_000, false)
        assertFalse(OperationMetrics.summary().contains("secret.example"))
    }
    @Test fun resourceSamplesAreBoundedAndValidateNumbers() {
        val window = ResourceWindow(2); window.add(1.0, 900.0, 800.0); window.add(2.0, 20.0, 10.0); window.add(3.0, 30.0, 15.0)
        assertTrue(window.summary().contains("2 个资源样本")); assertFalse(window.summary().contains("900.0"))
        try { window.add(Double.NaN, 0.0, 0.0); fail() } catch (_: IllegalArgumentException) { }
    }
}
