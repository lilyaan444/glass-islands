package com.lilyanmuller.glassislands.settings

import java.awt.Color

/**
 * Island and window colours of one appearance; the appearance itself always follows the Rider theme. Islands are
 * lighter than the window so they read as floating on it: very dark and slightly blue in dark mode, white glass on
 * a soft blue-grey in light mode.
 */
data class GlassPalette(val island: Color, val window: Color) {
    companion object {
        val DARK = GlassPalette(island = Color(34, 38, 50), window = Color(6, 8, 14))
        val LIGHT = GlassPalette(island = Color(255, 255, 255), window = Color(226, 230, 238))

        fun of(dark: Boolean) = if (dark) DARK else LIGHT
    }
}

/** Which layer carries the light colour: the islands (raised on a dark window) or the window (islands recessed in it). */
enum class IslandContrast {
    RAISED,
    INVERTED,
}
