package com.lilyanmuller.glassislands.window

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.mac.foundation.MacUtil
import java.awt.Window

/**
 * Resolves the `NSWindow*` behind an AWT window through the platform's own
 * [MacUtil.getWindowFromJavaWindow] (public, not marked internal). Internally it reads
 * `CFRetainedResource.ptr` of the JBR `CPlatformWindow` peer; the window must be displayable.
 */
internal object NativeWindowHandles {
    private val LOG = logger<NativeWindowHandles>()

    fun of(window: Window): Long? {
        if (!window.isDisplayable) return null
        return try {
            MacUtil.getWindowFromJavaWindow(window).toLong().takeIf { it != 0L }
        } catch (e: RuntimeException) {
            LOG.warn("Cannot resolve NSWindow for ${window.javaClass.name}", e)
            null
        }
    }
}
