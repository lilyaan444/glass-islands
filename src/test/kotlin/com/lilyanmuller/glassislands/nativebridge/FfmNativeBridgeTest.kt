package com.lilyanmuller.glassislands.nativebridge

import com.lilyanmuller.glassislands.environment.NativePlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Loads the real dylib built by `buildNativeBridge`; skipped outside macOS. */
class FfmNativeBridgeTest {
    private fun bridge(): FfmNativeBridge {
        assumeTrue(NativePlatform.current() != null, "macOS only")
        val library = NativeBridgeLoader.libraryPath(Path.of(System.getProperty("rlg.testPluginRoot")))
        assumeTrue(Files.isRegularFile(library), "native bridge not built")
        return FfmNativeBridge(library)
    }

    @Test
    fun `reports the running macOS version`() {
        val bridge = bridge()
        assertEquals(System.getProperty("os.version").substringBefore('.').toInt(), bridge.osMajorVersion)
        assertEquals(bridge.osMajorVersion >= 26, bridge.glassAvailable)
    }

    @Test
    fun `unknown windows have no recorded state`() {
        val bridge = bridge()
        assertEquals(NativeWindowStatus.UNKNOWN, bridge.status(0x1234))
        assertTrue(bridge.describe(0x1234).contains("not found"))
    }

    @Test
    fun `accessibility options are readable`() {
        val options = bridge().accessibilityOptions()
        assertTrue(options.flags in 0..7)
    }

    @Test
    fun `a closed bridge is inert`() {
        val bridge = bridge()
        bridge.close()
        assertEquals(NativeWindowStatus.UNKNOWN, bridge.status(0x1234))
        bridge.removeIslands(0x1234)
        bridge.close()
    }

    @Test
    fun `accessibility flags decode independently`() {
        val options = AccessibilityOptions(1 or 4)
        assertTrue(options.reduceTransparency && options.reduceMotion)
        assertEquals(false, options.increaseContrast)
    }

    @Test
    fun `status flags decode independently`() {
        val status = NativeWindowStatus(1 or 2 or 8)
        assertTrue(status.windowFound && status.backdropAttached && status.windowNonOpaque)
        assertEquals(false, status.glassBackend)
    }
}
