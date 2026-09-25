package com.lilyanmuller.glassislands.internals

import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.diagnostic.logger
import java.awt.Component
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.swing.JRootPane
import javax.swing.RepaintManager

/**
 * Every implementation detail of the JDK and of the IntelliJ platform the plugin depends on, in one place and
 * checked once. Each item names what it is for; when one is missing (a new Rider or JBR changed it), only the
 * feature built on it stops, and the debug report says which. Verified against Rider 2026.2 / JBR 25.
 */
internal object PlatformInternals {
    private val LOG = logger<PlatformInternals>()

    /** Tool-window islands painted by the Islands theme. */
    const val TOOL_WINDOW_ISLAND = "com.intellij.toolWindow.xNext.island.XNextIslandHolder"

    /** Editor island (holds every editor tab and split of a window). */
    const val EDITOR_ISLAND = "com.intellij.openapi.fileEditor.impl.EditorsSplitters"

    /** Main toolbar and its widgets (project, VCS, run configuration). */
    const val MAIN_TOOLBAR = "com.intellij.openapi.wm.impl.headertoolbar.MainToolbar"
    const val TOOLBAR_COMBO = "com.intellij.openapi.wm.impl.AbstractToolbarCombo"

    /** Floating tool window (a JDialog around the tool window). */
    const val FLOATING_TOOL_WINDOW = "com.intellij.openapi.wm.impl.FloatingDecorator"

    /** Tool-window stripes and their buttons. */
    const val STRIPE = "com.intellij.toolWindow.ToolWindowToolbar"
    const val STRIPE_BUTTON = "com.intellij.openapi.wm.impl.SquareStripeButton"

    /** A feature and the internals it needs. */
    enum class Feature(val description: String) {
        TRANSLUCENT_WINDOW("per-pixel translucent IDE frame"),
        ISLAND_LAYOUT("island shapes"),
        GLASS_BUTTONS("glass buttons without Swing highlight"),
        STRIPE_STATES("tool-window button states"),
    }

    private val loader: ClassLoader = ActionButton::class.java.classLoader

    /** `Component.background`: the only way to give a decorated frame a transparent background. */
    val componentBackground: Field? by member(Feature.TRANSLUCENT_WINDOW, "java.awt.Component.background") {
        Component::class.java.getDeclaredField("background").apply { isAccessible = true }
    }

    /** `AWTAccessor.getComponentAccessor()`, to reach the LWWindowPeer of a frame. */
    val componentAccessor: Any? by member(Feature.TRANSLUCENT_WINDOW, "sun.awt.AWTAccessor.getComponentAccessor") {
        Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
    }

    val getPeer: Method? by member(Feature.TRANSLUCENT_WINDOW, "sun.awt.AWTAccessor.ComponentAccessor.getPeer") {
        componentAccessor!!.javaClass.getMethod("getPeer", Component::class.java).apply { isAccessible = true }
    }

    /** `JRootPane.useTrueDoubleBuffering` and `RepaintManager.doubleBufferingChanged`: see WindowTranslucency. */
    val useTrueDoubleBuffering: Field? by member(Feature.TRANSLUCENT_WINDOW, "javax.swing.JRootPane.useTrueDoubleBuffering") {
        JRootPane::class.java.getDeclaredField("useTrueDoubleBuffering").apply { isAccessible = true }
    }

    val doubleBufferingChanged: Method? by member(Feature.TRANSLUCENT_WINDOW, "javax.swing.RepaintManager.doubleBufferingChanged") {
        RepaintManager::class.java.getDeclaredMethod("doubleBufferingChanged", JRootPane::class.java).apply { isAccessible = true }
    }

    /** `ActionButton.getButtonLook()` (protected): the look the IDE installed, wrapped by GlassButtonLooks. */
    val getButtonLook: Method? by member(Feature.GLASS_BUTTONS, "ActionButton.getButtonLook") {
        ActionButton::class.java.getDeclaredMethod("getButtonLook").apply { isAccessible = true }
    }

    /** `SquareStripeButton.isFocused()` / `getToolWindow()` (the class is Kotlin-internal to the platform). */
    val stripeIsFocused: Method? by member(Feature.STRIPE_STATES, "SquareStripeButton.isFocused") {
        platformClass(STRIPE_BUTTON).getMethod("isFocused")
    }

    val stripeToolWindow: Method? by member(Feature.STRIPE_STATES, "SquareStripeButton.getToolWindow") {
        platformClass(STRIPE_BUTTON).getMethod("getToolWindow")
    }

    /** `ResizeStripeManager.Companion.isShowNames()`: stripe buttons with names are taller and padded differently. */
    private val showNamesMethod: Pair<Any, Method>? by member(Feature.STRIPE_STATES, "ResizeStripeManager.isShowNames") {
        val companion = platformClass("com.intellij.toolWindow.ResizeStripeManager").getField("Companion").get(null)
        companion to companion.javaClass.getMethod("isShowNames")
    }

    fun stripeShowsNames(): Boolean = try {
        showNamesMethod?.let { (companion, method) -> method.invoke(companion) as? Boolean } ?: false
    } catch (e: ReflectiveOperationException) {
        false
    }

    private val islandClasses: Unit? by member(Feature.ISLAND_LAYOUT, "island and toolbar classes") {
        listOf(TOOL_WINDOW_ISLAND, EDITOR_ISLAND, MAIN_TOOLBAR, STRIPE).forEach(::platformClass)
    }

    private val missing = mutableMapOf<Feature, MutableList<String>>()

    /** Resolves everything once; returns the features that cannot run on this IDE. */
    val unavailableFeatures: Map<Feature, List<String>> by lazy {
        listOf(componentBackground, componentAccessor, getPeer, useTrueDoubleBuffering, doubleBufferingChanged,
            getButtonLook, stripeIsFocused, stripeToolWindow, showNamesMethod, islandClasses)
        missing.toMap()
    }

    fun isAvailable(feature: Feature): Boolean = feature !in unavailableFeatures

    fun report(): String =
        if (unavailableFeatures.isEmpty()) "all ${Feature.entries.size} features supported"
        else unavailableFeatures.entries.joinToString("; ") { (feature, items) -> "${feature.description} unavailable (${items.joinToString()})" }

    private fun platformClass(name: String): Class<*> = Class.forName(name, false, loader)

    private fun <T> member(feature: Feature, name: String, resolve: () -> T) = lazy {
        try {
            resolve()
        } catch (e: ReflectiveOperationException) {
            unavailable(feature, name, e)
        } catch (e: RuntimeException) {
            unavailable(feature, name, e)
        } catch (e: LinkageError) {
            unavailable(feature, name, e)
        }
    }

    private fun <T> unavailable(feature: Feature, name: String, error: Throwable): T? {
        LOG.warn("Glass Islands: $name not found, ${feature.description} disabled (${error.javaClass.simpleName}: ${error.message})")
        synchronized(missing) { missing.getOrPut(feature) { mutableListOf() } += name }
        return null
    }
}
