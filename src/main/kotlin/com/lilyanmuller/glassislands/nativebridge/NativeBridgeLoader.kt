package com.lilyanmuller.glassislands.nativebridge

import com.intellij.openapi.diagnostic.logger
import com.lilyanmuller.glassislands.environment.NativePlatform
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object NativeBridgeLoader {
    const val LIBRARY_FILE_NAME = "libliquidglass.dylib"

    private val LOG = logger<NativeBridgeLoader>()

    /** One universal (arm64 + x86_64) library for every supported Mac. */
    fun libraryPath(pluginRoot: Path): Path = pluginRoot.resolve("native").resolve(LIBRARY_FILE_NAME)

    /**
     * Never throws: any failure yields an [UnavailableNativeBridge] carrying the reason. [cacheDir], when given,
     * receives a copy of the library named after its content: macOS keeps an image loaded under the same path, so
     * after a plugin update without restart the new native code is only picked up from a new path.
     */
    fun load(
        pluginRoot: Path?,
        platform: NativePlatform?,
        cacheDir: Path? = null,
        factory: (Path) -> NativeBridge = ::FfmNativeBridge,
    ): NativeBridge {
        val bridge = when {
            platform == null -> UnavailableNativeBridge("not running on macOS arm64/x64")
            pluginRoot == null -> UnavailableNativeBridge("plugin installation directory not found")
            else -> loadLibrary(libraryPath(pluginRoot), cacheDir, factory)
        }
        if (bridge.isAvailable) {
            LOG.info("Native bridge loaded: ${bridge.description}, macOS ${bridge.osMajorVersion}, NSGlassEffectView=${bridge.glassAvailable}")
        } else {
            LOG.warn("Native bridge unavailable, Liquid Glass disabled: ${bridge.description}")
        }
        return bridge
    }

    private fun loadLibrary(path: Path, cacheDir: Path?, factory: (Path) -> NativeBridge): NativeBridge {
        if (!Files.isRegularFile(path)) return UnavailableNativeBridge("native library missing at $path")
        return try {
            factory(cacheDir?.let { versionedCopy(path, it) } ?: path)
        } catch (e: LinkageError) {
            failure(path, e)
        } catch (e: Exception) {
            // Method handles may throw checked exceptions; nothing from the bridge may escape into IDE startup.
            failure(path, e)
        }
    }

    /** Copies the library to `<cacheDir>/libliquidglass-<sha256 prefix>.dylib` once; falls back to the original. */
    internal fun versionedCopy(library: Path, cacheDir: Path): Path? = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(library))
        val name = "libliquidglass-" + digest.take(8).joinToString("") { "%02x".format(it) } + ".dylib"
        val target = cacheDir.resolve(name)
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(cacheDir)
            val temporary = Files.createTempFile(cacheDir, "libliquidglass", ".tmp")
            Files.copy(library, temporary, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        // Older copies are no longer used by this version of the plugin.
        Files.list(cacheDir).use { files ->
            files.filter { it != target && it.fileName.toString().startsWith("libliquidglass-") }.forEach { Files.deleteIfExists(it) }
        }
        target
    } catch (e: IOException) {
        LOG.warn("Cannot copy the native library to $cacheDir, loading it in place", e)
        null
    }

    private fun failure(path: Path, error: Throwable) =
        UnavailableNativeBridge("failed to load $path: ${error.javaClass.simpleName}: ${error.message}")
}
