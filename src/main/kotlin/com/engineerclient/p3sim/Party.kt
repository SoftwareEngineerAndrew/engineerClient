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
import kotlin.random.Random

/**
 * The other four of the party: one bot per class you're not, in leap menu slots 1-4 ([P3Plan.botOrder]).
 *
 * In P3 they do everything of [P3Plan] that isn't yours: each job at a random time between the
 * plan's bot times after its section starts (a section's last one held until you're at your early
 * enter, if you do one). They walk to their jobs, stand on early-enter spots, and leap onto whoever
 * is early-entering: onto a bot when the door opens, onto you as soon as you're there (pre moves).
 */
object Party {
    /** Hypixel's five classes, in their usual order. */
    val CLASSES by lazy { listOf(DungeonClass.HEALER, DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.TANK, DungeonClass.MAGE) }

    /** Bot walking speed, blocks a tick (sprinting at speed ~400 with turns and climbs). */
    private const val WALK = 0.95

    class Bot(val clazz: DungeonClass, val slot: Int) {
        val name = clazz.name.lowercase().replaceFirstChar { it.uppercase() } + "Bot"
        var entity: Mannequin? = null
        var pos: Vec3 = Vec3.ZERO
        var yaw = 0f
        /** Where it's walking to (null: standing). */
        var to: Vec3? = null
        /** At this terminal doing it (others see "already using"). */
        var working: Station? = null
        /** Leapt onto you at your early enter: stays with you until the next section starts. */
        var parked = false
    }

    private val bots = ArrayList<Bot>()

    /** The bots, in leap slot order (rebuilt when your class or the order changes). */
    fun bots(): List<Bot> {
        val order = P3Plan.botOrder()
        if (bots.map { it.clazz } != order) { clear(); bots.clear(); order.forEachIndexed { i, c -> bots += Bot(c, i + 1) } }
        return bots
    }

    fun bot(slot: Int) = bots().getOrNull(slot - 1)

    /** Bumped by every clear: anything queued before a restart is dropped. */
    private var generation = 0

    fun clear() {
        generation++
        jobs.clear(); leaps.clear()
        bots.forEach { it.entity?.discard(); it.entity = null; it.to = null; it.working = null }
    }

    fun busyAt(st: Station) = bots.any { it.working === st }

    /** Everyone (the bots) inside [box]. */
    fun allIn(box: AABB) = !P3Sim.bots || bots.all { it.entity == null || box.contains(it.pos) }

    // ------------------------------------------------------------------ P3

    /** A bot's job: [job] (a station id or "gate k") done by [bot] at n = [at]. */
    private class Job(val job: String, val section: Int, val bot: Bot, val at: Int)

    private val jobs = ArrayList<Job>()
    /** Leaps queued: [bot] onto [onto] (null = you) at n = [at]. */
    private class Leap(val bot: Bot, val onto: Bot?, val at: Int)
    private val leaps = ArrayList<Leap>()
    private var planned = 0
    /** Early enters you've reached (by section entered). */
    private val youArrived = BooleanArray(6)

    fun startP3(phase: GoldorPhase) {
        clear()
        youArrived.fill(false)
        planned = 0
        if (!P3Sim.bots) return
        val from = phase.from.coerceIn(1, 5)
        val ee = P3Plan.ee(from)?.takeIf { !it.byYou }
        for (b in bots()) spawn(b, if (ee != null) ee.spot.add(Random.nextDouble(-1.0, 1.0), 0.0, Random.nextDouble(-1.0, 1.0)) else startPos(from))
    }

    fun tickP3(phase: GoldorPhase) {
        if (!P3Sim.bots) return
        val n = phase.n
        val s = phase.section
        if (s != planned) { planned = s; sectionStarted(phase, s) }
        earlyEnters(phase)
        // Leaps that are due.
        leaps.removeAll { l ->
            if (n < l.at) return@removeAll false
            val target = l.onto?.pos ?: Sim.player?.position()
            if (target != null) { l.bot.pos = target; l.bot.to = null; if (l.onto == null) l.bot.parked = true }
            true
        }
        // Jobs that are due (a section's last held while you're on your way to your early enter).
        val held = holding(phase)
        jobs.removeAll { j ->
            if (n < j.at) return@removeAll false
            val st = phase.stations.firstOrNull { it.id == j.job }
            if (st != null) {
                if (st.done) return@removeAll true
                if (st.section == s && held && phase.stations.count { it.section == s && !it.done } == 1) return@removeAll false
                if (st.kind == Station.Kind.TERMINAL && Terminals.inUse(st)) return@removeAll false
                if (st.kind == Station.Kind.LEVER) phase.pullLever(st, j.bot.name) else st.complete(j.bot.name)
                if (st.kind == Station.Kind.DEVICE) phase.devices.shownDone(st.label)
                if (!st.done) return@removeAll false
            } else {
                val k = j.job.removePrefix("gate ").toIntOrNull() ?: return@removeAll true
                if (!phase.gateIsDown(k) && !phase.blowGate(k, j.bot.name)) return@removeAll false
            }
            j.bot.working = null
            next(j.bot)?.let { goTo(j.bot, spotOf(it.job)) }
            true
        }
        for (b in bots) move(b)
        // Working: at its terminal for the last 2 s before it's done.
        for (b in bots) b.working = next(b)?.takeIf { it.at - n <= 40 }?.let { j -> phase.stations.firstOrNull { it.id == j.job && it.kind == Station.Kind.TERMINAL } }
    }

    /** Section [s] began: schedule its jobs (and the later devices'), bots walk to theirs or leap onto the early enterer. */
    private fun sectionStarted(phase: GoldorPhase, s: Int) {
        val n = phase.n
        jobs.removeAll { it.section < s }
        bots.forEach { it.parked = false }
        if (s >= 5) { core(phase); return }
        val todo = P3Plan.jobsIn(s).filter { !P3Plan.isMine(it) }.filter { j ->
            phase.stations.firstOrNull { it.id == j }?.done != true && !(j.startsWith("gate") && phase.gateIsDown(s))
        }
        // The bot early-entering the next section is busy getting there.
        val eeBot = P3Plan.ee(s + 1)?.takeIf { !it.byYou }?.let { bot(it.who) }
        val free = bots.filter { it !== eeBot }.ifEmpty { bots.toList() }
        todo.forEachIndexed { i, job ->
            val owner = P3Plan.DEFAULT_OWNER[job]?.let { c -> free.firstOrNull { it.clazz == c } } ?: free[i % free.size]
            val at = n + (20 * (P3Plan.botMin + Random.nextDouble() * (P3Plan.botMax - P3Plan.botMin).coerceAtLeast(0.0))).toInt()
            jobs += Job(job, s, owner, at)
        }
        // Into this section: everyone not already in it leaps onto the bot that early-entered, or walks to their first job.
        val ee = P3Plan.ee(s)
        val onto = ee?.takeIf { !it.byYou }?.let { bot(it.who) }
        bots.forEachIndexed { i, b ->
            if (onto != null && b !== onto && !youArrived[s]) leaps += Leap(b, onto, n + 2 + i * gapTicks())
            else next(b)?.let { goTo(b, spotOf(it.job)) }
        }
        if (eeBot != null) {
            val e = P3Plan.ee(s + 1)!!
            val gen = generation
            Fight.later((e.after * 20).toInt(), "bot early enter") { if (gen == generation) goTo(eeBot, e.spot) }
        }
        // After a leap, on to their jobs.
        if (onto != null) {
            val gen = generation
            Fight.later(4 + bots.size * gapTicks(), "bots to jobs") { if (gen == generation) bots.forEach { b -> next(b)?.let { goTo(b, spotOf(it.job)) } } }
        }
    }

    /** Early enters you do: once you're at the spot, the bots leap onto you one by one. */
    private fun earlyEnters(phase: GoldorPhase) {
        val ee = P3Plan.ee(phase.section + 1)?.takeIf { it.byYou } ?: return
        if (youArrived[ee.into]) return
        val p = Sim.player ?: return
        if (p.position().distanceTo(ee.spot) > 3.0) return
        youArrived[ee.into] = true
        val n = phase.n
        bots.forEachIndexed { i, b -> leaps += Leap(b, null, n + (i + 1) * gapTicks()) }
        Sim.note("§aAt your ${ee.label}§7: the party leaps to you.")
    }

    /** Is a section's last job held for you (you early-enter the next one and aren't there yet). */
    private fun holding(phase: GoldorPhase): Boolean {
        if (!P3Plan.waitForYou) return false
        val ee = P3Plan.ee(phase.section + 1)?.takeIf { it.byYou } ?: return false
        return !youArrived[ee.into]
    }

    /** The core: everyone in (onto the core early enterer if there is one). */
    private fun core(phase: GoldorPhase) {
        val ee = P3Plan.ee(5)
        val onto = ee?.takeIf { !it.byYou }?.let { bot(it.who) }
        bots.forEachIndexed { i, b ->
            if (onto != null && b !== onto) leaps += Leap(b, onto, phase.n + 2 + i * gapTicks())
            else goTo(b, CORE_SPOT.add((i - 1.5) * 1.5, 0.0, 2.0 + Random.nextDouble()))
        }
        if (onto != null) {
            val gen = generation
            Fight.later(4 + bots.size * gapTicks(), "bots into core") { if (gen == generation) bots.forEachIndexed { i, b -> goTo(b, CORE_SPOT.add((i - 1.5) * 1.5, 0.0, 2.0 + Random.nextDouble())) } }
        }
    }

    private fun gapTicks() = (P3Plan.leapGap * 20).toInt().coerceAtLeast(1)

    private fun next(b: Bot) = jobs.filter { it.bot === b }.minByOrNull { it.at }

    private fun spotOf(job: String): Vec3 = STANDS[job] ?: job.removePrefix("gate ").toIntOrNull()?.let { GATES.getOrNull(it) } ?: CORE_SPOT

    private fun goTo(b: Bot, at: Vec3) { if (!b.parked) b.to = at }

    private fun move(b: Bot) {
        val to = b.to
        if (to != null) {
            val d = to.subtract(b.pos)
            val len = d.length()
            if (len <= WALK) { b.pos = to; b.to = null } else b.pos = b.pos.add(d.scale(WALK / len))
            if (Math.abs(d.x) + Math.abs(d.z) > 0.01) b.yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat()
        }
        place(b)
    }

    // ------------------------------------------------------------------ outside P3

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
        m.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(if (b.clazz == DungeonClass.ARCHER || b.clazz == DungeonClass.BERSERK) Items.BOW else Items.DIAMOND_PICKAXE))
        hideDescription(m)
        b.pos = at
        m.snapTo(at.x, at.y, at.z, 0f, 0f)
        b.entity = Sim.spawn(m)
    }

    private val hideMethod by lazy { Mannequin::class.java.getDeclaredMethod("setHideDescription", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
    private fun hideDescription(m: Mannequin) { runCatching { hideMethod.invoke(m, true) } }

    private fun place(b: Bot) {
        val e = b.entity ?: return
        e.snapTo(b.pos.x, b.pos.y, b.pos.z, b.yaw, 0f)
        e.yHeadRot = b.yaw; e.yBodyRot = b.yaw
    }

    private fun startPos(from: Int): Vec3 = when (from) {
        1 -> STANDS.getValue("S1 SS").add(Random.nextDouble(-1.5, 0.0), 0.0, Random.nextDouble(-1.5, 1.5))
        2 -> GoldorPhase.GATE_CENTRES[1].add(Random.nextDouble(-2.0, 2.0), -3.0, 3.0)
        3 -> GoldorPhase.GATE_CENTRES[2].add(-3.0, -3.0, Random.nextDouble(-2.0, 2.0))
        else -> STRIP.add(Random.nextDouble(-4.0, 4.0), 0.0, 0.0)
    }

    // ------------------------------------------------------------------ places

    /** Where a player stands to do each job (median from the recordings, terminal-roles.md). */
    val STANDS: Map<String, Vec3> = mapOf(
        "S1 T1" to Vec3(109.1, 118.8, 79.6), "S1 T2" to Vec3(92.3, 121.0, 99.7), "S1 T3" to Vec3(110.3, 113.0, 73.8), "S1 T4" to Vec3(92.1, 112.0, 92.7),
        "S1 east lever" to Vec3(106.9, 122.0, 111.7), "S1 west lever" to Vec3(95.4, 123.1, 113.6), "S1 SS" to Vec3(108.3, 120.0, 94.0),
        "S2 T1" to Vec3(69.0, 109.0, 124.7), "S2 T2" to Vec3(59.7, 120.0, 125.3), "S2 T3" to Vec3(46.4, 109.0, 122.6), "S2 T4" to Vec3(40.2, 124.0, 124.7), "S2 T5" to Vec3(39.2, 109.0, 140.5),
        "S2 low lever" to Vec3(28.3, 124.0, 128.7), "S2 high lever" to Vec3(25.6, 132.2, 137.5), "S2 Lights" to Vec3(60.6, 134.0, 139.0),
        "S3 T1" to Vec3(0.0, 109.0, 112.2), "S3 T2" to Vec3(1.0, 119.0, 93.6), "S3 T3" to Vec3(16.5, 123.0, 93.7), "S3 T4" to Vec3(0.8, 109.0, 77.5),
        "S3 west lever" to Vec3(4.3, 123.1, 55.4), "S3 east lever" to Vec3(13.0, 122.4, 55.7), "S3 Arrows" to Vec3(0.5, 120.0, 77.5),
        "S4 T1" to Vec3(41.3, 109.0, 32.6), "S4 T2" to Vec3(45.1, 121.6, 31.2), "S4 T3" to Vec3(67.1, 109.0, 33.1), "S4 T4" to Vec3(72.6, 115.0, 45.5),
        "S4 low lever" to Vec3(84.4, 122.5, 34.9), "S4 high lever" to Vec3(85.5, 124.6, 43.6), "S4 Target" to Vec3(63.5, 127.0, 35.5),
    )
    val GATES = arrayOf(Vec3.ZERO, Vec3(95.8, 123.9, 121.0), Vec3(19.3, 123.6, 127.9), Vec3(12.4, 116.8, 52.7))
    val STRIP = Vec3(54.6, 115.0, 51.5)
    val CORE_SPOT = Vec3(54.5, 115.0, 58.0)

    /** Your jobs, for the menu. */
    fun myJobs(): List<String> = (1..4).mapNotNull { s ->
        val mine = P3Plan.jobsIn(s).filter { P3Plan.isMine(it) }.map { it.removePrefix("S$s ") }
        if (mine.isEmpty()) null else "S$s: ${mine.joinToString(", ")}"
    }
}
