package com.devgineerclient.rotation

import com.engineerclient.EcConfig
import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.rotation.EcLog
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.minecraft.network.chat.Component

/**
 * Dynamic Term Roles' part of /ec: role, roles, setup and debug. Brigadier merges these into the /ec tree
 * Engineer Client registers (its log and memreport), so they read as one command.
 */
object RotationCommands {
    fun install() {
        EngineerClient.HELP += listOf(
            "§f /ec role <role>|clear§7 — your P3 starting role; §f/ec roles§7 — everyone's",
            "§f /ec setup§7 — checks the Odin setup Dynamic Term Roles relies on; §f/ec debug",
        )
        EngineerClient.markLines += { P3Rotation.debugLines() }
        EcLog.log("SESSION", "spec v${RotationSpec.graph.version}  starting role: ${RotationSpec.graph.name(EcConfig.data.myStartingRole)}")

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (name in listOf("engineerclient", "EngineerClient", "ec")) dispatcher.register(
                literal(name)
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
            )
        }
    }

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
}
