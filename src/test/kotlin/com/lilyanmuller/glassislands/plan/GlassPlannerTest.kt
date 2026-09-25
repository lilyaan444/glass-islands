package com.lilyanmuller.glassislands.plan

import com.lilyanmuller.glassislands.nativebridge.AccessibilityOptions
import com.lilyanmuller.glassislands.nativebridge.BackdropAppearance
import com.lilyanmuller.glassislands.nativebridge.BackdropBackend
import com.lilyanmuller.glassislands.nativebridge.BackdropMaterial
import com.lilyanmuller.glassislands.settings.IslandContrast
import com.lilyanmuller.glassislands.settings.LiquidGlassState
import com.lilyanmuller.glassislands.shapes.GlassShapeKind
import com.lilyanmuller.glassislands.surfaces.SurfaceGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class GlassPlannerTest {
    private fun plan(
        state: LiquidGlassState = LiquidGlassState(),
        dark: Boolean = true,
        glass: Boolean = true,
        accessibility: AccessibilityOptions = AccessibilityOptions.NONE,
    ) = GlassPlanner.plan(state, GlassEnvironment(ideIsDark = dark, glassAvailable = glass, accessibility = accessibility))!!

    @Test
    fun `disabled settings produce no plan`() {
        assertNull(GlassPlanner.plan(LiquidGlassState(enabled = false), GlassEnvironment(ideIsDark = true, glassAvailable = true)))
    }

    @Test
    fun `reduce transparency turns the glass off`() {
        val environment = GlassEnvironment(ideIsDark = true, glassAvailable = true, accessibility = AccessibilityOptions(1))
        assertNull(GlassPlanner.plan(LiquidGlassState(), environment))
    }

    @Test
    fun `increase contrast makes every layer more opaque with visible rims`() {
        val normal = plan(LiquidGlassState(opacity = 30))
        val contrast = plan(LiquidGlassState(opacity = 30), accessibility = AccessibilityOptions(2))
        assertTrue(contrast.islands.material.increasedContrast && contrast.background.increasedContrast)
        for (kind in listOf(GlassShapeKind.TOOL_WINDOW, GlassShapeKind.EDITOR)) {
            assertTrue(contrast.islands.tintByKind.getValue(kind).alpha > normal.islands.tintByKind.getValue(kind).alpha, "$kind")
        }
        assertTrue(contrast.surfaces.alphaByGroup.getValue(SurfaceGroup.SELECTION) > normal.surfaces.alphaByGroup.getValue(SurfaceGroup.SELECTION))
    }

    @Test
    fun `opacity grows with the importance of the content`() {
        val plan = plan(LiquidGlassState(opacity = 60))
        val tints = plan.islands.tintByKind
        val window = plan.background.tint!!.alpha
        val toolWindow = tints.getValue(GlassShapeKind.TOOL_WINDOW).alpha
        val editor = tints.getValue(GlassShapeKind.EDITOR).alpha
        assertTrue(window < toolWindow && toolWindow < editor, "window=$window toolWindow=$toolWindow editor=$editor")
        assertEquals(214, editor)
    }

    @Test
    fun `editor never becomes fully opaque on glass but can be kept opaque`() {
        assertEquals(GlassPlanner.EDITOR_MAX_OPACITY, GlassPlanner.editorOpacity(LiquidGlassState(opacity = 100)), 1e-9)
        assertEquals(1.0, GlassPlanner.editorOpacity(LiquidGlassState(editorGlass = false)), 1e-9)
    }

    @Test
    fun `appearance follows the Rider theme`() {
        assertEquals(BackdropAppearance.LIGHT, plan(dark = false).background.appearance)
        assertEquals(BackdropAppearance.DARK, plan(dark = true).background.appearance)
        assertEquals(BackdropAppearance.LIGHT, plan(dark = false).islands.material.appearance)
    }

    @Test
    fun `islands are lighter than the window in both appearances`() {
        for (dark in listOf(true, false)) {
            val (island, window) = GlassPlanner.colours(LiquidGlassState(), dark)
            assertTrue(luminance(island) > luminance(window), "dark=$dark")
        }
    }

    @Test
    fun `inverted contrast swaps island and window colours`() {
        val raised = GlassPlanner.colours(LiquidGlassState(), dark = true)
        val inverted = GlassPlanner.colours(LiquidGlassState(islandContrast = IslandContrast.INVERTED), dark = true)
        assertEquals(raised.first, inverted.second)
        assertEquals(raised.second, inverted.first)
    }

    @Test
    fun `buttons and tabs use Liquid Glass with the theme accent for selection`() {
        val accent = Color(10, 20, 200)
        val islands = GlassPlanner.plan(LiquidGlassState(), GlassEnvironment(ideIsDark = true, glassAvailable = true, accent = accent))!!.islands
        assertEquals(BackdropBackend.GLASS, islands.material.backend)
        assertEquals(accent.rgb and 0xFFFFFF, islands.tintByKind.getValue(GlassShapeKind.FOCUSED_CONTROL).rgb and 0xFFFFFF)
        assertTrue(islands.tintByKind.getValue(GlassShapeKind.SELECTED_CONTROL).alpha > islands.tintByKind.getValue(GlassShapeKind.HOVERED_CONTROL).alpha)
        assertTrue(GlassShapeKind.SELECTED_TAB in islands.tintByKind && GlassShapeKind.HOVERED_TAB in islands.tintByKind)
        assertTrue(islands.tintByKind.getValue(GlassShapeKind.HOVERED_CONTROL).alpha < 64)
    }

    @Test
    fun `controls fall back to frosted pills before macOS 26`() {
        assertEquals(BackdropBackend.VISUAL_EFFECT, plan(glass = false).islands.material.backend)
    }

    @Test
    fun `turning glass buttons off keeps the IDE highlights`() {
        val plan = plan(LiquidGlassState(glassControls = false))
        assertFalse(plan.glassControls)
        assertFalse(GlassShapeKind.FOCUSED_CONTROL in plan.islands.tintByKind)
        assertFalse(SurfaceGroup.CONTROLS in plan.surfaces.alphaByGroup)
        assertFalse(SurfaceGroup.TABS in plan.surfaces.alphaByGroup)
    }

    @Test
    fun `swing surfaces become transparent and selections translucent`() {
        val surfaces = plan().surfaces.alphaByGroup
        assertEquals(SurfaceGroup.entries.toSet(), surfaces.keys)
        assertTrue(surfaces.filterKeys { it != SurfaceGroup.SELECTION }.values.all { it == 0.0 })
        assertEquals(GlassPlanner.SELECTION_ALPHA, surfaces.getValue(SurfaceGroup.SELECTION), 1e-9)
    }

    @Test
    fun `popups use the menu material and floating tool windows the popover material`() {
        val plan = plan(LiquidGlassState(opacity = 60))
        assertEquals(BackdropMaterial.MENU, plan.popup.material)
        assertEquals(BackdropMaterial.POPOVER, plan.floating.material)
        assertEquals(plan.islands.tintByKind.getValue(GlassShapeKind.TOOL_WINDOW), plan.floating.tint)
        assertTrue(plan.popup.tint!!.alpha in 100..240)
    }

    @Test
    fun `popups follow the appearance and the contrast settings`() {
        assertEquals(BackdropAppearance.LIGHT, plan(dark = false).popup.appearance)
        assertTrue(plan(accessibility = AccessibilityOptions(2)).popup.increasedContrast)
    }

    private fun luminance(color: Color) = 0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
}
