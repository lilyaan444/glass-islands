package com.lilyanmuller.glassislands.settings

/**
 * Persisted settings: deliberately few, every other value of the design derives from them, and the appearance
 * (dark or light) always follows the Rider theme.
 */
data class LiquidGlassState(
    var enabled: Boolean = true,
    var islandContrast: IslandContrast = IslandContrast.RAISED,
    /** Opacity of the tool-window islands in percent; the editor and the window derive theirs from it. */
    var opacity: Int = DEFAULT_OPACITY,
    var editorGlass: Boolean = true,
    var glassControls: Boolean = true,
    /** Present frames once complete (see PresentationSync): removes blank flashes on the glass. */
    var flickerFree: Boolean = true,
    /** The plugin wrote the presentation VM option, so it removes it when uninstalled. */
    var displaySyncManaged: Boolean = false,
) {
    fun normalized(): LiquidGlassState = copy(opacity = opacity.coerceIn(MIN_OPACITY, 100))

    companion object {
        const val DEFAULT_OPACITY = 62

        /** Below this Rider's text loses contrast against busy desktops. */
        const val MIN_OPACITY = 20
    }
}
