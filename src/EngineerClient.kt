package com.engineerclient

import com.engineerclient.betterpf.BetterPF
import com.engineerclient.misc.AgroLeaderboard
import com.engineerclient.misc.RandomStuff
import com.engineerclient.rotation.EcLog
import com.engineerclient.rotation.LeapHighlight
import com.engineerclient.chat.ChatHider
import com.engineerclient.practice.SimonSaysPractice
import com.engineerclient.pov.PovPreviews

import com.engineerclient.rotation.P3Rotation
import com.engineerclient.rotation.RoleVignette
import com.engineerclient.rotation.RotationEngine
import com.engineerclient.rotation.RotationSpec
import com.engineerclient.rotation.SetupCheck
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

    /**
     * Every module, in the order the ClickGUI panel lists them (PanelOrderMixin: Odin itself sorts
     * a panel by name width).
     */
    val MODULES: List<com.odtheking.odin.features.Module> by lazy {
        listOf(
            PovPreviews, com.engineerclient.p3sim.P3Sim, BetterPF, SimonSaysPractice, BrWaypoints2, com.engineerclient.waypoints.PositionalMessages, AgroLeaderboard, DungeonSplits,
            P3Rotation, com.engineerclient.practice.TermInfo, StormPhase, ChatHider, RandomStuff, com.engineerclient.misc.HealthMana,
        )
    }

    override fun onInitializeClient() {
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
        safely("splits look") { OdinSplitsLook.install() }
        safely("hover terms") { com.engineerclient.practice.TermsimExtras.install() }
        safely("p3sim") { com.engineerclient.p3sim.P3Sim.init() }

        // Modules default OFF and only ModuleConfig.load() toggles saved state — on a
        // fresh install nothing has saved state yet, so turn these on once.
        if (firstRun) {
            for (m in listOf(AgroLeaderboard, com.engineerclient.p3sim.P3Sim, RandomStuff, com.engineerclient.misc.HealthMana, ChatHider, BetterPF, StormPhase, SimonSaysPractice)) if (!m.enabled) m.toggle()
            ModuleManager.saveConfigurations()
        }

        // Odin's event bus: world load resets class detection.
        on<LevelEvent.Load> { safely("levelLoad") { ClassDetect.reset() } }
        EventBus.subscribe(this)

        // Own-class poll: once a second is plenty; Odin keeps the teammate list fresh from packets.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (++tickCounter % 20 == 0) safely("classPoll") { ClassDetect.poll() }
        }

        LeapHighlight.register()
        com.engineerclient.misc.Witherborn.register()
        com.engineerclient.misc.I4Complete.register()
        com.engineerclient.practice.I4Aims.register()
        RoleVignette.register()
        EcLog.open(mc.gameDirectory.toPath(), listOf(
            "engineerclient ${FabricLoader.getInstance().getModContainer("engineerclient").map { it.metadata.version.friendlyString }.orElse("?")}" +
                "  spec v${RotationSpec.graph.version}" +
                "  odin ${FabricLoader.getInstance().getModContainer("odin").map { it.metadata.version.friendlyString }.orElse("?")}",
            "starting role: ${RotationSpec.graph.name(EcConfig.data.myStartingRole)}",
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

    /** /ec on its own: what the rest of it does. */
    private val HELP = listOf(
        "${PREFIX}§7commands:",
        "§f /ec role <role>|clear§7 — your P3 starting role; §f/ec roles§7 — everyone's",
        "§f /ec setup§7 — checks the Odin setup Dynamic Term Roles relies on",
        "§f /ec posmsg edit§7 — BR Roles position messages",
        "§f /ec log§7 [mark <note>] — the session log; §f/ec debug§7, §f/ec memreport",
    )

    private fun roleLines(): List<String> {
        val me = mc.player?.name?.string
        val mineId = EcConfig.data.myStartingRole
        val lines = mutableListOf("${EngineerClient.PREFIX}§7phase-3 starting roles §8(/ec role <role> sets yours; the rest are heard from party chat)")
        RotationSpec.graph.startingRoles.forEach { role ->
            val ign = P3Rotation.teamRoles[role.id] ?: if (role.id == mineId) me else null
            val mine = if (role.id == mineId) " §8(you)" else ""
            val tasks = role.tasks.joinToString(" ") { t ->
                val letter = t.type.first().uppercase()
                if (t.check) "§b$letter" else "§8$letter"
            }
            lines += "§7 ${role.name.padEnd(7)} §f${ign ?: "§8unbound"}$mine  $tasks§7 ${role.note}"
        }
        if (RotationEngine.running) {
            lines += "§7 live: §a${RotationEngine.roleOf(me ?: "")?.name ?: "§8—"}§7, section §a${P3Rotation.section}"
        }
        return lines
    }

    private fun mark(note: String) {
        EcLog.log("MARK", note.ifBlank { "(no note)" })
        P3Rotation.debugLines().forEach { EcLog.log("MARK", "  " + it.replace(Regex("§."), "")) }
    }

    private fun registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // /betterpf: the link to all your uploaded runs, private ones included.
            for (name in listOf("betterpf", "BetterPF")) dispatcher.register(literal(name).executes { BetterPF.myRunsLink(); 1 })
            // /posmsg: Positional Messages (removed from Odin in 0.3.6) is provided here.
            com.engineerclient.waypoints.PositionalMessages.registerCommand(dispatcher)
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
                    .then(literal("debug").executes { ctx ->
                        P3Rotation.debugLines().forEach { ctx.source.sendFeedback(Component.literal(it)) }
                        1
                    })
                    .then(literal("memreport").executes {
                        com.engineerclient.debug.MemReport.write { line -> msg(line) }
                        1
                    })
                    .then(literal("setup").executes { ctx ->
                        SetupCheck.lines().forEach { ctx.source.sendFeedback(Component.literal(it)) }
                        1
                    })
                    .then(literal("roles").executes { ctx ->
                        roleLines().forEach { ctx.source.sendFeedback(Component.literal(it)) }
                        1
                    })
                    .then(literal("role")
                        .then(literal("clear").executes { ctx ->
                            EcConfig.data.myStartingRole = null
                            EcConfig.save()
                            ctx.source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7your starting role is cleared."))
                            1
                        })
                        .then(argument("role", StringArgumentType.word()).executes { ctx ->
                            val wanted = StringArgumentType.getString(ctx, "role")
                            val role = RotationSpec.graph.startingRoles.find { it.name.equals(wanted, true) }
                            if (role == null) {
                                ctx.source.sendError(Component.literal(
                                    "§cUnknown starting role '$wanted' — use ${RotationSpec.graph.startingRoles.joinToString("/") { it.name }}"))
                                return@executes 0
                            }
                            EcConfig.data.myStartingRole = role.id
                            EcConfig.save()
                            ctx.source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7you run §a${role.name}§7 — announced to the party when you enter the boss room."))
                            P3Rotation.announceMyRole()
                            1
                        }))
                    .then(literal("posmsg").then(literal("edit").executes { mc.execute { com.engineerclient.waypoints.PosMsgEditor.toggle() }; 1 }))
            )
        }
    }
}

/** A selector's place in its option list (Odin's selectors hold the enum constant); setting it wraps around. */
var <E : Enum<E>> com.odtheking.odin.clickgui.settings.impl.SelectorSetting<E>.index: Int
    get() = options.indexOf(value)
    set(i) { value = options[Math.floorMod(i, options.size)] }
