package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.client.server.IntegratedServer
import net.minecraft.commands.Commands
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.storage.LevelResource

/**
 * The sim's server side: the integrated server of the "p3sim" world, and only that one. Every
 * handler here returns at once for any other server (a dedicated one, another singleplayer world),
 * so nothing of the sim exists outside its own world.
 */
object SimServer {
    /** The running sim server, or null. Read from the client thread too. */
    @Volatile var server: MinecraftServer? = null
        private set

    fun isSim(server: MinecraftServer?): Boolean =
        server is IntegratedServer && server.worldData.levelName == SimWorld.NAME

    val level: ServerLevel? get() = server?.overworld()

    /** The (only) player in the sim. */
    val player: ServerPlayer? get() = server?.playerList?.players?.firstOrNull()

    /** Runs [block] on the sim server's thread (from the client's menu, say), if it is running. */
    fun run(what: String, block: (MinecraftServer) -> Unit) {
        val s = server ?: return
        s.execute { if (server === s) EngineerClient.safely("p3sim $what") { block(s) } }
    }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { s ->
            if (isSim(s)) { server = s; EngineerClient.logger.info("[p3sim] sim server starting") }
        }
        ServerLifecycleEvents.SERVER_STARTED.register { s ->
            if (s !== server) return@register
            EngineerClient.safely("p3sim started") {
                rules(s)
                commands(s)
                SimWorld.markBuilt(s.getWorldPath(LevelResource.ROOT))
                Fight.reset(s)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { s ->
            if (s !== server) return@register
            // Saved as built: no fight entities, every changed block back.
            EngineerClient.safely("p3sim stopping") { Fight.stop(); Party.clear(); Sim.clearEntities(); Blocks.restoreAll() }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { s ->
            if (s !== server) return@register
            server = null
            Arena.unload()
        }
        ServerTickEvents.END_SERVER_TICK.register { s ->
            if (s === server) EngineerClient.safely("p3sim tick") { Fight.tick(s) }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, s ->
            if (s === server) EngineerClient.safely("p3sim join") { Fight.join(handler.player) }
        }
        SimItems.register()
    }

    /**
     * `/pc <message>`, only on the sim's server: party chat as Hypixel shows it (Odin sends its
     * party messages, "Leaped to X!" and the like, this way).
     */
    private fun commands(s: MinecraftServer) {
        s.commands.dispatcher.register(
            Commands.literal("pc").then(Commands.argument("message", StringArgumentType.greedyString()).executes { c ->
                val msg = StringArgumentType.getString(c, "message")
                val name = c.source.player?.gameProfile?.name ?: "You"
                Sim.chat("§9Party §8> §b[MVP§6+§b] $name§f: $msg")
                1
            })
        )
    }

    /** The world's rules: no time, weather, mobs, decay, fire, fall damage; things stay as built. */
    private fun rules(s: MinecraftServer) {
        val r: GameRules = s.gameRules
        r.set(GameRules.ADVANCE_TIME, false, s)
        r.set(GameRules.ADVANCE_WEATHER, false, s)
        r.set(GameRules.SPAWN_MOBS, false, s)
        r.set(GameRules.SPAWN_MONSTERS, false, s)
        r.set(GameRules.SPAWN_PHANTOMS, false, s)
        r.set(GameRules.SPAWN_PATROLS, false, s)
        r.set(GameRules.SPAWN_WANDERING_TRADERS, false, s)
        r.set(GameRules.RANDOM_TICK_SPEED, 0, s)
        r.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, s)
        r.set(GameRules.FALL_DAMAGE, false, s)
        r.set(GameRules.FIRE_DAMAGE, false, s)
        r.set(GameRules.DROWNING_DAMAGE, false, s)
        r.set(GameRules.KEEP_INVENTORY, true, s)
        r.set(GameRules.IMMEDIATE_RESPAWN, true, s)
        r.set(GameRules.SHOW_DEATH_MESSAGES, false, s)
        r.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, s)
        r.set(GameRules.MOB_GRIEFING, false, s)
        r.set(GameRules.TNT_EXPLODES, false, s)
        r.set(GameRules.PLAYER_MOVEMENT_CHECK, false, s)
        r.set(GameRules.ELYTRA_MOVEMENT_CHECK, false, s)
        r.set(GameRules.BLOCK_DROPS, false, s)
        r.set(GameRules.ENTITY_DROPS, false, s)
        r.set(GameRules.MOB_DROPS, false, s)
        r.set(GameRules.SEND_COMMAND_FEEDBACK, false, s)
        r.set(GameRules.COMMAND_BLOCK_OUTPUT, false, s)
        r.set(GameRules.LOCATOR_BAR, false, s)
        r.set(GameRules.RESPAWN_RADIUS, 0, s)
    }
}
