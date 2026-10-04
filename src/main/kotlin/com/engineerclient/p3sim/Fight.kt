package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.GameType
import kotlin.random.Random

/**
 * The fight: which phase runs, the server tick, delayed actions, and the player's setup. Server
 * thread only; [SimServer] calls in here only for the sim's own server.
 */
object Fight {
    /** One phase of the boss (or a part of one, like a P3 section). */
    abstract class Phase(val name: String) {
        /** Server ticks since this phase started. */
        var t = 0
        open fun start() {}
        open fun tick() {}
        open fun stop() {}
        /** Where "Restart" puts you back to. */
        abstract val restart: Start
    }

    /** What the menu can start. */
    enum class Start(val label: String) {
        P1("P1 Maxor"), P2("P2 Storm"), P3("P3 Goldor"), S1("S1"), S2("S2"), S3("S3"), S4("S4"), CORE("Core"), P4("P4 Necron"),
    }

    var phase: Phase? = null
        private set

    /** The phase before this one, when the fight went on by itself (P1 -> P2 ...); null after a menu start. */
    var previous: Phase? = null
        private set

    /** Every terminal opens as this type (the menu's "Terminals: ..."), or random when null. */
    val forcedTerminal: Terminals.Type? get() = P3Sim.forcedTerminal

    /** STANDS-14: the tick (n % 20) stand names are set on this run: 18 (sent on 19, 21 of 36 runs) or 17 (sent on 18, 15 of 36). */
    var refreshPhase = 18
        private set

    /** Server ticks since the sim started (the ping ids Odin counts as server ticks). */
    var serverTick = 0
        private set

    private class Later(val at: Int, val what: String, val epoch: Int, val run: () -> Unit)
    /** Bumped by every stop: actions queued before it never run after. */
    private var epoch = 0
    private val later = ArrayList<Later>()

    /** Runs [run] [ticks] server ticks from now (0 = later this tick). Cleared when a phase starts. */
    fun later(ticks: Int, what: String = "later", run: () -> Unit) {
        later += Later(serverTick + ticks.coerceAtLeast(0), what, epoch, run)
    }

    /** The player's ping, in server ticks: what their clicks and items wait before the server acts. */
    val pingTicks: Int get() = (P3Sim.ping.toInt() + 25) / 50

    private class Timed(val dueNs: Long, val what: String, val epoch: Int, val run: () -> Unit)
    private val timed = ArrayList<Timed>()

    /**
     * One simulated delay in real ms, sub-tick: the setting, plus (Ping Jitter) the spread recorded on
     * Hypixel (PING-03: round trip p10 30 / median 33 / p90 63 / p99 73 ms, with rare 150 ms+ spikes), as an
     * offset from the 33 ms median so it doesn't scale with the setting. 0 with no ping.
     */
    fun pingMs(): Double {
        val base = P3Sim.ping.toDouble()
        if (base <= 0.0) return 0.0
        if (!P3Sim.jitter) return base
        val u = Random.nextDouble()
        // Quantile table of RTT / median: (cumulative probability, factor).
        val q = JITTER_Q
        var f = q.last()[1]
        for (i in 1 until q.size) if (u <= q[i][0]) { val a = q[i - 1]; val b = q[i]; f = a[1] + (b[1] - a[1]) * (u - a[0]) / (b[0] - a[0]); break }
        return (base + (f - 1.0) * 33.0).coerceAtLeast(base * 0.5)
    }
    private val JITTER_Q = arrayOf(
        doubleArrayOf(0.0, 0.85), doubleArrayOf(0.10, 0.91), doubleArrayOf(0.50, 1.0), doubleArrayOf(0.90, 1.9),
        doubleArrayOf(0.99, 2.2), doubleArrayOf(0.998, 3.5), doubleArrayOf(1.0, 8.0),
    )

    /** Runs [run] after the simulated ping, in real time: at the first server tick on or after the delay (at once with none). */
    fun afterPing(what: String, run: () -> Unit) {
        val ms = pingMs()
        val p = phase
        if (ms <= 0.0) run()
        else timed += Timed(System.nanoTime() + (ms * 1e6).toLong(), what, epoch) { if (phase === p) run() }
    }

    /** (xRot, yRot) as of the end of the last server tick: the rotation of the movement packet before a click's (JERRY-03). */
    var lastRot: Pair<Float, Float> = 0f to 0f
        private set
    private val posHistory = java.util.ArrayDeque<Pair<Long, net.minecraft.world.phys.Vec3>>()

    /**
     * Where the server sees [p]: where they were one one-way latency ago (PING-08: its checks run on the
     * position packets that arrived, so about ping/2 late). Live position with no ping.
     */
    fun seenPos(p: ServerPlayer): net.minecraft.world.phys.Vec3 {
        val live = p.position()
        val ms = P3Sim.ping / 2.0
        if (ms <= 0.0 || posHistory.isEmpty()) return live
        val target = System.nanoTime() - (ms * 1e6).toLong()
        var prev: Pair<Long, net.minecraft.world.phys.Vec3>? = null
        for (e in posHistory) {
            if (e.first >= target) {
                val a = prev ?: return e.second
                val t = (target - a.first).toDouble() / (e.first - a.first).coerceAtLeast(1)
                return a.second.lerp(e.second, t.coerceIn(0.0, 1.0))
            }
            prev = e
        }
        val a = prev ?: return live
        val t = (target - a.first).toDouble() / (System.nanoTime() - a.first).coerceAtLeast(1)
        return a.second.lerp(live, t.coerceIn(0.0, 1.0))
    }

    fun reset(server: MinecraftServer) {
        serverTick = 0
        epoch++
        later.clear()
        timed.clear(); posHistory.clear()
        Terminals.closeAll()
        SimItems.reset()
        phase = null
        BossBar.hide()
        Sim.clearEntities()
        Sim.command("time set noon")
        Sim.command("weather clear")
        Blocks.restoreAll()
    }

    fun stop() {
        phase?.let { EngineerClient.safely("p3sim stop ${it.name}") { it.stop() } }
        phase = null
        epoch++
        later.clear()
        timed.clear()
        Terminals.closeAll()
        // The Dungeonbreaker's broken blocks would grow back into the next start's world (a gate a
        // later start has open); cooldowns start fresh, as the masks' do.
        SimItems.reset()
        BossBar.hide()
    }

    fun join(player: ServerPlayer) {
        setup(player)
        if (phase == null) {
            Sim.tp(player, Spots.LOBBY.x, Spots.LOBBY.y, Spots.LOBBY.z, Spots.LOBBY.yaw, Spots.LOBBY.pitch)
            later(20, "welcome") {
                Sim.note("Welcome to P3 Sim. §fRight click the Nether Star§7 (or /p3sim) for the menu.")
                if (P3Sim.autoStart) start(Start.P3)
            }
        }
    }

    /** Game mode, Hypixel speed, no knockback, no hunger, the boss hotbar. */
    fun setup(player: ServerPlayer) {
        // Hypixel: SURVIVAL (1111/1111 samples). Blocks stay whole through Sim.guardBlocks + DungeonbreakerSimMixin.
        if (player.gameMode() != GameType.CREATIVE) player.setGameMode(GameType.SURVIVAL)
        Sim.guardBlocks()
        applySpeed(player)
        player.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        player.getAttribute(Attributes.STEP_HEIGHT)?.baseValue = 0.6
        player.isInvulnerable = true
        // Hypixel main: 40 hp with 16 absorption (MOVE-05).
        player.getAttribute(Attributes.MAX_HEALTH)?.baseValue = 40.0
        player.health = 40f
        player.absorptionAmount = 16f
        player.removeEffect(MobEffects.SATURATION)
        // Invulnerable players are never hungry or hurt; the effects are Hypixel's (night vision 1, or with haste 0 + mining fatigue 255).
        player.addEffect(MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false, false))
        player.addEffect(MobEffectInstance(MobEffects.HASTE, -1, 0, false, false, false))
        player.addEffect(MobEffectInstance(MobEffects.MINING_FATIGUE, -1, 255, false, false, false))
        player.foodData.setFoodLevel(20); player.foodData.setSaturation(20f)
        SimItems.giveHotbar(player)
    }

    /**
     * Odin's 4th device solver (Arrows Device) keeps the blocks hit, the target and "complete" until
     * a world load; a sim restart is a new P3, so it starts clean too.
     */
    private fun resetArrowsDevice() {
        val c = com.odtheking.odin.features.impl.boss.ArrowsDevice::class.java
        fun field(name: String) = c.getDeclaredField(name).apply { isAccessible = true }
        (field("markedPositions").get(null) as? MutableSet<*>)?.clear()
        field("targetPosition").set(null, null)
        field("isDeviceComplete").setBoolean(null, false)
        field("optimalAimPositions").set(null, emptyList<Any>())
    }

    /** Your speed: the setting is without Black Cat; Black Cat adds 100 (and 100 to the cap), Phoenix out adds nothing. */
    fun applySpeed(player: ServerPlayer) {
        // Black Cat +100; the Racing Helmet +100 more (rec2 loadouts: Cat terms 650, Phoenix terms 550; Terror and Mask terms with Cat 550).
        val racing = SimItems.idOf(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)) == "RACING_HELMET"
        val speed = P3Sim.speed + (if (P3Sim.phoenix) 0 else 100) + (if (racing) 100 else 0)
        player.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = speed.coerceAtLeast(100).toDouble() / 1000.0
    }

    /** What the menu last started (the Restart keybind starts it again). */
    @Volatile var lastStart = Start.P3
        private set

    /** Starts [what] from its beginning (stopping whatever ran). */
    fun start(what: Start) {
        val player = Sim.player ?: return
        lastStart = what
        stop()
        later.clear()
        Sim.clearEntities()
        // The bots too: a start that spawns none (P2, P4 with Party Bots off) must not see the last run's.
        Party.clear()
        Blocks.restoreAll()
        // The world as the phases before this one leave it.
        Blocks.prepare(what)
        // Every start (and restart) is with Black Cat out; the Pet Rod or a Phoenix proc swaps it.
        P3Sim.phoenixS.value = false
        setup(player)
        Bows.start()
        Masks.reset()
        Lava.reset()
        refreshPhase = if (kotlin.random.Random.nextInt(36) < 21) 18 else 17
        Stats.runStart = if (what == Start.P1) serverTick else -1
        previous = null
        // Our splits: a fresh run from this phase, the ones before it at your Pace times. Queued on
        // the client before any of this fight's lines can reach it (it starts Odin's run too).
        val (split, termsDone) = when (what) {
            Start.P1 -> com.engineerclient.splits.SplitTracker.MAXOR to 0
            Start.P2 -> com.engineerclient.splits.SplitTracker.STORM to 0
            Start.P3, Start.S1 -> com.engineerclient.splits.SplitTracker.TERMS to 0
            Start.S2 -> com.engineerclient.splits.SplitTracker.TERMS to 1
            Start.S3 -> com.engineerclient.splits.SplitTracker.TERMS to 2
            Start.S4 -> com.engineerclient.splits.SplitTracker.TERMS to 3
            Start.CORE -> com.engineerclient.splits.SplitTracker.GOLDOR to 0
            Start.P4 -> com.engineerclient.splits.SplitTracker.NECRON to 0
        }
        EngineerClient.mc.execute {
            EngineerClient.safely("p3sim splits") { com.engineerclient.splits.DungeonSplits.simStart(split, termsDone) }
            EngineerClient.safely("p3sim arrows device") { resetArrowsDevice() }
        }
        Recorder.begin(what.label)
        // ODIN-07: no "Starting in 1 second." line (Hypixel has none in P3): SimOdinSplits starts Odin's run directly.
        val p: Phase = when (what) {
            Start.P1 -> P1Maxor()
            Start.P2 -> P2Storm()
            // From Storm's death: 5.1 s to Goldor's line, as in the game.
            Start.P3, Start.S1 -> StormEnd()
            Start.S2 -> GoldorPhase(2)
            Start.S3 -> GoldorPhase(3)
            Start.S4 -> GoldorPhase(4)
            Start.CORE -> GoldorPhase(5)
            Start.P4 -> P4Necron()
        }
        begin(p)
    }

    /** Hands over to the next phase (the fight going on by itself: P1 -> P2 -> ...). */
    fun begin(p: Phase) {
        phase?.let { if (it !== p) EngineerClient.safely("p3sim stop ${it.name}") { it.stop() } }
        previous = phase
        phase = p
        p.t = 0
        p.start()
    }

    /** Ends the fight (the menu's Stop): everything back to how it was built. */
    fun end() {
        Recorder.finish()
        stop()
        Sim.clearEntities()
        Blocks.restoreAll()
        Party.clear()
    }

    fun tick(server: MinecraftServer) {
        serverTick++
        // Hypixel pings every client each server tick; Odin (and this mod) count those as server
        // ticks: Simon Says, terminal first-click protection, splits all run on them.
        server.playerList.players.forEach { it.connection.send(ClientboundPingPacket(serverTick)) }
        if (later.isNotEmpty()) {
            val due = later.filter { it.at <= serverTick }
            later.removeAll(due.toSet())
            due.forEach { if (it.epoch == epoch) EngineerClient.safely("p3sim ${it.what}") { it.run() } }
        }
        if (timed.isNotEmpty()) {
            val nowNs = System.nanoTime()
            val due = timed.filter { it.dueNs <= nowNs }.sortedBy { it.dueNs }
            timed.removeAll(due.toSet())
            due.forEach { if (it.epoch == epoch) EngineerClient.safely("p3sim ${it.what}") { it.run() } }
        }
        Sim.player?.let { pl ->
            lastRot = pl.xRot to pl.yRot
            posHistory.addLast(System.nanoTime() to pl.position())
            while (posHistory.size > 40) posHistory.removeFirst()
        }
        Blocks.tick()
        Terminals.tick()
        SimItems.tick()
        EngineerClient.safely("p3sim bows") { Bows.tick() }
        if (P3Sim.lava) Sim.player?.let { pl -> EngineerClient.safely("p3sim lava") { Lava.tick(pl) } }
        val p = phase
        if (p == null) { Recorder.finish(); return }
        // The run recorder covers P1-P3 and stops at Necron; his phase still has to tick.
        if (p is P4Necron) Recorder.finish()
        EngineerClient.safely("p3sim ${p.name}") { p.tick() }
        p.t++
        Party.tick()
        if (p !is P4Necron) EngineerClient.safely("p3sim recorder") { Recorder.tick(serverTick) }
    }
}
