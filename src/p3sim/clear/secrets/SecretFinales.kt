package com.engineerclient.p3sim.clear.secrets

import kotlin.random.Random

/*
 * The timed parts of finding a secret, as plain data: what plays when after a chest or a skull is clicked, the pitches Hypixel
 * sends, and the small draws of a run (which bat carries the bonus point, which chest is the Mimic). Pure: no Minecraft types, so
 * the whole of it is unit-tested headless; the world side is [SecretsModule].
 */

/** The sound numbers of the secrets. Hypixel's pitches are all multiples of 1/63 (the old protocol sent them as one byte). */
object SecretSounds {
    private const val STEPS = 63f

    /** A pitch as Hypixel sends it: rounded down to a multiple of 1/63. */
    fun quantise(pitch: Float): Float = Math.floor((pitch * STEPS).toDouble()).toFloat() / STEPS

    /** A chest's opening sound: 56/63 to 62/63, each equally often. */
    fun chestOpen(random: Random): Float = (56 + random.nextInt(7)) / STEPS

    /** The five notes of a reveal, low to high (50, 56, 63, 69 and 75 sixty-thirds). */
    val HARP = floatArrayOf(50f / 63f, 56f / 63f, 63f / 63f, 69f / 63f, 75f / 63f)

    /** The anvil sound a lever makes when it is pulled or refused (107/63). */
    const val ANVIL_BREAK_PITCH = 107f / 63f
    /** The click a few levers make in their own block (37/63), quietly. */
    const val LEVER_CLICK_PITCH = 37f / 63f
    const val LEVER_CLICK_VOLUME = 0.3f
    /** The ticking of a wall that is opening (31/63). */
    const val WALL_TICK_PITCH = 31f / 63f
    /** The pling at the start of a wither essence skull (255/63), loud, only the finder hears it. */
    const val SKULL_PLING_PITCH = 255f / 63f
    const val SKULL_PLING_VOLUME = 8f
    /** The orb ping of every essence line (94/63). */
    const val ORB_PITCH = 94f / 63f
    /** The refusal of a locked chest or a used lever of the boss: an enderman teleport at volume 8 and pitch 0. */
    const val DENIED_VOLUME = 8f

    /** The pitch of a picked-up floor item: `(r1 - r2) * 1.4 + 2.0`, seen from 0.95 to 2.75. */
    fun pickup(random: Random): Float = (random.nextFloat() - random.nextFloat()) * 1.4f + 2.0f

    /** The extra ping when the bonus bat falls: volume 0.5 and a pitch spread from 1.70 to 2.68. */
    fun bonusPing(random: Random): Float = 1.70f + random.nextFloat() * 0.98f

    /** A bat's death sound: vanilla's `(r1 - r2) * 0.2 + 1` scaled by the bat's 0.95 voice, on Hypixel's 1/63 steps. */
    fun batDeath(random: Random): Float = quantise(((random.nextFloat() - random.nextFloat()) * 0.2f + 1f) * 0.95f)
}

/** A thing to do at a tick. [id] names it, so a savepoint restore or a find can drop what belongs to it. */
class Timed<T>(val due: Int, val what: T, val owner: String? = null)

/** Things to do on later ticks, in the order they fall due. Pure. */
class TimedQueue<T> {
    private val items = ArrayList<Timed<T>>()

    val size: Int get() = items.size
    fun isEmpty() = items.isEmpty()

    fun add(due: Int, what: T, owner: String? = null) { items += Timed(due, what, owner) }

    /** Removes and returns everything due at [now] or earlier, earliest first (equal ticks keep the order they were added). */
    fun takeDue(now: Int): List<T> {
        if (items.isEmpty()) return emptyList()
        val due = items.filter { it.due <= now }
        if (due.isEmpty()) return emptyList()
        items.removeAll { it.due <= now }
        return due.sortedBy { it.due }.map { it.what }
    }

    fun dropOwner(owner: String) { items.removeAll { it.owner == owner } }
    fun clear() = items.clear()
    fun owners(): Set<String> = items.mapNotNull { it.owner }.toSet()
}

/** Which beats of a schedule have come due: a beat plays once, the first tick its time has passed. Pure. */
object Beats {
    /** The indexes of [ats] (ticks after the start) that are due at [elapsed] and not in [done] yet; they are added to [done]. A negative [elapsed] (not started) gives none. */
    fun due(ats: List<Int>, elapsed: Int, done: MutableSet<Int>): List<Int> {
        if (elapsed < 0) return emptyList()
        val out = ArrayList<Int>()
        for ((i, at) in ats.withIndex()) if (at <= elapsed && done.add(i)) out += i
        return out
    }

    /** The beats that already played when a ceremony is picked up [elapsed] ticks in (a savepoint restore): everything at or before [elapsed]. */
    fun playedBy(ats: List<Int>, elapsed: Int): MutableSet<Int> = ats.indices.filterTo(HashSet()) { ats[it] <= elapsed }
}

/** What happens after a click on a secret chest, in server ticks after the click (the recorded offsets minus the one-tick network leg). */
object SecretChestFinale {
    enum class Step { CORNER_OPEN, LID_AND_CENTRE_OPEN, HARP, REVEAL }

    class Item(val at: Int, val step: Step, val note: Int = -1)

    /** Ticks from the click to the loot: the fifth note's tick. Repeat clicks in this window are silent. */
    const val REVEAL_AT = 24

    /**
     * A plain chest: the lid and one opening sound at the centre. A reward chest: a sound at the block's corner, then the lid and the
     * centre sound, then five harp notes (4, 9, 14, 19 and 24 ticks after the click), and the reveal on the last.
     */
    fun schedule(reward: Boolean): List<Item> = if (!reward) listOf(Item(1, Step.LID_AND_CENTRE_OPEN)) else buildList {
        add(Item(0, Step.CORNER_OPEN))
        add(Item(1, Step.LID_AND_CENTRE_OPEN))
        for (i in 0 until 5) add(Item(4 + 5 * i, Step.HARP, i))
        add(Item(REVEAL_AT, Step.REVEAL))
    }
}

/** What happens after the first click on a wither essence skull, in server ticks after the click: a pling, five notes and the reward on the last. */
object SkullFinale {
    enum class Step { PLING, HARP, REWARD }

    class Item(val at: Int, val step: Step, val note: Int = -1)

    /** Ticks from the click to the reward and the skull going. */
    const val REWARD_AT = 20

    fun schedule(withPling: Boolean): List<Item> = buildList {
        if (withPling) add(Item(1, Step.PLING))
        val at = intArrayOf(1, 5, 10, 15, 20)
        for (i in at.indices) add(Item(at[i], Step.HARP, i))
        add(Item(REWARD_AT, Step.REWARD))
    }

    /** The finder hears the orb ping twice, everyone else once. */
    fun orbPings(isFinder: Boolean): Int = if (isFinder) 2 else 1
}

/** The decisions and lines of a click on a secret, as plain functions (the world side only sends what they say). Pure. */
object SecretRules {
    enum class Repeat { SILENT, SEARCHED }

    /**
     * What a second click on an opened chest gets: nothing while a reward chest's harp rolls (121 of 121 clicks in that window printed nothing)
     * and on the Mimic's chest; "already searched" on a plain chest (76 percent of the clicks in its first 25 ticks) and after the reveal.
     */
    fun repeatClick(trapped: Boolean, reward: Boolean, finished: Boolean): Repeat = if (trapped || (reward && !finished)) Repeat.SILENT else Repeat.SEARCHED

    const val CHEST_SEARCHED = "§cThis chest has already been searched!"
    const val CHEST_LOCKED = "§cThat chest is locked!"
    const val LEVER_OPENING = "§cYou hear the sound of something opening..."
    const val LEVER_USED = "§cThis lever has already been used."
    const val KEY_FOUND = "§r§aYou found a Secret Redstone Key!"
    const val SOCKET_USED = "§7§oYou hear something open..."

    /**
     * The lines a pull of a secret lever prints for the puller: the opening line the first time, the "used" line after (whoever pulled it
     * first: the "someone has already activated" line belongs to the boss's terminal levers), and for a sneaking player the first pull
     * is followed in the same tick by the "used" line as well (the off-hand click is processed too).
     */
    fun leverLines(alreadyUsed: Boolean, sneaking: Boolean): List<String> =
        if (alreadyUsed) listOf(LEVER_USED) else if (sneaking) listOf(LEVER_OPENING, LEVER_USED) else listOf(LEVER_OPENING)
}

/** A locked chest's refusal: the line and the enderman sound answer a click once, then stay quiet while the right-click repeats. */
object LockedChest {
    /** The smallest gap between two refusals seen is 12 ticks (17 of 23 refusals were followed by a repeat click within 8 that got nothing). UNMEASURED as an upper bound. */
    const val QUIET_TICKS = 12

    fun answers(lastAnswered: Int?, now: Int): Boolean = lastAnswered == null || now - lastAnswered >= QUIET_TICKS
}

/** The ticks that make up a lever wall opening: nine clicks five ticks apart, from the pull. */
object LeverWall {
    val TICKS: IntArray = IntArray(9) { it * 5 }
    /** Rooms whose levers open without the ticking sound. */
    val SILENT_ROOMS: Set<String> = setOf("buttons", "old trap", "bridges")
    /** The share of pulls that also click in the lever block (14 of 89). */
    const val LEVER_CLICK_SHARE = 0.16

    fun ticks(roomName: String): Boolean = roomName.trim().lowercase() !in SILENT_ROOMS
}

/** The bonus bat: the run has its point on the Nth counted secret-bat kill. */
object BonusBat {
    /** 94 bonus lines: on the first counted kill 70 percent of the time, on the second 24, the third 5, the fourth 1. */
    private val WEIGHTS = listOf(1 to 70, 2 to 24, 3 to 5, 4 to 1)

    fun draw(random: Random): Int {
        var n = random.nextInt(WEIGHTS.sumOf { it.second })
        for ((i, w) in WEIGHTS) { n -= w; if (n < 0) return i }
        return 1
    }
}

/** Counts the secret bats that were counted (by anyone) and says when the bonus point is due: on the [index]th, or never when [index] is 0. */
class BonusBatTracker(var index: Int, var counted: Int = 0) {
    /** One more bat counted. True when this is the one that carries the point. */
    fun count(): Boolean { counted++; return index > 0 && counted == index }
}

/** The trapped chest (the Mimic's chest): one chest of the dungeon is trapped. */
object TrappedChest {
    /** The share of dungeons that have one when the pack says nothing (151 of 211 had it among the chunks loaded at the start). */
    const val DEFAULT_CHANCE = 0.72

    /** Ticks from the click to the zombie's death a teammate causes, by the recorded spread (4 to 11). */
    val KILL_DELAY = 4..11

    /** Picks the trapped chest of a dungeon from its candidates, or none with the stated [chance]. */
    fun pick(candidates: List<SecretSite>, chance: Double, random: Random): SecretSite? {
        if (candidates.isEmpty()) return null
        val u = random.nextDouble()
        val i = random.nextInt(candidates.size)
        return if (u < chance) candidates[i] else null
    }
}
