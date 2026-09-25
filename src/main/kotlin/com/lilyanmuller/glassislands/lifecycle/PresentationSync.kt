package com.lilyanmuller.glassislands.lifecycle

import com.intellij.diagnostic.VMOptions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.lilyanmuller.glassislands.settings.LiquidGlassSettings
import java.io.IOException

/**
 * Removes the last source of flicker on the glass, which lives in the JetBrains Runtime rather than in Swing. By
 * default JBR's Metal pipeline presents window frames from a display link, asynchronously from Swing painting; on
 * an opaque window a frame caught mid-update is invisible, but on a translucent one it shows as a blank flash (a
 * whole panel, or the whole window, empty for one frame). With `sun.java2d.metal.displaySync=false` JBR presents
 * each frame when Swing has finished it. Measured on screen recordings: two to three blank frames per 12 s of
 * scrolling a diff before, none after.
 *
 * JBR reads the property once at startup and nothing can change it in a running IDE, so while the glass is on the
 * plugin writes it to the IDE's own user VM options (Help | Edit Custom VM Options) and asks for a restart once. The
 * "Flicker-free rendering" setting turns this off, and the option is removed if the plugin is uninstalled.
 */
internal object PresentationSync {
    private val LOG = logger<PresentationSync>()
    const val PROPERTY = "sun.java2d.metal.displaySync"

    /** Frames are presented synchronously in this session. */
    val isActive: Boolean get() = System.getProperty(PROPERTY) == "false"

    /** The option is written for the next start (active after a restart). */
    val isConfigured: Boolean
        get() = try {
            VMOptions.readOption("-D$PROPERTY=", true) == "false"
        } catch (e: RuntimeException) {
            false
        }

    val canConfigure: Boolean get() = VMOptions.canWriteOptions()

    /**
     * Brings the user VM options in line with [wanted]. Returns true when the option was just written, so the caller
     * can tell the user that a restart applies it.
     */
    fun sync(wanted: Boolean): Boolean {
        if (!canConfigure) return false
        val settings = LiquidGlassSettings.getInstance()
        return try {
            when {
                wanted && !isConfigured -> {
                    VMOptions.setProperty(PROPERTY, "false")
                    settings.updateSilently { it.copy(displaySyncManaged = true) }
                    LOG.info("Wrote -D$PROPERTY=false to the user VM options")
                    !isActive
                }
                !wanted && settings.snapshot().displaySyncManaged && isConfigured -> {
                    removeIfManaged()
                    false
                }
                else -> false
            }
        } catch (e: IOException) {
            LOG.warn("Cannot update $PROPERTY in the user VM options", e)
            false
        }
    }

    fun restart() = ApplicationManager.getApplication().restart()

    /** Called when the plugin is uninstalled or disabled: gives the IDE back its default presentation. */
    fun removeIfManaged() {
        val settings = LiquidGlassSettings.getInstance()
        if (!settings.snapshot().displaySyncManaged) return
        try {
            VMOptions.setProperty(PROPERTY, null)
            settings.updateSilently { it.copy(displaySyncManaged = false) }
        } catch (e: IOException) {
            LOG.warn("Cannot remove $PROPERTY from the user VM options", e)
        }
    }
}
