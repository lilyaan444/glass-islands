package com.lilyanmuller.glassislands.window

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsListener
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.EDT
import com.lilyanmuller.glassislands.environment.EnvironmentInfo
import com.lilyanmuller.glassislands.internals.PlatformInternals
import com.lilyanmuller.glassislands.lifecycle.GlassNotifications
import com.lilyanmuller.glassislands.lifecycle.PresentationSync
import com.lilyanmuller.glassislands.lifecycle.StartupGuard
import com.lilyanmuller.glassislands.lifecycle.ThemeSupport
import com.lilyanmuller.glassislands.nativebridge.AccessibilityOptions
import com.lilyanmuller.glassislands.nativebridge.NativeBridge
import com.lilyanmuller.glassislands.nativebridge.NativeBridgeLoader
import com.lilyanmuller.glassislands.plan.GlassEnvironment
import com.lilyanmuller.glassislands.plan.GlassPlan
import com.lilyanmuller.glassislands.plan.GlassPlanner
import com.lilyanmuller.glassislands.settings.LiquidGlassSettings
import com.lilyanmuller.glassislands.settings.LiquidGlassSettingsListener
import com.lilyanmuller.glassislands.settings.LiquidGlassState
import com.lilyanmuller.glassislands.surfaces.SurfaceTranslucency
import com.lilyanmuller.glassislands.surfaces.SwingUiColorTable
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/** Whether the glass is shown, and if not, why: shown in the settings page and the debug report. */
sealed interface GlassStatus {
    data object Active : GlassStatus
    data object Disabled : GlassStatus
    data class Paused(val reason: String) : GlassStatus
}

/**
 * Tracks the main frame of each open project and keeps its glass in sync with the settings, the theme and the
 * macOS accessibility options. EDT only.
 */
@Service(Service.Level.APP)
class GlassWindowRegistry : Disposable {

    val environment: EnvironmentInfo = EnvironmentInfo.detect()

    private val bridgeLoader = lazy {
        NativeBridgeLoader.load(pluginRoot(), environment.nativePlatform, PathManager.getSystemDir().resolve("glass-islands").resolve("native"))
    }
    val bridge: NativeBridge by bridgeLoader

    private val controllers = LinkedHashMap<Project, GlassWindowController>()

    /** Frames not displayable yet, waiting to be attached when they open; unhooked if the plugin unloads first. */
    private val pendingFrames = HashMap<java.awt.Window, WindowAdapter>()
    private val secondaryWindows by lazy {
        SecondaryWindowWatcher(bridge, isProjectFrame = { window -> controllers.values.any { it.frame === window } }, onRevealed = ::applySurfaces)
    }
    private val surfaces = SurfaceTranslucency()
    private val startupGuard = StartupGuard()
    private var previewState: LiquidGlassState? = null
    private var accessibility = AccessibilityOptions.NONE
    private var presentationMode = UISettings.getInstance().presentationMode

    var status: GlassStatus = GlassStatus.Disabled
        private set

    /** When this instance of the plugin was loaded: shows reloads (dynamic plugin updates) in the debug report. */
    val loadedAt: java.time.LocalTime = java.time.LocalTime.now().withNano(0)

    init {
        LOG.info("Liquid Glass initialized: ${environment.summary()}")
        if (startupGuard.previousSessionFailed()) {
            val settings = LiquidGlassSettings.getInstance()
            settings.update(settings.snapshot().copy(enabled = false))
            GlassNotifications.turnedOffAfterFailure()
        }
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(LiquidGlassSettingsListener.TOPIC, LiquidGlassSettingsListener { refreshAll() })
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener {
            if (controllers.isNotEmpty()) secondaryWindows.onThemeChanged()
            refreshAll()
        })
        // Editors switch to the new scheme in their own listener of the same topic: refresh after them, so the
        // colours saved and derived are the new scheme's.
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
            ApplicationManager.getApplication().invokeLater(::refreshAll, ModalityState.any())
        })
        connection.subscribe(UISettingsListener.TOPIC, UISettingsListener { settings ->
            if (settings.presentationMode != presentationMode) {
                presentationMode = settings.presentationMode
                refreshAll()
            }
        })
        connection.subscribe(ProjectCloseListener.TOPIC, object : ProjectCloseListener {
            override fun projectClosing(project: Project) = onEdt { detach(project) }
        })
        // Accessibility options are changed in System Settings, so they are re-read whenever Rider comes back.
        connection.subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
            override fun applicationActivated(ideFrame: IdeFrame) = refreshAccessibility()
        })
        // Installed or re-enabled without a restart: projects are already open.
        ApplicationManager.getApplication().invokeLater({ ProjectManager.getInstance().openProjects.forEach(::attach) }, ModalityState.any())
    }

    val activeSurfaceKeys: Set<String> get() = surfaces.activeKeys

    val accessibilityOptions: AccessibilityOptions get() = accessibility

    fun attach(project: Project) {
        if (project.isDisposed || project in controllers) return
        val frame = WindowManager.getInstance().getFrame(project) ?: return
        if (!frame.isDisplayable) {
            val listener = object : WindowAdapter() {
                override fun windowOpened(e: WindowEvent) {
                    frame.removeWindowListener(this)
                    pendingFrames.remove(frame)
                    attach(project)
                }
            }
            pendingFrames[frame] = listener
            frame.addWindowListener(listener)
            return
        }
        LOG.info("Main window found for project '${project.name}': ${frame.javaClass.name}")
        if (controllers.isEmpty()) accessibility = bridge.accessibilityOptions()
        controllers[project] = GlassWindowController(frame, bridge, ::applySurfaces)
        refreshAll()
    }

    /** Applies unsaved settings while the settings page is open. */
    fun preview(state: LiquidGlassState) {
        previewState = state
        refreshAll()
    }

    /** Returns to the saved settings; [refresh] is false when a settings update is about to refresh anyway. */
    fun endPreview(refresh: Boolean) {
        if (previewState == null) return
        previewState = null
        if (refresh) refreshAll()
    }

    /**
     * Global Swing surfaces turn transparent only once a window shows its glass, and turn opaque again before any
     * window drops it; see GlassWindowController for the per-window ordering.
     */
    fun refreshAll() {
        val plan = currentPlan()
        if (plan == null) surfaces.restore()
        if (plan != null && controllers.isNotEmpty()) startupGuard.arm()
        controllers.values.forEach { it.apply(plan) }
        secondaryWindows.apply(plan?.takeIf { controllers.isNotEmpty() })
        applySurfaces()
        syncPresentation(plan != null && controllers.values.any { it.isTranslucent })
    }

    /** Keeps the flicker-free presentation option in step with the glass; the first write asks for a restart. */
    private fun syncPresentation(glassShown: Boolean) {
        val state = previewState ?: LiquidGlassSettings.getInstance().snapshot()
        if (!glassShown && state.enabled) return
        if (PresentationSync.sync(state.enabled && state.flickerFree)) GlassNotifications.restartForFlickerFix()
    }

    private fun refreshAccessibility() {
        if (controllers.isEmpty() || !bridge.isAvailable) return
        val current = bridge.accessibilityOptions()
        if (current == accessibility) return
        LOG.info("macOS accessibility options changed: reduce transparency=${current.reduceTransparency}, increase contrast=${current.increaseContrast}")
        accessibility = current
        refreshAll()
    }

    private fun applySurfaces() {
        val plan = currentPlan()
        if (plan != null && controllers.values.any { it.isTranslucent }) surfaces.apply(plan.surfaces) else surfaces.restore()
    }

    internal fun controllerFor(project: Project): GlassWindowController? = controllers[project]

    /** Popups, floating tool windows and detached editor windows currently wearing the glass. */
    internal fun secondaryWindowsSummary(): String = secondaryWindows.describe()

    private fun detach(project: Project) {
        controllers.remove(project)?.dispose()
        if (controllers.isEmpty()) {
            secondaryWindows.apply(null)
            surfaces.restore()
        }
    }

    private fun currentPlan(): GlassPlan? {
        val state = previewState ?: LiquidGlassSettings.getInstance().snapshot()
        val pause = when {
            !state.enabled -> null
            !bridge.isAvailable -> "the native bridge could not be loaded (${bridge.description})"
            !PlatformInternals.isAvailable(PlatformInternals.Feature.TRANSLUCENT_WINDOW) ||
                !PlatformInternals.isAvailable(PlatformInternals.Feature.ISLAND_LAYOUT) ->
                "this Rider version is not supported yet: ${PlatformInternals.report()}"
            accessibility.reduceTransparency -> "Reduce transparency is on in System Settings > Accessibility > Display"
            presentationMode -> "Presentation Mode keeps Rider opaque for projectors and screen sharing"
            else -> ThemeSupport.unsupportedReason()
        }
        status = when {
            !state.enabled -> GlassStatus.Disabled
            pause != null -> GlassStatus.Paused(pause)
            else -> GlassStatus.Active
        }
        if (status != GlassStatus.Active) return null
        val glassControls = state.glassControls && PlatformInternals.isAvailable(PlatformInternals.Feature.GLASS_BUTTONS)
        val environment = GlassEnvironment(
            ideIsDark = !JBColor.isBright(),
            glassAvailable = bridge.glassAvailable,
            accent = SwingUiColorTable.themeColor(ACCENT_KEY) ?: GlassPlanner.DEFAULT_ACCENT,
            accessibility = accessibility,
        )
        return GlassPlanner.plan(state.copy(glassControls = glassControls), environment)
    }

    override fun dispose() {
        pendingFrames.forEach { (frame, listener) -> frame.removeWindowListener(listener) }
        pendingFrames.clear()
        secondaryWindows.dispose()
        controllers.values.forEach(GlassWindowController::dispose)
        controllers.clear()
        surfaces.restore()
        startupGuard.clear()
        // Every native view is gone: the library can be unloaded with the plugin.
        if (bridgeLoader.isInitialized()) bridge.close()
    }

    companion object {
        /** Theme accent of selected stripe buttons; read from the theme layer, below our own overrides. */
        private const val ACCENT_KEY = "ToolWindow.Button.selectedBackground"

        private val LOG = logger<GlassWindowRegistry>()

        fun getInstance(): GlassWindowRegistry = service()

        /** The plugin's own class loader knows its descriptor, hence the installation directory. */
        private fun pluginRoot() = (GlassWindowRegistry::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor?.pluginPath

        private fun onEdt(action: () -> Unit) {
            if (EDT.isCurrentThreadEdt()) action()
            else ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
        }
    }
}
