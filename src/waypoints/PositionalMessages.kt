package com.engineerclient.waypoints

import com.engineerclient.EngineerClient
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ListSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.handlers.schedule
import com.odtheking.odin.utils.render.drawCylinder
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/**
 * Odin's Positional Messages (and its /posmsg), which Odin dropped in 0.3.6: party chat when you
 * reach a spot in the boss, once per world. Arrival callouts in the boss rely on these boxes, so
 * engineerClient carries the module on as it was - same settings, same stored format (its saved
 * list is copied over from Odin's config by [com.engineerclient.ConfigMigration]).
 */
object PositionalMessages : Module(
    name = "Positional Messages",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Sends a message when you're near a certain position. /posmsg",
) {
    private val onlyDungeons by BooleanSetting("Only in Dungeons", true, desc = "Only sends messages when you're in a dungeon.")
    private val showPositions by BooleanSetting("Show Positions", false, desc = "Draws boxes/lines around the positions.")
    private val cylinderHeight by NumberSetting("Height", 0.2f, 0.1..5.0, 0.1, desc = "Height of the cylinder for in messages.").withDependency { showPositions }
    private val displayMessage by BooleanSetting("Show Message", true, desc = "Whether or not to display the message in the box.").withDependency { showPositions }
    private val messageSize by NumberSetting("Message Size", 1f, 0.1..4.0, 0.1f, desc = "The size at which to display the message in the box.").withDependency { showPositions && displayMessage }

    data class PosMessage(val x: Double, val y: Double, val z: Double, val x2: Double?, val y2: Double?, val z2: Double?, val delay: Int, val distance: Double?, val color: Color, val message: String?, val dontSend: Boolean) {
        @Transient
        private var _center: Vec3? = null
        val center: Vec3
            get() = _center ?: Vec3((x + (x2 ?: x)) / 2, (y + (y2 ?: y)) / 2, (z + (z2 ?: z)) / 2).also { _center = it }

        @Transient
        private var _box: AABB? = null
        val box: AABB?
            get() {
                if (_box == null && x2 != null && y2 != null && z2 != null) _box = AABB(x, y, z, x2, y2, z2)
                return _box
            }

        @Transient
        private var _radiusSquared: Double? = null
        val radiusSquared: Double?
            get() {
                if (_radiusSquared == null) distance?.let { _radiusSquared = it * it }
                return _radiusSquared
            }
    }

    val posMessageStrings by ListSetting("Pos Messages", mutableListOf<PosMessage>())

    /** Sent this world (cleared on a world load); [PosMsgEditor] re-arms them with Posmsg Re-trigger. */
    val sentMessages = mutableSetOf<PosMessage>()

    init {
        onSend<ServerboundMovePlayerPacket> {
            if (onlyDungeons && !DungeonUtils.inBoss) return@onSend
            posMessageStrings.forEach { posMessage ->
                if (!posMessage.dontSend && posMessage !in sentMessages) posMessage.x2?.let { handleInString(posMessage) } ?: handleAtString(posMessage)
            }
        }

        on<RenderExtractEvent> {
            if (!showPositions || (onlyDungeons && !DungeonUtils.inBoss)) return@on
            posMessageStrings.forEach { posMessage ->
                if (posMessage.distance != null) {
                    drawCylinder(posMessage.center, posMessage.distance.toFloat(), cylinderHeight, color = posMessage.color, depth = true)
                    if (displayMessage) posMessage.message?.let { drawText(it, Vec3(posMessage.x, posMessage.y + 1, posMessage.z), messageSize, true) }
                } else {
                    drawWireFrameBox(posMessage.box ?: return@forEach, posMessage.color, depth = true)
                    if (displayMessage) posMessage.message?.let { drawText(it, posMessage.center.add(0.0, 1.0, 0.0), messageSize, true) }
                }
            }
        }

        on<LevelEvent.Load> {
            sentMessages.clear()
        }
    }

    private fun handleAtString(posMessage: PosMessage) {
        val player = mc.player ?: return
        val radiusSquared = posMessage.radiusSquared ?: return
        if (player.distanceToSqr(posMessage.x, posMessage.y, posMessage.z) > radiusSquared) return
        sentMessages.add(posMessage)
        schedule(posMessage.delay) { sendCommand("pc ${posMessage.message}") }
    }

    private fun handleInString(posMessage: PosMessage) {
        val aabb = posMessage.box ?: return
        val position = mc.player?.position() ?: return
        if (!aabb.contains(position)) return
        sentMessages.add(posMessage)
        schedule(posMessage.delay) { sendCommand("pc ${posMessage.message}") }
    }

    // --- /posmsg, as Odin had it ------------------------------------------------------------------

    private val COLORS = linkedMapOf(
        "darkblue" to Colors.MINECRAFT_DARK_BLUE, "darkgreen" to Colors.MINECRAFT_DARK_GREEN, "darkaqua" to Colors.MINECRAFT_DARK_AQUA,
        "darkred" to Colors.MINECRAFT_DARK_RED, "darkpurple" to Colors.MINECRAFT_DARK_PURPLE, "gold" to Colors.MINECRAFT_GOLD,
        "gray" to Colors.MINECRAFT_GRAY, "darkgray" to Colors.MINECRAFT_DARK_GRAY, "blue" to Colors.MINECRAFT_BLUE,
        "green" to Colors.MINECRAFT_GREEN, "aqua" to Colors.MINECRAFT_AQUA, "red" to Colors.MINECRAFT_RED,
        "lightpurple" to Colors.MINECRAFT_LIGHT_PURPLE, "yellow" to Colors.MINECRAFT_YELLOW, "white" to Colors.WHITE, "black" to Colors.BLACK,
    )

    private fun say(text: String) = EngineerClient.msg(text)

    private fun suggest(values: () -> List<String>) = SuggestionProvider<FabricClientCommandSource> { _, b ->
        values().filter { it.startsWith(b.remaining, true) }.forEach(b::suggest)
        b.buildFuture()
    }

    private fun coord(get: (net.minecraft.client.player.LocalPlayer) -> Double) = suggest { listOfNotNull(mc.player?.let { floor(get(it)).toString() }) }

    private fun coords(names: List<String>, last: ArgumentBuilder<FabricClientCommandSource, *>): ArgumentBuilder<FabricClientCommandSource, *> {
        var tail = last
        for ((i, n) in names.withIndex().reversed()) {
            tail = argument(n, DoubleArgumentType.doubleArg()).suggests(coord(listOf<(net.minecraft.client.player.LocalPlayer) -> Double>({ it.x }, { it.y }, { it.z })[i % 3])).then(tail)
        }
        return tail
    }

    private fun tail(vararg pre: Pair<String, com.mojang.brigadier.arguments.ArgumentType<*>>, run: (CommandContext<FabricClientCommandSource>, Color, Boolean, String) -> Unit): ArgumentBuilder<FabricClientCommandSource, *> {
        val message = argument("message", StringArgumentType.greedyString()).executes { ctx ->
            val name = StringArgumentType.getString(ctx, "color")
            val color = COLORS[name.lowercase()]
            if (color == null) say("Unknown color $name") else run(ctx, color, BoolArgumentType.getBool(ctx, "send"), StringArgumentType.getString(ctx, "message"))
            1
        }
        var node: ArgumentBuilder<FabricClientCommandSource, *> = argument("color", StringArgumentType.word()).suggests(suggest { COLORS.keys.toList() })
            .then(argument("send", BoolArgumentType.bool()).then(message))
        for ((n, type) in pre.reversed()) node = argument(n, type).then(node)
        return node
    }

    private fun added(message: String, entry: PosMessage, text: String) {
        if (posMessageStrings.any { it.message == message }) return
        posMessageStrings.add(entry)
        say(text)
        ModuleManager.saveConfigurations()
    }

    fun registerCommand(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(literal("posmsg")
            .then(literal("add")
                .then(literal("at").then(coords(listOf("x", "y", "z"), tail("delay" to IntegerArgumentType.integer(), "distance" to DoubleArgumentType.doubleArg()) { ctx, color, dontSend, message ->
                    val x = DoubleArgumentType.getDouble(ctx, "x"); val y = DoubleArgumentType.getDouble(ctx, "y"); val z = DoubleArgumentType.getDouble(ctx, "z")
                    val delay = IntegerArgumentType.getInteger(ctx, "delay"); val distance = DoubleArgumentType.getDouble(ctx, "distance")
                    added(message, PosMessage(x, y, z, null, null, null, delay, distance, color, message, dontSend),
                        "Message \"$message\" added at $x, $y, $z, with ${delay}t delay, triggered up to $distance blocks away.")
                })))
                .then(literal("in").then(coords(listOf("x", "y", "z", "x2", "y2", "z2"), tail("delay" to IntegerArgumentType.integer()) { ctx, color, dontSend, message ->
                    val c = listOf("x", "y", "z", "x2", "y2", "z2").map { DoubleArgumentType.getDouble(ctx, it) }
                    val delay = IntegerArgumentType.getInteger(ctx, "delay")
                    added(message, PosMessage(c[0], c[1], c[2], c[3], c[4], c[5], delay, null, color, message, dontSend),
                        "Message \"$message\" added in ${c[0]}, ${c[1]}, ${c[2]}, ${c[3]}, ${c[4]}, ${c[5]}, with ${delay}t delay.")
                }))))
            .then(literal("remove").then(argument("message", StringArgumentType.greedyString())
                .suggests(suggest { posMessageStrings.mapNotNull { it.message }.distinct() })
                .executes { ctx ->
                    val message = StringArgumentType.getString(ctx, "message").trim()
                    if (posMessageStrings.none { it.message.equals(message, true) }) {
                        say("Message not found. Available messages: ${posMessageStrings.joinToString { "\"${it.message}\"" }}")
                        return@executes 0
                    }
                    val removed = posMessageStrings.count { it.message.equals(message, true) }
                    posMessageStrings.removeAll { it.message.equals(message, true) }
                    say("Removed $removed Positional Message(s): \"$message\"")
                    ModuleManager.saveConfigurations()
                    1
                }))
            .then(literal("clear").executes {
                say("Cleared List")
                posMessageStrings.clear()
                ModuleManager.saveConfigurations()
                1
            })
            .then(literal("list").executes {
                val output = posMessageStrings.withIndex().joinToString(separator = "\n") { (index, it) ->
                    "${index + 1}: ${it.x}, ${it.y}, ${it.z}, ${it.x2}, ${it.y2}, ${it.z2}, ${it.delay}, ${it.distance}, ${it.color.hex()}, send=${!it.dontSend}, \"${it.message}\""
                }
                say(if (posMessageStrings.isEmpty()) "Positional Message list is empty!" else "Positional Message list:\n$output")
                1
            }))
    }
}
