package com.lilyanmuller.glassislands.environment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativePlatformTest {
    @Test
    fun `Apple Silicon is detected from the JVM arch name`() {
        assertEquals(NativePlatform.MAC_ARM64, NativePlatform.detect("Mac OS X", "aarch64"))
        assertEquals(NativePlatform.MAC_ARM64, NativePlatform.detect("Mac OS X", "arm64"))
    }

    @Test
    fun `Intel Macs are detected`() {
        assertEquals(NativePlatform.MAC_X64, NativePlatform.detect("Mac OS X", "x86_64"))
        assertEquals(NativePlatform.MAC_X64, NativePlatform.detect("Mac OS X", "amd64"))
    }

    @Test
    fun `other systems and architectures are unsupported`() {
        assertNull(NativePlatform.detect("Windows 11", "amd64"))
        assertNull(NativePlatform.detect("Linux", "aarch64"))
        assertNull(NativePlatform.detect("Mac OS X", "ppc"))
    }
}
