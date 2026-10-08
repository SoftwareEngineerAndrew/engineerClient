package com.engineerclient.misc

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.utils.alert
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

/**
 * A title the moment your 4th device (i4, the S4 target) is done - only while you stand on its plate, (63, 127, 35).
 * Part of Random Stuff, always on while it is. Works on Hypixel and in P3 Sim. Three signals; the first one wins (tools/p3sim/research/devices.md §1):
 *
 *  - Chat: "<you> completed a device! (n/7)". Hypixel sends it in the tick of the last hit.
 *  - Device tag: the device's stand turning "Active" (Hypixel renames its stands on a 20-tick grid, so up to a
 *    second later).
 *  - Emeralds stop: the board lights its next target on a 10-tick grid; when a grid tick after a hit passes with
 *    nothing lit, there was no next target. Needs no chat, but a target hit before it ever lit (the board's hidden-
 *    target quirk) looks the same as the last one, so it fires a little later than chat at best.
 */
object I4Complete {
    private const val TITLE = "§a§li4 Complete!"

    private val PLATE = BlockPos(63, 127, 35)
    private val TARGETS: Set<BlockPos> = (0 until 9).map { BlockPos(64 + (it % 3) * 2, 126 + (it / 3) * 2, 50) }.toSet()
    private val STAND_BOX = AABB(62.5, 124.5, 33.5, 64.5, 127.5, 35.5)
    private val CONTROL_CODES = Regex("§.")
    private val DEVICE_LINE = Regex("^(.{1,16}) completed a device! \\(\\d/\\d\\)$")

    private var serverTicks = 0
    /** The board's 10-tick grid (the tick an emerald last lit, mod 10), or -1 before one has lit. */
    private var grid = -1
    /** The cell showing emerald, or null. (A hit on a grid tick sends the new target's emerald before the old one's blue, in one packet.) */
    private var lit: BlockPos? = null
    /** The tick of the last hit (an emerald going blue), or -1. */
    private var hitAt = -1
    private var tagWasActive: Boolean? = null
    private var shown = false

    /** Its handlers are on Odin's bus from here on; they do nothing while Random Stuff is off. */
    fun register() = EventBus.subscribe(this)

    private fun reset() { grid = -1; lit = null; hitAt = -1; tagWasActive = null; shown = false }

    /** You stand on the plate: your box over its block, feet on it. */
    private fun onPlate(): Boolean {
        val b = mc.player?.boundingBox ?: return false
        return b.maxX > PLATE.x && b.minX < PLATE.x + 1 && b.maxZ > PLATE.z && b.minZ < PLATE.z + 1 && b.minY >= PLATE.y - 0.01 && b.minY < PLATE.y + 0.25
    }

    private fun done(why: String) {
        if (shown || !RandomStuff.enabled || !onPlate()) return
        shown = true
        mc.execute { alert(TITLE, true) }
    }

    init {
        on<LevelEvent.Load> { reset() }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CONTROL_CODES, "")
            if (msg == "[BOSS] Goldor: Who dares trespass into my domain?") { reset(); return@onReceive }
            val m = DEVICE_LINE.matchEntire(msg) ?: return@onReceive
            if (m.groupValues[1] == mc.player?.gameProfile?.name) done("chat")
        }

        on<BlockUpdateEvent> {
            if (pos !in TARGETS) return@on
            if (updated.`is`(Blocks.EMERALD_BLOCK)) {
                lit = pos.immutable(); grid = serverTicks.mod(10); shown = false
            } else if (old.`is`(Blocks.EMERALD_BLOCK)) {
                if (pos == lit) lit = null
                hitAt = serverTicks
            }
        }

        on<TickEvent.Server> {
            serverTicks++
            // The first grid tick after the hit (an emerald can light on the hit's own tick), and one more for the
            // block update to arrive behind the tick's other packets.
            if (hitAt >= 0 && lit == null && grid >= 0) {
                val next = hitAt + 1 + (grid - (hitAt + 1)).mod(10)
                if (serverTicks >= next + 1) { hitAt = -1; done("emeralds") }
            }
        }

        on<TickEvent.End> {
            val level = mc.level ?: return@on
            val active = level.getEntitiesOfClass(ArmorStand::class.java, STAND_BOX) { it.hasCustomName() }
                .any { it.customName?.string?.replace(CONTROL_CODES, "") == "Active" }
            val was = tagWasActive
            tagWasActive = active
            // Only the change counts: stepping onto the plate of a device done earlier shows nothing.
            if (active && was == false) done("tag")
        }
    }
}
