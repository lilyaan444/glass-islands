package com.lilyanmuller.glassislands.window

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.lilyanmuller.glassislands.shapes.GlassShapeLocator
import java.awt.Component
import java.awt.Container
import java.awt.Window

/**
 * Inside the glass islands of one window, clears the panels painted with a theme surface colour (see
 * [SurfaceClearing]), so their content sits on the island glass. Scoped to the islands, never global. EDT only.
 */
internal class IslandContentTransparency(private val window: Window) {

    private val clearing = SurfaceClearing(SURFACE_KEYS) {
        listOf(EditorColorsManager.getInstance().globalScheme.defaultBackground)
    }

    val clearedCount: Int get() = clearing.clearedCount

    /** Called on each layout change: new or re-created components are handled as they appear. */
    fun refresh() {
        clearing.clearAll(GlassShapeLocator.islandComponents(window).flatMap { (it as? Container)?.components?.toList().orEmpty() })
    }

    /** Clears a component just added to the window, if it belongs to an island. */
    fun clearIfInIsland(component: Component) {
        if (GlassShapeLocator.islandOf(component) != null) clearing.clear(component)
    }

    fun restoreAll() = clearing.restoreAll()

    private companion object {
        /** Theme colours IDE panels use as plain backgrounds (Islands maps them all to layer-0/layer-1 greys). */
        val SURFACE_KEYS = listOf(
            "ToolWindow.background", "Panel.background", "Tree.background", "List.background",
            "Table.background", "TextArea.background", "EditorPane.background", "MainWindow.background",
        )
    }
}
