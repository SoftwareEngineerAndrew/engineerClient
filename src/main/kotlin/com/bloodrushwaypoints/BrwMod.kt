package com.bloodrushwaypoints

import com.bloodrushwaypoints.gui.BrwScreen
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
        ModuleManager.registerModules(ModuleConfig("bloodrushwaypoints.json"), BrwWaypoints)

        // Modules default OFF and only ModuleConfig.load() toggles saved state — on a
        // fresh install nothing has saved state yet, so turn the module on once.
        if (firstRun && !BrwWaypoints.enabled) {
            BrwWaypoints.toggle()
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
