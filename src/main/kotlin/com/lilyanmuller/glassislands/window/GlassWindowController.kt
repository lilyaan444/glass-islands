package com.lilyanmuller.glassislands.window

import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.diagnostic.logger
import com.lilyanmuller.glassislands.diagnostics.GlassMetrics
import com.lilyanmuller.glassislands.diagnostics.GlassMetrics.Operation
import com.lilyanmuller.glassislands.nativebridge.BackdropSpec
import com.lilyanmuller.glassislands.nativebridge.IslandShape
import com.lilyanmuller.glassislands.nativebridge.NativeBridge
import com.lilyanmuller.glassislands.nativebridge.NativeWindowStatus
import com.lilyanmuller.glassislands.nativebridge.ShapeRole
import com.lilyanmuller.glassislands.plan.GlassPlan
import com.lilyanmuller.glassislands.shapes.GlassShape
import com.lilyanmuller.glassislands.shapes.GlassShapeKind
import com.lilyanmuller.glassislands.shapes.GlassShapeTracker
import com.lilyanmuller.glassislands.shapes.LayoutScan
import java.awt.Color
import java.awt.Component
import java.awt.Window
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities

/**
 * Owns the glass state of one IDE window (a project frame or a detached editor window). EDT only.
 *
 * Order matters to avoid flashes: native calls are synchronous, so the glass is in place before anything in
 * Swing turns transparent, and Swing is opaque again before the glass goes away. [onRevealed] lets the registry
 * switch the global Swing surfaces in the same EDT turn as the window itself. The native side is only called
 * when what it would draw actually changes.
 */
internal class GlassWindowController(
    val frame: Window,
    private val bridge: NativeBridge,
    private val onRevealed: () -> Unit,
) {
    private val translucency = WindowTranslucency(frame)
    private val editors = EditorTransparency(frame)
    private val islandContent = IslandContentTransparency(frame)
    private val buttonLooks = GlassButtonLooks()
    private val opacityGuard = OpacityGuard(frame)
    private var plan: GlassPlan? = null
    private var windowHandle: Long? = null
    private var shapeTracker: GlassShapeTracker? = null
    private var pushedBackground: BackdropSpec? = null
    private var pushedShapes: Pair<List<IslandShape>, BackdropSpec>? = null

    var lastError: String? = null
        private set

    /** How many times IDE code reset an opaque frame background that had to be made translucent again. */
    var backgroundResets = 0
        private set

    /** Native updates actually sent; stays still while nothing visible changes. */
    var nativeUpdates = 0
        private set

    /** What changed in the last shape update, for the debug report. */
    var lastShapeChange = "none"
        private set

    /** IDE code may reset an opaque frame background (theme switch); translucency is restored right after. */
    private val backgroundGuard = PropertyChangeListener { event ->
        val color = event.newValue as? Color ?: return@PropertyChangeListener
        if (translucency.isEnabled && color.alpha == 255) {
            backgroundResets++
            LOG.debug("Frame background reset to opaque by IDE code", Throwable())
            translucency.adoptBackground(color)
            // Window.setBackground turns the peer opaque right after notifying us: re-assert once it is done.
            SwingUtilities.invokeLater { plan?.let(::apply) }
        }
    }

    init {
        frame.addPropertyChangeListener("background", backgroundGuard)
    }

    val nativeHandle: Long? get() = windowHandle

    /** True once the window and its Swing content actually show the glass. */
    val isTranslucent: Boolean get() = translucency.isEnabled

    val glassShapes: List<GlassShape> get() = shapeTracker?.shapes.orEmpty()

    val scanSummary: String get() = shapeTracker?.scan?.summary() ?: "no scan yet"

    val transparentEditorCount: Int get() = editors.editorCount

    val clearedPanelCount: Int get() = islandContent.clearedCount

    /** Opaque components that did not cover their bounds, made non-opaque against partial-repaint flicker. */
    val flickerFixes: List<String> get() = opacityGuard.describe()

    fun apply(newPlan: GlassPlan?) {
        val previous = plan
        plan = newPlan
        if (newPlan == null) {
            removeGlass()
            return
        }
        val handle = NativeWindowHandles.of(frame)
        if (handle == null) {
            fail("NSWindow not found for ${frame.javaClass.name}", null)
            return
        }
        if (windowHandle != handle) LOG.info("NSWindow found: 0x${handle.toString(16)} for ${frame.javaClass.simpleName}")
        // A recreated native window (new peer) is opaque and bare: push everything again and re-assert translucency.
        val newPeer = windowHandle != null && windowHandle != handle
        if (newPeer) {
            pushedBackground = null
            pushedShapes = null
        }
        windowHandle = handle
        try {
            pushBackground(handle, newPlan.background)
            if (previous != null && previous.glassControls && !newPlan.glassControls) buttonLooks.restoreAll()
            trackLayout()
            if (!translucency.isEnabled) {
                translucency.enable()
                onRevealed()
            } else if (newPeer) {
                translucency.enable()
            } else {
                translucency.reassert()
            }
            refreshContent()
            frame.repaint()
            lastError = null
        } catch (e: ReflectiveOperationException) {
            fail("cannot make the frame translucent", e)
        } catch (e: RuntimeException) {
            fail("native bridge call failed", e)
        } catch (e: LinkageError) {
            fail("native bridge call failed", e)
        }
    }

    fun status(): NativeWindowStatus = windowHandle?.let(bridge::status) ?: NativeWindowStatus.UNKNOWN

    fun describeNative(): String = windowHandle?.let(bridge::describe) ?: "no NSWindow resolved yet"

    fun dispose() {
        frame.removePropertyChangeListener("background", backgroundGuard)
        plan = null
        removeGlass()
    }

    /** Starts following the layout on first use; later plans are pushed with the shapes already known. */
    private fun trackLayout() {
        val tracker = shapeTracker
        if (tracker == null) {
            GlassShapeTracker(frame, ::onComponentAdded, ::onScan, ::pushShapes, ::guardOpacity).also { shapeTracker = it }.start()
        } else {
            tracker.scan?.let(::onScan)
            pushShapes(tracker.shapes)
        }
    }

    /** Clears new panels and editors synchronously, before their first paint, so they never flash opaque. */
    private fun onComponentAdded(component: Component) {
        if (!translucency.isEnabled) return
        GlassMetrics.measure(Operation.CONTENT_REFRESH) {
            islandContent.clearIfInIsland(component)
            editors.refresh()
        }
    }

    private fun onScan(scan: LayoutScan) {
        if (plan?.glassControls == true) buttonLooks.install(scan.controls.mapNotNull { it.first as? ActionButton })
        guardOpacity()
    }

    private fun refreshContent() {
        if (!translucency.isEnabled) return
        editors.refresh()
        islandContent.refresh()
        guardOpacity()
    }

    private fun guardOpacity() {
        if (!translucency.isEnabled) return
        GlassMetrics.measure(Operation.OPACITY_SWEEP) { opacityGuard.sweep() }
    }

    private fun pushBackground(handle: Long, background: BackdropSpec) {
        if (background == pushedBackground) return
        pushedBackground = background
        nativeUpdates++
        GlassMetrics.measure(Operation.NATIVE_CALL) { bridge.applyBackdrop(handle, background) }
    }

    private fun pushShapes(shapes: List<GlassShape>) {
        val handle = windowHandle ?: return
        val islands = plan?.islands ?: return
        val nativeShapes = shapes.mapNotNull { shape ->
            val tint = islands.tintByKind[shape.kind] ?: return@mapNotNull null
            IslandShape(shape.bounds, tint, shape.cornerRadius, shadowOf(shape.kind), roleOf(shape.kind), shape.key)
        }
        val next = nativeShapes to islands.material
        val previous = pushedShapes
        if (next == previous) return
        lastShapeChange = describeChange(previous, next)
        pushedShapes = next
        nativeUpdates++
        GlassMetrics.measure(Operation.NATIVE_CALL) { bridge.setIslands(handle, nativeShapes, islands.material) }
    }

    private fun stopTrackingShapes() {
        shapeTracker?.stop()
        shapeTracker = null
    }

    /** Swing opaque first, native glass last: nothing ever shows through an empty window. */
    private fun removeGlass() {
        stopTrackingShapes()
        buttonLooks.restoreAll()
        editors.restoreAll()
        islandContent.restoreAll()
        opacityGuard.restoreAll()
        try {
            translucency.disable()
        } catch (e: ReflectiveOperationException) {
            LOG.warn("Cannot restore frame opacity", e)
        }
        frame.repaint()
        try {
            windowHandle?.let { handle ->
                bridge.removeBackdrop(handle)
                bridge.removeIslands(handle)
            }
        } catch (e: RuntimeException) {
            LOG.warn("Cannot remove native backdrop", e)
        }
        pushedBackground = null
        pushedShapes = null
    }

    private fun fail(reason: String, error: Throwable?) {
        if (lastError == null) LOG.warn("Liquid Glass disabled for this window: $reason", error)
        lastError = reason + (error?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        plan = null
        removeGlass()
    }

    private companion object {
        val LOG = logger<GlassWindowController>()

        /** Islands float with a full shadow, selected pills slightly; hovered ones stay flat. */
        fun shadowOf(kind: GlassShapeKind) = when (kind) {
            GlassShapeKind.TOOL_WINDOW, GlassShapeKind.EDITOR -> 1.0
            GlassShapeKind.FOCUSED_CONTROL -> 0.6
            GlassShapeKind.SELECTED_CONTROL, GlassShapeKind.SELECTED_TAB -> 0.35
            GlassShapeKind.HOVERED_CONTROL, GlassShapeKind.HOVERED_TAB -> 0.0
        }

        fun describeChange(previous: Pair<List<IslandShape>, BackdropSpec>?, next: Pair<List<IslandShape>, BackdropSpec>): String {
            if (previous == null) return "initial"
            if (previous.second != next.second) return "material"
            val before = previous.first.associateBy { it.key }
            val after = next.first.associateBy { it.key }
            val added = (after.keys - before.keys).size
            val removed = (before.keys - after.keys).size
            val changed = after.count { (key, shape) -> before[key]?.let { it != shape } == true }
            return "added=$added removed=$removed changed=$changed"
        }

        fun roleOf(kind: GlassShapeKind) = when (kind) {
            GlassShapeKind.TOOL_WINDOW, GlassShapeKind.EDITOR -> ShapeRole.SURFACE
            else -> ShapeRole.CONTROL
        }
    }
}
