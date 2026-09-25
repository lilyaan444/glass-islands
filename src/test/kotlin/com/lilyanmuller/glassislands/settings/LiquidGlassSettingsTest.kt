package com.lilyanmuller.glassislands.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class LiquidGlassSettingsTest {
    @Test
    fun `out of range opacity is clamped when loaded`() {
        val settings = LiquidGlassSettings()
        settings.loadState(LiquidGlassState(opacity = 3))
        assertEquals(LiquidGlassState.MIN_OPACITY, settings.state.opacity)
        settings.loadState(LiquidGlassState(opacity = 250))
        assertEquals(100, settings.state.opacity)
    }

    @Test
    fun `update stores a normalized copy`() {
        val settings = LiquidGlassSettings()
        settings.update(LiquidGlassState(islandContrast = IslandContrast.INVERTED, opacity = -5))
        assertEquals(IslandContrast.INVERTED, settings.state.islandContrast)
        assertEquals(LiquidGlassState.MIN_OPACITY, settings.state.opacity)
    }

    @Test
    fun `snapshot is detached from the persisted state`() {
        val settings = LiquidGlassSettings()
        val snapshot = settings.snapshot()
        snapshot.enabled = false
        assertNotSame(snapshot, settings.state)
        assertEquals(true, settings.state.enabled)
    }

    @Test
    fun `defaults match the documented configuration`() {
        val defaults = LiquidGlassState()
        assertEquals(IslandContrast.RAISED, defaults.islandContrast)
        assertEquals(62, defaults.opacity)
        assertEquals(true, defaults.editorGlass)
        assertEquals(true, defaults.glassControls)
    }
}
