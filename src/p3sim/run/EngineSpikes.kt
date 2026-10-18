package com.engineerclient.p3sim.run

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Arena
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimServer
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.zombie.Zombie
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * The engine spikes (BRIEF stage 3): three measurements the rest of the plan is waiting on, run from `/ads dev ...` and reported in
 * chat and in the log (`[ads spike]` lines, so they can be searched for in latest.log).
 *
 *  - `difficulty`: the boss sim as it is, on NORMAL instead of PEACEFUL: does anything vanish, target the player or hurt them?
 *  - `cost`: time to playable with 169 roofed dungeon chunks next to the arena, the server's milliseconds per tick with 0, 300 and 750 static
 *    mobs at the pinned simulation distance (6), the client's frame rate with 430 entities in view, and how long the arena's resync takes.
 *  - `lag`: the lag layer's real tick timeline, to confirm the freeze-then-burst shape and the loss per minute.
 *
 * Everything that touches the world runs on the sim server's thread from [tick]; the two client-side readings (the frame rate, the moment the
 * world becomes playable) come from [clientTick]. Nothing here runs unless a command started it.
 */
object EngineSpikes {
    private val log = LoggerFactory.getLogger("engineerclient")

    /** A line to the log and to the chat of everyone in the sim (when a sim server runs). Any thread. */
    fun say(text: String) {
        log.info("[ads spike] $text")
        val s = SimServer.server ?: return
        s.execute { if (SimServer.server === s) EngineerClient.safely("ads spike say") { Sim.asPlayer(null) { Sim.everyone { Sim.note(text) } } } }
    }

    // ---- marks (time to playable) ----------------------------------------------------------------------------------------------------

    private val marks = LinkedHashMap<String, Long>()
    @Volatile private var markingWorld = false

    /** Starts a stopwatch for a world build: "command" now; [mark] adds the steps. Client thread. */
    fun beginWorldClock() { synchronized(marks) { marks.clear(); marks["command"] = System.nanoTime(); markingWorld = true } }

    /** Notes that [name] happened now, when a world build is being timed. Any thread. */
    fun mark(name: String) { if (markingWorld) synchronized(marks) { marks.putIfAbsent(name, System.nanoTime()) } }

    /** The steps as "from -> to: ms" lines, or empty. */
    fun markLines(): List<String> = synchronized(marks) {
        val keys = marks.keys.toList()
        keys.zipWithNext().map { (a, b) -> "  ${a} -> ${b}: ${(marks.getValue(b) - marks.getValue(a)) / 1_000_000} ms" } +
            (if (keys.size >= 2) listOf("  total, command -> ${keys.last()}: ${(marks.getValue(keys.last()) - marks.getValue(keys.first())) / 1_000_000} ms") else emptyList())
    }

    /** The world build has been reported: later joins are not part of it. */
    fun endWorldClock() { markingWorld = false }

    // ---- the per-tick driver ---------------------------------------------------------------------------------------------------------

    private var difficultyAudit: DifficultyAudit? = null
    private var cost: CostRun? = null
    private var lagWatch: LagWatch? = null

    /** `/ads dev cost` asked for the measurements to follow the world build (the join starts them). */
    @Volatile var costOnJoin: CostOptions? = null

    /** Called every sim server tick, ahead of the lag layer. Server thread. */
    fun tick(server: MinecraftServer) {
        val level = server.overworld()
        difficultyAudit?.let { if (it.tick(level)) difficultyAudit = null }
        cost?.let { if (it.tick(server, level)) cost = null }
        lagWatch?.let { if (it.tick()) lagWatch = null }
    }

    /** `Fight.join` (the host): a cost build waiting for the world starts its measurements a few seconds after the join. */
    fun joined(player: ServerPlayer) {
        mark("join")
        val opts = costOnJoin ?: return
        costOnJoin = null
        cost = CostRun(opts, afterTicks = 100)
        log.info("[ads spike] cost: the world is up for ${player.gameProfile.name}; measuring in 5 s")
    }

    // ---- client-side readings --------------------------------------------------------------------------------------------------------

    @Volatile private var fpsWindow = 0
    private val fpsSamples = ArrayList<Int>()
    private var waitingForPlayable = false

    /** Asks for a "playable" mark: the first client tick with the world loaded, a player and no loading screen. Client thread. */
    fun expectPlayable() { waitingForPlayable = true }

    /** Every client tick (registered by [EngineModule.onInit]). Client thread. */
    fun clientTick(mc: net.minecraft.client.Minecraft) {
        if (waitingForPlayable && mc.level != null && mc.player != null && mc.screen == null && markingWorld) { waitingForPlayable = false; mark("playable") }
        if (fpsWindow > 0) { synchronized(fpsSamples) { fpsSamples += mc.fps }; fpsWindow-- }
    }

    /** Starts collecting the client's frame rate for [ticks] client ticks. */
    internal fun collectFps(ticks: Int) { synchronized(fpsSamples) { fpsSamples.clear() }; fpsWindow = ticks }
    internal fun fpsDone() = fpsWindow <= 0
    internal fun fpsStats(): String = synchronized(fpsSamples) {
        if (fpsSamples.isEmpty()) return "no frames sampled"
        val s = fpsSamples.sorted()
        "mean ${"%.0f".format(s.average())} fps, 1% low ${s[(s.size * 0.01).toInt()]} fps, min ${s.first()} fps over ${s.size} ticks"
    }

    // ---- difficulty -----------------------------------------------------------------------------------------------------------------

    /**
     * Puts the world on [to] and watches the boss sim for [ticks] ticks: which sim mobs vanish, which have a target, and what happens to the
     * player's health. Meant to be started with a boss phase running (`/ads p1`); the report says so when nothing was there to watch.
     */
    fun difficulty(server: MinecraftServer, to: Difficulty, ticks: Int = 400) {
        val level = server.overworld()
        val before = server.worldData.difficulty
        server.setDifficulty(to, true)
        say("Difficulty ${before.name} -> ${to.name}. Watching the sim's mobs for ${ticks / 20} s.")
        difficultyAudit = DifficultyAudit(ticks).also { it.begin(level) }
    }

    private class DifficultyAudit(private val total: Int) {
        private var n = 0
        private val seen = HashMap<java.util.UUID, String>()
        private val targeted = HashSet<java.util.UUID>()
        private var healthStart = 0f
        private var healthMin = Float.MAX_VALUE

        private fun simEntities(level: ServerLevel): List<Entity> {
            val out = ArrayList<Entity>()
            level.allEntities.forEach { if (it.entityTags().contains(Sim.TAG)) out += it }
            return out
        }

        fun begin(level: ServerLevel) {
            for (e in simEntities(level)) seen[e.uuid] = e.type.toShortString()
            healthStart = Sim.host?.health ?: 0f; healthMin = healthStart
        }

        fun tick(level: ServerLevel): Boolean {
            n++
            val now = simEntities(level)
            for (e in now) {
                seen.putIfAbsent(e.uuid, e.type.toShortString())
                if (e is Mob && e.target != null) targeted += e.uuid
            }
            Sim.host?.let { healthMin = minOf(healthMin, it.health) }
            if (n < total) return false
            val alive = now.map { it.uuid }.toSet()
            val gone = seen.filterKeys { it !in alive }.values.groupingBy { it }.eachCount()
            val mobs = now.count { it is Mob }
            say("Difficulty audit, ${total / 20} s: ${seen.size} sim entities seen (${mobs} mobs now), ${gone.values.sum()} gone" +
                (if (gone.isEmpty()) "" else " (" + gone.entries.joinToString { "${it.key} x${it.value}" } + ")") +
                ", ${targeted.size} mobs had a target. Your health ${"%.1f".format(healthStart)} -> lowest ${"%.1f".format(healthMin)}.")
            if (seen.isEmpty()) say("Nothing of the sim was in the world to watch: start a boss phase (/ads p1) and run this again.")
            return true
        }
    }

    // ---- cost -----------------------------------------------------------------------------------------------------------------------

    /** What `/ads dev cost` may be asked: [pin] false leaves the player's own distances (to compare with 5), [quick] shorter windows. */
    class CostOptions(val pin: Boolean = true, val quick: Boolean = false)

    /** Starts the measurements in the world that is running now (the stub dungeon's, or a run's). Server thread. */
    fun costNow(opts: CostOptions) { cost = CostRun(opts, afterTicks = 20); say("Cost measurements start in a second. Stand still in a room.") }

    private class CostRun(private val opts: CostOptions, afterTicks: Int) {
        private enum class Step { WAIT, LOAD, BASE, M300, M750, VIEW, RESYNC, DONE }
        private var loadStart = 0L
        private var step = Step.WAIT
        private var left = afterTicks
        private val spawned = ArrayList<Entity>()
        private val lines = ArrayList<String>()
        private val window = 100
        private val settle = if (opts.quick) 20 else 40

        private fun mspt(server: MinecraftServer): String {
            val t = server.tickTimesNanos.filter { it > 0 }.sorted()
            if (t.isEmpty()) return "no samples"
            return "mean ${"%.2f".format(t.average() / 1e6)} ms, p95 ${"%.2f".format(t[(t.size * 0.95).toInt().coerceAtMost(t.size - 1)] / 1e6)} ms, max ${"%.2f".format(t.last() / 1e6)} ms (50 ms is a full tick)"
        }

        fun tick(server: MinecraftServer, level: ServerLevel): Boolean {
            val player = Sim.host
            if (player == null) return false
            when (step) {
                Step.WAIT -> if (--left <= 0) {
                    if (opts.pin) { ViewPin.pin(); server.playerList.setViewDistance(ViewPin.DISTANCE); server.playerList.setSimulationDistance(ViewPin.DISTANCE) }
                    // In the made-up dungeon: to the middle room, so the chunks round it (roofed rooms, sea lanterns) are the ones that load.
                    if (WorldRegions.regions.any { it.name == StubDungeon.NAME }) {
                        val c = com.engineerclient.p3sim.pack.DungeonFrame.tileCentre(2) + 0.5
                        Sim.asPlayer(player) { Sim.tp(player, c, StubDungeon.FLOOR_Y + 1.0, c, 0f, 0f) }
                    }
                    loadStart = System.nanoTime(); left = 20 * 60; step = Step.LOAD
                }
                Step.LOAD -> {
                    val r = 70
                    val here = BlockPos.containing(player.x, player.y, player.z)
                    val ready = listOf(-r to -r, -r to r, r to -r, r to r, 0 to 0).all { (dx, dz) -> level.isLoaded(here.offset(dx, 0, dz)) }
                    if (ready || --left <= 0) {
                        lines += "Chunks round you: ${if (ready) "loaded" else "NOT all loaded after 60 s"} in ${(System.nanoTime() - loadStart) / 1_000_000} ms after the move."
                        lines += "View distance ${server.playerList.viewDistance}, simulation distance ${server.playerList.simulationDistance}, ${level.chunkSource.loadedChunksCount} chunks loaded, difficulty ${server.worldData.difficulty.name}."
                        left = settle + window; step = Step.BASE
                    }
                }
                Step.BASE -> if (--left <= 0) {
                    lines += "0 mobs: ${mspt(server)}."
                    spawnStatic(level, player, 300); lines += "(spawned ${spawned.size} static mobs)"
                    left = settle + window; step = Step.M300
                }
                Step.M300 -> if (--left <= 0) {
                    lines += "300 static mobs: ${mspt(server)}."
                    spawnStatic(level, player, 450); lines += "(now ${spawned.size} static mobs)"
                    left = settle + window; step = Step.M750
                }
                Step.M750 -> if (--left <= 0) {
                    lines += "750 static mobs: ${mspt(server)}."
                    spawned.forEach { it.discard() }; spawned.clear()
                    spawnView(level, player, 330, 100)
                    EngineSpikes.collectFps(if (opts.quick) 100 else 200)
                    left = 1; step = Step.VIEW
                }
                Step.VIEW -> if (EngineSpikes.fpsDone()) {
                    lines += "430 entities in view (330 mobs, 100 armor stands): client ${EngineSpikes.fpsStats()}. Server ${mspt(server)}."
                    spawned.forEach { it.discard() }; spawned.clear()
                    step = Step.RESYNC
                }
                Step.RESYNC -> {
                    val t0 = System.nanoTime()
                    val fixed = Arena.resync(level)
                    lines += "Arena.resync: ${(System.nanoTime() - t0) / 1_000_000} ms ($fixed blocks fixed)."
                    step = Step.DONE
                }
                Step.DONE -> {
                    val head = "Cost report (${if (opts.pin) "distance pinned to ${ViewPin.DISTANCE}" else "the player's own distances"}):"
                    EngineSpikes.say(head); lines.forEach { EngineSpikes.say("  $it") }
                    EngineSpikes.markLines().takeIf { it.isNotEmpty() }?.let { EngineSpikes.say("Time to playable:"); it.forEach(EngineSpikes::say) }
                    EngineSpikes.endWorldClock()
                    return true
                }
            }
            return false
        }

        /** [n] zombies with no AI, persistent, standing on the floor within 64 blocks of the player (all in loaded chunks). */
        private fun spawnStatic(level: ServerLevel, around: ServerPlayer, n: Int) {
            var made = 0; var tries = 0
            while (made < n && tries < n * 30) {
                tries++
                val x = around.x + Random.nextDouble(-64.0, 64.0); val z = around.z + Random.nextDouble(-64.0, 64.0)
                val pos = BlockPos.containing(x, around.y, z)
                if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir || !level.getBlockState(pos.below()).isSolid) continue
                val e = Zombie(EntityType.ZOMBIE, level)
                e.isNoAi = true; e.setPersistenceRequired(); e.isSilent = true
                e.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, Random.nextFloat() * 360f, 0f)
                spawned += Sim.spawn(e); made++
            }
        }

        /** Zombies and stands within 13 blocks of the player (one room), so that all of them are in view. */
        private fun spawnView(level: ServerLevel, around: ServerPlayer, mobs: Int, stands: Int) {
            var made = 0; var tries = 0
            while (made < mobs + stands && tries < (mobs + stands) * 30) {
                tries++
                val pos = BlockPos.containing(around.x + Random.nextDouble(-13.0, 13.0), around.y, around.z + Random.nextDouble(-13.0, 13.0))
                if (!level.getBlockState(pos).isAir || !level.getBlockState(pos.below()).isSolid) continue
                val e: Entity = if (made < mobs) Zombie(EntityType.ZOMBIE, level).also { it.isNoAi = true; it.setPersistenceRequired(); it.isSilent = true }
                    else ArmorStand(EntityType.ARMOR_STAND, level).also { it.isSilent = true; it.setNoGravity(true) }
                e.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, Random.nextFloat() * 360f, 0f)
                spawned += Sim.spawn(e); made++
            }
        }
    }

    // ---- lag --------------------------------------------------------------------------------------------------------------------------

    /** Turns the lag layer to [preset] for this session and reports the tick timeline after [seconds] (0: no report). */
    fun lag(preset: LagPreset, seconds: Int) {
        LagRuntime.devPreset = preset
        LagRuntime.stats.clear()
        val loss = LagPresets.lossPerMinute(preset, com.engineerclient.p3sim.pack.Packs.current?.runstate?.lag?.tiers)
        say(if (preset == LagPreset.OFF) "Lag layer off." else "Lag layer: ${preset.label}, target ${"%.1f".format(loss)} ticks lost a minute. Collecting ${seconds} s; the report follows. ${if (LagRuntime.canForget) "" else "(The server's tick clock cannot be reached here, so lost ticks will be caught up in full.)"}")
        lagWatch = if (preset != LagPreset.OFF && seconds > 0) LagWatch(System.nanoTime() + seconds * 1_000_000_000L, loss) else null
    }

    /** Prints the timeline of the last [seconds] seconds as it stands. */
    fun lagReport(seconds: Int, target: Double? = null) {
        val r = LagRuntime.stats.report(seconds.toDouble())
        if (r == null) { say("No ticks recorded yet."); return }
        say("Ticks, last ${"%.0f".format(r.seconds)} s: ${r.ticks} ran, ${"%.1f".format(r.lostTicks)} lost = ${"%.1f".format(r.lostPerMinute)} a minute" + (target?.let { " (target ${"%.1f".format(it)})" } ?: "") + ".")
        say("Freezes of 100 ms or more: ${r.freezes.size}, longest ${"%.0f".format(r.longestMs)} ms. Of the ticks they missed, ${"%.0f".format(r.recoveredShare * 100)}% ran again in the bursts.")
        for (f in r.freezes.sortedByDescending { it.gapMs }.take(5)) {
            say("  gap ${"%.0f".format(f.gapMs)} ms (missed ${"%.1f".format(f.missedTicks)}), then ${f.burstTicks} ticks " + (if (f.burstTicks > 0) "${"%.1f".format(f.burstMeanMs)} ms apart" else "at the normal pace"))
        }
        val big = r.freezes.filter { it.gapMs >= 500 }
        say(if (big.isEmpty()) "Freeze-then-burst: no freeze of 500 ms or more yet; run longer."
            else "Freeze-then-burst: ${if (big.all { it.burstTicks > 0 }) "yes, every long freeze was followed by a burst" else "NO: ${big.count { it.burstTicks == 0 }} of ${big.size} long freezes had no burst"}.")
    }

    private class LagWatch(private val atNs: Long, private val target: Double) {
        fun tick(): Boolean {
            if (System.nanoTime() < atNs) return false
            lagReport(60, target)
            return true
        }
    }
}
