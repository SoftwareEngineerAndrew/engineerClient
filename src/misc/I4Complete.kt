package com.engineerclient.misc

import com.engineerclient.EngineerClient.mc
import com.adm.events.LevelEvent
import com.adm.events.TickEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.utils.alert
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB

/**
 * A title the moment your 4th device (i4, the S4 target) is done - only while you stand on its plate, (63, 127, 35).
 * Part of Random Stuff, always on while it is. Works on Hypixel and in P3 Sim. Two signals; the first one wins:
 *
 *  - Chat: "<you> completed a device! (n/7)". Hypixel sends it in the tick of the last hit.
 *  - Device tag: the device's stand turning "Active" (Hypixel renames its stands on a 20-tick grid, so up to a
 *    second later).
 *
 * No guess from the board going dark: the next emerald takes 1-10 server ticks after a count, with one tick of slack
 * against when the client sees each tick, so that deadline fired the title before the device was done; the chat
 * comes in the tick of the last hit anyway.
 *
 * And the same for Simon Says: a title on your own device line while you're within 5 blocks of it.
 */
object I4Complete {
    private const val TITLE = "§a§li4 Complete!"
    private const val SS_TITLE = "§a§lSS Complete!"

    private val PLATE = BlockPos(63, 127, 35)
    private val STAND_BOX = AABB(62.5, 124.5, 33.5, 64.5, 127.5, 35.5)
    private val SS_SPOT = net.minecraft.world.phys.Vec3(110.0, 121.0, 93.5)
    private val CONTROL_CODES = Regex("§.")
    private val DEVICE_LINE = Regex("^(.{1,16}) completed a device! \\(\\d/\\d\\)$")

    private var tagWasActive: Boolean? = null
    private var shown = false
    private var ssShown = false

    fun register() = SubscribeWhile.add(this, ::reset) { RandomStuff.enabled }

    private fun reset() { tagWasActive = null; shown = false; ssShown = false }

    private fun inP3() = RandomStuff.enabled && DungeonUtils.getF7Phase() == M7Phases.P3

    /** You stand on the plate: your box over its block, feet on it. */
    internal fun onPlate(): Boolean {
        val b = mc.player?.boundingBox ?: return false
        return b.maxX > PLATE.x && b.minX < PLATE.x + 1 && b.maxZ > PLATE.z && b.minZ < PLATE.z + 1 && b.minY >= PLATE.y - 0.01 && b.minY < PLATE.y + 0.25
    }

    private fun done() {
        if (shown || !RandomStuff.enabled || !onPlate()) return
        shown = true
        mc.execute { alert(TITLE, true) }
    }

    /** Your own device line while you're at Simon Says (S1). */
    private fun ssDone() {
        if (ssShown || !inP3()) return
        val p = mc.player ?: return
        if (p.position().distanceToSqr(SS_SPOT) > 25.0) return
        ssShown = true
        alert(SS_TITLE, true)
    }

    init {
        on<LevelEvent.Load> { reset() }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = it.chatText
            if (msg == "[BOSS] Goldor: Who dares trespass into my domain?") { reset(); return@onReceive }
            if (!msg.contains(" completed a device! (")) return@onReceive
            val m = DEVICE_LINE.matchEntire(msg) ?: return@onReceive
            if (m.groupValues[1] != mc.player?.gameProfile?.name) return@onReceive
            done()
            mc.execute { ssDone() }
        }

        on<TickEvent.End> {
            if (!inP3()) { tagWasActive = null; return@on }
            val level = mc.level ?: return@on
            val active = level.getEntitiesOfClass(ArmorStand::class.java, STAND_BOX) { it.hasCustomName() }
                .any { it.customName?.string?.replace(CONTROL_CODES, "") == "Active" }
            val was = tagWasActive
            tagWasActive = active
            // Only the change counts: stepping onto the plate of a device done earlier shows nothing.
            if (active && was == false) done()
        }
    }
}
