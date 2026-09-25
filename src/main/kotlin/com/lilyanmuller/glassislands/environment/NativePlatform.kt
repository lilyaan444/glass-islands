package com.lilyanmuller.glassislands.environment

/** Macs supported by the universal native AppKit bridge. */
enum class NativePlatform {
    MAC_ARM64,
    MAC_X64;

    companion object {
        fun detect(osName: String, osArch: String): NativePlatform? {
            if (!osName.startsWith("Mac", ignoreCase = true)) return null
            return when (osArch.lowercase()) {
                "aarch64", "arm64" -> MAC_ARM64
                "x86_64", "amd64" -> MAC_X64
                else -> null
            }
        }

        fun current(): NativePlatform? =
            detect(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty())
    }
}
