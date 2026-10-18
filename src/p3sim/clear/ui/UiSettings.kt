package com.engineerclient.p3sim.clear.ui

import com.engineerclient.p3sim.run.LagPreset
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.engineerclient.enumSelector
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.mojang.blaze3d.platform.InputConstants

/**
 * The run settings in the ADS Odin module (next to the boss practice's own). They are the defaults the start screen opens with
 * and what `/ads run random` uses for whatever the command does not name; the start screen's buttons change them too, so the
 * last choice is remembered. All names start with "Run " (Odin keys a setting by its name).
 *
 * Created in [register], not when the object loads, as the module rules ask (the headless tests load this object without Minecraft).
 */
object UiSettings {
    enum class MobHp { BASE, PER_RUN }

    private lateinit var bots: BooleanSetting
    private lateinit var partySize: NumberSetting<Int>
    private lateinit var lag: SelectorSetting
    private lateinit var noDeaths: BooleanSetting
    private lateinit var mobHp: SelectorSetting
    private lateinit var strategy: StringSetting

    @Volatile private var ready = false

    fun register(host: com.odtheking.odin.features.Module) {
        bots = host.registerSetting(BooleanSetting("Run Bots", true, "Teammate bots in a full run: they walk the dungeon and their rooms check off on the measured schedule. Off: you clear alone and the party list is just you."))
        partySize = host.registerSetting(NumberSetting("Run Party Size", 5, 1, 5, 1, desc = "Players in a full run, you included. The bots are the classes nobody plays. 1 is the same as bots off."))
        lag = host.registerSetting(enumSelector("Run Lag", LagPreset.TYPICAL, desc = "Server lag in a full run, as net ticks lost per minute: Good 14.5, Typical 30.3, Bad 56.6, High slot (an instance slot above 200) 54.6. Off: no lag at all."))
        noDeaths = host.registerSetting(BooleanSetting("Run No Deaths", false, "You cannot die in the clear: your health stops at its last point instead of the death and the revive."))
        mobHp = host.registerSetting(enumSelector("Run Mob HP", MobHp.BASE, desc = "Base: every mob has its base HP. Per run: each run draws its HP scale from the recorded per-run table, as the real dungeon's mobs vary."))
        // The strategies a plug-in offers are not known until it loads, so this cannot be a fixed list; the start screen's button is the picker.
        strategy = StringSetting("Run Party Strategy", StrategyChoice.AUTO, 64, desc = "Which party strategy the bots follow (auto, neutral or a plug-in preset's id). Chosen with the Party Strategy button on the start screen.").also { it.hide() }
        host.registerSetting(strategy)
        host.registerSetting(KeybindSetting("Run Start Screen Keybind", InputConstants.UNKNOWN, "Opens the full run start screen (layout, party, lag).").onPress { UiModule.openStartScreen() })
        host.registerSetting(KeybindSetting("Run Restart Room Keybind", InputConstants.UNKNOWN, "In a full run: go back to the savepoint taken when the party entered the room you are in, as /ads run restart room does.").onPress { UiServer.send("restart", "room") })
        host.registerSetting(KeybindSetting("Run Restart Keybind", InputConstants.UNKNOWN, "In a full run: go back to the very start (the countdown), as /ads run restart does. The same dungeon, no rebuild.").onPress { UiServer.send("restart") })
        host.registerSetting(KeybindSetting("Run Savepoint Keybind", InputConstants.UNKNOWN, "In a full run: take a savepoint right now (/ads run restore <number> goes back to it).").onPress { UiServer.send("savepoint") })
        ready = true
    }

    /** The current defaults; the plain ones before the settings exist. */
    fun defaults(): RunDefaults =
        if (!ready) RunDefaults() else RunDefaults(bots.value, partySize.value, LagPreset.entries[lag.value.coerceIn(0, LagPreset.entries.size - 1)], noDeaths.value, mobHp.value == MobHp.PER_RUN.ordinal, strategy.value.ifBlank { StrategyChoice.AUTO })

    /** The start screen remembers its choices here (saved with Odin's config when the screen closes). */
    fun store(d: RunDefaults) {
        if (!ready) return
        bots.value = d.bots
        partySize.value = d.partySize.coerceIn(1, 5)
        lag.value = d.lag.ordinal
        noDeaths.value = d.noDeaths
        mobHp.value = (if (d.mobHpFromPack) MobHp.PER_RUN else MobHp.BASE).ordinal
        strategy.value = d.strategy
    }

}
