package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.GameType

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

    /** Runs [run] after the simulated ping (at once with none). */
    fun afterPing(what: String, run: () -> Unit) {
        val n = pingTicks
        val p = phase
        if (n == 0) run() else later(n, what) { if (phase === p) run() }
    }

    fun reset(server: MinecraftServer) {
        serverTick = 0
        epoch++
        later.clear()
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

    /** Your speed: the setting with Black Cat out, 100 less with Phoenix. */
    fun applySpeed(player: ServerPlayer) {
        val speed = P3Sim.speed - if (P3Sim.phoenix) 100 else 0
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
        Stats.runStart = if (what == Start.P1) serverTick else -1
        previous = null
        // Our splits: a fresh run from this phase, the ones before it at your Pace times. Queued on
        // the client before any of this fight's lines can reach it.
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
        // Odin's Splits start on the dungeon's countdown line.
        Sim.chat("§aStarting in 1 second.")
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
