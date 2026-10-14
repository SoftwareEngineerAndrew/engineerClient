package com.engineerclient

import com.engineerclient.adr.ADR
import com.engineerclient.misc.AgroSphere
import com.engineerclient.misc.RandomStuff
import com.engineerclient.rotation.EcLog
import com.engineerclient.practice.SimonSaysPractice
import com.engineerclient.pov.PovPreviews

import com.engineerclient.splits.DungeonSplits
import com.engineerclient.splits.OdinSplitsLook
import com.engineerclient.storm.StormPhase
import com.engineerclient.waypoints.BrWaypoints2
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.config.ModuleConfig
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.ModuleManager
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object EngineerClient : ClientModInitializer {

    val logger: Logger = LoggerFactory.getLogger("engineerclient")
    val mc: Minecraft get() = Minecraft.getInstance()

    private var tickCounter = 0
    /** [keepWindowUp] has run (once is enough: the attribute sticks). */
    private var windowKept = false

    /**
     * Fullscreen game windows minimize themselves when they lose focus (Alt-Tab, a click on another
     * screen), and on KDE Wayland a window minimized that way comes back blank: the game never hears
     * it was restored, so it keeps skipping its frames. Auto-minimize off, and a window that is
     * already stuck minimized is restored.
     */
    /** [noExclusiveFullscreen] has run. */
    private var exclusiveChecked = false

    /**
     * Exclusive fullscreen (a video mode change) on Wayland goes through Xwayland, and once the game
     * loses focus KDE never shows its window again, focused or not. Borderless fullscreen looks the
     * same and doesn't break, so on Wayland exclusive is turned off. Since 26.3 the option applies
     * live, so a window in exclusive fullscreen goes straight to borderless.
     */
    private fun noExclusiveFullscreen() {
        exclusiveChecked = true
        if (System.getenv("XDG_SESSION_TYPE") != "wayland") return
        val o = mc.options
        if (!o.exclusiveFullscreen().get()) return
        o.exclusiveFullscreen().set(false)
        o.save()
        logger.info("[ec] exclusive fullscreen off (it breaks on Wayland once the game loses focus)")
    }

    private fun keepWindowUp() {
        val h = mc.window.handle()
        if (h == 0L) return
        // 26.3 windows are SDL3: auto-minimize is a hint, read at every focus loss.
        org.lwjgl.sdl.SDLHints.SDL_SetHint(org.lwjgl.sdl.SDLHints.SDL_HINT_VIDEO_MINIMIZE_ON_FOCUS_LOSS, "0")
        if ((org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(h) and org.lwjgl.sdl.SDLVideo.SDL_WINDOW_MINIMIZED) != 0L) org.lwjgl.sdl.SDLVideo.SDL_RestoreWindow(h)
        windowKept = true
    }

    /**
     * Every module, in the order the ClickGUI panel lists them (PanelOrderMixin: Odin itself sorts
     * a panel by name width).
     */
    val MODULES: List<com.odtheking.odin.features.Module> by lazy {
        listOf(
            PovPreviews, com.engineerclient.p3sim.P3Sim, ADR, SimonSaysPractice, BrWaypoints2, AgroSphere, DungeonSplits,
            com.engineerclient.practice.TermInfo, StormPhase, RandomStuff, com.engineerclient.misc.HealthMana, com.engineerclient.misc.Timers,
        )
    }

    override fun onInitializeClient() {
        try { ConfigMigration.rename(mc.gameDirectory.toPath()) } catch (t: Throwable) { logger.warn("[ec] moving Engineer Client's settings to ADM failed", t) }
        try { ConfigMigration.run(mc.gameDirectory.toPath().resolve("config").resolve("odin")) } catch (t: Throwable) { logger.warn("[ec] module settings migration failed", t) }
        val firstRun = EcConfig.load()

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
        safely("hover terms") { com.engineerclient.practice.TermsimExtras.install() }
        safely("odin term times") { com.engineerclient.practice.OdinTermTimes.install() }
        safely("launch stats") { com.engineerclient.misc.LaunchStats.install() }
        safely("leap order") { com.engineerclient.misc.LeapOrder.install() }
        safely("p3sim") { com.engineerclient.p3sim.P3Sim.init() }

        // Modules default OFF and only ModuleConfig.load() toggles saved state — on a
        // fresh install nothing has saved state yet, so turn these on once.
        if (firstRun) {
            for (m in listOf(AgroSphere, com.engineerclient.p3sim.P3Sim, RandomStuff, com.engineerclient.misc.HealthMana, com.engineerclient.misc.Timers, ADR, StormPhase, SimonSaysPractice)) if (!m.enabled) m.toggle()
            ModuleManager.saveConfigurations()
        }

        // Odin's event bus: world load resets class detection.
        on<LevelEvent.Load> { safely("levelLoad") { ClassDetect.reset() } }
        EventBus.subscribe(this)

        // Own-class poll: once a second is plenty; Odin keeps the teammate list fresh from packets.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (++tickCounter % 20 == 0) safely("classPoll") { ClassDetect.poll() }
            if (!windowKept) safely("window") { keepWindowUp() }
            if (!exclusiveChecked) safely("exclusive fullscreen") { noExclusiveFullscreen() }
        }

        DeadPlayers.register()
        com.engineerclient.misc.Witherborn.register()
        com.engineerclient.misc.I4Complete.register()
        EcLog.open(mc.gameDirectory.toPath(), listOf(
            "engineerclient ${FabricLoader.getInstance().getModContainer("engineerclient").map { it.metadata.version.friendlyString }.orElse("?")}" +
                "  odin ${FabricLoader.getInstance().getModContainer("odin").map { it.metadata.version.friendlyString }.orElse("?")}",
        ))
        // The player is not known until they log in; record who this client is once they are.
        ClientTickEvents.END_CLIENT_TICK.register(object : ClientTickEvents.EndTick {
            var done = false
            override fun onEndTick(client: Minecraft) {
                if (done) return
                client.player?.let { EcLog.log("SESSION", "I am ${it.name.string}"); done = true }
            }
        })

        registerCommand()

        logger.info("[ec] initialized — class stash=${EcConfig.data.lastKnownClass}")
    }

    /** Every handler that runs inside Odin's bus or a coroutine must not be able to take Odin down with it. */
    inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("[ec] $what failed", t)
        }
    }

    fun chat(msg: String) {
        mc.schedule { mc.gui.hud.chat.addClientSystemMessage(Component.literal(msg)) }
    }

    fun chat(msg: Component) {
        mc.schedule { mc.gui.hud.chat.addClientSystemMessage(msg) }
    }

    /** What every line the mod says in chat starts with. */
    const val PREFIX = "§8[§6EC§8] "

    /** A line from the mod, with its [PREFIX]. Anything the mod says goes through here. */
    fun msg(text: String) = chat(PREFIX + text)

    fun msg(text: Component) = chat(Component.literal(PREFIX).append(text))

    /** /ec on its own: what the rest of it does. Other parts of the mod add their own lines. */
    val HELP = mutableListOf(
        "${PREFIX}§7commands:",
        "§f /ec log§7 [mark <note>] — the session log; §f/ec memreport",
    )

    /** More lines for /ec log mark to stamp under the note (other parts of the mod add theirs). */
    val markLines = mutableListOf<() -> List<String>>()

    private fun mark(note: String) {
        EcLog.log("MARK", note.ifBlank { "(no note)" })
        markLines.forEach { lines -> lines().forEach { EcLog.log("MARK", "  " + it.replace(Regex("§."), "")) } }
    }

    private fun registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // /adr: the link to all your uploaded runs, private ones included.
            // /adr online: ADR Online in your browser, signed in.
            for (name in listOf("adr", "ADR")) dispatcher.register(literal(name).executes { ADR.myRunsLink(); 1 }
                .then(literal("online").executes { com.engineerclient.adr.OnlineLink.openPage(); 1 }))
            // Odin's settings menu, as its /od opens it.
            for (name in listOf("adm", "ADM", "advanceddungeonsmod", "eclient", "ea", "eec", "em"))
                dispatcher.register(literal(name).executes { mc.schedule { mc.setScreenAndShow(com.odtheking.odin.clickgui.ClickGUI) }; 1 })
            // /termsim inf: next to Odin's /termsim (Brigadier merges the trees; the literal wins over its arguments).
            dispatcher.register(literal("termsim").then(literal("inf").executes { mc.schedule { com.engineerclient.practice.InfNumbersSim.open(0L) }; 1 }))
            // Same tree registered under the formal name (both casings, since Brigadier
            // literals are case-sensitive) and the short alias. Built fresh per name —
            // a bare redirect would not run the root executes on the alias itself.
            for (name in listOf("engineerclient", "EngineerClient", "ec")) dispatcher.register(
                literal(name)
                    .executes { ctx ->
                        HELP.forEach { ctx.source.sendFeedback(Component.literal(it)) }
                        1
                    }
                    .then(literal("log")
                        .executes { ctx ->
                            ctx.source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7log: §f${EcLog.path ?: "not open"}"))
                            ctx.source.sendFeedback(Component.literal("§7 send that file to debug a run; §f/ec log mark <note>§7 stamps a note into it"))
                            1
                        }
                        .then(literal("mark")
                            .executes { ctx -> mark(""); ctx.source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7marked.")); 1 }
                            .then(argument("note", StringArgumentType.greedyString()).executes { ctx ->
                                val note = StringArgumentType.getString(ctx, "note")
                                mark(note)
                                ctx.source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7marked: §f$note"))
                                1
                            })))
                    .then(literal("memreport").executes {
                        com.engineerclient.debug.MemReport.write { line -> msg(line) }
                        1
                    })
            )
        }
    }
}

/** A selector's place in its option list (Odin's selectors hold the enum constant); setting it wraps around. */
var <E : Enum<E>> com.odtheking.odin.clickgui.settings.impl.SelectorSetting<E>.index: Int
    get() = options.indexOf(value)
    set(i) { value = options[Math.floorMod(i, options.size)] }
