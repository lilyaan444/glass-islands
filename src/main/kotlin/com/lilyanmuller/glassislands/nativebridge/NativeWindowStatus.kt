package com.lilyanmuller.glassislands.nativebridge

/** Decodes the RLG_STATUS_* flags reported by the native bridge. */
@JvmInline
value class NativeWindowStatus(val flags: Int) {
    val windowFound: Boolean get() = has(1)
    val backdropAttached: Boolean get() = has(2)
    val glassBackend: Boolean get() = has(4)
    val windowNonOpaque: Boolean get() = has(8)
    val layerNonOpaque: Boolean get() = has(16)
    val islandsAttached: Boolean get() = has(32)

    private fun has(flag: Int) = flags and flag != 0

    companion object {
        val UNKNOWN = NativeWindowStatus(0)
    }
}

/** Decodes the RLG_ACCESSIBILITY_* flags: System Settings > Accessibility > Display. */
@JvmInline
value class AccessibilityOptions(val flags: Int) {
    val reduceTransparency: Boolean get() = flags and 1 != 0
    val increaseContrast: Boolean get() = flags and 2 != 0
    val reduceMotion: Boolean get() = flags and 4 != 0

    companion object {
        val NONE = AccessibilityOptions(0)
    }
}
