// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.devices

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.settings.BooleanSetting
import com.engineerclient.ads.settings.NumberSetting
import com.engineerclient.ads.settings.SelectorSetting
import com.engineerclient.ads.settings.Settings
import com.engineerclient.ads.settings.withDependency
import com.engineerclient.devices.DeviceRender.BoxStyle
import com.engineerclient.devices.DeviceSolvers.DeviceColor
import com.engineerclient.practice.SimonSaysFeed
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB

/**
 * Simon Says (F7's first device) in ADS's worlds: Odin's Simon Says (its boxes, colours, style and Block Wrong
 * Clicks options), with the answer worked out by ADS's own solver ([SimonSaysFeed], the one OdinSimonSays gives
 * Odin on Hypixel) instead of Odin's. SS Practice's device draws its boxes with these settings too.
 */
object AdsSimonSays : Settings("Simon Says", "devices-simon-says.json") {
    private val enabledS = +BooleanSetting("Solver", true, desc = "Shows the order to press Simon Says' buttons in, in the P3 Sim, Rustclear worlds and on SS Practice's device.")
    private val firstS = +SelectorSetting("First Color", DeviceColor.GREEN, desc = "The button to press next.")
    private val secondS = +SelectorSetting("Second Color", DeviceColor.GOLD, desc = "The button after it.")
    private val thirdS = +SelectorSetting("Third Color", DeviceColor.RED, desc = "The buttons after those.")
    private val opacityS = +NumberSetting("Opacity", 50, 10..100, 5, unit = "%", desc = "How solid the boxes are.")
    private val styleS = +SelectorSetting("Style", BoxStyle.FILLED_OUTLINE, desc = "The style of the boxes.")
    private val blockWrongS = +BooleanSetting("Block Wrong Clicks", false, desc = "Wrong buttons can't be pressed. Sneak to press one anyway.")
    private val blockStartS = +BooleanSetting("Block Wrong on Start", false, desc = "Stops start button presses after Max Start Clicks before the first round's buttons come up. Sneak to press it anyway.")
    private val maxStartS = +NumberSetting("Max Start Clicks", 4, 1..10, 1, desc = "Start button presses allowed before the first round.").withDependency { blockStartS.value }

    val enabled: Boolean get() = enabledS.value
    val style: BoxStyle get() = styleS.value

    /** The box colour of answer [i] when [next] is the one to press. */
    fun colorFor(i: Int, next: Int): Int {
        val c = when (i) { next -> firstS.value; next + 1 -> secondS.value; else -> thirdS.value }
        return c.argb(opacityS.value / 100.0)
    }

    private val START = BlockPos(110, 121, 91)
    private val feed = SimonSaysFeed()
    /** Start presses since the device was last reset, and whether a round's buttons came up since (Odin's first phase). */
    private var startClicks = 0
    private var firstPhase = true

    /** The answer (each button's lamp, in order) and how many of it are pressed. */
    val answer: List<BlockPos> get() = feed.solver.answer
    val next: Int get() = feed.solver.next

    fun reset() { feed.reset(); startClicks = 0; firstPhase = true }

    fun arrived(packet: Packet<*>) {
        if (!enabled) return
        val at = DeviceSolvers.serverTick()
        val lamps = feed.arrived(packet, at) ?: return
        val handle = Runnable { EngineerClient.safely("ads ss lamps") { feed.lamps(lamps, at) } }
        if (mc.isSameThread) handle.run() else mc.execute(handle)
    }

    fun tick() {
        if (enabled) feed.serverTick(DeviceSolvers.serverTick() - 1)
    }

    fun blockChanged(pos: BlockPos, old: BlockState, updated: BlockState) {
        if (!enabled) return
        val level = mc.level ?: return
        if (!feed.blockChanged(level, pos, old, updated, DeviceSolvers.serverTick())) return
        if (updated.block is ButtonBlock && old.block !is ButtonBlock) firstPhase = false
    }

    fun chat(text: String) {
        if (text == "[BOSS] Goldor: Who dares trespass into my domain?") { startClicks = 0; firstPhase = true }
    }

    /**
     * Odin's Block Wrong Clicks and Block Wrong on Start, for a right click on [pos]; true blocks it. [count]: the
     * click is happening (a start press counts toward Max Start Clicks), else only asked about (SS Practice's sounds).
     */
    @JvmOverloads
    fun blocksClick(pos: BlockPos, count: Boolean = true): Boolean {
        if (!enabled) return false
        val sneaking = mc.player?.isShiftKeyDown == true
        if (pos == START && firstPhase && blockStartS.value) {
            val blocked = startClicks >= maxStartS.value && !sneaking
            if (count) startClicks++
            return blocked
        }
        if (pos.x != 110 || pos.y !in 120..123 || pos.z !in 92..95) return false
        return blockWrongS.value && !sneaking && pos.east() != answer.getOrNull(next)
    }

    fun draw(ctx: LevelRenderContext) {
        if (!enabled) return
        val order = answer
        val next = next
        for (i in next until order.size) {
            val p = order[i]
            DeviceRender.box(ctx, AABB(p.x + 0.05, p.y + 0.37, p.z + 0.3, p.x - 0.15, p.y + 0.63, p.z + 0.7), colorFor(i, next), style)
        }
    }
}
