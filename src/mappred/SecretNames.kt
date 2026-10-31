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
    /** [solve]'s answers by pools; several threads of one session ([PredictionSession]) share them. */
    private class Box(val result: Result?)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Box>()
    private val maxT = targets.maxOrNull() ?: -1

    /**
     * [likelihood]: the chance names drawn from the pools hit a target (0: this set of rooms can't be the
     * map). [byPool]: for a room of each pool, each free name's chance (in [avail] order).
     */
    class Result(val likelihood: Double, val byPool: Map<Int, DoubleArray>)

    /**
     * For unnamed rooms of these [pools], any number of them: worked out exactly, pool by pool, by the secrets each
     * set of free names adds up to (rooms of one pool take different names), then the pools together.
     */
    fun solve(pools: IntArray): Result? {
        if (targets.isEmpty()) return null
        val sorted = pools.sortedArray()
        val key = sorted.joinToString(",")
        cache[key]?.let { return it.result }
        val res = count(sorted)
        return cache.getOrPut(key) { Box(res) }.result
    }

    /**
     * Each name drawn as often as it comes up in its pool, the rooms in turn, never one name twice: a pool's [k] rooms
     * take an ordered set of k names, weighed by their product; its weight by secrets is k! times the sum, over the
     * sets of k names adding up to those secrets, of their products ([sets]).
     */
    private fun count(pools: IntArray): Result {
        if (pools.isEmpty()) return Result(if (0 in targets) 1.0 else 0.0, emptyMap())
        val byPool = pools.toList().groupingBy { it }.eachCount()
        val ps = byPool.keys.toIntArray()
        val top = maxOf(maxT, 0) + 1
        val w = Array(ps.size) { j -> DoubleArray(avail[ps[j]].size) { avail[ps[j]][it].seen / poolWeight[ps[j]] } }
        val sec = Array(ps.size) { j -> IntArray(avail[ps[j]].size) { avail[ps[j]][it].secrets } }
        val fact = DoubleArray(ps.size) { j -> (1..byPool.getValue(ps[j])).fold(1.0) { a, b -> a * b } }
        // each pool by its secrets, and every pool but one together (past the highest target nothing counts)
        val f = Array(ps.size) { j -> sets(w[j], sec[j], byPool.getValue(ps[j]), -1, top).also { a -> for (s in a.indices) a[s] *= fact[j] } }
        val rest = Array(ps.size) { j -> var a = DoubleArray(top).also { it[0] = 1.0 }; for (o in ps.indices) if (o != j) a = conv(a, f[o], top); a }
        val all = conv(rest[0], f[0], top)
        val total = targets.sumOf { if (it in 0 until top) all[it] else 0.0 }
        val out = HashMap<Int, DoubleArray>()
        for (j in ps.indices) {
            val k = byPool.getValue(ps[j])
            out[ps[j]] = DoubleArray(w[j].size) { i ->
                if (total <= 0) return@DoubleArray 0.0
                // the sets with this name in: it, and k - 1 others; a room of the pool has it a k-th of those
                val with = sets(w[j], sec[j], k - 1, i, top)
                var sum = 0.0
                for (t in targets) for (s in 0 until top) {
                    val o = t - s - sec[j][i]
                    if (with[s] > 0 && o in 0 until top) sum += with[s] * rest[j][o]
                }
                sum * w[j][i] * fact[j] / k / total
            }
        }
        return Result(total, out)
    }

    /** For each secret count below [top], the sum over sets of [k] names (but name [skip]) adding up to it of their weights' product. */
    private fun sets(w: DoubleArray, sec: IntArray, k: Int, skip: Int, top: Int): DoubleArray {
        val e = Array(k + 1) { DoubleArray(top) }
        e[0][0] = 1.0
        for (i in w.indices) {
            if (i == skip) continue
            for (j in minOf(k, i + 1) downTo 1) {
                val from = e[j - 1]; val to = e[j]
                for (s in top - 1 downTo sec[i]) if (from[s - sec[i]] != 0.0) to[s] += w[i] * from[s - sec[i]]
            }
        }
        return e[k]
    }

    private fun conv(a: DoubleArray, b: DoubleArray, top: Int): DoubleArray {
        val o = DoubleArray(top)
        for (x in a.indices) if (a[x] != 0.0) for (y in 0 until top - x) if (b[y] != 0.0) o[x + y] += a[x] * b[y]
        return o
    }

    companion object {
        /**
         * Unnamed rooms the samplers weigh each sample by at most (the last few rooms, where it matters; every arrangement
         * of a sample's specials is solved, so more costs a lot of time). [MapPredictor.certainNames] has no such limit.
         */
        const val MAX_ROOMS = 4

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
