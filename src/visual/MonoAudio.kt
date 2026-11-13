package com.engineerclient.visual

import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.world.phys.Vec3
import org.lwjgl.openal.AL10

object MonoAudio : Module(
    name = "Mono Audio",
    category = Category.SKYBLOCK,
    description = "Plays all game audio through a single centred channel.",
    key = null,
) {
    interface MonoChannel {
        fun ecRefreshMonoPosition()
    }

    @JvmStatic
    fun active(): Boolean = enabled

    @JvmStatic
    fun distanceToListener(pos: Vec3): Double = pos.distanceTo(mc.soundManager.listenerTransform.position())

    @JvmStatic
    fun applyCenteredPosition(source: Int, distance: Double) {
        AL10.alSourcefv(source, AL10.AL_POSITION, floatArrayOf(0f, 0f, -distance.toFloat()))
    }
}
