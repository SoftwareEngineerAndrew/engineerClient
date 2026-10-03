package com.engineerclient.p3sim

import com.mojang.authlib.GameProfile
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/**
 * The other four of the party: bots that play the five-role P3 plan of
 * docs/mechanics/terminal-roles.md (ss, i4, ee3, 42·gates, ee2·core), each job at the pace of the
 * fast Better PF runs (terminal solve times by window type, levers 4 ticks, gates 9, leaps 8/14,
 * walks by the measured fit). Your role is yours: no bot does it, and the menu lists its jobs.
 */
object Party {
    enum class Role(val label: String, val clazz: DungeonClass, val botName: String) {
        SS("ss", DungeonClass.HEALER, "SimonBot"),
        I4("i4", DungeonClass.BERSERK, "FourthBot"),
        EE3("ee3", DungeonClass.ARCHER, "ArrowBot"),
        GATES("42·gates", DungeonClass.TANK, "GateBot"),
        CORE("ee2·core", DungeonClass.MAGE, "CoreBot"),
    }

    val myRole: Role get() = Role.entries[P3Sim.role.coerceIn(0, 4)]

    class Bot(val role: Role) {
        val name get() = role.botName
        val clazz get() = role.clazz
        var entity: Mannequin? = null
        var pos: Vec3 = Vec3.ZERO
        var yaw = 0f
        /** Walking: from, to, ticks, elapsed. */
        var from: Vec3 = Vec3.ZERO
        var to: Vec3? = null
        var moveTicks = 0
        var moved = 0
        var jobs = ArrayDeque<Job>()
        var job: Job? = null
        var wait = 0
        var working: Station? = null
        val idle get() = to == null && wait <= 0
        /** Sent to the core (section 5), whatever was left of the plan. */
        var cored = false
    }

    /** One step of a bot's plan; [run] returns true when the step is over. */
    class Job(val what: String, val section: Int = 0, val run: Bot.(GoldorPhase) -> Boolean)

    private val bots = ArrayList<Bot>()

    /** The bots (for Odin's party list), whether or not they're spawned. */
    fun bots(): List<Bot> {
        if (bots.isEmpty() || bots.any { it.role == myRole }) { clear(); bots.clear(); Role.entries.filter { it != myRole }.forEach { bots += Bot(it) } }
        return bots
    }

    fun bot(role: Role) = bots.firstOrNull { it.role == role }

    /** Bumped by every clear: a leap queued before a restart doesn't move the new bots. */
    private var generation = 0

    fun clear() {
        generation++
        bots.forEach { it.entity?.discard(); it.entity = null; it.jobs.clear(); it.job = null; it.to = null; it.working = null; it.wait = 0; it.cored = false }
    }

    /** Is a bot doing [st] right now. */
    fun busyAt(st: Station) = bots.any { it.working === st }

    /** Everyone (the bots) inside [box]. */
    fun allIn(box: AABB) = !P3Sim.bots || bots.all { it.entity == null || box.contains(it.pos) }

    // ------------------------------------------------------------------ P3

    fun startP3(phase: GoldorPhase) {
        clear()
        if (!P3Sim.bots) return
        for (b in bots()) {
            spawn(b, startPos(b.role, phase.from))
            // Steps of earlier sections are dropped, except stations still to do (i4's target): those go last, before the core.
            val all = plan(b.role)
            val kept = all.filter { it.first >= phase.from }.map { it.second }
            val owed = all.filter { it.first < phase.from && it.second.section >= phase.from }.map { it.second }
            b.jobs = ArrayDeque(kept.dropLast(1) + owed + kept.last())
        }
    }

    fun tickP3(phase: GoldorPhase) {
        if (!P3Sim.bots) return
        for (b in bots.toList()) {
            if (b.entity == null) continue
            // The core is open: everyone heads in, whatever they were stuck on.
            if (phase.section >= 5 && !b.cored) { b.cored = true; b.job = null; b.working = null; b.wait = 0; b.jobs = ArrayDeque(listOf(core())) }
            // Moving.
            b.to?.let { to ->
                b.moved++
                val f = (b.moved.toDouble() / b.moveTicks).coerceAtMost(1.0)
                b.pos = b.from.add(to.subtract(b.from).scale(f))
                if (f >= 1.0) b.to = null
            }
            if (b.wait > 0) b.wait--
            if (b.idle) {
                val j = b.job ?: b.jobs.removeFirstOrNull()
                b.job = j
                if (j != null && j.run(b, phase)) b.job = null
            }
            place(b)
        }
    }

    /** The bots standing still at [spots] (P1, P2: leap targets). */
    fun standAt(spots: List<Vec3>) {
        clear()
        if (!P3Sim.bots) return
        bots().forEachIndexed { i, b -> spawn(b, spots[i % spots.size]); place(b) }
    }

    /** Called every server tick by the fight (outside P3, the bots just stand). */
    fun tick() {}

    private fun spawn(b: Bot, at: Vec3) {
        val m = Mannequin(EntityType.MANNEQUIN, Sim.level)
        m.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(GameProfile(UUID.nameUUIDFromBytes("p3sim:${b.name}".toByteArray()), b.name)))
        m.setCustomName(Component.literal("§a${b.name} §7(${b.clazz.name[0]})"))
        m.isCustomNameVisible = true
        m.isInvulnerable = true
        m.setNoGravity(true)
        m.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(if (b.role == Role.I4) Items.BOW else Items.DIAMOND_PICKAXE))
        hideDescription(m)
        b.pos = at
        m.snapTo(at.x, at.y, at.z, 0f, 0f)
        b.entity = Sim.spawn(m)
    }

    private val hideMethod by lazy { Mannequin::class.java.getDeclaredMethod("setHideDescription", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
    private fun hideDescription(m: Mannequin) { runCatching { hideMethod.invoke(m, true) } }

    private fun place(b: Bot) {
        val e = b.entity ?: return
        val to = b.to
        if (to != null) { val d = to.subtract(b.from); if (abs(d.x) + abs(d.z) > 0.01) b.yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat() }
        e.snapTo(b.pos.x, b.pos.y, b.pos.z, b.yaw, 0f)
        e.yHeadRot = b.yaw; e.yBodyRot = b.yaw
    }

    // ------------------------------------------------------------------ moves

    /** Walks to [at]: ticks = 5.3 + 1.06 x horizontal + 0.78 x climb (the measured fit). */
    private fun Bot.walk(at: Vec3) {
        val h = Math.hypot(at.x - pos.x, at.z - pos.z)
        val climb = (at.y - pos.y).coerceAtLeast(0.0)
        go(at, (5.3 + 1.06 * h + 0.78 * climb).toInt().coerceAtLeast(1))
    }

    private fun Bot.go(at: Vec3, ticks: Int) { from = pos; to = at; moveTicks = ticks; moved = 0 }

    /** A Spirit Leap to [role]'s bot (or you): lands 8 ticks after (14 at a door). */
    private fun Bot.leap(role: Role, atDoor: Boolean) {
        val target = if (role == myRole) Sim.player?.position() else bot(role)?.pos
        target ?: return
        wait = if (atDoor) 14 else 8
        val gen = generation
        Fight.later(wait - 1, "bot leap") { if (gen == generation) { pos = target; to = null } }
    }

    // ------------------------------------------------------------------ jobs

    private fun st(phase: GoldorPhase, s: Int, label: String) = phase.station(s, label)

    /** Go to [label] in S[s], wait until it can be done, do it in its time. Skipped if done already. */
    private fun doIt(s: Int, label: String) = Job("S$s $label", s) { phase ->
        val st = st(phase, s, label)
        if (st.done) { working = null; return@Job true }
        val spot = STANDS["S$s $label"] ?: st.at
        if (pos.distanceTo(spot) > 1.5 && working !== st) { walk(spot); return@Job false }
        val early = st.kind == Station.Kind.DEVICE && s > phase.section
        if (phase.section != s && !early) return@Job false
        if (st.kind == Station.Kind.TERMINAL && Terminals.inUse(st)) return@Job false
        if (working !== st) {
            working = st
            wait = solveTime(st, phase)
            return@Job false
        }
        working = null
        if (st.kind == Station.Kind.LEVER) phase.pullLever(st, name) else st.complete(name)
        if (st.kind == Station.Kind.DEVICE) phase.devices.shownDone(st.label)
        st.done
    }

    private fun solveTime(st: Station, phase: GoldorPhase): Int = when (st.kind) {
        Station.Kind.LEVER -> 4
        Station.Kind.TERMINAL -> 6 + jitter(when (st.nextType()) {
            Terminals.Type.ORDER -> 56; Terminals.Type.PANES -> 40; Terminals.Type.RUBIX -> 35
            Terminals.Type.SELECT -> 27; Terminals.Type.STARTS -> 28; Terminals.Type.MELODY -> 152
        })
        Station.Kind.DEVICE -> when (st.label) {
            // Simon Says: done at ~251 (236-259 in fast runs); the target ~147 (72-213); Lights 64 after entering S2; Arrow Align 12.
            "SS" -> (Random.nextInt(236, 260) - phase.n).coerceAtLeast(1)
            "Target" -> (Random.nextInt(100, 200) - phase.n).coerceAtLeast(1)
            "Lights" -> jitter(30)
            else -> jitter(12)
        }
    }

    private fun jitter(t: Int) = (t * (0.8 + Random.nextDouble() * 0.45)).toInt().coerceAtLeast(1)

    private fun gate(s: Int) = Job("gate $s") { phase ->
        if (phase.gateIsDown(s)) { if (working === GATE_MARK) working = null; return@Job true }
        val spot = GATES[s]
        if (pos.distanceTo(spot) > 1.5) { walk(spot); return@Job false }
        if (phase.section < s) return@Job false
        if (working !== GATE_MARK) { wait = 9; working = GATE_MARK; return@Job false }
        working = null
        phase.blowGate(s, name)
        true
    }

    private fun leapTo(role: Role, atDoor: Boolean = false) = Job("leap ${role.label}") { leap(role, atDoor); true }
    private fun walkTo(at: Vec3) = Job("walk") { phase -> if (pos.distanceTo(at) > 1.0) { walk(at); false } else true }
    private fun untilSection(s: Int) = Job("wait S$s") { phase -> phase.section >= s }
    private fun untilN(n: Int) = Job("wait $n") { phase -> phase.n >= n || phase.section >= 3 }
    private fun core() = Job("core") { phase ->
        if (phase.section < 5) return@Job false
        if (!GoldorPhase.CORE_BOX.contains(pos)) go(CORE_SPOT.add(Random.nextDouble(-2.0, 2.0), 0.0, Random.nextDouble(0.0, 3.0)), 11 + Random.nextInt(10))
        true
    }

    private val GATE_MARK = Station(Station.Kind.LEVER, 0, Vec3.ZERO, "gate")

    /** Each role's plan, by section (pairs of the section the step belongs to and the step). */
    private fun plan(role: Role): List<Pair<Int, Job>> = when (role) {
        Role.SS -> listOf(
            1 to doIt(1, "SS"),
            2 to untilSection(2), 2 to doIt(2, "T1"),
            3 to untilSection(3), 3 to leapTo(Role.EE3, true), 3 to doIt(3, "west lever"), 3 to doIt(3, "east lever"), 3 to leapTo(Role.CORE),
            4 to untilN(481), 4 to doIt(4, "low lever"), 4 to doIt(4, "high lever"), 4 to doIt(4, "T4"),
            5 to core(),
        )
        Role.I4 -> listOf(
            1 to doIt(4, "Target"), 1 to leapTo(Role.SS), 1 to doIt(1, "west lever"), 1 to gate(1),
            2 to untilSection(2), 2 to leapTo(Role.CORE, true), 2 to doIt(2, "high lever"), 2 to doIt(2, "T5"),
            3 to untilSection(3), 3 to leapTo(Role.EE3, true), 3 to doIt(3, "T3"), 3 to doIt(3, "Arrows"), 3 to walkTo(STRIP),
            4 to untilSection(4), 4 to doIt(4, "T1"),
            5 to core(),
        )
        Role.EE3 -> listOf(
            1 to doIt(1, "T1"), 1 to leapTo(Role.SS), 1 to doIt(1, "east lever"),
            2 to untilSection(2), 2 to leapTo(Role.GATES, true), 2 to doIt(2, "T4"), 2 to walkTo(STANDS.getValue("S3 T1")),
            3 to untilSection(3), 3 to doIt(3, "T1"), 3 to doIt(3, "T4"),
            4 to untilSection(4), 4 to leapTo(Role.GATES, true), 4 to doIt(4, "T3"),
            5 to core(),
        )
        Role.GATES -> listOf(
            1 to doIt(1, "T4"), 1 to doIt(1, "T2"), 1 to untilN(181), 1 to walkTo(STANDS.getValue("S2 T3")),
            2 to doIt(2, "T3"), 2 to leapTo(Role.EE3), 2 to gate(2),
            3 to untilSection(3), 3 to leapTo(Role.EE3, true), 3 to doIt(3, "T2"), 3 to gate(3), 3 to walkTo(STRIP),
            4 to untilSection(4), 4 to doIt(4, "T2"),
            5 to core(),
        )
        Role.CORE -> listOf(
            1 to doIt(1, "T3"), 1 to untilN(181), 1 to doIt(2, "Lights"),
            2 to untilSection(2), 2 to doIt(2, "T2"), 2 to leapTo(Role.EE3), 2 to doIt(2, "low lever"), 2 to walkTo(STRIP),
            5 to core(),
        )
    }

    private fun startPos(role: Role, from: Int): Vec3 = when (from) {
        1 -> when (role) { Role.I4 -> STANDS.getValue("S4 Target"); else -> STANDS.getValue("S1 SS").add(Random.nextDouble(-1.5, 0.0), 0.0, Random.nextDouble(-1.5, 1.5)) }
        2 -> GoldorPhase.GATE_CENTRES[1].add(Random.nextDouble(-2.0, 2.0), -3.0, 3.0)
        3 -> GoldorPhase.GATE_CENTRES[2].add(-3.0, -3.0, Random.nextDouble(-2.0, 2.0))
        4 -> STRIP.add(Random.nextDouble(-4.0, 4.0), 0.0, 0.0)
        else -> STRIP.add(Random.nextDouble(-4.0, 4.0), 0.0, 0.0)
    }

    /** Where a player stands to do each station (median from the recordings, terminal-roles.md). */
    val STANDS: Map<String, Vec3> = mapOf(
        "S1 T1" to Vec3(109.1, 118.8, 79.6), "S1 T2" to Vec3(92.3, 121.0, 99.7), "S1 T3" to Vec3(110.3, 113.0, 73.8), "S1 T4" to Vec3(92.1, 112.0, 92.7),
        "S1 east lever" to Vec3(106.9, 122.0, 111.7), "S1 west lever" to Vec3(95.4, 123.1, 113.6), "S1 SS" to Vec3(108.3, 120.0, 94.0),
        "S2 T1" to Vec3(69.0, 109.0, 124.7), "S2 T2" to Vec3(59.7, 120.0, 125.3), "S2 T3" to Vec3(46.4, 109.0, 122.6), "S2 T4" to Vec3(40.2, 124.0, 124.7), "S2 T5" to Vec3(39.2, 109.0, 140.5),
        "S2 low lever" to Vec3(28.3, 124.0, 128.7), "S2 high lever" to Vec3(25.6, 132.2, 137.5), "S2 Lights" to Vec3(60.6, 132.0, 139.0),
        "S3 T1" to Vec3(0.0, 109.0, 112.2), "S3 T2" to Vec3(1.0, 119.0, 93.6), "S3 T3" to Vec3(16.5, 123.0, 93.7), "S3 T4" to Vec3(0.8, 109.0, 77.5),
        "S3 west lever" to Vec3(4.3, 123.1, 55.4), "S3 east lever" to Vec3(13.0, 122.4, 55.7), "S3 Arrows" to Vec3(0.5, 120.0, 77.5),
        "S4 T1" to Vec3(41.3, 109.0, 32.6), "S4 T2" to Vec3(45.1, 121.6, 31.2), "S4 T3" to Vec3(67.1, 109.0, 33.1), "S4 T4" to Vec3(72.6, 115.0, 45.5),
        "S4 low lever" to Vec3(84.4, 122.5, 34.9), "S4 high lever" to Vec3(85.5, 124.6, 43.6), "S4 Target" to Vec3(63.5, 127.0, 35.5),
    )
    val GATES = arrayOf(Vec3.ZERO, Vec3(95.8, 123.9, 121.0), Vec3(19.3, 123.6, 127.9), Vec3(12.4, 116.8, 52.7))
    val STRIP = Vec3(54.6, 115.0, 51.5)
    val CORE_SPOT = Vec3(54.5, 115.0, 58.0)

    /** Your role's jobs, for the menu. */
    fun myJobs(): List<String> = when (myRole) {
        Role.SS -> listOf("S1: Simon Says", "S2: T1", "S3: west lever, east lever, then the strip", "S4: in at 481: low lever, high lever, T4")
        Role.I4 -> listOf("S1: the target (S4 device) from the plate, west lever, gate 1/2", "S2: high lever, T5", "S3: T3, Arrow Align, then the strip", "S4: T1")
        Role.EE3 -> listOf("S1: T1, east lever", "S2: T4, then into S3 at T1", "S3: T1, T4", "S4: T3")
        Role.GATES -> listOf("S1: T4, T2, into S2 at 181 (T3)", "S2: T3, gate 2/3", "S3: T2, gate 3/4, then the strip", "S4: T2")
        Role.CORE -> listOf("S1: T3, into S2 at 181, Lights", "S2: T2, low lever, then the strip", "S3/S4: hold the strip, first into the core")
    }
}
