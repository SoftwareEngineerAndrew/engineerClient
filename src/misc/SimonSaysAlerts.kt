package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.engineerclient.devices.Devices
import com.engineerclient.practice.SimonSaysSolver
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.utils.modMessage
import com.adm.utils.playSoundAtPlayer
import com.adm.utils.sendCommand
import com.adm.utils.setTitle
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock

object SimonSaysAlerts {

    private const val STAGES = SimonSaysSolver.MAX_ROUNDS
    private const val BREAK_TICKS = 12
    private val lamps = (120..123).flatMap { y -> (92..95).map { z -> BlockPos(111, y, z) } }.toTypedArray()
    private val buttons = Array(lamps.size) { lamps[it].west() }
    private val deviceRegex = Regex("""^\w{1,16} (?:activated|completed) a device! \(\d/\d\)$""")

    private val shown = HashSet<BlockPos>()
    private val runTick = Runnable { EngineerClient.safely("ss alerts") { tick() } }
    private var roundLength = 0
    private var pendingStage = 0
    private var goneTicks = 0
    private var running = false
    private var wasBroken = false

    private fun active() = Devices.simonSaysOn && DungeonUtils.getF7Phase() == M7Phases.P3

    fun reset() {
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
        var lit = false
        for (l in lamps) if (level.getBlockState(l).block == Blocks.SEA_LANTERN) { lit = true; shown.add(l) }
        if (lit) {
            if (pendingStage > 0) stageDone(pendingStage)
            pendingStage = 0
            goneTicks = 0
            if (!running) {
                if (wasBroken && Devices.restartMessage) sendCommand("pc SS Started Again!")
                wasBroken = false
                running = true
            }
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
        on<TickEvent.Server> { if (active()) EngineerClient.mc.execute(runTick) }
        on<MessageEvent.Chat> {
            if (deviceRegex.matches(message)) EngineerClient.mc.execute { EngineerClient.safely("ss alerts device") { deviceDone() } }
        }
    }
}
