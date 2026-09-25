package com.lilyanmuller.glassislands.window

import com.lilyanmuller.glassislands.diagnostics.GlassMetrics
import com.lilyanmuller.glassislands.diagnostics.GlassMetrics.Operation
import com.lilyanmuller.glassislands.internals.PlatformInternals
import com.lilyanmuller.glassislands.plan.GlassPlan
import com.lilyanmuller.glassislands.nativebridge.NativeBridge
import com.lilyanmuller.glassislands.shapes.GlassShapeLocator
import java.awt.AWTEvent
import java.awt.Container
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.UIResource

/**
 * Brings the glass to the other windows of a glass project frame as they open: popups and menus (menu material),
 * floating tool windows (popover material) and detached editor windows (islands, like the main window). Dialogs
 * keep Rider's opaque look, as macOS sheets and dialogs do. Only listens while the glass is on. EDT only.
 */
internal class SecondaryWindowWatcher(
    private val bridge: NativeBridge,
    private val isProjectFrame: (Window) -> Boolean,
    private val onRevealed: () -> Unit,
) {
    private val glasses = LinkedHashMap<Window, SecondaryWindowGlass>()
    private val editorWindows = LinkedHashMap<Window, GlassWindowController>()
    private var plan: GlassPlan? = null
    private var listening = false

    private val listener = AWTEventListener { event ->
        val window = (event as? ComponentEvent)?.component as? Window ?: return@AWTEventListener
        GlassMetrics.tick(Operation.EVENT)
        when (event.id) {
            WindowEvent.WINDOW_OPENED, ComponentEvent.COMPONENT_SHOWN -> onShown(window)
            WindowEvent.WINDOW_CLOSED -> onClosed(window)
        }
    }

    val windowCount: Int get() = glasses.size + editorWindows.size

    fun describe(): String =
        (glasses.values.map { "\n    " + it.describe() } +
            editorWindows.values.map { "EDITOR_WINDOW ${it.frame.javaClass.simpleName}" })
            .ifEmpty { listOf("none") }.joinToString()

    fun apply(newPlan: GlassPlan?) {
        plan = newPlan
        if (newPlan == null) {
            stop()
            return
        }
        if (!listening) {
            listening = true
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK or AWTEvent.COMPONENT_EVENT_MASK)
            Window.getWindows().filter { it.isShowing }.forEach(::onShown)
        }
        glasses.values.forEach { it.apply(specFor(it.kind, newPlan)) }
        editorWindows.values.forEach { it.apply(newPlan) }
    }

    /**
     * After a theme switch, open popups go back to Rider's own look: list cell renderers keep colours of the old
     * theme that no clearing can reach, which would be unreadable on the new glass. Popups opened afterwards get the
     * glass again. Theme switches only (including the theme preview of Quick Switch).
     */
    fun onThemeChanged() {
        val popups = glasses.filterValues { it.kind == SecondaryWindowGlass.Kind.POPUP }
        popups.forEach { (window, glass) ->
            glass.remove()
            glasses.remove(window)
            // The background restored with the window is a theme colour of the previous theme (Swing popups use
            // Panel.background): take the new theme's, as the IDE does for newly created popups.
            if (window.background is UIResource) UIManager.getColor("Panel.background")?.let { window.background = it }
            SwingUtilities.updateComponentTreeUI(window)
            window.repaint()
        }
    }

    fun dispose() = stop()

    private fun stop() {
        if (listening) {
            listening = false
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        }
        glasses.values.forEach(SecondaryWindowGlass::remove)
        glasses.clear()
        editorWindows.values.forEach(GlassWindowController::dispose)
        editorWindows.clear()
    }

    private fun onShown(window: Window, retry: Boolean = true) {
        val current = plan ?: return
        glasses[window]?.let {
            it.apply(specFor(it.kind, current))
            return
        }
        if (window in editorWindows || isProjectFrame(window)) return
        if (window.width < MIN_SIZE || window.height < MIN_SIZE) return
        LOG.debug("Window shown: ${window.javaClass.name} type=${window.type} owner=${window.owner?.javaClass?.name}")
        // Detached editor windows are frames of their own (no owner); their content may arrive after they open.
        if (window is JFrame) {
            if (containsEditorIsland(window)) {
                editorWindows[window] = GlassWindowController(window, bridge, onRevealed).also { it.apply(current) }
            } else if (retry) {
                SwingUtilities.invokeLater { if (window.isShowing) onShown(window, retry = false) }
            }
            return
        }
        if (!ownedByProjectFrame(window)) return
        when {
            GlassShapeLocator.isA(window, PlatformInternals.FLOATING_TOOL_WINDOW) ->
                glasses[window] = SecondaryWindowGlass(window, bridge, SecondaryWindowGlass.Kind.FLOATING).also { it.apply(current.floating) }
            window is JWindow || window.type == Window.Type.POPUP ->
                glasses[window] = SecondaryWindowGlass(window, bridge, SecondaryWindowGlass.Kind.POPUP).also { it.apply(current.popup) }
        }
    }

    /** Closed windows are gone with their native views: only forget them. */
    private fun onClosed(window: Window) {
        glasses.remove(window)
        editorWindows.remove(window)?.dispose()
    }

    private fun specFor(kind: SecondaryWindowGlass.Kind, plan: GlassPlan) =
        if (kind == SecondaryWindowGlass.Kind.POPUP) plan.popup else plan.floating

    private fun ownedByProjectFrame(window: Window): Boolean {
        var owner: Window? = window.owner
        while (owner != null) {
            if (isProjectFrame(owner)) return true
            owner = owner.owner
        }
        return false
    }

    private fun containsEditorIsland(window: Window): Boolean {
        fun search(container: Container): Boolean = container.components.any {
            GlassShapeLocator.isA(it, PlatformInternals.EDITOR_ISLAND) || (it is Container && search(it))
        }
        return search(window)
    }

    private companion object {
        val LOG = com.intellij.openapi.diagnostic.logger<SecondaryWindowWatcher>()
        const val MIN_SIZE = 24
    }
}
