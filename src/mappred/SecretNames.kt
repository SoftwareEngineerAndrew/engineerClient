package com.engineerclient.mappred

/**
 * Names for the rooms nobody has named yet, from the dungeon's secret total: every name comes up at most
 * once a map ([MapStats.NAMES]), so the unnamed rooms take different names from their pools, and their
 * secrets have to add up to what the named rooms leave of the total ([targets]: each total the tab list
 * allows, less the named rooms' secrets). [missing]: names the floor never has ([FloorRules.missingNames]).
 */
class SecretNames(used: Set<String>, private val targets: IntArray, missing: Set<String> = emptySet()) {
    /** Names still free in each pool. */
    val avail: Array<List<RoomName>> = Array(MapStats.POOLS) { p -> MapStats.NAMES.filter { it.pool == p && it.name !in used && it.name !in missing } }
    private val poolWeight = DoubleArray(MapStats.POOLS) { p -> avail[p].sumOf { it.seen.toDouble() } }
    private val minSec = IntArray(MapStats.POOLS) { p -> avail[p].minOfOrNull { it.secrets } ?: 0 }
    private val maxSec = IntArray(MapStats.POOLS) { p -> avail[p].maxOfOrNull { it.secrets } ?: 0 }
    /** [solve]'s answers by pools; several threads of one session ([PredictionSession]) share them. */
    private class Box(val result: Result?)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Box>()
    private val minT = targets.minOrNull() ?: 0
    private val maxT = targets.maxOrNull() ?: -1

    /**
     * [likelihood]: the chance names drawn from the pools hit a target (0: this set of rooms can't be the
     * map). [byPool]: for a room of each pool, each free name's chance (in [avail] order).
     */
    class Result(val likelihood: Double, val byPool: Map<Int, DoubleArray>)

    /** For unnamed rooms of these [pools]; null when there are too many to work out exactly. */
    fun solve(pools: IntArray): Result? {
        if (pools.size > MAX_ROOMS || targets.isEmpty()) return null
        val sorted = pools.sortedArray()
        val key = sorted.joinToString(",")
        cache[key]?.let { return it.result }
        val res = try { enumerate(sorted) } catch (_: TooMany) { null }
        return cache.getOrPut(key) { Box(res) }.result
    }

    private object TooMany : RuntimeException() { private fun readResolve(): Any = TooMany }

    private fun enumerate(pools: IntArray): Result {
        val m = pools.size
        val minRest = IntArray(m + 1); val maxRest = IntArray(m + 1)
        for (i in m - 1 downTo 0) { minRest[i] = minRest[i + 1] + minSec[pools[i]]; maxRest[i] = maxRest[i + 1] + maxSec[pools[i]] }
        val pick = IntArray(m)
        val marg = Array(m) { DoubleArray(avail[pools[it]].size) }
        var total = 0.0
        var steps = 0
        fun dfs(i: Int, sum: Int, w: Double) {
            if (++steps > BUDGET) throw TooMany
            if (i == m) {
                if (sum in targets) { total += w; for (j in 0 until m) marg[j][pick[j]] += w }
                return
            }
            val p = pools[i]
            val names = avail[p]
            for (k in names.indices) {
                var taken = false
                for (j in 0 until i) if (pools[j] == p && pick[j] == k) { taken = true; break }
                if (taken) continue
                val s = sum + names[k].secrets
                if (s + minRest[i + 1] > maxT || s + maxRest[i + 1] < minT) continue
                pick[i] = k
                dfs(i + 1, s, w * names[k].seen / poolWeight[p])
            }
        }
        if (m == 0) return Result(if (0 in targets) 1.0 else 0.0, emptyMap())
        dfs(0, 0, 1.0)
        val byPool = HashMap<Int, DoubleArray>()
        for (j in 0 until m) if (pools[j] !in byPool) byPool[pools[j]] = DoubleArray(marg[j].size) { if (total > 0) marg[j][it] / total else 0.0 }
        return Result(total, byPool)
    }

    companion object {
        /** Unnamed rooms worked out exactly at most (the last few rooms, where it matters). */
        const val MAX_ROOMS = 4
        private const val BUDGET = 3_000_000

        /**
         * Totals the tab list allows: [found] secrets shown as [percent]% (rounded, to 1 or 2 places).
         * Empty when nothing is found yet.
         */
        fun totals(found: Int, percent: Float): IntArray {
            if (found <= 0 || percent <= 0f) return IntArray(0)
            if (percent >= 100f) return intArrayOf(found)
            val lo = kotlin.math.ceil(100.0 * found / (percent + 0.05)).toInt()
            val hi = kotlin.math.floor(100.0 * found / maxOf(percent - 0.05, 0.001)).toInt()
            if (hi < lo || hi - lo > 40) return IntArray(0)
            return (maxOf(lo, found)..hi).toList().toIntArray()
        }
    }
}
