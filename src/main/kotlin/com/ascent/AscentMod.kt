package com.ascent

import com.ascent.gui.AscentScreen
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.OdinMod
import com.odtheking.odin.events.FloorEnterEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
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

object AscentMod : ClientModInitializer {

    val logger: Logger = LoggerFactory.getLogger("ascent")
    val mc: Minecraft get() = Minecraft.getInstance()

    private var tickCounter = 0

    override fun onInitializeClient() {
        AscentConfig.load()

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
            "[ascent] initialized — players=${AscentConfig.data.playersOnRush}p " +
                "door=${AscentConfig.data.dedicatedDoor} override=${AscentConfig.data.classOverride} " +
                "stash=${AscentConfig.data.lastKnownClass}"
        )
    }

    /** Every handler that runs inside Odin's bus or a coroutine must not be able to take Odin down with it. */
    inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("[ascent] $what failed", t)
        }
    }

    fun chat(msg: String) {
        mc.schedule { mc.gui.chat.addClientSystemMessage(Component.literal(msg)) }
    }

    private fun statusLines(): List<String> {
        val d = AscentConfig.data
        return listOf(
            "§8[§6Ascent§8]§7 enabled: ${if (d.enabled) "§ayes" else "§cno"}",
            "§7 class: §a${RushProfiles.effectiveClass() ?: "§cunknown"}§7 " +
                "(override=${d.classOverride ?: "auto"}, live=${ClassDetect.detected?.name ?: "none"}, stash=${d.lastKnownClass ?: "none"})",
            "§7 players on rush: §a${d.playersOnRush}§7, dedicated door: §a${d.dedicatedDoor}",
            "§7 active pack: §a${RushProfiles.activePackName() ?: "none"}§7, custom: §a${d.customPacks.joinToString().ifEmpty { "none" }}",
            "§7 tab-detection misses this session: §a${ClassDetect.fallbackFloors}",
        )
    }

    private fun registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("ascent")
                    .executes { ctx ->
                        mc.execute { mc.setScreen(AscentScreen()) }
                        1
                    }
                    .then(literal("status").executes { ctx ->
                        statusLines().forEach { ctx.source.sendFeedback(Component.literal(it)) }
                        1
                    })
                    .then(literal("apply").executes {
                        RushProfiles.applySelection("manual /ascent apply")
                        1
                    })
                    .then(literal("players").then(argument("count", IntegerArgumentType.integer(2, 5)).executes { ctx ->
                        AscentConfig.data.playersOnRush = IntegerArgumentType.getInteger(ctx, "count")
                        AscentConfig.save()
                        RushProfiles.applySelection("players set")
                        1
                    }))
                    .then(literal("door").then(argument("on", StringArgumentType.word()).executes { ctx ->
                        AscentConfig.data.dedicatedDoor = StringArgumentType.getString(ctx, "on").equals("on", true)
                        AscentConfig.save()
                        RushProfiles.applySelection("door set")
                        1
                    }))
                    .then(literal("class").then(argument("name", StringArgumentType.word()).executes { ctx ->
                        val arg = StringArgumentType.getString(ctx, "name")
                        if (arg.equals("auto", true)) {
                            AscentConfig.data.classOverride = null
                        } else {
                            val match = RushProfiles.CLASSES.find { it.equals(arg, true) }
                            if (match == null) {
                                ctx.source.sendError(Component.literal("§cUnknown class '$arg' — use auto/${RushProfiles.CLASSES.joinToString("/")}"))
                                return@executes 0
                            }
                            AscentConfig.data.classOverride = match
                        }
                        AscentConfig.save()
                        RushProfiles.applySelection("class override set")
                        1
                    }))
            )
        }
    }
}
