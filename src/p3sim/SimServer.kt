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

    /** A test driver's per-tick hook (end of every server tick), so it acts on exact ticks even while the server sprints. Null in play. */
    @Volatile var devTick: ((MinecraftServer) -> Unit)? = null

    /** The sim's world: named p3sim and opened by the mod (or built by it before), never just any world of that name. */
    fun isSim(server: MinecraftServer?): Boolean =
        server is IntegratedServer && server.worldData.levelName == SimWorld.NAME &&
            (SimWorld.opening || SimWorld.isBuilt(server.getWorldPath(LevelResource.ROOT)))

    /** Is [level] the sim's (server side). */
    @JvmStatic
    fun isSimLevel(level: net.minecraft.world.level.Level): Boolean {
        val s = server ?: return false
        return level is ServerLevel && level.server === s
    }

    val level: ServerLevel? get() = server?.overworld()

    /** The sim's player: the one whose game it is (a party's joiners come after). */
    val player: ServerPlayer? get() {
        val l = server?.playerList?.players ?: return null
        // By this game's own account: a respawn puts the host's new player at the end of the list.
        val me = net.minecraft.client.Minecraft.getInstance().user.profileId
        return l.firstOrNull { it.uuid == me } ?: l.firstOrNull()
    }

    /** Runs [block] on the sim server's thread (from the client's menu, say), if it is running. */
    fun run(what: String, block: (MinecraftServer) -> Unit) {
        val s = server ?: return
        // The host's (a packet handler that threw may have left someone else's actor set).
        s.execute { if (server === s) EngineerClient.safely("p3sim $what") { Sim.asPlayer(null) { block(s) } } }
    }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { s ->
            if (isSim(s)) { server = s; EngineerClient.logger.info("[p3sim] sim server starting"); com.engineerclient.p3sim.run.EngineSpikes.mark("server-starting") }
            SimWorld.opening = false
        }
        ServerLifecycleEvents.SERVER_STARTED.register { s ->
            if (s !== server) return@register
            com.engineerclient.p3sim.run.EngineSpikes.mark("server-started")
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
            // A run ends first (its modules clean up, the view distance pin is released), then the boss sim's own cleanup as before.
            EngineerClient.safely("p3sim stopping run") { com.engineerclient.p3sim.run.Runs.stop() }
            EngineerClient.safely("p3sim stopping") { P3Party.stopHosting(unpublish = false); Fight.stop(); Party.clear(); Sim.clearEntities(); Blocks.restoreAll() }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { s ->
            if (s !== server) return@register
            server = null
            Arena.unload()
            // The regions this world used (a run's dungeon, the clipped arena) go with it; the next world's, registered already, stay.
            com.engineerclient.p3sim.run.WorldRegions.worldStopped()
            com.engineerclient.p3sim.run.ArenaRegion.reset()
            com.engineerclient.p3sim.run.LagRuntime.reset()
        }
        ServerTickEvents.END_SERVER_TICK.register { s ->
            if (s === server) {
                EngineerClient.safely("p3sim tick") { Fight.tick(s) }
                devTick?.let { t -> EngineerClient.safely("ads dev tick") { t(s) } }
                // The lag layer last: it stalls this thread (a freeze) once the tick's work is done.
                EngineerClient.safely("ads lag") { com.engineerclient.p3sim.run.EngineModule.lagTick(s) }
            }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, s ->
            if (s === server) EngineerClient.safely("p3sim join") { Fight.join(handler.player) }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, s ->
            if (s === server) EngineerClient.safely("p3sim leave") { Fight.leave(handler.player) }
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
                // A chat command runs later, outside the packet: as whoever typed it.
                val name = c.source.player?.gameProfile?.name ?: "You"
                // The whole party sees it (sent by whoever typed it).
                Sim.everyone { Sim.chat("§9Party §8> §b[MVP§4+§b] $name§f: $msg") }
                1
            })
        )
        // `/gfs <item> <amount>` (get from sacks): Ender Pearls and Superboom TNT, the two the clear takes from sacks.
        s.commands.dispatcher.register(
            Commands.literal("gfs").then(Commands.argument("what", StringArgumentType.greedyString()).executes { c ->
                val p = c.source.player ?: return@executes 0
                Sim.asPlayer(p) { getFromSacks(p, StringArgumentType.getString(c, "what")) }
                1
            })
        )
    }

    /** The sack items `/gfs` hands out: the name it is asked by (lower case, words joined by _, a plural s dropped), its stack and its stack size. */
    private val SACK_ITEMS = mapOf(
        "ender_pearl" to Triple("Ender Pearl", { SimItems.PEARLS }, 16),
        "superboom_tnt" to Triple("Superboom TNT", { SimItems.SUPERBOOM }, 64),
        "superboom" to Triple("Superboom TNT", { SimItems.SUPERBOOM }, 64),
    )

    /**
     * `/gfs ender_pearl 16`: tops up the stacks you have and fills empty slots with up to [what]'s amount (64 when none is given), as Hypixel's
     * "Moved N Ender Pearl from your Sacks to your inventory." says (recorded). The sacks never run out.
     */
    private fun getFromSacks(p: net.minecraft.server.level.ServerPlayer, what: String) {
        val words = what.trim().split(Regex("\\s+"))
        val amount = words.last().toIntOrNull()
        val key = (if (amount != null) words.dropLast(1) else words).joinToString("_").lowercase().removeSuffix("s")
        val (name, make, max) = SACK_ITEMS[key] ?: run { Sim.chat("§cCouldn't find an item in your Sacks with that name!"); return }
        val want = (amount ?: 64).coerceAtLeast(1)
        val id = SimItems.idOf(make())
        val inv = p.inventory
        var left = want
        for (i in 0 until 36) {
            if (left == 0) break
            val s = inv.getItem(i)
            if (!s.isEmpty && SimItems.idOf(s) == id && s.count < max) { val n = minOf(left, max - s.count); s.grow(n); left -= n }
        }
        for (i in 0 until 36) {
            if (left == 0) break
            if (inv.getItem(i).isEmpty) { val n = minOf(left, max); inv.setItem(i, make().also { it.count = n }); left -= n }
        }
        val moved = want - left
        if (moved == 0) { Sim.chat("§cYou don't have enough space in your inventory!"); return }
        p.inventoryMenu.broadcastChanges()
        Sim.chat("§aMoved §f$moved $name§a from your Sacks to your inventory.")
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
