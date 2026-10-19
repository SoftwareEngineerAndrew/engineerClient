package com.engineerclient.p3sim.run

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Fight
import com.engineerclient.p3sim.Players
import com.engineerclient.p3sim.Roles
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimItems
import com.engineerclient.p3sim.SimServer
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Packs
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Difficulty
import org.slf4j.LoggerFactory

/**
 * Holds the one current [Run] and starts and stops it.
 *
 * Start flow (docs/ads/ARCHITECTURE.md 14): on the client thread [RunLauncher.launch] loads the packs, plans the layout, registers the
 * dungeon region and the clipped arena region with [WorldRegions] and opens the world; once the sim server is up and the player has joined
 * (`Fight.join`) [start] runs on the server thread: NORMAL difficulty, view and simulation distance [ViewPin.DISTANCE], the lag layer, `Fight.setup` for each
 * player (game mode, speed, health, the hotbar), the party at the entrance, the [Run] created and begun, and the "Start" savepoint.
 * A pack problem is a plain chat message ([Packs.userLines]), never an exception.
 */
object Runs {
    private val log = LoggerFactory.getLogger("engineerclient")

    @Volatile var current: Run? = null
        private set

    /** What starting a run answers. [message] is plain English for the player. */
    sealed interface StartResult {
        class Started(val run: Run) : StartResult
        class Failed(val message: String) : StartResult
    }

    /**
     * Starts a run (server thread, in the sim's world) from the launch that is [RunLauncher.pending]: the plan was made before the world,
     * because the dungeon is written while the world's chunks are generated. Without a pending launch there is nothing to start.
     */
    fun start(config: RunConfig): StartResult {
        val pending = RunLauncher.takePending() ?: return StartResult.Failed("Start a run with /ads run (the world has to be built for it).")
        val set = RunLauncher.takePendingPacks()
        // The run pins the set itself once it begins; the launch's pin is let go either way.
        try { return start(pending.first, pending.second, set) } finally { set?.unpin() }
    }

    /** Starts a run for an already planned dungeon whose region the world was built from. Server thread. */
    fun start(config: RunConfig, plan: DungeonPlan, launchPacks: com.engineerclient.p3sim.pack.PackSet? = null): StartResult {
        val server = SimServer.server ?: return StartResult.Failed("The ADS world is not running.")
        if (!server.isSameThread) return StartResult.Failed("A run starts on the sim server's thread.")
        val level = SimServer.level ?: return StartResult.Failed("The ADS world is not running.")
        val humans = Sim.players()
        if (humans.isEmpty()) return StartResult.Failed("Nobody is in the ADS world yet.")
        current?.let { stop(EndReason.STOPPED) }
        // The set the world was built from, when the launch kept it; else whatever is loaded now.
        val packs = launchPacks ?: Packs.current ?: Packs.ensureLoaded()

        // The world: NORMAL difficulty (mobs are real, the player is kept unhurtable in vanilla terms by Fight.setup), view and
        // simulation distance pinned (Hypixel's 15 x 15 chunk window), the lag the player chose.
        server.setDifficulty(Difficulty.NORMAL, true)
        ViewPin.pin()
        server.playerList.setViewDistance(ViewPin.DISTANCE); server.playerList.setSimulationDistance(ViewPin.DISTANCE)
        LagRuntime.runPreset = config.lag; LagRuntime.devPreset = null
        startClock(server, config.seed)

        val spawn = plan.practice?.let { practiceSpawn(it) } ?: entranceSpawn(level, plan, config.seed)
        for (p in humans) {
            // Game mode, speed, health, no knockback; then the clear's kit and armour (the boss kit comes back at the jump to Maxor).
            Fight.setup(p, giveHotbar = false)
            Sim.asPlayer(p) { SimItems.giveClearKit(p); Sim.chat("§fYou can no longer consume or splash any potions during the remainder of this Dungeon run!"); Sim.tp(p, spawn.x, spawn.y, spawn.z, spawn.yaw, 0f) }
            // The chunks the login put round the world's spawn are not Hypixel's window any more: let the client drop the ones the new window leaves out.
            for (after in FORGET_LOGIN_CHUNKS_AFTER) Fight.later(after, "forget login chunks") { forgetLoginChunks(p) }
        }

        val id = "run-" + java.lang.Long.toHexString(config.seed)
        val run = Run(id, config, plan, packs, RunBus(serverThread = { SimServer.server?.isSameThread == true }))
        run.savepoints = RunSavepoints(MinecraftSavepointWorld)
        run.bossStarter = BossHandoff::start
        install(run)
        log.info("[ads] run $id starts: layout ${plan.layoutId}, seed ${config.seed}, ${humans.size} player(s), lag ${config.lag.label}")
        run.begin(Fight.serverTick)
        run.savepoint("Start")
        return StartResult.Started(run)
    }

    /** Server ticks after the party's teleport at which the chunks left over from the login are forgotten (the second catches a chunk that was still arriving). */
    private val FORGET_LOGIN_CHUNKS_AFTER = intArrayOf(3, 40)

    /** The render distance the login is taken to have used when the pin has no record of the player's own (the game's largest default). */
    private const val LOGIN_DISTANCE_GUESS = 16

    /**
     * The world's spawn is where the login puts the player (0.5, 255, 0.5): the game sends the chunks round it, and the teleport to the entrance
     * makes it forget the ones the new window leaves out, but some stay on the client (about 25 to 30 chunks of the arena, for minutes). Hypixel's
     * client holds only the window round the player, so a forget goes out for every chunk of the login window that the player's tracked window
     * does not contain. A chunk the client does not hold is simply ignored by it.
     */
    private fun forgetLoginChunks(player: ServerPlayer) {
        if (player.isRemoved || SimServer.server == null) return
        val view = player.chunkTrackingView
        // The login sent the window of the player's own render distance (the pin has not been on for the chunks that came first).
        val loginDistance = (ViewPin.saved?.first ?: LOGIN_DISTANCE_GUESS).coerceIn(ViewPin.DISTANCE, 32)
        for ((x, z) in ChunkWindow.leftBehind(loginDistance) { x, z -> view.contains(x, z) })
            player.connection.send(net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket(net.minecraft.world.level.ChunkPos(x, z)))
    }

    /**
     * Hypixel's clock runs, from a time of day that is anywhere in the day (the first packet of 510 sessions spreads evenly over the 24000 ticks); the
     * sim's was frozen at noon. The weather stays off. The time of day is drawn from the run's [seed].
     */
    private fun startClock(server: net.minecraft.server.MinecraftServer, seed: Long) {
        server.gameRules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, true, server)
        Sim.command("time set ${java.util.Random(seed xor 0x71BEL).nextInt(24000)}")
    }

    /** Where the party starts: a feet position and the way it faces. */
    data class Spawn(val x: Double, val y: Double, val z: Double, val yaw: Float)

    /**
     * The party's start, as Hypixel puts it: on the outer edge of the entrance tile, a few blocks above its floor, facing into the dungeon
     * (recorded over 180 runs: along the edge at tile min + 15.5, across it at min + 14.5 / 17.5 (inner, y 75.5) or 11.5 / 19.5 (outer, y 76.5),
     * both about equally often). The party then drops onto the floor. Which of the two a run gets is not measured, so it is drawn from [seed].
     * An entrance that is not on the edge of the grid (a made-up layout) keeps the middle of its tile on the floor.
     */
    internal fun entranceSpawn(level: ServerLevel, plan: DungeonPlan, seed: Long = 0L): Spawn {
        val tile = plan.entrance?.tiles?.firstOrNull() ?: plan.rooms.firstOrNull()?.tiles?.firstOrNull()
        val edge = tile?.let { spawnOnEdge(it.x, it.z, outer = java.util.Random(seed xor 0x5A17L).nextBoolean()) }
        if (edge != null) return edge
        val x = (tile?.let { DungeonFrame.tileCentre(it.x) } ?: DungeonFrame.tileCentre(0)) + 0.5
        val z = (tile?.let { DungeonFrame.tileCentre(it.z) } ?: DungeonFrame.tileCentre(0)) + 0.5
        return Spawn(x, floorY(level, x.toInt(), z.toInt()).toDouble(), z, 180f)
    }

    /** A practice run's start: in its one doorway, a block in from the room's edge (the bedrock behind), on the floor, facing into the room. Pure. */
    internal fun practiceSpawn(p: PracticeStart): Spawn {
        val (x, z) = com.engineerclient.p3sim.clear.layout.DoorGeometry.sideCell(p.tx, p.tz, p.side, 15, 1)
        val yaw = when (p.side) { 'N' -> 0f; 'S' -> 180f; 'W' -> 270f; else -> 90f }
        return Spawn(x + 0.5, 69.0, z + 0.5, yaw)
    }

    /** The edge spawn for the tile ([tx], [tz]), or null when the tile is not on the edge of the grid. Pure. */
    internal fun spawnOnEdge(tx: Int, tz: Int, outer: Boolean): Spawn? {
        val last = DungeonFrame.TILES - 1
        val y = if (outer) 76.5 else 75.5
        val minX = DungeonFrame.tileMin(tx).toDouble(); val minZ = DungeonFrame.tileMin(tz).toDouble()
        return when {
            tz == 0 -> Spawn(minX + 15.5, y, minZ + if (outer) 11.5 else 14.5, 0f)
            tz == last -> Spawn(minX + 15.5, y, minZ + if (outer) 19.5 else 17.5, 180f)
            tx == 0 -> Spawn(minX + if (outer) 11.5 else 14.5, y, minZ + 15.5, 270f)
            tx == last -> Spawn(minX + if (outer) 19.5 else 17.5, y, minZ + 15.5, 90f)
            else -> null
        }
    }

    /** First y from the bottom where there is standing room (two free blocks) on something solid; [SPAWN_FALLBACK_Y] when the column is solid or empty. */
    private fun floorY(level: ServerLevel, x: Int, z: Int): Int {
        fun solid(y: Int): Boolean { val p = BlockPos(x, y, z); return !level.getBlockState(p).getCollisionShape(level, p).isEmpty }
        for (y in 62..130) if (solid(y - 1) && !solid(y) && !solid(y + 1)) return y
        return SPAWN_FALLBACK_Y
    }

    /** The floor of a dungeon room is y 68, so a player stands at 69 (the door bodies are y 69..72). */
    const val SPAWN_FALLBACK_Y = 69

    /** Stops the current run (the menu's Stop, leaving the world, a boss practice started from the menu). */
    fun stop(reason: EndReason = EndReason.STOPPED) {
        val r = current ?: return
        r.end(reason)
        current = null
        // No run any more: the clients' bridge goes back to "in boss" for the boss practice that may follow in this world.
        RunWire.broadcast(null)
        // The Minecraft side of the cleanup (the lag layer, the view distance pin, the clear's entities); a hook so this class stays testable headless.
        onStopped(r)
    }

    /** What happens in the game when a run stops. [AdsBootstrap] installs the real one; the headless tests leave it empty. */
    internal var onStopped: (Run) -> Unit = {}

    /** Called every server tick from `Fight.tick` (before the phase). Does nothing with no run. */
    fun tick(serverTick: Int) { current?.tick(serverTick) }

    /** Engine only: makes [run] current (after `begin`). */
    internal fun install(run: Run?) { current = run }

    /** `Fight.join`: a party joiner arrived while a run is on. They get a record and a place at the host's side, and every system hears of it. */
    fun playerJoined(player: ServerPlayer) {
        val run = current ?: return
        if (run.isOver) return
        EngineModule.addHuman(run, player)
        run.bus.post(PlayerJoined(run.t, player.uuid))
    }

    /** `Fight.leave`: a party joiner left. */
    fun playerLeft(player: ServerPlayer) {
        val run = current ?: return
        if (run.isOver) return
        run.state.players.remove(player.uuid)
        run.bus.post(PlayerLeft(run.t, player.uuid))
    }

    /** The class a human plays in the run's party list, spelled as the bots' records spell it (`ARCHER`, `HEALER`...: Odin's enum names), so the leap order and the roster compare equal. */
    internal fun classLabel(player: ServerPlayer): String = Players.classOf(player).name
}
