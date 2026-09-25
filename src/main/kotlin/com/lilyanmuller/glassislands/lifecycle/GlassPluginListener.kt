package com.lilyanmuller.glassislands.lifecycle

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor

/** Undoes the plugin's only IDE-level change (the presentation VM option) when it is uninstalled or disabled. */
internal class GlassPluginListener : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (!isUpdate && pluginDescriptor.pluginId.idString == PLUGIN_ID) PresentationSync.removeIfManaged()
    }

    private companion object {
        const val PLUGIN_ID = "com.lilyanmuller.glassislands"
    }
}
