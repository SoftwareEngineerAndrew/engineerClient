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
    /** The skill levels: [Roles.PRESETS], then Random (Quality PF's roles, each bot job at a random time). */
    val SKILLS = Roles.PRESETS.map { it.name } + "Random"
    const val RANDOM = 3

    /** Where each early enter stands (into: 5 = the core). Who does it comes from the roles. */
    class EarlyEnter(val key: String, val label: String, val into: Int, var spot: Vec3) {
        val owner: DungeonClass? get() = plan().ee[into]
        val on get() = owner != null
        val byYou get() = owner != null && owner == P3Sim.myClass
    }

    fun defaultEarlyEnters() = listOf(
        // On S2's device (Lights): the EE2 player does it early and waits there for the leaps.
        EarlyEnter("ee2", "EE2", 2, Vec3(60.6, 132.0, 139.0)),
        EarlyEnter("ee3", "EE3", 3, Vec3(0.0, 109.0, 112.2)),
        EarlyEnter("ee4", "EE4", 4, Vec3(41.3, 109.0, 32.6)),
        EarlyEnter("core", "Core", 5, Vec3(54.6, 115.0, 51.5)),
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
    /** Seconds between the bots leaping onto you once you're at your early enter (pre moves). */
    var leapGap = 0.5
    /** The four bots' classes, leap menu slot 1 to 4 (your class is left out). */
    val leapOrder = ArrayList<DungeonClass>()

    fun preset(): Roles.Preset = Roles.PRESETS[if (skill == RANDOM) 1 else skill.coerceIn(0, Roles.PRESETS.size - 1)]
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
    /** Back to your class's role. */
    fun resetMine() { mineFor = ""; mine(); save() }
    fun chooseSkill(i: Int) { skill = i.coerceIn(0, SKILLS.size - 1); mine(); save() }

    fun ee(into: Int) = earlyEnters.firstOrNull { it.into == into && it.on }

    /** Jobs two classes share (whoever gets there first; yours if one of them is you). */
    fun isStack(job: String) = (plan().owners[job]?.size ?: 0) > 1

    /** Who does [job]: you if it's yours, else its role's bot (a stack: the first listed), null: whichever bot is least busy. */
    fun doer(job: String): DungeonClass? = if (isMine(job)) P3Sim.myClass else plan().owners[job]?.firstOrNull { it != P3Sim.myClass }

    /** Every job, in menu order: each section's terminals, levers, device, then its gate. */
    fun allJobs(): List<String> = (1..4).flatMap { s -> jobsIn(s) }

    fun jobsIn(s: Int): List<String> = Station.all().filter { it.section == s }.map { it.id } + (if (s <= 3) listOf("gate $s") else emptyList())

    /** The bots' classes in leap slot order (fills in, drops your class). */
    fun botOrder(): List<DungeonClass> {
        val mineClass = P3Sim.myClass
        val order = (leapOrder + Party.CLASSES).distinct().filter { it != mineClass }
        leapOrder.clear(); leapOrder += order
        return if (odinSort) odinOrder(order) ?: order else order
    }

    /** The leap menu sorted as Odin does by default: by class priority, into Odin's quadrants. */
    var odinSort = false

    private fun odinOrder(classes: List<DungeonClass>): List<DungeonClass>? = runCatching {
        val players = classes.map { com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer(Roles.label(it), it, 50, null) }.sortedBy { it.clazz.priority }
        com.odtheking.odin.features.impl.dungeon.LeapMenu.odinSorting(players).toList().map { it.clazz }.filter { it in classes }
    }.getOrNull()?.takeIf { it.size == classes.size && it.toSet() == classes.toSet() }

    /** Slot [slot] (1-4) takes the next class: swaps with the slot that had it. */
    fun cycleSlot(slot: Int) {
        // From Odin's order to your own, starting from what it showed.
        val order = botOrder().toMutableList()
        odinSort = false
        val i = slot - 1
        val j = (i + 1) % order.size
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
            s.leapGap?.let { leapGap = it }
            s.odinSort?.let { odinSort = it }
            s.leapOrder?.let { names -> leapOrder.clear(); leapOrder += names.mapNotNull { n -> Party.CLASSES.firstOrNull { it.name == n } } }
            // EE2 used to default to S2's 1st terminal (69, 109, 124.7): a saved copy of that goes to the new default.
            s.spots?.forEach { (k, v) -> earlyEnters.firstOrNull { it.key == k }?.let { if (v.size == 3 && !(k == "ee2" && v == listOf(69.0, 109.0, 124.7))) it.spot = Vec3(v[0], v[1], v[2]) } }
        }
    }

    fun save() {
        EngineerClient.safely("p3sim plan save") {
            val s = Saved(skill, mine.toList(), mineFor, botMin, botMax, waitForYou, leapGap, botOrder().let { leapOrder.map { it.name } }, odinSort,
                earlyEnters.associate { it.key to listOf(it.spot.x, it.spot.y, it.spot.z) })
            file.parentFile.mkdirs()
            // The copy before this session's first save, should a save ever lose something (as one did EE3's spot).
            if (!backedUp && file.exists()) { backedUp = true; file.copyTo(File(file.path + ".bak"), overwrite = true) }
            file.writeText(gson.toJson(s))
        }
    }
}
