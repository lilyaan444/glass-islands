package com.lilyanmuller.glassislands.actions

import java.awt.Component
import java.awt.Container
import javax.swing.JComponent

/**
 * Lists opaque Swing components covering a large part of a frame: these hide the native material,
 * whatever the NSWindow state is.
 */
internal object OpaqueLayerScanner {
    private const val MIN_COVERAGE = 0.10

    fun scan(root: Container): List<String> {
        val frameArea = root.width.toDouble() * root.height
        if (frameArea <= 0) return emptyList()
        val result = mutableListOf<String>()
        fun visit(component: Component, depth: Int) {
            if (!component.isShowing) return
            val coverage = component.width.toDouble() * component.height / frameArea
            if (coverage < MIN_COVERAGE) return
            if (component is JComponent && component.isOpaque) {
                val bg = component.background
                result += "${"  ".repeat(depth)}${component.javaClass.name} ${percent(coverage)} bg=${bg?.let { "#%08X".format(it.rgb) }}"
            }
            if (component is Container) component.components.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return result
    }

    private fun percent(value: Double) = "${(value * 100).toInt()}%"

    /**
     * Every opaque component of at least 16 px: with a translucent back buffer Swing clears a repainted region with
     * the background of the first opaque component it meets, so an opaque component that paints nothing (its
     * surface was made transparent) flashes its opaque background at each partial repaint.
     */
    /**
     * The component Swing starts a partial repaint from at each point (first opaque ancestor, or the topmost
     * component when none is opaque) and the colour the translucent back buffer is cleared with there.
     */
    fun paintOrigins(root: Container): List<String> {
        val points = listOf(0.5 to 0.3, 0.5 to 0.8, 0.1 to 0.5, 0.9 to 0.5, 0.5 to 0.99, 0.5 to 0.01, 0.02 to 0.5)
        return points.map { (fx, fy) ->
            val x = (root.width * fx).toInt()
            val y = (root.height * fy).toInt()
            var deepest: Component? = javax.swing.SwingUtilities.getDeepestComponentAt(root, x, y)
            val leaf = deepest?.javaClass?.name?.substringAfterLast('.')
            var origin: Component? = null
            var top: Component? = null
            while (deepest != null && deepest !is java.awt.Window) {
                if (deepest is JComponent) top = deepest
                if (origin == null && deepest.isOpaque && deepest is JComponent) origin = deepest
                deepest = deepest.parent
            }
            val chosen = origin ?: top
            "  at ${(fx * 100).toInt()}%,${(fy * 100).toInt()}% leaf=$leaf origin=${chosen?.javaClass?.name?.substringAfterLast('.')} opaque=${chosen?.isOpaque} clear=${chosen?.background?.let { "#%08X".format(it.rgb) }}"
        }
    }

    fun opaqueComponents(root: Container): List<String> {
        val result = mutableListOf<String>()
        fun visit(component: Component, inIsland: Boolean) {
            if (!component.isShowing) return
            val island = inIsland || com.lilyanmuller.glassislands.shapes.GlassShapeLocator.isIsland(component)
            if (component is JComponent && component.isOpaque && component.width >= 16 && component.height >= 16) {
                val bg = component.background
                result += "${component.javaClass.name.substringAfterLast('.')} ${component.width}x${component.height} bg=${bg?.let { "#%08X".format(it.rgb) }} island=$island parent=${component.parent?.javaClass?.name?.substringAfterLast('.')}"
            }
            if (component is Container) component.components.forEach { visit(it, island) }
        }
        visit(root, false)
        return result
    }
}
