package com.lilyanmuller.glassislands.lifecycle

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.lilyanmuller.glassislands.LiquidGlassBundle
import com.lilyanmuller.glassislands.settings.LiquidGlassSettings
import com.lilyanmuller.glassislands.ui.LiquidGlassConfigurable

/** The few balloons the plugin ever shows. */
internal object GlassNotifications {
    private const val GROUP_ID = "Glass Islands"
    private const val SETUP_GROUP_ID = "Glass Islands Setup"

    /** The flicker-free presentation option was written: it applies at the next start of Rider. */
    fun restartForFlickerFix() {
        // Sticky: the fix only takes effect after a restart, which the user should not miss.
        NotificationGroupManager.getInstance().getNotificationGroup(SETUP_GROUP_ID)
            .createNotification(
                LiquidGlassBundle.message("notification.flicker.title"),
                LiquidGlassBundle.message("notification.flicker.content"),
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring(LiquidGlassBundle.message("notification.flicker.restart")) {
                PresentationSync.restart()
            })
            .addAction(NotificationAction.createSimpleExpiring(LiquidGlassBundle.message("notification.flicker.later")) {})
            .notify(null)
    }

    /** Safe mode: the previous session stopped while the glass was being applied. */
    fun turnedOffAfterFailure() {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(
                LiquidGlassBundle.message("notification.safe.mode.title"),
                LiquidGlassBundle.message("notification.safe.mode.content"),
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring(LiquidGlassBundle.message("notification.safe.mode.enable")) {
                val settings = LiquidGlassSettings.getInstance()
                settings.update(settings.snapshot().copy(enabled = true))
            })
            .addAction(NotificationAction.createSimple(LiquidGlassBundle.message("notification.open.settings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(null, LiquidGlassConfigurable::class.java)
            })
            .notify(null)
    }
}
