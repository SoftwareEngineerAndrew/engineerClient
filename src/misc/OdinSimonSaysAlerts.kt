package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.engineerclient.devices.Devices
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.playSoundAtPlayer
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.setTitle
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock

object OdinSimonSaysAlerts {

    private const val STAGES = 5
    private const val BREAK_TICKS = 12
    private val lamps = (120..123).flatMap { y -> (92..95).map { z -> BlockPos(111, y, z) } }
    private val buttons = lamps.map { it.west() }
    private val deviceRegex = Regex("""^\w{1,16} (?:activated|completed) a device! \(\d/\d\)$""")

    private val shown = HashSet<BlockPos>()
    private var roundLength = 0
    private var pendingStage = 0
    private var goneTicks = 0
    private var running = false
    private var wasBroken = false

    private fun active() = Devices.simonSaysOn && DungeonUtils.getF7Phase() == M7Phases.P3

    private fun reset() {
        shown.clear(); roundLength = 0; pendingStage = 0; goneTicks = 0; running = false; wasBroken = false
    }

    private fun stageDone(stage: Int) {
        if (!Devices.stageMessages) return
        val text = "SS $stage/$STAGES"
        if (Devices.stageTarget == Devices.Target.PARTY) sendCommand("pc $text") else modMessage(text)
    }

    private fun tick() {
        if (!active()) return
        val level = EngineerClient.mc.level ?: return
        val lit = lamps.filter { level.getBlockState(it).block == Blocks.SEA_LANTERN }
        if (lit.isNotEmpty()) {
            if (pendingStage > 0) stageDone(pendingStage)
            pendingStage = 0
            goneTicks = 0
            if (!running) {
                if (wasBroken && Devices.restartMessage) sendCommand("pc SS Started Again!")
                wasBroken = false
                running = true
            }
            shown.addAll(lit)
            return
        }
        if (!running) return
        if (buttons.any { level.getBlockState(it).block is ButtonBlock }) {
            if (shown.isNotEmpty()) roundLength = shown.size.coerceAtMost(STAGES)
            shown.clear()
            goneTicks = 0
            return
        }
        if (!buttons.all { level.getBlockState(it).isAir }) return
        if (roundLength > 0) pendingStage = roundLength
        roundLength = 0
        if (++goneTicks < BREAK_TICKS) return
        running = false
        wasBroken = true
        pendingStage = 0
        shown.clear()
        if (!Devices.breakAlert) return
        if (Devices.breakParty) sendCommand("pc SS Broke!")
        if (Devices.breakTitle) setTitle("§c§l§nSS BROKE!")
        if (Devices.breakSound) playSoundAtPlayer(SoundEvents.ANVIL_LAND, 5f, 0f)
    }

    // The device's completion ends its last stage, with no next show to confirm it.
    private fun deviceDone() {
        if (!active() || !running) return
        val last = maxOf(pendingStage, roundLength)
        if (last > 0) stageDone(last)
        reset()
    }

    fun install() {
        on<LevelEvent.Load> { EngineerClient.mc.execute { reset() } }
        on<TickEvent.Server> { EngineerClient.mc.execute { EngineerClient.safely("ss alerts") { tick() } } }
        on<MessageEvent.Chat> {
            if (deviceRegex.matches(message)) EngineerClient.mc.execute { EngineerClient.safely("ss alerts device") { deviceDone() } }
        }
        EventBus.subscribe(this)
    }
}
