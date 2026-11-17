package com.engineerclient

import com.engineerclient.adr.ADR
import com.engineerclient.misc.AgroSphere
import com.engineerclient.misc.RandomStuff
import com.engineerclient.rotation.EcLog
import com.engineerclient.pov.PosePov

import com.engineerclient.splits.DungeonSplits
import com.engineerclient.splits.SplitsLook
import com.engineerclient.waypoints.BrWaypoints2
import com.mojang.brigadier.arguments.StringArgumentType
import com.adm.config.ModuleConfig
import com.adm.events.LevelEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.ModuleManager
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
     * already stuck minimized is restored. Only on Wayland: elsewhere a fullscreen window that doesn't
     * minimize stays in front of whatever you Alt-Tab to (Windows), so tabbing out looks broken.
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
        if (System.getenv("XDG_SESSION_TYPE") != "wayland") { windowKept = true; return }
        val h = mc.window.handle()
        if (h == 0L) return
        // 26.3 windows are SDL3: auto-minimize is a hint, read at every focus loss.
        org.lwjgl.sdl.SDLHints.SDL_SetHint(org.lwjgl.sdl.SDLHints.SDL_HINT_VIDEO_MINIMIZE_ON_FOCUS_LOSS, "0")
        if ((org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(h) and org.lwjgl.sdl.SDLVideo.SDL_WINDOW_MINIMIZED) != 0L) org.lwjgl.sdl.SDLVideo.SDL_RestoreWindow(h)
        windowKept = true
    }

    /** Every module. */
    val MODULES: List<com.adm.features.Module> by lazy {
        listOf(
            PosePov, com.engineerclient.terminals.Terminals, com.engineerclient.devices.Devices, com.engineerclient.p3sim.P3Sim, ADR, BrWaypoints2, AgroSphere, DungeonSplits, com.engineerclient.practice.Recore, com.engineerclient.practice.EeHighlight,
            RandomStuff, com.engineerclient.misc.CommandAliases, com.engineerclient.dungeon.Refill,
            com.engineerclient.misc.BlockInstaclear,
            com.engineerclient.culling.ChunkCulling,
            com.engineerclient.misc.ColorPalette,
            com.engineerclient.dungeon.ClearInfo, com.engineerclient.dungeon.SalvageOverlay, com.engineerclient.dungeon.LagWarning, com.engineerclient.dungeon.ExplosiveShot,
            com.engineerclient.dungeon.TickTimers, com.engineerclient.dungeon.PositionalMessages, com.engineerclient.dungeon.Relics,
            com.engineerclient.skyblock.ItemTweaks,
            com.engineerclient.skyblock.EnchantColors,
            com.engineerclient.skyblock.ItemProtection,
            com.engineerclient.render.BossBar,
            com.engineerclient.skyblock.Arrows,
            com.engineerclient.skyblock.SearchBar,
            com.engineerclient.visual.Sounds,
            com.engineerclient.visual.Performance,
            com.engineerclient.visual.BlazeDaggerHUD,
            com.engineerclient.render.Visibility,
            com.engineerclient.render.ScrollableTooltip,
            com.engineerclient.render.BlockOverlay,
            com.engineerclient.dungeon.Dungeonbreaker,
            com.engineerclient.dungeon.DungeonQueue,
            com.engineerclient.render.ItemAnimations,
        )
    }

    override fun onInitializeClient() {
        try { ConfigMigration.rename(mc.gameDirectory.toPath()) } catch (t: Throwable) { logger.warn("[ec] moving Engineer Client's settings to ADM failed", t) }
        try { ConfigMigration.run(com.adm.AdmMod.configFile.toPath()) } catch (t: Throwable) { logger.warn("[ec] module settings migration failed", t) }
        val firstRun = EcConfig.load()
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register { _, _, _ -> EcConfig.migrateRoleBindings() }

        // Register our own module into ADM's module system: own ClickGUI panel
        // ("Engineer Client"), own config file (config/core/addons/engineerclient.json), own event
        // subscription lifecycle. This is ADM's documented addon path.
        ModuleManager.registerModules(ModuleConfig("engineerclient.json"), *MODULES.toTypedArray())
        safely("subscribe enabled") { subscribeEnabled(MODULES) }

        // Additions to ADM's own modules (e.g. the Engineer Splits look on ADM's Splits) go in
        // before anything saves the configs, which would drop saved values for settings that don't
        // exist yet.
        safely("ss solver") { com.engineerclient.practice.SimonSaysTracker.install() }
        safely("masks used") { com.engineerclient.misc.MasksUsed.install() }
        safely("chat melody") { com.engineerclient.misc.MelodyChat.install() }
        safely("highlight look") { com.engineerclient.misc.HighlightLook.install() }
        safely("door outline") { com.engineerclient.misc.DoorOutline.install() }
        safely("instant chime") { com.engineerclient.misc.InstantChime.install() }
        safely("secret counter") { com.engineerclient.dungeon.SecretCounter.install() }
        safely("ss alerts") { com.engineerclient.misc.SimonSaysAlerts.install() }
        safely("custom keybinds") { com.engineerclient.misc.CustomKeybinds.install() }
        safely("core link") { com.engineerclient.party.OdinLink.install() }
        safely("splits look") { SplitsLook.install() }
        safely("sub splits tracking") { com.engineerclient.splits.DungeonSplits.install() }
        safely("term info") { com.engineerclient.practice.TermInfo.init() }
        // Room Prediction and LCM Style (with LCM Colors): settings on ADM's own Dungeon Map
        // (from Ascent Client, same names, so saved values carry over); ADM's configs are read again so they apply
        // (reading never writes the file).
        safely("lcm style") { com.engineerclient.lcm.LcmStyle.install() }
        // Read on the first dungeon otherwise, on the render thread.
        Thread({ safely("warm packs") { com.engineerclient.dungeonpack.pack.doors.DoorPack.bundled; com.engineerclient.dungeonpack.pack.secrets.SecretPack.bundled } }, "ec warm packs")
            .apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        safely("map prediction") { com.engineerclient.mappred.MapPrediction.install() }
        safely("map icons") { com.engineerclient.mapicons.MapIcons.install() }
        safely("map sizes") { com.engineerclient.mapicons.MapSizes.install() }
        safely("map layout") { com.engineerclient.mapicons.MapLayout.install() }
        // Team TP Maze: settings on ADM's Puzzle Solvers (its TP Maze), the party's shared maze map over ADM's websocket.
        safely("team tp maze") { com.engineerclient.tpmaze.TeamTpMaze.install() }
        safely("water board") { com.engineerclient.waterboard.WaterBoardSolver.install() }
        safely("tic tac toe") { com.engineerclient.dungeon.TicTacToeSolver.install() }
        safely("freeze report") { com.engineerclient.misc.FreezeReport.install() }
        safely("health mana") { com.engineerclient.misc.HealthMana.install() }
        safely("lcm melee warning") { com.engineerclient.misc.LcmMeleeWarning.install() }
        safely("terminals") { com.engineerclient.terminals.Terminals.install() }
        safely("devices") { com.engineerclient.devices.Devices.install() }
        safely("clear info") { com.engineerclient.dungeon.ClearInfo.install() }
        safely("item worth") { com.engineerclient.skyblock.ItemWorth.install() }
        safely("pov in leap menu") { com.engineerclient.pov.PovInLeapMenu.install() }
        safely("creeper beams ding") { com.engineerclient.dungeon.CreeperBeamsDing.install() }
        safely("puzzle overlay") { com.engineerclient.misc.PuzzleOverlay.install() }
        safely("secret waypoints") { com.engineerclient.dungeon.SecretWaypoints.install() }
        safely("item highlight") { com.engineerclient.misc.ItemHighlight.install() }
        safely("close chest on key") { com.engineerclient.skyblock.CloseChestOnKey.install() }
        safely("map configs") { ModuleManager.loadConfigurations() }
        runCatching { com.engineerclient.misc.LaunchStats.install() }
        runCatching { com.engineerclient.misc.UpdateCheck.install() }
        safely("leap order") { com.engineerclient.misc.LeapOrder.install() }
        safely("runs logger") { com.engineerclient.skyblock.RunsLogger.install() }
        safely("p3sim") { com.engineerclient.p3sim.P3Sim.init() }

        // Modules default OFF and only ModuleConfig.load() toggles saved state — on a
        // fresh install nothing has saved state yet, so turn these on once.
        if (firstRun) {
            for (m in listOf(AgroSphere, com.engineerclient.practice.Recore, com.engineerclient.p3sim.P3Sim, RandomStuff, com.engineerclient.dungeon.TickTimers, ADR)) if (!m.enabled) m.toggle()
            ModuleManager.saveConfigurations()
        }

        // ADM's event bus: world load resets class detection.
        on<LevelEvent.Load> { safely("levelLoad") { ClassDetect.reset() } }
        EventBus.subscribe(this)

        // Own-class poll: once a second is plenty; ADM keeps the teammate list fresh from packets.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (++tickCounter % 20 == 0) safely("classPoll") { ClassDetect.poll() }
            if (!windowKept) safely("window") { keepWindowUp() }
            if (!exclusiveChecked) safely("exclusive fullscreen") { noExclusiveFullscreen() }
        }

        DeadPlayers.register()
        com.engineerclient.misc.Witherborn.register()
        com.engineerclient.misc.Zpew.register()
        com.engineerclient.misc.I4Complete.register()
        EcLog.open(mc.gameDirectory.toPath(), listOf(
            "engineerclient ${FabricLoader.getInstance().getModContainer("engineerclient").map { it.metadata.version.friendlyString }.orElse("?")}",
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

    /**
     * Subscribes those of [modules] that are on to ADM's event bus. ADM subscribes a module only when it's toggled on
     * (onEnable) or always active: one made with `toggled = true` starts on but unsubscribed, and a config load toggles
     * only a module whose saved state differs, so with "enabled" saved it stays that way for good. On in the ClickGUI,
     * yet every `on<...>` / `onReceive<...>` of its is never called until it's turned off and on again (Terminal Hitboxes'
     * boxes, EE Highlight's Show Spots, Term Progress's labels; 2026-11-07, ADM 0.4.0). Call right after
     * registerModules: a later config load that turns one off then unsubscribes it as usual. Subscribing twice is nothing.
     */
    fun subscribeEnabled(modules: Iterable<com.adm.features.Module>) {
        val fixed = modules.filter { it.enabled && !it.alwaysActive && it !in EventBus.activeSubscribers }
        for (m in fixed) EventBus.subscribe(m)
        if (fixed.isNotEmpty()) logger.info("[ec] on but left off ADM's event bus, subscribed: ${fixed.joinToString { it.name }}")
    }

    /** Every handler that runs inside ADM's bus or a coroutine must not be able to take ADM down with it. */
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
    /** Engineer Client, the beta: true. promote.sh makes it false for ADM, the public build, which hides the unfinished ADS buttons (full run, GUI). */
    const val BETA = true

    const val PREFIX = "§8[§6EC§8] "

    /** A line from the mod, with its [PREFIX]. Anything the mod says goes through here. */
    fun msg(text: String) = chat(PREFIX + text)

    fun msg(text: Component) = chat(Component.literal(PREFIX).append(text))

    /** The command's names: /adm and its aliases. Other parts of the mod add their subcommands under these. */
    val COMMANDS = listOf("adm", "ADM", "advanceddungeonsmod", "eclient", "ea", "eec", "em")

    /** /adm help: what the rest of it does. Other parts of the mod add their own lines. */
    val HELP = mutableListOf(
        "${PREFIX}§7commands:",
        "§f /adm keybinds§7 — Command Keybinds",
        "§f /adm worth§7 — what the held item is worth, part by part",
        "§f /adm log§7 [mark <note>] — the session log; §f/adm memreport",
    )

    private val OD = Regex("""^(?i)(od|odin)(\s+|$)""")
    private val FLOOR = Regex("""^(?i)(e|[fm][1-7])$""")

    /** A saved command written for /od: the same under /adm, floors as /f7. */
    fun admCommand(command: String): String {
        val m = OD.find(command) ?: return command
        val rest = command.substring(m.range.last + 1).trim()
        return if (FLOOR.matches(rest)) rest.lowercase() else "adm $rest".trim()
    }

    /** More lines for /adm log mark to stamp under the note (other parts of the mod add theirs). */
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
            // /e, /f1-/f7, /m1-/m7: join that floor, joininstance.
            val numbers = listOf("one", "two", "three", "four", "five", "six", "seven")
            val floors = mapOf("e" to "catacombs_entrance") + (1..7).flatMap { n ->
                listOf("f$n" to "catacombs_floor_${numbers[n - 1]}", "m$n" to "master_catacombs_floor_${numbers[n - 1]}")
            }
            for ((name, instance) in floors) dispatcher.register(literal(name).executes { mc.schedule { com.adm.utils.sendCommand("joininstance $instance") }; 1 })
            // /edt: #EndDungeonsToxicity in party chat.
            dispatcher.register(literal("edt").executes { mc.schedule { com.adm.utils.sendCommand("pc #EndDungeonsToxicity") }; 1 })
            // /termsim [ping], /termsim <terminal> [ping], /termsim inf.
            fun sim(open: (Long) -> Unit) = argument("ping", com.mojang.brigadier.arguments.LongArgumentType.longArg(0, 2000))
                .executes { ctx -> val p = com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "ping"); mc.schedule { open(p) }; 1 }
            var termsim = literal("termsim").executes { mc.schedule { com.engineerclient.terminals.sim.StartGUI.open(com.engineerclient.terminals.Terminals.simPing.toLong()) }; 1 }
                .then(sim { com.engineerclient.terminals.sim.StartGUI.open(it) })
                .then(literal("inf").executes { mc.schedule { com.engineerclient.practice.InfNumbersSim.open(0L) }; 1 })
            for (type in com.engineerclient.terminals.TerminalTypes.entries) termsim = termsim.then(literal(type.name.lowercase())
                .executes { mc.schedule { type.simulator().open(com.engineerclient.terminals.Terminals.simPing.toLong()) }; 1 }
                .then(sim { type.simulator().open(it) }))
            dispatcher.register(termsim)
            // /adm on its own: the settings menu; ADM's commands live under it too.
            // Same tree under every alias (Brigadier literals are case-sensitive). Built fresh per
            // name — a bare redirect would not run the root executes on the alias itself.
            val core = com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>()
                .also { com.adm.commands.mainCommand.register(it) }.root.getChild("adm").children
            for (name in COMMANDS) dispatcher.register(
                literal(name).apply { core.forEach { then(it) } }
                    .executes { mc.schedule { mc.setScreenAndShow(com.engineerclient.gui.AdmClickGui) }; 1 }
                    .then(literal("keybinds").executes {
                        mc.schedule { com.engineerclient.gui.AdmClickGui.open(com.adm.features.impl.skyblock.CommandKeybinds) }
                        1
                    })
                    .then(literal("worth").executes { ctx ->
                        val stack = mc.player?.mainHandItem
                        val w = stack?.let(com.engineerclient.skyblock.ItemWorth::worth)
                        if (w == null) ctx.source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7No price for the held item."))
                        else {
                            ctx.source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7").append(stack.hoverName).append("§7: §6${com.engineerclient.skyblock.ItemWorth.coins(w.first)}"))
                            for ((name, v) in w.second.sortedByDescending { it.second }) ctx.source.sendFeedback(Component.literal("§7 $name: §6${com.engineerclient.skyblock.ItemWorth.coins(v)}"))
                        }
                        1
                    })
                    .then(literal("help").executes { ctx ->
                        HELP.forEach { ctx.source.sendFeedback(Component.literal(it)) }
                        1
                    })
                    .then(literal("log")
                        .executes { ctx ->
                            ctx.source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7log: §f${EcLog.path ?: "not open"}"))
                            ctx.source.sendFeedback(Component.literal("§7 send that file to debug a run; §f/adm log mark <note>§7 stamps a note into it"))
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

/** A selector's place in its option list (ADM's selectors hold the enum constant); setting it wraps around. */
var <E : Enum<E>> com.adm.clickgui.settings.impl.SelectorSetting<E>.index: Int
    get() = options.indexOf(value)
    set(i) { value = options[Math.floorMod(i, options.size)] }
