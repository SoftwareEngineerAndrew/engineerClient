package com.engineerclient.p3sim.clear.secrets

import kotlin.random.Random

/** The five dungeon blessings. */
enum class Blessing(val displayName: String) {
    POWER("Power"), LIFE("Life"), WISDOM("Wisdom"), STONE("Stone"), TIME("Time");
}

/**
 * Blessings: which one a pickup is, its level, and the text and stat effect it grants (gap-sb-stats-loot 3, MEASURED lines).
 * The effect per level is linear: a flat +5.8 and a multiplier of +3% per level (Life +4.36%); Time is rare and only at level V.
 * Pure.
 */
object Blessings {
    /** The level of a blessing found as a puzzle reward (every recorded one read V, with the run clock). */
    const val OWN_LEVEL = 5
    /**
     * The levels of a blessing out of a SECRET CHEST, for the finder and for everyone who sees it: I 46 percent, II 30, V 24 (254 own
     * reveals: 118 / 70 / 66; 1,480 teammate lines: 684 / 500 / 296). None of them carries the run clock. Which level a given find gets
     * is UNMEASURED, so it is a plain weighted draw.
     */
    val CHEST_LEVEL_WEIGHTS = listOf(1 to 46, 2 to 30, 5 to 24)
    /** The types in the chest flow (1,756 lines): Life 35.5 percent, Power 27.7, Stone 20.4, Wisdom 14.5, Time 1.9 (Time exists at V only). */
    private val CHEST_WEIGHTS = listOf(Blessing.LIFE to 355, Blessing.POWER to 277, Blessing.STONE to 204, Blessing.WISDOM to 145, Blessing.TIME to 19)
    /**
     * What a teammate's puzzle-reward blessing shows, drawn per find: recorded lines read I 37.5 percent, II 27.1 percent, V 35.4 percent (4,577 lines).
     * Which level a given teammate's find gets (the distance rule, if any) is UNMEASURED, so it is a plain weighted draw.
     */
    val OBSERVER_LEVEL_WEIGHTS = listOf(1 to 375, 2 to 271, 5 to 354)
    /** Share of the V lines a teammate sees that carry the `(Ns)` clock (18.6 percent of all lines were timed V, 35.4 percent were V). */
    const val OBSERVER_TIMED_SHARE_OF_V = 0.525
    /**
     * Hypixel runs a date-wide state where every grant is 25 percent stronger (all of the grants on two recorded days, none on most
     * others), so it is not random per grant: [BOOST] applies to flat, multiplier and the speed and damage terms when the setting is on
     * (`Blessing boost x1.25`, default off). Whether Speed and Damage scale too is UNMEASURED.
     */
    const val BOOST = 1.25
    const val FLAT_PER_LEVEL = 5.8
    const val MULT_PER_LEVEL = 0.03
    const val LIFE_MULT_PER_LEVEL = 0.0436
    const val WISDOM_SPEED_PER_LEVEL = 4.8
    const val STONE_DAMAGE_PER_LEVEL = 7.2

    // Hypixel's private-use stat glyphs, as recorded.
    private const val G_STRENGTH = "\ue00d"
    private const val G_CRIT_DAMAGE = "\ue007"
    private const val G_INTELLIGENCE = "\ue003"
    private const val G_SPEED = "\ue022"
    private const val G_REGEN = "\ue011"
    private const val G_DEFENSE = "\ue008"
    private const val G_DAMAGE = "\ue050"

    // The types of the puzzle-reward flow (the timed level V lines): Power 31 percent, Wisdom 27, Life 26, Stone 16.
    private val WEIGHTS = listOf(Blessing.POWER to 31, Blessing.WISDOM to 27, Blessing.LIFE to 26, Blessing.STONE to 16)

    /** The type of a puzzle-reward blessing. */
    fun pick(random: Random): Blessing = draw(WEIGHTS, random, Blessing.POWER)

    /** The type of a secret chest's blessing. */
    fun pickChest(random: Random): Blessing = draw(CHEST_WEIGHTS, random, Blessing.LIFE)

    /**
     * A blessing a dying mob drops (ADR, 1,178 pickups in 250 runs, 4.7 a run, every one level V with the run clock): Power 34 percent,
     * Life 31, Wisdom 21, Stone 13. 61 percent print `A Blessing of X was picked up!` and `A Blessing of X V was found! (Ns)` with no name;
     * the rest `<rank> <name> has obtained Blessing of X!` and the finder's `You found` / `<name> found a Blessing of X V! (Ns)`.
     */
    private val KILL_WEIGHTS = listOf(Blessing.POWER to 402, Blessing.LIFE to 370, Blessing.WISDOM to 252, Blessing.STONE to 154)
    const val KILL_PICKED_UP_SHARE = 0.61

    fun pickKill(random: Random): Blessing = draw(KILL_WEIGHTS, random, Blessing.POWER)

    /** The level a secret chest's blessing shows (I, II or V); Time is always V. */
    fun chestLevel(b: Blessing, random: Random): Int {
        var n = random.nextInt(CHEST_LEVEL_WEIGHTS.sumOf { it.second })
        for ((l, w) in CHEST_LEVEL_WEIGHTS) { n -= w; if (n < 0) return levelFor(b, l) }
        return levelFor(b, 5)
    }

    private fun <T> draw(weights: List<Pair<T, Int>>, random: Random, fallback: T): T {
        var n = random.nextInt(weights.sumOf { it.second })
        for ((b, w) in weights) { n -= w; if (n < 0) return b }
        return fallback
    }

    /** Time exists at level V only. */
    fun levelFor(b: Blessing, wanted: Int): Int = if (b == Blessing.TIME) 5 else wanted.coerceIn(1, 5)

    fun roman(level: Int): String = when (level) { 1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"; else -> level.toString() }

    /** `24s` below a minute, `01m 16s` after (the run clock, as the line prints it). */
    fun clock(ticks: Int): String {
        val s = (ticks / 20).coerceAtLeast(0)
        return if (s < 60) "${s}s" else "%02dm %02ds".format(s / 60, s % 60)
    }

    /** A teammate's find shows at level I, II or V by [OBSERVER_LEVEL_WEIGHTS]. */
    fun observerLevel(random: Random): Int {
        var n = random.nextInt(OBSERVER_LEVEL_WEIGHTS.sumOf { it.second })
        for ((l, w) in OBSERVER_LEVEL_WEIGHTS) { n -= w; if (n < 0) return l }
        return 5
    }

    /** Whether a teammate's find of level [level] carries the run clock. Only some V lines do. */
    fun observerTimed(random: Random, level: Int): Boolean = level == 5 && random.nextDouble() < OBSERVER_TIMED_SHARE_OF_V

    private fun label(b: Blessing, level: Int) = "§r§dBlessing of ${b.displayName} ${roman(level)}§r§f"

    /** `A Blessing of Power was picked up!`: the line for a blessing taken off the floor (the sim's secrets give blessings from chests, so only [SecretsModule] callers with a floor item use it). */
    fun pickedUp(b: Blessing) = "§eA §r§dBlessing of ${b.displayName}§r§e was picked up!"

    /** The line a chest or drop prints for everyone: `<who> has obtained Blessing of Wisdom!`. [who] is the already-coloured display name. */
    fun obtained(who: String, b: Blessing) = "$who §r§ehas obtained §r§dBlessing of ${b.displayName}§r§e!"

    /** The headline for the player who found it, with the run clock: `DUNGEON BUFF! A Blessing of Power V was found! (30s)`. */
    fun announceOwn(b: Blessing, level: Int, ticks: Int) = "§6§lDUNGEON BUFF! §r§fA ${label(b, level)} was found! (§r§a${clock(ticks)}§r§f)"

    /** The same find after a chest drop prints it: `You found a Blessing of Life II!`, with the clock only when [ticks] is given (V). */
    fun announceYouFound(b: Blessing, level: Int, ticks: Int?) =
        "§6§lDUNGEON BUFF! §r§fYou found a ${label(b, level)}!" + (ticks?.let { " (§r§a${clock(it)}§r§f)" } ?: "")

    /** The line a teammate sees. [rankColour] is the colour of the finder's name; [ticks] adds the run clock to some V lines. */
    fun announceOther(name: String, b: Blessing, level: Int, rankColour: String = "§b", ticks: Int? = null) =
        "§6§lDUNGEON BUFF! §r$rankColour$name §r§ffound a ${label(b, level)}!" + (ticks?.let { " (§r§a${clock(it)}§r§f)" } ?: "")

    /** A number as the grant lines print it: one decimal at most, no trailing `.0`. */
    private fun fmt(v: Double): String = "%.1f".format(v).removeSuffix(".0")
    private fun mult(m: Double) = "%.2f".format(m)

    /** One `+flat & +multx <tail>` term. */
    private fun term(level: Int, tail: String, boost: Double): String =
        "§r§a+${fmt(FLAT_PER_LEVEL * level * boost)}§r§7 & §r§a+${mult(1.0 + MULT_PER_LEVEL * level * boost)}x $tail"

    /**
     * The "Granted you ..." lines that follow the headline, indented five spaces as Hypixel prints them. Power is two lines, the others one.
     * [boost] is 1.0, or [BOOST] when the date-wide boost setting is on.
     */
    fun grantLines(b: Blessing, level: Int, boost: Double = 1.0): List<String> {
        val pre = "     §7Granted you "
        return when (b) {
            Blessing.POWER -> listOf(
                pre + term(level, "§r§c$G_STRENGTH Strength", boost) + "§r§7.",
                "     §7Also granted you " + term(level, "§r§9$G_CRIT_DAMAGE Crit Damage", boost) + "§r§7.",
            )
            Blessing.LIFE -> {
                val m = mult(1.0 + LIFE_MULT_PER_LEVEL * level * boost)
                listOf(pre + "§r§a+${m}x HP §r§7and §r§a+${m}x §r§c$G_REGEN Health Regen§r§7.")
            }
            Blessing.WISDOM -> listOf(pre + term(level, "§r§b$G_INTELLIGENCE Intelligence", boost) + " §r§7and §r§a+${fmt(WISDOM_SPEED_PER_LEVEL * level * boost)} §r§f$G_SPEED Speed§r§7.")
            Blessing.STONE -> listOf(pre + term(level, "$G_DEFENSE Defense", boost) + " §r§7and §r§a+${fmt(STONE_DAMAGE_PER_LEVEL * level * boost)} §r§c$G_DAMAGE Damage§r§7.")
            Blessing.TIME -> listOf(
                pre + term(level, "HP", boost) + "§r§7, " + term(level, "$G_DEFENSE Defense", boost) + "§r§7, " +
                    term(level, "§r§b$G_INTELLIGENCE Intelligence", boost) + "§r§7, and " + term(level, "§r§c$G_STRENGTH Strength", boost) + "§r§7.",
            )
        }
    }

    /** The multiplier a stat of the blessing's kind is scaled by at [level] (1.0 + 3% per level; Life 4.36%). For the damage/stat layer. */
    fun multiplier(b: Blessing, level: Int): Double = when (b) {
        Blessing.LIFE -> 1.0 + LIFE_MULT_PER_LEVEL * level
        else -> 1.0 + MULT_PER_LEVEL * level
    }
}
