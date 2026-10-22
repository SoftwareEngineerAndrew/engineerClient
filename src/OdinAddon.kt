package com.engineerclient

import com.engineerclient.EngineerClient.mc
import com.engineerclient.EngineerClient.safely
import com.engineerclient.adr.ADR
import com.engineerclient.misc.AgroSphere
import com.engineerclient.misc.RandomStuff
import com.engineerclient.practice.SimonSaysPractice
import com.engineerclient.pov.PovPreviews
import com.engineerclient.splits.DungeonSplits
import com.engineerclient.splits.OdinSplitsLook
import com.engineerclient.waypoints.BrWaypoints2
import com.odtheking.odin.config.ModuleConfig
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.ModuleManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Everything of the mod that runs on Odin: its modules (own ClickGUI panel, own config file), the additions to Odin's
 * own modules, Odin's event bus, and the commands that open Odin things. [EngineerClient] calls [init] only when Odin
 * is loaded ([EngineerClient.odin]), and nothing else Odin-free reaches in here, so without Odin none of it is loaded.
 */
object OdinAddon {

    /**
     * Every module, in the order the ClickGUI panel lists them (PanelOrderMixin: Odin itself sorts
     * a panel by name width).
     */
    val MODULES: List<com.odtheking.odin.features.Module> by lazy {
        listOf(
            PovPreviews, ADR, SimonSaysPractice, BrWaypoints2, AgroSphere, DungeonSplits, com.engineerclient.practice.Recore,
            com.engineerclient.clearbreakdown.ClearBreakdown,
            RandomStuff, com.engineerclient.misc.HealthMana, com.engineerclient.misc.Timers, com.engineerclient.misc.LcmMeleeWarning, FullRun,
        )
    }

    /**
     * The full run's settings (Engineer Client's ADS full run, src/p3sim/run and src/p3sim/clear): its modules add theirs to an
     * Odin module ([com.engineerclient.p3sim.run.AdsBootstrap.registerSettings]), and ADS's P3Sim is not one, so they get this.
     */
    object FullRun : com.odtheking.odin.features.Module(
        name = "ADS Full Run",
        category = com.odtheking.odin.features.Category.custom("ADS", 860, 10),
        description = "The full F7 run (/ads run): the clear's bots, mobs, secrets, traps and blood room.",
        key = null,
    ) {
        init { com.engineerclient.p3sim.run.AdsBootstrap.registerSettings(this) }
    }

    private var tickCounter = 0

    /** [firstRun]: a fresh install (no saved settings yet). */
    fun init(firstRun: Boolean) {
        // Register our own module into Odin's module system: own ClickGUI panel
        // ("Engineer Client"), own config file (config/odin/addons/engineerclient.json), own event
        // subscription lifecycle. This is Odin's documented addon path.
        ModuleManager.registerModules(ModuleConfig("engineerclient.json"), *MODULES.toTypedArray())

        // Additions to Odin's own modules (e.g. the Engineer Splits look on Odin's Splits) go in
        // before anything saves the configs, which would drop saved values for settings that don't
        // exist yet.
        safely("ss solver") { com.engineerclient.practice.OdinSimonSays.install() }
        safely("masks used") { com.engineerclient.misc.OdinMasksUsed.install() }
        safely("tick timers") { com.engineerclient.misc.OdinTickTimers.install() }
        safely("highlight look") { com.engineerclient.misc.OdinHighlightLook.install() }
        safely("splits look") { OdinSplitsLook.install() }
        safely("sub splits tracking") { com.engineerclient.splits.DungeonSplits.install() }
        safely("term info") { com.engineerclient.practice.TermInfo.init() }
        // Room Prediction and LCM Style: settings on Odin's own Dungeon Map (from Ascent Client, same names, so saved values
        // carry over); Odin's configs are read again so they apply (reading never writes the file).
        safely("lcm style") { com.engineerclient.lcm.LcmStyle.install() }
        safely("map prediction") { com.engineerclient.mappred.OdinMapPrediction.install() }
        safely("map configs") { ModuleManager.loadConfigurations() }
        safely("hover terms") { com.engineerclient.practice.TermsimExtras.install() }
        safely("odin term times") { com.engineerclient.practice.OdinTermTimes.install() }
        safely("terminal guard") { com.engineerclient.terminals.OdinTerminalGuard.install() }
        runCatching { com.engineerclient.misc.LaunchStats.install() }
        safely("leap order") { com.engineerclient.misc.LeapOrder.install() }

        // Modules default OFF and only ModuleConfig.load() toggles saved state — on a
        // fresh install nothing has saved state yet, so turn these on once.
        if (firstRun) {
            for (m in listOf(AgroSphere, com.engineerclient.practice.Recore, RandomStuff, com.engineerclient.misc.HealthMana, com.engineerclient.misc.Timers, ADR, SimonSaysPractice)) if (!m.enabled) m.toggle()
            ModuleManager.saveConfigurations()
        }

        // Odin's event bus: world load resets class detection.
        on<LevelEvent.Load> { safely("levelLoad") { ClassDetect.reset() } }
        EventBus.subscribe(this)

        // Own-class poll: once a second is plenty; Odin keeps the teammate list fresh from packets.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (++tickCounter % 20 == 0) safely("classPoll") { ClassDetect.poll() }
        }

        DeadPlayers.register()
        com.engineerclient.misc.Witherborn.register()
        com.engineerclient.misc.I4Complete.register()

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // /adr: the link to all your uploaded runs, private ones included.
            // /adr online: ADR Online in your browser, signed in.
            for (name in listOf("adr", "ADR")) dispatcher.register(literal(name).executes { ADR.myRunsLink(); 1 }
                .then(literal("online").executes { com.engineerclient.adr.OnlineLink.openPage(); 1 }))
            // /termsim inf: next to Odin's /termsim (Brigadier merges the trees; the literal wins over its arguments).
            dispatcher.register(literal("termsim").then(literal("inf").executes { mc.schedule { com.engineerclient.practice.InfNumbersSim.open(0L) }; 1 }))
        }
    }

    /** /adm on its own: Odin's settings menu, as its /od opens it. */
    fun openClickGui() = mc.schedule { mc.setScreenAndShow(com.odtheking.odin.clickgui.ClickGUI) }
}

/** A selector's place in its option list (Odin's selectors hold the enum constant); setting it wraps around. */
var <E : Enum<E>> com.odtheking.odin.clickgui.settings.impl.SelectorSetting<E>.index: Int
    get() = options.indexOf(value)
    set(i) { value = options[Math.floorMod(i, options.size)] }
