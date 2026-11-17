package com.engineerclient.render

import java.util.function.BooleanSupplier

/** BadOptimizations only redraws the lightmap when the sky changes, so Fullbright turning on or off has to ask for it. */
class FullbrightLightmapHook : BooleanSupplier {
    override fun getAsBoolean(): Boolean {
        val on = Visibility.fullbright()
        if (on == last) return false
        last = on
        return true
    }

    private companion object {
        var last = false
    }
}
