package com.engineerclient.p3sim.clear.puzzles

import kotlin.random.Random

/**
 * Higher Or Lower (the Blaze puzzle) as a state machine. Ten blazes with different health; they must be killed in order of
 * health: the "Higher" room from the lowest up, the "Lower" room from the highest down (Odin's `BlazeSolver` sorts the same
 * way, by the number after the slash on each blaze's name). A kill out of order fails the puzzle; the last kill solves it.
 * The health values follow the 17 complete sets recorded on Hypixel (see [drawHps]); the count of ten was the same in every one.
 * Pure: no Minecraft.
 */
class BlazeLogic(val hps: List<Int>, val ascending: Boolean) {
    private val alive = hps.toMutableList()
    var failed = false
        private set

    /** The health of the blaze to kill next, or null when none is left. */
    val next: Int? get() = if (alive.isEmpty()) null else if (ascending) alive.min() else alive.max()
    val solved: Boolean get() = hps.isNotEmpty() && alive.isEmpty() && !failed
    val remaining: Int get() = alive.size

    /** A blaze with [hp] died. True when it was the right one; false fails the puzzle. */
    fun kill(hp: Int): Boolean {
        if (failed || hp !in alive) return !failed
        if (hp != next) { failed = true; alive.remove(hp); return false }
        alive.remove(hp)
        return true
    }

    fun reset() { alive.clear(); alive.addAll(hps); failed = false }

    companion object {
        const val COUNT = 10

        /** The lowest health of a set is between these (recorded sets: 1,002 to 1,993). */
        const val LOWEST_MIN = 1000
        const val LOWEST_MAX = 2000

        /** Each next health is this much above the one before, at least 1 and at most this (recorded steps: 1 to 500, mean about 225). */
        const val STEP_MAX = 450

        /** The level in the name tag (Odin accepts any digits; 16 of 22 recorded rooms say 11, the rest 15). */
        const val LEVEL = 11

        /**
         * Ten distinct health values like the real room's: a lowest one between [LOWEST_MIN] and [LOWEST_MAX], then each next one a random
         * 1 to [STEP_MAX] above the one before (sets span 1,500 to 3,000 and are never round numbers). Returned in random order.
         */
        fun drawHps(rnd: Random, count: Int = COUNT): List<Int> {
            var hp = rnd.nextInt(LOWEST_MIN, LOWEST_MAX + 1)
            val out = ArrayList<Int>(count)
            repeat(count) { out += hp; hp += rnd.nextInt(1, STEP_MAX + 1) }
            return out.shuffled(rnd)
        }

        /**
         * A replay's set: the HP of the recorded blaze (x, y, z, HP of each body) standing on each of [cells] (world x, y, z), or null unless
         * every cell has one and they all differ.
         */
        fun recordedHps(cells: List<IntArray>, rec: List<DoubleArray>): List<Int>? {
            val out = cells.map { c -> rec.firstOrNull { Math.abs(it[0] - c[0]) < 0.5 && Math.abs(it[1] - c[1]) < 0.5 && Math.abs(it[2] - c[2]) < 0.5 }?.get(3)?.toInt() ?: return null }
            return out.takeIf { it.toSet().size == it.size }
        }

        /**
         * The text of the name tag Odin reads from a blaze's armor stand: `[Lv11]` + the blaze icon glyph + ` Blaze 3,261/3,261❤` (one space each side
         * of the glyph, commas in thousands), with Hypixel's colours. The glyph U+E07C is a private-use character that Hypixel's resource pack draws
         * as the blaze icon; Odin's regex needs it.
         */
        fun nameOf(hp: Int): String {
            val s = String.format(java.util.Locale.ROOT, "%,d", hp)
            return "§8[§7Lv$LEVEL§8] §4$ICON §cBlaze §a$s§f/§a$s§c❤"
        }

        /** The blaze icon in the name tag. */
        const val ICON = ""
    }
}

/**
 * Where the ten blazes of Higher Or Lower hover and what the room's rail chest does, in the room's own frame (x and z from the room's
 * corner whatever way the room is turned, y absolute). Measured on Hypixel: 78 of 91 recorded rooms and every full set in the recorder
 * sessions have the same shape, a spiral of cells around the centre pillar. Pure: no Minecraft.
 */
object BlazeLayout {
    /** The centre pillar's column. */
    const val CENTRE = 15

    /** Blaze `k` (0 to 9, the lowest first) hangs 4 blocks above blaze `k - 1`. */
    const val SPACING = 4

    /** Height of the lowest blaze: Lower Blaze 27, Higher Blaze 77. */
    fun firstY(higher: Boolean) = if (higher) 77 else 27

    /** The column offsets (dx, dz) from the pillar by `k mod 4`, a quarter turn at a time, 2 blocks out. */
    private val RING = listOf(0 to -2, -2 to 0, 0 to 2, 2 to 0)

    class Cell(val x: Int, val y: Int, val z: Int)

    /** The cell of blaze [k] (0 to 9): the blaze stands in this cell's corner, its name tag 2 blocks above. */
    fun cell(k: Int, higher: Boolean): Cell {
        val (dx, dz) = RING[k % 4]
        return Cell(CENTRE + dx, firstY(higher) + SPACING * k, CENTRE + dz)
    }

    /** How far above a blaze its name tag stands. */
    const val TAG_HEIGHT = 2.0
}

/**
 * The chest on the centre pillar of Higher Or Lower. From the first correct kill it moves one block every [STEP_TICKS] ticks for [STEPS]
 * moves (Lower Blaze down from 69 to 20, Higher Blaze up from 70 to 119) and the room turns green [GREEN_AFTER_END] ticks after it stops.
 * Lower leaves a bar behind it in every cell it leaves; Higher eats a column of bars that stands above it, so the cell it leaves is air.
 * Pure: no Minecraft.
 */
class BlazeRail(val higher: Boolean) {
    /** Where the chest stands before the first kill: Lower 69, Higher 70. */
    val startY: Int get() = if (higher) 70 else 69

    private val dir: Int get() = if (higher) 1 else -1

    /** The chest's height after [step] moves (0 to [STEPS]). */
    fun yAt(step: Int): Int = startY + dir * step.coerceIn(0, STEPS)

    /** Where the chest ends: Lower 20, Higher 119. */
    val endY: Int get() = yAt(STEPS)

    /** The tick of move [step] (0-based; the first move is on the tick of the first kill). */
    fun moveTick(firstKill: Int, step: Int): Int = firstKill + STEP_TICKS * step

    /** The tick the chest stops (the last move). */
    fun endTick(firstKill: Int): Int = moveTick(firstKill, STEPS - 1)

    /** The tick the room's check mark turns at the earliest. */
    fun greenTick(firstKill: Int): Int = endTick(firstKill) + GREEN_AFTER_END

    /** What a rail cell holds. */
    enum class Cell { CHEST, BARS, AIR }

    /** The heights the chest can stand at, lowest first. */
    val cells: IntRange get() = minOf(startY, endY)..maxOf(startY, endY)

    /**
     * What the rail cell at height [y] holds when the chest has made [step] moves. The cell the chest stands in holds it; a cell it has
     * left holds a bar (Lower) or air (Higher); a cell it has yet to reach holds air (Lower) or a bar (Higher).
     */
    fun cellAt(step: Int, y: Int): Cell {
        val at = yAt(step)
        if (y == at) return Cell.CHEST
        val behind = if (higher) y < at else y > at
        return if (behind != higher) Cell.BARS else Cell.AIR
    }

    companion object {
        const val STEP_TICKS = 5
        const val STEPS = 49

        /** Measured: the room turns green 244 ticks after the first kill, 4 after the last move. */
        const val GREEN_AFTER_END = 4
    }
}
