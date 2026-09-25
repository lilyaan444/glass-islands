package com.lilyanmuller.glassislands.nativebridge

import java.awt.Color
import java.awt.Rectangle

/** Material of the controls (RLG_BACKEND_*); surfaces and the backdrop are always frosted NSVisualEffectView. */
enum class BackdropBackend(val nativeCode: Int) {
    VISUAL_EFFECT(0),

    /** NSGlassEffectView: the Liquid Glass material, public AppKit API since macOS 26. */
    GLASS(1),
}

/** Mirrors RLG_APPEARANCE_* in LiquidGlassBridge.h. */
enum class BackdropAppearance(val nativeCode: Int) {
    DARK(1),
    LIGHT(2),
}

/** Mirrors RLG_MATERIAL_*: the NSVisualEffectView material of a backdrop. */
enum class BackdropMaterial(val nativeCode: Int) {
    /** Project windows. */
    WINDOW(0),

    /** Popups, lookups and menus. */
    MENU(1),

    /** Floating panels: documentation, balloons, floating tool windows. */
    POPOVER(2),
}

/** Mirrors the role field of RLG_ISLAND_STRIDE records. */
enum class ShapeRole(val nativeCode: Int) {
    /** Frosted pane with a specular rim, identical whether or not Rider is the key window. */
    SURFACE(0),

    /** Liquid Glass pill, dimmed by macOS in inactive windows like any native control. */
    CONTROL(1),
}

/**
 * One native shape: bounds in window coordinates (top-left origin, points), tint (`null` for none), corner radius,
 * shadow strength (0 = none, 1 = the full island shadow) and a [key] that stays the same for the same IDE
 * component, so the native side updates that view in place instead of recreating or re-assigning views.
 */
data class IslandShape(
    val bounds: Rectangle,
    val tint: Color?,
    val cornerRadius: Double,
    val shadow: Double,
    val role: ShapeRole,
    val key: Long,
)

/**
 * Whole-window backdrop, or the material and appearance shared by all shapes of a window. [increasedContrast]
 * strengthens the rims (Increase Contrast); [material] and [cornerRadius] only apply to backdrops.
 */
data class BackdropSpec(
    val backend: BackdropBackend,
    val appearance: BackdropAppearance,
    val alpha: Double,
    val tint: Color?,
    val increasedContrast: Boolean = false,
    val material: BackdropMaterial = BackdropMaterial.WINDOW,
    val cornerRadius: Double = 0.0,
)
