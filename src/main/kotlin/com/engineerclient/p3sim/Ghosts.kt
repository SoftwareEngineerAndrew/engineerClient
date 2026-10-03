package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.zip.GZIPInputStream

/**
 * Ghost parties: real fast F7 P3s from Better PF (tools/p3sim/ghosts.py), replayed by the other
 * four. Each ghost walks, leaps and holds what its player did, and does the stations and gates its
 * player did, when they did them; you take the place of the player of your role's class, and their
 * stations are yours.
 *
 * Time is per section: in section s a ghost is where its player was [recorded start of s + ticks
 * since s started here], held at the section's recorded end if you're slower (they wait at the
 * door, as a party waits for you), jumping on if you're faster.
 */
object Ghosts {
    class Track(val n: IntArray, val x: DoubleArray, val y: DoubleArray, val z: DoubleArray, val yaw: FloatArray, val pitch: FloatArray, val item: IntArray) {
        private fun index(t: Int): Int {
            var lo = 0; var hi = n.size - 1
            if (t <= n[0]) return 0
            if (t >= n[hi]) return hi
            while (hi - lo > 1) { val m = (lo + hi) / 2; if (n[m] <= t) lo = m else hi = m }
            return lo
        }

        /** Where at [t] (between samples up to 3 ticks apart: interpolated; longer gaps are teleports, so held). */
        fun pos(t: Int): Vec3 {
            val i = index(t)
            if (i + 1 < n.size && t > n[i] && n[i + 1] - n[i] <= 3) {
                val f = (t - n[i]).toDouble() / (n[i + 1] - n[i])
                return Vec3(x[i] + (x[i + 1] - x[i]) * f, y[i] + (y[i + 1] - y[i]) * f, z[i] + (z[i + 1] - z[i]) * f)
            }
            return Vec3(x[i], y[i], z[i])
        }

        fun yaw(t: Int) = yaw[index(t)]
        fun pitch(t: Int) = pitch[index(t)]
        fun item(t: Int) = item[index(t)]
    }

    /**
     * One of the run's players. Their track is only what the recorder saw (moves only: a gap is
     * standing still, or out of its range); before it starts they're at [before], the station they
     * were doing out of sight (the i4's target), if any.
     */
    class GhostPlayer(val name: String, val clazz: String, val skin: String, val track: Track) {
        var before: Vec3? = null
        fun pos(t: Int): Vec3 = before?.takeIf { t < track.n[0] } ?: track.pos(t)
    }
    class Comp(val n: Int, val station: String, val actor: String)
    class Run(
        val id: String,
        /** Each section's recorded start: [_, 0, door 1, door 2, door 3, core]. */
        val starts: IntArray,
        val gates: Array<Pair<Int, String?>?>,
        val comps: List<Comp>,
        val players: List<GhostPlayer>,
    ) {
        val core get() = starts[5]
        fun player(clazz: String) = players.firstOrNull { it.clazz == clazz }
        fun sectionTimes() = (1..4).map { starts[it + 1] - starts[it] }
    }

    private var items: List<String> = emptyList()

    /** The runs, fastest first (empty if the file is missing). */
    val runs: List<Run> by lazy {
        val out = ArrayList<Run>()
        EngineerClient.safely("p3sim ghosts") {
            val stream = Ghosts::class.java.getResourceAsStream("/assets/engineerclient/p3sim/ghosts.json.gz") ?: return@safely
            val root = GZIPInputStream(stream).use { JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject }
            items = root.getAsJsonArray("items").map { it.asString }
            for (e in root.getAsJsonArray("runs")) out += parse(e.asJsonObject)
        }
        // Only runs whose players were all seen into S4 (the recorder lost some earlier: they would stand still).
        out.filter { r -> r.players.all { it.track.n.last() >= r.starts[4] } }.sortedBy { it.core }
    }

    private fun parse(r: JsonObject): Run {
        val doors = r.getAsJsonObject("doors")
        val starts = intArrayOf(0, 0, doors["1"].asInt, doors["2"].asInt, doors["3"].asInt, r["core"].asInt)
        val gatesJ = r.getAsJsonObject("gates")
        val gates = arrayOfNulls<Pair<Int, String?>>(4)
        for (k in 1..3) gatesJ[k.toString()]?.asJsonArray?.let { a -> gates[k] = a[0].asInt to a[1].takeIf { !it.isJsonNull }?.asString }
        val comps = r.getAsJsonArray("comps").map { c -> c.asJsonArray.let { Comp(it[0].asInt, it[1].asString, it[2].asString) } }
        val players = r.getAsJsonArray("players").map { pe ->
            val p = pe.asJsonObject
            val rows = p.getAsJsonArray("track")
            val k = rows.size()
            val t = Track(IntArray(k), DoubleArray(k), DoubleArray(k), DoubleArray(k), FloatArray(k), FloatArray(k), IntArray(k))
            rows.forEachIndexed { i, re ->
                val a = re.asJsonArray
                t.n[i] = a[0].asInt; t.x[i] = a[1].asDouble; t.y[i] = a[2].asDouble; t.z[i] = a[3].asDouble
                t.yaw[i] = a[4].asFloat; t.pitch[i] = a[5].asFloat; t.item[i] = a[6].asInt
            }
            GhostPlayer(p["name"].asString, p["class"].asString, p["skin"].asString, t)
        }
        for (p in players) {
            val first = comps.firstOrNull { it.actor == p.name } ?: continue
            if (first.n < p.track.n[0]) p.before = Party.STANDS[first.station]
        }
        return Run(r["id"].asString, starts, gates, comps, players)
    }

    /** The run the menu picked (Party Run 1..), or null for the scripted bots. */
    val run: Run? get() {
        val k = P3Sim.partyRun
        if (!P3Sim.bots || k <= 0) return null
        return runs.getOrNull(k - 1)
    }

    /** The player you replace: your role's class. */
    fun replaced(r: Run? = run): GhostPlayer? = r?.player(Party.myRole.clazz.name)

    // ------------------------------------------------------------------ replay

    private val fired = HashSet<Int>()
    private val gated = BooleanArray(4)
    private var lastT = Int.MIN_VALUE

    fun start() { fired.clear(); gated.fill(false); lastT = Int.MIN_VALUE; spawnPace() }

    /** The player you replace, as a glowing outline where they were (race them). */
    private var pace: net.minecraft.world.entity.decoration.Mannequin? = null

    private fun spawnPace() {
        pace?.discard(); pace = null
        if (!P3Sim.paceGhost) return
        val me = replaced() ?: return
        val m = net.minecraft.world.entity.decoration.Mannequin(net.minecraft.world.entity.EntityType.MANNEQUIN, Sim.level)
        m.isInvisible = true
        m.setGlowingTag(true)
        m.isInvulnerable = true
        m.setNoGravity(true)
        m.noPhysics = true
        val p = me.pos(0)
        m.snapTo(p.x, p.y, p.z, 0f, 0f)
        pace = Sim.spawn(m)
    }

    /** The recorded tick the ghosts are at, for the sim's [phase]. */
    fun time(phase: GoldorPhase, r: Run): Int {
        val s = phase.section.coerceIn(1, 5)
        val t = r.starts[s] + (phase.n - phase.sectionStartN(s))
        val out = if (s < 5) t.coerceAtMost(r.starts[s + 1]) else t
        // Never backwards (a section started here before its recorded start).
        lastT = maxOf(lastT, out)
        return lastT
    }

    fun tick(phase: GoldorPhase, r: Run) {
        val t = time(phase, r)
        val me = replaced(r)?.name
        replaced(r)?.let { g -> pace?.let { m -> val p = g.pos(t); val yaw = g.track.yaw(t); m.snapTo(p.x, p.y, p.z, yaw, g.track.pitch(t)); m.yHeadRot = yaw; m.yBodyRot = yaw } }
        for (b in Party.bots()) {
            val g = b.ghost ?: continue
            val e = b.entity ?: continue
            b.pos = g.pos(t)
            // Last seen before the core (out of the recorder's range): in it by now, as they were.
            if (phase.section >= 5 && t > g.track.n.last() && !GoldorPhase.CORE_BOX.contains(b.pos)) b.pos = Party.CORE_SPOT.add((b.role.ordinal - 2) * 1.5, 0.0, 0.0)
            b.yaw = g.track.yaw(t)
            e.snapTo(b.pos.x, b.pos.y, b.pos.z, b.yaw, g.track.pitch(t))
            e.yHeadRot = b.yaw; e.yBodyRot = b.yaw
            val held = g.track.item(t)
            if (held != b.heldIx) { b.heldIx = held; e.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, stack(items.getOrNull(held) ?: "")) }
            b.working = null
        }
        r.comps.forEachIndexed { i, c ->
            if (i in fired) return@forEachIndexed
            val st = phase.stations.firstOrNull { it.id == c.station }
            if (st == null || st.done || c.actor == me) { fired += i; return@forEachIndexed }
            val bot = Party.bots().firstOrNull { it.ghost?.name == c.actor }
            if (bot == null) { fired += i; return@forEachIndexed }
            // At a terminal for its last 40 ticks: it's in use (yours to wait for, as on Hypixel).
            if (st.kind == Station.Kind.TERMINAL && t in c.n - 40 until c.n && bot.pos.distanceTo(st.at) < 5.0) bot.working = st
            if (t < c.n) return@forEachIndexed
            if (st.kind == Station.Kind.TERMINAL && Terminals.inUse(st)) return@forEachIndexed
            fired += i
            if (st.kind == Station.Kind.LEVER) phase.pullLever(st, bot.name) else st.complete(bot.name)
            if (st.kind == Station.Kind.DEVICE) phase.devices.shownDone(st.label)
        }
        for (k in 1..3) {
            if (gated[k]) continue
            val (n, actor) = r.gates[k] ?: continue
            if (actor != null && actor == me) { gated[k] = true; continue }
            if (t < n || phase.gateIsDown(k)) { if (phase.gateIsDown(k)) gated[k] = true; continue }
            gated[k] = true
            phase.blowGate(k, actor)
        }
    }

    /** What a ghost holds: the sim's own item where it has one, else something like it. */
    private fun stack(id: String): ItemStack = when {
        id.isEmpty() -> ItemStack.EMPTY
        id == "TERMINATOR" -> SimItems.TERMINATOR
        id in setOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE") -> SimItems.HYPERION
        id.endsWith("BONZO_STAFF") -> SimItems.BONZO
        id == "JERRY_STAFF" -> SimItems.JERRY
        id == "WITHER_CLOAK" -> SimItems.CLOAK
        id.endsWith("SPIRIT_LEAP") -> SimItems.LEAP
        id.endsWith("SUPERBOOM_TNT") -> SimItems.SUPERBOOM.also { it.count = 1 }
        id == "DUNGEONBREAKER" -> SimItems.DUNGEONBREAKER
        id == "ASPECT_OF_THE_VOID" -> SimItems.AOTV
        id == "ITEM_SPIRIT_BOW" -> SimItems.SPIRIT_BOW
        id == "ENDER_PEARL" -> SimItems.PEARLS.also { it.count = 1 }
        "ROD" in id -> ItemStack(Items.FISHING_ROD)
        "AXE" in id -> ItemStack(Items.GOLDEN_AXE)
        "BOW" in id -> ItemStack(Items.BOW)
        "SWORD" in id || "CLAYMORE" in id || "BLADE" in id -> ItemStack(Items.IRON_SWORD)
        "WAND" in id || "STAFF" in id -> ItemStack(Items.STICK)
        else -> ItemStack(Items.PAPER)
    }

    /** Your jobs in this run (the replaced player's), for the menu. */
    fun myJobs(r: Run): List<String> {
        val me = replaced(r) ?: return listOf("No ${Party.myRole.clazz.name.lowercase()} in this run")
        val bySection = (1..4).map { s -> s to r.comps.filter { it.actor == me.name && it.station.startsWith("S$s ") }.map { it.station.substringAfter(' ') } }
        val gates = (1..3).filter { r.gates[it]?.second == me.name }.map { "gate $it/${it + 1}" }
        return listOf("You are §f${me.name}§7 (${me.clazz.lowercase()})") +
            bySection.filter { it.second.isNotEmpty() }.map { (s, l) -> "S$s: ${l.joinToString(", ")}" } +
            (if (gates.isNotEmpty()) listOf(gates.joinToString(", ")) else emptyList())
    }

    /** The menu's label for run [k] (1..). */
    fun label(k: Int): String {
        if (k <= 0) return "Scripted bots"
        val r = runs.getOrNull(k - 1) ?: return "Run $k?"
        return "Run $k: P3 ${"%.1f".format(r.core / 20.0)}s"
    }
}
