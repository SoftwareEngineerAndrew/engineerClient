package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.P3Plan
import com.engineerclient.p3sim.Players
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.BloodDoorOpened
import com.engineerclient.p3sim.run.DoorOpened
import com.engineerclient.p3sim.run.DoorFell
import com.engineerclient.p3sim.run.DamageSource
import com.engineerclient.p3sim.run.api.Services
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.phys.Vec3
import com.engineerclient.p3sim.run.KeyDropped
import com.engineerclient.p3sim.run.KeyKind
import com.engineerclient.p3sim.run.KeyPicked
import com.engineerclient.p3sim.run.PlayerDamaged
import com.engineerclient.p3sim.run.PlayerDied
import com.engineerclient.p3sim.run.PlayerJoined
import com.engineerclient.p3sim.run.PlayerLeft
import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.PlayerRevived
import com.engineerclient.p3sim.run.RoomCleared
import com.engineerclient.p3sim.run.RoomEntered
import com.engineerclient.p3sim.run.RoomGreen
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunBlocks
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.EndReason
import com.engineerclient.p3sim.run.SavepointRestored
import com.engineerclient.p3sim.run.api.BotService
import com.engineerclient.p3sim.run.api.LeapTarget
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.random.Random

/**
 * Teammate bots in the clear: a ClearPlan per layout from the class role table and the timing tables, visible door-to-door movement,
 * recorded in-room walks where the optional pack has them, doors opened with keys, re-planning when the human takes a room or opens a door,
 * and the party-strategy hook (the neutral default lives here; presets come from the plug-in seam).
 *
 * What a player sees: up to four teammates, drawn as player entities with a name, rank and levels of their own (the classes no human plays, dressed as the boss sim's bots are) that run
 * the dungeon ahead of and around the human. One of them fetches the wither keys and opens the doors; the others clear rooms, and a room
 * turns white and green on the measured schedule. They never fight: a room's mobs die when the plan says so, through the damage service, so
 * every kill is a real kill event (the room's key drops, the mark changes). When the human walks into a room a bot was about to clear, the
 * bot gives it up and the plan is redrawn; a room the human does not get to within [BotTuning.COVER_AFTER_TICKS] ticks of its time is covered
 * by the bots. See `docs/ads/bots.md` for the design and the in-game checks.
 *
 * The ClearPlan is the truth ([ClearPlanner], [PlanExecutor]); the body ([BotBodies]) is only shown while a human is near.
 */
object BotsModule : AdsModule, BotService {
    override val id = "bots"
    override val owner = "bots"

    private val log = LoggerFactory.getLogger("engineerclient")

    // ---- settings (created in registerSettings, never at object start) ------------------------------------------------
    private lateinit var showDistanceS: NumberSetting<Int>
    private lateinit var speedScaleS: NumberSetting<Double>
    private lateinit var coverS: BooleanSetting
    private lateinit var coverAfterS: NumberSetting<Int>
    private lateinit var graceS: NumberSetting<Int>
    private lateinit var namesS: BooleanSetting
    private lateinit var playersS: BooleanSetting

    override fun registerSettings(host: Module) {
        showDistanceS = host.registerSetting(NumberSetting("Bots Show Distance", BotTuning.BODY_RADIUS.toInt(), 24..120, 4, desc = "How near you have to be for a teammate bot to be drawn. Bots farther away are still running the plan; you just do not see them (the world only keeps the rooms near you loaded)."))
        speedScaleS = host.registerSetting(NumberSetting("Bots Speed Scale", 1.0, 0.5..2.0, 0.1, desc = "How fast the bots travel between rooms compared with the measured pace of a real party (1.0). Faster bots get ahead of you sooner; their rooms still finish on the same schedule."))
        coverS = host.registerSetting(BooleanSetting("Bots Cover Your Rooms", true, desc = "A room the plan gave to you that you have not gone into after a while is cleared by a bot, as a teammate would. Off: your share waits for you."))
        coverAfterS = host.registerSetting(NumberSetting("Bots Cover After", BotTuning.COVER_AFTER_TICKS, 100..3000, 50, unit = " ticks", desc = "How long after the plan expected you in a room the bots start covering it (20 ticks a second)."))
        namesS = host.registerSetting(BooleanSetting("Bots Use Player Names", true, desc = "Teammate bots in a full run have names, ranks and levels of their own, like a party on Hypixel. Off: they are named for their class (Healer, Mage...), as the boss practice names them."))
        playersS = host.registerSetting(BooleanSetting("Bots Are Players", true, desc = "Teammate bots reach your game as real player entities with a name over the head, as Hypixel's teammates do (Odin's class glow and teammate tracking need that). Off: they are mannequins, as in the first version. Takes effect when a bot is next drawn."))
        graceS = host.registerSetting(NumberSetting("Bots Wait For You", BotTuning.HUMAN_ROOM_GRACE_TICKS, 0..1200, 20, unit = " ticks", desc = "While you stand in a room a bot was going to clear, it holds back the last mob this long, in case you are about to kill it yourself."))
    }

    private fun showDistance(): Double = if (::showDistanceS.isInitialized) showDistanceS.value.toDouble() else BotTuning.BODY_RADIUS
    private fun speedScale(): Double = if (::speedScaleS.isInitialized) speedScaleS.value else 1.0
    private fun coverOn(): Boolean = if (::coverS.isInitialized) coverS.value else true
    private fun coverAfter(): Int = if (::coverAfterS.isInitialized) coverAfterS.value else BotTuning.COVER_AFTER_TICKS
    private fun playerNames(): Boolean = if (::namesS.isInitialized) namesS.value else true
    private fun playerBodies(): Boolean = if (::playersS.isInitialized) playersS.value else true
    private fun grace(): Int = if (::graceS.isInitialized) graceS.value else BotTuning.HUMAN_ROOM_GRACE_TICKS

    // ---- one run's bots ---------------------------------------------------------------------------------------------------

    /** Everything the module holds for the current run. Replaced in [onRunStart], dropped when the clear ends. */
    class Session(
        val run: Run,
        val roster: Roster,
        val tables: BotTables,
        val rooms: BotRooms,
        val world: BotWorldImpl,
        val bodies: BotBodies,
        val traversals: TraversalLibrary?,
        /** The walkable floor of each room, from the rooms pack's blocks. */
        val areas: PackRoomAreas = PackRoomAreas(run.packs.rooms),
    ) {
        lateinit var executor: PlanExecutor
        val plan: ClearPlan get() = executor.plan
        var active = true
        var announcedJoin = false
        var covered = false
        var replanAt: Int? = null
        var replanReason = ""
        var replans = 0
        var lastCoverCheck = 0
        /** Room to the tick a human last went into it. */
        val humanTouched = HashMap<Int, Int>()
        val pos = HashMap<Int, Pt>()
        val y = HashMap<Int, Double>()
        val yaw = HashMap<Int, Float>()
        /** A bot sent to a key stand: it walks there, waits for the key to be taken and walks back. */
        val excursion = HashMap<Int, Excursion>()
        /** Key stands nobody has taken yet: kind to (the stand, the drop tick, whether a bot was already sent for it after a wait). */
        val stands = HashMap<KeyKind, StandWatch>()
        val kept = ArrayList<String>()
        val steps = StepClock(run.subRandom("bots.steps"))
        /** The teammate deaths still to come (none in most clears). */
        val deaths = ArrayList<TeamDeath>()
        /** When a mob lands a hit on a fighting teammate. */
        val hits = HitClock(run.subRandom("bots.hits"))
        /** Door to its crack animation, while it is shown (a bot opened it). */
        val cracking = HashMap<Int, Crack>()
        /** Where the party appeared (the human's spawn block) and the height it appeared at; null when the human was not in the entrance room. */
        var spawn: Pt? = null
        var spawnY = BotTuning.DEFAULT_FLOOR_Y
        /** Where each bot waits for the clock to start (by the entrance room's exit door). */
        val gates = HashMap<Int, Pt>()
        /** Each bot's walk from the spawn block to its gate, built on the first tick of the countdown. */
        val countdown = HashMap<Int, SeatTrack>()
        var countdownBuilt = false
        /** Bots still dropping from the spawn height onto the floor. */
        val falls = HashMap<Int, Fall>()
        /** The small life of the bodies: the main hand, swings, sprint, crouch, the head. */
        val motion = BotMotion(run.subRandom("bots.motion"))
        /** Dice for the sounds of a kill. */
        val cast = run.subRandom("bots.cast")
    }

    /** A key stand somebody has to take. */
    class StandWatch(val at: Pt, val droppedT: Int, var sentFallback: Boolean = false)

    /** A bot-opened door's block-break animation: the tick the door opened and the breaker id the packets carry (the opener's entity id, as on Hypixel). */
    class Crack(val openT: Int, val breakerId: Int)

    @Volatile var session: Session? = null
        private set

    private fun live(run: Run): Session? = session?.takeIf { it.run === run && it.active }

    override fun onRunStart(run: Run) {
        session?.let { it.bodies.removeAll() }
        session = null
        if (!run.config.bots) { log.info("[ads] bots: none planned (Run Bots is off)"); return }
        try {
            val set = run.packs
            val tables = BotTables(set.runstate)
            val humans = humansOf(run)
            val roster = BotRoster.build(humans, true, run.config.partySize, botOrder(), if (playerNames()) run.subRandom("bots.identity") else null)
            if (roster.bots.isEmpty()) { log.info("[ads] bots: none planned (party size ${run.config.partySize} leaves no seat for a bot)"); return }
            for (b in roster.bots) {
                val uuid = roster.uuids.getValue(b.index)
                val rec = run.state.players.getOrPut(uuid) { PlayerRecord(uuid, roster.names.getValue(b.index), b.dungeonClass, true) }
                roster.identities[b.index]?.applyTo(rec)
            }
            val trav = try { TraversalLibrary.load(set.dir, set.report) } catch (t: Throwable) { null }
            val strategy = BotStrategies.resolve(run.config.strategy)
            val rooms = BotRooms(run.plan)
            val s = Session(run, roster, tables, rooms, BotWorldImpl(run, roster, rooms, ::killFx), BotBodies(::playerBodies), trav)
            planCountdown(run, s)
            val first = buildPlan(run, s, PlanState.initial(), strategy)
            s.executor = PlanExecutor(first, s.world, grace())
            val geo = PlanGeometry(run.plan)
            val start = geo.centre(run.plan.entrance?.index ?: 0)
            for (b in roster.bots) { s.pos[b.index] = first.track(b.index)?.positionAt(0) ?: start; s.y[b.index] = BotTuning.DEFAULT_FLOOR_Y; s.yaw[b.index] = 0f; s.motion.start(b.index, s.bodies.restingItem(b.dungeonClass)) }
            for (t in TeamDeaths.schedule(run.subRandom("bots.deaths"), first.milestones.watcherDoneT)) s.deaths += TeamDeath(t, run.subRandom("bots.death.$t"))
            // Each teammate has its own health number at full health (the sidebar shows it).
            val hpRandom = run.subRandom("bots.maxhp")
            for (b in roster.bots) run.state.players[roster.uuids.getValue(b.index)]?.extras?.set("maxHp", BotHealth.maxHp(b.dungeonClass, hpRandom.nextDouble()))
            session = s
            log.info("[ads] bots: ${roster.bots.joinToString { it.dungeonClass }} planned (party of ${run.config.partySize}); ${s.deaths.size} teammate death(s) scheduled; blood open at t=${first.milestones.bloodOpenT}, ${first.doors.size} doors, ${first.rooms.size} rooms")
        } catch (t: Throwable) {
            log.warn("[ads] the bots could not be planned; the run goes on without them", t)
            session = null
        }
    }

    /**
     * The countdown: the bots appear on the block the human spawned on and walk to the entrance room's exit door ([Countdown]). Needs the human to
     * stand in the entrance room (the engine put them on its edge); otherwise the bots start on a ring round the room's middle as before.
     */
    private fun planCountdown(run: Run, s: Session) {
        try {
            val entrance = run.plan.entrance ?: return
            val me = Sim.players().firstOrNull() ?: return
            val here = DungeonFrame.tileOf(me.x, me.z)
            if (here == null || here !in entrance.tiles) return
            val door = run.plan.doors.firstOrNull { it.kind == DoorKind.ENTRANCE } ?: return
            val geo = PlanGeometry(run.plan)
            val spawn = Pt(me.x, me.z)
            val rnd = run.subRandom("bots.start")
            s.spawn = spawn; s.spawnY = me.y
            for (b in s.roster.bots) s.gates[b.index] = Countdown.gate(geo.doorPt(door.index), spawn, rnd)
        } catch (t: Throwable) { log.info("[ads] bots: no countdown walk (${t.message})") }
    }

    /** The human seats: the run's non-bot records (the engine adds them), else the players in the world. */
    private fun humansOf(run: Run): List<Triple<UUID, String, String?>> {
        val fromState = run.state.players.values.filter { !it.isBot }.map { Triple(it.uuid, it.name, it.dungeonClass) }
        if (fromState.isNotEmpty()) return fromState
        return try { Sim.players().map { Triple(it.uuid, it.gameProfile.name, Players.classOf(it).name) } } catch (t: Throwable) { emptyList() }
    }

    /** The bots' class order: the boss sim's own leap order for the host (the classes the human is not), else the usual order. */
    private fun botOrder(): List<String> = try { P3Plan.botOrder().map { it.name } } catch (t: Throwable) { BotRoster.CLASSES }

    private fun buildPlan(run: Run, s: Session, state: PlanState, strategy: com.engineerclient.p3sim.run.plugin.PartyStrategy = BotStrategies.resolve(run.config.strategy)): ClearPlan {
        val rnd = run.subRandom("bots.plan.${s.replans}")
        val ctx = BotStrategyContext(run.plan, s.roster.seats, Random(rnd.nextLong()), run.packs.runstate, state.now)
        val assignment = BotStrategies.assignment(strategy, ctx)
        // A bot that is a ghost does no work until it is back: its rooms and doors go to the others.
        val seats = s.roster.seats.filter { seat -> seat.isHuman || s.roster.uuids[seat.index]?.let { run.state.players[it]?.ghost } != true }
        return ClearPlanner(PlannerInput(run.plan, s.tables, seats, rnd, assignment, state, speedScale(), BotTuning.ROOM_WORK_SCALE, s.traversals, s.gates, s.areas)).build()
    }

    override fun onStage(run: Run, from: RunStage?, to: RunStage) {
        val s = session ?: return
        if (to.inBoss || to == RunStage.END) shutdown(s)
    }

    override fun onRunEnd(run: Run, reason: EndReason) { session?.let { shutdown(it) } }

    /** The clear is over (boss entry, end, stop): the bodies go and the plan is dropped; the boss sim spawns its own bots. The party records stay for the HUD. */
    private fun shutdown(s: Session) {
        s.bodies.removeAll()
        s.active = false
    }

    // ---- every tick -----------------------------------------------------------------------------------------------------

    override fun onTick(run: Run) {
        val s = live(run) ?: return
        if (!run.stage.inDungeon) return
        val t = run.t
        if (!s.announcedJoin) {
            s.announcedJoin = true
            for (b in s.roster.bots) run.bus.post(PlayerJoined(t, s.roster.uuids.getValue(b.index)))
        }
        if (!s.countdownBuilt) { s.countdownBuilt = true; buildCountdown(run, s, t) }
        s.bodies.prune()
        if (t >= 0) {
            if (coverOn()) coverCheck(run, s, t)
            val due = s.replanAt
            if (due != null && t >= due) { s.replanAt = null; replan(run, s, t) }
            s.executor.tick(t)
        }
        move(run, s, t)
        if (t >= 0) { health(run, s, t); doorCracks(run, s, t); standFallback(run, s, t) }
    }

    /** From the first tick of the countdown (run tick [t], negative): every bot's walk from the spawn block to its gate, and the drop from the spawn height. */
    private fun buildCountdown(run: Run, s: Session, t: Int) {
        val spawn = s.spawn ?: return
        if (t >= 0) return
        val rnd = run.subRandom("bots.countdown")
        for (b in s.roster.bots) {
            val gate = s.gates[b.index] ?: continue
            s.countdown[b.index] = SeatTrack(b.index, Countdown.legs(spawn, gate, t, rnd), emptyList())
            s.pos[b.index] = spawn
            s.falls[b.index] = Fall(s.spawnY)
            s.y[b.index] = s.spawnY
        }
    }

    /** The hands a teammate death works with: the damage service, aimed at the bot's own room. */
    private class DeathHands(val run: Run, val s: Session) : TeamDeath.World {
        private fun rec(seat: Int): PlayerRecord? = s.roster.uuids[seat]?.let { run.state.players[it] }
        private fun bots() = s.roster.bots.filter { rec(it.index)?.ghost == false }

        fun mobOf(seat: Int): com.engineerclient.p3sim.run.api.MobRecord? {
            val p = s.pos[seat] ?: return null
            val room = run.state.roomAt(p.x, p.z)?.index ?: return null
            return Services.mobs.inRoom(room).firstOrNull { it.required && it.alive } ?: Services.mobs.inRoom(room).firstOrNull { it.alive }
        }

        override fun inFight(random: Random): Int? = bots().filter { mobOf(it.index) != null }.randomOrNull(random)?.index
        override fun anyone(random: Random): Int? = bots().randomOrNull(random)?.index
        override fun alive(seat: Int) = rec(seat)?.ghost == false
        override fun hpPercent(seat: Int) = rec(seat)?.hpPercent ?: 0.0
        override fun hit(seat: Int, percent: Double) {
            val r = rec(seat) ?: return
            Services.damage.hurtPlayer(run, r.uuid, percent, DamageSource.MELEE, mobOf(seat)?.id)
        }
        override fun kill(seat: Int) {
            val r = rec(seat) ?: return
            Services.damage.kill(run, r.uuid, DamageSource.MELEE, mobOf(seat)?.id)
        }
        override fun disconnect(seat: Int) {
            val r = rec(seat) ?: return
            Services.damage.kill(run, r.uuid, DamageSource.DISCONNECT, null)
        }
    }

    /**
     * Teammate health: the rare scheduled death (hits, then the ghost), and the ordinary hits a mob lands on a teammate that is fighting. The
     * damage service keeps the ledger (immunity, regeneration, the death line); the hurt animation follows the [PlayerDamaged] event.
     */
    private fun health(run: Run, s: Session, t: Int) {
        val hands = DeathHands(run, s)
        for (d in s.deaths.toList()) d.step(t, hands)
        s.deaths.removeAll { it.finished }
        for (b in s.roster.bots) {
            val rec = s.roster.uuids[b.index]?.let { run.state.players[it] } ?: continue
            if (rec.ghost) continue
            val mob = hands.mobOf(b.index) ?: continue
            val pct = s.hits.roll() ?: continue
            if (rec.hpPercent - pct < BotHealth.HP_FLOOR) continue
            Services.damage.hurtPlayer(run, rec.uuid, pct, DamageSource.MELEE, mob.id)
        }
    }

    /** A bot-opened door shows the opener's block-break crack on its cells for the tick it opens and the next, and clears it when it falls. */
    private fun doorCracks(run: Run, s: Session, t: Int) {
        val it = s.cracking.entries.iterator()
        while (it.hasNext()) {
            val (door, c) = it.next()
            if (t > c.openT + 1) it.remove()
            else crack(run, door, 0, c.breakerId)
        }
    }

    private fun crack(run: Run, door: Int, progress: Int, breakerId: Int) {
        val (x, z) = run.plan.doors.getOrNull(door)?.spot ?: return
        val pkt = ClientboundBlockDestructionPacket(breakerId, BlockPos(x, DOOR_CRACK_Y, z), progress)
        for (p in try { Sim.players() } catch (e: Throwable) { emptyList() }) p.connection.send(pkt)
    }

    private fun move(run: Run, s: Session, t: Int) {
        val humans = try { Sim.players() } catch (t: Throwable) { emptyList() }
        val radius = showDistance()
        for (b in s.roster.bots) {
            val seat = b.index
            val uuid = s.roster.uuids.getValue(seat)
            val rec = run.state.players[uuid] ?: continue
            // A ghost is not in the world for its six seconds: no body, no steps, no place on the map but where it fell.
            if (rec.ghost) { s.bodies.remove(seat); s.excursion.remove(seat); continue }
            val walk = if (t < 0) s.countdown[seat] else null
            val plan = walk?.positionAt(t) ?: s.executor.positionOf(seat, t) ?: s.pos[seat] ?: Pt(rec.x, rec.z)
            // A bot off the plan to take a key walks there and back; the plan's own position is what it returns to.
            val trip = s.excursion[seat]
            val stepped = trip?.step(s.pos[seat] ?: plan, plan)
            if (trip != null && stepped == null) s.excursion.remove(seat)
            val target = stepped ?: plan
            val old = s.pos[seat] ?: target
            if (old.dist(target) > 0.05) s.yaw[seat] = Math.toDegrees(Math.atan2(-(target.x - old.x), target.z - old.z)).toFloat()
            val walking = old.dist(target) > 0.05
            val speed = old.dist(target)
            s.pos[seat] = target
            val near = humans.any { h -> Math.hypot(h.x - target.x, h.z - target.z) <= radius + (if (s.bodies.has(seat)) BotTuning.BODY_HYSTERESIS else 0.0) }
            val look = s.roster.identities[seat]?.look ?: BotLook(s.roster.names.getValue(seat), uuid, "§a", "", "§a")
            // A bot that has just appeared above the floor drops onto it first.
            val fall = s.falls[seat]
            val dropY = fall?.let { f -> f.step(s.bodies.groundY(target.x, target.z, BotTuning.DEFAULT_FLOOR_Y)).also { if (f.landed) s.falls.remove(seat) } }
            // What the body shows besides its place: the hand, a swing while it works, sprint, crouch, the head.
            val activity = when (if (t >= 0) s.executor.workOf(seat, t) else null) {
                is ClearMobs -> BotActivity.FIGHT
                is FindSecrets -> BotActivity.SEARCH
                is SolvePuzzle -> BotActivity.PUZZLE
                else -> if (speed > BotMotion.MOVING_ABOVE) BotActivity.WALK else BotActivity.STAND
            }
            val frame = s.motion.step(seat, t, speed, s.yaw[seat] ?: 0f, activity)
            s.yaw[seat] = frame.bodyYaw
            val y = s.bodies.place(seat, b.dungeonClass, look, target.x, target.z, frame, near, s.y[seat] ?: BotTuning.DEFAULT_FLOOR_Y, s.motion.held(seat), dropY)
            s.y[seat] = y
            if (s.steps.due(seat, t, walking) && s.bodies.has(seat)) stepSound(target.x, y, target.z)
            rec.x = target.x; rec.y = y; rec.z = target.z
            rec.extras["yaw"] = s.yaw[seat] ?: 0f   // the HUD turns the bot's map marker by it
        }
    }

    /** A bot killed a mob: it swings, the mob's blast is heard, and now and then the bot's own teleport tick (MEASURED near teammates: explode volume 1.0, enderman teleport volume 1.0 pitch 1.0; the 40% is the finding's, unmeasured). */
    private fun killFx(seat: Int, x: Double, y: Double, z: Double) {
        val s = session?.takeIf { it.active } ?: return
        s.motion.swingNow(seat)
        if (!s.bodies.has(seat)) return
        try {
            Sim.sound(SoundEvents.GENERIC_EXPLODE, 1f, 1f, Vec3(x, y, z), SoundSource.BLOCKS)
            val at = s.pos[seat]
            if (at != null && s.cast.nextDouble() < 0.4) Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, Vec3(at.x, s.y[seat] ?: y, at.z), SoundSource.HOSTILE)
        } catch (e: Throwable) { /* no world (headless) */ }
    }

    /** A footstep (volume 0.15, pitch 1.0, blocks channel) at a walking teammate's feet: the glass tick on most floors, the floor's own step on gravel, wool, wood and grass. */
    private fun stepSound(x: Double, y: Double, z: Double) {
        val under = try { RunBlocks.get(Math.floor(x).toInt(), Math.floor(y - 0.5).toInt(), Math.floor(z).toInt()) } catch (e: Throwable) { null }
        val group = when (under?.soundType) {
            SoundType.GRAVEL -> "gravel"
            SoundType.WOOL -> "wool"
            SoundType.WOOD -> "wood"
            SoundType.GRASS -> "grass"
            else -> ""
        }
        val event = when (StepSounds.forFloor(group)) {
            StepSound.GRAVEL -> SoundEvents.GRAVEL_STEP
            StepSound.WOOL -> SoundEvents.WOOL_STEP
            StepSound.WOOD -> SoundEvents.WOOD_STEP
            StepSound.GRASS -> SoundEvents.GRASS_STEP
            StepSound.GLASS -> SoundEvents.GLASS_PLACE
        }
        try { Sim.sound(event, 0.15f, 1f, Vec3(x, y, z), SoundSource.BLOCKS) } catch (e: Throwable) { /* no world (headless) */ }
    }

    // ---- re-planning ----------------------------------------------------------------------------------------------------

    private fun schedule(s: Session, t: Int, reason: String) {
        val at = t + BotTuning.REPLAN_DEBOUNCE
        if (s.replanAt == null) { s.replanAt = at; s.replanReason = reason } else s.replanReason += "; $reason"
    }

    private fun replan(run: Run, s: Session, t: Int) {
        try {
            s.replans++
            val opener = s.plan.doors.values.filter { it.openT > t }.minByOrNull { it.openT }?.seat
            val state = ReplanState.build(run.state, t.coerceAtLeast(0), s.plan, s.roster, s.pos.mapValues { it.value }, s.humanTouched, s.covered, opener, coverAfter())
            val fresh = buildPlan(run, s, state)
            s.executor.replace(fresh)
            s.kept += "t=$t ${s.replanReason}"
            if (s.kept.size > 12) s.kept.removeAt(0)
        } catch (e: Throwable) { log.warn("[ads] bots re-plan failed; the old plan stays", e) }
    }

    /** Rooms the plan gave the human that nobody has been into for [coverAfter] ticks after their time: the bots cover them from now on. */
    private fun coverCheck(run: Run, s: Session, t: Int) {
        if (s.covered || t - s.lastCoverCheck < COVER_CHECK_TICKS) return
        s.lastCoverCheck = t
        val after = coverAfter()
        val overdue = s.plan.rooms.values.any { rp ->
            rp.soft && t > rp.arriveT + after && run.state.room(rp.room)?.let { r -> r.check.ordinal < com.engineerclient.p3sim.run.CheckMark.WHITE.ordinal } == true && rp.room !in s.humanTouched
        }
        if (overdue) { s.covered = true; schedule(s, t, "covering the player's rooms") }
    }

    override fun requestReplan(run: Run, reason: String) {
        val s = live(run) ?: return
        schedule(s, maxOf(run.t, 0), reason)
    }

    // ---- events -------------------------------------------------------------------------------------------------------

    override fun onEvent(run: Run, event: RunEvent) {
        val s = live(run) ?: return
        val t = run.t
        when (event) {
            is RoomEntered -> if (s.roster.seatOf(event.player)?.isHuman == true) {
                s.humanTouched[event.room] = t
                val rp = s.plan.rooms[event.room]
                if (rp != null && !rp.soft && rp.arriveT > t) schedule(s, t, "the player took room ${event.room}")
            }
            is DoorOpened -> {
                val opener = event.by?.let { s.roster.seatOf(it) }?.takeIf { !it.isHuman }
                if (opener != null) {
                    // The breaker id is the opener's own entity id when its body is drawn (on Hypixel it is), else one of ours per door.
                    val c = Crack(t, s.bodies.entityId(opener.index) ?: (CRACK_ID_BASE + event.door))
                    s.cracking[event.door] = c
                    crack(run, event.door, 0, c.breakerId)
                }
                val byHuman = event.by != null && s.roster.seatOf(event.by)?.isHuman == true
                val planned = s.plan.doors[event.door]
                if (byHuman && planned != null && planned.openT > t + 10) schedule(s, t, "the player opened door ${event.door}")
            }
            is RoomCleared, is RoomGreen -> {
                val room = if (event is RoomCleared) event.room else (event as RoomGreen).room
                val rp = s.plan.rooms[room]
                if (rp != null && !rp.soft && ((if (event is RoomCleared) rp.whiteT else rp.greenT) ?: Int.MAX_VALUE) > t + EARLY_TICKS) schedule(s, t, "room $room finished early")
            }
            is PlayerDamaged -> s.roster.seatOf(event.player)?.takeIf { !it.isHuman }?.let { if (event.percent > 0.0) s.bodies.hurt(it.index) }
            is PlayerDied -> if (s.roster.seatOf(event.player)?.isHuman == false) schedule(s, t, "a bot died")
            is PlayerRevived -> if (s.roster.seatOf(event.player)?.isHuman == false) schedule(s, t, "a bot came back")
            is KeyDropped -> keyDropped(s, event, t)
            is KeyPicked -> { s.stands.remove(event.kind); s.excursion.values.forEach { it.release() } }
            is PlayerLeft -> if (s.roster.seatOf(event.player)?.isHuman == true) { s.covered = true; schedule(s, t, "a player left") }
            is SavepointRestored -> restoredNow(run, s)
            is DoorFell -> s.cracking.remove(event.door)?.let { crack(run, event.door, 255, it.breakerId) }
            is BloodDoorOpened -> {}
            else -> {}
        }
    }

    /**
     * A key stand appeared. A bot that is already near it (the one that killed in the room) steps onto it, so the doors module's pickup (by
     * position) sees it; nobody is sent from across the dungeon. If nobody has taken the stand [FALLBACK_WAIT] ticks later and no human is at it,
     * the nearest bot within [FALLBACK_REACH] blocks walks over.
     */
    private fun keyDropped(s: Session, e: KeyDropped, t: Int) {
        val stand = Pt(e.x, e.z)
        s.stands[e.kind] = StandWatch(stand, t)
        val near = nearestBot(s, stand, NEAR_STAND) ?: return
        s.excursion.putIfAbsent(near, Excursion(stand, STAND_HOLD_TICKS))
    }

    /** The seat of the living bot nearest to [at] within [within] blocks that is not on a trip, or null. */
    private fun nearestBot(s: Session, at: Pt, within: Double): Int? {
        val free = s.roster.bots.filter { b -> s.excursion[b.index] == null && s.run.state.players[s.roster.uuids.getValue(b.index)]?.ghost == false }
        return StandDuty.nearest(free.mapNotNull { b -> s.pos[b.index]?.let { b.index to it } }.toMap(), at, within)
    }

    /** Sends a bot for a stand that has waited too long for anybody to take it. */
    private fun standFallback(run: Run, s: Session, t: Int) {
        if (s.stands.isEmpty()) return
        val humans = try { Sim.players() } catch (e: Throwable) { emptyList() }
        for ((_, w) in s.stands) {
            if (w.sentFallback || t - w.droppedT < FALLBACK_WAIT) continue
            w.sentFallback = true
            if (StandDuty.someoneThere(humans.map { Pt(it.x, it.z) }, w.at)) continue
            val seat = nearestBot(s, w.at, FALLBACK_REACH) ?: continue
            s.excursion[seat] = Excursion(w.at, FALLBACK_HOLD_TICKS)
        }
    }

    // ---- savepoints ---------------------------------------------------------------------------------------------------------

    override fun snapshot(run: Run): JsonObject? {
        val s = live(run) ?: return null
        return JsonObject().also { o ->
            o.addProperty("covered", s.covered)
            o.add("bots", JsonArray().also { a ->
                for ((seat, p) in s.pos) a.add(JsonObject().also { b -> b.addProperty("seat", seat); b.addProperty("x", p.x); b.addProperty("z", p.z) })
            })
        }
    }

    override fun restore(run: Run, data: JsonObject) {
        val s = live(run) ?: return
        s.covered = data.get("covered")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        data.getAsJsonArray("bots")?.forEach { e ->
            val b = e.asJsonObject
            s.pos[b.get("seat").asInt] = Pt(b.get("x").asDouble, b.get("z").asDouble)
        }
    }

    /** After every module restored: forget what the old plan finished and plan again from the restored run. */
    private fun restoredNow(run: Run, s: Session) {
        s.world.reset()
        s.bodies.removeAll()
        s.humanTouched.clear()
        s.excursion.clear(); s.stands.clear()
        s.executor.forgetFinished()
        replan(run, s, run.t.coerceAtLeast(0))
    }

    // ---- the service -------------------------------------------------------------------------------------------------------

    override fun botIds(run: Run): List<UUID> = live(run)?.let { s -> s.roster.bots.map { s.roster.uuids.getValue(it.index) } } ?: emptyList()

    override fun ownerOfRoom(run: Run, roomIndex: Int): UUID? {
        val s = live(run) ?: return null
        val seat = s.plan.rooms[roomIndex]?.seat ?: return null
        return s.roster.uuids[seat]
    }

    override fun leapTargets(run: Run, viewer: UUID): List<LeapTarget> {
        val s = session?.takeIf { it.run === run }
        val me = run.state.players[viewer]
        val order = leapOrder(me?.dungeonClass)
        val out = ArrayList<LeapTarget>()
        for (cls in order) {
            val rec = run.state.players.values.firstOrNull { it.dungeonClass == cls && it.uuid != viewer } ?: continue
            val bot = s?.roster?.seatOf(rec.uuid)?.takeIf { !it.isHuman }
            if (bot != null && s != null) {
                // A far bot's chunk may not be loaded: asking for its block loads it, so the leap lands on real ground.
                val p = s.pos[bot.index] ?: Pt(rec.x, rec.z)
                RunBlocks.get(Math.floor(p.x).toInt(), 69, Math.floor(p.z).toInt())
                val y = s.bodies.groundY(p.x, p.z, s.y[bot.index] ?: rec.y.takeIf { it != 0.0 } ?: BotTuning.DEFAULT_FLOOR_Y)
                s.y[bot.index] = y
                out += LeapTarget(rec.uuid, rec.name, cls, p.x, y, p.z, s.yaw[bot.index] ?: 0f, 0f, !rec.ghost)
            } else {
                val pl = try { Sim.players().firstOrNull { it.uuid == rec.uuid } } catch (t: Throwable) { null }
                out += LeapTarget(rec.uuid, rec.name, cls, pl?.x ?: rec.x, pl?.y ?: rec.y, pl?.z ?: rec.z, pl?.yRot ?: 0f, pl?.xRot ?: 0f, !rec.ghost && pl != null)
            }
        }
        return out
    }

    /** The classes in the viewer's leap order (slots 1 to 4): the boss sim's order for them, else the usual order without them. */
    private fun leapOrder(cls: String?): List<String> {
        try {
            val viewer = cls?.let { c -> DungeonClass.entries.firstOrNull { it.name == c } }
            if (viewer != null) return P3Plan.orderFor(viewer).map { it.name }
        } catch (t: Throwable) { /* the boss sim's order is not available (headless): the usual order */ }
        return BotRoster.CLASSES.filter { it != cls }
    }

    // ---- dev commands (BotCommands) -----------------------------------------------------------------------------------------

    override fun devCommands(): List<AdsCommand> = BotCommands.commands()

    const val COVER_CHECK_TICKS = 40
    /** A bot this near a fresh key stand steps onto it. */
    const val NEAR_STAND = 6.0
    /** Ticks a bot waits on a stand it stepped onto for the key to be taken, before it goes back anyway. */
    const val STAND_HOLD_TICKS = 4
    /** Ticks after a drop before a bot is sent for a stand nobody has taken, the farthest it is sent from, and how long it waits there. UNMEASURED. */
    const val FALLBACK_WAIT = 20
    const val FALLBACK_REACH = 12.0
    const val FALLBACK_HOLD_TICKS = 40
    /** The height of the door cells the crack is drawn on (the real packets sit at y 70) and the fake breaker ids the cracks use (one per door). */
    const val DOOR_CRACK_Y = 70
    const val CRACK_ID_BASE = 1_900_000
    /** A room finishing this many ticks before the plan says is "early" and makes a re-plan. */
    const val EARLY_TICKS = 60
}
