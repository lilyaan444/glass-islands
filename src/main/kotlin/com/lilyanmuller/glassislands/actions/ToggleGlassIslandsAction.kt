package com.lilyanmuller.glassislands.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction
import com.lilyanmuller.glassislands.settings.LiquidGlassSettings

/** View > Appearance > Liquid Glass, also reachable from Find Action and assignable to a shortcut. */
internal class ToggleGlassIslandsAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = LiquidGlassSettings.getInstance().snapshot().enabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val settings = LiquidGlassSettings.getInstance()
        settings.update(settings.snapshot().copy(enabled = state))
    }
}
