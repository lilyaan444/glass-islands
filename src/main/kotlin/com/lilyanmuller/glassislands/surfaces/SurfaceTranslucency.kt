package com.lilyanmuller.glassislands.surfaces

import java.awt.Color
import javax.swing.UIManager
import javax.swing.plaf.ColorUIResource
import kotlin.math.roundToInt

/** Theme keys painted by Islands for each family of surfaces (see themes/islands/ManyIslandsDark.theme.json in the platform). */
enum class SurfaceGroup(val keys: List<String>) {
    /**
     * Gaps between islands (JBColor.namedColor("MainWindow.background"), also used for the corners outside the
     * rounded islands) and the 6 px band Islands paints around each island in `Island.borderColor`.
     */
    WINDOW(listOf("MainWindow.background", "Island.borderColor")),
    NAVIGATION(listOf("MainToolbar.background", "MainToolbar.inactiveBackground")),
    STATUS_BAR(listOf("StatusBar.background")),
    TOOL_WINDOWS(
        listOf(
            "ToolWindow.background",
            "ToolWindow.Header.background",
            "ToolWindow.Header.inactiveBackground",
            "ToolWindow.Stripe.background",
        )
    ),
    EDITOR(listOf("EditorTabs.background")),

    /**
     * Highlights replaced by glass pills: the accent square of the selected stripe button and the hover/press
     * backgrounds of main toolbar widgets (these keys are only used by the main toolbar and the stripes).
     */
    CONTROLS(
        listOf(
            "ToolWindow.Button.selectedBackground",
            "MainToolbar.Dropdown.transparentHoverBackground",
            "MainToolbar.Dropdown.hoverBackground",
            "MainToolbar.Dropdown.pressedBackground",
        )
    ),

    /** Editor tab backgrounds and borders painted by IslandsTabPainter, replaced by glass pills. */
    TABS(
        listOf(
            "EditorTabs.underlinedTabBackground",
            "EditorTabs.inactiveUnderlinedTabBackground",
            "EditorTabs.underlinedBorderColor",
            "EditorTabs.inactiveUnderlinedTabBorderColor",
            "EditorTabs.hoverBackground",
            "EditorTabs.hoverInactiveBackground",
            "EditorTabs.hoverBorderColor",
            "EditorTabs.regularBackground",
            "EditorTabs.regularBorderColor",
        )
    ),

    /** Selection pills of trees, lists and tables: kept as accents, but part of the glass rather than stickers on it. */
    SELECTION(
        listOf(
            "Tree.selectionBackground",
            "Tree.selectionInactiveBackground",
            "List.selectionBackground",
            "List.selectionInactiveBackground",
            "Table.selectionBackground",
            "Table.selectionInactiveBackground",
        )
    ),
}

/** Alpha factor (0..1) applied to the theme colour of each group; absent groups stay untouched. */
data class SurfacePlan(val alphaByGroup: Map<SurfaceGroup, Double>) {
    companion object {
        val OPAQUE = SurfacePlan(emptyMap())
    }
}

interface UiColorTable {
    /** Colour defined by the current look-and-feel/theme, ignoring developer overrides. */
    fun themeColor(key: String): Color?

    fun effectiveColor(key: String): Color?

    /** Writes a developer-level override; `null` removes it and reveals the theme colour again. */
    fun override(key: String, color: Color?)
}

object SwingUiColorTable : UiColorTable {
    override fun themeColor(key: String): Color? = UIManager.getLookAndFeelDefaults().getColor(key)

    override fun effectiveColor(key: String): Color? = UIManager.getColor(key)

    override fun override(key: String, color: Color?) {
        UIManager.put(key, color)
    }
}

/**
 * Makes Islands surfaces translucent through developer-level UIManager overrides, which survive
 * look-and-feel switches and never modify the theme itself. Keys already overridden by someone else are left alone.
 * Must be used on the EDT.
 */
class SurfaceTranslucency(private val table: UiColorTable = SwingUiColorTable) {

    private val overriddenKeys = mutableSetOf<String>()

    val activeKeys: Set<String> get() = overriddenKeys

    /**
     * Keys missing from the theme are overridden too when fully transparent: their painters then fall back to a
     * hard-coded opaque default. UIManager is only written when a value actually changes, since every write
     * notifies the IDE's UI listeners.
     */
    fun apply(plan: SurfacePlan) {
        val wanted = buildMap {
            for ((group, factor) in plan.alphaByGroup) {
                if (factor >= 1.0) continue
                for (key in group.keys) {
                    val base = table.themeColor(key)
                    if (key !in overriddenKeys && table.effectiveColor(key) != base) continue
                    when {
                        factor <= 0.0 -> put(key, TRANSPARENT)
                        base != null -> put(key, translucent(base, factor))
                    }
                }
            }
        }
        (overriddenKeys - wanted.keys).forEach { table.override(it, null) }
        wanted.forEach { (key, color) -> if (table.effectiveColor(key) != color) table.override(key, color) }
        overriddenKeys.clear()
        overriddenKeys += wanted.keys
    }

    fun restore() = apply(SurfacePlan.OPAQUE)

    private companion object {
        val TRANSPARENT: Color = ColorUIResource(Color(0, 0, 0, 0))
    }

    private fun translucent(base: Color, factor: Double): Color {
        val alpha = (base.alpha * factor.coerceIn(0.0, 1.0)).roundToInt()
        return ColorUIResource(Color(base.red, base.green, base.blue, alpha))
    }
}
