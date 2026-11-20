package com.engineerclient.devices

import com.engineerclient.EngineerClient.mc
import com.adm.events.BlockInteractEvent
import com.adm.events.BlockUpdateEvent
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.utils.playSoundSettings
import com.adm.utils.render.drawStyledBox
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.AABB

/** Simon Says: boxes the buttons to press from the solver's answer ([com.engineerclient.practice.SimonSaysTracker]), blocks wrong ones, announces rounds. */
object DevSimonSays {
    private val startButton = BlockPos(110, 121, 91)
    /** The lamps of the round being put in, in order (the solver's answer), and how many are pressed. */
    val clickInOrder = ArrayList<BlockPos>()
    var clickNeeded = 0
    private var startClickCounter = 0
    /** Before the device's buttons first come up: the start button may be spammed only then; a fail's restart arms it again. */
    private var firstPhase = true
    /** The device is waiting for a start press: not started yet, or failed. */
    private var stopped = true

    /** The buttons went with no new round showing: a fail (or the device done). */
    fun deviceStopped() { stopped = true }

    fun install() {
        on<LevelEvent.Load> { clickInOrder.clear(); clickNeeded = 0; startClickCounter = 0; firstPhase = true; stopped = true }
        on<MessageEvent.Chat> { if (message == "[BOSS] Goldor: Who dares trespass into my domain?") { startClickCounter = 0; firstPhase = true; stopped = true } }
        on<BlockUpdateEvent> {
            if (!Devices.simonSaysOn || !Devices.blockWrongStart) return@on
            if (pos == startButton) {
                // Only a press that starts a new attempt arms it; a start press on a running device is itself a fail.
                if (stopped && updated.block is ButtonBlock && updated.getValue(BlockStateProperties.POWERED)) { firstPhase = true; stopped = false }
            } else if (pos.x == 110 && pos.y in 120..123 && pos.z in 92..95 && updated.block is ButtonBlock && old.block !is ButtonBlock) {
                stopped = false
                if (firstPhase) { firstPhase = false; startClickCounter = 0 }
            }
        }

        on<BlockInteractEvent> {
            if (!Devices.simonSaysOn || DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            val sneaking = mc.player?.isShiftKeyDown == true
            if (pos == startButton && Devices.blockWrongStart && firstPhase) {
                if (startClickCounter++ >= Devices.maxStartClicks && !sneaking) {
                    if (Devices.customClickSounds) playSoundSettings(Devices.wrongSound())
                    cancel()
                    return@on
                } else if (Devices.customClickSounds) playSoundSettings(Devices.correctSound())
            }
            if (pos.x == 110 && pos.y in 120..123 && pos.z in 92..95) {
                if (Devices.announceProgress && clickInOrder.isNotEmpty() && pos.east() == clickInOrder.last()) sendCommand("pc SS ${clickInOrder.size}/4")
                if (Devices.ssBlockWrong && !sneaking && pos.east() != clickInOrder.getOrNull(clickNeeded)) {
                    if (Devices.customClickSounds) playSoundSettings(Devices.wrongSound())
                    cancel()
                } else if (Devices.customClickSounds) playSoundSettings(Devices.correctSound())
            }
        }

        on<RenderExtractEvent> {
            if (!Devices.simonSaysOn || DungeonUtils.getF7Phase() != M7Phases.P3 || clickNeeded >= clickInOrder.size) return@on
            for (index in clickNeeded until clickInOrder.size) with(clickInOrder[index]) {
                val color = when (index) {
                    clickNeeded -> Devices.ssFirstColor
                    clickNeeded + 1 -> Devices.ssSecondColor
                    else -> Devices.ssThirdColor
                }
                drawStyledBox(AABB(x + 0.05, y + 0.37, z + 0.3, x - 0.15, y + 0.63, z + 0.7), color, Devices.ssStyle, true)
            }
        }
        EventBus.subscribe(this)
    }
}
