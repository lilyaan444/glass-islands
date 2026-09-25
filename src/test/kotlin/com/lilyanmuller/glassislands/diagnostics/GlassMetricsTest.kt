package com.lilyanmuller.glassislands.diagnostics

import com.lilyanmuller.glassislands.diagnostics.GlassMetrics.Operation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlassMetricsTest {
    @Test
    fun `operations are counted and timed`() {
        GlassMetrics.reset()
        val result = GlassMetrics.measure(Operation.FULL_SCAN) { 42 }
        GlassMetrics.tick(Operation.EVENT)
        GlassMetrics.tick(Operation.EVENT)
        assertEquals(42, result)
        assertEquals(1, GlassMetrics.countOf(Operation.FULL_SCAN))
        assertEquals(2, GlassMetrics.countOf(Operation.EVENT))
        assertTrue(GlassMetrics.report().contains("full layout scans        1"))
    }

    @Test
    fun `a failing operation is still counted`() {
        GlassMetrics.reset()
        runCatching { GlassMetrics.measure(Operation.NATIVE_CALL) { error("boom") } }
        assertEquals(1, GlassMetrics.countOf(Operation.NATIVE_CALL))
    }
}
