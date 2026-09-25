package com.lilyanmuller.glassislands.shapes

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.wm.ToolWindow
import com.lilyanmuller.glassislands.internals.PlatformInternals
import com.lilyanmuller.glassislands.internals.PlatformInternals.EDITOR_ISLAND
import com.lilyanmuller.glassislands.internals.PlatformInternals.MAIN_TOOLBAR
import com.lilyanmuller.glassislands.internals.PlatformInternals.STRIPE
import com.lilyanmuller.glassislands.internals.PlatformInternals.STRIPE_BUTTON
import com.lilyanmuller.glassislands.internals.PlatformInternals.TOOLBAR_COMBO
import com.lilyanmuller.glassislands.internals.PlatformInternals.TOOL_WINDOW_ISLAND
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.impl.TabLabel
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Window
import java.util.WeakHashMap
import javax.swing.SwingUtilities

/**
 * Shapes of the IDE frame. Islands are the components the Islands theme paints as rounded islands (its
 * tool-window and editor border painters); toolbar, status bar and stripes sit on the window as in the native
 * theme, and only their selected or hovered buttons, like the selected or hovered editor tab, get a glass pill.
 */
enum class GlassShapeKind {
    TOOL_WINDOW,
    EDITOR,

    /** Stripe button of the active tool window (Rider paints it with the accent colour). */
    FOCUSED_CONTROL,

    /** Toggled-on button, e.g. the stripe button of an open but inactive tool window. */
    SELECTED_CONTROL,
    HOVERED_CONTROL,
    SELECTED_TAB,
    HOVERED_TAB,
}

/** A shape in window coordinates; [key] identifies the IDE component (and kind) it follows. */
data class GlassShape(val kind: GlassShapeKind, val bounds: Rectangle, val cornerRadius: Double, val key: Long)

/** Result of a full walk of the window, reused while only the hover or selection state changes. */
class LayoutScan(
    val islands: List<GlassShape>,
    internal val controls: List<Pair<Component, Double>>,
    internal val tabs: List<TabLabel>,
) {
    /** For the debug report: what the scan found and which buttons currently read as selected. */
    fun summary(): String {
        val states = controls.mapNotNull { (control, _) ->
            GlassShapeLocator.controlKind(control, hovered = null)?.let { "${control.javaClass.simpleName}=$it" }
        }
        return "${controls.size} buttons, ${tabs.size} editor tabs, highlighted: ${states.ifEmpty { listOf("none") }.joinToString()}"
    }
}

/**
 * Matching is done on platform class names (checked against Rider 2026.2) where the classes are implementation
 * details, so a renamed class only removes a shape.
 */
object GlassShapeLocator {
    private const val MIN_SIZE = 8
    private const val CONTROL_SEARCH_DEPTH = 4

    /** Full walk: islands, the buttons of the toolbar and stripes, and the editor tabs. */
    fun scan(window: Window): LayoutScan {
        val islandInset = IslandGeometry.inset()
        val islandRadius = IslandGeometry.cornerRadius()
        val islands = mutableListOf<GlassShape>()
        val controls = mutableListOf<Pair<Component, Double>>()
        val tabs = mutableListOf<TabLabel>()
        fun collectTabs(component: Component) {
            if (!component.isShowing) return
            if (component is TabLabel) {
                tabs += component
                return
            }
            if (component is Container) component.components.forEach(::collectTabs)
        }
        /** [buttonRadius] is non-null inside a control bar: the arc its buttons paint their background with. */
        fun visit(component: Component, buttonRadius: Double?) {
            if (!component.isShowing || component.width < MIN_SIZE || component.height < MIN_SIZE) return
            when {
                isA(component, TOOL_WINDOW_ISLAND) -> {
                    islands += shape(GlassShapeKind.TOOL_WINDOW, component, islandInset, islandRadius, window)
                    return
                }
                isA(component, EDITOR_ISLAND) -> {
                    islands += shape(GlassShapeKind.EDITOR, component, islandInset, islandRadius, window)
                    collectTabs(component)
                    return
                }
                buttonRadius != null && isControl(component) -> {
                    controls += component to buttonRadius
                    return
                }
            }
            val radius = buttonRadius ?: when {
                isA(component, MAIN_TOOLBAR) -> IslandGeometry.toolbarButtonRadius()
                isA(component, STRIPE) -> IslandGeometry.stripeButtonRadius()
                else -> null
            }
            if (component is Container) component.components.forEach { visit(it, radius) }
        }
        (window as? Container)?.components?.forEach { visit(it, null) }
        return LayoutScan(islands, controls, tabs)
    }

    /** Cheap pass over the scanned buttons and tabs: pills for the selected and hovered ones. */
    fun interactiveShapes(window: Window, scan: LayoutScan, hovered: Component?): List<GlassShape> {
        val shapes = mutableListOf<GlassShape>()
        for ((control, radius) in scan.controls) {
            if (!control.isShowing) continue
            val kind = controlKind(control, hovered) ?: continue
            shapes += if (isA(control, STRIPE_BUTTON)) stripeShape(kind, control, radius, window) else shape(kind, control, 0, radius, window)
        }
        val tabRadius = IslandGeometry.tabRadius()
        for (tab in scan.tabs) {
            if (!tab.isShowing) continue
            val kind = when {
                (tab.parent as? JBTabs)?.selectedInfo === tab.info -> GlassShapeKind.SELECTED_TAB
                tab === hovered || tab.isHovered -> GlassShapeKind.HOVERED_TAB
                else -> continue
            }
            shapes += tabShape(kind, tab, tabRadius, window)
        }
        return shapes
    }

    /**
     * Mirrors SquareStripeButtonLook.getState: a stripe button whose tool window is active gets the accent, one whose
     * tool window is open a neutral highlight, a hovered one the lightest. SquareStripeButton is internal to the
     * platform, so `isFocused()` and `getToolWindow()` are looked up reflectively once.
     */
    internal fun controlKind(control: Component, hovered: Component?): GlassShapeKind? {
        val stripe = isA(control, STRIPE_BUTTON)
        return when {
            stripe && stripeFocused(control) -> GlassShapeKind.FOCUSED_CONTROL
            stripe && stripeToolWindowVisible(control) -> GlassShapeKind.SELECTED_CONTROL
            control is ActionButton && control.isSelected -> GlassShapeKind.SELECTED_CONTROL
            control === hovered || (control is ActionButton && control.isRollover) -> GlassShapeKind.HOVERED_CONTROL
            else -> null
        }
    }

    private fun stripeToolWindowVisible(button: Component): Boolean = try {
        (PlatformInternals.stripeToolWindow?.invoke(button) as? ToolWindow)?.isVisible == true
    } catch (e: ReflectiveOperationException) {
        false
    }

    private fun stripeFocused(button: Component): Boolean = try {
        PlatformInternals.stripeIsFocused?.invoke(button) == true
    } catch (e: ReflectiveOperationException) {
        false
    }

    /** The island (tool-window holder or editor splitters) containing [component], if any. */
    fun islandOf(component: Component): Component? {
        var current: Component? = component
        while (current != null && current !is Window) {
            if (isIsland(current)) return current
            current = current.parent
        }
        return null
    }

    fun isIsland(component: Component) = isA(component, TOOL_WINDOW_ISLAND) || isA(component, EDITOR_ISLAND)

    /** Components painted as islands (tool-window holders and editor splitters) that are showing in [window]. */
    fun islandComponents(window: Window): List<Component> {
        val islands = mutableListOf<Component>()
        fun visit(component: Component) {
            if (!component.isShowing) return
            if (isIsland(component)) {
                islands += component
                return
            }
            if (component is Container) component.components.forEach(::visit)
        }
        (window as? Container)?.components?.forEach(::visit)
        return islands
    }

    /** The toolbar button, widget or editor tab containing [component], if any. */
    fun interactiveOf(component: Component?): Component? {
        var current = component
        repeat(CONTROL_SEARCH_DEPTH) {
            val candidate = current ?: return null
            if (isControl(candidate) || candidate is TabLabel) return candidate
            current = candidate.parent
        }
        return null
    }

    internal fun isControl(component: Component) = component is ActionButton || isA(component, TOOLBAR_COMBO)

    internal fun isA(component: Component, className: String): Boolean {
        var type: Class<*>? = component.javaClass
        while (type != null && type != Component::class.java) {
            if (type.name == className) return true
            type = type.superclass
        }
        return false
    }

    private fun shape(kind: GlassShapeKind, component: Component, inset: Int, radius: Double, window: Window): GlassShape {
        val bounds = SwingUtilities.convertRectangle(component.parent, component.bounds, window)
        bounds.grow(-inset, -inset)
        return GlassShape(kind, bounds, radius, keyOf(component, kind))
    }

    /**
     * Stripe buttons touch each other; Rider paints their highlight inside the button insets and the stripe icon
     * padding (SquareStripeButtonLook), which keeps neighbouring highlights apart. The pill uses the same rectangle,
     * so two selected neighbours never merge into one blob of glass.
     */
    private fun stripeShape(kind: GlassShapeKind, button: Component, radius: Double, window: Window): GlassShape {
        val bounds = SwingUtilities.convertRectangle(button.parent, button.bounds, window)
        (button as? javax.swing.JComponent)?.insets?.let { JBInsets.removeFrom(bounds, it) }
        val left = isOnLeftStripe(button)
        JBInsets.removeFrom(bounds, JBUI.CurrentTheme.Toolbar.stripeToolbarButtonIconPadding(left, stripeShowsNames()))
        return GlassShape(kind, bounds, radius, keyOf(button, kind))
    }

    private fun isOnLeftStripe(button: Component): Boolean {
        var current: Component? = button
        while (current != null && current !is Window) {
            val name = current.javaClass.simpleName
            if (name.contains("RightToolbar")) return false
            if (name.contains("LeftToolbar")) return true
            current = current.parent
        }
        return true
    }

    /** "Show Tool Window Names" (larger stripe buttons); read reflectively, false when unavailable. */
    private fun stripeShowsNames(): Boolean = PlatformInternals.stripeShowsNames()

    /** Tab pill: the Islands tab height (28, 24 in compact mode) centred in the label, slightly inset horizontally. */
    private fun tabShape(kind: GlassShapeKind, tab: TabLabel, radius: Double, window: Window): GlassShape {
        val bounds = SwingUtilities.convertRectangle(tab.parent, tab.bounds, window)
        val height = minOf(bounds.height, JBUIScale.scale(if (UISettings.getInstance().compactMode) 24 else 28))
        val inset = JBUIScale.scale(2)
        return GlassShape(
            kind,
            Rectangle(bounds.x + inset, bounds.y + (bounds.height - height) / 2, bounds.width - 2 * inset, height),
            radius,
            keyOf(tab, kind),
        )
    }

    /** Stable, unique per component (identity hash codes can collide) and small enough to be exact as a double. */
    private val ids = WeakHashMap<Component, Long>()
    private var nextId = 1L

    private fun keyOf(component: Component, kind: GlassShapeKind): Long =
        (ids.getOrPut(component) { nextId++ } shl 3) or kind.ordinal.toLong()
}
