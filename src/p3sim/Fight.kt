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

    /** The tick (n % 20) stand names are set on this run: 18 (sent on 19, slightly more common) or 17 (sent on 18). */
    var refreshPhase = 18
        private set

    /** Server ticks since the sim started (the ping ids Odin counts as server ticks). */
    var serverTick = 0
        private set

    /** A queued action, and whose it is ([Sim.asPlayer]: a party player's click stays theirs when it lands). */
    private class Later(val at: Int, val what: String, val epoch: Int, val run: () -> Unit) {
        val actor = Sim.acting
        val all = Sim.toAll
        fun go() = Sim.asPlayer(actor, all, run)
    }
    /** Bumped by every stop: actions queued before it never run after. */
    private var epoch = 0
    private val later = ArrayList<Later>()

    /** Runs [run] [ticks] server ticks from now (0 = later this tick), as whoever queued it. Cleared when a phase starts. */
    fun later(ticks: Int, what: String = "later", run: () -> Unit) {
        later += Later(serverTick + ticks.coerceAtLeast(0), what, epoch, run)
    }

    /** The player's ping, in server ticks: what their clicks and items wait before the server acts. */
    val pingTicks: Int get() = (P3Sim.ping.toInt() + 25) / 50

    private class Timed(val dueNs: Long, val what: String, val epoch: Int, val run: () -> Unit) {
        val actor = Sim.acting
        val all = Sim.toAll
        fun go() = Sim.asPlayer(actor, all, run)
    }
    private val timed = ArrayList<Timed>()

    /**
     * One simulated delay in real ms, sub-tick: the setting, plus (Ping Jitter) the spread recorded on
     * Hypixel (round trip p10 30 / median 33 / p90 63 / p99 73 ms, with rare 150 ms+ spikes), as an
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
    fun afterPing(what: String, run: () -> Unit) = afterMs(pingMs(), what, run)

    /** Runs [run] [ms] real ms from now, at the first server tick on or after it (at once for 0). */
    fun afterMs(ms: Double, what: String, run: () -> Unit) {
        val p = phase
        if (ms <= 0.0) run()
        else timed += Timed(System.nanoTime() + (ms * 1e6).toLong(), what, epoch) { if (phase === p) run() }
    }

    /**
     * (xRot, yRot) at the head of the last handleUseItem, before it snaps the player to the packet's rotation: the
     * last movement packet's rotation, which Hypixel aims a use_item's Jerry-chine with. Set by UseItemRotSimMixin.
     */
    var lastRot: Pair<Float, Float> by perPlayer { 0f to 0f }
        private set

    /** UseItemRotSimMixin: a use_item reached [p]'s handler (server thread of the sim's server only). */
    @JvmStatic
    fun noteUseItem(p: ServerPlayer) {
        val s = p.level().server
        if (s !== SimServer.server || !s.isSameThread) return
        Sim.asPlayer(p) { lastRot = p.xRot to p.yRot }
    }
    /** Each player's recent positions (ns, where), for [seenPos]. */
    private val posHistories = HashMap<java.util.UUID, java.util.ArrayDeque<Pair<Long, net.minecraft.world.phys.Vec3>>>()

    /**
     * Where the server sees [p]: where they were one one-way latency ago (its checks run on the
     * position packets that arrived, so about ping/2 late). Live position with no ping.
     */
    fun seenPos(p: ServerPlayer): net.minecraft.world.phys.Vec3 = Sim.asPlayer(p, Sim.toAll) {
        val live = p.position()
        val ms = P3Sim.ping / 2.0
        val posHistory = posHistories[p.uuid]
        if (ms <= 0.0 || posHistory == null || posHistory.isEmpty()) return@asPlayer live
        val target = System.nanoTime() - (ms * 1e6).toLong()
        var prev: Pair<Long, net.minecraft.world.phys.Vec3>? = null
        for (e in posHistory) {
            if (e.first >= target) {
                val a = prev ?: return@asPlayer e.second
                val t = (target - a.first).toDouble() / (e.first - a.first).coerceAtLeast(1)
                return@asPlayer a.second.lerp(e.second, t.coerceIn(0.0, 1.0))
            }
            prev = e
        }
        val a = prev ?: return@asPlayer live
        val t = (target - a.first).toDouble() / (System.nanoTime() - a.first).coerceAtLeast(1)
        a.second.lerp(live, t.coerceIn(0.0, 1.0))
    }

    fun reset(server: MinecraftServer) {
        // A new sim: nobody's timers from the last one (they'd sit far ahead of this one's ticks).
        PerPlayer.clearAll()
        serverTick = 0
        epoch++
        later.clear()
        timed.clear(); posHistories.clear()
        Terminals.closeAll()
        Sim.forEachPlayer { SimItems.reset() }
        SimItems.resetShared()
        Players.clear()
        PartyServer.reset()
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
        Sim.forEachPlayer { SimItems.reset() }
        SimItems.resetShared()
        BossBar.hide()
    }

    fun join(player: ServerPlayer) {
        // A party's joiner (P3Party): a class of their own (its bot gone), set up as the host is and put next to them;
        // the fight goes on as it is (the next start has them in it from the start).
        val host = SimServer.player
        if (host != null && host !== player) {
            // Their game says hello next, with their class and settings (PartyServer.hello): set up again then.
            // No class until their game says which it wants (hello): a stand-in one would take a bot, or someone's pick.
            Sim.asPlayer(player) {
                PartyServer.rosterDirty = true
                setup(player)
                Sim.tp(player, host.x, host.y, host.z, host.yRot, host.xRot)
            }
            return
        }
        setup(player)
        if (phase == null) {
            Sim.tp(player, Spots.LOBBY.x, Spots.LOBBY.y, Spots.LOBBY.z, Spots.LOBBY.yaw, Spots.LOBBY.pitch)
            later(20, "welcome") {
                Sim.note("Welcome to P3 Sim Beta. §fRight click the Nether Star§7 (or /p3sim) for the menu.")
                if (P3Sim.autoStart) start(Start.P3)
            }
        }
    }

    /** A party's player left: their window, their class's place (its bot comes back at the next start). */
    fun leave(player: ServerPlayer) {
        // A crystal they carried goes back on its stand (else Maxor's side waits for it forever).
        (phase as? P1Maxor)?.playerLeft(player)
        // Their ghost state goes (a pending revive with it), so a rejoin starts clean.
        Masks.leftOne(player)
        Players.forgetFiles(player)
        val joiner = !Sim.isHost(player)
        val c = Players.classOf(player)
        val theirs = Players.jobsOf(player)
        val at = player.position()
        Players.left(player)
        // Their bot back mid-P3 where they stood, doing what they left (before the roster's own, which would give it the role's).
        if (joiner) (phase as? GoldorPhase)?.let { Party.takeOver(it, c, at, theirs) }
        PartyServer.left(player)
        Party.rosterChanged()
        if (!Sim.isHost(player)) Sim.everyone { Sim.note("§e${player.gameProfile.name}§7 left the party.") }
    }

    /** Game mode, Hypixel speed, no knockback, no hunger, the boss hotbar. */
    fun setup(player: ServerPlayer) = Sim.asPlayer(player) {
        // Hypixel: SURVIVAL. Blocks stay whole through Sim.guardBlocks + DungeonbreakerSimMixin.
        if (player.gameMode() != GameType.CREATIVE) player.setGameMode(GameType.SURVIVAL)
        Sim.guardBlocks()
        applySpeed(player)
        player.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        player.getAttribute(Attributes.STEP_HEIGHT)?.baseValue = 0.6
        player.isPermanentlyInvulnerable = true
        // Hypixel main: 40 hp with 16 absorption.
        player.getAttribute(Attributes.MAX_HEALTH)?.baseValue = 40.0
        player.health = 40f
        // 26.1.2 clamps absorption to MAX_ABSORPTION (base 0): raise it first or the 16 comes out as 0.
        player.getAttribute(Attributes.MAX_ABSORPTION)?.let { if (it.baseValue < 16.0) it.baseValue = 16.0 }
        player.absorptionAmount = 16f
        player.removeEffect(MobEffects.SATURATION)
        // Invulnerable players are never hungry or hurt; the effects are Hypixel's (night vision 1, or with haste 0 + mining fatigue 255).
        // As sent on main: night vision amplifier 1 with flags 7 (ambient, particles, icon); haste and mining fatigue flags 3 (no icon).
        player.addEffect(MobEffectInstance(MobEffects.NIGHT_VISION, -1, 1, true, true, true))
        player.foodData.setFoodLevel(20); player.foodData.setSaturation(20f)
        // P1/P2's hotbar mid-run there (a joiner arriving), else P3's.
        SimItems.giveHotbar(player, p3 = phase !is P1Maxor && phase !is P2Storm)
        // Nothing left of a ghost (a rejoin keeps the saved effects and flight).
        if (player.gameMode() != GameType.CREATIVE) {
            player.removeEffect(MobEffects.INVISIBILITY)
            player.abilities.flying = false; player.abilities.mayfly = false; player.onUpdateAbilities()
        }
        // Haste 0 + Mining Fatigue 255 unless the Dungeonbreaker is in hand (main toggles them with the held slot).
        SimItems.miningEffects(player, force = true)
    }

    /**
     * Odin's 4th device solver (Arrows Device) keeps the blocks hit, the target and "complete" until
     * a world load; a sim restart is a new P3, so it starts clean too.
     */
    fun resetArrowsDevice() {
        val c = com.odtheking.odin.features.impl.boss.ArrowsDevice::class.java
        fun field(name: String) = c.getDeclaredField(name).apply { isAccessible = true }
        (field("markedPositions").get(null) as? MutableSet<*>)?.clear()
        field("targetPosition").set(null, null)
        field("isDeviceComplete").setBoolean(null, false)
        field("optimalAimPositions").set(null, emptyList<Any>())
    }

    /**
     * Your speed stat: the setting is without Black Cat; Black Cat adds 100 (and 100 to the cap), Phoenix out
     * adds nothing; the Racing Helmet adds 100 more.
     */
    fun speedStat(player: ServerPlayer): Int = Sim.asPlayer(player, Sim.toAll) {
        val racing = SimItems.idOf(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)) == "RACING_HELMET"
        P3Sim.speed + (if (P3Sim.phoenix) 0 else 100) + (if (racing) 100 else 0)
    }

    fun applySpeed(player: ServerPlayer) {
        val speed = speedStat(player)
        player.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = speed.coerceAtLeast(100).toDouble() / 1000.0
        // The abilities packet's walk speed (FOV scaling) follows it, as Hypixel's does.
        player.abilities.setWalkingSpeed(speed.coerceAtLeast(100) / 1000f)
        player.onUpdateAbilities()
    }

    /** What the menu last started (the Restart keybind starts it again). */
    @Volatile var lastStart = Start.P3
        private set

    /** Starts [what] from its beginning (stopping whatever ran). [practice]: a section practice's start ([Practice]); any other start ends practice mode. */
    fun start(what: Start, practice: Boolean = false) = Sim.asPlayer(null) { startAll(what, practice) }

    private fun startAll(what: Start, practice: Boolean) {
        if (Sim.players().isEmpty()) return
        if (!practice) Practice.exit()
        lastStart = what
        stop()
        // Every game's Term Info from nothing (a joiner's only reads chat): the phase's own start says where from.
        PartyClient.send("termStart", 0)
        later.clear()
        Sim.clearEntities()
        // The bots too: a start that spawns none (P2, P4 with Party Bots off) must not see the last run's.
        Party.clear()
        Blocks.restoreAll()
        // The world as the phases before this one leave it.
        Blocks.prepare(what)
        SimItems.rebreak()
        // Every start (and restart) is with Black Cat out; the Pet Rod or a Phoenix proc swaps it.
        Sim.forEachPlayer { pl ->
            P3Sim.setPhoenix(false)
            setup(pl)
            Bows.start()
            Lava.reset()
        }
        Masks.reset()
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
        PartyClient.send("splitsStart", termsDone, split)
        Recorder.begin(what.label)
        // No "Starting in 1 second." line (Hypixel has none in P3): SimOdinSplits starts Odin's run directly.
        val p: Phase = when (what) {
            Start.P1 -> P1Maxor()
            Start.P2 -> P2Storm()
            // From Storm's death: 3.1 s to Goldor's line, as in the game since the boss update.
            Start.P3 -> StormEnd()
            // S1's practice starts at Goldor's line, you on your spawn.
            Start.S1 -> if (practice) GoldorPhase(1) else StormEnd()
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
        Practice.exit()
        Recorder.finish()
        stop()
        Sim.clearEntities()
        Blocks.restoreAll()
        Party.clear()
        // The splits stop with it: ours and Odin's cleared as a world load clears them, rather than
        // left counting a fight that's gone. Queued on the client like a start's, so they stay in order.
        PartyClient.send("splitsStop")
        PartyClient.send("termStart", 0)
    }

    fun tick(server: MinecraftServer) {
        Sim.clearActor()
        serverTick++
        // Hypixel pings every client each server tick; Odin (and this mod) count those as server
        // ticks: Simon Says, terminal first-click protection, splits all run on them.
        server.playerList.players.forEach { it.connection.send(ClientboundPingPacket(serverTick)) }
        if (later.isNotEmpty()) {
            val due = later.filter { it.at <= serverTick }
            later.removeAll(due.toSet())
            due.forEach { if (it.epoch == epoch) EngineerClient.safely("p3sim ${it.what}") { it.go() } }
        }
        if (timed.isNotEmpty()) {
            val nowNs = System.nanoTime()
            val due = timed.filter { it.dueNs <= nowNs }.sortedBy { it.dueNs }
            timed.removeAll(due.toSet())
            due.forEach { if (it.epoch == epoch) EngineerClient.safely("p3sim ${it.what}") { it.go() } }
        }
        val now = System.nanoTime()
        for (pl in Sim.players()) {
            val h = posHistories.getOrPut(pl.uuid) { java.util.ArrayDeque() }
            h.addLast(now to pl.position())
            while (h.size > 40) h.removeFirst()
        }
        EngineerClient.safely("p3sim party") { PartyServer.tick() }
        Blocks.tick()
        Terminals.tick()
        SimItems.tick()
        Sim.forEachPlayer { EngineerClient.safely("p3sim bows") { Bows.tick() } }
        Sim.forEachPlayer { pl -> if (P3Sim.lava) EngineerClient.safely("p3sim lava") { Lava.tick(pl) } }
        val p = phase
        if (p == null) { Recorder.finish(); SimItems.flushMotion(); return }
        // The run recorder covers P1-P3 and stops at Necron; his phase still has to tick.
        if (p is P4Necron) Recorder.finish()
        EngineerClient.safely("p3sim ${p.name}") { p.tick() }
        p.t++
        if (p !is P4Necron) EngineerClient.safely("p3sim recorder") { Recorder.tick(serverTick) }
        // This tick's knockback, straight to the player in the burst's own tick (SimItems.push).
        SimItems.flushMotion()
    }
}
