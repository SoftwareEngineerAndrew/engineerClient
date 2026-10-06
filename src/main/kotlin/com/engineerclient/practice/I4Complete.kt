package com.engineerclient.practice

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.alert
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

/**
 * A title the moment your 4th device (i4, the S4 target) is done - only while you stand on its plate, (63, 127, 35).
 * Works on Hypixel and in P3 Sim. Three signals, each optional; the first one wins (tools/p3sim/research/devices.md §1):
 *
 *  - Chat: "<you> completed a device! (n/7)". Hypixel sends it in the tick of the last hit.
 *  - Device tag: the device's stand turning "Active" (Hypixel renames its stands on a 20-tick grid, so up to a
 *    second later).
 *  - Emeralds stop: the board lights its next target on a 10-tick grid; when a grid tick after a hit passes with
 *    nothing lit, there was no next target. Needs no chat, but a target hit before it ever lit (the board's hidden-
 *    target quirk) looks the same as the last one, so it fires a little later than chat at best.
 */
object I4Complete : Module(
    name = "i4 Complete Title",
    category = Category.custom("Engineer Client", 1030, 10),
    description = "A title when your 4th device (i4) is done, while you're on its plate: from the chat line, the device tag turning Active, or emeralds no longer lighting.",
) {
    private val title by StringSetting("Title", "§a§li4 Complete!", 40, desc = "The title shown (§ colour codes work).", placeholder = "")
    private val sound by BooleanSetting("Sound", true, desc = "Odin's alert sound with the title.")
    private val byChat by BooleanSetting("From Chat", true, desc = "\"<you> completed a device!\": the fastest, in the tick of the last hit.")
    private val byTag by BooleanSetting("From Device Tag", true, desc = "The device's tag turning Active (Hypixel renames it on a 20-tick grid: up to a second late).")
    private val byEmeralds by BooleanSetting("From Emeralds Stopping", true, desc = "No new emerald on the board's next 10-tick grid tick after a hit.")
    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    /** On by default, once (turning it off sticks). */
    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

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

    private fun reset() { grid = -1; lit = null; hitAt = -1; tagWasActive = null; shown = false }

    /** You stand on the plate: your box over its block, feet on it. */
    private fun onPlate(): Boolean {
        val b = mc.player?.boundingBox ?: return false
        return b.maxX > PLATE.x && b.minX < PLATE.x + 1 && b.maxZ > PLATE.z && b.minZ < PLATE.z + 1 && b.minY >= PLATE.y - 0.01 && b.minY < PLATE.y + 0.25
    }

    private fun done(why: String) {
        if (shown || !onPlate()) return
        shown = true
        mc.execute { alert(title, sound) }
        com.engineerclient.recorder.EcRec.line("ec.i4") { o -> o.str("done", why).num("serverTicks", serverTicks) }
    }

    init {
        on<LevelEvent.Load> { reset() }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CONTROL_CODES, "")
            if (msg == "[BOSS] Goldor: Who dares trespass into my domain?") { reset(); return@onReceive }
            if (!byChat) return@onReceive
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
            if (byEmeralds && hitAt >= 0 && lit == null && grid >= 0) {
                val next = hitAt + 1 + (grid - (hitAt + 1)).mod(10)
                if (serverTicks >= next + 1) { hitAt = -1; done("emeralds") }
            }
        }

        on<TickEvent.End> {
            if (!byTag) return@on
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
