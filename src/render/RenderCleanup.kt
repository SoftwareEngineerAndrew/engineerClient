package com.engineerclient.render

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.SmokeParticle
import net.minecraft.client.particle.TerrainParticle

object RenderCleanup : Module(
    name = "Render Cleanup",
    category = Category.RENDER,
    description = "Hides particles and entities that clutter the screen.",
    key = null,
) {
    private val hideBlockBreak by BooleanSetting("Hide Block Break Particles", false, desc = "No block crack and break particles.")
    private val hideSmoke by BooleanSetting("Hide Smoke Particles", false, desc = "No smoke particles.")
    private val hideGroundedArrows by BooleanSetting("Hide Grounded Arrows", false, desc = "Arrows stuck in blocks are not drawn.")

    fun hidesParticle(particle: Particle): Boolean = enabled &&
        (hideBlockBreak && particle is TerrainParticle || hideSmoke && particle is SmokeParticle)

    fun hidesGroundedArrows() = enabled && hideGroundedArrows
}
