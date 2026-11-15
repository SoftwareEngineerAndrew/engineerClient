package com.engineerclient.devices

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.events.BlockInteractEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.utils.playSoundSettings
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB

/** Simon Says: boxes the buttons to press from the solver's answer ([com.engineerclient.practice.OdinSimonSays]), blocks wrong ones, announces rounds. */
object DevSimonSays {
    private val startButton = BlockPos(110, 121, 91)
    /** The lamps of the round being put in, in order (the solver's answer), and how many are pressed. */
    val clickInOrder = ArrayList<BlockPos>()
    var clickNeeded = 0
    private var startClickCounter = 0

    fun install() {
        on<LevelEvent.Load> { clickInOrder.clear(); clickNeeded = 0; startClickCounter = 0 }
        on<MessageEvent.Chat> { if (message == "[BOSS] Goldor: Who dares trespass into my domain?") startClickCounter = 0 }

        on<BlockInteractEvent> {
            if (!Devices.simonSaysOn || DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            val sneaking = mc.player?.isShiftKeyDown == true
            if (pos == startButton && Devices.blockWrongStart) {
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
