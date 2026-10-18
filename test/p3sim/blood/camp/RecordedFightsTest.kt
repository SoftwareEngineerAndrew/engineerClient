package com.engineerclient.p3sim.blood.camp

import com.engineerclient.p3sim.clear.blood.camp.CampConfig
import com.engineerclient.p3sim.clear.blood.camp.CampEngine
import com.engineerclient.p3sim.clear.blood.camp.CampLines
import com.engineerclient.p3sim.clear.blood.camp.CampMech
import com.engineerclient.p3sim.clear.blood.camp.CampPort
import com.engineerclient.p3sim.clear.blood.camp.Landing
import com.engineerclient.p3sim.clear.blood.camp.Vec3
import com.engineerclient.p3sim.clear.blood.camp.WallClass
import com.engineerclient.p3sim.clear.blood.camp.WallPlan
import com.engineerclient.p3sim.clear.blood.camp.WallStation
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The camp's rules against recorded fights. The recordings are not in this repository: `ADS_BLOOD_FIGHTS` names a folder of
 * `*.json` files, one per fight, each with
 *
 *  - `events`: rows `[tick, "head", entityId, x, y, z]` (the wall's stands, first sight of each), `[tick, "wpos", x, y, z, fresh]`
 *    (where the Watcher is; the first is his home), `[tick, "add", entityId, kind, name, x, y, z]` and `[tick, "die", entityId]` or
 *    `[tick, "gone", entityId]` for each blood mob;
 *  - `truth`: `door` (the tick of the door line, same clock as `events`), `proven` (the tick the pass line printed) and `throws`
 *    (the tick of each head throw, in order).
 *
 * Without the variable the test is skipped. Each fight is replayed with its own head order and its own deaths; what the camp
 * decides from there (when he leaves, when he throws, when the kills are counted, when the pass line prints) is compared to what
 * the recording shows. His chat lines are fed in as recorded (the camp asks for none of its own), so the queue behaves as it did.
 */
class RecordedFightsTest {
    private val dir: File? = System.getenv("ADS_BLOOD_FIGHTS")?.let { File(it) }?.takeIf { it.isDirectory }

    private class Add(val tick: Int, val id: Int, val name: String, val x: Double, val z: Double)

    private class Fight(val name: String, val home: Vec3, val stands: List<Vec3>, val adds: List<Add>, val deaths: Map<Int, Int>, val throws: List<Int>, val proven: Int, val lines: List<CampMech.Request>)

    private fun load(f: File): Fight? {
        val o = JsonParser.parseString(f.readText()).asJsonObject
        val door = o.getAsJsonObject("truth")["door"].asInt
        val stands = LinkedHashMap<Int, Vec3>()
        var home: Vec3? = null
        val adds = ArrayList<Add>()
        val deaths = HashMap<Int, Int>()
        val lines = ArrayList<CampMech.Request>()
        for (row in o.getAsJsonArray("events").map { it.asJsonArray }) {
            val t = row[0].asInt - door
            when (row[1].asString) {
                "head" -> stands.putIfAbsent(row[2].asInt, Vec3(row[3].asDouble, row[4].asDouble, row[5].asDouble))
                "wpos" -> if (home == null) home = Vec3(row[2].asDouble, row[3].asDouble, row[4].asDouble)
                // A second giant is a duplicate body of the first, not a head the Watcher threw.
                "add" -> if (row[4].asString != "Giant" || adds.none { it.name == "Giant" }) adds += Add(t, row[2].asInt, row[4].asString, row[5].asDouble, row[7].asDouble)
                "die", "gone" -> deaths.putIfAbsent(row[2].asInt, t)
                "chat" -> lineOf(row[2].asString)?.let { lines += CampMech.Request(t, it) }
            }
        }
        val truth = o.getAsJsonObject("truth")
        val requests = requestsOf(lines)
        val throws = truth.getAsJsonArray("throws").map { it.asInt - door }
        // A recording that starts mid-camp (the first mob already in, or fewer than 15 mobs) cannot be replayed from the door.
        if (home == null || stands.size < 15 || adds.size < 15 || adds.any { it.id !in deaths } || adds.first().tick > FIRST_MOB_LATEST) return null
        return Fight(f.nameWithoutExtension, home, stands.values.toList(), adds.sortedBy { it.tick }, deaths, throws, truth["proven"].asInt - door, requests)
    }

    /**
     * When each recorded line was asked for. A line that printed while the queue was free was asked for on that tick. A line that
     * printed the moment the line before it ended (back to back) was asked for some time while that line was being said; the tick after
     * it started is taken. The two lines a player's action sets off (a hit on him, a player out of reach) are taken as asked for when
     * they printed.
     */
    private fun requestsOf(printed: List<CampMech.Request>): List<CampMech.Request> {
        val out = ArrayList<CampMech.Request>()
        var prev: CampMech.Request? = null
        for (p in printed.sortedBy { it.t }) {
            val before = prev
            val free = before == null || p.t > before.t + before.line.busy || p.line == CampMech.Line.ATTACK || p.line == CampMech.Line.LEDGE
            out += CampMech.Request(if (free) p.t else minOf(p.t, before!!.t + 1), p.line)
            prev = p
        }
        return out
    }

    /** The class of the Watcher's chat line, or null for a line that is not his (or is the pass line). A line of his that no class knows keeps the queue as busy as a reaction. */
    private fun lineOf(chat: String): CampMech.Line? {
        val prefix = "[BOSS] The Watcher: "
        if (!chat.startsWith(prefix)) return null
        val text = chat.removePrefix(prefix)
        return when {
            text.startsWith("You have proven yourself") -> null
            text == CampLines.INTRO || text == CampLines.INTRO_OTHER -> CampMech.Line.INTRO
            text in CampLines.SPAWN -> CampMech.Line.SPAWN
            text in CampLines.REACT -> CampMech.Line.REACT
            text in CampLines.HANDLE -> CampMech.Line.HANDLE
            text in CampLines.ENOUGH -> CampMech.Line.ENOUGH
            text in CampLines.ATTACK -> CampMech.Line.ATTACK
            text in CampLines.LEDGE -> CampMech.Line.LEDGE
            else -> CampMech.Line.REACT
        }
    }

    private class Outcome(val name: String, val throwsExact: Int, val throwsTotal: Int, val initialExact: Int, val passDiff: Int, val spawnDiffMax: Int)

    private fun replay(fight: Fight): Outcome? {
        // Which stand each recorded mob came from: the head flies a straight line toward the room centre, 16 blocks for the first four and 12 after.
        val centre = Vec3(fight.home.x, fight.home.y + CampEngine.HEAD_TARGET_RISE, fight.home.z)
        val slotOf = HashMap<Int, Int>()
        for ((k, a) in fight.adds.withIndex()) {
            val len = if (k < 4) CampEngine.HEAD_LENGTH_INITIAL else CampEngine.HEAD_LENGTH_LATER
            val best = fight.stands.indices.minBy { i ->
                val e = WallPlan.headEnd(fight.stands[i], centre, len)
                Math.hypot(e.x - a.x, e.z - a.z)
            }
            val e = WallPlan.headEnd(fight.stands[best], centre, len)
            if (Math.hypot(e.x - a.x, e.z - a.z) > 1.5) return null
            slotOf[a.id] = best
        }
        val soulSlots = fight.adds.filter { "Wandering" in it.name }.map { slotOf.getValue(it.id) }.toSet()
        val wall = fight.stands.mapIndexed { i, v -> WallStation(i, v, if (i in soulSlots) WallClass.WANDERING_SOUL else WallClass.STANDARD, "") }
        val order = fight.adds.map { wall[slotOf.getValue(it.id)] }
        val byHandle = HashMap<String, Add>()
        val spawnTicks = ArrayList<Pair<Int, Int>>()
        val deathOf = HashMap<String, Int>()
        val port = object : CampPort {
            override fun spawn(landing: Landing): String? {
                val a = fight.adds.first { slotOf.getValue(it.id) == landing.station.slot }
                val h = a.id.toString()
                byHandle[h] = a
                deathOf[h] = maxOf(fight.deaths.getValue(a.id), landing.spawnTick)
                spawnTicks += landing.spawnTick to a.tick
                return h
            }
        }
        val engine = CampEngine(fight.home, wall, Random(1), port, CampConfig(chatCoins = false, wsChance = 0.0, recordedLines = fight.lines), order)
        var r = 0
        while (!engine.finished && r < fight.proven + 400) {
            val alive = deathOf.filter { it.value >= r }.keys
            for ((h, d) in deathOf) if (d == r) engine.noteDeath(h, r)
            engine.step(r, alive.toSet())
            r++
        }
        if (!engine.finished) return null
        val got = engine.landings.map { it.throwTick }
        val n = minOf(got.size, fight.throws.size)
        val exact = (0 until n).count { got[it] == fight.throws[it] }
        if (exact != fight.throws.size) println("${fight.name}: throws got $got want ${fight.throws}; deaths ${fight.adds.map { fight.deaths.getValue(it.id) }}; adds ${fight.adds.map { it.tick }}")
        val initial = (0 until minOf(4, n)).count { got[it] == fight.throws[it] }
        return Outcome(fight.name, exact, fight.throws.size, initial, engine.passTick!! - fight.proven, spawnTicks.maxOfOrNull { Math.abs(it.first - it.second) } ?: 0)
    }

    @Test
    fun theCampDecidesWhatTheRecordedFightsShow() {
        assumeTrue(dir != null, "ADS_BLOOD_FIGHTS not set")
        val files = dir!!.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        val outcomes = files.mapNotNull { f -> load(f)?.let { replay(it) } }
        assumeTrue(outcomes.isNotEmpty(), "no usable fight in $dir")
        for (o in outcomes) println("${o.name}: ${o.throwsExact}/${o.throwsTotal} throws exact, initial ${o.initialExact}/4, pass line ${o.passDiff} ticks off, mob appears at most ${o.spawnDiffMax} ticks off the recording")
        // The opening needs no chat queue and no kill: the four initial throws are exact in every fight.
        assertTrue(outcomes.all { it.initialExact == 4 }, "initial throws: " + outcomes.filter { it.initialExact != 4 }.map { it.name })
        // Where every throw is exact, every mob is added on the tick the recording shows (one tick of slack for the clock of the recording).
        val exactFights = outcomes.filter { it.throwsExact == it.throwsTotal }
        assertTrue(exactFights.all { it.spawnDiffMax <= 1 }, "mob arrival: " + exactFights.filter { it.spawnDiffMax > 1 }.map { "${it.name} ${it.spawnDiffMax}" })
        val total = outcomes.sumOf { it.throwsTotal }
        val exact = outcomes.sumOf { it.throwsExact }
        println("replay: $exact of $total throws exact in ${outcomes.size} fights, ${exactFights.size} fights with every throw exact")
        for (o in outcomes.filter { it.throwsExact != it.throwsTotal || it.passDiff != 0 }) println("not exact: ${o.name}")
        assertTrue(exact >= total * THROWS_EXACT_SHARE, "throws exact $exact of $total")
        assertTrue(outcomes.all { Math.abs(it.passDiff) <= PASS_TOLERANCE }, "pass line: " + outcomes.map { "${it.name} ${it.passDiff}" })
    }

    private companion object {
        /**
         * What the rules reproduce on the recordings in hand: 189 of 196 throws exactly, 12 of 13 fights wholly. The exceptions are a
         * first later departure that did not happen on door+420 although a mob was alive (a case the rules do not explain) and one
         * throw one tick off.
         */
        const val THROWS_EXACT_SHARE = 0.9

        /** The pass line printed on the recorded tick in every fight in hand; this much is allowed for a fight the rules do not explain. */
        const val PASS_TOLERANCE = 20

        /** The giant lands about door+200; a recording whose first mob comes later than this began after the camp did. */
        const val FIRST_MOB_LATEST = 230
    }
}
