package com.odtheking.odin.features.impl.render

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Module
import net.minecraft.client.CameraType
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.Entity

object Camera : Module(
    name = "Camera",
    description = "Every camera setting in one place."
) {
    private val noFrontCamera by BooleanSetting("No Front Camera", true, desc = "Skips the front-facing third person view.")
    private val offset by NumberSetting("Offset", 0.0, -1.0..1.0, 0.05, desc = "First person camera, in blocks: + forward, - back.")
    private val instantSneak by BooleanSetting("Instant Sneak", false, desc = "The camera snaps to sneak height instead of sliding.")
    private val noHurtCamera by BooleanSetting("No Hurt Camera", false, desc = "No camera shake when you take damage.")
    private val fixRidingCamera by BooleanSetting("Fix Riding Camera", false, desc = "The camera no longer lags behind while riding (MC-259512).")
    private val ridingInputFix by BooleanSetting("Riding Input Delay Fix", false, desc = "No mouse input delay while riding (MC-206540).")
    private val doubleSneakFix by BooleanSetting("Double Sneak Fix", false, desc = "The server can't change your own sneak pose.")
    private val hidePortalOverlay by BooleanSetting("Hide Portal Overlay", false, desc = "No purple overlay inside a nether portal.")

    // Entity data slot 6 is the pose.
    private const val POSE_ID = 6

    init {
        on<TickEvent.End> {
            if (noFrontCamera && mc.options.cameraType == CameraType.THIRD_PERSON_FRONT)
                mc.options.cameraType = CameraType.FIRST_PERSON
        }

        onReceive<ClientboundSetEntityDataPacket> {
            if (!doubleSneakFix || id != mc.player?.id) return@onReceive
            runCatching { packedItems.removeIf { it.id == POSE_ID } }
        }
    }

    @JvmStatic fun offsetBlocks(): Float = if (enabled) offset.toFloat() else 0f
    @JvmStatic fun instantSneak() = enabled && instantSneak
    @JvmStatic fun noHurtCamera() = enabled && noHurtCamera
    @JvmStatic fun fixRidingCamera(entity: Entity) = enabled && fixRidingCamera && entity === mc.player && entity.isPassenger
    @JvmStatic fun hidePortalOverlay() = enabled && hidePortalOverlay

    @JvmStatic
    fun onPassengerTurned(passenger: Entity, vehicleYaw: Float) {
        if (!enabled || !ridingInputFix || passenger !== mc.player) return
        passenger.setYBodyRot(vehicleYaw)
        passenger.setYHeadRot(passenger.yRot)
    }
}
