package com.lilyanmuller.glassislands.shapes

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JPanel

class GlassShapeLocatorTest {
    @Test
    fun `plain Swing buttons are not IDE toolbar controls`() {
        assertFalse(GlassShapeLocator.isControl(JButton()))
        assertNull(GlassShapeLocator.interactiveOf(JPanel().also { it.add(JButton()) }.getComponent(0)))
        assertNull(GlassShapeLocator.interactiveOf(null))
    }

    @Test
    fun `class matching walks the superclass chain`() {
        assertTrue(GlassShapeLocator.isA(JPanel(), "javax.swing.JComponent"))
        assertFalse(GlassShapeLocator.isA(JPanel(), "com.intellij.openapi.fileEditor.impl.EditorsSplitters"))
    }
}
