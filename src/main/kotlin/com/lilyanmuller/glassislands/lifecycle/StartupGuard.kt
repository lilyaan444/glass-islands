package com.lilyanmuller.glassislands.lifecycle

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Timer

/**
 * Safe mode. A marker file exists from the moment the glass is first applied in a session until it has been on
 * screen for a while; if Rider is killed or crashes in between, the marker is still there at the next start and
 * the glass is turned off before anything native runs. Costs two tiny file operations per session.
 */
internal class StartupGuard(private val marker: Path = defaultMarker()) {

    private var armed = false
    private var confirmation: Timer? = null

    /** True when the previous session stopped while the glass was being applied. Clears the marker. */
    fun previousSessionFailed(): Boolean {
        val failed = Files.exists(marker)
        if (failed) {
            LOG.warn("Rider stopped while Liquid Glass was being applied in the previous session: turning it off")
            clear()
        }
        return failed
    }

    /** Call right before the first native change of the session. */
    fun arm() {
        if (armed) return
        armed = true
        try {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, ProcessHandle.current().pid().toString())
        } catch (e: IOException) {
            LOG.debug("Cannot write safe-mode marker", e)
        }
        confirmation = Timer(CONFIRMATION_DELAY_MS) { clear() }.apply {
            isRepeats = false
            start()
        }
    }

    /** The glass has been shown long enough, or Rider is closing normally. */
    fun clear() {
        confirmation?.stop()
        confirmation = null
        try {
            Files.deleteIfExists(marker)
        } catch (e: IOException) {
            LOG.debug("Cannot delete safe-mode marker", e)
        }
    }

    companion object {
        private val LOG = logger<StartupGuard>()

        /** Long enough to cover startup, the first layout passes and the first focus changes. */
        const val CONFIRMATION_DELAY_MS = 15_000

        fun defaultMarker(): Path = PathManager.getSystemDir().resolve("glass-islands").resolve("applying.marker")
    }
}
