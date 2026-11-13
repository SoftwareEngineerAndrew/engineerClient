package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.ParticleEvent
import com.odtheking.odin.features.impl.render.RenderOptimizer
import net.minecraft.core.particles.ParticleTypes

object OdinHypeExplosion {

    private val hypeOnly = BooleanSetting("Only Hyperion Explosions", false, desc = "Hide Explosion Particles only hides Hyperion's Implosion. Added by ADM.")

    // Implosion is the only explosion Hypixel sends with both override limiter and always show set.
    @JvmStatic
    fun keep(event: ParticleEvent): Boolean =
        hypeOnly.value && !(event.particle == ParticleTypes.EXPLOSION && event.overrideLimiter && event.alwaysShowParticles)

    fun install() {
        RenderOptimizer.registerSetting(hypeOnly)
    }
}
