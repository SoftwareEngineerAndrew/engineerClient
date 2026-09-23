package com.engineerclient

import com.engineerclient.gui.BrwScreen
import com.engineerclient.misc.AutoJoinHypixel
import com.engineerclient.misc.RandomStuff
import com.engineerclient.misc.RestartGame
import com.engineerclient.misc.SimplifySkeletors
import com.engineerclient.rotation.EcLog
import com.engineerclient.rotation.LeapHighlight
import com.engineerclient.chat.ChatHider
import com.engineerclient.pf.PartyFinderStats
import com.engineerclient.pov.PovPreviews
import com.engineerclient.price.LowestBin
import com.engineerclient.render.NoGlint
import com.engineerclient.rotation.P3Rotation
import com.engineerclient.rotation.RoleVignette
import com.engineerclient.rotation.RotationEngine
import com.engineerclient.rotation.RotationSpec
import com.engineerclient.rotation.SetupCheck
import com.engineerclient.waypoints.BrwWaypoints
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.OdinMod
import com.odtheking.odin.config.ModuleConfig
import com.odtheking.odin.events.FloorEnterEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.ModuleManager
import kotlinx.coroutines.launch
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

    override fun onInitializeClient() {
        migrateOldNames()
        val firstRun = EcConfig.load()

        // Register our own module into Odin's module system: own ClickGUI panel
        // ("Blood Rush"), own config file (config/odin/addons/engineerclient.json), own event
        // subscription lifecycle. This is Odin's documented addon path.
        ModuleManager.registerModules(ModuleConfig("engineerclient.json"), BrwWaypoints, P3Rotation, PovPreviews, PartyFinderStats, NoGlint, RandomStuff, ChatHider, LowestBin, SimplifySkeletors, RestartGame, AutoJoinHypixel)

        // Modules default OFF and only ModuleConfig.load() toggles saved state — on a
        // fresh install nothing has saved state yet, so turn the module on once.
        if (firstRun) {
            if (!BrwWaypoints.enabled) BrwWaypoints.toggle()
            if (!P3Rotation.enabled) P3Rotation.toggle()
            if (!PartyFinderStats.enabled) PartyFinderStats.toggle()
            if (!NoGlint.enabled) NoGlint.toggle()
            if (!LowestBin.enabled) LowestBin.toggle()
            ModuleManager.saveConfigurations()
        }

        // Odin's event bus: floor entry drives profile application, world load resets detection.
        on<FloorEnterEvent> { safely("floorEnter") { ClassDetect.onFloorEnter(floor.name) } }
        on<LevelEvent.Load> { safely("levelLoad") { ClassDetect.reset() } }
        EventBus.subscribe(this)

        // Own-class poll: once a second is plenty; Odin keeps the teammate list fresh from packets.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (++tickCounter % 20 == 0) safely("classPoll") { ClassDetect.poll() }
        }

        LeapHighlight.register()
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

        OdinMod.scope.launch {
            safely("ensurePacks") { RushProfiles.ensureAllPacks() }
        }

        logger.info(
            "[ec] initialized — players=${EcConfig.data.playersOnRush}p " +
                "door=${EcConfig.data.dedicatedDoor} override=${EcConfig.data.classOverride} " +
                "stash=${EcConfig.data.lastKnownClass}"
        )
    }

    /**
     * One-time migrations across the mod's earlier names, oldest first:
     * ascent -> bloodrushwaypoints -> engineerclient. Each step moves
     * config/<old> (profile config, waypoint packs, pf stats) to config/<new> and carries the
     * Odin addon module file config/odin/addons/<old>.json across, so authored waypoints,
     * starting roles and module settings survive a rename. Only runs when the new location
     * does not exist yet.
     */
    private fun migrateOldNames() {
        migrateName("ascent", "bloodrushwaypoints") { it.replace("Ascent Waypoints", "Blood Rush Waypoints") }
        migrateName("bloodrushwaypoints", "engineerclient") { it }
    }

    private fun migrateName(old: String, new: String, rewriteModuleJson: (String) -> String) {
        try {
            val gameDir = mc.gameDirectory.toPath()
            val oldCfg = gameDir.resolve("config").resolve(old)
            val newCfg = gameDir.resolve("config").resolve(new)
            if (java.nio.file.Files.isDirectory(oldCfg) && !java.nio.file.Files.exists(newCfg)) {
                java.nio.file.Files.move(oldCfg, newCfg)
                logger.info("[ec] migrated config/$old -> config/$new")
            }
            val addons = gameDir.resolve("config").resolve("odin").resolve("addons")
            val oldModule = addons.resolve("$old.json")
            val newModule = addons.resolve("$new.json")
            if (java.nio.file.Files.exists(oldModule) && !java.nio.file.Files.exists(newModule)) {
                java.nio.file.Files.writeString(newModule, rewriteModuleJson(java.nio.file.Files.readString(oldModule)))
                java.nio.file.Files.delete(oldModule)
                logger.info("[ec] migrated Odin addon config $old.json -> $new.json")
            }
        } catch (t: Throwable) {
            logger.warn("[ec] $old->$new data migration failed", t)
        }
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
        mc.schedule { mc.gui.chat.addClientSystemMessage(Component.literal(msg)) }
    }

    private fun statusLines(): List<String> {
        val d = EcConfig.data
        return listOf(
            "§8[§6EC§8]§7 enabled: ${if (d.enabled) "§ayes" else "§cno"}",
            "§7 class: §a${RushProfiles.effectiveClass() ?: "§cunknown"}§7 " +
                "(override=${d.classOverride ?: "auto"}, live=${ClassDetect.detected?.name ?: "none"}, stash=${d.lastKnownClass ?: "none"})",
            "§7 players on rush: §a${d.playersOnRush}§7, dedicated door: §a${d.dedicatedDoor}",
            "§7 active pack: §a${RushProfiles.activePackName() ?: "none"}§7, custom: §a${d.customPacks.joinToString().ifEmpty { "none" }}",
            "§7 tab-detection misses this session: §a${ClassDetect.fallbackFloors}",
        )
    }

    private fun roleLines(): List<String> {
        val me = mc.player?.name?.string
        val mineId = EcConfig.data.myStartingRole
        val lines = mutableListOf("§8[§6EC§8]§7 phase-3 starting roles §8(/brw role <role> sets yours; the rest are heard from party chat)")
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
            // Same tree registered under the formal name (both casings, since Brigadier
            // literals are case-sensitive) and the short alias. Built fresh per name —
            // a bare redirect would not run the root executes on the alias itself.
            for (name in listOf("engineerclient", "EngineerClient", "ec", "brw")) dispatcher.register(
                literal(name)
                    .executes { ctx ->
                        mc.execute { mc.setScreen(BrwScreen()) }
                        1
                    }
                    .then(literal("status").executes { ctx ->
                        statusLines().forEach { ctx.source.sendFeedback(Component.literal(it)) }
                        1
                    })
                    .then(literal("apply").executes {
                        RushProfiles.applySelection("manual /brw apply")
                        1
                    })
                    .then(literal("players").then(argument("count", IntegerArgumentType.integer(2, 5)).executes { ctx ->
                        EcConfig.data.playersOnRush = IntegerArgumentType.getInteger(ctx, "count")
                        EcConfig.save()
                        RushProfiles.applySelection("players set")
                        1
                    }))
                    .then(literal("door").then(argument("on", StringArgumentType.word()).executes { ctx ->
                        EcConfig.data.dedicatedDoor = StringArgumentType.getString(ctx, "on").equals("on", true)
                        EcConfig.save()
                        RushProfiles.applySelection("door set")
                        1
                    }))
                    .then(literal("log")
                        .executes { ctx ->
                            ctx.source.sendFeedback(Component.literal("§8[§6EC§8]§7 log: §f${EcLog.path ?: "not open"}"))
                            ctx.source.sendFeedback(Component.literal("§7 send that file to debug a run; §f/brw log mark <note>§7 stamps a note into it"))
                            1
                        }
                        .then(literal("mark")
                            .executes { ctx -> mark(""); ctx.source.sendFeedback(Component.literal("§8[§6EC§8]§7 marked.")); 1 }
                            .then(argument("note", StringArgumentType.greedyString()).executes { ctx ->
                                val note = StringArgumentType.getString(ctx, "note")
                                mark(note)
                                ctx.source.sendFeedback(Component.literal("§8[§6EC§8]§7 marked: §f$note"))
                                1
                            })))
                    .then(literal("debug").executes { ctx ->
                        P3Rotation.debugLines().forEach { ctx.source.sendFeedback(Component.literal(it)) }
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
                            ctx.source.sendFeedback(Component.literal("§8[§6EC§8]§7 your starting role is cleared."))
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
                            ctx.source.sendFeedback(Component.literal("§8[§6EC§8]§7 you run §a${role.name}§7 — announced to the party when you enter the boss room."))
                            P3Rotation.announceMyRole()
                            1
                        }))
                    .then(literal("class").then(argument("name", StringArgumentType.word()).executes { ctx ->
                        val arg = StringArgumentType.getString(ctx, "name")
                        if (arg.equals("auto", true)) {
                            EcConfig.data.classOverride = null
                        } else {
                            val match = RushProfiles.CLASSES.find { it.equals(arg, true) }
                            if (match == null) {
                                ctx.source.sendError(Component.literal("§cUnknown class '$arg' — use auto/${RushProfiles.CLASSES.joinToString("/")}"))
                                return@executes 0
                            }
                            EcConfig.data.classOverride = match
                        }
                        EcConfig.save()
                        RushProfiles.applySelection("class override set")
                        1
                    }))
            )
        }
    }
}
