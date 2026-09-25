package com.lilyanmuller.glassislands.lifecycle

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class StartupGuardTest {
    @TempDir
    lateinit var dir: Path

    private val marker get() = dir.resolve("guard/applying.marker")

    @Test
    fun `a clean first start is not a failure`() {
        assertFalse(StartupGuard(marker).previousSessionFailed())
    }

    @Test
    fun `a session that stops while applying is detected once`() {
        StartupGuard(marker).arm()
        assertTrue(Files.exists(marker))
        val next = StartupGuard(marker)
        assertTrue(next.previousSessionFailed())
        assertFalse(next.previousSessionFailed())
    }

    @Test
    fun `a normal shutdown clears the marker`() {
        val guard = StartupGuard(marker)
        guard.arm()
        guard.clear()
        assertFalse(StartupGuard(marker).previousSessionFailed())
    }

    @Test
    fun `arming twice in a session writes once and clears cleanly`() {
        val guard = StartupGuard(marker)
        guard.arm()
        guard.arm()
        guard.clear()
        assertFalse(Files.exists(marker))
    }
}
