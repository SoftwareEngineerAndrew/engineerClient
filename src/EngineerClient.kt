package com.engineerclient

import com.engineerclient.rotation.EcLog
import com.mojang.brigadier.arguments.StringArgumentType
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

    /** Odin is installed: the Odin addon ([OdinAddon]) loads, everything else runs either way. */
    @JvmStatic
    val odin: Boolean = FabricLoader.getInstance().isModLoaded("odin")

    override fun onInitializeClient() {
        try { ConfigMigration.rename(mc.gameDirectory.toPath()) } catch (t: Throwable) { logger.warn("[ec] moving Engineer Client's settings to ADM failed", t) }
        try { ConfigMigration.run(mc.gameDirectory.toPath().resolve("config").resolve("odin")) } catch (t: Throwable) { logger.warn("[ec] module settings migration failed", t) }
        val firstRun = EcConfig.load()
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register { _, _, _ -> EcConfig.migrateRoleBindings() }

        // The sim first: on a first start it reads its settings from Odin's file, before Odin can save over it.
        safely("clean hud") { com.engineerclient.ads.CleanHud.init() }
        safely("p3sim") { com.engineerclient.p3sim.P3Sim.init() }
        // ADS's own terminal solver, for the sim's and Rustclear's terminals.
        safely("terminal solver") { com.engineerclient.terminals.AdsTerminalSolver.init() }
        // Everything that runs on Odin (its modules, the additions to Odin's own, its event bus), only with Odin.
        if (odin) safely("odin addon") { OdinAddon.init(firstRun) }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!windowKept) safely("window") { keepWindowUp() }
            if (!exclusiveChecked) safely("exclusive fullscreen") { noExclusiveFullscreen() }
        }

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

    /** The command's names: /adm and its aliases. Other parts of the mod add their subcommands under these. */
    val COMMANDS = listOf("adm", "ADM", "advanceddungeonsmod", "eclient", "ea", "eec", "em")

    /** /adm help: what the rest of it does. Other parts of the mod add their own lines. */
    val HELP = mutableListOf(
        "${PREFIX}§7commands:",
        "§f /adm log§7 [mark <note>] — the session log; §f/adm memreport",
    )

    /** More lines for /adm log mark to stamp under the note (other parts of the mod add theirs). */
    val markLines = mutableListOf<() -> List<String>>()

    private fun mark(note: String) {
        EcLog.log("MARK", note.ifBlank { "(no note)" })
        markLines.forEach { lines -> lines().forEach { EcLog.log("MARK", "  " + it.replace(Regex("§."), "")) } }
    }

    private fun registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // /adm on its own: Odin's settings menu, as its /od opens it (ADS's settings without Odin); /adm help
            // lists the rest. Same tree under every alias (Brigadier literals are case-sensitive). Built fresh per
            // name — a bare redirect would not run the root executes on the alias itself.
            for (name in COMMANDS) dispatcher.register(
                literal(name)
                    .executes { if (odin) OdinAddon.openClickGui() else com.engineerclient.p3sim.P3Sim.openSettings(); 1 }
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
