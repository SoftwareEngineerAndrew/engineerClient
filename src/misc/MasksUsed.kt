package com.engineerclient.misc

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.features.impl.dungeon.InvincibilityTimer

/**
 * "Only Used" for ADM's Invincibility Timer: its HUD lists only the masks (and Phoenix) that have
 * saved you since you joined the world - each stays once used, cooldown over or not, so you see
 * which are back. Nothing used yet: no HUD. Registered into ADM's module like the Splits look
 * (see SplitsLook); the HUD asks [shows] where it reads its Show Spirit / Bonzo / Phoenix
 * settings while drawing.
 */
object MasksUsed {

    private val onlyUsed = BooleanSetting("Only Used", false, desc = "Only show the masks and Phoenix that have saved you this run. Added by engineerClient.")
        .withDependency { (InvincibilityTimer.settings["Displayed Items"] as? DropdownSetting)?.value ?: true }

    private val used = BooleanArray(3)

    /**
     * ADM's Show setting for item [index] (0 Spirit, 1 Bonzo, 2 Phoenix), less the unused ones with Only Used on, and
     * none in P3 Sim while it's stopped (no phase running).
     */
    @JvmStatic
    fun shows(index: Int, setting: Boolean): Boolean = setting && (!onlyUsed.value || used[index]) &&
        !(com.engineerclient.p3sim.P3Sim.inSim && com.engineerclient.p3sim.PartyClient.phase.isEmpty())

    /**
     * P3 Sim restarted its masks and Phoenix (Masks.reset, every start): ADM's timers go back to ready as well, and
     * nothing counts as used this run. Client thread.
     */
    fun resetTimers() {
        used.fill(false)
        InvincibilityTimer.resetTimers()
    }

    fun markUsed(index: Int) { used[index] = true }

    fun clearUsed() = used.fill(false)

    /** Adds the setting to ADM's module; before SplitsLook.install, which re-reads ADM's config for both. */
    fun install() {
        InvincibilityTimer.registerSetting(onlyUsed)
    }
}
