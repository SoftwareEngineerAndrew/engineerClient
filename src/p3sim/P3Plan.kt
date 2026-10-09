package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.io.File

/**
 * Your P3 plan: the skill preset ([Roles]: who does what, and when), what's yours (your class's
 * role, changeable per job), the early-enter spots and the leap menu. The menu's Plan and Early
 * Enters tabs edit it; it's saved to `config/engineerclient/p3sim-plan.json`.
 *
 * The roles and their times live in [Roles]; the early-enter spots' defaults are here.
 */
object P3Plan {
    /**
     * The skill levels: [Roles.PRESETS] with Random (Quality PF's roles, each bot job at a random time) 4th
     * (the choice is saved by index, so it stays there); later presets after it.
     */
    const val RANDOM = 3
    val SKILLS = Roles.PRESETS.take(RANDOM).map { it.name } + "Random" + Roles.PRESETS.drop(RANDOM).map { it.name }

    /**
     * Where each early enter stands (into: 5 = the core, just outside it in S4; 6 = the recore,
     * inside the core, where the core player goes once everyone's leapt) and which way they face.
     * Who does it comes from the roles (the recore: the core's).
     */
    class EarlyEnter(val key: String, val label: String, val into: Int, var spot: Vec3, var yaw: Float = 0f, var pitch: Float = 0f) {
        val owner: DungeonClass? get() = plan().ee[if (into == 6) 5 else into]
        val on get() = owner != null
        val byYou get() = owner != null && owner == P3Sim.myClass
    }

    fun defaultEarlyEnters() = listOf(
        // On S2's device (Lights): the EE2 player does it early and waits there for the leaps.
        EarlyEnter("ee2", "EE2", 2, Vec3(60.6, 132.0, 139.0)),
        EarlyEnter("ee3", "EE3", 3, Vec3(1.9, 109.0, 104.6)),
        // Just outside the core in S4; then inside it.
        EarlyEnter("core", "Core", 5, Vec3(54.5, 115.06, 50.5)),
        EarlyEnter("recore", "Recore", 6, Vec3(54.4, 115.0, 57.6)),
    )

    // ------------------------------------------------------------------ the plan

    var skill = 1
    /** Your jobs: station ids ("S1 T1", "S2 Lights", "S3 west lever"...) and "gate 1".."gate 3". */
    private val mine = LinkedHashSet<String>()
    /** Which skill/class [mine] was made for: a change of either starts again from that role. */
    private var mineFor = ""
    /** Random only: each bot job between these (seconds after its section starts). */
    var botMin = 1.0
    var botMax = 9.0
    val earlyEnters = defaultEarlyEnters()
    /** Hold a section's last bot job until you're at your early enter for the next one. */
    var waitForYou = true
    /** Bots help on your stacks (the preset's helps: another's terminal doer gets a lever of yours too). */
    var helper = false
    /** Seconds between the bots leaping onto you once you're at your early enter (pre moves). */
    var leapGap = 0.5
    /** The four bots' classes, leap menu slot 1 to 4 (your class is left out). */
    val leapOrder = ArrayList<DungeonClass>()
    /** Classes whose bot plays your best run as that class (P3 from S1, when one is saved: [GhostStore]). */
    val ghosts = LinkedHashSet<String>()

    /**
     * Spots set in the Roles menu (where, and which way you or the bot face) for one skill and one class of yours ("1/MAGE/spawn", "1/MAGE/ee3"...): your P3
     * spawn ("spawn") and each early enter's spot ([EarlyEnter.key]), whoever does it.
     */
    private var custom: HashMap<String, Spots.Spot>? = HashMap()
    /** [custom], made if it isn't there (a hotswapped game starts it null). */
    private fun spots() = custom ?: HashMap<String, Spots.Spot>().also { custom = it }
    private fun customKey(key: String) = "$skill/${P3Sim.myClass?.name}/$key"

    /** The spot set for this skill and class, else the default ([defaultSpot]: Normal PF's, for every skill). */
    fun customSpot(key: String): Spots.Spot? = spots()[customKey(key)] ?: defaultSpot(P3Sim.myClass, key)
    /** Whether this skill and class has its own [key] spot (set in Roles), not the default. */
    fun hasCustomSpot(key: String) = spots().containsKey(customKey(key))
    /** null: back to the default. */
    fun setCustomSpot(key: String, p: Spots.Spot?) { if (p == null) spots().remove(customKey(key)) else spots()[customKey(key)] = p; save() }

    /**
     * Every skill's default spawn and early-enter spots, by your class: the ones set for Normal PF (2026-10-09). The early
     * enters are the same whatever your class; the spawn is S1's terminal side for all but the Berserk (at i4).
     */
    fun defaultSpot(c: DungeonClass?, key: String): Spots.Spot? = when (key) {
        "spawn" -> if (c == DungeonClass.BERSERK) Spots.Spot("Spawn", 63.5, 127.0, 35.5, -11.18f, 0.33f)
            else Spots.Spot("Spawn", 108.3, 120.0, 94.0, 269.6f, 0.57f)
        "ee2" -> Spots.Spot("EE2", 60.5, 132.0, 139.0, 90.47f, 1.64f)
        "ee3" -> Spots.Spot("EE3", 2.0, 109.0, 102.0, -175.24f, 5.83f)
        "core" -> Spots.Spot("Core", 54.5, 115.06, 50.5, 179.91f, 0.57f)
        "recore" -> Spots.Spot("Recore", 52.5, 115.0, 57.6, -159.12f, 2.63f)
        else -> null
    }

    /** Where [e] is stood on with this skill and class: the one set for them, else the default, else the preset's, else the Early Enters tab's. */
    fun eeSpot(e: EarlyEnter): Spots.Spot = customSpot(e.key) ?: (preset().spots[e.key] ?: e.spot).let { Spots.Spot(e.label, it.x, it.y, it.z, e.yaw, e.pitch) }

    fun ghostOn(c: DungeonClass) = c.name in ghosts
    fun toggleGhost(c: DungeonClass) { if (!ghosts.remove(c.name)) ghosts += c.name; save() }

    fun preset(): Roles.Preset = Roles.PRESETS[when {
        skill == RANDOM -> 1
        skill > RANDOM -> (skill - 1).coerceAtMost(Roles.PRESETS.size - 1)
        else -> skill.coerceAtLeast(0)
    }]
    fun plan(): Roles.Plan = Roles.plan(preset())
    fun skillName() = SKILLS[skill.coerceIn(0, SKILLS.size - 1)]

    fun mine(): Set<String> {
        val key = "$skill/${P3Sim.myClass}"
        if (mineFor != key) {
            mineFor = key
            mine.clear()
            mine += plan().jobsOf[P3Sim.myClass].orEmpty()
        }
        return mine
    }

    fun isMine(job: String) = job in mine()
    fun toggle(job: String) { mine(); if (!mine.remove(job)) mine += job; save() }
    /** Back to your class's role: your jobs, the bots picked and your stacks for this skill and class. */
    fun resetMine() {
        mineFor = ""; mine()
        val prefix = "$skill/${P3Sim.myClass?.name}/"
        pickMap().keys.removeIf { it.startsWith(prefix) }; stackSet().removeIf { it.startsWith(prefix) }
        save()
    }
    fun chooseSkill(i: Int) { skill = i.coerceIn(0, SKILLS.size - 1); mine(); save() }

    fun ee(into: Int) = earlyEnters.firstOrNull { it.into == into && it.on }

    /** Jobs two classes share (whoever gets there first; yours if one of them is you). */
    fun isStack(job: String) = (plan().owners[job]?.size ?: 0) > 1

    /** Who does [job]: you if it's yours, else the bot you picked for it (right click), else its role's (a stack: the first listed), null: whichever bot is least busy. */
    fun doer(job: String): DungeonClass? = if (isMine(job)) P3Sim.myClass else picked(job) ?: roleDoer(job)

    /** [job]'s bot by the roles (a stack: the first listed that isn't you). */
    fun roleDoer(job: String): DungeonClass? = plan().owners[job]?.firstOrNull { it != P3Sim.myClass }

    /** Bots picked for jobs (skill/your class/job -> class), and your jobs a bot does too with the Helper on: for this skill and class. */
    private var picks: HashMap<String, String>? = HashMap()
    private var stacked: HashSet<String>? = HashSet()
    private fun pickMap() = picks ?: HashMap<String, String>().also { picks = it }
    private fun stackSet() = stacked ?: HashSet<String>().also { stacked = it }
    private fun jobKey(job: String) = "$skill/${P3Sim.myClass?.name}/$job"

    private fun picked(job: String): DungeonClass? =
        pickMap()[jobKey(job)]?.let { n -> Party.CLASSES.firstOrNull { it.name == n && it != P3Sim.myClass } }

    /** A bot's job goes to the next bot (back to its role's: the pick is dropped). */
    fun cycleDoer(job: String) {
        val bots = Party.CLASSES.filter { it != P3Sim.myClass }
        val next = bots[(bots.indexOf(doer(job)) + 1) % bots.size]
        if (next == roleDoer(job)) pickMap().remove(jobKey(job)) else pickMap()[jobKey(job)] = next.name
        save()
    }

    /** One of your jobs a bot does too, with the Helper on (whoever's first): a stack you made. */
    fun isStacked(job: String) = jobKey(job) in stackSet()
    fun toggleStacked(job: String) { if (!stackSet().remove(jobKey(job))) stackSet() += jobKey(job); save() }

    /** The bot that helps on your stacked [job]: the one you picked, its role's, else the first bot. */
    fun helperOf(job: String): DungeonClass? = picked(job) ?: roleDoer(job) ?: Party.CLASSES.firstOrNull { it != P3Sim.myClass }

    /** Every job, in menu order: each section's terminals, levers, device, then its gate. */
    fun allJobs(): List<String> = (1..4).flatMap { s -> jobsIn(s) }

    fun jobsIn(s: Int): List<String> = Station.all().filter { it.section == s }.map { it.id } + (if (s <= 3) listOf("gate $s") else emptyList())

    /** A job as the menus show it: a terminal's number ("2"), "L" / "R" for the levers, "D" the device, "G" the gate. */
    fun short(job: String): String {
        if (job.startsWith("gate")) return "G"
        if (job in Roles.LEFT.values) return "L"
        if (job in Roles.RIGHT.values) return "R"
        val name = job.substringAfter(' ')
        if (name.length > 1 && name[0] == 'T' && name.drop(1).all { it.isDigit() }) return name.drop(1)
        return "D"
    }

    /** The bots' classes in leap slot order (fills in, drops your class). */
    fun botOrder(): List<DungeonClass> {
        val mineClass = P3Sim.myClass
        val order = (leapOrder + Party.CLASSES).distinct().filter { it != mineClass }
        leapOrder.clear(); leapOrder += order
        return if (odinSort) odinOrder(order) ?: order else order
    }

    /** The leap menu sorted as Odin does by default: by class priority, into Odin's quadrants. On unless you choose your own order. */
    var odinSort = true

    private fun odinOrder(classes: List<DungeonClass>): List<DungeonClass>? = runCatching {
        val players = classes.map { com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer(Roles.label(it), it, 50, null) }.sortedBy { it.clazz.priority }
        com.odtheking.odin.features.impl.dungeon.LeapMenu.odinSorting(players).toList().map { it.clazz }.filter { it in classes }
    }.getOrNull()?.takeIf { it.size == classes.size && it.toSet() == classes.toSet() }

    /** Slot [slot] (1-4) takes the next class ([back]: the one before): swaps with the slot that had it. */
    fun cycleSlot(slot: Int, back: Boolean = false) {
        // From Odin's order to your own, starting from what it showed.
        val order = botOrder().toMutableList()
        odinSort = false
        val i = slot - 1
        val j = (i + (if (back) order.size - 1 else 1)) % order.size
        val t = order[i]; order[i] = order[j]; order[j] = t
        leapOrder.clear(); leapOrder += order
        save()
    }

    // ------------------------------------------------------------------ saving

    private class Saved(
        val skill: Int? = null, val mine: List<String>? = null, val mineFor: String? = null,
        val botMin: Double? = null, val botMax: Double? = null,
        val waitForYou: Boolean? = null, val leapGap: Double? = null, val leapOrder: List<String>? = null, val odinSort: Boolean? = null,
        val spots: Map<String, List<Double>>? = null,
        val helper: Boolean? = null,
        val ghosts: List<String>? = null,
        /** [custom]: key -> [x, y, z, yaw, pitch]. */
        val custom: Map<String, List<Double>>? = null,
        /** Bots picked for jobs ([doer]) and your stacked jobs ([isStacked]), keyed skill/class/job. */
        val picks: Map<String, String>? = null,
        val stacked: List<String>? = null,
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = File(Minecraft.getInstance().gameDirectory, "config/engineerclient/p3sim-plan.json")
    private var loaded = false
    private var backedUp = false

    fun load() {
        if (loaded) return
        loaded = true
        EngineerClient.safely("p3sim plan load") {
            val f = file
            if (!f.exists()) return@safely
            val s = gson.fromJson(f.readText(), Saved::class.java) ?: return@safely
            s.skill?.let { skill = it.coerceIn(0, SKILLS.size - 1) }
            if (s.mine != null && s.mineFor != null) { mine.clear(); mine += s.mine; mineFor = s.mineFor }
            s.botMin?.let { botMin = it }
            s.botMax?.let { botMax = it }
            s.waitForYou?.let { waitForYou = it }
            s.helper?.let { helper = it }
            s.ghosts?.let { ghosts.clear(); ghosts += it }
            s.custom?.forEach { (k, v) -> if (v.size >= 5) spots()[k] = Spots.Spot(k, v[0], v[1], v[2], v[3].toFloat(), v[4].toFloat()) }
            s.picks?.let { pickMap().clear(); pickMap() += it }
            s.stacked?.let { stackSet().clear(); stackSet() += it }
            s.leapGap?.let { leapGap = it }
            s.odinSort?.let { odinSort = it }
            s.leapOrder?.let { names -> leapOrder.clear(); leapOrder += names.mapNotNull { n -> Party.CLASSES.firstOrNull { it.name == n } } }
            // [x, y, z] or [x, y, z, yaw, pitch]. A saved EE2 spot at (69, 109, 124.7), S2's 1st terminal and an
            // earlier default, is ignored so the current default applies.
            s.spots?.forEach { (k, v) ->
                earlyEnters.firstOrNull { it.key == k }?.let {
                    if (v.size >= 3 && !(k == "ee2" && v == listOf(69.0, 109.0, 124.7))) it.spot = Vec3(v[0], v[1], v[2])
                    if (v.size >= 5) { it.yaw = v[3].toFloat(); it.pitch = v[4].toFloat() }
                }
            }
        }
    }

    fun save() {
        EngineerClient.safely("p3sim plan save") {
            val s = Saved(skill, mine.toList(), mineFor, botMin, botMax, waitForYou, leapGap, botOrder().let { leapOrder.map { it.name } }, odinSort,
                earlyEnters.associate { it.key to listOf(it.spot.x, it.spot.y, it.spot.z, it.yaw.toDouble(), it.pitch.toDouble()) }, helper, ghosts.toList(),
                spots().mapValues { (_, p) -> listOf(p.x, p.y, p.z, p.yaw.toDouble(), p.pitch.toDouble()) }, HashMap(pickMap()), stackSet().toList())
            file.parentFile.mkdirs()
            // A backup of the file before this session's first save, should a save ever lose something.
            if (!backedUp && file.exists()) { backedUp = true; file.copyTo(File(file.path + ".bak"), overwrite = true) }
            file.writeText(gson.toJson(s))
        }
    }
}
