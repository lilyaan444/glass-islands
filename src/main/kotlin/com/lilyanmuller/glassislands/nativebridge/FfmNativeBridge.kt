package com.lilyanmuller.glassislands.nativebridge

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/**
 * [NativeBridge] backed by libliquidglass.dylib through the Foreign Function & Memory API of Java 25. Unlike JNA,
 * nothing is cached against the plugin's classes, and [close] unloads the library, so the plugin can be unloaded
 * without a restart. Only integers and double arrays cross the boundary (see LiquidGlassBridge.h).
 */
internal class FfmNativeBridge(private val libraryPath: Path) : NativeBridge {

    private val libraryArena = Arena.ofShared()
    private val lookup = SymbolLookup.libraryLookup(libraryPath, libraryArena)
    private val linker = Linker.nativeLinker()

    private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(lookup.find(name).orElseThrow { UnsatisfiedLinkError("$name not found in $libraryPath") }, descriptor)

    private val bridgeVersion = function("rlg_bridge_version", FunctionDescriptor.of(JAVA_INT))
    private val osMajor = function("rlg_os_major_version", FunctionDescriptor.of(JAVA_INT))
    private val glass = function("rlg_glass_available", FunctionDescriptor.of(JAVA_INT))
    private val accessibility = function("rlg_accessibility_options", FunctionDescriptor.of(JAVA_INT))
    private val applyBackdrop = function("rlg_apply_backdrop", FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS))
    private val setIslands = function("rlg_set_islands", FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS))
    private val removeBackdrop = function("rlg_remove_backdrop", FunctionDescriptor.ofVoid(JAVA_LONG))
    private val windowStatus = function("rlg_window_status", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
    private val describeWindow = function("rlg_describe_window", FunctionDescriptor.of(ADDRESS, JAVA_LONG))
    private val freeString = function("rlg_free_string", FunctionDescriptor.ofVoid(ADDRESS))

    @Volatile
    private var closed = false

    init {
        val version = bridgeVersion.invoke() as Int
        if (version != EXPECTED_BRIDGE_VERSION) {
            libraryArena.close()
            throw IllegalStateException("native bridge version $version does not match expected $EXPECTED_BRIDGE_VERSION")
        }
    }

    override val isAvailable = true
    override val description = "libliquidglass v$EXPECTED_BRIDGE_VERSION loaded from $libraryPath"
    override val glassAvailable = (glass.invoke() as Int) != 0
    override val osMajorVersion: Int = osMajor.invoke() as Int

    override fun accessibilityOptions(): AccessibilityOptions =
        if (closed) AccessibilityOptions.NONE else AccessibilityOptions(accessibility.invoke() as Int)

    override fun applyBackdrop(windowHandle: Long, spec: BackdropSpec) {
        if (closed) return
        Arena.ofConfined().use { arena ->
            applyBackdrop.invoke(windowHandle, config(arena, spec))
        }
    }

    override fun setIslands(windowHandle: Long, shapes: List<IslandShape>, spec: BackdropSpec) {
        if (closed) return
        val records = DoubleArray(shapes.size * ISLAND_STRIDE)
        shapes.forEachIndexed { index, shape ->
            val offset = index * ISLAND_STRIDE
            records[offset] = shape.bounds.x.toDouble()
            records[offset + 1] = shape.bounds.y.toDouble()
            records[offset + 2] = shape.bounds.width.toDouble()
            records[offset + 3] = shape.bounds.height.toDouble()
            shape.tint?.let {
                records[offset + 4] = it.red / 255.0
                records[offset + 5] = it.green / 255.0
                records[offset + 6] = it.blue / 255.0
                records[offset + 7] = it.alpha / 255.0
            }
            records[offset + 8] = shape.cornerRadius
            records[offset + 9] = shape.shadow
            records[offset + 10] = shape.role.nativeCode.toDouble()
            records[offset + 11] = shape.key.toDouble()
        }
        Arena.ofConfined().use { arena ->
            val data = if (records.isEmpty()) MemorySegment.NULL else arena.allocateFrom(JAVA_DOUBLE, *records)
            setIslands.invoke(windowHandle, data, shapes.size, config(arena, spec))
        }
    }

    override fun removeIslands(windowHandle: Long) = setIslands(windowHandle, emptyList(), EMPTY_SPEC)

    override fun removeBackdrop(windowHandle: Long) {
        if (closed) return
        removeBackdrop.invoke(windowHandle)
    }

    override fun status(windowHandle: Long): NativeWindowStatus =
        if (closed) NativeWindowStatus.UNKNOWN else NativeWindowStatus(windowStatus.invoke(windowHandle) as Int)

    override fun describe(windowHandle: Long): String {
        if (closed) return "native bridge closed"
        val text = describeWindow.invoke(windowHandle) as MemorySegment
        if (text == MemorySegment.NULL) return "no description"
        try {
            return text.reinterpret(Long.MAX_VALUE).getString(0)
        } finally {
            freeString.invoke(text)
        }
    }

    /** Unloads the library; later calls are no-ops. Callers must have removed their native views first. */
    override fun close() {
        if (closed) return
        closed = true
        libraryArena.close()
    }

    private fun config(arena: Arena, spec: BackdropSpec): MemorySegment {
        val tint = spec.tint
        val values = doubleArrayOf(
            spec.backend.nativeCode.toDouble(),
            spec.appearance.nativeCode.toDouble(),
            spec.alpha,
            (tint?.red ?: 0) / 255.0,
            (tint?.green ?: 0) / 255.0,
            (tint?.blue ?: 0) / 255.0,
            (tint?.alpha ?: 0) / 255.0,
            if (spec.increasedContrast) 1.0 else 0.0,
            spec.material.nativeCode.toDouble(),
            spec.cornerRadius,
        )
        check(values.size == CONFIG_LENGTH)
        return arena.allocateFrom(JAVA_DOUBLE, *values)
    }

    private companion object {
        const val EXPECTED_BRIDGE_VERSION = 8

        /** RLG_CONFIG_LENGTH: backend, appearance, alpha, tint r, g, b, a, increased contrast, material, corner radius. */
        const val CONFIG_LENGTH = 10

        /** RLG_ISLAND_STRIDE: x, y, width, height, tint r, g, b, a, corner radius, shadow, role, identifier. */
        const val ISLAND_STRIDE = 12

        val EMPTY_SPEC = BackdropSpec(BackdropBackend.VISUAL_EFFECT, BackdropAppearance.DARK, 1.0, null)
    }
}
