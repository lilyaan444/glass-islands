package com.lilyanmuller.glassislands.nativebridge

import com.lilyanmuller.glassislands.environment.NativePlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class NativeBridgeLoaderTest {
    @TempDir
    lateinit var pluginRoot: Path

    @Test
    fun `non macOS platforms get an unavailable bridge`() {
        val bridge = NativeBridgeLoader.load(pluginRoot, platform = null)
        assertFalse(bridge.isAvailable)
        assertEquals(NativeWindowStatus.UNKNOWN, bridge.status(42))
    }

    @Test
    fun `missing library disables the glass cleanly`() {
        val bridge = NativeBridgeLoader.load(pluginRoot, NativePlatform.MAC_ARM64)
        assertFalse(bridge.isAvailable)
        assertTrue(bridge.description.contains("missing"))
    }

    @Test
    fun `link errors are turned into an unavailable bridge`() {
        installFakeLibrary()
        val bridge = NativeBridgeLoader.load(pluginRoot, NativePlatform.MAC_ARM64) { _ -> throw UnsatisfiedLinkError("bad arch") }
        assertFalse(bridge.isAvailable)
        assertTrue(bridge.description.contains("bad arch"))
    }

    @Test
    fun `one universal library serves every architecture`() {
        installFakeLibrary()
        for (platform in NativePlatform.entries) {
            val expected = UnavailableNativeBridge("stub")
            var requested: Path? = null
            val bridge = NativeBridgeLoader.load(pluginRoot, platform) { requested = it; expected }
            assertSame(expected, bridge)
            assertEquals(pluginRoot.resolve("native/libliquidglass.dylib"), requested)
        }
    }

    @Test
    fun `checked exceptions from the loader are contained`() {
        installFakeLibrary()
        val bridge = NativeBridgeLoader.load(pluginRoot, NativePlatform.MAC_ARM64) { _ -> throw java.io.IOException("io") }
        assertFalse(bridge.isAvailable)
    }

    @Test
    fun `the library is loaded from a copy named after its content`(@TempDir cache: Path) {
        installFakeLibrary()
        var requested: Path? = null
        NativeBridgeLoader.load(pluginRoot, NativePlatform.MAC_ARM64, cache) { requested = it; UnavailableNativeBridge("stub") }
        val first = requested!!
        assertTrue(first.startsWith(cache) && first.fileName.toString().matches(Regex("libliquidglass-[0-9a-f]{16}\\.dylib")))
        Files.writeString(NativeBridgeLoader.libraryPath(pluginRoot), "another build")
        NativeBridgeLoader.load(pluginRoot, NativePlatform.MAC_ARM64, cache) { requested = it; UnavailableNativeBridge("stub") }
        assertTrue(requested != first, "a new build gets a new path")
        assertFalse(Files.exists(first), "older copies are removed")
    }

    private fun installFakeLibrary() {
        val path = NativeBridgeLoader.libraryPath(pluginRoot)
        Files.createDirectories(path.parent)
        Files.writeString(path, "not a real dylib")
    }
}
