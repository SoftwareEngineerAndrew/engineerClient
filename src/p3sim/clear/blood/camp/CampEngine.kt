package com.engineerclient.p3sim.clear.blood.camp

import com.engineerclient.p3sim.clear.blood.camp.CampMech.Line
import com.engineerclient.p3sim.clear.blood.camp.CampMech.Request
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.random.Random

/** What the engine asks of the world. [WatcherCamp] implements it with the blood host; tests use a fake. */
interface CampPort {
    /** Puts the mob in the room now. Returns a handle for it, or null when it could not be made (the engine then treats it as dead at once). */
    fun spawn(landing: Landing): String?

    /** A head leaves its wall stand on the throw tick. Default: nothing to show. */
    fun headThrown(station: WallStation) {}

    /**
     * The thrown head flies in a straight line from its stand to [end] over [ticks] ticks (80 for the first four, 40 for the
     * later ones), spinning, and is removed on arrival. Called on the throw tick, right after [headThrown]. Default: nothing.
     */
    fun flyHead(station: WallStation, end: Vec3, ticks: Int) {}

    /** The thrown head has arrived at its end point and is gone. Default: nothing. */
    fun headArrived(station: WallStation) {}

    /** The Watcher is at ([x], [y], [z]) facing [yaw] (Minecraft degrees) on this tick; called each tick while he flies. Default: he does not move. */
    fun watcherAt(x: Double, y: Double, z: Double, yaw: Float) {}

    /** The Watcher says a line of this class (the port picks the words). Called on the tick his chat queue prints it. */
    fun say(line: Line) {}

    /**
     * The counted kills went up to [total] (1 to 15) on this loop tick: his health and bar step down, and the 15th is the defeat.
     * Called once per tick on which the total changed, never twice with the same total. Default: nothing.
     */
    fun killsCounted(total: Int) {}

    /** A line for the debug log. */
    fun log(text: String) {}
}

/** Switches that change nothing about the mechanism, only how much of the Watcher's chat queue is simulated and which optional legs happen. */
class CampConfig(
    /** The random chat lines (a reaction to a kill, a line on a throw) that keep his chat queue busy; they delay his first move and the pass line a little. */
    val chatCoins: Boolean = true,
    /** Counted kills that defeat him. */
    val killsToDefeat: Int = CampMech.KILLS_TO_DEFEAT,
    /** Later throws. */
    val laterThrows: Int = CampMech.LATER_THROWS,
    /** Chance the Wandering Soul head is thrown as an extra, uncounted leg (measured: 23 of about 700 camps). */
    val wsChance: Double = WallPlan.WS_THROW_CHANCE,
    /** Chance of "That will be enough for now." when he flies home at the end (measured: 7 of 104 camps). */
    val enoughChance: Double = 0.07,
    /**
     * The lines of a recorded camp as the queue saw them (the tick each was asked for, which is its printing tick when the queue was
     * free), for replaying that camp. When set, the camp asks for no line of its own (no coins, no home line, no farewell) and these
     * stand in for all of them, the opening line included.
     */
    val recordedLines: List<CampMech.Request>? = null,
)

/**
 * The Watcher's blood camp as a live state machine: the one 10-tick task that runs from the door click, fed kills as they happen.
 * No Minecraft, no other EC classes: [CampPort] is the whole outside world.
 *
 * Ticks are door ticks: [step] is called with r = ticks since the blood door opened (0 on the tick it opened), once per r.
 * The Watcher's body is not simulated, only where he would be: a flight is the stop rule of [CampMech.flyTo] between his rest
 * point and a station (plus one block up), which gives the throw tick and the mob's landing tick exactly as the real task does.
 *
 *  - Opening: loop ticks from door+90 every 30 ticks while idle: four hops at 0.70 (a giant, a revived boss, two standard
 *    mobs), then home at 0.50. Initial heads fly 80 ticks, so the mobs land about door+200 to 330.
 *  - Later: from door+420 on a loop tick while idle, FAST (0.6125) on door+40k if no mob is alive, SLOW (0.4375) on door+60k
 *    if any is. The first departure also needs 40 ticks at home (SLOW) or no line of his around it (FAST). Later heads fly 40.
 *  - Kills: a death on tick d is counted on the first loop tick after d, and each new total goes to [CampPort.killsCounted] on that
 *    tick (his health and bar step with it). The 15th count is the defeat tick D; the pass line waits for his chat queue (0 to 20
 *    ticks after D), and [finished] turns true on the tick it prints.
 *
 * "Alive on a decision tick D" means spawned before D and not dead before D: a mob killed on D itself still counts alive.
 */
class CampEngine(
    private val home: Vec3,
    stations: List<WallStation>,
    private val rng: Random,
    private val port: CampPort,
    private val config: CampConfig = CampConfig(),
    /** The throw order, when it is already known (a restored camp); null draws it from [stations]. */
    order: List<WallStation>? = null,
) {
    private enum class State { OPENING, AT_HOME, LATER, DONE }

    private class Mob(val handle: String?, val spawnTick: Int, val ws: Boolean = false) {
        var death = Int.MAX_VALUE
        var counted = false
        val alive get() = death == Int.MAX_VALUE
    }

    private val throwOrder = order ?: WallPlan.throwOrder(stations, rng, config.laterThrows, config.wsChance)
    private val bySlot = stations.associateBy { it.slot }
    private val mobs = ArrayList<Mob>()
    private val byHandle = HashMap<String, Mob>()
    private val scheduled = ArrayList<Landing>()
    private val thrown = ArrayList<Landing>()
    private val requests = ArrayList<Request>().also { it += config.recordedLines ?: listOf(Request(3, Line.INTRO)) }

    private var state = State.OPENING
    private var pos = home
    private var movingUntil = -1
    private var throws = 0
    private var homeArrival = 0
    private var kills = 0
    private var last = -1
    private var defeatPrinted = -1
    private var pendingFinish = -1
    private var moveStart = 0
    private var moveFrom = home
    private var moveTo = home
    private var moveV = 0.0
    private var moveN = 0
    private val said = HashSet<CampMech.Printed>()
    private val arrivals = ArrayList<Pair<Int, WallStation>>()

    /** True on and after the tick the pass line prints. */
    var finished = false
        private set

    /** Kills counted so far (the Watcher's health bar). */
    val killsCounted get() = kills

    /** The door tick the pass line prints, once known. */
    val passTick get() = defeatPrinted.takeIf { it >= 0 }

    /** The tick of the first departure for the later heads, once it happened. */
    var firstLaterDecision = -1
        private set

    /** The landing plan so far, in throw order. */
    val landings: List<Landing> get() = thrown

    /** Where the Watcher would be now (his rest point or the end of his current leg). */
    val watcherRest: Vec3 get() = pos

    /** A mob of this engine died on door tick [r] (reported by the world). A death on a decision tick still counts as alive on it. */
    fun noteDeath(handle: String, r: Int) {
        val m = byHandle[handle] ?: return
        if (m.alive) m.death = r
    }

    /** Runs every tick up to [r] that has not run yet; [aliveNow] is the set of this camp's mob handles the world still has. */
    fun step(r: Int, aliveNow: Set<String>? = null) {
        if (finished) return
        var t = last + 1
        while (t <= r && !finished) {
            // A mob the world no longer has and nobody reported: gone (a removed or lost mob must not hold the camp up).
            if (aliveNow != null && t == r) for (m in mobs) if (m.alive && m.handle != null && m.spawnTick < t && m.handle !in aliveNow) {
                m.death = t
                port.log("mob ${m.handle} vanished without a kill report on tick $t; counted dead")
            }
            tickOne(t)
            t++
        }
        last = maxOf(last, r)
    }

    private fun tickOne(r: Int) {
        // Landings due now (before the decisions: alive needs spawn < r).
        val due = scheduled.filter { it.spawnTick == r }
        for (l in due) {
            val h = port.spawn(l)
            val m = Mob(h, r, l.station.cls == WallClass.WANDERING_SOUL)
            if (h == null) { m.death = r; port.log("a camp mob could not be spawned at tick $r; counted dead") } else byHandle[h] = m
            mobs += m
        }
        scheduled.removeAll(due)
        // Heads leave their stands on the throw tick and are gone when they arrive.
        for (l in thrown) if (l.throwTick == r) { port.headThrown(l.station); port.flyHead(l.station, headEnd(l), if (l.initial) CampMech.FLIGHT_INITIAL else CampMech.FLIGHT_LATER) }
        val gone = arrivals.filter { it.first == r }
        for (g in gone) port.headArrived(g.second)
        arrivals.removeAll(gone)
        flyWatcher(r)
        sayLines(r)
        if (pendingFinish >= 0) { if (r >= pendingFinish) finished = true; return }
        if (!CampMech.isLoop(r)) return
        // ---- the kill counter
        val newly = mobs.filter { !it.counted && !it.ws && it.death < r }
        for (m in newly) {
            m.counted = true
            kills++
            // Asked for the next tick: this tick's lines have already been printed above, and a line whose slot is in the past is never said
            // (a request for r itself was silently dropped, so the Watcher never reacted to a kill).
            if (config.chatCoins && rng.nextBoolean()) ask(Request(r + 1, Line.REACT))
        }
        // His health packet steps on this same tick, and the 15th step is the defeat the exit timers count from.
        if (newly.isNotEmpty()) port.killsCounted(kills)
        if (kills >= config.killsToDefeat) {
            val lines = CampMech.chatLines(requests.filter { it.t <= r }, r)
            defeatPrinted = CampMech.passTick(r, lines)
            state = State.DONE
            pendingFinish = defeatPrinted
        }
        if (pendingFinish >= 0) { if (r >= pendingFinish) finished = true; return }
        val m10 = r / CampMech.LOOP
        if (movingUntil >= r) return
        val alive = mobs.count { !it.ws && it.spawnTick < r && it.death >= r }
        when {
            state == State.OPENING && m10 % 3 == 0 && m10 >= CampMech.OPEN_FIRST / CampMech.LOOP -> {
                if (throws < 4) throwAt(r, CampMech.V_OPEN, initial = true) else goHome(r)
            }
            (state == State.AT_HOME || state == State.LATER) && throws < throwOrder.size && m10 >= 42 -> {
                val speed = when {
                    alive == 0 && m10 % 4 == 0 -> CampMech.Speed.FAST
                    alive > 0 && m10 % 6 == 0 -> CampMech.Speed.SLOW
                    else -> return
                }
                if (state == State.AT_HOME) {
                    if (speed == CampMech.Speed.SLOW && r - homeArrival < CampMech.HOME_MIN) return
                    if (speed == CampMech.Speed.FAST && CampMech.talking(requests, r)) return
                    state = State.LATER
                    firstLaterDecision = r
                }
                throwAt(r, speed.v, initial = false)
            }
            state == State.LATER && throws == throwOrder.size && m10 % 4 == 0 -> {
                val fly = CampMech.flyTo(pos, home, CampMech.V_HOME)
                beginMove(r, pos, fly, home, CampMech.V_HOME)
                if (rng.nextDouble() < config.enoughChance) ask(Request(r, Line.ENOUGH))
                movingUntil = r + fly.n
                pos = fly.end
                state = State.DONE
            }
        }
    }

    companion object {
        /** A thrown head flies toward the room centre at this height over the Watcher's home height (his home is the room's middle, heads aim 0.3 higher). */
        const val HEAD_TARGET_RISE = 0.3

        /** Straight flight length: 0.2 blocks a tick for 80 ticks, then 0.3 for 40. */
        const val HEAD_LENGTH_INITIAL = 16.0
        const val HEAD_LENGTH_LATER = 12.0
    }

    /** Flies to the next head, throws it 10 ticks after arriving and schedules the mob's landing. */
    private fun throwAt(r: Int, v: Double, initial: Boolean) {
        val st = throwOrder[throws++]
        val fly = CampMech.flyTo(pos, CampMech.target(st.stand), v)
        val arrive = r + fly.n + 1
        val thr = arrive + CampMech.THROW_DELAY
        val spawn = thr + if (initial) CampMech.INITIAL_SPAWN else CampMech.LATER_SPAWN
        val land = thr + if (initial) CampMech.FLIGHT_INITIAL else CampMech.FLIGHT_LATER
        val l = Landing(st, initial, thr, spawn, land)
        beginMove(r, pos, fly, CampMech.target(st.stand), v)
        arrivals += (thr + if (initial) CampMech.FLIGHT_INITIAL else CampMech.FLIGHT_LATER) to st
        scheduled += l
        thrown += l
        pos = fly.end
        movingUntil = r + fly.n
        if (config.chatCoins && rng.nextBoolean()) ask(Request(arrive, Line.SPAWN))
    }

    /** Where the thrown head ends: [WallPlan.headEnd] toward the room centre at head height. */
    private fun headEnd(l: Landing): Vec3 =
        WallPlan.headEnd(l.station.stand, Vec3(home.x, home.y + HEAD_TARGET_RISE, home.z), if (l.initial) HEAD_LENGTH_INITIAL else HEAD_LENGTH_LATER)

    /** Where a thrown head ends, as the mob's landing spot reads it. */
    fun endOf(l: Landing): Vec3 = headEnd(l)

    private fun beginMove(r: Int, from: Vec3, fly: CampMech.Fly, to: Vec3, v: Double) {
        moveStart = r; moveFrom = from; moveTo = to; moveV = v; moveN = fly.n
    }

    /** Moves the Watcher along his current leg: [moveV] blocks a tick toward [moveTo], stopping at the end the leg's flight computed. */
    private fun flyWatcher(r: Int) {
        val k = r - moveStart
        if (moveN <= 0 || k < 1 || k > moveN) return
        val d = moveTo - moveFrom
        val dist = d.length()
        if (dist < 1e-9) return
        val at = moveFrom + d * (minOf(moveV * k, dist) / dist)
        val yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat()
        port.watcherAt(at.x, at.y, at.z, yaw)
    }

    /** Hands every chat line that prints on tick [r] to the port, once. */
    private fun sayLines(r: Int) {
        for (p in CampMech.chatLines(requests.filter { it.t <= r }, r + 1)) if (p.at == r && said.add(p)) port.say(p.line)
    }

    /** Asks for a line of the camp's own, unless a recorded camp's lines stand in for them. */
    private fun ask(request: Request) { if (config.recordedLines == null) requests += request }

    private fun goHome(r: Int) {
        val fly = CampMech.flyTo(pos, home, CampMech.V_HOME)
        movingUntil = r + fly.n
        beginMove(r, pos, fly, home, CampMech.V_HOME)
        homeArrival = r + fly.n + 1
        ask(Request(homeArrival, Line.HANDLE))
        pos = fly.end
        state = State.AT_HOME
    }

    // ---- savepoints ------------------------------------------------------------------------------------------------------------

    /** Everything the camp has decided so far, for a savepoint ([restore] takes it back). Mob handles are the world's own ids. */
    fun snapshot(): JsonObject {
        val o = JsonObject()
        o.addProperty("state", state.name)
        o.addProperty("last", last)
        o.addProperty("throws", throws)
        o.addProperty("kills", kills)
        o.addProperty("movingUntil", movingUntil)
        o.addProperty("homeArrival", homeArrival)
        o.addProperty("defeatPrinted", defeatPrinted)
        o.addProperty("pendingFinish", pendingFinish)
        o.addProperty("firstLaterDecision", firstLaterDecision)
        o.addProperty("finished", finished)
        o.add("pos", vec(pos))
        o.addProperty("moveStart", moveStart)
        o.add("moveFrom", vec(moveFrom)); o.add("moveTo", vec(moveTo))
        o.addProperty("moveV", moveV); o.addProperty("moveN", moveN)
        o.add("order", JsonArray().also { a -> throwOrder.forEach { a.add(it.slot) } })
        o.add("mobs", JsonArray().also { a ->
            for (m in mobs) a.add(JsonObject().also { j ->
                m.handle?.let { j.addProperty("h", it) }
                j.addProperty("spawn", m.spawnTick); j.addProperty("ws", m.ws); j.addProperty("counted", m.counted)
                if (!m.alive) j.addProperty("death", m.death)
            })
        })
        o.add("scheduled", landingsJson(scheduled))
        o.add("thrown", landingsJson(thrown))
        o.add("requests", JsonArray().also { a -> requests.forEach { a.add(JsonArray().also { p -> p.add(it.t); p.add(it.line.name) }) } })
        o.add("said", JsonArray().also { a -> said.forEach { a.add(JsonArray().also { p -> p.add(it.at); p.add(it.line.name); p.add(it.requested) }) } })
        o.add("arrivals", JsonArray().also { a -> arrivals.forEach { a.add(JsonArray().also { p -> p.add(it.first); p.add(it.second.slot) }) } })
        return o
    }

    private fun vec(v: Vec3) = JsonArray().also { it.add(v.x); it.add(v.y); it.add(v.z) }
    private fun vec(a: JsonArray) = Vec3(a[0].asDouble, a[1].asDouble, a[2].asDouble)

    private fun landingsJson(list: List<Landing>) = JsonArray().also { a ->
        for (l in list) a.add(JsonObject().also { j ->
            j.addProperty("slot", l.station.slot); j.addProperty("initial", l.initial)
            j.addProperty("throw", l.throwTick); j.addProperty("spawn", l.spawnTick); j.addProperty("land", l.landTick)
        })
    }

    /**
     * Takes the state [snapshot] wrote. The engine must have been built with the same wall (the same [WallStation]s) and the throw
     * order the snapshot holds (its `order` slots); the chat-line coins and lines drawn after a restore come from the engine's own
     * random source, so they may differ from an uninterrupted camp. Returns false, and changes nothing, if the snapshot does not
     * fit the wall.
     */
    fun restore(o: JsonObject): Boolean {
        try {
            val land = { j: JsonObject -> Landing(bySlot[j["slot"].asInt]!!, j["initial"].asBoolean, j["throw"].asInt, j["spawn"].asInt, j["land"].asInt) }
            val newMobs = o.getAsJsonArray("mobs").map { it.asJsonObject }.map { j ->
                Mob(j["h"]?.asString, j["spawn"].asInt, j["ws"].asBoolean).also { m ->
                    m.counted = j["counted"].asBoolean
                    j["death"]?.let { m.death = it.asInt }
                }
            }
            val newScheduled = o.getAsJsonArray("scheduled").map { land(it.asJsonObject) }
            val newThrown = o.getAsJsonArray("thrown").map { land(it.asJsonObject) }
            val newRequests = o.getAsJsonArray("requests").map { Request(it.asJsonArray[0].asInt, Line.valueOf(it.asJsonArray[1].asString)) }
            val newSaid = o.getAsJsonArray("said").map { CampMech.Printed(it.asJsonArray[0].asInt, Line.valueOf(it.asJsonArray[1].asString), it.asJsonArray[2].asInt) }
            val newArrivals = o.getAsJsonArray("arrivals").map { it.asJsonArray[0].asInt to bySlot[it.asJsonArray[1].asInt]!! }
            val newState = State.valueOf(o["state"].asString)
            val newPos = vec(o.getAsJsonArray("pos"))
            val newFrom = vec(o.getAsJsonArray("moveFrom")); val newTo = vec(o.getAsJsonArray("moveTo"))
            val ints = listOf("last", "throws", "kills", "movingUntil", "homeArrival", "defeatPrinted", "pendingFinish", "firstLaterDecision", "moveStart", "moveN").map { o[it].asInt }
            val newMoveV = o["moveV"].asDouble; val newFinished = o["finished"].asBoolean
            // Everything parsed: now take it.
            state = newState; pos = newPos; moveFrom = newFrom; moveTo = newTo; moveV = newMoveV; finished = newFinished
            last = ints[0]; throws = ints[1]; kills = ints[2]; movingUntil = ints[3]; homeArrival = ints[4]
            defeatPrinted = ints[5]; pendingFinish = ints[6]; firstLaterDecision = ints[7]; moveStart = ints[8]; moveN = ints[9]
            mobs.clear(); mobs += newMobs
            byHandle.clear(); for (m in newMobs) m.handle?.let { byHandle[it] = m }
            scheduled.clear(); scheduled += newScheduled
            thrown.clear(); thrown += newThrown
            requests.clear(); requests += newRequests
            said.clear(); said += newSaid
            arrivals.clear(); arrivals += newArrivals
            return true
        } catch (t: Exception) {
            port.log("the saved camp does not fit this wall: ${t.message}")
            return false
        }
    }
}
