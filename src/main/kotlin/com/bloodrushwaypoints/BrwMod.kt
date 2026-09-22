package com.bloodrushwaypoints

import com.bloodrushwaypoints.gui.BrwScreen
import com.bloodrushwaypoints.rotation.BrwLog
import com.bloodrushwaypoints.rotation.LeapHighlight
import com.bloodrushwaypoints.pf.PartyFinderStats
import com.bloodrushwaypoints.pov.PovPreviews
import com.bloodrushwaypoints.rotation.P3Rotation
import com.bloodrushwaypoints.rotation.RoleVignette
import com.bloodrushwaypoints.rotation.RotationEngine
import com.bloodrushwaypoints.rotation.RotationSpec
import com.bloodrushwaypoints.rotation.SetupCheck
import com.bloodrushwaypoints.waypoints.BrwWaypoints
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

object BrwMod : ClientModInitializer {

    val logger: Logger = LoggerFactory.getLogger("bloodrushwaypoints")
    val mc: Minecraft get() = Minecraft.getInstance()

    private var tickCounter = 0

    override fun onInitializeClient() {
        migrateAscentData()
        val firstRun = BrwConfig.load()

        // Register our own module into Odin's module system: own ClickGUI panel
        // ("Blood Rush"), own config file (config/odin/addons/bloodrushwaypoints.json), own event
        // subscription lifecycle. This is Odin's documented addon path.
        ModuleManager.registerModules(ModuleConfig("bloodrushwaypoints.json"), BrwWaypoints, P3Rotation, PovPreviews, PartyFinderStats)

        // Modules default OFF and only ModuleConfig.load() toggles saved state — on a
        // fresh install nothing has saved state yet, so turn the module on once.
        if (firstRun) {
            if (!BrwWaypoints.enabled) BrwWaypoints.toggle()
            if (!P3Rotation.enabled) P3Rotation.toggle()
            if (!PartyFinderStats.enabled) PartyFinderStats.toggle()
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
        BrwLog.open(mc.gameDirectory.toPath(), listOf(
            "brw ${FabricLoader.getInstance().getModContainer("bloodrushwaypoints").map { it.metadata.version.friendlyString }.orElse("?")}" +
                "  spec v${RotationSpec.graph.version}" +
                "  odin ${FabricLoader.getInstance().getModContainer("odin").map { it.metadata.version.friendlyString }.orElse("?")}",
            "starting role: ${RotationSpec.graph.name(BrwConfig.data.myStartingRole)}",
        ))
        // The player is not known until they log in; record who this client is once they are.
        ClientTickEvents.END_CLIENT_TICK.register(object : ClientTickEvents.EndTick {
            var done = false
            override fun onEndTick(client: Minecraft) {
                if (done) return
                client.player?.let { BrwLog.log("SESSION", "I am ${it.name.string}"); done = true }
            }
        })

        registerCommand()

        OdinMod.scope.launch {
            safely("ensurePacks") { RushProfiles.ensureAllPacks() }
        }

        logger.info(
            "[brw] initialized — players=${BrwConfig.data.playersOnRush}p " +
                "door=${BrwConfig.data.dedicatedDoor} override=${BrwConfig.data.classOverride} " +
                "stash=${BrwConfig.data.lastKnownClass}"
        )
    }

    /**
     * One-time migration from the mod's original "ascent" name: moves
     * config/ascent -> config/bloodrushwaypoints (profile config + waypoint packs)
     * and rewrites config/odin/addons/ascent.json -> bloodrushwaypoints.json with the
     * module renamed, so authored waypoints and module settings survive the rename.
     */
    private fun migrateAscentData() {
        try {
            val gameDir = mc.gameDirectory.toPath()
            val oldCfg = gameDir.resolve("config").resolve("ascent")
            val newCfg = gameDir.resolve("config").resolve("bloodrushwaypoints")
            if (java.nio.file.Files.isDirectory(oldCfg) && !java.nio.file.Files.exists(newCfg)) {
                java.nio.file.Files.move(oldCfg, newCfg)
                logger.info("[brw] migrated config/ascent -> config/bloodrushwaypoints")
            }
            val oldModule = gameDir.resolve("config").resolve("odin").resolve("addons").resolve("ascent.json")
            val newModule = gameDir.resolve("config").resolve("odin").resolve("addons").resolve("bloodrushwaypoints.json")
            if (java.nio.file.Files.exists(oldModule) && !java.nio.file.Files.exists(newModule)) {
                java.nio.file.Files.writeString(newModule, java.nio.file.Files.readString(oldModule).replace("Ascent Waypoints", "Blood Rush Waypoints"))
                java.nio.file.Files.delete(oldModule)
                logger.info("[brw] migrated Odin addon config ascent.json -> bloodrushwaypoints.json")
            }
        } catch (t: Throwable) {
            logger.warn("[brw] ascent->bloodrushwaypoints data migration failed", t)
        }
    }

    /** Every handler that runs inside Odin's bus or a coroutine must not be able to take Odin down with it. */
    inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("[brw] $what failed", t)
        }
    }

    fun chat(msg: String) {
        mc.schedule { mc.gui.chat.addClientSystemMessage(Component.literal(msg)) }
    }

    private fun statusLines(): List<String> {
        val d = BrwConfig.data
        return listOf(
            "§8[§6BRW§8]§7 enabled: ${if (d.enabled) "§ayes" else "§cno"}",
            "§7 class: §a${RushProfiles.effectiveClass() ?: "§cunknown"}§7 " +
                "(override=${d.classOverride ?: "auto"}, live=${ClassDetect.detected?.name ?: "none"}, stash=${d.lastKnownClass ?: "none"})",
            "§7 players on rush: §a${d.playersOnRush}§7, dedicated door: §a${d.dedicatedDoor}",
            "§7 active pack: §a${RushProfiles.activePackName() ?: "none"}§7, custom: §a${d.customPacks.joinToString().ifEmpty { "none" }}",
            "§7 tab-detection misses this session: §a${ClassDetect.fallbackFloors}",
        )
    }

    private fun roleLines(): List<String> {
        val me = mc.player?.name?.string
        val mineId = BrwConfig.data.myStartingRole
        val lines = mutableListOf("§8[§6BRW§8]§7 phase-3 starting roles §8(/brw role <role> sets yours; the rest are heard from party chat)")
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
        BrwLog.log("MARK", note.ifBlank { "(no note)" })
        P3Rotation.debugLines().forEach { BrwLog.log("MARK", "  " + it.replace(Regex("§."), "")) }
    }

    private fun registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // Same tree registered under the formal name (both casings, since Brigadier
            // literals are case-sensitive) and the short alias. Built fresh per name —
            // a bare redirect would not run the root executes on the alias itself.
            for (name in listOf("bloodrushwaypoints", "BloodRushWaypoints", "brw")) dispatcher.register(
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
                        BrwConfig.data.playersOnRush = IntegerArgumentType.getInteger(ctx, "count")
                        BrwConfig.save()
                        RushProfiles.applySelection("players set")
                        1
                    }))
                    .then(literal("door").then(argument("on", StringArgumentType.word()).executes { ctx ->
                        BrwConfig.data.dedicatedDoor = StringArgumentType.getString(ctx, "on").equals("on", true)
                        BrwConfig.save()
                        RushProfiles.applySelection("door set")
                        1
                    }))
                    .then(literal("log")
                        .executes { ctx ->
                            ctx.source.sendFeedback(Component.literal("§8[§6BRW§8]§7 log: §f${BrwLog.path ?: "not open"}"))
                            ctx.source.sendFeedback(Component.literal("§7 send that file to debug a run; §f/brw log mark <note>§7 stamps a note into it"))
                            1
                        }
                        .then(literal("mark")
                            .executes { ctx -> mark(""); ctx.source.sendFeedback(Component.literal("§8[§6BRW§8]§7 marked.")); 1 }
                            .then(argument("note", StringArgumentType.greedyString()).executes { ctx ->
                                val note = StringArgumentType.getString(ctx, "note")
                                mark(note)
                                ctx.source.sendFeedback(Component.literal("§8[§6BRW§8]§7 marked: §f$note"))
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
                            BrwConfig.data.myStartingRole = null
                            BrwConfig.save()
                            ctx.source.sendFeedback(Component.literal("§8[§6BRW§8]§7 your starting role is cleared."))
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
                            BrwConfig.data.myStartingRole = role.id
                            BrwConfig.save()
                            ctx.source.sendFeedback(Component.literal("§8[§6BRW§8]§7 you run §a${role.name}§7 — announced to the party when you enter the boss room."))
                            P3Rotation.announceMyRole()
                            1
                        }))
                    .then(literal("class").then(argument("name", StringArgumentType.word()).executes { ctx ->
                        val arg = StringArgumentType.getString(ctx, "name")
                        if (arg.equals("auto", true)) {
                            BrwConfig.data.classOverride = null
                        } else {
                            val match = RushProfiles.CLASSES.find { it.equals(arg, true) }
                            if (match == null) {
                                ctx.source.sendError(Component.literal("§cUnknown class '$arg' — use auto/${RushProfiles.CLASSES.joinToString("/")}"))
                                return@executes 0
                            }
                            BrwConfig.data.classOverride = match
                        }
                        BrwConfig.save()
                        RushProfiles.applySelection("class override set")
                        1
                    }))
            )
        }
    }
}
