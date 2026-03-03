package com.intellij.debugmap

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.DebugMapBundle"

internal object DebugMapBundle : DynamicBundle(DebugMapBundle::class.java, BUNDLE) {
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): @Nls String {
    return getMessage(key, *params)
  }
}
