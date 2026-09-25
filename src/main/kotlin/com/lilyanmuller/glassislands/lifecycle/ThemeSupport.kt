package com.lilyanmuller.glassislands.lifecycle

import com.lilyanmuller.glassislands.LiquidGlassBundle
import javax.swing.UIManager

/**
 * The glass is designed around the Islands themes. They are recognised by the island geometry they define
 * (`Island.arc`, 20 in Islands Dark/Light/Darcula), which the Classic UI and the other themes do not define; High
 * Contrast defines it as 0, square and opaque on purpose.
 */
internal object ThemeSupport {
    fun unsupportedReason(): String? {
        val arc = UIManager.get("Island.arc") as? Number ?: return LiquidGlassBundle.message("pause.theme.no.islands")
        if (arc.toInt() == 0) return LiquidGlassBundle.message("pause.theme.high.contrast")
        return null
    }
}
