package com.lilyanmuller.glassislands.actions

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * Help > Diagnostic Tools > Liquid Glass Debug Information: writes the report next to idea.log and opens it with the
 * system text viewer, like Help > Show Log in Finder. No dialog and no editor opened from plugin code: those keep
 * references (stack traces, accessibility caches) that could prevent the plugin from unloading.
 */
internal class ShowDebugInformationAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        DebugReport.save(DebugReport.build(e.project))
        RevealFileAction.openFile(DebugReport.file())
    }
}
