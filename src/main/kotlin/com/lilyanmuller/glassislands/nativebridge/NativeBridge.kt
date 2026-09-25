package com.lilyanmuller.glassislands.nativebridge

/**
 * Boundary between the plugin and AppKit. Window handles are `NSWindow*` pointers owned by the
 * JetBrains Runtime; implementations must never dereference a handle that is no longer a live window.
 */
interface NativeBridge : AutoCloseable {
    val isAvailable: Boolean

    /** Where the bridge was loaded from, or why it is unavailable. */
    val description: String

    /** Whether NSGlassEffectView (Liquid Glass) exists on this macOS. */
    val glassAvailable: Boolean

    val osMajorVersion: Int?

    /** System accessibility display options, read live (cheap). */
    fun accessibilityOptions(): AccessibilityOptions

    fun applyBackdrop(windowHandle: Long, spec: BackdropSpec)

    /** One rounded shape of the [spec] material per entry; an empty list removes them. */
    fun setIslands(windowHandle: Long, shapes: List<IslandShape>, spec: BackdropSpec)

    fun removeIslands(windowHandle: Long)

    fun removeBackdrop(windowHandle: Long)

    fun status(windowHandle: Long): NativeWindowStatus

    fun describe(windowHandle: Long): String

    /** Releases the native library; the bridge is inert afterwards. */
    override fun close() = Unit
}

class UnavailableNativeBridge(reason: String) : NativeBridge {
    override val isAvailable = false
    override val description = reason
    override val glassAvailable = false
    override val osMajorVersion: Int? = null

    override fun accessibilityOptions() = AccessibilityOptions.NONE

    override fun applyBackdrop(windowHandle: Long, spec: BackdropSpec) = Unit

    override fun setIslands(windowHandle: Long, shapes: List<IslandShape>, spec: BackdropSpec) = Unit

    override fun removeIslands(windowHandle: Long) = Unit

    override fun removeBackdrop(windowHandle: Long) = Unit

    override fun status(windowHandle: Long) = NativeWindowStatus.UNKNOWN

    override fun describe(windowHandle: Long) = "native bridge unavailable: $description"
}
