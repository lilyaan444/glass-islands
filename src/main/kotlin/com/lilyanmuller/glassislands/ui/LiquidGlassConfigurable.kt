package com.lilyanmuller.glassislands.ui

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.lilyanmuller.glassislands.LiquidGlassBundle
import com.lilyanmuller.glassislands.lifecycle.PresentationSync
import com.lilyanmuller.glassislands.settings.IslandContrast
import com.lilyanmuller.glassislands.settings.LiquidGlassSettings
import com.lilyanmuller.glassislands.settings.LiquidGlassState
import com.lilyanmuller.glassislands.window.GlassStatus
import com.lilyanmuller.glassislands.window.GlassWindowRegistry
import javax.swing.JComponent
import javax.swing.JEditorPane
import kotlin.reflect.KMutableProperty1

/**
 * Settings > Appearance & Behavior > Glass Islands. A handful of choices; every other value of the design
 * derives from them and the appearance follows the Rider theme. Changes preview live and apply without a restart.
 */
internal class LiquidGlassConfigurable : SearchableConfigurable {

    private val settings = LiquidGlassSettings.getInstance()
    private val registry = GlassWindowRegistry.getInstance()

    private var working = settings.snapshot()
    private var dialogPanel: DialogPanel? = null
    private var statusLabel: JEditorPane? = null

    override fun getId() = ID

    override fun getDisplayName() = LiquidGlassBundle.message("plugin.name")

    override fun createComponent(): JComponent {
        lateinit var enabled: Cell<JBCheckBox>
        val created = panel {
            row { enabled = toggle("settings.enable", LiquidGlassState::enabled) }
            row { statusLabel = comment(statusText()).component }
            rowsRange {
                row(LiquidGlassBundle.message("settings.contrast")) {
                    comboBox(IslandContrast.entries, textListCellRenderer { it?.let(::contrastName).orEmpty() })
                        .bindItem({ working.islandContrast }, { working.islandContrast = it ?: working.islandContrast })
                        .onChanged { changed() }
                }
                row(LiquidGlassBundle.message("settings.opacity")) {
                    slider(LiquidGlassState.MIN_OPACITY, 100, 5, 20)
                        .bindValue({ working.opacity }, { working.opacity = it })
                        .onChanged { changed() }
                }.rowComment(LiquidGlassBundle.message("settings.opacity.comment"))
                row {
                    toggle("settings.editor", LiquidGlassState::editorGlass)
                        .comment(LiquidGlassBundle.message("settings.editor.comment"))
                }
                row {
                    toggle("settings.controls", LiquidGlassState::glassControls)
                        .comment(LiquidGlassBundle.message("settings.controls.comment"))
                }
                row {
                    toggle("settings.flicker", LiquidGlassState::flickerFree)
                        .comment(LiquidGlassBundle.message(flickerStatusKey()))
                }
            }.enabledIf(enabled.selected)
            separator()
            row { button(LiquidGlassBundle.message("settings.reset")) { resetToDefaults() } }
        }
        dialogPanel = created
        return created
    }

    private fun Row.toggle(key: String, property: KMutableProperty1<LiquidGlassState, Boolean>): Cell<JBCheckBox> =
        checkBox(LiquidGlassBundle.message(key))
            .bindSelected({ property.get(working) }, { property.set(working, it) })
            .onChanged { changed() }

    override fun isModified(): Boolean = dialogPanel?.isModified() == true || working != settings.snapshot()

    override fun apply() {
        dialogPanel?.apply()
        registry.endPreview(refresh = false)
        settings.update(working.copy())
        updateStatus()
    }

    override fun reset() {
        working = settings.snapshot()
        dialogPanel?.reset()
        registry.endPreview(refresh = true)
        updateStatus()
    }

    override fun disposeUIResources() {
        registry.endPreview(refresh = true)
        dialogPanel = null
        statusLabel = null
    }

    private fun resetToDefaults() {
        working = LiquidGlassState()
        dialogPanel?.reset()
        changed()
    }

    private fun changed() {
        val panel = dialogPanel ?: return
        panel.apply()
        registry.preview(working.copy())
        updateStatus()
    }

    private fun updateStatus() {
        statusLabel?.text = statusText()
    }

    /** What the user actually gets, including why the glass may be paused. */
    private fun statusText(): String {
        val bridge = registry.bridge
        val text = when (val status = registry.status) {
            GlassStatus.Active -> {
                val controls = LiquidGlassBundle.message(if (bridge.glassAvailable) "settings.status.active.glass" else "settings.status.active.frosted")
                LiquidGlassBundle.message("settings.status.active", controls, bridge.osMajorVersion ?: "?")
            }
            GlassStatus.Disabled -> LiquidGlassBundle.message("settings.status.disabled")
            is GlassStatus.Paused -> LiquidGlassBundle.message("settings.status.paused", status.reason)
        }
        val contrast = registry.accessibilityOptions.increaseContrast && registry.status == GlassStatus.Active
        return if (contrast) "<html>$text<br>${LiquidGlassBundle.message("settings.status.contrast")}</html>" else text
    }

    private fun flickerStatusKey() = when {
        PresentationSync.isActive -> "settings.flicker.active"
        PresentationSync.isConfigured -> "settings.flicker.restart"
        else -> "settings.flicker.inactive"
    }

    companion object {
        const val ID = "com.lilyanmuller.glassislands.settings"

        fun contrastName(contrast: IslandContrast) = LiquidGlassBundle.message("settings.contrast.${contrast.name.lowercase()}")
    }
}
