package com.engineerclient.p3sim

import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.GameType
import java.util.UUID

/**
 * Spectate Route (the menu's button): P3 from S1 as Restart P3 starts it, but with a bot for every class, yours too, so
 * the whole party plays the plan (your jobs by your class's bot), and you watch your class's bot as a spectator from its
 * eyes (F5: from behind or in front, as vanilla spectating does).
 *
 * The camera stays on the bot: a new body (P3's start makes new ones), a teleport or a leap, it's put back on it, and
 * sneaking doesn't leave it (SpectateRouteSneakMixin). Nothing you do touches the fight (no clicks, items or hits); the
 * menu's Stop and Restart P3 still work. Solo only: in a party everyone plays their own class.
 *
 * It ends with the run (Goldor dead, Stop After Terms, Stop, a restart, the world closing): the camera back on you, your
 * game mode back (survival), the sim's setup and hotbar given again, where your class's bot stood (your P3 spawn if
 * that's in a wall). A tag on the player marks it, so a world closed mid-route has you back in survival when you join.
 */
object SpectateRoute {
    /** On the watching player while they watch (saved with the world): a join finds it and puts them back. */
    private const val TAG = "ads_spectate_route"
    /** ... and they were in creative before it. */
    private const val TAG_CREATIVE = "ads_spectate_route_creative"

    /** Whether a route is being watched: no class is a player's (any thread: [Players] reads it). */
    @Volatile var active = false
        private set

    /** Who watches it. */
    private var watcher: UUID? = null

    /** Inside [start]'s own [Fight.start]: its stop isn't the end of this route. */
    private var starting = false

    /** What the end says ([finished]: the run got to its end), else that it stopped. */
    private var doneLine: String? = null

    /** [p] watches the route (their jobs are their bot's, they're not in the fight). Server thread. */
    fun watching(p: ServerPlayer?) = active && p != null && p.uuid == watcher

    /** SpectateRouteSneakMixin: [p] can't sneak off the bot while watching. */
    @JvmStatic fun holds(p: Player): Boolean = p is ServerPlayer && SimServer.server?.isSameThread == true && watching(p)

    /** The menu's Spectate Route, from [p]: a fresh P3 with five bots and [p] on their class's. */
    fun start(p: ServerPlayer) {
        if (Sim.players().size > 1) { Sim.asPlayer(p) { Sim.note("Spectate Route is solo only: in a party everyone plays their own class.") }; return }
        if (!P3Sim.bots) { Sim.asPlayer(p) { Sim.note("Spectate Route needs the bots shown: turn §fBots§7 on (Advanced > Bots).") }; return }
        if (!watching(p)) {
            if (p.gameMode() == GameType.CREATIVE) p.addTag(TAG_CREATIVE)
            p.addTag(TAG)
        }
        watcher = p.uuid
        active = true
        doneLine = null
        // Its bot is made with the others (StormEnd's lead-in): the class has to be a bot's before the start.
        starting = true
        try { Fight.start(Fight.Start.P3) } finally { starting = false }
        if (Fight.phase == null) { end("§7Spectate Route couldn't start."); return }
        p.setGameMode(GameType.SPECTATOR)
        attach(p)
        Sim.asPlayer(p) { Sim.note("Spectating the §f${Roles.label(P3Sim.ownClass)}§7 bot's route (${P3Plan.skillName()}). §fF5§7: first or third person. Stop or Restart P3 ends it.") }
    }

    /** The run is at its end ([GoldorPhase]: Goldor dead): it stops, and [line] says so. */
    fun finished(line: String) { doneLine = line; Fight.end() }

    /** [Fight.stop]: the run stopped (Stop, a restart, its end, the world closing): back to playing. */
    fun stopped() { if (active && !starting) end(doneLine ?: "§7Spectate Route stopped.") }

    /** [Fight.begin]: a phase that isn't P3's (P4) has no route to watch. */
    fun began(p: Fight.Phase) { if (active && !starting && p !is StormEnd && p !is GoldorPhase) end("§7Spectate Route ended with P3.") }

    /** A party's joiner came in: the class is theirs to play again (the run goes on, you in it). */
    fun partyJoined() { if (active) end("§7Someone joined: Spectate Route ended, you play the §f${Roles.label(P3Sim.ownClass)}§7 from here.") }

    /** [p] left (or the world is closing with them in it): theirs ends with them. */
    fun left(p: ServerPlayer) { if (watching(p)) end(null) }

    /** A new sim: nobody watches. A player saved mid-route is put back as they join ([joined]). */
    fun reset() { active = false; watcher = null; doneLine = null; starting = false }

    /** [Fight.join]: a world closed mid-route left [p] a spectator: back as they were, before the sim's setup. */
    fun joined(p: ServerPlayer) {
        if (TAG !in p.entityTags()) return
        restoreMode(p)
        Fight.later(30, "spectate route rejoin") { Sim.asPlayer(p) { Sim.note("§7Your Spectate Route ended with the world: you're back in survival.") } }
    }

    /** Each server tick: [p] stays a spectator on their class's bot. */
    fun tick() {
        if (!active) return
        val p = Sim.players().firstOrNull { it.uuid == watcher } ?: run { end(null); return }
        if (p.gameMode() != GameType.SPECTATOR) p.setGameMode(GameType.SPECTATOR)
        attach(p)
    }

    /**
     * [p]'s camera on their class's bot. A body gone (P3's start replaces them) drops it back to [p] (vanilla), and a
     * new one takes it at once: its spawn reaches their game before the camera does (same connection, in order).
     * Once a second it's said again, should their game have missed it (the bot out of its range then).
     */
    private fun attach(p: ServerPlayer) {
        val bot = Party.entityOf(P3Sim.ownClass)?.takeIf { it.isAlive && it.level() === p.level() } ?: return
        if (p.camera !== bot) p.setCamera(bot)
        else if (Fight.serverTick % 20 == 0) p.connection.send(ClientboundSetCameraPacket(bot))
    }

    private fun end(line: String?) {
        val id = watcher
        active = false
        watcher = null
        doneLine = null
        // Your class is a player's again: its bot leaves (mid-run, a joiner's arrival: you take over where it was).
        Party.rosterChanged()
        val p = Sim.players().firstOrNull { it.uuid == id } ?: return
        restoreMode(p)
        // Where your class's bot stood (in the core, at the end), unless that's inside blocks (its dig into the core).
        if (!p.level().noCollision(p, p.boundingBox)) Spots.p3Start(1).let { Sim.tp(p, it.x, it.y, it.z, it.yaw, it.pitch) }
        // The sim's setup after a run: health, effects, the hotbar as you left it.
        Fight.setup(p)
        if (line != null) Sim.asPlayer(p) { Sim.note("$line You're back in survival.") }
    }

    /** Off the bot and out of spectator: survival (creative if that's what they had). */
    private fun restoreMode(p: ServerPlayer) {
        val creative = TAG_CREATIVE in p.entityTags()
        p.removeTag(TAG); p.removeTag(TAG_CREATIVE)
        p.setCamera(p)
        p.setGameMode(if (creative) GameType.CREATIVE else GameType.SURVIVAL)
    }

    /**
     * Nothing a watcher does reaches the fight: no item use, block or entity click, or hit, even the ones vanilla's
     * spectator rules let through (Fabric's callbacks come before them). Registered before [SimItems]', so theirs never see it.
     */
    fun register() {
        fun blocked(p: Player) = p is ServerPlayer && SimServer.isSimLevel(p.level()) && watching(p)
        UseItemCallback.EVENT.register { p, _, _ -> if (blocked(p)) InteractionResult.FAIL else InteractionResult.PASS }
        UseBlockCallback.EVENT.register { p, _, _, _ -> if (blocked(p)) InteractionResult.FAIL else InteractionResult.PASS }
        UseEntityCallback.EVENT.register { p, _, _, _, _ -> if (blocked(p)) InteractionResult.FAIL else InteractionResult.PASS }
        // A spectator's hit on an entity spectates it (vanilla): the camera stays on the bot.
        AttackEntityCallback.EVENT.register { p, _, _, _, _ -> if (blocked(p)) InteractionResult.FAIL else InteractionResult.PASS }
        AttackBlockCallback.EVENT.register { p, _, _, _, _ -> if (blocked(p)) InteractionResult.FAIL else InteractionResult.PASS }
    }
}
