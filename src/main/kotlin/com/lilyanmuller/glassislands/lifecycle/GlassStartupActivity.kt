package com.lilyanmuller.glassislands.lifecycle

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.lilyanmuller.glassislands.environment.NativePlatform
import com.lilyanmuller.glassislands.window.GlassWindowRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class GlassStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (NativePlatform.current() == null) return
        withContext(Dispatchers.EDT) {
            GlassWindowRegistry.getInstance().attach(project)
        }
    }
}
