package com.engineerclient.p3sim.clear.state

import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.*
import com.engineerclient.p3sim.run.api.DoorOpenResult
import com.engineerclient.p3sim.run.api.KeyPlan
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID
import kotlin.math.roundToInt

/** One step of the start of a run, on the run clock. */
sealed interface StartStep {
    /** "<name> is now ready!" for the party member at [index]. */
    data class Ready(val index: Int) : StartStep
    /** "Starting in N seconds." */
    data class Starting(val seconds: Int) : StartStep
    /** Mort's line [index] (0 = the first, which is T0 and the start of the CLEAR stage). */
    data class Mort(val index: Int) : StartStep
    data object EntranceDoorFalls : StartStep
    data object FreeDoorFalls : StartStep
    /** The entrance room is discovered. */
    data object EntranceDiscovered : StartStep
    data object EntranceGreen : StartStep
    /** A practice run's start: straight into the clear. */
    data object PracticeGo : StartStep
}

/** A key stand on the floor, waiting for someone to pick it up. */
class KeyStand(val id: Int, val kind: KeyKind, val room: Int, val x: Double, val y: Double, val z: Double, val spawnT: Int, val killer: UUID?)

/**
 * The run-state logic of the clear, for one run: the countdown and Mort's lines, the doors (coal -> barrier -> air), the keys,
 * the room check marks and the tile counters, room discovery and entry from positions. Reads and writes [RunState] and the bus
 * through [run], and does everything to the world through [fx], so the whole thing runs headless in the tests.
 *
 * Time is the run clock (`run.t`) throughout, so a savepoint restore (which puts the clock back) replays it correctly.
 * Server thread only.
 */
class StateController(val run: Run, val rules: StateRules, val cfg: StateConfig, val fx: StateEffects) {
    val plan: DungeonPlan = run.plan
    val state: RunState = run.state
    val keyPlan: KeyPlan = KeyPlans.of(plan)
    val ledger = KeyLedger(state.keys, keyPlan)
    private val falls = DoorFalls()
    private val schedule = TickSchedule<StartStep>()
    private val rng = run.subRandom("doors")

    /** The last run tick the controller has processed. */
    var lastT: Int = 0
        private set
    private val stands = LinkedHashMap<Int, KeyStand>()
    private var nextStandId = 1
    /** Room -> run tick at which a room with no required mob counts as done. */
    private val synth = LinkedHashMap<Int, Int>()
    /** Room -> run tick at which a key room that dropped no key gets one at its centre. */
    private val keyFallback = LinkedHashMap<Int, Int>()
    /** The last required kill in each room: (killer, run tick). The killer takes the key stand in the tick it appears. */
    private val lastKill = HashMap<Int, Pair<UUID, Int>>()
    /** Run tick at which the blood room's check turns green (set by the Watcher's pass line, brought forward by a portal notice), or null. */
    private var bloodGreenAt: Int? = null
    /**
     * Room -> the world cell of the chest whose opening finishes it: the trap rooms the pack has a rule for (`chestClears`), turned with
     * the room's placement. A room without a placement or a rule is not in it and keeps the timer.
     */
    private val clearChests: Map<Int, Triple<Int, Int, Int>> by lazy {
        buildMap {
            for (r in plan.rooms) {
                val cell = rules.chestClear(r.name)?.cell ?: continue
                val w = r.placement?.toWorld(cell.x, cell.y, cell.z) ?: continue
                put(r.index, Triple(w.x, w.y, w.z))
            }
        }
    }

    // ---- start ---------------------------------------------------------------------------------------------------------

    /** Plans the countdown: sets T0, schedules every line and door of the start. Call once, in `onRunStart`. */
    fun start() {
        if (plan.practice != null) { startPractice(); return }
        val lead = rules.startLeadTicks
        val delay = cfg.countdownDelayTicks.coerceAtLeast(0)
        // "Starting in 4" -> "1 second" is 60 ticks (3 gaps of 20), "1 second" -> Mort is `lead`.
        run.setT0(run.serverTick + delay + 60 + lead)
        lastT = run.t - 1
        schedule.clear()
        val s4 = -(60 + lead)
        for (i in 0 until 5) schedule.add(maxOf(s4 - 12 + i, lastT + 1), StartStep.Ready(i))
        for (n in 4 downTo 1) schedule.add(-(lead + 20 * (n - 1)), StartStep.Starting(n))
        val entranceFall = -lead + rules.entranceFallAfterStart1
        schedule.add(entranceFall + rules.freeDoorOffset, StartStep.FreeDoorFalls)
        schedule.add(entranceFall, StartStep.EntranceDoorFalls)
        schedule.add(0, StartStep.Mort(0)); schedule.add(0, StartStep.EntranceDiscovered)
        schedule.add(40, StartStep.Mort(1)); schedule.add(70, StartStep.Mort(2))
        schedule.add(rules.entranceNtoG, StartStep.EntranceGreen)
        state.milestones.countdownStartTick = state.milestones.countdownStartTick ?: run.serverTick
        refreshCounters()
    }

    /** Practice has no countdown, no Mort and no entrance: the clock starts now and the clear with it. */
    private fun startPractice() {
        run.setT0(run.serverTick)
        lastT = run.t - 1
        schedule.clear()
        schedule.add(0, StartStep.PracticeGo)
        state.milestones.countdownStartTick = state.milestones.countdownStartTick ?: run.serverTick
        refreshCounters()
    }

    // ---- tick ----------------------------------------------------------------------------------------------------------

    fun tick() {
        // "Not Ready" in Mort's window holds the countdown: T0 moves on with the server, so the clock stays on this tick.
        if (run.stage == RunStage.COUNTDOWN && plan.practice == null && run.t < 0 && ReadyUpMenu.holding()) run.t0Tick?.let { run.setT0(it + 1) }
        val t = run.t
        fx.tick()
        for (step in schedule.due(lastT, t)) runStep(step)
        for (s in falls.restDue(t, lastT)) applyStep(s)
        for (s in falls.due(t)) { applyStep(s); state.doors[s.door].phase = DoorPhase.OPEN; run.bus.post(DoorFell(t, s.door)) }
        // The steps up to now have run: set before the room entries, since one takes a savepoint and its saved clock must not run this tick's steps again.
        lastT = t
        detectRooms()
        pollRooms(t)
        pollStands(t)
    }

    private fun runStep(step: StartStep) {
        when (step) {
            is StartStep.Ready -> state.players.values.toList().getOrNull(step.index)?.let { fx.chat(StateText.ready(it.name, nameColourOf(it.uuid))) }
            is StartStep.Starting -> { fx.chat(StateText.startingIn(step.seconds)); fx.sound(StateText.COUNTDOWN_TICK) }
            is StartStep.Mort -> {
                if (step.index == 0) classDoubledLines()
                fx.chat(StateText.mort(step.index)); fx.sound(StateText.MORT_LINE)
                if (step.index == 0) run.requestStage(RunStage.CLEAR)
                if (step.index == 1) MortNpc.blankNames(MortNpc.BLANK_AFTER_SECOND_LINE)
            }
            StartStep.EntranceDoorFalls -> plan.doors.filter { it.kind == DoorKind.ENTRANCE }.forEach { beginFall(it.index, null) }
            StartStep.FreeDoorFalls -> plan.doors.filter { it.kind == DoorKind.FAIRY }.forEach { beginFall(it.index, null) }
            StartStep.EntranceDiscovered -> plan.entrance?.let { discover(it.index) }
            StartStep.EntranceGreen -> plan.entrance?.let { mark(it.index, CheckMark.GREEN) }
            StartStep.PracticeGo -> run.requestStage(RunStage.CLEAR)
        }
    }

    /** "Your <class> stats are doubled..." to every human whose class nobody else in the party has, on the tick of Mort's first line. */
    private fun classDoubledLines() {
        val all = state.players.values.toList()
        for (p in all) {
            if (p.isBot) continue
            if (all.count { it.dungeonClass.equals(p.dungeonClass, ignoreCase = true) } != 1) continue
            StateText.classDoubled(p.dungeonClass)?.forEach { fx.chat(it, p.uuid) }
        }
    }

    // ---- doors ---------------------------------------------------------------------------------------------------------

    /** The index of the door whose volume holds the block, or null. */
    fun doorAtBlock(x: Int, y: Int, z: Int): Int? = plan.doorContaining(x, y, z)?.index

    /**
     * A player (or bot) uses a closed door. A wither or blood door opens when the party has the key (one key per door, shared);
     * without it the door stays and the player hears and reads the no-key line. The entrance and Fairy doors fall by themselves.
     */
    fun tryOpen(door: Int, by: UUID?): DoorOpenResult {
        val d = plan.doors.getOrNull(door) ?: return DoorOpenResult.NOT_A_DOOR
        if (!d.kind.hasBody) return DoorOpenResult.NOT_A_DOOR
        val rec = state.doors[door]
        if (rec.phase != DoorPhase.CLOSED) return DoorOpenResult.ALREADY_OPEN
        val needs = when (d.kind) { DoorKind.WITHER -> KeyKind.WITHER; DoorKind.BLOOD -> KeyKind.BLOOD; else -> return DoorOpenResult.ALREADY_OPEN }
        if (!ledger.consume(needs)) { noKey(door, by); return DoorOpenResult.NO_KEY }
        val t = run.t
        beginFall(door, by)
        if (needs == KeyKind.WITHER) {
            fx.chat(StateText.witherOpened(nameOf(by), nameColourOf(by))); fx.sound(StateText.DOOR_OPENED)
        } else {
            fx.chat(StateText.BLOOD_OPENED); fx.chat(StateText.SHIVER); fx.sound(StateText.DOOR_OPENED); fx.sound(StateText.BLOOD_DOOR)
            state.milestones.bloodOpenT = t
            run.bus.post(BloodDoorOpened(t))
        }
        return DoorOpenResult.OPENED
    }

    /**
     * A player hit a closed key door (a left click or a dig): Hypixel takes it as a use of the door, so it opens with a key in the party
     * (DR, 139 runs: 168 of your 229 WITHER door openings came from a dig with no right click) or says the no-key line. True when the door
     * is a closed key door that stays shut.
     */
    fun touch(door: Int, by: UUID?): Boolean {
        val d = plan.doors.getOrNull(door) ?: return false
        if (d.kind != DoorKind.WITHER && d.kind != DoorKind.BLOOD) return false
        if (state.doors[door].phase != DoorPhase.CLOSED) return false
        return tryOpen(door, by) != DoorOpenResult.OPENED
    }

    /** How many times Hypixel says the no-key line (and plays its sound) for one hit. */
    private val NO_KEY_REPEAT = 2

    /** The tick each player was last told there is no key. */
    private val noKeyTold = HashMap<UUID?, Int>()

    private fun noKey(door: Int, by: UUID?) {
        val t = run.t
        // Hypixel says it as a pair, line and sound twice, once a tick however many clicks and swings hit the door that tick
        // (DR, 139 runs: 2,025 ticks of 2 lines and 2 sounds, never one; a click, a swing, or both with a dig start all give one pair).
        if (noKeyTold.put(by, t) == t) return
        repeat(NO_KEY_REPEAT) { fx.chat(StateText.NO_KEY_LINE, by); fx.sound(StateText.NO_KEY, by) }
        if (by != null) run.bus.post(DoorLocked(t, door, by))
    }

    /** Opens a door without a key (the start doors, a dev command): barrier now, air later, and both rooms behind it are discovered. */
    fun beginFall(door: Int, by: UUID?) {
        val d = plan.doors.getOrNull(door) ?: return
        val rec = state.doors[door]
        if (rec.phase != DoorPhase.CLOSED || !d.kind.hasBody) return
        val t = run.t
        rec.phase = DoorPhase.FALLING; rec.openedAt = t; rec.openedBy = by
        val delay = if (d.kind == DoorKind.WITHER || d.kind == DoorKind.BLOOD) rules.keyDoorBarrierDelay else 0
        for (s in falls.begin(door, t, rules.barrierFor(d.kind), rules.firstBlockToRestTicks, delay)) applyStep(s)
        fx.spawnDoorDebris(d)
        // A door that fell shows the rooms on both sides on the map. Not the Fairy door that falls at the start: the Fairy room shows when someone walks in
        // (ADR, 180 runs: a median 15 ticks before the first map marker inside, 189 after the start) and the room across it when its own turn comes
        // (416 layouts: a median 137 ticks after the start, 9% by 60).
        if (d.kind != DoorKind.FAIRY) { discover(d.roomA); discover(d.roomB) }
        run.bus.post(DoorOpened(t, door, d.kind, by))
    }

    private fun applyStep(s: FallStep) { plan.doors.getOrNull(s.door)?.let { fx.setBody(it, s.blocks, s.onlyFirst) } }

    private fun nameOf(id: UUID?): String = id?.let { state.players[it]?.name } ?: "Someone"

    /** The colour of a player's name (a bot's rank sets it; everyone else reads as the commonest rank, aqua). */
    private fun nameColourOf(id: UUID?): String = (id?.let { state.players[it]?.extras?.get("nameColour") } as? String) ?: "§b"

    /** The rank bracket in front of a player's name in the key lines. */
    private fun rankBracketOf(id: UUID?): String = (id?.let { state.players[it]?.extras?.get("rankBracket") } as? String) ?: StateText.KEY_RANK_PREFIX

    // ---- keys ----------------------------------------------------------------------------------------------------------

    /** The key this room drops when its last required mob dies, or null (not a key room, or it already dropped). */
    fun keyForRoom(room: Int): KeyKind? = ledger.pending(room)

    /**
     * Drops the room's key stand at ([x], [z]). The killer of the room's last required mob (if known) takes it in the same tick
     * on the measured share (0.669); otherwise the first party member who comes close does. Posts `KeyDropped`.
     */
    fun dropKey(room: Int, x: Double, z: Double) {
        val kind = ledger.pending(room) ?: return
        ledger.markDropped(room)
        keyFallback.remove(room)
        val t = run.t
        val id = nextStandId++
        val y = fx.spawnStand(id, kind, x, z, rules.standYDefault)
        val killer = if (keysFromRecording) null else lastKill[room]?.takeIf { t - it.second <= 2 }?.first
        val stand = KeyStand(id, kind, room, x, y, z, t, killer)
        stands[id] = stand
        run.bus.post(KeyDropped(t, kind, room, x, y, z))
        if (killer != null && rng.nextDouble() < rules.pickupInstantFrac && canPick(killer)) pickUp(stand, killer)
        // A bot that killed for it takes it after the measured wait: bots fight from range and never come within the pickup radius by
        // chance, so a stand they left lay until someone happened past (a replay: 120 ticks, every door after it late).
        else if (killer != null && state.players[killer]?.isBot == true) botPickups[id] = killer to t + rules.pickupDelayed.sample(rng.nextDouble()).roundToInt().coerceAtLeast(1)
    }

    /** Key stands a bot will take: stand id to (bot, tick). */
    private val botPickups = HashMap<Int, Pair<UUID, Int>>()

    private fun canPick(id: UUID): Boolean = state.players[id]?.let { !it.ghost } == true

    /**
     * A replay's pickup: [by] takes the oldest stand of [kind]; with none lying (the recording's key fell out of its player's sight, or
     * ADS's plan had no key due there), one is dropped where [by] stands, in [room] (or the first room still owing one) and taken at once.
     */
    fun pickUpKey(by: UUID, kind: KeyKind, room: Int?, x: Double, z: Double): Boolean {
        stands.values.filter { it.kind == kind }.minByOrNull { it.spawnT }?.let { pickUp(it, by); return true }
        val r = room?.takeIf { ledger.pending(it) == kind } ?: run.plan.rooms.map { it.index }.firstOrNull { ledger.pending(it) == kind } ?: return false
        dropKey(r, x, z)
        stands.values.filter { it.kind == kind }.minByOrNull { it.spawnT }?.let { pickUp(it, by); return true }
        return false
    }

    private fun pickUp(stand: KeyStand, by: UUID) {
        if (stands.remove(stand.id) == null) return
        fx.removeStand(stand.id)
        ledger.pick(stand.kind)
        val t = run.t
        if (stand.kind == KeyKind.BLOOD) state.milestones.bloodKeyT = t
        fx.chat(StateText.keyObtained(stand.kind, nameOf(by), rankBracketOf(by)))
        fx.chat(StateText.rightClick(stand.kind))
        fx.sound(StateText.KEY_OBTAINED); fx.sound(StateText.KEY_SHUTTER)
        run.bus.post(KeyPicked(t, stand.kind, by))
    }

    private fun pollStands(t: Int) {
        if (stands.isEmpty()) { botPickups.clear(); return }
        // A replay takes its keys on the recorded ticks only ([pickUpKey]).
        if (keysFromRecording) return
        for ((id, due) in botPickups.entries.toList()) if (t >= due.second) {
            botPickups.remove(id)
            val stand = stands[id] ?: continue
            if (canPick(due.first)) pickUp(stand, due.first)
        }
        for (stand in stands.values.toList()) {
            val near = state.players.values.filter { !it.ghost }.map { it to dist(it, stand) }
            val taker = near.filter { it.second <= cfg.pickupRadius }.minByOrNull { it.second }?.first
                ?: if (t - stand.spawnT >= rules.autoCollectTicks) near.minByOrNull { it.second }?.first else null
            if (taker != null) pickUp(stand, taker.uuid)
        }
    }

    private fun dist(p: PlayerRecord, s: KeyStand): Double {
        val dx = p.x - s.x; val dz = p.z - s.z; val dy = p.y - s.y
        // Close in the plane, within a few blocks up or down (a floor above is not close).
        return if (Math.abs(dy) > cfg.pickupRadius + 1.5) Double.MAX_VALUE else Math.sqrt(dx * dx + dz * dz)
    }

    // ---- rooms ---------------------------------------------------------------------------------------------------------

    /** Moves a room to [to] through the one writer, then keeps the tile counters in line. */
    private fun mark(room: Int, to: CheckMark) {
        val r = state.rooms.getOrNull(room) ?: return
        val before = r.check
        if (before == to) return
        state.setCheck(room, to)
        refreshCounters()
        if (before == CheckMark.UNDISCOVERED && to == CheckMark.NONE) scheduleSynth(room)
    }

    /** The room appears on the map (a door fell beside it, someone stepped in). */
    fun discover(room: Int) {
        val r = state.rooms.getOrNull(room) ?: return
        if (r.check != CheckMark.UNDISCOVERED) return
        mark(room, RoomChecks.onDiscovered(r.plan.type))
    }

    private fun refreshCounters() {
        val (opened, completed) = RoomChecks.counters(state.rooms)
        state.counters.opened = opened; state.counters.completed = completed
    }

    /**
     * The room's required objective is done (its required mobs are dead, its puzzle solved): WHITE, or GREEN at once when it has no
     * secret left to find. Public so the bots module can finish a room on the measured schedule. The entrance, Fairy and blood rooms
     * have their own rules and are not touched.
     */
    fun objectiveDone(room: Int) {
        val r = state.rooms.getOrNull(room) ?: return
        if (r.plan.type == RoomType.ENTRANCE || r.plan.type == RoomType.FAIRY || r.plan.type == RoomType.BLOOD) return
        if (r.check == CheckMark.WHITE || r.check == CheckMark.GREEN) return
        synth.remove(room)
        mark(room, RoomChecks.onObjectiveDone(r.secretsMax, r.secretsFound))
        // The mobs module drops the key on the kill; a room that finished without one (no mob to kill) still has to pay its key.
        // With no required mob there is no kill to wait for: the key comes now, to whoever finished the room (still standing in it).
        if (ledger.pending(room) != null) { if (r.mobsRequired == 0) fallbackKey(room) else keyFallback.putIfAbsent(room, run.t + cfg.keyFallbackTicks) }
    }

    /** A key room's key with no kill to give it to: dropped at the room's middle and taken by whoever is in the room, if anyone is. */
    private fun fallbackKey(room: Int) {
        val (x, z) = centre(room); dropKey(room, x, z)
        // Left lying, a key in a miniboss room without its miniboss held up every door after it until someone happened past (120 ticks in a replay).
        // A replay takes its keys on the recorded ticks only ([pickUpKey]): the stand waits for whoever the recording says took it.
        if (keysFromRecording) return
        val stand = stands.values.firstOrNull { it.room == room }
        val who = state.rooms.getOrNull(room)?.occupants?.firstOrNull { canPick(it) }
        if (stand != null && who != null) pickUp(stand, who)
    }

    private fun scheduleSynth(room: Int) {
        if (!cfg.synthesiseClears) return
        val r = state.rooms[room]
        if (r.plan.type !in MOB_TYPES) return
        // A room with a chest rule (the trap rooms) is finished by the player opening that chest, never by a timer.
        if (room in clearChests) return
        val delay = rules.whiteDelay(r.plan.name, r.plan.type.name, rng.nextDouble())
        synth[room] = run.t + delay
    }

    private fun pollRooms(t: Int) {
        for (r in state.rooms) {
            if (r.plan.type !in MOB_TYPES) continue
            when (r.check) {
                CheckMark.NONE -> if (r.mobsRequired > 0 && r.mobsRequiredAlive <= 0) objectiveDone(r.index)
                CheckMark.WHITE -> if (RoomChecks.secretsComplete(r.secretsMax, r.secretsFound)) mark(r.index, CheckMark.GREEN)
                else -> {}
            }
        }
        for ((room, at) in synth.entries.toList()) if (t >= at) {
            synth.remove(room)
            val r = state.rooms[room]
            if (r.check == CheckMark.NONE && (r.mobsRequired == 0 || room in clearChests)) objectiveDone(room)
        }
        bloodGreenAt?.let { at -> if (t >= at) greenBlood() }
        for ((room, at) in keyFallback.entries.toList()) if (t >= at) {
            keyFallback.remove(room)
            if (ledger.pending(room) != null) fallbackKey(room)
        }
    }

    /** World (x, z) of the middle of a room, from its tile centres. */
    fun centre(room: Int): Pair<Double, Double> {
        val tiles = plan.rooms[room].tiles
        val x = tiles.map { DungeonFrame.tileCentre(it.x) + 0.5 }.average()
        val z = tiles.map { DungeonFrame.tileCentre(it.z) + 0.5 }.average()
        return x to z
    }

    /** Who is in which room, from positions; a first step into a room discovers it and posts `RoomEntered`. */
    private fun detectRooms() {
        for (p in state.players.values) {
            val inBox = DungeonFrame.inDungeonBox(Math.floor(p.x).toInt(), Math.floor(p.z).toInt())
            val here = if (inBox && !p.ghost) state.roomAt(p.x, p.z)?.index else null
            if (here == null) {
                // In a door gap the player keeps the room they came from; outside the dungeon (or a ghost) they are in none.
                if (!inBox || p.ghost) leaveRoom(p)
                continue
            }
            if (p.roomIndex == here) continue
            leaveRoom(p)
            p.roomIndex = here
            val r = state.rooms[here]
            r.occupants += p.uuid; r.visitors += p.uuid
            discover(here)
            run.bus.post(RoomEntered(run.t, here, p.uuid))
        }
    }

    private fun leaveRoom(p: PlayerRecord) {
        p.roomIndex?.let { state.rooms.getOrNull(it)?.occupants?.remove(p.uuid) }
        p.roomIndex = null
    }

    // ---- events --------------------------------------------------------------------------------------------------------

    fun onEvent(e: RunEvent) {
        when (e) {
            is MobKilled -> {
                val room = e.mob.room ?: return
                if (e.mob.required && e.by != null) lastKill[room] = e.by to e.t
                val r = state.rooms.getOrNull(room) ?: return
                if (e.mob.required && r.check == CheckMark.NONE && r.mobsRequiredAlive <= 0) objectiveDone(room)
            }
            is SecretFound -> state.rooms.getOrNull(e.room)?.let { r ->
                if (r.check == CheckMark.WHITE && RoomChecks.secretsComplete(r.secretsMax, r.secretsFound)) mark(r.index, CheckMark.GREEN)
                chestOpened(r, e)
            }
            is PuzzleSolved -> state.rooms.getOrNull(e.room)?.let { r ->
                if (r.check != CheckMark.GREEN) { if (r.check == CheckMark.UNDISCOVERED) discover(r.index); forceObjective(r.index) }
            }
            is PuzzleFailed -> state.rooms.getOrNull(e.room)?.let { r ->
                if (r.check != CheckMark.GREEN) { if (r.check == CheckMark.UNDISCOVERED) discover(r.index); mark(r.index, CheckMark.RED) }
            }
            is StageChanged -> when (e.to) {
                RunStage.BLOOD -> plan.blood?.let { b -> bloodGreenAt = null; discover(b.index); mark(b.index, CheckMark.RED) }
                // A party in the boss before the blood room's check is due leaves it red for good (139 DR and ADR runs: the 71 that reached
                // Maxor 62 to 92 ticks after the pass line never turned it green; the 60 that turned it green did so 64 to 95 ticks after the pass,
                // before Maxor).
                RunStage.BOSS_ENTRY, RunStage.BOSS -> bloodGreenAt = null
                else -> {}
            }
            // The room stays red through the Watcher's pass line; the check comes with the portal, about 90 ticks after his last kill.
            is WatcherDone -> scheduleBloodGreen(e.t + rules.bloodGreenAfterPass)
            is ModuleEvent -> when {
                e.source == "bots" -> botNotice(e)
                // The blood module tells the tick its portal blocks appear; the check follows one tick later (it is optional: without it the timer above runs).
                e.source == "blood" && e.name == "portal" -> scheduleBloodGreen(e.t + rules.bloodGreenAfterPortal)
            }
            is PlayerLeft -> state.players[e.player]?.let { leaveRoom(it) }
            // The bots check rooms off on the measured schedule; they tell this (the only writer of the marks) when a room's turn comes (the `ModuleEvent` branch above).
            is SavepointRestored -> refreshCounters()
            else -> {}
        }
    }

    /** The blood room's green check is due at [at] (run tick), or earlier if a check is already due sooner. Nothing to do once it is green. */
    private fun scheduleBloodGreen(at: Int) {
        val b = plan.blood ?: return
        if (state.rooms[b.index].check == CheckMark.GREEN) return
        bloodGreenAt = minOf(bloodGreenAt ?: Int.MAX_VALUE, at)
    }

    private fun greenBlood() {
        bloodGreenAt = null
        plan.blood?.let { mark(it.index, CheckMark.GREEN) }
    }

    /**
     * Someone opened a chest. When it is the chest that finishes this trap room (the pack's rule, turned with the room), the room's
     * mark follows after the measured 0 to 5 ticks. The other chests of the room change nothing.
     */
    private fun chestOpened(r: RoomRecord, e: SecretFound) {
        val cell = clearChests[r.index] ?: return
        if (e.x != cell.first || e.y != cell.second || e.z != cell.third) return
        if (r.check != CheckMark.NONE || r.index in synth) return
        val delay = rules.chestOpenDelay(r.plan.name, rng.nextDouble())
        if (delay <= 0) objectiveDone(r.index) else synth[r.index] = run.t + delay
    }

    /** A bot's room check-off: `clear.white` finishes the room's objective (green when no secret is left), `clear.green` and `clear.puzzle` make it green. */
    private fun botNotice(e: ModuleEvent) {
        val room = (e.data["room"] as? Number)?.toInt() ?: return
        val r = state.rooms.getOrNull(room) ?: return
        when (e.name) {
            "clear.white" -> { discover(room); objectiveDone(room) }
            "clear.green", "clear.puzzle" -> {
                if (r.plan.type == RoomType.ENTRANCE || r.plan.type == RoomType.FAIRY || r.plan.type == RoomType.BLOOD) return
                discover(room)
                if (r.check == CheckMark.NONE) objectiveDone(room)
                // GREEN only with every secret counted: the secrets module counts what the room still owes for the teammate who cleared it.
                if (r.secretsFound < r.secretsMax) Services.secrets.creditRoom(run, room, (e.data["player"] as? String)?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() })
                if (r.check != CheckMark.GREEN && RoomChecks.onObjectiveDone(r.secretsMax, r.secretsFound) == CheckMark.GREEN) mark(room, CheckMark.GREEN)
            }
        }
    }

    /** A solved puzzle: the room is done even from RED (a failed puzzle that is solved later turns green). */
    private fun forceObjective(room: Int) {
        val r = state.rooms[room]
        if (r.check == CheckMark.RED) {
            // RED -> GREEN again (11 of 530 puzzles failed first, FORMAT-runstate 6.2); a room with secrets left goes WHITE.
            mark(room, RoomChecks.onObjectiveDone(r.secretsMax, r.secretsFound))
        } else objectiveDone(room)
    }

    // ---- savepoints ------------------------------------------------------------------------------------------------------

    fun snapshot(): JsonObject = JsonObject().also { o ->
        o.addProperty("lastT", lastT)
        o.addProperty("nextStandId", nextStandId)
        o.add("dropped", JsonArray().also { a -> ledger.droppedRooms().forEach { a.add(it) } })
        o.add("stands", JsonArray().also { a ->
            for (s in stands.values) a.add(JsonObject().also { j ->
                j.addProperty("id", s.id); j.addProperty("kind", s.kind.name); j.addProperty("room", s.room)
                j.addProperty("x", s.x); j.addProperty("y", s.y); j.addProperty("z", s.z); j.addProperty("t", s.spawnT)
                s.killer?.let { j.addProperty("killer", it.toString()) }
            })
        })
        o.add("synth", JsonArray().also { a -> synth.forEach { (r, at) -> a.add(JsonObject().also { j -> j.addProperty("room", r); j.addProperty("at", at) }) } })
        o.add("keyFallback", JsonArray().also { a -> keyFallback.forEach { (r, at) -> a.add(JsonObject().also { j -> j.addProperty("room", r); j.addProperty("at", at) }) } })
        bloodGreenAt?.let { o.addProperty("bloodGreenAt", it) }
    }

    /**
     * Rebuilds the controller's own state after `RunState.restore` and the region resync: the doors that were open are written
     * open again (the resync put them back closed), the ones falling resume, the key stands are made again.
     */
    fun restore(o: JsonObject) {
        lastT = o.get("lastT")?.asInt ?: run.t
        nextStandId = o.get("nextStandId")?.asInt ?: 1
        ledger.restoreDropped(o.getAsJsonArray("dropped")?.map { it.asInt }.orEmpty())
        falls.clear(); synth.clear(); keyFallback.clear(); lastKill.clear()
        bloodGreenAt = o.get("bloodGreenAt")?.asInt
        fx.reset()
        for (j in o.getAsJsonArray("synth").orEmpty()) j.asJsonObject.let { synth[it["room"].asInt] = it["at"].asInt }
        for (j in o.getAsJsonArray("keyFallback").orEmpty()) j.asJsonObject.let { keyFallback[it["room"].asInt] = it["at"].asInt }
        for (d in plan.doors) {
            val rec = state.doors[d.index]
            if (!d.kind.hasBody) continue
            when (rec.phase) {
                DoorPhase.OPEN -> fx.setBody(d, BodyBlocks.AIR)
                DoorPhase.FALLING -> { fx.setBody(d, BodyBlocks.BARRIER); falls.resume(d.index, rec.openedAt ?: run.t, rules.barrierFor(d.kind)) }
                DoorPhase.CLOSED -> {}
            }
        }
        fx.clearStands(); stands.clear(); botPickups.clear()
        for (j in o.getAsJsonArray("stands").orEmpty()) {
            val s = j.asJsonObject
            val kind = KeyKind.valueOf(s["kind"].asString)
            val id = s["id"].asInt
            val y = fx.spawnStand(id, kind, s["x"].asDouble, s["z"].asDouble, s["y"].asDouble)
            stands[id] = KeyStand(id, kind, s["room"].asInt, s["x"].asDouble, y, s["z"].asDouble, s["t"].asInt, s.get("killer")?.asString?.let { UUID.fromString(it) })
        }
        refreshCounters()
    }

    /** Removes the world things this controller made (key stands). The doors are the layout's. */
    fun end() { fx.clearStands(); stands.clear(); botPickups.clear() }

    /** The stands on the floor now (dev command, tests). */
    fun standList(): List<KeyStand> = stands.values.toList()

    companion object {
        /** A replay: keys drop and are taken on the recorded ticks only (no drop on a kill, no pickup by coming close). */
        @Volatile @JvmStatic var keysFromRecording = false

        /** Room types whose objective is a mob kill. Puzzles have their own solver; the entrance, Fairy and blood rooms their own rules. */
        private val MOB_TYPES = setOf(RoomType.NORMAL, RoomType.TRAP, RoomType.YELLOW, RoomType.RARE)

        private fun JsonArray?.orEmpty(): List<com.google.gson.JsonElement> = this?.toList().orEmpty()
    }
}
