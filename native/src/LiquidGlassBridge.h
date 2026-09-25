#ifndef LIQUID_GLASS_BRIDGE_H
#define LIQUID_GLASS_BRIDGE_H

#include <stdint.h>

/*
 * C ABI consumed from the JVM through the Foreign Function & Memory API (java.lang.foreign), so the plugin keeps
 * no JNA proxy or structure cache and can be unloaded without a restart. Only plain integers and double arrays
 * cross the boundary.
 *
 * Every entry point runs its AppKit work on the main thread and waits for it, like the JDK's own AWT natives:
 * the wait is also serviced in AWTRunLoopMode, so calling from the AWT event thread cannot deadlock, and the
 * native shapes change in the same turn as the Swing content they sit under.
 */

#define RLG_BRIDGE_VERSION 8

/* Material of the controls; surfaces and backdrops are always frosted NSVisualEffectView. */
#define RLG_BACKEND_VISUAL_EFFECT 0
#define RLG_BACKEND_GLASS 1

#define RLG_APPEARANCE_DARK 1
#define RLG_APPEARANCE_LIGHT 2

/* NSVisualEffectView material of a backdrop. */
#define RLG_MATERIAL_WINDOW 0   /* UnderWindowBackground: project windows */
#define RLG_MATERIAL_MENU 1     /* Menu: popups, lookups, context menus */
#define RLG_MATERIAL_POPOVER 2  /* Popover: floating tool windows, documentation, balloons */

#define RLG_STATUS_WINDOW_FOUND 1
#define RLG_STATUS_BACKDROP_ATTACHED 2
#define RLG_STATUS_GLASS_BACKEND 4  /* controls use Liquid Glass */
#define RLG_STATUS_WINDOW_NON_OPAQUE 8
#define RLG_STATUS_LAYER_NON_OPAQUE 16
#define RLG_STATUS_ISLANDS_ATTACHED 32

/* System accessibility display options (System Settings > Accessibility > Display). */
#define RLG_ACCESSIBILITY_REDUCE_TRANSPARENCY 1
#define RLG_ACCESSIBILITY_INCREASE_CONTRAST 2
#define RLG_ACCESSIBILITY_REDUCE_MOTION 4

/*
 * Configuration shared by a backdrop or by all shapes of a window: RLG_CONFIG_LENGTH doubles
 *   backend           RLG_BACKEND_*
 *   appearance        RLG_APPEARANCE_*
 *   alpha             0..1
 *   red, green, blue, tint alpha   backdrop tint (0..1); alpha 0 disables it. Shapes carry their own tint.
 *   increased contrast  1 when the rims must be clearly visible (Increase Contrast)
 *   material          RLG_MATERIAL_* (backdrops only)
 *   corner radius     backdrop corner radius in points (backdrops only; 0 = square, the window clips it)
 */
#define RLG_CONFIG_LENGTH 10

int32_t rlg_bridge_version(void);
int32_t rlg_os_major_version(void);
int32_t rlg_glass_available(void);
int32_t rlg_accessibility_options(void);

/* Whole-window frosted backdrop behind every shape. */
void rlg_apply_backdrop(int64_t nsWindow, const double *config);

/*
 * Shapes of the IDE, stacked between the whole-window backdrop and the AWT content view. `shapes` holds `count`
 * records of RLG_ISLAND_STRIDE doubles:
 *   x, y, width, height   points, top-left origin, relative to the window frame
 *   red, green, blue, a   tint (0..1); alpha 0 = no tint
 *   corner radius         points
 *   shadow                0 = none, 1 = full island shadow
 *   role                  0 = surface: frosted NSVisualEffectView with specular rim, identical in active and
 *                             inactive windows (NSGlassEffectView turns flat or loses its blur when the window
 *                             resigns key, so it is not used for large panes);
 *                         1 = control: Liquid Glass (NSGlassEffectView in an NSGlassEffectContainerView) when the
 *                             config backend is RLG_BACKEND_GLASS, dimmed by macOS in inactive windows like native controls
 *   identifier            stable per shape; views are reused by identifier and only changed state is reapplied
 * `count == 0` removes every shape.
 */
#define RLG_ISLAND_STRIDE 12
void rlg_set_islands(int64_t nsWindow, const double *shapes, int32_t count, const double *config);

/* Removes the whole-window backdrop only; shapes are removed with rlg_set_islands(count = 0). */
void rlg_remove_backdrop(int64_t nsWindow);

/* Current state of this window (RLG_STATUS_* flags). */
int32_t rlg_window_status(int64_t nsWindow);

/* Human readable snapshot of the native hierarchy; release with rlg_free_string. */
char *rlg_describe_window(int64_t nsWindow);
void rlg_free_string(char *value);

#endif
