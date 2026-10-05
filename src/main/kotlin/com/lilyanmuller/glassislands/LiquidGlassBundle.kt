package com.lilyanmuller.glassislands

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.LiquidGlassBundle"

/** Every user-visible text of the plugin. */
internal object LiquidGlassBundle {
    private val bundle = DynamicBundle(LiquidGlassBundle::class.java, BUNDLE)

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = bundle.getMessage(key, *params)
}
