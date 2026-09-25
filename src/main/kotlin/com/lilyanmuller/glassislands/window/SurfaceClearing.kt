package com.lilyanmuller.glassislands.window

import com.lilyanmuller.glassislands.surfaces.SwingUiColorTable
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.util.WeakHashMap
import javax.swing.JComponent
import kotlin.math.abs

/**
 * Stops opaque panels, lists, trees and text areas from filling their background when it is one of the given theme
 * surface colours, so their content sits on the glass behind; any other background (a coloured banner, a
 * selection, a text field) is kept. Scoped to explicit roots, never global. EDT only.
 */
internal class SurfaceClearing(
    private val surfaceKeys: List<String>,
    /**
     * Also gives the cleared components a transparent background colour: needed where UI delegates paint the
     * background themselves whatever `isOpaque` says (rounded popup menus). Restored exactly afterwards.
     */
    private val transparentBackground: Boolean = false,
    private val extraSurfaces: () -> List<Color> = { emptyList() },
) {
    /** Cleared components and their original background (only recorded with [transparentBackground]). */
    private val cleared = WeakHashMap<JComponent, Color?>()
    private val wasOpaque = WeakHashMap<JComponent, Boolean>()

    val clearedCount: Int get() = cleared.size

    /** Clears [root] and everything below it. */
    fun clear(root: Component) = visit(root, surfaceColours())

    fun clearAll(roots: List<Component>) {
        if (roots.isEmpty()) return
        val surfaces = surfaceColours()
        roots.forEach { visit(it, surfaces) }
    }

    fun restoreAll() {
        cleared.forEach { (component, background) ->
            if (transparentBackground) component.background = background
            component.isOpaque = wasOpaque[component] ?: true
            component.repaint()
        }
        cleared.clear()
        wasOpaque.clear()
    }

    private fun visit(component: Component, surfaces: List<Color>) {
        // With transparentBackground, non-opaque components count too: their UI may fill the background anyway.
        val candidate = component is JComponent && component !in cleared && (component.isOpaque || transparentBackground)
        if (candidate && isSurface(component.background, surfaces)) {
            component as JComponent
            cleared[component] = component.background
            wasOpaque[component] = component.isOpaque
            component.isOpaque = false
            if (transparentBackground) component.background = TRANSPARENT
            component.repaint()
        }
        if (component is Container) component.components.forEach { visit(it, surfaces) }
    }

    private fun isSurface(color: Color?, surfaces: List<Color>): Boolean =
        color != null && color.alpha == 255 && surfaces.any { close(it, color) }

    private fun close(a: Color, b: Color) =
        abs(a.red - b.red) <= TOLERANCE && abs(a.green - b.green) <= TOLERANCE && abs(a.blue - b.blue) <= TOLERANCE

    private fun surfaceColours(): List<Color> = (surfaceKeys.mapNotNull(SwingUiColorTable::themeColor) + extraSurfaces()).distinct()

    private companion object {
        const val TOLERANCE = 6
        val TRANSPARENT = Color(0, 0, 0, 0)
    }
}
