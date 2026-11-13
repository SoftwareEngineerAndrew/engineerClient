package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.boss.SimonSays
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

    private enum class Target(private val label: String) {
        PARTY("Party"), SELF("Self");
        override fun toString() = label
    }

    private val stageMessages = BooleanSetting("Stage Messages", false, desc = "Sends a message each time a stage is completed. Added by ADM.")
    private val stageTarget = SelectorSetting("Stage Message To", Target.PARTY, desc = "Party chat or only you.").withDependency { stageMessages.value }
    private val breakAlert = BooleanSetting("Break Alert", false, desc = "Alerts when the device breaks. Added by ADM.")
    private val breakParty = BooleanSetting("Party Message", true, desc = "Sends SS Broke! in party chat.").withDependency { breakAlert.value }
    private val breakTitle = BooleanSetting("Title", true, desc = "Shows SS BROKE! on screen.").withDependency { breakAlert.value }
    private val breakSound = BooleanSetting("Sound", true, desc = "Plays an anvil sound.").withDependency { breakAlert.value }
    private val restartMessage = BooleanSetting("Restart Message", false, desc = "Sends SS Started Again! in party chat when it restarts after a break. Added by ADM.")

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

    private fun active() = SimonSays.enabled && DungeonUtils.getF7Phase() == M7Phases.P3

    private fun reset() {
        shown.clear(); roundLength = 0; pendingStage = 0; goneTicks = 0; running = false; wasBroken = false
    }

    private fun stageDone(stage: Int) {
        if (!stageMessages.value) return
        val text = "SS $stage/$STAGES"
        if (stageTarget.value == Target.PARTY) sendCommand("pc $text") else modMessage(text)
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
                if (wasBroken && restartMessage.value) sendCommand("pc SS Started Again!")
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
        if (!breakAlert.value) return
        if (breakParty.value) sendCommand("pc SS Broke!")
        if (breakTitle.value) setTitle("§c§l§nSS BROKE!")
        if (breakSound.value) playSoundAtPlayer(SoundEvents.ANVIL_LAND, 5f, 0f)
    }

    // The device's completion ends its last stage, with no next show to confirm it.
    private fun deviceDone() {
        if (!active() || !running) return
        val last = maxOf(pendingStage, roundLength)
        if (last > 0) stageDone(last)
        reset()
    }

    fun install() {
        listOf(stageMessages, stageTarget, breakAlert, breakParty, breakTitle, breakSound, restartMessage).forEach { SimonSays.registerSetting(it) }
        on<LevelEvent.Load> { EngineerClient.mc.execute { reset() } }
        on<TickEvent.Server> { EngineerClient.mc.execute { EngineerClient.safely("ss alerts") { tick() } } }
        on<MessageEvent.Chat> {
            if (deviceRegex.matches(message)) EngineerClient.mc.execute { EngineerClient.safely("ss alerts device") { deviceDone() } }
        }
        EventBus.subscribe(this)
    }
}
