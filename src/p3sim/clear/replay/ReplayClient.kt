package com.engineerclient.p3sim.clear.replay

import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.SimNet
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3

/**
 * The game's side of a replay: while one plays, your mouse, keys and clicks do nothing in the world and your game sends no moves
 * or clicks of its own (AdsReplayClientMixin); your player is put where the recording's was each tick, looking where they looked
 * (eased between ticks, frame by frame), crouching, sprinting, holding and swinging as they did. Chat, commands and the menus still work,
 * so `/ads run replay stop` gives you your player back. Client thread.
 */
object ReplayClient {
    @JvmField @Volatile var active = false

    private var px = Double.NaN; private var py = 0.0; private var pz = 0.0
    private var yaw = 0f; private var pitch = 0f
    private var fromYaw = 0f; private var fromPitch = 0f
    private var slot = -1
    private var flags = 0
    private var fresh = false

    fun init() {
        SimNet.onClient(ReplaySession.NET) { data -> handle(data) }
        ClientTickEvents.END_CLIENT_TICK.register { if (active) tick() }

    }

    private fun handle(data: String) {
        val v = data.split(' ')
        when (v[0]) {
            "start" -> { active = true; px = Double.NaN }
            "stop" -> stop()
            "p" -> {
                px = v[1].toDoubleOrNull() ?: return; py = v[2].toDouble(); pz = v[3].toDouble()
                yaw = v[4].toFloat(); pitch = v[5].toFloat(); slot = v[6].toInt(); flags = v[7].toInt()
                fresh = true
            }
            "inv" -> mc.execute {
                val p = mc.player ?: return@execute
                if (v[1] == "1") { if (mc.gui.screen() == null) mc.gui.setScreen(InventoryScreen(p)) }
                else if (mc.gui.screen() is InventoryScreen) mc.gui.setScreen(null)
            }
        }
    }

    fun stop() { active = false }

    /** A system chat line as it arrived (see AdsReplayChatMixin), for the report. */
    @JvmStatic fun chat(text: String) { ReplayModule.session?.diff?.adsChat(text) }

    /** End of the client tick: the recorded pose of this tick. */
    private fun tick() {
        val p = mc.player ?: return
        if (px.isNaN()) return
        if (!fresh) return
        fresh = false
        fromYaw = p.yRot; fromPitch = p.xRot
        p.setPos(px, py, pz)
        p.deltaMovement = Vec3.ZERO
        if (slot in 0..8 && p.inventory.selectedSlot != slot) p.inventory.setSelectedSlot(slot)
        val sprint = flags and 1 != 0
        if (p.isSprinting != sprint) p.isSprinting = sprint
        if (flags and 8 != 0) p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.attackAnimation, true)
    }

    /** Every frame (instead of the mouse): the look eased from the last tick's to this one's by the frame's place in the tick. */
    @JvmStatic
    fun look() {
        val p = mc.player ?: return
        if (px.isNaN()) return
        val t = mc.deltaTracker.getGameTimeDeltaPartialTick(true).coerceIn(0f, 1f)
        val y = fromYaw + wrap(yaw - fromYaw) * t
        val x = fromPitch + (pitch - fromPitch) * t
        p.yRot = y; p.xRot = x
        p.yHeadRot = y
    }

    private fun wrap(d: Float): Float { var a = d % 360f; if (a >= 180f) a -= 360f; if (a < -180f) a += 360f; return a }

    /** Your crouch for the input (the camera height and the pose), the rest of the keys off. */
    @JvmStatic fun crouch(): Boolean = flags and 2 != 0

    /** What your game must not send while a replay drives the player: the server gets the recording's instead. */
    @JvmStatic
    fun blocks(pk: Packet<*>): Boolean = active && (pk is ServerboundMovePlayerPacket || pk is ServerboundPlayerInputPacket || pk is ServerboundPlayerCommandPacket ||
        pk is ServerboundUseItemPacket || pk is ServerboundUseItemOnPacket || pk is ServerboundAttackPacket || pk is ServerboundInteractPacket ||
        pk is ServerboundPlayerActionPacket || pk is ServerboundPunchPacket || pk is ServerboundSetCarriedItemPacket ||
        pk is ServerboundContainerClickPacket || pk is ServerboundContainerClosePacket || pk is ServerboundContainerButtonClickPacket)
}
