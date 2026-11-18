package com.engineerclient.dungeon

import com.adm.AdmMod.mc
import com.engineerclient.EngineerClient
import com.engineerclient.dungeon.PositionalMessages.PosMsg
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

/** Odin's /posmsg: add at, add in, add atself, remove, clear, list, colors; on its own it opens the manager. */
object PosMsgCommand {
    private typealias Ctx = CommandContext<FabricClientCommandSource>
    private typealias Node = ArgumentBuilder<FabricClientCommandSource, *>

    fun install() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(tree("posmsg"))
            for (name in EngineerClient.COMMANDS) dispatcher.register(literal(name).then(tree("posmsg")))
        }
    }

    private fun d(name: String) = argument(name, DoubleArgumentType.doubleArg())
    private fun d(ctx: Ctx, name: String) = DoubleArgumentType.getDouble(ctx, name)

    /** delay [distance] color message..., then [add]. */
    private fun tail(withDistance: Boolean, add: (Ctx, Long, Double?, String, String) -> Unit): Node {
        val message = argument("message", StringArgumentType.greedyString()).executes { ctx ->
            add(ctx, LongArgumentType.getLong(ctx, "delay"), if (withDistance) d(ctx, "distance") else null,
                StringArgumentType.getString(ctx, "color"), StringArgumentType.getString(ctx, "message")); 1
        }
        val color = argument("color", StringArgumentType.word()).then(message)
        return argument("delay", LongArgumentType.longArg(0)).then(if (withDistance) d("distance").then(color) else color)
    }

    private fun add(m: PosMsg, where: String) {
        if (PositionalMessages.parseColor(m.color) == null) return EngineerClient.msg("§cUnknown color ${m.color}§7, see /posmsg colors.")
        val list = PositionalMessages.messages
        if (list.any { PosMsgScreen.posText(it) == PosMsgScreen.posText(m) && it.message == m.message }) return EngineerClient.msg("§cThis message already exists!")
        list.add(m)
        PositionalMessages.changed()
        EngineerClient.msg("§7Message \"§f${m.message}§7\" added $where, with ${m.delay}ms delay.")
    }

    private fun tree(name: String): LiteralArgumentBuilder<FabricClientCommandSource> = literal(name)
        .executes { mc.schedule { mc.gui.setScreen(PosMsgScreen(null)) }; 1 }
        .then(literal("gui").executes { mc.schedule { mc.gui.setScreen(PosMsgScreen(null)) }; 1 })
        .then(literal("add")
            .then(literal("at").then(d("x").then(d("y").then(d("z").then(tail(true) { ctx, delay, dist, color, msg ->
                val (x, y, z) = Triple(d(ctx, "x"), d(ctx, "y"), d(ctx, "z"))
                add(PosMsg(x, y, z, delay = delay, distance = dist, color = color, message = msg), "at $x, $y, $z, triggered up to $dist blocks away")
            })))))
            .then(literal("in").then(d("x").then(d("y").then(d("z").then(d("x2").then(d("y2").then(d("z2").then(tail(false) { ctx, delay, _, color, msg ->
                val x = d(ctx, "x"); val y = d(ctx, "y"); val z = d(ctx, "z"); val x2 = d(ctx, "x2"); val y2 = d(ctx, "y2"); val z2 = d(ctx, "z2")
                add(PosMsg(x, y, z, x2, y2, z2, delay, null, color, msg), "in $x, $y, $z to $x2, $y2, $z2")
            }))))))))
            .then(literal("atself").then(tail(true) { _, delay, dist, color, msg ->
                val p = mc.player ?: return@tail
                val x = PosMsgScreen.round(p.x); val y = PosMsgScreen.round(p.y); val z = PosMsgScreen.round(p.z)
                add(PosMsg(x, y, z, delay = delay, distance = dist, color = color, message = msg), "at $x, $y, $z, triggered up to $dist blocks away")
            })))
        .then(literal("remove").then(argument("index", IntegerArgumentType.integer(1)).executes { ctx ->
            val i = IntegerArgumentType.getInteger(ctx, "index")
            val list = PositionalMessages.messages
            if (i > list.size) EngineerClient.msg("§cThere's no message #$i.")
            else { list.removeAt(i - 1); PositionalMessages.changed(); EngineerClient.msg("§7Removed positional message #$i.") }
            1
        }))
        .then(literal("clear").executes { PositionalMessages.messages.clear(); PositionalMessages.changed(); EngineerClient.msg("§7Cleared positional messages."); 1 })
        .then(literal("list").executes {
            val list = PositionalMessages.messages
            if (list.isEmpty()) EngineerClient.msg("§7No positional messages.")
            else EngineerClient.msg("§7Positional messages:\n" + list.withIndex().joinToString("\n") { (i, m) ->
                "§f${i + 1}§7: ${PosMsgScreen.posText(m)}, ${m.delay}ms${m.distance?.takeIf { !m.isBox }?.let { ", ${PosMsgScreen.num(it)} blocks" } ?: ""}, ${m.color}, \"§f${m.message}§7\""
            })
            1
        })
        .then(literal("colors").executes {
            EngineerClient.msg("§7Colors: §f${PositionalMessages.colorNames.joinToString(", ")}§7, or hex like #FF0000.")
            1
        })
}
