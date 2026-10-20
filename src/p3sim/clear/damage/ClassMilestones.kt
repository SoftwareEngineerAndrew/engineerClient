package com.engineerclient.p3sim.clear.damage

/**
 * The class milestone chat lines of a dungeon run ("Mage Milestone ❶: You have dealt 750,000 Total Damage so far! 02s"), as pure
 * rules. Thresholds measured on Hypixel; the same for Mage, Berserk and Archer (with any number of Mages in the party: 45 recorded
 * runs with 2 to 4 Mages and 557 ADR lines all read 750,000 for the first), and the Tank has its own. A Healer has none.
 */
object ClassMilestones {
    private val STANDARD = longArrayOf(750_000, 3_750_000, 15_000_000, 37_500_000, 75_000_000, 112_500_000, 150_000_000, 187_500_000, 225_000_000)
    private val TANK = longArrayOf(450_000, 2_250_000, 9_000_000, 22_500_000, 45_000_000, 67_500_000, 90_000_000, 112_500_000, 135_000_000)

    /** The numerals on the lines, first to ninth. */
    private val NUMERALS = "❶❷❸❹❺❻❼❽❾"

    /** The thresholds of [dungeonClass] (any letter case), or null for a class with no milestones. */
    fun thresholds(dungeonClass: String?): LongArray? = when (dungeonClass?.lowercase()) {
        "mage", "berserk", "berserker", "archer" -> STANDARD
        "tank" -> TANK
        else -> null
    }

    /** How many thresholds [total] has reached. */
    fun reached(table: LongArray, total: Double): Int = table.count { total >= it }

    /** Run time as the lines show it: `02s`, or `02m 05s` after a minute. */
    fun clock(seconds: Int): String = if (seconds < 60) "%02ds".format(seconds) else "%02dm %02ds".format(seconds / 60, seconds % 60)

    /** The chat line for reaching milestone [index] (0-based) of [table]. */
    fun line(dungeonClass: String, index: Int, table: LongArray, seconds: Int): String {
        val cls = dungeonClass.lowercase()
        val name = when (cls) { "berserker" -> "Berserk"; else -> cls.replaceFirstChar { it.uppercase() } }
        val what = if (cls == "tank") "You have tanked and dealt" else "You have dealt"
        val kind = if (cls == "archer") "Ranged Damage" else "Total Damage"
        val amount = "%,d".format(java.util.Locale.ROOT, table[index])
        return "§e§l$name Milestone §r§e${NUMERALS[index]}§r§7: $what §r§c$amount§r§7 $kind so far! §r§a${clock(seconds)}"
    }
}
