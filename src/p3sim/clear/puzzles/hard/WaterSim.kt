package com.engineerclient.p3sim.clear.puzzles.hard

/**
 * Water as the 1.8 server flows it, which is what Hypixel's Water Board is built on. A small box of cells; every cell is
 * solid, empty, or water of one of the 16 legacy levels: 0 is a source, 1 to 7 is water that has spread that many blocks
 * sideways, 8 is water falling, and 8 plus a spread level is water that fell from a sideways flow (the Water Board's recordings
 * show exactly these numbers, 9 to 15).
 *
 * The rules are `BlockDynamicLiquid.updateTick` of 1.8: a flowing cell takes its level from the lowest neighbour plus one (or
 * becomes falling water when water is above it), falls into the cell below when that is free, otherwise spreads to the
 * sides, and prefers sides that lead to a drop within four blocks. Every update comes five ticks after the change that asked
 * for it. Modern water differs in its details, which is why this class exists: the Water Board's lever times were measured on the
 * old rules, and the time water takes to reach a channel's end is what the puzzle is about.
 *
 * Pure: no Minecraft. Cells outside the box count as solid. Server thread only (the caller's).
 */
class WaterSim(val x0: Int, val y0: Int, val z0: Int, val w: Int, val h: Int, val d: Int) {
    private val solid = BooleanArray(w * h * d)
    private val level = ByteArray(w * h * d) { -1 }
    private val scheduled = BooleanArray(w * h * d)
    private val buckets = java.util.TreeMap<Int, ArrayList<Int>>()

    /** Ticks this simulation has run. */
    var tickCount: Int = 0
        private set

    /** One change of a water cell: its new level, or -1 when the water is gone. */
    class Change(val x: Int, val y: Int, val z: Int, val level: Int)

    private val changes = ArrayList<Change>()

    private fun idx(x: Int, y: Int, z: Int): Int = ((y - y0) * d + (z - z0)) * w + (x - x0)
    private fun inside(x: Int, y: Int, z: Int) = x >= x0 && x < x0 + w && y >= y0 && y < y0 + h && z >= z0 && z < z0 + d

    fun isSolid(x: Int, y: Int, z: Int): Boolean = !inside(x, y, z) || solid[idx(x, y, z)]

    /** The water level of a cell (0 to 15), or -1 when it holds no water. */
    fun water(x: Int, y: Int, z: Int): Int = if (inside(x, y, z)) level[idx(x, y, z)].toInt() else -1

    /** Water that is already there when the board starts: it does not flow until something next to it changes. */
    fun setStaticWater(x: Int, y: Int, z: Int, lvl: Int) {
        if (!inside(x, y, z)) return
        val i = idx(x, y, z)
        solid[i] = false
        level[i] = lvl.toByte()
    }

    /** Sets a cell solid or free. A solid cell drowns the water in it without a change record; a freed cell wakes the water around it. */
    fun setSolid(x: Int, y: Int, z: Int, isSolid: Boolean) {
        if (!inside(x, y, z)) return
        val i = idx(x, y, z)
        solid[i] = isSolid
        if (isSolid) level[i] = -1
        notifyAround(x, y, z)
    }

    /** Puts a source block in a free cell (the Water Board's intake). */
    fun placeSource(x: Int, y: Int, z: Int) {
        if (!inside(x, y, z) || solid[idx(x, y, z)]) return
        setWater(x, y, z, 0)
    }

    private fun setWater(x: Int, y: Int, z: Int, lvl: Int) {
        val i = idx(x, y, z)
        level[i] = lvl.toByte()
        changes += Change(x, y, z, lvl)
        schedule(x, y, z)
        notifyAround(x, y, z)
    }

    private fun clearWater(x: Int, y: Int, z: Int) {
        val i = idx(x, y, z)
        level[i] = -1
        changes += Change(x, y, z, -1)
        notifyAround(x, y, z)
    }

    private fun schedule(x: Int, y: Int, z: Int) {
        if (!inside(x, y, z)) return
        val i = idx(x, y, z)
        if (scheduled[i]) return
        scheduled[i] = true
        buckets.getOrPut(tickCount + TICK_RATE) { ArrayList() }.add(i)
    }

    private fun notifyAround(x: Int, y: Int, z: Int) {
        for (k in NEIGHBOURS) {
            val nx = x + k[0]; val ny = y + k[1]; val nz = z + k[2]
            if (inside(nx, ny, nz) && level[idx(nx, ny, nz)] >= 0) schedule(nx, ny, nz)
        }
    }

    /** Runs one tick and returns the water changes it made. */
    fun tick(): List<Change> {
        advance()
        return runDue()
    }

    /** Starts the next tick without running its updates, so the caller can make its own changes at the start of the tick first (they are timed from it). */
    fun advance() { tickCount++ }

    /** Runs the updates due in the current tick and returns the changes since the last call. */
    fun runDue(): List<Change> {
        val due = buckets.remove(tickCount)
        if (due != null) {
            for (i in due) scheduled[i] = false
            for (i in due) {
                val x = x0 + i % w; val z = z0 + (i / w) % d; val y = y0 + i / (w * d)
                update(x, y, z)
            }
        }
        return takeChanges()
    }

    /** The changes made since the last call, oldest first. */
    fun takeChanges(): List<Change> {
        if (changes.isEmpty()) return emptyList()
        val out = ArrayList(changes)
        changes.clear()
        return out
    }

    private fun blocked(x: Int, y: Int, z: Int) = isSolid(x, y, z)

    private fun update(x: Int, y: Int, z: Int) {
        if (!inside(x, y, z)) return
        var i = level[idx(x, y, z)].toInt()
        if (i < 0) return
        if (i > 0) {
            var low = -100
            var sources = 0
            for (k in SIDES) {
                var dd = water(x + k[0], y, z + k[1])
                if (dd < 0) continue
                if (dd == 0) sources++
                if (dd >= 8) dd = 0
                if (low < 0 || dd < low) low = dd
            }
            var next = low + 1
            if (next >= 8 || low < 0) next = -1
            val up = water(x, y + 1, z)
            if (up >= 0) next = if (up >= 8) up else up + 8
            // Two sources beside water over a floor make a new source (the old infinite-water rule).
            if (sources >= 2 && (blocked(x, y - 1, z) || water(x, y - 1, z) == 0)) next = 0
            if (next != i) {
                i = next
                if (next < 0) clearWater(x, y, z) else setWater(x, y, z, next)
            }
        }
        // A cell that has just dried up still pushes one last block of level 7 into a free cell below it: a 1.8 quirk the board's timings include.
        if (canFlowInto(x, y - 1, z)) {
            setWater(x, y - 1, z, if (i >= 8) i else i + 8)
        } else if (i >= 0 && (i == 0 || blocked(x, y - 1, z))) {
            var reach = i + 1
            if (i >= 8) reach = 1
            if (reach >= 8) return
            for (q in flowDirections(x, y, z)) {
                val nx = x + SIDES[q][0]; val nz = z + SIDES[q][1]
                if (canFlowInto(nx, y, nz)) setWater(nx, y, nz, reach)
            }
        }
    }

    /** Free of both solid and water: the only cells new water is put into. */
    private fun canFlowInto(x: Int, y: Int, z: Int) = inside(x, y, z) && !solid[idx(x, y, z)] && level[idx(x, y, z)] < 0

    /** Which sides water spreads to: those with the shortest way down within four blocks, or all open sides when there is no way down. */
    private fun flowDirections(x: Int, y: Int, z: Int): List<Int> {
        var best = 1000
        val out = ArrayList<Int>(4)
        for (q in 0 until 4) {
            val nx = x + SIDES[q][0]; val nz = z + SIDES[q][1]
            if (blocked(nx, y, nz) || water(nx, y, nz) == 0) continue
            val cost = if (blocked(nx, y - 1, nz)) flowCost(nx, y, nz, 1, q xor 1) else 0
            if (cost < best) out.clear()
            if (cost <= best) { out += q; best = cost }
        }
        return out
    }

    private fun flowCost(x: Int, y: Int, z: Int, depth: Int, from: Int): Int {
        var best = 1000
        for (q in 0 until 4) {
            if (q == from) continue
            val nx = x + SIDES[q][0]; val nz = z + SIDES[q][1]
            if (blocked(nx, y, nz) || water(nx, y, nz) == 0) continue
            if (!blocked(nx, y - 1, nz)) return depth
            if (depth < 4) {
                val c = flowCost(nx, y, nz, depth + 1, q xor 1)
                if (c < best) best = c
            }
        }
        return best
    }

    /** Every water cell and every pending update, for a savepoint: `x,y,z,level` quadruples and `index,ticksLeft` pairs. */
    fun snapshot(): Pair<IntArray, IntArray> {
        val cells = ArrayList<Int>()
        for (i in level.indices) if (level[i] >= 0) {
            cells += x0 + i % w; cells += y0 + i / (w * d); cells += z0 + (i / w) % d; cells += level[i].toInt()
        }
        val pend = ArrayList<Int>()
        for ((t, list) in buckets) for (i in list) { pend += i; pend += t - tickCount }
        return cells.toIntArray() to pend.toIntArray()
    }

    /** Restores what [snapshot] returned over the current solids (water cells and updates replaced, no changes recorded). */
    fun restore(cells: IntArray, pending: IntArray) {
        java.util.Arrays.fill(level, (-1).toByte())
        java.util.Arrays.fill(scheduled, false)
        buckets.clear()
        changes.clear()
        var k = 0
        while (k + 3 < cells.size) {
            if (inside(cells[k], cells[k + 1], cells[k + 2])) level[idx(cells[k], cells[k + 1], cells[k + 2])] = cells[k + 3].toByte()
            k += 4
        }
        k = 0
        while (k + 1 < pending.size) {
            val i = pending[k]
            if (i in solid.indices && !scheduled[i]) {
                scheduled[i] = true
                buckets.getOrPut(tickCount + pending[k + 1].coerceAtLeast(1)) { ArrayList() }.add(i)
            }
            k += 2
        }
    }

    companion object {
        /** Ticks between a change and the update it causes (water's tick rate). */
        const val TICK_RATE = 5

        private val SIDES = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        private val NEIGHBOURS = arrayOf(
            intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(0, -1, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
        )
    }
}
