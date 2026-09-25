package com.lilyanmuller.glassislands.actions

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import com.lilyanmuller.glassislands.diagnostics.GlassMetrics
import com.lilyanmuller.glassislands.internals.PlatformInternals
import com.lilyanmuller.glassislands.plan.GlassPlanner
import com.lilyanmuller.glassislands.settings.LiquidGlassSettings
import com.lilyanmuller.glassislands.ui.LiquidGlassConfigurable
import com.lilyanmuller.glassislands.window.GlassWindowRegistry

internal object DebugReport {
    /** Next to idea.log, so it can be attached to a bug report with Help > Collect Logs. */
    fun file(): Path = PathManager.getLogDir().resolve("glass-islands-report.txt")

    fun save(report: String) {
        try {
            Files.writeString(file(), report)
        } catch (e: IOException) {
            logger<DebugReport>().warn("Cannot write ${file()}", e)
        }
    }

    private val SWING_BUFFER_PROPERTIES = listOf("swing.bufferPerWindow", "swing.volatileImageBufferEnabled", "awt.nativeDoubleBuffering")

    fun build(project: Project?): String {
        val registry = GlassWindowRegistry.getInstance()
        val env = registry.environment
        val bridge = registry.bridge
        val state = LiquidGlassSettings.getInstance().snapshot()
        val controller = project?.let(registry::controllerFor)
        val status = controller?.status()
        return buildString {
            line("Rider version", "${env.ideName} ${env.ideVersion}")
            line("Build", env.ideBuild)
            line("JBR", "${env.runtimeVendor} ${env.runtimeVersion}")
            line("macOS", "${env.osName} ${env.osVersion} (major reported by AppKit: ${bridge.osMajorVersion ?: "n/a"})")
            line("Architecture", "${env.osArch} -> ${env.nativePlatform?.name?.lowercase() ?: "unsupported"}")
            line("Native bridge", if (bridge.isAvailable) "loaded - ${bridge.description}" else "unavailable - ${bridge.description}")
            line("NSGlassEffectView", if (bridge.glassAvailable) "available (Liquid Glass)" else "not available")
            appendLine()
            line("Main frame", controller?.frame?.javaClass?.name ?: "not tracked")
            line("NSWindow status", controller?.nativeHandle?.let { "0x${it.toString(16)}, found=${status?.windowFound}, non-opaque=${status?.windowNonOpaque}, Metal layer non-opaque=${status?.layerNonOpaque}" } ?: "not resolved")
            line("Window backdrop", status?.let { if (it.backdropAttached) "frosted NSVisualEffectView" else "none" } ?: "n/a")
            line("Shapes", status?.let { if (it.islandsAttached) "${controller.glassShapes.size} (controls: ${if (it.glassBackend) "Liquid Glass" else "frosted"})" else "none" } ?: "n/a")
            line("Last scan", controller?.scanSummary ?: "n/a")
            line("Native updates sent", controller?.nativeUpdates?.let { "$it (last shape change: ${controller.lastShapeChange})" } ?: "n/a")
            line("AWT frame translucent", controller?.isTranslucent?.toString() ?: "n/a")
            line("Frame background resets", controller?.backgroundResets?.toString() ?: "n/a")
            line("Transparent editors", controller?.transparentEditorCount?.toString() ?: "n/a")
            line("Panels cleared in islands", controller?.clearedPanelCount?.toString() ?: "n/a")
            line("Flicker fixes (non-opaque)", controller?.flickerFixes?.let { "${it.size}: ${it.joinToString()}" } ?: "n/a")
            line("Swing buffering", SWING_BUFFER_PROPERTIES.joinToString { "$it=${System.getProperty(it)}" })
            line("Last error", controller?.lastError ?: "none")
            line("Other glass windows", registry.secondaryWindowsSummary())
            line("Theme", "Island.arc=${javax.swing.UIManager.get("Island.arc")}, popup radius=${com.lilyanmuller.glassislands.shapes.IslandGeometry.popupRadius()}")
            appendLine("Plugin work on the EDT since startup:")
            appendLine(GlassMetrics.report())
            appendLine()
            line("Enabled", state.enabled.toString())
            line("Status", "${registry.status} (this plugin instance loaded at ${registry.loadedAt})")
            line("Platform internals", PlatformInternals.report())
            line("Accessibility", registry.accessibilityOptions.let { "reduce transparency=${it.reduceTransparency}, increase contrast=${it.increaseContrast}, reduce motion=${it.reduceMotion}" })
            line("Island contrast", LiquidGlassConfigurable.contrastName(state.islandContrast))
            line("Opacity", "${state.opacity}% (editor ${"%.0f".format(GlassPlanner.editorOpacity(state) * 100)}%)")
            line("Glass buttons and tabs", state.glassControls.toString())
            line("Translucent UI keys", registry.activeSurfaceKeys.sorted().joinToString().ifEmpty { "none" })
            appendLine()
            appendLine("Native hierarchy:")
            appendLine(controller?.describeNative() ?: "n/a")
            appendLine()
            appendLine("Glass shapes (window coordinates):")
            controller?.glassShapes?.forEach { appendLine("  ${it.kind} ${it.bounds.x},${it.bounds.y} ${it.bounds.width}x${it.bounds.height}") }
            appendLine()
            appendLine("Partial repaint origins (the clear colour must be transparent):")
            controller?.frame?.let { OpaqueLayerScanner.paintOrigins(it as java.awt.Container) }?.forEach(::appendLine)
            appendLine("Opaque components (possible flicker sources on partial repaints):")
            controller?.frame?.let { OpaqueLayerScanner.opaqueComponents(it as java.awt.Container) }?.forEach { appendLine("  $it") } ?: appendLine("n/a")
            appendLine()
            appendLine("Opaque Swing layers covering >= 10% of the frame (they hide the material):")
            controller?.frame?.let(OpaqueLayerScanner::scan)?.forEach(::appendLine) ?: appendLine("n/a")
        }
    }

    private fun StringBuilder.line(label: String, value: String) {
        append(label.padEnd(28)).append(": ").appendLine(value)
    }
}
