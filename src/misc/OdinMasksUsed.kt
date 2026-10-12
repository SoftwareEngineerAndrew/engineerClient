package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.InvincibilityTimer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

/**
 * "Only Used" for Odin's Invincibility Timer: its HUD lists only the masks (and Phoenix) that have
 * saved you since you joined the world - each stays once used, cooldown over or not, so you see
 * which are back. Nothing used yet: no HUD. Registered into Odin's module like the Splits look
 * (see OdinSplitsLook); `InvincibilityHudMixin` asks [shows] where Odin reads its Show Spirit /
 * Bonzo / Phoenix settings while drawing.
 */
object OdinMasksUsed {

    private val onlyUsed = BooleanSetting("Only Used", false, desc = "Only show the masks and Phoenix that have saved you this run. Added by engineerClient.")
        .withDependency { (InvincibilityTimer.settings["Invincibility Hud"] as? DropdownSetting)?.value ?: true }

    /** Same messages Odin procs on: Spirit, Bonzo, Phoenix. */
    private val PROCS = listOf(
        Regex("^Second Wind Activated! Your Spirit Mask saved your life!$"),
        Regex("^Your (?:. )?Bonzo's Mask saved your life!$"),
        Regex("^Your Phoenix Pet saved you from certain death!$"),
    )
    private val used = BooleanArray(3)

    /** Odin's Show setting for item [index] (0 Spirit, 1 Bonzo, 2 Phoenix), less the unused ones with Only Used on. */
    @JvmStatic
    fun shows(index: Int, setting: Boolean): Boolean = setting && (!onlyUsed.value || used[index])

    /**
     * P3 Sim restarted its masks and Phoenix (Masks.reset, every start): Odin's timers go back to ready as well, and
     * nothing counts as used this run. Odin's InvincibilityType enum is package-private, so its public reset()
     * (cooldown and invincibility to 0) is reached by reflection. Client thread.
     */
    fun resetTimers() {
        used.fill(false)
        com.engineerclient.EngineerClient.safely("odin invincibility reset") {
            val type = Class.forName("com.odtheking.odin.features.impl.dungeon.InvincibilityTimer\$InvincibilityType")
            val reset = type.getMethod("reset").apply { isAccessible = true }
            type.enumConstants.forEach { reset.invoke(it) }
        }
    }

    /** Adds the setting to Odin's module; before OdinSplitsLook.install, which re-reads Odin's config for both. */
    fun install() {
        InvincibilityTimer.registerSetting(onlyUsed)
        on<MessageEvent.Chat> {
            if ((InvincibilityTimer.settings["Only In Dungeons"] as? BooleanSetting)?.value != false && !DungeonUtils.inDungeons) return@on
            PROCS.indexOfFirst { message.matches(it) }.takeIf { it >= 0 }?.let { used[it] = true }
        }
        on<LevelEvent.Load> { used.fill(false) }
        EventBus.subscribe(this)
    }
}
