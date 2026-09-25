package com.lilyanmuller.glassislands.window

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager

class SurfaceClearingTest {
    private val surface = Color(0x26, 0x28, 0x2C)

    init {
        UIManager.getLookAndFeelDefaults()["RlgTest.surface"] = surface
    }

    @AfterEach
    fun cleanUp() {
        UIManager.getLookAndFeelDefaults().remove("RlgTest.surface")
    }

    private fun panel(color: Color, opaque: Boolean = true) = JPanel().apply {
        background = color
        isOpaque = opaque
    }

    @Test
    fun `only surface coloured panels are cleared and restored`() {
        val root = panel(surface)
        val banner = panel(Color.RED).also(root::add)
        val nested = panel(Color(0x27, 0x29, 0x2D)).also(root::add)
        val clearing = SurfaceClearing(listOf("RlgTest.surface"))
        clearing.clear(root)
        assertFalse(root.isOpaque)
        assertFalse(nested.isOpaque, "close colours count as the surface")
        assertTrue(banner.isOpaque)
        assertEquals(2, clearing.clearedCount)
        clearing.restoreAll()
        assertTrue(root.isOpaque && nested.isOpaque && banner.isOpaque)
    }

    @Test
    fun `popup mode also clears the background colour of non opaque components and restores it`() {
        val menu = panel(surface, opaque = false)
        val label = JLabel("text").also(menu::add)
        val clearing = SurfaceClearing(listOf("RlgTest.surface"), transparentBackground = true)
        clearing.clear(menu)
        assertEquals(0, menu.background.alpha)
        clearing.restoreAll()
        assertEquals(surface, menu.background)
        assertFalse(menu.isOpaque, "components keep their own opacity")
        assertTrue(label.isOpaque == JLabel().isOpaque)
    }

    @Test
    fun `clearing twice does not record the transparent colour as the original`() {
        val menu = panel(surface, opaque = false)
        val clearing = SurfaceClearing(listOf("RlgTest.surface"), transparentBackground = true)
        clearing.clear(menu)
        clearing.clear(menu)
        clearing.restoreAll()
        assertEquals(surface, menu.background)
    }
}
