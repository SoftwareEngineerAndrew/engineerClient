package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.io.File

/**
 * Your P3 plan: what you do, what the bots do, and the early enters. The menu's Plan and Early
 * Enters tabs edit it; it's saved to `config/engineerclient/p3sim-plan.json` (readable, and fine
 * to edit by hand while the game is closed).
 *
 * Everything in a section that isn't yours is done by the bots, each at a random time between
 * [botMin] and [botMax] seconds after the section starts. To change a default, change it here.
 */
object P3Plan {
    // ------------------------------------------------------------------ defaults (edit these)

    /** Who does what by default (the 5-role rotation of docs/mechanics/terminal-roles.md). Gates are "gate 1".."gate 3". */
    val DEFAULT_OWNER: Map<String, DungeonClass> = mapOf(
        "S1 SS" to DungeonClass.HEALER, "S2 T1" to DungeonClass.HEALER, "S3 west lever" to DungeonClass.HEALER, "S3 east lever" to DungeonClass.HEALER,
        "S4 low lever" to DungeonClass.HEALER, "S4 high lever" to DungeonClass.HEALER, "S4 T4" to DungeonClass.HEALER,
        "S4 Target" to DungeonClass.BERSERK, "S1 west lever" to DungeonClass.BERSERK, "gate 1" to DungeonClass.BERSERK, "S2 high lever" to DungeonClass.BERSERK,
        "S2 T5" to DungeonClass.BERSERK, "S3 T3" to DungeonClass.BERSERK, "S3 Arrows" to DungeonClass.BERSERK, "S4 T1" to DungeonClass.BERSERK,
        "S1 T1" to DungeonClass.ARCHER, "S1 east lever" to DungeonClass.ARCHER, "S2 T4" to DungeonClass.ARCHER, "S3 T1" to DungeonClass.ARCHER,
        "S3 T4" to DungeonClass.ARCHER, "S4 T3" to DungeonClass.ARCHER,
        "S1 T4" to DungeonClass.TANK, "S1 T2" to DungeonClass.TANK, "S2 T3" to DungeonClass.TANK, "gate 2" to DungeonClass.TANK,
        "S3 T2" to DungeonClass.TANK, "gate 3" to DungeonClass.TANK, "S4 T2" to DungeonClass.TANK,
        "S1 T3" to DungeonClass.MAGE, "S2 Lights" to DungeonClass.MAGE, "S2 T2" to DungeonClass.MAGE, "S2 low lever" to DungeonClass.MAGE,
    )

    /** The menu's presets: the roles' jobs (from [DEFAULT_OWNER]), plus everything and nothing. */
    val PRESETS: List<Pair<String, () -> Set<String>>> = listOf(
        "ss" to { ownedBy(DungeonClass.HEALER) },
        "i4" to { ownedBy(DungeonClass.BERSERK) },
        "ee3" to { ownedBy(DungeonClass.ARCHER) },
        "gates" to { ownedBy(DungeonClass.TANK) },
        "ee2/core" to { ownedBy(DungeonClass.MAGE) },
        "all" to { allJobs().toSet() },
        "none" to { emptySet() },
    )

    /**
     * The early enters: [into] is the section you enter early (5 = the core), [spot] where whoever
     * does it stands, [after] how many seconds into the section before a bot goes there.
     */
    class EarlyEnter(val key: String, val label: String, val into: Int, var spot: Vec3, var after: Double) {
        /** -1 off, 0 you, 1-4 the bot in that leap menu slot. */
        var who = -1
        val on get() = who >= 0
        val byYou get() = who == 0
    }

    fun defaultEarlyEnters() = listOf(
        EarlyEnter("ee2", "EE2", 2, Vec3(69.0, 109.0, 124.7), 5.0),
        EarlyEnter("ee3", "EE3", 3, Vec3(0.0, 109.0, 112.2), 5.0),
        EarlyEnter("ee4", "EE4", 4, Vec3(41.3, 109.0, 32.6), 5.0),
        EarlyEnter("core", "Core", 5, Vec3(54.6, 115.0, 51.5), 3.0),
    )

    // ------------------------------------------------------------------ the plan

    /** Your jobs: station ids ("S1 T1", "S2 Lights", "S3 west lever"...) and "gate 1".."gate 3". */
    val mine = LinkedHashSet<String>(ownedBy(DungeonClass.BERSERK))
    /** The bots' times: each of theirs between these (seconds after its section starts). */
    var botMin = 1.0
    var botMax = 9.0
    val earlyEnters = defaultEarlyEnters()
    /** Hold a section's last bot job until you're at your early enter for the next one. */
    var waitForYou = true
    /** Seconds between the bots leaping onto you once you're at your early enter (pre moves). */
    var leapGap = 0.5
    /** The four bots' classes, leap menu slot 1 to 4 (your class is left out). */
    val leapOrder = ArrayList<DungeonClass>()

    fun isMine(job: String) = job in mine
    fun toggle(job: String) { if (!mine.remove(job)) mine += job; save() }
    fun ee(into: Int) = earlyEnters.firstOrNull { it.into == into && it.on }

    /** Every job, in menu order: each section's terminals, levers, device, then its gate. */
    fun allJobs(): List<String> = (1..4).flatMap { s -> jobsIn(s) }

    fun jobsIn(s: Int): List<String> = Station.all().filter { it.section == s }.map { it.id } + (if (s <= 3) listOf("gate $s") else emptyList())

    private fun ownedBy(c: DungeonClass) = DEFAULT_OWNER.filterValues { it == c }.keys

    /** The bots' classes in leap slot order (fills in, drops your class). */
    fun botOrder(): List<DungeonClass> {
        val mineClass = P3Sim.myClass
        val order = (leapOrder + Party.CLASSES).distinct().filter { it != mineClass }
        leapOrder.clear(); leapOrder += order
        return order
    }

    /** Slot [slot] (1-4) takes the next class: swaps with the slot that had it. */
    fun cycleSlot(slot: Int) {
        val order = botOrder().toMutableList()
        val i = slot - 1
        val j = (i + 1) % order.size
        val t = order[i]; order[i] = order[j]; order[j] = t
        leapOrder.clear(); leapOrder += order
        save()
    }

    // ------------------------------------------------------------------ saving

    private class Saved(
        val mine: List<String>? = null, val botMin: Double? = null, val botMax: Double? = null,
        val waitForYou: Boolean? = null, val leapGap: Double? = null, val leapOrder: List<String>? = null,
        val earlyEnters: List<SavedEe>? = null,
    )
    private class SavedEe(val key: String, val who: Int, val spot: List<Double>, val after: Double)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = File(Minecraft.getInstance().gameDirectory, "config/engineerclient/p3sim-plan.json")
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        EngineerClient.safely("p3sim plan load") {
            val f = file
            if (!f.exists()) return@safely
            val s = gson.fromJson(f.readText(), Saved::class.java) ?: return@safely
            s.mine?.let { mine.clear(); mine += it }
            s.botMin?.let { botMin = it }
            s.botMax?.let { botMax = it }
            s.waitForYou?.let { waitForYou = it }
            s.leapGap?.let { leapGap = it }
            s.leapOrder?.let { names -> leapOrder.clear(); leapOrder += names.mapNotNull { n -> Party.CLASSES.firstOrNull { it.name == n } } }
            s.earlyEnters?.forEach { e ->
                val ee = earlyEnters.firstOrNull { it.key == e.key } ?: return@forEach
                ee.who = e.who.coerceIn(-1, 4)
                if (e.spot.size == 3) ee.spot = Vec3(e.spot[0], e.spot[1], e.spot[2])
                ee.after = e.after
            }
        }
    }

    fun save() {
        EngineerClient.safely("p3sim plan save") {
            val s = Saved(mine.toList(), botMin, botMax, waitForYou, leapGap, botOrder().map { it.name },
                earlyEnters.map { SavedEe(it.key, it.who, listOf(it.spot.x, it.spot.y, it.spot.z), it.after) })
            file.parentFile.mkdirs()
            file.writeText(gson.toJson(s))
        }
    }
}
