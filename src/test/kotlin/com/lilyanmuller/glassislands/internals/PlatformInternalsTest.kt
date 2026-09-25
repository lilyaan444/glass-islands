package com.lilyanmuller.glassislands.internals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Runs against the Rider jars the plugin is compiled with: every internal it relies on must resolve. */
class PlatformInternalsTest {
    @Test
    fun `every internal resolves on the targeted Rider`() {
        assertEquals(emptyMap<PlatformInternals.Feature, List<String>>(), PlatformInternals.unavailableFeatures)
        PlatformInternals.Feature.entries.forEach { assertTrue(PlatformInternals.isAvailable(it), it.name) }
        assertTrue(PlatformInternals.report().startsWith("all"))
    }
}
