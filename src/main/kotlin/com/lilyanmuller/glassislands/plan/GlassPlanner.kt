package com.lilyanmuller.glassislands.plan

import com.lilyanmuller.glassislands.nativebridge.AccessibilityOptions
import com.lilyanmuller.glassislands.nativebridge.BackdropAppearance
import com.lilyanmuller.glassislands.nativebridge.BackdropBackend
import com.lilyanmuller.glassislands.nativebridge.BackdropMaterial
import com.lilyanmuller.glassislands.nativebridge.BackdropSpec
import com.lilyanmuller.glassislands.settings.GlassPalette
import com.lilyanmuller.glassislands.settings.IslandContrast
import com.lilyanmuller.glassislands.settings.LiquidGlassState
import com.lilyanmuller.glassislands.shapes.GlassShapeKind
import com.lilyanmuller.glassislands.surfaces.SurfaceGroup
import com.lilyanmuller.glassislands.surfaces.SurfacePlan
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Shapes to draw behind the IDE surfaces: one shared [material], one tint per kind; kinds without a tint are not drawn. */
data class IslandsPlan(val material: BackdropSpec, val tintByKind: Map<GlassShapeKind, Color>)

/**
 * Everything to apply to Rider's windows. The native layer owns every surface: [background] is the frosted window,
 * [islands] the panes and glass pills on it, and [surfaces] makes the matching Swing backgrounds transparent so
 * Swing only paints content. [glassControls] tells whether toolbar/stripe buttons lose their Swing highlight.
 * [popup] is the menu material of popups, lookups and context menus; [floating] the material of floating tool
 * windows.
 */
data class GlassPlan(
    val background: BackdropSpec,
    val islands: IslandsPlan,
    val surfaces: SurfacePlan,
    val glassControls: Boolean,
    val popup: BackdropSpec,
    val floating: BackdropSpec,
)

/** What the plan depends on besides the user's settings. */
data class GlassEnvironment(
    val ideIsDark: Boolean,
    val glassAvailable: Boolean,
    val accent: Color = GlassPlanner.DEFAULT_ACCENT,
    val accessibility: AccessibilityOptions = AccessibilityOptions.NONE,
)

/**
 * The design system: opacity grows with the importance of the content (window < tool windows < editor), islands
 * are lighter than the window (or darker with inverted contrast), and accents stay translucent on the glass.
 * macOS accessibility settings win over the user's taste: Reduce Transparency turns the glass off and Increase
 * Contrast makes every layer more opaque with clearly drawn rims.
 */
object GlassPlanner {
    /** The editor is the content layer: always clearly more opaque than the tool windows, never fully opaque. */
    const val EDITOR_OPACITY_BONUS = 0.24
    const val EDITOR_MAX_OPACITY = 0.94

    /** The window under toolbar, stripes and status bar is a step lighter than the tool windows. */
    const val WINDOW_OPACITY_FACTOR = 0.9

    const val SELECTION_ALPHA = 0.72
    const val FOCUSED_CONTROL_ALPHA = 0.62

    /**
     * Popups use the menu material, already denser than the window material: the same tint as tool windows keeps
     * the frosting visible, as in macOS menus, while short texts stay crisp.
     */
    const val POPUP_OPACITY_BONUS = 0.0
    const val POPUP_MAX_OPACITY = 0.92

    /** Increase Contrast: surfaces at least this opaque, selections nearly solid. */
    const val CONTRAST_MIN_OPACITY = 80
    const val CONTRAST_SELECTION_ALPHA = 0.9

    /** Accent of `ToolWindow.Button.selectedBackground` in the Islands themes, used when the theme has none. */
    val DEFAULT_ACCENT = Color(0x38, 0x71, 0xE1)

    private val DARK_HOVER = Color(255, 255, 255, 22)
    private val LIGHT_HOVER = Color(0, 0, 0, 12)
    private val DARK_SELECTED = Color(255, 255, 255, 40)
    private val LIGHT_SELECTED = Color(0, 0, 0, 22)
    private val DARK_SELECTED_TAB = Color(255, 255, 255, 34)
    private val LIGHT_SELECTED_TAB = Color(255, 255, 255, 215)

    /** Returns `null` when the glass must not be shown: disabled, or Reduce Transparency is on. */
    fun plan(state: LiquidGlassState, environment: GlassEnvironment): GlassPlan? {
        if (!state.enabled || environment.accessibility.reduceTransparency) return null
        val effective = effectiveState(state, environment.accessibility)
        val contrast = environment.accessibility.increaseContrast
        val dark = environment.ideIsDark
        val appearance = if (dark) BackdropAppearance.DARK else BackdropAppearance.LIGHT
        val (island, window) = colours(effective, dark)
        val opacity = effective.opacity / 100.0
        val background = BackdropSpec(
            BackdropBackend.VISUAL_EFFECT, appearance, 1.0, window.withAlpha(opacity * WINDOW_OPACITY_FACTOR), increasedContrast = contrast,
        )
        val controls = if (effective.glassControls && environment.glassAvailable) BackdropBackend.GLASS else BackdropBackend.VISUAL_EFFECT
        val material = BackdropSpec(controls, appearance, 1.0, island, increasedContrast = contrast)
        return GlassPlan(
            background = background,
            islands = IslandsPlan(material, tints(effective, island, dark, environment.accent)),
            surfaces = SurfacePlan(surfaceAlphas(effective, contrast)),
            glassControls = effective.glassControls,
            popup = BackdropSpec(
                BackdropBackend.VISUAL_EFFECT, appearance, 1.0, island.withAlpha(popupOpacity(effective)),
                increasedContrast = contrast, material = BackdropMaterial.MENU,
            ),
            floating = BackdropSpec(
                BackdropBackend.VISUAL_EFFECT, appearance, 1.0, island.withAlpha(opacity),
                increasedContrast = contrast, material = BackdropMaterial.POPOVER,
            ),
        )
    }

    /** The settings as actually rendered: Increase Contrast raises the opacity floor. */
    fun effectiveState(state: LiquidGlassState, accessibility: AccessibilityOptions): LiquidGlassState =
        if (accessibility.increaseContrast) state.copy(opacity = max(state.opacity, CONTRAST_MIN_OPACITY)) else state

    /** (island colour, window colour) for the appearance; inverted contrast swaps the two roles. */
    fun colours(state: LiquidGlassState, dark: Boolean): Pair<Color, Color> {
        val palette = GlassPalette.of(dark)
        return if (state.islandContrast == IslandContrast.INVERTED) palette.window to palette.island else palette.island to palette.window
    }

    fun popupOpacity(state: LiquidGlassState): Double = min(POPUP_MAX_OPACITY, state.opacity / 100.0 + POPUP_OPACITY_BONUS)

    fun editorOpacity(state: LiquidGlassState): Double =
        if (state.editorGlass) min(EDITOR_MAX_OPACITY, state.opacity / 100.0 + EDITOR_OPACITY_BONUS) else 1.0

    fun tints(state: LiquidGlassState, island: Color, dark: Boolean, accent: Color): Map<GlassShapeKind, Color> = buildMap {
        put(GlassShapeKind.TOOL_WINDOW, island.withAlpha(state.opacity / 100.0))
        put(GlassShapeKind.EDITOR, island.withAlpha(editorOpacity(state)))
        if (state.glassControls) {
            val hover = if (dark) DARK_HOVER else LIGHT_HOVER
            put(GlassShapeKind.FOCUSED_CONTROL, accent.withAlpha(FOCUSED_CONTROL_ALPHA))
            put(GlassShapeKind.SELECTED_CONTROL, if (dark) DARK_SELECTED else LIGHT_SELECTED)
            put(GlassShapeKind.HOVERED_CONTROL, hover)
            put(GlassShapeKind.SELECTED_TAB, if (dark) DARK_SELECTED_TAB else LIGHT_SELECTED_TAB)
            put(GlassShapeKind.HOVERED_TAB, hover)
        }
    }

    /** Swing backgrounds replaced by glass become transparent; selections stay as translucent accents. */
    fun surfaceAlphas(state: LiquidGlassState, increasedContrast: Boolean = false): Map<SurfaceGroup, Double> = SurfaceGroup.entries
        .filter { state.glassControls || (it != SurfaceGroup.CONTROLS && it != SurfaceGroup.TABS) }
        .associateWith {
            when {
                it != SurfaceGroup.SELECTION -> 0.0
                increasedContrast -> CONTRAST_SELECTION_ALPHA
                else -> SELECTION_ALPHA
            }
        }

    private fun Color.withAlpha(alpha: Double) = Color(red, green, blue, (alpha.coerceIn(0.0, 1.0) * 255).roundToInt())
}
