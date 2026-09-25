package com.lilyanmuller.glassislands.surfaces

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class SurfaceTranslucencyTest {
    private class FakeTable(val theme: MutableMap<String, Color>) : UiColorTable {
        val overrides = mutableMapOf<String, Color>()
        override fun themeColor(key: String) = theme[key]
        override fun effectiveColor(key: String) = overrides[key] ?: theme[key]
        override fun override(key: String, color: Color?) {
            if (color == null) overrides.remove(key) else overrides[key] = color
        }
    }

    private val base = Color(30, 31, 34)

    @Test
    fun `applies the opacity factor to theme colours`() {
        val table = FakeTable(mutableMapOf("MainWindow.background" to base))
        SurfaceTranslucency(table).apply(SurfacePlan(mapOf(SurfaceGroup.WINDOW to 0.5)))
        val color = table.overrides.getValue("MainWindow.background")
        assertEquals(128, color.alpha)
        assertEquals(base.rgb and 0xFFFFFF, color.rgb and 0xFFFFFF)
    }

    @Test
    fun `leaves keys overridden by someone else untouched`() {
        val table = FakeTable(mutableMapOf("StatusBar.background" to base))
        table.overrides["StatusBar.background"] = Color.RED
        SurfaceTranslucency(table).apply(SurfacePlan(mapOf(SurfaceGroup.STATUS_BAR to 0.5)))
        assertEquals(Color.RED, table.overrides["StatusBar.background"])
    }

    @Test
    fun `restore removes every override`() {
        val table = FakeTable(mutableMapOf("MainWindow.background" to base, "StatusBar.background" to base))
        val translucency = SurfaceTranslucency(table)
        translucency.apply(SurfacePlan(mapOf(SurfaceGroup.WINDOW to 0.5, SurfaceGroup.STATUS_BAR to 0.7)))
        translucency.restore()
        assertTrue(table.overrides.isEmpty())
        assertTrue(translucency.activeKeys.isEmpty())
    }

    @Test
    fun `reapplying after a theme switch uses the new theme colours`() {
        val theme = mutableMapOf("MainWindow.background" to base)
        val table = FakeTable(theme)
        val translucency = SurfaceTranslucency(table)
        translucency.apply(SurfacePlan(mapOf(SurfaceGroup.WINDOW to 0.5)))
        theme["MainWindow.background"] = Color(240, 240, 240)
        translucency.apply(SurfacePlan(mapOf(SurfaceGroup.WINDOW to 0.5)))
        assertEquals(Color(240, 240, 240, 128), Color(table.overrides.getValue("MainWindow.background").rgb, true))
    }

    @Test
    fun `fully opaque groups are not overridden`() {
        val table = FakeTable(mutableMapOf("MainWindow.background" to base))
        SurfaceTranslucency(table).apply(SurfacePlan(mapOf(SurfaceGroup.WINDOW to 1.0)))
        assertTrue(table.overrides.isEmpty())
    }
}
