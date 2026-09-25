package com.lilyanmuller.glassislands.window

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.lilyanmuller.glassislands.internals.PlatformInternals
import java.awt.Color
import java.awt.Component
import java.awt.Window
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Lets the editor glass shape show through the editors of one window: each editor paints no background, its
 * caret row becomes a translucent veil, and its containers up to `EditorsSplitters` stop filling theirs.
 * Only public editor APIs are used, and colours go through the editor's own scheme delegate, so the global
 * colour scheme is never modified. EDT only.
 */
internal class EditorTransparency(private val window: Window) {

    /** An editor colour before we changed it; inherited colours go back to following the scheme. */
    private class SavedColor(val color: Color?, val inherited: Boolean)

    private class Touched(val colors: Map<ColorKey, SavedColor>, val opaqueContainers: List<JComponent>)

    private val touched = mutableMapOf<EditorEx, Touched>()

    /** Global scheme the touched editors were made transparent with; their saved colours belong to it. */
    private var scheme: EditorColorsScheme? = null

    val editorCount: Int get() = touched.size

    /**
     * Applies to editors of the window not handled yet; called whenever the window layout changes. When the colour
     * scheme changed (theme switch), every editor is restored and redone, so no colour of the old scheme lingers.
     */
    fun refresh() {
        val current = EditorColorsManager.getInstance().globalScheme
        if (current !== scheme) {
            restoreAll()
            scheme = current
        }
        touched.keys.removeAll { it.isDisposed }
        EditorFactory.getInstance().allEditors
            .filterIsInstance<EditorEx>()
            .filter { !it.isDisposed && it !in touched && SwingUtilities.getWindowAncestor(it.component) === window }
            .forEach { touched[it] = makeTransparent(it) }
    }

    fun restoreAll() {
        touched.forEach { (editor, state) -> if (!editor.isDisposed) restore(editor, state) }
        touched.clear()
        scheme = null
    }

    private fun makeTransparent(editor: EditorEx): Touched {
        val scheme = editor.colorsScheme
        val global = EditorColorsManager.getInstance().globalScheme
        val colors = (TRANSPARENT_KEYS + CARET_ROW).associateWith {
            val color = scheme.getColor(it)
            SavedColor(color, inherited = color == global.getColor(it))
        }
        TRANSPARENT_KEYS.forEach { scheme.setColor(it, TRANSPARENT) }
        colors[CARET_ROW]?.color?.let { scheme.setColor(CARET_ROW, Color(it.red, it.green, it.blue, CARET_ROW_ALPHA)) }
        editor.setBackgroundColor(TRANSPARENT)
        val opaque = containersUpToSplitters(editor.contentComponent).filter { it.isOpaque }
        opaque.forEach { it.isOpaque = false }
        editor.component.repaint()
        return Touched(colors, opaque)
    }

    private fun restore(editor: EditorEx, state: Touched) {
        // Re-setting an inherited colour would pin it: after a theme switch the editor would keep the old one.
        state.colors.forEach { (key, saved) ->
            editor.colorsScheme.setColor(key, if (saved.inherited) AbstractColorsScheme.INHERITED_COLOR_MARKER else saved.color)
        }
        editor.setBackgroundColor(null)
        state.opaqueContainers.forEach { it.isOpaque = true }
        editor.component.repaint()
    }

    private fun containersUpToSplitters(start: Component): List<JComponent> {
        val result = mutableListOf<JComponent>()
        var current: Component? = start
        while (current != null && current !is Window) {
            if (current is JComponent) result += current
            if (current.javaClass.name == PlatformInternals.EDITOR_ISLAND) break
            current = current.parent
        }
        return result
    }

    private companion object {
        val TRANSPARENT = Color(0, 0, 0, 0)
        val TRANSPARENT_KEYS: List<ColorKey> = listOf(EditorColors.GUTTER_BACKGROUND, EditorColors.EDITOR_GUTTER_BACKGROUND)
        val CARET_ROW: ColorKey = EditorColors.CARET_ROW_COLOR

        /** The caret row stays visible but reads as a veil on the glass rather than an opaque band. */
        const val CARET_ROW_ALPHA = 110
    }
}
