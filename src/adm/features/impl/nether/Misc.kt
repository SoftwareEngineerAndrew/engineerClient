package com.adm.features.impl.nether

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.handlers.schedule
import com.adm.utils.sendCommand

object Misc : Module(
    name = "Misc",
    description = "Miscellaneous Nether features."
) {
    private val manaDrain by BooleanSetting("Mana Drain", true, desc = "Sends in party chat when you drain mana near players.")
    private val autoRequeue by BooleanSetting("Auto Requeue", true, desc = "Automatically requeues you after a Kuudra run.")
    private val requeueDelay by NumberSetting("Requeue Delay", 20, 0..100, 1, unit = "ticks", desc = "Delay before requeuing after a run ends.").withDependency { autoRequeue }

    private const val RUN_OVER = "[NPC] Elle: Good job everyone. A hard fought battle come to an end. Let's get out of here before we run into any more trouble!"
    private const val FOCUS_PREFIX = "Used Extreme Focus! ("
    private const val FOCUS_SUFFIX = " Mana)"

    init {
        on<MessageEvent.Chat> {
            if (manaDrain && message.startsWith(FOCUS_PREFIX) && message.endsWith(FOCUS_SUFFIX)) {
                val mana = message.substring(FOCUS_PREFIX.length, message.length - FOCUS_SUFFIX.length)
                if (mana.isNotEmpty() && mana.all { it in '0'..'9' }) reportDrain(mana)
            }
            if (autoRequeue && message == RUN_OVER) schedule(requeueDelay) { sendCommand("instancerequeue") }
        }
    }

    private fun reportDrain(mana: String) {
        val self = mc.player ?: return
        val players = mc.level?.players() ?: return
        var nearby = 0
        for (other in players) if (other !== self && other.distanceToSqr(self) < 49 && other.uuid.version() == 4) nearby++
        sendCommand("pc Used $mana mana ($nearby players nearby)")
    }
}
