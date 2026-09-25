package com.lilyanmuller.glassislands.window

import com.lilyanmuller.glassislands.internals.PlatformInternals
import java.awt.Color
import java.awt.Window
import javax.swing.JComponent
import javax.swing.RepaintManager
import javax.swing.RootPaneContainer

/**
 * Switches a decorated IDE frame to per-pixel translucency, which the public AWT API refuses
 * (`Frame.setBackground` throws for decorated frames). It reproduces what `Window.setBackground`
 * does for undecorated windows:
 *  - `Component.background` gets alpha 0, so `Window.isOpaque()` is false and Swing paints into
 *    translucent back buffers;
 *  - `LWWindowPeer.setOpaque(false)` makes the NSWindow and its MTLLayer non-opaque;
 *  - the root pane leaves "true double buffering" (IntelliJ runs with `swing.bufferPerWindow=true`):
 *    that path flips the window BufferStrategy with SrcOver (`MTLGraphicsConfig.flip`), so translucent
 *    pixels pile up until opaque, whereas classic double buffering blits them with `AlphaComposite.Src`.
 * All rely on JDK internals opened to plugins by Rider's `--add-opens` for `java.desktop/java.awt`,
 * `javax.swing` and `sun.lwawt` (see [PlatformInternals]). Works for any Swing window (frames, dialogs, popups).
 * Must be used on the EDT.
 */
internal class WindowTranslucency(private val frame: Window) {

    private val container = frame as RootPaneContainer

    private var originalBackground: Color? = null

    /** Rounded popups are already non-opaque windows: they must stay so when the glass goes away. */
    private var originallyOpaque = true
    private val originalOpacity = mutableMapOf<JComponent, Boolean>()

    var isEnabled = false
        private set

    fun enable() {
        if (!isEnabled) {
            originalBackground = frame.background
            originallyOpaque = frame.isOpaque
            panes().forEach { originalOpacity[it] = it.isOpaque }
        }
        background().set(frame, TRANSPARENT)
        panes().forEach { it.isOpaque = false }
        setPeerOpaque(false)
        setTrueDoubleBuffering(false)
        isEnabled = true
    }

    /** Records an opaque background set by IDE code while translucent, so [disable] restores the latest one. */
    fun adoptBackground(color: Color) {
        originalBackground = color
    }

    /**
     * IDE code may make the window opaque again behind our back: a theme switch calls `Window.setBackground`,
     * which also turns the peer opaque, and `updateUI` makes the root panes opaque. Re-applies translucency only
     * when one of those actually happened, so it is cheap to call on every refresh.
     */
    fun reassert() {
        if (!isEnabled) return
        val drifted = frame.background?.alpha != 0 || panes().any { it.isOpaque }
        if (drifted) enable()
    }

    fun disable() {
        if (!isEnabled) return
        background().set(frame, originalBackground)
        originalOpacity.forEach { (pane, opaque) -> pane.isOpaque = opaque }
        originalOpacity.clear()
        setPeerOpaque(originallyOpaque)
        setTrueDoubleBuffering(true)
        isEnabled = false
    }

    private fun panes(): List<JComponent> =
        listOfNotNull(container.rootPane, container.layeredPane, container.contentPane as? JComponent)

    private fun background() = PlatformInternals.componentBackground ?: throw missing()

    private fun setPeerOpaque(opaque: Boolean) {
        val getPeer = PlatformInternals.getPeer ?: throw missing()
        val peer = getPeer.invoke(PlatformInternals.componentAccessor, frame) ?: return
        val setOpaque = peer.javaClass.getMethod("setOpaque", Boolean::class.javaPrimitiveType)
        setOpaque.isAccessible = true
        setOpaque.invoke(peer, opaque)
    }

    /** Mirrors `JRootPane.disableTrueDoubleBuffering()`, which also drops the window BufferStrategy. */
    private fun setTrueDoubleBuffering(enabled: Boolean) {
        val rootPane = container.rootPane ?: return
        val field = PlatformInternals.useTrueDoubleBuffering ?: throw missing()
        val changed = PlatformInternals.doubleBufferingChanged ?: throw missing()
        if (field.getBoolean(rootPane) == enabled) return
        field.setBoolean(rootPane, enabled)
        changed.invoke(RepaintManager.currentManager(rootPane), rootPane)
    }

    private fun missing() = NoSuchFieldException(PlatformInternals.report())

    private companion object {
        val TRANSPARENT = Color(0, 0, 0, 0)
    }
}
