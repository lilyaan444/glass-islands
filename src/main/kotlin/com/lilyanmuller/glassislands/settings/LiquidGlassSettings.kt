package com.lilyanmuller.glassislands.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic

@Service(Service.Level.APP)
@State(name = "GlassIslands", storages = [Storage("glassIslands.xml")])
class LiquidGlassSettings : PersistentStateComponent<LiquidGlassState> {

    private var current = LiquidGlassState()

    override fun getState(): LiquidGlassState = current

    override fun loadState(state: LiquidGlassState) {
        current = state.normalized()
    }

    fun snapshot(): LiquidGlassState = current.copy()

    fun update(newState: LiquidGlassState) {
        current = newState.normalized()
        ApplicationManager.getApplication()?.messageBus?.syncPublisher(LiquidGlassSettingsListener.TOPIC)
            ?.settingsChanged(snapshot())
    }

    /** Stores internal bookkeeping without notifying listeners (nothing visible changes). */
    fun updateSilently(change: (LiquidGlassState) -> LiquidGlassState) {
        current = change(current.copy()).normalized()
    }

    companion object {
        fun getInstance(): LiquidGlassSettings = service()
    }
}

fun interface LiquidGlassSettingsListener {
    fun settingsChanged(state: LiquidGlassState)

    companion object {
        @Topic.AppLevel
        val TOPIC = Topic(LiquidGlassSettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
