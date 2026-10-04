package com.engineerclient.p3sim

import com.engineerclient.p3sim.GhostPlayer.Kind
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The ghost replay ([GhostPlayer]), its recording ([GhostRecording]), file format ([GhostRun]) and
 * store ([GhostStore]), against a fake party: sections that start when a test says, early enterers
 * that get on their spots when it says, stations that someone else did or that can't be done.
 */
class GhostTest {

    // ------------------------------------------------------------------ building runs

    /** A run, tick by tick: [stand], [walk], [tp] add frames; events land on the last frame (n = [at]). */
    class Rb(clazz: String = "MAGE", skill: String = "PF", start: P = P(0.0, 100.0, 0.0)) {
        val rec = GhostRecording(clazz, skill)
        var pos = start
        var n = 0
        var held = ""
        var yaw = 0f
        val at get() = n - 1

        init { frame() }

        private fun frame() { rec.frame(n++, GhostRun.Frame(pos, yaw, 0f, held)) }
        fun stand(ticks: Int) = apply { repeat(ticks) { frame() } }
        fun walk(to: P, ticks: Int) = apply {
            val from = pos
            for (i in 1..ticks) { pos = from + (to - from) * (i.toDouble() / ticks); frame() }
        }
        fun tp(to: P) = apply { pos = to; frame() }
        fun ev(type: String, a: String = "", k: Int = 0) = apply { rec.event(at, type, a, k) }
        fun section(s: Int) = apply { rec.section(s, at) }
        fun done(id: String) = ev("done", id)
        fun build(): GhostRun = rec.finish(date = 1) ?: fail("no run")
    }

    companion object {
        val A = P(0.0, 100.0, 0.0)
        val T1 = P(20.0, 100.0, 0.0)
        val EE2 = P(60.0, 130.0, 140.0)
        val S2T2 = P(60.0, 120.0, 125.0)
        val EE3 = P(2.0, 109.0, 104.0)
        val S3T1 = P(0.0, 109.0, 112.0)
        val GATE3 = P(12.0, 116.0, 52.0)
        val HEALER_AT = P(45.0, 109.0, 33.0)
        val S4T3 = P(67.0, 109.0, 33.0)
        val CORE = P(54.5, 115.0, 60.0)

        /**
         * A mage's run: S1 T1; leap onto the archer's EE2; S2 T2; its own EE3 (berserk and tank land
         * on it, it leaves once S3 is in); S3 T1; gate 3; leap onto the healer; S4 T3; into the core.
         */
        fun mageRun(): GhostRun = Rb().apply {
            stand(9) // 0..9
            walk(T1, 20) // ..29
            stand(30); done("S1 T1") // 59
            ev("ee", "ARCHER", 2) // archer on EE2 at 59
            stand(10); tp(EE2); ev("leap", "ARCHER") // 70
            stand(29); section(2) // 99
            walk(S2T2, 10) // ..109
            stand(40); done("S2 T2") // 149
            walk(EE3, 20); ev("ee", "MAGE", 3) // 169
            stand(20); ev("landed", "BERSERK") // 189
            stand(10); ev("landed", "TANK") // 199
            stand(10); section(3) // 209
            stand(5); walk(S3T1, 1); ev("left", "MAGE", 3) // 215: off the spot
            stand(43); done("S3 T1") // 258
            walk(GATE3, 10); stand(10); ev("gate", k = 3) // 278
            stand(20); section(4) // 298
            stand(20); tp(HEALER_AT); ev("leap", "HEALER") // 319
            walk(S4T3, 20); stand(40); done("S4 T3"); section(5) // 379
            walk(CORE, 20); ev("core") // 399
            stand(40) // 439
        }.build()
    }

    // ------------------------------------------------------------------ a fake party

    open class World(val run: GhostRun? = null) : GhostPlayer.World {
        var now = 0
        /** n each section starts (live). */
        val sections = HashMap<Int, Int>()
        val done = HashSet<String>()
        val blocked = HashSet<String>()
        val gates = HashSet<Int>()
        /** Who early-enters where (classes), and when they got there. */
        val eeOwner = HashMap<Int, String>()
        val eeAt = HashMap<Int, Int>()
        val readyAt = HashMap<Int, Int>()
        var coreOwner: String? = null
        var recoredAt: Int? = null
        val positions = HashMap<String, P>()
        var held = false

        fun section() = (sections.filter { it.value <= now }.keys.maxOrNull() ?: 1)
        override fun sectionStart(s: Int) = if (s <= 1) 0 else sections[s]?.takeIf { it <= now }
        override fun stationDone(id: String) = id in done
        override fun canDo(id: String): Boolean {
            if (id in done || id in blocked) return false
            val s = GhostPlayer.sectionOf(id)
            // In its section; the fake party moves on without it (the real one can't), so later too.
            return s <= section() || GhostPlayer.isDevice(id)
        }
        override fun gateDown(s: Int) = s in gates
        override fun canGate(s: Int) = section() >= s
        override fun eeArrivedAt(into: Int, who: String): Int? {
            val o = eeOwner[into] ?: return 0
            if (o != who) return 0
            return eeAt[into]?.takeIf { it <= now }
        }
        override fun eeReadyAt(into: Int) = readyAt[into]?.takeIf { it <= now }
        override fun recoredAt(who: String): Int? = if (coreOwner != who) 0 else recoredAt?.takeIf { it <= now }
        override fun positionOf(who: String) = positions[who]

        /** The world as the run had it (everything when it was). */
        fun asRecorded(): World = apply {
            val r = run!!
            for (s in 2..5) if (r.sectionN[s] >= 0) sections[s] = r.sectionN[s]
            for (e in r.events) when (e.type) {
                "ee" -> if (e.a != r.clazz) { eeOwner[e.k] = e.a; eeAt[e.k] = e.n }
                "recore" -> if (e.a != r.clazz) { coreOwner = e.a; recoredAt = e.n }
            }
            // Ready: the last who landed on you there.
            val evs = r.events
            for (e in evs.filter { it.type == "ee" && it.a == r.clazz }) {
                val left = evs.firstOrNull { it.type == "left" && it.k == e.k && it.n >= e.n }?.n ?: r.end
                evs.lastOrNull { it.type == "landed" && it.n in e.n..left }?.let { readyAt[e.k] = it.n }
            }
            // Leap targets where you landed on them.
            for (e in evs.filter { it.type == "leap" }) positions[e.a] = r.frame(e.n).p
        }
    }

    class Body(val world: World) : GhostPlayer.Body {
        val log = ArrayList<String>()
        /** Live n -> where it was shown that tick (the last move). */
        val shown = HashMap<Int, P>()
        val moves = ArrayList<Pair<Int, P>>()
        var working: String? = null
        val workingLog = ArrayList<String?>()
        var swings = 0
        val gaveUp = ArrayList<String>()
        val heldSeen = HashSet<String>()

        /** Where it was at live n (its last move by then). */
        fun at(n: Int) = moves.lastOrNull { it.first <= n }?.second

        fun n(kind: String) = log.firstOrNull { it.startsWith("$kind ") }?.substringAfterLast('@')?.toInt()
        fun nOf(entry: String) = log.firstOrNull { it.substringBeforeLast('@') == entry }?.substringAfterLast('@')?.toInt()

        override fun move(p: P, yaw: Float, pitch: Float, held: String) { shown[world.now] = p; moves += world.now to p; heldSeen += held }
        override fun complete(id: String) { log += "done $id@${world.now}"; world.done += id }
        override fun gate(s: Int) { log += "gate $s@${world.now}"; world.gates += s }
        override fun leap(who: String, at: P) { log += "leap $who@${world.now}" }
        override fun swing() { swings++ }
        override fun eeOn(into: Int) { log += "eeOn $into@${world.now}" }
        override fun eeLeft(into: Int) { log += "eeLeft $into@${world.now}" }
        override fun recore() { log += "recore x@${world.now}" }
        override fun working(id: String?) { working = id; workingLog += id }
        override fun giveUp(job: String) { log += "giveUp $job@${world.now}"; gaveUp += job }
    }

    /** Plays [run] live from 0 to [until], [each] before every tick (the world's moves). */
    fun play(run: GhostRun, world: World, until: Int = run.end + 2000, each: (Int) -> Unit = {}): Pair<GhostPlayer, Body> {
        val body = Body(world)
        val g = GhostPlayer(run, world, body)
        for (n in 0..until) {
            world.now = n
            each(n)
            g.tick(n)
        }
        return g to body
    }

    /** The run's [kind] action (onto / of [arg]). */
    private fun act(r: GhostRun, kind: Kind, arg: String? = null) = GhostPlayer.build(r).single { it.kind == kind && (arg == null || it.arg == arg) }

    private fun close(a: P, b: P, eps: Double = 1e-6) = a.dist(b) <= eps
    private fun assertAt(expected: P, actual: P?, msg: String = "") {
        assertNotNull(actual, msg)
        assertTrue(close(expected, actual, 1e-6), "$msg: expected $expected, was $actual")
    }

    // ------------------------------------------------------------------ GhostRun: the file

    @Test
    fun `json roundtrip keeps everything`() {
        val r = mageRun()
        val back = GhostRun.fromJson(r.toJson())
        assertNotNull(back)
        assertEquals(r.clazz, back.clazz)
        assertEquals(r.skill, back.skill)
        assertEquals(r.time, back.time)
        assertEquals(r.date, back.date)
        assertEquals(r.sectionN.toList(), back.sectionN.toList())
        assertEquals(r.frames.size, back.frames.size)
        assertEquals(r.events, back.events)
        for (i in r.frames.indices) assertTrue(close(r.frames[i].p, back.frames[i].p, 0.01), "frame $i")
    }

    @Test
    fun `json keeps held items as changes`() {
        val rb = Rb()
        rb.held = "INFINITE_SPIRIT_LEAP"; rb.stand(3)
        rb.held = ""; rb.stand(2)
        rb.held = "HYPERION"; rb.stand(1)
        rb.section(5); rb.stand(1)
        val r = rb.build()
        val back = GhostRun.fromJson(r.toJson())!!
        assertEquals(r.frames.map { it.held }, back.frames.map { it.held })
        assertEquals("", back.frames[0].held)
        assertEquals("INFINITE_SPIRIT_LEAP", back.frames[1].held)
        assertEquals("", back.frames[4].held)
        assertEquals("HYPERION", back.frames[6].held)
    }

    @Test
    fun `json rounds positions and angles`() {
        val rb = Rb(start = P(1.23456, 100.0049, -3.14159))
        rb.yaw = 12.345f
        rb.stand(1); rb.section(5)
        val back = GhostRun.fromJson(rb.build().toJson())!!
        assertEquals(1.23, back.frames[0].p.x, 1e-9)
        assertEquals(100.0, back.frames[0].p.y, 1e-9)
        assertEquals(-3.14, back.frames[0].p.z, 1e-9)
        assertEquals(12.3f, back.frames[1].yaw, 1e-4f)
    }

    @Test
    fun `json rejects what isn't a run`() {
        val good = mageRun().toJson()
        assertNull(GhostRun.fromJson(""))
        assertNull(GhostRun.fromJson("not json"))
        assertNull(GhostRun.fromJson("[]"))
        assertNull(GhostRun.fromJson("{}"))
        assertNull(GhostRun.fromJson(good.replace("\"v\":1", "\"v\":2")))
        assertNull(GhostRun.fromJson(good.substring(0, good.length / 2)))
        val o = com.google.gson.JsonParser.parseString(good).asJsonObject
        // Frames cut mid-frame.
        val cut = o.deepCopy().also { val f = it.getAsJsonArray("frames"); f.remove(f.size() - 1) }
        assertNull(GhostRun.fromJson(cut.toString()))
        // No frames.
        assertNull(GhostRun.fromJson(o.deepCopy().also { it.add("frames", com.google.gson.JsonArray()) }.toString()))
        // Sections not 6.
        assertNull(GhostRun.fromJson(o.deepCopy().also { it.add("sections", com.google.gson.JsonArray().apply { add(0) }) }.toString()))
        // Time past the frames.
        assertNull(GhostRun.fromJson(o.deepCopy().also { it.addProperty("time", 100000) }.toString()))
        assertNull(GhostRun.fromJson(o.deepCopy().also { it.addProperty("time", -1) }.toString()))
        // Missing class.
        assertNull(GhostRun.fromJson(o.deepCopy().also { it.remove("class") }.toString()))
        // Events of the wrong shape.
        assertNull(GhostRun.fromJson(o.deepCopy().also { it.add("events", com.google.gson.JsonArray().apply { add("x") }) }.toString()))
    }

    @Test
    fun `json without a date or held list still reads`() {
        val o = com.google.gson.JsonParser.parseString(mageRun().toJson()).asJsonObject
        o.remove("date"); o.remove("held")
        val back = GhostRun.fromJson(o.toString())
        assertNotNull(back)
        assertEquals(0L, back.date)
        assertTrue(back.frames.all { it.held == "" })
    }

    @Test
    fun `frame clamps to the run`() {
        val r = mageRun()
        assertEquals(r.frames.first(), r.frame(-5))
        assertEquals(r.frames.last(), r.frame(r.end + 100))
        assertEquals(r.frames[10], r.frame(10))
    }

    @Test
    fun `jobs lists stations then gates`() {
        assertEquals(listOf("S1 T1", "S2 T2", "S3 T1", "S4 T3", "gate 3"), mageRun().jobs)
    }

    // ------------------------------------------------------------------ GhostRecording

    @Test
    fun `a missed tick repeats the frame before`() {
        val r = GhostRecording("MAGE", "PF")
        r.frame(0, GhostRun.Frame(A, 0f, 0f))
        r.frame(3, GhostRun.Frame(T1, 0f, 0f))
        assertEquals(4, r.frames.size)
        assertEquals(A, r.frames[1].p)
        assertEquals(A, r.frames[2].p)
        assertEquals(T1, r.frames[3].p)
    }

    @Test
    fun `a first frame late fills from it`() {
        val r = GhostRecording("MAGE", "PF")
        r.frame(2, GhostRun.Frame(T1, 0f, 0f))
        assertEquals(3, r.frames.size)
        assertTrue(r.frames.all { it.p == T1 })
    }

    @Test
    fun `a tick recorded twice keeps the later`() {
        val r = GhostRecording("MAGE", "PF")
        r.frame(0, GhostRun.Frame(A, 0f, 0f))
        r.frame(0, GhostRun.Frame(T1, 0f, 0f))
        assertEquals(1, r.frames.size)
        assertEquals(T1, r.frames[0].p)
        r.frame(-1, GhostRun.Frame(A, 0f, 0f))
        assertEquals(1, r.frames.size)
    }

    @Test
    fun `no run without the core`() {
        val rb = Rb(); rb.stand(100); rb.section(2); rb.section(3); rb.section(4)
        assertNull(rb.rec.finish())
        assertNull(GhostRecording("MAGE", "PF").finish())
    }

    @Test
    fun `the run's time is the core's opening`() {
        val r = mageRun()
        assertEquals(379, r.time)
        assertEquals(r.sectionN[5], r.time)
        assertEquals(0, r.sectionN[1])
        assertEquals(listOf(0, 99, 209, 298, 379), r.sectionN.drop(1).toList())
    }

    @Test
    fun `a section's start is only its first`() {
        val r = GhostRecording("MAGE", "PF")
        r.section(2, 50); r.section(2, 60); r.section(9, 1); r.section(0, 1)
        assertEquals(50, r.sectionN[2])
        assertEquals(-1, r.sectionN[0])
    }

    @Test
    fun `a leap clicked a tick before the teleport lands on the teleport`() {
        val rb = Rb(); rb.stand(5)
        rb.ev("leap", "ARCHER") // clicked at 5; the tp shows at 6
        rb.tp(EE2); rb.stand(3); rb.section(5); rb.stand(1)
        val r = rb.build()
        assertEquals(6, r.events.single { it.type == "leap" }.n)
    }

    @Test
    fun `a leap two ticks before the teleport`() {
        val rb = Rb(); rb.stand(5); rb.ev("leap", "ARCHER"); rb.stand(1); rb.tp(EE2); rb.stand(2); rb.section(5); rb.stand(1)
        assertEquals(7, rb.build().events.single { it.type == "leap" }.n)
    }

    @Test
    fun `a leap on its teleport stays`() {
        val rb = Rb(); rb.stand(5); rb.tp(EE2); rb.ev("leap", "ARCHER"); rb.stand(2); rb.section(5); rb.stand(1)
        assertEquals(6, rb.build().events.single { it.type == "leap" }.n)
    }

    @Test
    fun `a leap with no teleport near stays where it was`() {
        val rb = Rb(); rb.stand(5); rb.ev("leap", "ARCHER"); rb.walk(T1, 10); rb.section(5); rb.stand(1)
        assertEquals(5, rb.build().events.single { it.type == "leap" }.n)
    }

    @Test
    fun `a leap at the run's very end`() {
        val rb = Rb(); rb.stand(5); rb.section(5); rb.ev("leap", "ARCHER")
        assertEquals(5, rb.build().events.single { it.type == "leap" }.n)
    }

    @Test
    fun `events past the end are dropped, the rest kept in order`() {
        val r = GhostRecording("MAGE", "PF")
        r.frame(0, GhostRun.Frame(A, 0f, 0f)); r.frame(1, GhostRun.Frame(A, 0f, 0f))
        r.section(5, 1)
        r.event(1, "done", "S1 T2"); r.event(0, "done", "S1 T1"); r.event(5, "done", "S1 T3"); r.event(1, "swing")
        val run = r.finish()!!
        assertEquals(listOf("S1 T1", "S1 T2", ""), run.events.map { it.a })
        assertEquals(listOf(0, 1, 1), run.events.map { it.n })
        r.event(-3, "swing")
        assertEquals(4, r.events.size)
    }

    // ------------------------------------------------------------------ GhostStore

    private fun withStore(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("ghosts").toFile()
        GhostStore.root = dir
        GhostStore.clearCache()
        try { test(dir) } finally { GhostStore.root = null; GhostStore.clearCache(); dir.deleteRecursively() }
    }

    private fun runOf(time: Int, clazz: String = "MAGE", skill: String = "PF"): GhostRun {
        val rb = Rb(clazz, skill); rb.stand(time); rb.section(5); rb.stand(5)
        return rb.build()
    }

    @Test
    fun `the first run is kept and read back from disk`() = withStore { dir ->
        assertNull(GhostStore.best("PF", "MAGE"))
        GhostStore.clearCache()
        val (saved, old) = GhostStore.offer(runOf(500))
        assertTrue(saved); assertNull(old)
        assertTrue(File(dir, "PF/MAGE.json.gz").exists())
        GhostStore.clearCache()
        assertEquals(500, GhostStore.best("PF", "MAGE")?.time)
    }

    @Test
    fun `slower runs aren't kept, faster and equal ones are`() = withStore {
        GhostStore.offer(runOf(500))
        val (s1, o1) = GhostStore.offer(runOf(600))
        assertFalse(s1); assertEquals(500, o1?.time)
        val (s2, o2) = GhostStore.offer(runOf(450))
        assertTrue(s2); assertEquals(500, o2?.time)
        assertTrue(GhostStore.offer(runOf(450)).first)
        GhostStore.clearCache()
        assertEquals(450, GhostStore.best("PF", "MAGE")?.time)
    }

    @Test
    fun `best runs are per skill and class`() = withStore {
        GhostStore.offer(runOf(500, "MAGE", "PF"))
        GhostStore.offer(runOf(400, "TANK", "PF"))
        GhostStore.offer(runOf(300, "MAGE", "Quality PF"))
        GhostStore.clearCache()
        assertEquals(500, GhostStore.best("PF", "MAGE")?.time)
        assertEquals(400, GhostStore.best("PF", "TANK")?.time)
        assertEquals(300, GhostStore.best("Quality PF", "MAGE")?.time)
        assertNull(GhostStore.best("Quality PF", "TANK"))
        assertNull(GhostStore.best("Random", "MAGE"))
    }

    @Test
    fun `odd skill names stay inside the store`() = withStore { dir ->
        GhostStore.offer(runOf(500, "MAGE", "../../evil/x"))
        assertTrue(dir.walkTopDown().any { it.name == "MAGE.json.gz" })
        assertFalse(File(dir.parentFile, "evil").exists())
        GhostStore.clearCache()
        assertEquals(500, GhostStore.best("../../evil/x", "MAGE")?.time)
    }

    @Test
    fun `a broken file is no run (and a new one replaces it)`() = withStore { dir ->
        File(dir, "PF").mkdirs()
        File(dir, "PF/MAGE.json.gz").writeText("garbage")
        assertNull(GhostStore.best("PF", "MAGE"))
        assertTrue(GhostStore.offer(runOf(900)).first)
        GhostStore.clearCache()
        assertEquals(900, GhostStore.best("PF", "MAGE")?.time)
    }

    @Test
    fun `forget removes a best`() = withStore {
        GhostStore.offer(runOf(500))
        GhostStore.forget("PF", "MAGE")
        assertNull(GhostStore.best("PF", "MAGE"))
        GhostStore.clearCache()
        assertNull(GhostStore.best("PF", "MAGE"))
    }

    @Test
    fun `better is by the core's time`() {
        assertTrue(GhostStore.better(runOf(10), null))
        assertTrue(GhostStore.better(runOf(10), runOf(11)))
        assertTrue(GhostStore.better(runOf(10), runOf(10)))
        assertFalse(GhostStore.better(runOf(11), runOf(10)))
    }

    // ------------------------------------------------------------------ the actions

    @Test
    fun `the mage run as actions`() {
        val a = GhostPlayer.build(mageRun())
        assertEquals(listOf(Kind.DONE, Kind.LEAP, Kind.GO, Kind.DONE, Kind.EE_ON, Kind.EE_LEAVE, Kind.DONE, Kind.GATE, Kind.LEAP, Kind.DONE, Kind.ENTER), a.map { it.kind })
        assertEquals(listOf(59, 70, 100, 149, 169, 215, 258, 278, 319, 379, 399), a.map { it.rec })
    }

    @Test
    fun `settling is where you stood still before it`() {
        val r = mageRun()
        // S1 T1: within 3 of the terminal from 26 (walked 9..29, a block a tick).
        assertEquals(26, act(r, Kind.DONE, "S1 T1").settle)
        // S2 T2: walked 99..109 -> stood from ~108 (within 3 blocks).
        assertTrue(act(r, Kind.DONE, "S2 T2").settle in 106..109, "${act(r, Kind.DONE, "S2 T2").settle}")
        // A leap's stand is before the teleport.
        assertEquals(59, act(r, Kind.LEAP, "ARCHER").settle)
        // EE3: on the spot on arrival.
        assertEquals(169, act(r, Kind.EE_ON).settle)
    }

    @Test
    fun `settle never goes before the action before`() {
        val a = GhostPlayer.build(mageRun())
        for (i in 1 until a.size) assertTrue(a[i].settle >= a[i - 1].rec, "${a[i]} before ${a[i - 1]}")
        for (x in a) assertTrue(x.settle <= x.rec, "$x")
    }

    @Test
    fun `settle from a standing start`() {
        val rb = Rb(); rb.stand(50); rb.done("S1 T1"); rb.section(5); rb.stand(1)
        assertEquals(0, GhostPlayer.build(rb.build())[0].settle)
    }

    @Test
    fun `a terminal waits for its section, S1 and devices don't`() {
        val r = mageRun()
        assertTrue(act(r, Kind.DONE, "S1 T1").anchors.isEmpty())
        val s2 = act(r, Kind.DONE, "S2 T2").anchors.single() as GhostPlayer.Anchor.Section
        assertEquals(2, s2.s); assertEquals(99, s2.rec)
        val rb = Rb(); rb.stand(10); rb.done("S2 Lights"); rb.section(2); rb.stand(5); rb.section(5); rb.stand(1)
        assertTrue(GhostPlayer.build(rb.build())[0].anchors.isEmpty())
    }

    @Test
    fun `a gate waits for its section`() {
        val gate = GhostPlayer.build(mageRun()).single { it.kind == Kind.GATE }
        assertEquals(3, gate.k)
        assertEquals("gate 3", gate.job)
        assertEquals(3, (gate.anchors.single() as GhostPlayer.Anchor.Section).s)
        val rb = Rb(); rb.stand(10); rb.ev("gate", k = 1); rb.section(5); rb.stand(1)
        assertTrue(GhostPlayer.build(rb.build())[0].anchors.isEmpty())
    }

    @Test
    fun `a leap onto an early enterer waits for them on their spot`() {
        val leap = act(mageRun(), Kind.LEAP, "ARCHER")
        val on = leap.anchors.single() as GhostPlayer.Anchor.EeOn
        assertEquals(2, on.into); assertEquals("ARCHER", on.who); assertEquals(59, on.rec)
        // Onto the healer (no early enter): only S4, which you stood at the gate until.
        val s4 = act(mageRun(), Kind.LEAP, "HEALER").anchors.single() as GhostPlayer.Anchor.Section
        assertEquals(4, s4.s); assertEquals(298, s4.rec)
    }

    @Test
    fun `a leap onto someone on two early enters waits for the latest before it`() {
        val rb = Rb(); rb.ev("ee", "ARCHER", 2); rb.stand(50); rb.ev("ee", "ARCHER", 4); rb.stand(10); rb.tp(EE2); rb.ev("leap", "ARCHER"); rb.section(5); rb.stand(1)
        val on = GhostPlayer.build(rb.build()).single { it.kind == Kind.LEAP }.anchors.filterIsInstance<GhostPlayer.Anchor.EeOn>().single()
        assertEquals(4, on.into); assertEquals(50, on.rec)
    }

    @Test
    fun `a leap after the early enterer's arrival isn't held for a later arrival`() {
        val rb = Rb(); rb.stand(10); rb.tp(EE2); rb.ev("leap", "ARCHER"); rb.stand(10); rb.ev("ee", "ARCHER", 2); rb.section(5); rb.stand(1)
        assertTrue(GhostPlayer.build(rb.build()).single { it.kind == Kind.LEAP }.anchors.isEmpty())
    }

    @Test
    fun `a leap into the core waits for the recore`() {
        val rb = Rb(); rb.stand(10); rb.section(5); rb.ev("ee", "TANK", 5); rb.stand(20); rb.ev("recore", "TANK"); rb.stand(5); rb.tp(CORE); rb.ev("leap", "TANK"); rb.ev("core"); rb.stand(5)
        val leap = GhostPlayer.build(rb.build()).single { it.kind == Kind.LEAP }
        // The archer's EE, the recore and the core's opening (you stood until it).
        assertEquals(3, leap.anchors.size)
        val re = leap.anchors.filterIsInstance<GhostPlayer.Anchor.Recore>().single()
        assertEquals("TANK", re.who); assertEquals(30, re.rec)
    }

    @Test
    fun `your early enter waits for the last who landed on you`() {
        val a = GhostPlayer.build(mageRun())
        val on = a.single { it.kind == Kind.EE_ON }
        assertEquals(3, on.k); assertEquals(169, on.rec)
        val off = a.single { it.kind == Kind.EE_LEAVE }
        assertEquals(3, off.k); assertEquals(215, off.rec)
        val ready = off.anchors.filterIsInstance<GhostPlayer.Anchor.Ready>().single()
        assertEquals(3, ready.into); assertEquals(199, ready.rec)
        // You also stood there until S3 started (209): on 6 ticks after it.
        assertEquals(209, off.anchors.filterIsInstance<GhostPlayer.Anchor.Section>().single().rec)
        assertEquals(215 - 209, off.work)
    }

    @Test
    fun `landings after you left don't count`() {
        val rb = Rb(); rb.walk(EE3, 5); rb.ev("ee", "MAGE", 3); rb.stand(10); rb.ev("landed", "TANK"); rb.walk(S3T1, 5); rb.ev("left", "MAGE", 3)
        rb.stand(10); rb.ev("landed", "ARCHER"); rb.section(5); rb.stand(1)
        val off = GhostPlayer.build(rb.build()).single { it.kind == Kind.EE_LEAVE }
        assertEquals(15, (off.anchors.single() as GhostPlayer.Anchor.Ready).rec)
    }

    @Test
    fun `landings before you were on don't count`() {
        val rb = Rb(); rb.stand(3); rb.ev("landed", "TANK"); rb.walk(EE3, 5); rb.ev("ee", "MAGE", 3); rb.stand(10); rb.walk(S3T1, 5); rb.ev("left", "MAGE", 3); rb.section(5); rb.stand(1)
        assertTrue(GhostPlayer.build(rb.build()).single { it.kind == Kind.EE_LEAVE }.anchors.none { it is GhostPlayer.Anchor.Ready })
    }

    @Test
    fun `an early enter you never left has no leave`() {
        val rb = Rb(); rb.walk(EE3, 5); rb.ev("ee", "MAGE", 3); rb.stand(10); rb.section(5); rb.stand(1)
        val a = GhostPlayer.build(rb.build())
        assertEquals(1, a.count { it.kind == Kind.EE_ON })
        assertEquals(0, a.count { it.kind == Kind.EE_LEAVE })
    }

    @Test
    fun `only your first time on a spot counts`() {
        val rb = Rb(); rb.walk(EE3, 5); rb.ev("ee", "MAGE", 3); rb.walk(S3T1, 5); rb.ev("left", "MAGE", 3); rb.walk(EE3, 5); rb.ev("ee", "MAGE", 3); rb.walk(S3T1, 5); rb.ev("left", "MAGE", 3); rb.section(5); rb.stand(1)
        val a = GhostPlayer.build(rb.build())
        assertEquals(1, a.count { it.kind == Kind.EE_ON })
        assertEquals(1, a.count { it.kind == Kind.EE_LEAVE })
        assertEquals(10, a.single { it.kind == Kind.EE_LEAVE }.rec)
    }

    @Test
    fun `off your early enter and straight back - not leaving`() {
        val off = EE3 + P(4.0, 0.0, 0.0)
        val rb = Rb(); rb.walk(EE3, 5); rb.ev("ee", "MAGE", 3); rb.stand(5); rb.ev("landed", "TANK")
        rb.tp(off); rb.ev("left", "MAGE", 3); rb.stand(5); rb.tp(EE3); rb.ev("back", "MAGE", 3) // 11 off, 17 back
        rb.stand(10); rb.ev("landed", "BERSERK"); rb.stand(5); rb.walk(S3T1, 1); rb.ev("left", "MAGE", 3); rb.stand(2); rb.section(5); rb.stand(1)
        val a = GhostPlayer.build(rb.build())
        val leave = a.single { it.kind == Kind.EE_LEAVE }
        assertEquals(33, leave.rec)
        // Both landings count, the last the one waited for.
        assertEquals(27, leave.anchors.filterIsInstance<GhostPlayer.Anchor.Ready>().single().rec)
    }

    @Test
    fun `off your early enter and back too late - that was leaving`() {
        val off = EE3 + P(4.0, 0.0, 0.0)
        val rb = Rb(); rb.walk(EE3, 5); rb.ev("ee", "MAGE", 3); rb.stand(5)
        rb.tp(off); rb.ev("left", "MAGE", 3); rb.stand(GhostPlayer.BACK + 5); rb.tp(EE3); rb.ev("back", "MAGE", 3); rb.stand(5); rb.section(5); rb.stand(1)
        assertEquals(11, GhostPlayer.build(rb.build()).single { it.kind == Kind.EE_LEAVE }.rec)
    }

    @Test
    fun `a step off your early enter played back - it stays on it`() {
        val off = EE3 + P(4.0, 0.0, 0.0)
        val rb = Rb(); rb.walk(EE3, 5); rb.ev("ee", "MAGE", 3); rb.stand(5); rb.ev("landed", "TANK")
        rb.tp(off); rb.ev("left", "MAGE", 3); rb.stand(5); rb.tp(EE3); rb.ev("back", "MAGE", 3)
        rb.stand(10); rb.ev("landed", "BERSERK"); rb.stand(5); rb.walk(S3T1, 1); rb.ev("left", "MAGE", 3); rb.stand(2); rb.section(5); rb.stand(1)
        val r = rb.build()
        val w = World(r).asRecorded()
        w.readyAt[3] = 200
        val (_, b) = play(r, w)
        assertEquals(200 + 6, b.nOf("eeLeft 3"))
        // Waiting, it stands on the spot (the last frame before leaving).
        assertAt(EE3, b.at(150))
    }

    @Test
    fun `others' early enters aren't yours`() {
        val a = GhostPlayer.build(mageRun())
        assertEquals(1, a.count { it.kind == Kind.EE_ON })
    }

    @Test
    fun `the core early enter leaves once the core is open`() {
        val rb = Rb(); rb.walk(P(54.5, 115.0, 50.5), 5); rb.ev("ee", "MAGE", 5); rb.stand(20); rb.ev("landed", "TANK"); rb.stand(20); rb.section(5); rb.stand(5)
        rb.walk(CORE, 5); rb.ev("left", "MAGE", 5); rb.ev("core"); rb.ev("recore", "MAGE"); rb.stand(5)
        val a = GhostPlayer.build(rb.build())
        val off = a.single { it.kind == Kind.EE_LEAVE }
        assertEquals(setOf("Ready", "Section"), off.anchors.map { it.javaClass.simpleName }.toSet())
        assertTrue(a.any { it.kind == Kind.RECORE })
        assertTrue(a.any { it.kind == Kind.ENTER })
    }

    @Test
    fun `someone else's recore isn't an action`() {
        val rb = Rb(); rb.stand(5); rb.section(5); rb.ev("recore", "TANK"); rb.stand(5)
        assertTrue(GhostPlayer.build(rb.build()).none { it.kind == Kind.RECORE })
    }

    @Test
    fun `into the core once - the first time`() {
        val rb = Rb(); rb.stand(5); rb.section(5); rb.walk(CORE, 5); rb.ev("core"); rb.walk(A, 5); rb.walk(CORE, 5); rb.ev("core"); rb.stand(1)
        val e = GhostPlayer.build(rb.build()).single { it.kind == Kind.ENTER }
        assertEquals(10, e.rec)
        assertEquals(5, (e.anchors.single() as GhostPlayer.Anchor.Section).s)
    }

    @Test
    fun `swings and landings aren't actions`() {
        val rb = Rb(); rb.stand(5); rb.ev("swing"); rb.ev("landed", "TANK"); rb.ev("ee", "TANK", 2); rb.section(5); rb.stand(1)
        assertTrue(GhostPlayer.build(rb.build()).isEmpty())
    }

    @Test
    fun `several actions in one tick keep their order`() {
        val rb = Rb(); rb.stand(5); rb.done("S1 east lever"); rb.tp(EE2); rb.ev("leap", "ARCHER"); rb.done("S1 SS"); rb.section(5); rb.stand(1)
        assertEquals(listOf("S1 east lever", "ARCHER", "S1 SS"), GhostPlayer.build(rb.build()).map { it.arg })
    }

    @Test
    fun `work is the time after the last thing waited for`() {
        val r = mageRun()
        // S2 T2: stood from ~108, the section from 99: 41ish after the stand.
        val t2 = act(r, Kind.DONE, "S2 T2")
        assertEquals(149 - t2.settle, t2.work)
        // S3 T1: section 3 at 209, stood from 215: after the stand.
        val t31 = act(r, Kind.DONE, "S3 T1")
        assertEquals(215, t31.waitedFor); assertEquals(258 - 215, t31.work)
        // S1 T1: no anchors: from the stand.
        assertEquals(59 - 26, act(r, Kind.DONE, "S1 T1").work)
        // Off EE2 a tick after S2 started.
        assertEquals(1, act(r, Kind.GO).work)
    }

    @Test
    fun `standing until a section started, then off - a departure waiting for it`() {
        val go = act(mageRun(), Kind.GO)
        assertEquals(100, go.rec)
        assertEquals(70, go.settle)
        assertEquals(2, (go.anchors.single() as GhostPlayer.Anchor.Section).s)
    }

    @Test
    fun `no departure where no section started`() {
        val rb = Rb(); rb.stand(30); rb.walk(T1, 10); rb.done("S1 T1"); rb.section(5); rb.stand(1)
        assertTrue(GhostPlayer.build(rb.build()).none { it.kind == Kind.GO })
    }

    @Test
    fun `no departure for a short stop`() {
        val rb = Rb(); rb.walk(T1, 10); rb.stand(2); rb.section(2); rb.walk(S2T2, 10); rb.done("S2 T2"); rb.section(5); rb.stand(1)
        assertTrue(GhostPlayer.build(rb.build()).none { it.kind == Kind.GO })
    }

    @Test
    fun `no departure for the section your own job started`() {
        // Your terminal was S1's last: S2 starts that tick; you walk off 20 ticks later (no waiting on S2).
        val rb = Rb(); rb.walk(T1, 5); rb.stand(20); rb.done("S1 T1"); rb.section(2); rb.stand(20); rb.walk(S2T2, 10); rb.done("S2 T2"); rb.section(5); rb.stand(1)
        assertTrue(GhostPlayer.build(rb.build()).none { it.kind == Kind.GO })
    }

    @Test
    fun `waiting at the core door - a departure for the core`() {
        val door = P(54.5, 115.0, 50.0)
        val rb = Rb(); rb.walk(door, 10); rb.stand(30); rb.section(5); rb.stand(3); rb.walk(CORE, 6); rb.ev("core"); rb.stand(10)
        val a = GhostPlayer.build(rb.build())
        val go = a.single { it.kind == Kind.GO }
        assertEquals(5, go.k)
        assertTrue(a.indexOf(go) < a.indexOfFirst { it.kind == Kind.ENTER })
    }

    @Test
    fun `a departure goes before the action of its tick`() {
        val rb = Rb(); rb.stand(20); rb.section(2); rb.tp(EE2); rb.ev("leap", "ARCHER"); rb.stand(2); rb.section(5); rb.stand(2)
        val a = GhostPlayer.build(rb.build())
        assertEquals(listOf(Kind.LEAP), a.map { it.kind })
        // The leap itself waits for S2 (you stood until it started).
        assertEquals(2, (a.single().anchors.single() as GhostPlayer.Anchor.Section).s)
    }

    @Test
    fun `early enters with ghosts - the plan's when no ghost is involved`() {
        assertEquals("ARCHER", GhostPlayer.eeOwner("ARCHER", "BERSERK", emptyList(), emptySet()))
        assertNull(GhostPlayer.eeOwner(null, "BERSERK", emptyList(), emptySet()))
        assertEquals("BERSERK", GhostPlayer.eeOwner("BERSERK", "BERSERK", emptyList(), emptySet()))
    }

    @Test
    fun `early enters with ghosts - a ghost that did it does it, whoever the plan has`() {
        assertEquals("MAGE", GhostPlayer.eeOwner("ARCHER", "BERSERK", listOf("MAGE"), setOf("MAGE")))
        assertEquals("MAGE", GhostPlayer.eeOwner(null, "BERSERK", listOf("MAGE"), setOf("MAGE")))
        assertEquals("MAGE", GhostPlayer.eeOwner("MAGE", "BERSERK", listOf("MAGE"), setOf("MAGE")))
    }

    @Test
    fun `early enters with ghosts - yours stays yours`() {
        assertEquals("BERSERK", GhostPlayer.eeOwner("BERSERK", "BERSERK", listOf("MAGE"), setOf("MAGE")))
    }

    @Test
    fun `early enters with ghosts - a ghost the plan has there but whose run didn't, doesn't`() {
        assertNull(GhostPlayer.eeOwner("MAGE", "BERSERK", emptyList(), setOf("MAGE")))
        // Another ghost did: that one.
        assertEquals("TANK", GhostPlayer.eeOwner("MAGE", "BERSERK", listOf("TANK"), setOf("MAGE", "TANK")))
    }

    @Test
    fun `early enters with ghosts - two ghosts that did it - the first in leap order`() {
        assertEquals("TANK", GhostPlayer.eeOwner("ARCHER", "BERSERK", listOf("TANK", "MAGE"), setOf("MAGE", "TANK")))
    }

    @Test
    fun `helpers`() {
        assertEquals(2, GhostPlayer.sectionOf("S2 T4"))
        assertEquals(3, GhostPlayer.sectionOf("gate 3"))
        assertEquals(4, GhostPlayer.sectionOf("S4 high lever"))
        assertTrue(GhostPlayer.isDevice("S1 SS")); assertTrue(GhostPlayer.isDevice("S2 Lights")); assertTrue(GhostPlayer.isDevice("S3 Arrows")); assertTrue(GhostPlayer.isDevice("S4 Target"))
        assertFalse(GhostPlayer.isDevice("S1 T1")); assertFalse(GhostPlayer.isDevice("S2 low lever"))
        assertTrue(GhostPlayer.isTerminal("S3 T4")); assertFalse(GhostPlayer.isTerminal("S3 east lever")); assertFalse(GhostPlayer.isTerminal("S1 SS"))
        // Every station's id is one of the three.
        for (st in Station.all()) {
            val kinds = listOf(GhostPlayer.isTerminal(st.id), GhostPlayer.isDevice(st.id), st.kind == Station.Kind.LEVER)
            assertEquals(1, kinds.count { it }, st.id)
            assertEquals(st.kind == Station.Kind.TERMINAL, GhostPlayer.isTerminal(st.id), st.id)
            assertEquals(st.kind == Station.Kind.DEVICE, GhostPlayer.isDevice(st.id), st.id)
            assertEquals(st.section, GhostPlayer.sectionOf(st.id), st.id)
        }
    }

    // ------------------------------------------------------------------ playing it back

    @Test
    fun `as recorded it plays exactly as recorded`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        val (g, b) = play(r, w)
        for (n in 0..r.end) assertAt(r.frame(n).p, b.at(n) ?: b.moves.lastOrNull { it.first <= n }?.second, "n $n")
        assertEquals(59, b.nOf("done S1 T1")); assertEquals(70, b.nOf("leap ARCHER")); assertEquals(149, b.nOf("done S2 T2"))
        assertEquals(169, b.nOf("eeOn 3")); assertEquals(215, b.nOf("eeLeft 3")); assertEquals(258, b.nOf("done S3 T1"))
        assertEquals(278, b.nOf("gate 3")); assertEquals(319, b.nOf("leap HEALER")); assertEquals(379, b.nOf("done S4 T3"))
        assertEquals(0, g.shift)
        assertTrue(g.finished)
        assertTrue(b.gaveUp.isEmpty())
    }

    @Test
    fun `a section later - it waits at its terminal, then goes on as late`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.sections[2] = 99 + 60
        val (g, b) = play(r, w)
        assertEquals(149 + 60, b.nOf("done S2 T2"))
        // On EE2 until S2 started, then off as you went (S2 T2 as long as yours took).
        assertAt(EE2, b.at(130))
        assertAt(EE2, b.at(159))
        assertAt(S2T2, b.at(200))
        // And the rest later too, as long as the rest of the party was (here: as recorded, so it catches up where it can).
        assertEquals(169 + 60, b.nOf("eeOn 3"))
        assertTrue(g.finished)
    }

    @Test
    fun `a section earlier - it does it as soon as it's there, no sooner`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.sections[2] = 75 // you landed at 70 and stood on EE2 until S2 (99); live it starts at 75
        val (_, b) = play(r, w)
        val go = act(r, Kind.GO)
        val a = act(r, Kind.DONE, "S2 T2")
        // Off EE2 a tick after 75, then the walk and the terminal as you did them.
        val shift = 76 - go.rec
        assertEquals(maxOf(a.settle + shift, 75) + a.work, b.nOf("done S2 T2"))
        assertEquals(149 + shift, b.nOf("done S2 T2"))
        assertAt(EE2, b.at(75))
        assertTrue(b.at(77)!!.dist(EE2) > 0.1)
    }

    @Test
    fun `it never shows a frame from before one it showed`() {
        val r = mageRun()
        for (seed in 0 until 40) {
            val rnd = Random(seed)
            val w = World(r).asRecorded()
            var prev = 1
            for (s in 2..5) { w.sections[s] = (r.sectionN[s] + rnd.nextInt(-60, 120)).coerceAtLeast(prev + 1); prev = w.sections[s]!! }
            w.eeAt[2] = w.eeAt[2]!! + rnd.nextInt(-40, 200)
            w.readyAt[3] = w.readyAt[3]!! + rnd.nextInt(-100, 300)
            val body = Body(w)
            val g = GhostPlayer(r, w, body)
            var last = -1
            for (n in 0..r.end + 3000) {
                w.now = n
                g.tick(n)
                assertTrue(g.shown >= last, "seed $seed n $n: ${g.shown} < $last")
                last = g.shown
            }
            assertTrue(g.finished, "seed $seed: ${g.status}")
            assertEquals(r.jobs.size, body.log.count { it.startsWith("done ") || it.startsWith("gate ") }, "seed $seed: ${body.log}")
        }
    }

    @Test
    fun `everything fires in run order, each after what it waits for`() {
        val r = mageRun()
        for (seed in 0 until 40) {
            val rnd = Random(seed)
            val w = World(r).asRecorded()
            var prev = 1
            for (s in 2..5) { w.sections[s] = (r.sectionN[s] + rnd.nextInt(-80, 200)).coerceAtLeast(prev + 1); prev = w.sections[s]!! }
            w.eeAt[2] = (w.eeAt[2]!! + rnd.nextInt(-50, 300)).coerceAtLeast(0)
            w.readyAt[3] = (w.readyAt[3]!! + rnd.nextInt(-150, 400)).coerceAtLeast(0)
            val (_, b) = play(r, w)
            val order = listOf("done S1 T1", "leap ARCHER", "done S2 T2", "eeOn 3", "eeLeft 3", "done S3 T1", "gate 3", "leap HEALER", "done S4 T3")
            assertTrue(b.nOf("leap HEALER")!! >= w.sections[4]!!, "seed $seed: leapt before S4 (you waited for it)")
            assertTrue(b.nOf("eeLeft 3")!! >= w.sections[3]!!, "seed $seed")
            val ns = order.map { b.nOf(it) ?: fail("seed $seed: no $it in ${b.log}") }
            assertEquals(ns.sorted(), ns, "seed $seed")
            assertTrue(b.nOf("leap ARCHER")!! >= w.eeAt[2]!!, "seed $seed: leapt before the archer was there")
            assertTrue(b.nOf("done S2 T2")!! >= w.sections[2]!!, "seed $seed: S2 terminal before S2")
            assertTrue(b.nOf("done S3 T1")!! >= w.sections[3]!!, "seed $seed")
            assertTrue(b.nOf("gate 3")!! >= w.sections[3]!!, "seed $seed")
            assertTrue(b.nOf("done S4 T3")!! >= w.sections[4]!!, "seed $seed")
            assertTrue(b.nOf("eeLeft 3")!! >= w.readyAt[3]!!, "seed $seed: left before everyone was on")
        }
    }

    @Test
    fun `positions are your frames, but for a landing still blending out`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.positions["HEALER"] = HEALER_AT + P(5.0, 0.0, -3.0)
        val (g, b) = play(r, w)
        assertAt(HEALER_AT + P(5.0, 0.0, -3.0), b.at(319), "landed on the healer where it is")
        // Blended out by the next stand (S4 T3).
        val next = act(r, Kind.DONE, "S4 T3")
        assertAt(r.frame(next.settle).p, b.at(next.settle), "where you were at the terminal")
        // In between: part way.
        val mid = (319 + next.settle) / 2
        val off = b.at(mid)!! - r.frame(mid).p
        assertTrue(off.dist(P(0.0, 0.0, 0.0)) in 0.5..6.0, "mid $off")
        assertTrue(g.finished)
    }

    @Test
    fun `the blend shrinks every tick to nothing`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.positions["HEALER"] = HEALER_AT + P(8.0, 2.0, 0.0)
        val (g, _) = play(r, w)
        val next = act(r, Kind.DONE, "S4 T3")
        var prev = Double.MAX_VALUE
        for (t in 319..next.settle) {
            val d = g.blend(t).dist(P(0.0, 0.0, 0.0))
            assertTrue(d <= prev + 1e-9, "t $t: $d > $prev")
            prev = d
        }
        assertEquals(0.0, g.blend(next.settle).dist(P(0.0, 0.0, 0.0)), 1e-9)
        assertEquals(0.0, g.blend(318).dist(P(0.0, 0.0, 0.0)), 1e-9)
        assertEquals(0.0, g.blend(next.settle + 50).dist(P(0.0, 0.0, 0.0)), 1e-9)
    }

    @Test
    fun `a leap waits for its early enterer, standing where you stood`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.eeAt[2] = 150
        val (_, b) = play(r, w)
        val leap = act(r, Kind.LEAP, "ARCHER")
        assertEquals(150 + leap.work, b.nOf("leap ARCHER"))
        // Waiting before the leap: at T1 (not jumping to the landing).
        assertAt(T1, b.at(120))
        assertAt(T1, b.at(150))
        // Then S2 T2 once there and S2 (99) is long in: as soon as it's walked there and done it.
        assertTrue(b.nOf("done S2 T2")!! > 150)
    }

    @Test
    fun `an early enterer that's there sooner - the leap comes as soon as you'd have`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.eeAt[2] = 10
        val (_, b) = play(r, w)
        // You stood 59..69 after the terminal; the archer was there at 59 then. Still 70.
        assertEquals(70, b.nOf("leap ARCHER"))
    }

    @Test
    fun `an early enterer that never gets there - it leaps anyway, late`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.eeAt.remove(2)
        val (g, b) = play(r, w)
        val leap = act(r, Kind.LEAP, "ARCHER")
        val n = b.nOf("leap ARCHER")!!
        assertTrue(n >= 59 + GhostPlayer.MAX_WAIT, "$n")
        assertTrue(n <= 59 + GhostPlayer.MAX_WAIT + leap.rec - leap.settle + 1, "$n")
        assertTrue(g.finished)
    }

    @Test
    fun `leaping onto someone who isn't there - no leap, on with the run`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.positions.remove("HEALER")
        val (g, b) = play(r, w)
        assertNull(b.nOf("leap HEALER"))
        assertNotNull(b.nOf("done S4 T3"))
        assertTrue(g.finished)
        assertFalse(g.leaptOnto("HEALER", 0))
    }

    @Test
    fun `slower party at your early enter - you hold until they're on`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.readyAt[3] = 199 + 100
        val (_, b) = play(r, w)
        assertEquals(299 + 6, b.nOf("eeLeft 3"))
        // On the spot all along (the frames before leaving stay within 3 of it).
        for (n in 170..304) assertTrue(b.at(n)?.let { it.dist(EE3) <= GhostPlayer.SETTLE } ?: true, "n $n")
    }

    @Test
    fun `faster party at your early enter - you leave sooner, never before you're on`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.readyAt[3] = 100
        val (_, b) = play(r, w)
        val on = b.nOf("eeOn 3")!!
        assertEquals(169, on)
        // Not before S3 (you stood there until it): as you did.
        assertEquals(215, b.nOf("eeLeft 3"))
    }

    @Test
    fun `nobody ever on you - you leave after the safety wait`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.readyAt.remove(3)
        val (g, b) = play(r, w)
        val left = b.nOf("eeLeft 3")!!
        assertTrue(left >= 169 + GhostPlayer.MAX_WAIT, "$left")
        assertTrue(g.finished)
    }

    @Test
    fun `everyone on before you even got there`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.readyAt[3] = 5
        val (_, b) = play(r, w)
        assertTrue(b.nOf("eeLeft 3")!! >= b.nOf("eeOn 3")!!)
        assertEquals(215, b.nOf("eeLeft 3"))
    }

    @Test
    fun `a terminal someone else did - it doesn't stand there`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        val (_, b) = play(r, w) { n -> if (n == 120) w.done += "S2 T2" }
        assertNull(b.nOf("done S2 T2"))
        // It moved on once there (and the terminal done): the early enter came sooner.
        assertTrue(b.nOf("eeOn 3")!! < 169, "${b.log}")
    }

    @Test
    fun `a terminal done before it got there - on as it arrives`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.done += "S2 T2"
        val (_, b) = play(r, w)
        val a = act(r, Kind.DONE, "S2 T2")
        assertNull(b.nOf("done S2 T2"))
        assertEquals(169 - (149 - a.settle), b.nOf("eeOn 3"))
    }

    @Test
    fun `a gate already down - no blowing it`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.gates += 3
        val (g, b) = play(r, w)
        assertNull(b.nOf("gate 3"))
        assertTrue(g.finished)
    }

    @Test
    fun `a gate before its section waits`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.sections[3] = 400
        w.sections[4] = 450; w.sections[5] = 500
        val (_, b) = play(r, w)
        assertTrue(b.nOf("gate 3")!! >= 400)
        assertTrue(b.nOf("done S3 T1")!! >= 400)
    }

    @Test
    fun `a station it can't do yet - it waits there`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.blocked += "S3 T1"
        val (_, b) = play(r, w) { n -> if (n == 300) w.blocked -= "S3 T1" }
        assertEquals(300, b.nOf("done S3 T1"))
        assertTrue(b.gaveUp.isEmpty())
    }

    @Test
    fun `a station it can never do - it gives it to someone else and goes on`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.blocked += "S3 T1"
        val (g, b) = play(r, w)
        assertNull(b.nOf("done S3 T1"))
        assertEquals(listOf("S3 T1"), b.gaveUp)
        assertTrue(g.finished)
    }

    @Test
    fun `a gate it can never blow - given up`() {
        val r = mageRun()
        val w = object : World(r) { override fun canGate(s: Int) = false }.asRecorded()
        val (g, b) = play(r, w)
        assertEquals(listOf("gate 3"), b.gaveUp)
        assertTrue(g.finished)
    }

    @Test
    fun `the core - in once it's open`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.sections[5] = 600
        val (_, b) = play(r, w)
        // Not in the core (near CORE) before 600.
        for (n in 0 until 600) b.at(n)?.let { assertTrue(it.dist(CORE) > 0.5, "n $n in the core at $it") }
        assertNotNull(b.moves.firstOrNull { it.first >= 600 && it.second.dist(CORE) < 0.5 })
    }

    @Test
    fun `a leap onto the core early enterer waits for its recore`() {
        val rb = Rb(); rb.stand(10); rb.section(2); rb.section(3); rb.section(4); rb.stand(10); rb.section(5); rb.ev("ee", "TANK", 5)
        rb.stand(20); rb.ev("recore", "TANK"); rb.stand(5); rb.tp(CORE); rb.ev("leap", "TANK"); rb.ev("core"); rb.stand(20)
        val r = rb.build()
        val w = World(r).asRecorded()
        w.coreOwner = "TANK"; w.recoredAt = 200; w.positions["TANK"] = CORE
        val (_, b) = play(r, w)
        assertEquals(200 + 6, b.nOf("leap TANK"))
    }

    @Test
    fun `you as the core early enterer - on, off once all are on and it's open, recore`() {
        val core = P(54.5, 115.06, 50.5)
        val rb = Rb(); rb.walk(core, 10); rb.ev("ee", "MAGE", 5); rb.stand(20); rb.ev("landed", "TANK"); rb.stand(10); rb.section(2); rb.section(3); rb.section(4)
        rb.stand(10); rb.section(5); rb.stand(4); rb.walk(CORE, 4); rb.ev("left", "MAGE", 5); rb.ev("core"); rb.ev("recore", "MAGE"); rb.stand(20)
        val r = rb.build()
        val w = World(r).asRecorded()
        w.sections[5] = 300
        val (g, b) = play(r, w)
        assertEquals(10, b.nOf("eeOn 5"))
        val off = b.nOf("eeLeft 5")!!
        assertTrue(off >= 300, "$off")
        assertTrue(b.nOf("recore x")!! >= off)
        assertTrue(g.finished)
    }

    @Test
    fun `multiple actions in one tick all happen that tick`() {
        val rb = Rb(); rb.stand(5); rb.done("S1 east lever"); rb.tp(EE2); rb.ev("leap", "ARCHER"); rb.done("S1 SS"); rb.section(5); rb.stand(3)
        val r = rb.build()
        val w = World(r).asRecorded()
        val (_, b) = play(r, w)
        assertEquals(5, b.nOf("done S1 east lever"))
        assertEquals(6, b.nOf("leap ARCHER"))
        assertEquals(6, b.nOf("done S1 SS"))
    }

    @Test
    fun `a death's teleport plays as it was`() {
        val rb = Rb(); rb.walk(T1, 10); rb.stand(5); rb.tp(A); rb.walk(T1, 10); rb.done("S1 T1"); rb.section(5); rb.stand(2)
        val r = rb.build()
        val w = World(r).asRecorded()
        val (_, b) = play(r, w)
        assertAt(A, b.at(16))
        assertEquals(26, b.nOf("done S1 T1"))
    }

    @Test
    fun `after its last frame it stands where you ended`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        val (g, b) = play(r, w)
        assertTrue(g.finished)
        assertEquals(r.end, g.shown)
        assertAt(r.frames.last().p, b.moves.last().second)
        assertEquals("finished", g.status)
    }

    @Test
    fun `a run with no actions just plays`() {
        val rb = Rb(); rb.walk(T1, 30); rb.section(5); rb.stand(10)
        val r = rb.build()
        val (g, b) = play(r, World(r))
        assertTrue(g.actions.isEmpty())
        for (n in 0..r.end) assertAt(r.frame(n).p, b.at(n), "n $n")
        assertTrue(g.finished)
    }

    @Test
    fun `working - at its terminal before doing it`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        val (_, b) = play(r, w)
        assertTrue("S1 T1" in b.workingLog)
        assertTrue("S2 T2" in b.workingLog)
        assertTrue("S3 T1" in b.workingLog)
        assertNull(b.working)
        // Not for the gate or a lever.
        assertTrue(b.workingLog.filterNotNull().all { GhostPlayer.isTerminal(it) })
    }

    @Test
    fun `not working at a terminal someone else did`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.done += "S2 T2"
        val (_, b) = play(r, w)
        assertFalse("S2 T2" in b.workingLog)
    }

    @Test
    fun `swings come through, a few at most when time is cut`() {
        val rb = Rb(); rb.walk(T1, 5); repeat(5) { rb.stand(2); rb.ev("swing") }; rb.stand(40); rb.done("S2 T1"); rb.section(2); rb.section(5); rb.stand(3)
        val r = rb.build()
        val w = World(r).asRecorded()
        assertEquals(5, play(r, w).second.swings)
        // Section 2 at once: the standing is cut, the swings in it come at most 3 at a time.
        val w2 = World(r).asRecorded(); w2.sections[2] = 1
        assertTrue(play(r, w2).second.swings in 1..5)
    }

    @Test
    fun `what you held is shown`() {
        val rb = Rb(); rb.held = "INFINITE_SPIRIT_LEAP"; rb.stand(3); rb.held = "SUPERBOOM_TNT"; rb.stand(3); rb.section(5); rb.stand(1)
        val r = rb.build()
        val b = play(r, World(r)).second
        assertTrue("INFINITE_SPIRIT_LEAP" in b.heldSeen)
        assertTrue("SUPERBOOM_TNT" in b.heldSeen)
    }

    @Test
    fun `expects a leap until it leapt`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        val body = Body(w)
        val g = GhostPlayer(r, w, body)
        assertTrue(g.expectsLeap("ARCHER", 2))
        assertFalse(g.expectsLeap("ARCHER", 3))
        assertFalse(g.expectsLeap("HEALER", 4))
        assertFalse(g.expectsLeap("TANK", 2))
        for (n in 0..75) { w.now = n; g.tick(n) }
        assertFalse(g.expectsLeap("ARCHER", 2))
        assertTrue(g.leaptOnto("ARCHER", 60))
        assertFalse(g.leaptOnto("ARCHER", 71))
    }

    @Test
    fun `early enters and their spots`() {
        val g = GhostPlayer(mageRun(), World(), Body(World()))
        assertTrue(g.earlyEnters(3))
        assertFalse(g.earlyEnters(2)); assertFalse(g.earlyEnters(5))
        assertAt(EE3, g.eeSpot(3)?.p)
        assertNull(g.eeSpot(2))
    }

    @Test
    fun `its jobs are the run's`() {
        val g = GhostPlayer(mageRun(), World(), Body(World()))
        assertEquals(setOf("S1 T1", "S2 T2", "S3 T1", "S4 T3", "gate 3"), g.jobs.toSet())
    }

    @Test
    fun `started late (the live clock ahead) - it catches up only by cutting standing`() {
        // The ghost's first tick at n = 40: it's at 40 in your run, nothing skipped but frames it never showed.
        val r = mageRun()
        val w = World(r).asRecorded()
        val b = Body(w)
        val g = GhostPlayer(r, w, b)
        for (n in 40..r.end + 50) { w.now = n; g.tick(n) }
        assertEquals(59, b.nOf("done S1 T1"))
        assertTrue(g.finished)
    }

    @Test
    fun `ticks skipped live - actions still all happen, in order`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        val b = Body(w)
        val g = GhostPlayer(r, w, b)
        for (n in 0..r.end + 50 step 7) { w.now = n; g.tick(n) }
        assertEquals(9, b.log.count { !it.startsWith("working") })
        assertTrue(g.finished)
    }

    @Test
    fun `the same tick twice changes nothing`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        val b = Body(w)
        val g = GhostPlayer(r, w, b)
        for (n in 0..r.end) { w.now = n; g.tick(n); g.tick(n) }
        assertEquals(9, b.log.size)
        assertEquals(0, g.shift)
    }

    @Test
    fun `the status says what it waits for`() {
        val r = mageRun()
        val w = World(r).asRecorded()
        w.eeAt.remove(2)
        val b = Body(w)
        val g = GhostPlayer(r, w, b)
        for (n in 0..100) { w.now = n; g.tick(n) }
        assertTrue(g.status.startsWith("waiting"), g.status)
        assertTrue("EeOn" in g.status, g.status)
    }

    // ------------------------------------------------------------------ a recording, played back

    @Test
    fun `record, save, load, play - as recorded`() = withStore {
        val r = mageRun()
        GhostStore.offer(r)
        GhostStore.clearCache()
        val back = GhostStore.best("PF", "MAGE")!!
        val w = World(back).asRecorded()
        val (g, b) = play(back, w)
        assertEquals(GhostPlayer.build(r).map { it.kind to it.rec }, g.actions.map { it.kind to it.rec })
        assertEquals(59, b.nOf("done S1 T1"))
        assertEquals(379, b.nOf("done S4 T3"))
        assertTrue(g.finished)
    }

    @Test
    fun `random runs - any party timing, everything done once, in order, never back`() {
        for (seed in 0 until 60) {
            val rnd = Random(seed * 7919 + 1)
            val run = randomRun(rnd)
            val w = World(run).asRecorded()
            for (s in 2..5) if (s in w.sections) w.sections[s] = (w.sections[s]!! + rnd.nextInt(-60, 240)).coerceAtLeast(1)
            for (k in w.eeAt.keys.toList()) w.eeAt[k] = (w.eeAt[k]!! + rnd.nextInt(-60, 240)).coerceAtLeast(0)
            for (k in w.readyAt.keys.toList()) if (rnd.nextInt(5) == 0) w.readyAt.remove(k) else w.readyAt[k] = (w.readyAt[k]!! + rnd.nextInt(-60, 240)).coerceAtLeast(0)
            if (rnd.nextBoolean()) w.done += run.jobs.filter { !it.startsWith("gate") }.shuffled(rnd).take(rnd.nextInt(3))
            val body = Body(w)
            val g = GhostPlayer(run, w, body)
            var last = -1
            for (n in 0..run.end + 6000) {
                w.now = n
                g.tick(n)
                assertTrue(g.shown >= last, "seed $seed")
                last = g.shown
                if (g.finished) break
            }
            assertTrue(g.finished, "seed $seed: ${g.status}")
            val fired = body.log.filter { it.startsWith("done ") }.map { it.substringBeforeLast('@').removePrefix("done ") }
            assertEquals(fired.distinct(), fired, "seed $seed: twice")
            for (job in run.jobs.filter { !it.startsWith("gate") }) assertTrue(job in w.done || job in body.gaveUp, "seed $seed: $job not done")
        }
    }

    /** A run of terminals, leaps and an early enter at random places and times. */
    private fun randomRun(rnd: Random): GhostRun {
        val rb = Rb(clazz = "TANK")
        var section = 1
        val ids = (1..4).associateWith { s -> Station.all().filter { it.section == s }.map { it.id }.shuffled(rnd).take(2) }
        fun spot() = P(rnd.nextDouble(0.0, 100.0), 110.0, rnd.nextDouble(0.0, 140.0))
        rb.stand(rnd.nextInt(1, 20))
        while (section <= 4) {
            for (id in ids.getValue(section)) {
                rb.walk(spot(), rnd.nextInt(3, 30)); rb.stand(rnd.nextInt(0, 60)); rb.done(id)
                if (rnd.nextInt(4) == 0) { rb.stand(rnd.nextInt(1, 10)); rb.ev("swing") }
            }
            if (section <= 3 && rnd.nextBoolean()) { rb.walk(spot(), 5); rb.stand(rnd.nextInt(0, 20)); rb.ev("gate", k = section) }
            when (rnd.nextInt(3)) {
                0 -> { val who = listOf("ARCHER", "MAGE", "HEALER").random(rnd); rb.ev("ee", who, section + 1); rb.stand(rnd.nextInt(1, 40)); rb.tp(spot()); rb.ev("leap", who) }
                1 -> if (section < 4) {
                    rb.walk(spot(), 10); rb.ev("ee", "TANK", section + 1)
                    repeat(rnd.nextInt(0, 3)) { rb.stand(rnd.nextInt(1, 30)); rb.ev("landed", "BERSERK") }
                    rb.stand(rnd.nextInt(1, 30))
                    section++; rb.section(section)
                    rb.stand(rnd.nextInt(0, 10)); rb.walk(spot(), 5); rb.ev("left", "TANK", section)
                    continue
                }
                else -> {}
            }
            rb.stand(rnd.nextInt(1, 30))
            section++; rb.section(section)
        }
        rb.walk(CORE, 10); rb.ev("core"); rb.stand(20)
        return rb.build()
    }
}
