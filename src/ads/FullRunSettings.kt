package com.engineerclient.ads

import com.engineerclient.ads.settings.AdsSettingsRegistry
import com.engineerclient.ads.settings.Settings
import com.engineerclient.ads.settings.SettingsPanel

/**
 * The full run's settings (Engineer Client's recorded-dungeon run, src/p3sim/run and src/p3sim/clear), in ADS's own settings:
 * each of its modules adds its settings here ([com.engineerclient.p3sim.run.AdsBootstrap.registerSettings]), as it would to an
 * Odin module, so the run needs no Odin. Saved to config/engineerclient/fullrun.json; `/ads` shows them as one panel per module,
 * and the Practice page's Full clear sets the run's own choices (layout, party, lag, rules).
 */
object FullRunSettings : Settings("Full Run", "fullrun.json") {

    /** Registers the run's settings, reads the saved values and adds the `/ads` panels. Once, at start-up. */
    fun init() {
        com.engineerclient.p3sim.run.AdsBootstrap.registerSettings(this)
        load()
        registerKeybinds()
        AdsSettingsRegistry.register { panels() }
    }

    /** One panel per kind of setting, by the name each module starts its settings with ("Run Bots", "Mobs Spawn"...). */
    private fun panels(): List<SettingsPanel> {
        val groups = linkedMapOf(
            "Full Run" to listOf("Run "),
            "Full Run: Bots" to listOf("Bots "),
            "Full Run: Mobs" to listOf("Mobs "),
            "Full Run: Secrets" to listOf("Secrets ", "Blessing "),
        )
        val used = HashSet<com.engineerclient.ads.settings.Setting<*>>()
        val out = ArrayList<SettingsPanel>()
        for ((title, prefixes) in groups) {
            val list = all.filter { s -> prefixes.any { s.name.startsWith(it) } }
            used += list
            if (list.isNotEmpty()) out += SettingsPanel(this, title, list)
        }
        val rest = all.filter { it !in used }
        if (rest.isNotEmpty()) out += SettingsPanel(this, "Full Run: Other", rest)
        return out
    }
}
