package com.lilyanmuller.glassislands.shapes

import com.intellij.ide.ui.UISettings
import com.intellij.ui.scale.JBUIScale
import javax.swing.UIManager

/**
 * Island geometry of the Islands theme, mirrored from IslandsUICustomization.paintIslandAreaRaw: each island
 * is its component inset by `Island.borderWidth / 2` (so two neighbours are one border width apart), with
 * corners of radius `Island.arc / 2` (`Island.arc.compact` in compact mode).
 */
object IslandGeometry {
    private const val DEFAULT_BORDER_WIDTH = 6
    private const val DEFAULT_ARC = 20
    private const val DEFAULT_COMPACT_ARC = 16
    private const val DEFAULT_BUTTON_ARC = 12
    private const val DEFAULT_POPUP_RADIUS = 8

    fun inset(): Int = JBUIScale.scale(themeInt("Island.borderWidth", DEFAULT_BORDER_WIDTH) / 2)

    fun cornerRadius(): Double {
        val arc = if (UISettings.getInstance().compactMode) themeInt("Island.arc.compact", DEFAULT_COMPACT_ARC) else themeInt("Island.arc", DEFAULT_ARC)
        return JBUIScale.scale(arc / 2f).toDouble()
    }

    /** Corner radius of popups (`PopupMenu.borderCornerRadius`), which JBR applies to their native windows. */
    fun popupRadius(): Double = JBUIScale.scale(themeInt("PopupMenu.borderCornerRadius", DEFAULT_POPUP_RADIUS).toFloat()).toDouble()

    /** Arc of stripe buttons (SquareStripeButtonLook uses `Button.ToolWindow.arc`, 12 by default). */
    fun stripeButtonRadius(): Double = JBUIScale.scale(themeInt("Button.ToolWindow.arc", DEFAULT_BUTTON_ARC) / 2f).toDouble()

    /** Arc of main toolbar buttons and widgets. */
    fun toolbarButtonRadius(): Double = JBUIScale.scale(themeInt("MainToolbar.Button.arc", DEFAULT_BUTTON_ARC) / 2f).toDouble()

    /** Arc of editor tabs (IslandsTabPainter uses the main toolbar hover arc). */
    fun tabRadius(): Double = JBUIScale.scale(themeInt("MainToolbar.Button.hoverArc", DEFAULT_BUTTON_ARC) / 2f).toDouble()

    private fun themeInt(key: String, default: Int) = UIManager.getInt(key).takeIf { it > 0 } ?: default
}
