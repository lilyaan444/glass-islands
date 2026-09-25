package com.lilyanmuller.glassislands.window

import com.intellij.openapi.diagnostic.logger
import com.lilyanmuller.glassislands.diagnostics.GlassMetrics
import com.lilyanmuller.glassislands.diagnostics.GlassMetrics.Operation
import com.lilyanmuller.glassislands.nativebridge.BackdropSpec
import com.lilyanmuller.glassislands.nativebridge.NativeBridge
import com.lilyanmuller.glassislands.shapes.IslandGeometry
import java.awt.Color
import java.awt.Window
import java.beans.PropertyChangeListener
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Glass of a window without islands: a popup (menu material, rounded like the popup window itself) or a floating
 * tool window (popover material). The whole window is one frosted backdrop and the surface-coloured panels of its
 * content are cleared. Same ordering as [GlassWindowController]: native first, Swing last; Swing opaque first on
 * removal. EDT only.
 */
internal class SecondaryWindowGlass(val window: Window, private val bridge: NativeBridge, val kind: Kind) {

    enum class Kind { POPUP, FLOATING }

    private val translucency = WindowTranslucency(window)
    private val clearing = SurfaceClearing(SURFACE_KEYS, transparentBackground = kind == Kind.POPUP)
    private var handle: Long? = null
    private var pushed: BackdropSpec? = null
    private var spec: BackdropSpec? = null

    /** A theme switch resets the window background, which turns the window opaque again: re-assert afterwards. */
    private val backgroundGuard = PropertyChangeListener { event ->
        val color = event.newValue as? Color ?: return@PropertyChangeListener
        if (translucency.isEnabled && color.alpha == 255) {
            translucency.adoptBackground(color)
            SwingUtilities.invokeLater { spec?.let(::apply) }
        }
    }


    val isTranslucent: Boolean get() = translucency.isEnabled

    /** For the debug report (state of the window and of its content). */
    fun describe(): String {
        val status = handle?.let(bridge::status)
        return "$kind ${window.javaClass.simpleName} ${window.width}x${window.height} showing=${window.isShowing} " +
            "translucent=${translucency.isEnabled} bg=${window.background?.alpha} native=[found=${status?.windowFound} " +
            "backdrop=${status?.backdropAttached} nonOpaque=${status?.windowNonOpaque} layerNonOpaque=${status?.layerNonOpaque}] " +
            "cleared=${clearing.clearedCount}\n" + tree((window as RootPaneContainer).rootPane, 3) +
            "\n      native: " + (handle?.let(bridge::describe) ?: "n/a").replace("\n", "\n      ")
    }

    /** Applies [spec] (from GlassPlan.popup or .floating), or removes the glass when `null`. */
    fun apply(spec: BackdropSpec?) {
        this.spec = spec
        if (spec == null) {
            remove()
            return
        }
        val nativeHandle = NativeWindowHandles.of(window) ?: return
        // A theme switch can recreate the native window of a popup: the new one is opaque and has no backdrop.
        val newPeer = handle != null && handle != nativeHandle
        if (newPeer) pushed = null
        handle = nativeHandle
        if (window.getPropertyChangeListeners("background").none { it === backgroundGuard }) {
            window.addPropertyChangeListener("background", backgroundGuard)
        }
        val sized = if (kind == Kind.POPUP) spec.copy(cornerRadius = IslandGeometry.popupRadius()) else spec
        try {
            if (sized != pushed) {
                GlassMetrics.measure(Operation.NATIVE_CALL) { bridge.applyBackdrop(nativeHandle, sized) }
                pushed = sized
            }
            if (translucency.isEnabled && !newPeer) translucency.reassert() else translucency.enable()
            refreshContent()
        } catch (e: ReflectiveOperationException) {
            fail(e)
        } catch (e: RuntimeException) {
            fail(e)
        } catch (e: LinkageError) {
            fail(e)
        }
    }

    /** Popups are reused with new content: cleared again every time they are shown. */
    fun refreshContent() {
        if (!translucency.isEnabled) return
        GlassMetrics.measure(Operation.CONTENT_REFRESH) { clearing.clear((window as RootPaneContainer).contentPane) }
    }

    fun remove() {
        spec = null
        window.removePropertyChangeListener("background", backgroundGuard)
        clearing.restoreAll()
        try {
            translucency.disable()
        } catch (e: ReflectiveOperationException) {
            LOG.warn("Cannot restore opacity of ${window.javaClass.name}", e)
        }
        window.repaint()
        handle?.let { nativeHandle ->
            try {
                bridge.removeBackdrop(nativeHandle)
            } catch (e: RuntimeException) {
                LOG.warn("Cannot remove backdrop of ${window.javaClass.name}", e)
            }
        }
        pushed = null
    }

    private fun tree(component: java.awt.Component, depth: Int, indent: String = "      "): String {
        val bg = component.background
        val line = "$indent${component.javaClass.name.substringAfterLast('.')} opaque=${component.isOpaque} bg=${bg?.let { "#%08X".format(it.rgb) }} ${component.width}x${component.height}"
        if (depth == 0 || component !is java.awt.Container) return line
        return (listOf(line) + component.components.take(6).map { tree(it, depth - 1, "$indent  ") }).joinToString("\n")
    }

    private fun fail(error: Throwable) {
        LOG.warn("Glass disabled for ${window.javaClass.name}", error)
        remove()
    }

    private companion object {
        val LOG = logger<SecondaryWindowGlass>()

        /** Plain backgrounds of popups (Islands: popup-bg / layer-1-bg) and of tool window content. */
        val SURFACE_KEYS = listOf(
            "Popup.background", "PopupMenu.background", "Popup.Header.activeBackground", "Popup.Header.inactiveBackground",
            "Popup.Toolbar.background", "Popup.Advertiser.background", "CompletionPopup.Advertiser.background",
            "SearchEverywhere.Advertiser.background", "SearchEverywhere.List.settingsBackground",
            "List.background", "Tree.background", "Panel.background", "ToolWindow.background", "Table.background",
        )
    }
}
