package com.lilyanmuller.glassislands.window

import com.intellij.openapi.diagnostic.logger
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.image.BufferedImage
import java.util.WeakHashMap
import javax.swing.JComponent
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Removes the flicker of partial repaints on the glass. In a translucent window Swing repaints a region by clearing
 * it with the background of the first *opaque* component above it (RepaintManager.PaintManager, translucent back
 * buffer), then painting that component. An opaque component that does not cover its bounds (its surface became
 * transparent, e.g. the status bar or a tool-window header) therefore flashes its opaque background at every
 * partial repaint: typing, scrolling, logs, focus changes.
 *
 * Opaque components with a *transparent* background are just as harmful: Swing skips painting whatever an opaque
 * sibling covers (JComponent.paintChildren, obscured-sibling optimization), so the content under such a component
 * vanishes for one frame on some partial repaints (a whole diff panel blinking while scrolling).
 *
 * Each opaque component is painted once into an offscreen image; if it leaves pixels uncovered it is made
 * non-opaque, which changes nothing visually (those pixels were never its own) but makes Swing repaint the region
 * from an ancestor that clears and paints it completely. Verdicts are cached per size and background; sweeps run on
 * layout, focus and activation changes. EDT only.
 */
internal class OpacityGuard(private val window: Window) {

    private val cleared = WeakHashMap<JComponent, Unit>()

    /** Components found to cover their bounds, with the signature (size, background) they were checked with. */
    private val covering = WeakHashMap<JComponent, Long>()

    val clearedCount: Int get() = cleared.size

    fun describe(): List<String> = cleared.keys.map { "${it.javaClass.name.substringAfterLast('.')} ${it.width}x${it.height}" }

    fun sweep() {
        fun visit(component: Component) {
            if (!component.isShowing) return
            if (component is JComponent && component.isOpaque) check(component)
            if (component is Container) component.components.forEach(::visit)
        }
        (window as? Container)?.components?.forEach(::visit)
    }

    fun restoreAll() {
        cleared.keys.forEach {
            it.isOpaque = true
            it.repaint()
        }
        cleared.clear()
        covering.clear()
    }

    private fun check(component: JComponent) {
        if (component.width < MIN_SIZE || component.height < MIN_SIZE) return
        val background = component.background?.rgb ?: 0
        val signature = (component.width.toLong() shl 40) or (component.height.toLong() shl 20) xor background.toLong()
        if (covering[component] == signature) return
        if (coversBounds(component)) {
            covering[component] = signature
        } else {
            covering.remove(component)
            component.isOpaque = false
            cleared[component] = Unit
            component.repaint()
        }
    }

    /** Paints the component (with its children) offscreen and checks that no pixel is left transparent. */
    private fun coversBounds(component: JComponent): Boolean {
        val area = component.width.toDouble() * component.height
        val scale = if (area > MAX_TEST_AREA) sqrt(MAX_TEST_AREA / area) else 1.0
        val width = max(1, ceil(component.width * scale).toInt())
        val height = max(1, ceil(component.height * scale).toInt())
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.scale(scale, scale)
            component.paint(graphics)
        } catch (e: RuntimeException) {
            LOG.debug("Cannot test-paint ${component.javaClass.name}", e)
            return true
        } finally {
            graphics.dispose()
        }
        // Rounded corners and borders are covered by the ancestors either way; sample the inside densely.
        val step = max(1, minOf(width, height) / 32)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if ((image.getRGB(x, y) ushr 24) < OPAQUE_ALPHA) return false
                x += step
            }
            y += step
        }
        return true
    }

    private companion object {
        val LOG = logger<OpacityGuard>()
        const val MIN_SIZE = 8
        const val MAX_TEST_AREA = 250_000.0
        const val OPAQUE_ALPHA = 250
    }
}
