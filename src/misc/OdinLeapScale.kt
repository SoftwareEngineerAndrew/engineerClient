package com.engineerclient.misc

import com.odtheking.odin.features.impl.dungeon.LeapMenu

/** The scale Odin's Leap Menu draws its four boxes at (its private getScale), 1 when it can't be read. */
object OdinLeapScale {
    private val scaleGetter by lazy {
        runCatching { LeapMenu::class.java.getDeclaredMethod("getScale").apply { isAccessible = true } }.getOrNull()
    }

    fun get(): Float =
        runCatching { scaleGetter?.invoke(LeapMenu) as? Float }.getOrNull() ?: 1f
}
