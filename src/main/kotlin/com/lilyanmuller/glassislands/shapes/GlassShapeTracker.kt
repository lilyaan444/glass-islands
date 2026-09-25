package com.lilyanmuller.glassislands.shapes

import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.impl.TabLabel
import com.lilyanmuller.glassislands.diagnostics.GlassMetrics
import com.lilyanmuller.glassislands.diagnostics.GlassMetrics.Operation
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.ContainerEvent
import java.awt.event.FocusEvent
import java.awt.event.WindowEvent
import java.awt.event.MouseEvent
import javax.swing.CellRendererPane
import javax.swing.SwingUtilities

/**
 * Follows the layout of a window, purely event driven and coalesced to one pass per EDT cycle:
 *  - a full [GlassShapeLocator.scan] when islands, bars or tabs move, appear or disappear. Resizes *inside* an
 *    island (the editor growing while typing, a tree expanding) cannot change any shape and are ignored;
 *  - a cheap pass over the scanned buttons and tabs when the hover or the selected editor tab changes.
 * [onComponentAdded] runs synchronously, before the new component is first painted; [onScan] after each full
 * scan; [onChange] only when the shapes actually differ. EDT only.
 */
class GlassShapeTracker(
    private val window: Window,
    private val onComponentAdded: (Component) -> Unit,
    private val onScan: (LayoutScan) -> Unit,
    private val onChange: (List<GlassShape>) -> Unit,
    /** Focus or activation changed in the window (components restyle themselves then); coalesced, after the IDE. */
    private val onFocusChange: () -> Unit = {},
) {
    /** Last full scan, reused while only hover or selection change. */
    var scan: LayoutScan? = null
        private set
    private var lastShapes: List<GlassShape>? = null
    private var hovered: Component? = null
    private var layoutPending = false
    private var interactivePending = false
    private var focusPending = false
    private var running = false

    private val listener = AWTEventListener { event ->
        GlassMetrics.tick(Operation.EVENT)
        when (event) {
            is FocusEvent -> if (belongsToWindow(event.component)) scheduleFocus()
            is WindowEvent -> if (event.window === window) scheduleFocus()
            is MouseEvent -> onMouse(event)
            is ContainerEvent -> onContainer(event)
            is ComponentEvent -> onComponent(event.component)
        }
    }

    val shapes: List<GlassShape> get() = lastShapes.orEmpty()

    fun start() {
        running = true
        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener,
            AWTEvent.COMPONENT_EVENT_MASK or AWTEvent.CONTAINER_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or
                AWTEvent.FOCUS_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK,
        )
        runLayout()
    }

    fun stop() {
        running = false
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        scan = null
        lastShapes = null
        hovered = null
    }

    private fun onContainer(event: ContainerEvent) {
        val container = event.container
        if (!belongsToWindow(container)) return
        // Cell renderers are added and removed at every paint of a tree, list or table: never a layout change.
        if (container is CellRendererPane) return
        if (event.id == ContainerEvent.COMPONENT_ADDED) onComponentAdded(event.child)
        if (affectsShapes(container) || event.child is TabLabel) scheduleLayout()
    }

    private fun onComponent(component: Component) {
        if (!belongsToWindow(component)) return
        when {
            component is TabLabel || affectsShapes(component) -> scheduleLayout()
            component.parent is JBTabs -> scheduleInteractive()
        }
    }

    private fun onMouse(event: MouseEvent) {
        if (event.id != MouseEvent.MOUSE_ENTERED && event.id != MouseEvent.MOUSE_EXITED) return
        val target = GlassShapeLocator.interactiveOf(event.component) ?: return
        if (!belongsToWindow(target)) return
        val next = if (event.id == MouseEvent.MOUSE_ENTERED) target else hovered.takeUnless { it === target }
        if (next === hovered) return
        hovered = next
        scheduleInteractive()
    }

    /** Only islands and what lies outside them position the shapes; content inside an island never does. */
    private fun affectsShapes(component: Component): Boolean {
        val island = GlassShapeLocator.islandOf(component)
        return island == null || island === component
    }

    private fun belongsToWindow(component: Component) =
        component === window || (component !is Window && SwingUtilities.getWindowAncestor(component) === window)

    private fun scheduleLayout() {
        if (layoutPending) return
        layoutPending = true
        SwingUtilities.invokeLater {
            layoutPending = false
            runLayout()
        }
    }

    private fun scheduleInteractive() {
        if (interactivePending || layoutPending) return
        interactivePending = true
        SwingUtilities.invokeLater {
            interactivePending = false
            GlassMetrics.measure(Operation.INTERACTIVE_PASS) { publish() }
        }
    }

    private fun scheduleFocus() {
        if (focusPending) return
        focusPending = true
        SwingUtilities.invokeLater {
            focusPending = false
            if (running) onFocusChange()
        }
    }

    private fun runLayout() {
        if (!running) return
        val next = GlassMetrics.measure(Operation.FULL_SCAN) { GlassShapeLocator.scan(window) }
        scan = next
        onScan(next)
        publish()
    }

    private fun publish() {
        if (!running) return
        val current = scan ?: return
        val shapes = current.islands + GlassShapeLocator.interactiveShapes(window, current, hovered)
        if (shapes == lastShapes) return
        lastShapes = shapes
        onChange(shapes)
    }
}
