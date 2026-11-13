package com.engineerclient.render

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.Entity

object CameraTweaks : Module(
    name = "Camera Tweaks",
    category = Category.RENDER,
    description = "Small camera and overlay fixes.",
    key = null,
) {
    private val doubleSneakFix by BooleanSetting("Double Sneak Fix", false, desc = "The server can't change your own sneak pose.")
    private val ridingInputFix by BooleanSetting("Riding Input Delay Fix", false, desc = "No mouse input delay while riding (MC-206540).")
    private val hidePortalOverlay by BooleanSetting("Hide Portal Overlay", false, desc = "No purple overlay inside a nether portal.")

    // Entity data slot 6 is the pose.
    private const val POSE_ID = 6

    init {
        onReceive<ClientboundSetEntityDataPacket> {
            if (!doubleSneakFix || id != mc.player?.id) return@onReceive
            runCatching { packedItems.removeIf { it.id == POSE_ID } }
        }
    }

    fun hidePortalOverlay() = enabled && hidePortalOverlay

    fun onPassengerTurned(passenger: Entity, vehicleYaw: Float) {
        if (!enabled || !ridingInputFix || passenger !== mc.player) return
        passenger.setYBodyRot(vehicleYaw)
        passenger.setYHeadRot(passenger.yRot)
    }
}
