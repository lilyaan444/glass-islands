package com.lilyanmuller.glassislands.window

import com.intellij.openapi.actionSystem.ActionButtonComponent
import com.intellij.openapi.actionSystem.ex.ActionButtonLook
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.lilyanmuller.glassislands.internals.PlatformInternals
import java.awt.Graphics
import java.util.WeakHashMap
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Look of toolbar and stripe buttons while their hover/selection pill is native glass: the original look still
 * paints the icon (including the selected-icon colour and dropdown arrow), but no background or border, so the
 * glass is the only layer and never doubles up with a Swing highlight.
 */
private class GlassButtonLook(val original: ActionButtonLook) : ActionButtonLook() {
    override fun <ButtonType> paintBackground(g: Graphics, component: ButtonType)
        where ButtonType : JComponent, ButtonType : ActionButtonComponent = Unit

    override fun <ButtonType> paintBorder(g: Graphics, component: ButtonType)
        where ButtonType : JComponent, ButtonType : ActionButtonComponent = Unit

    override fun paintBackground(g: Graphics, component: JComponent, state: Int) = Unit

    override fun paintBorder(g: Graphics, component: JComponent, state: Int) = Unit

    override fun paintIcon(g: Graphics, actionButton: ActionButtonComponent, icon: Icon) =
        original.paintIcon(g, actionButton, icon)

    override fun paintIcon(g: Graphics, actionButton: ActionButtonComponent, icon: Icon, x: Int, y: Int) =
        original.paintIcon(g, actionButton, icon, x, y)

    override fun paintDownArrow(g: Graphics, actionButton: ActionButtonComponent, originalIcon: Icon, arrowIcon: Icon) =
        original.paintDownArrow(g, actionButton, originalIcon, arrowIcon)

    override fun getDisabledIcon(icon: Icon): Icon = original.getDisabledIcon(icon)

    override fun updateUI() = original.updateUI()
}

/** Installs [GlassButtonLook] on the buttons of one window and restores their own look afterwards. EDT only. */
internal class GlassButtonLooks {
    private val originals = WeakHashMap<ActionButton, ActionButtonLook>()

    /** Wraps new buttons, and buttons whose look the IDE replaced (theme switch), leaving the others untouched. */
    fun install(buttons: List<ActionButton>) {
        for (button in buttons) {
            val current = currentLook(button) ?: continue
            if (current is GlassButtonLook) continue
            originals[button] = current
            button.setLook(GlassButtonLook(current))
            button.repaint()
        }
    }

    fun restoreAll() {
        originals.forEach { (button, look) ->
            button.setLook(look)
            button.repaint()
        }
        originals.clear()
    }

    private companion object {
        /** `ActionButton.getButtonLook()` is protected; the look set by the IDE is not otherwise readable. */
        fun currentLook(button: ActionButton): ActionButtonLook? =
            PlatformInternals.getButtonLook?.invoke(button) as? ActionButtonLook
    }
}
