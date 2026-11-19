package com.engineerclient.mappred

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A trained network that predicts the unknown part of a map from what the map shows, in one pass. */
class RoomNet(input: InputStream) {
    private val d: Int
    private val layers: Int
    private val heads: Int
    private val ff: Int
    private val feat: Int
    private val w = HashMap<String, FloatArray>()

    init {
        val s = DataInputStream(input.buffered())
        val magic = ByteArray(4).also { s.readFully(it) }
        require(String(magic) == "MPN1") { "not a room net" }
        val hb = ByteBuffer.wrap(ByteArray(20).also { s.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
        d = hb.int; layers = hb.int; heads = hb.int; ff = hb.int; feat = hb.int
        fun read(name: String, n: Int) {
            val bb = ByteBuffer.wrap(ByteArray(n * 2).also { s.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
            w[name] = FloatArray(n) { java.lang.Float.float16ToFloat(bb.short) }
        }
        fun lin(name: String, nIn: Int, nOut: Int) { read("$name.w", nIn * nOut); read("$name.b", nOut) }
        lin("inp", feat, d); read("pos", N * d)
        for (i in 0 until layers) {
            read("b$i.n1.w", d); read("b$i.n1.b", d); lin("b$i.qkv", d, 3 * d); lin("b$i.proj", d, d)
            read("b$i.n2.w", d); read("b$i.n2.b", d); lin("b$i.f1", d, ff); lin("b$i.f2", ff, d)
        }
        read("norm.w", d); read("norm.b", d)
        lin("tile", d, 9); lin("big", d, 12); lin("split", d, 11); lin("ea", d, 2 * d); lin("eb", d, 2 * d); lin("eout", d, 6)
    }

    @Synchronized
    fun predict(v: MapView): Prediction {
        val r = v.rules
        val onMap = BooleanArray(N) { t -> (t % W < r.w || (r.puzzleColumn && t % W == r.w)) && t / W < r.h }
        val out = run(features(v, onMap))
        val tile = out[0]; val edge = out[1]; val big = out[2]; val behind = out[3]

        val probs = arrayOfNulls<FloatArray>(N)
        val cell = BooleanArray(N)
        for (t in 0 until N) {
            if (v.roomAt[t] >= 0 || !onMap[t]) continue
            val main = t % W < r.w
            if (!main && v.roomAt[t - 1] >= 0 && !Edge.isDoor(v.edge(t - 1, t))) continue
            cell[t] = true
            val q = FloatArray(Cls.COUNT) { tile[t * 9 + it] }
            if (main) { val s = q.sum(); for (c in q.indices) q[c] /= s }
            probs[t] = q
        }
        val splits = ArrayList<Split>()
        for (t in 0 until N) if (cell[t]) for (n in v.neighbours(t)) {
            if (v.roomAt[n] < 0) continue
            val k = v.edge(t, n)
            if (!Edge.isDoor(k)) continue
            val b = t * 11
            splits += Split(t, k, behind[b], behind[b + 1], FloatArray(Cls.COUNT) { behind[b + 2 + it] }, behind[b + 10])
            break
        }
        val bigRooms = ArrayList<BigRoom>()
        for (a in 0 until N) for (k in 1 until 12) {
            val p = big[a * 12 + k]
            if (p < 0.05f) continue
            val tiles = BIG_TILES[k].map { a + it }.toIntArray()
            if (tiles.any { it !in 0 until N || it % W < a % W || it % W >= r.w || it / W >= r.h || !cell[it] }) continue
            bigRooms += BigRoom(tiles, p)
        }
        bigRooms.sortByDescending { it.chance }
        val doors = ArrayList<PredictedDoor>()
        for (t in 0 until N) for (s in 0 until 2) {
            val n = if (s == 0) (if (t % W + 1 < W) t + 1 else -1) else (if (t + W < N) t + W else -1)
            if (n < 0 || !cell[t] || !cell[n]) continue
            val e = (t * 2 + s) * 6
            val door = edge[e + 2] + edge[e + 3] + edge[e + 4]
            if (door >= 0.05f) doors += PredictedDoor(t, n, door, edge[e + 3], edge[e + 4], 1f / (1f + exp(-edge[e + 5])))
        }
        doors.sortByDescending { it.chance }
        return Prediction(W, W, probs, splits, bigRooms, emptyList(), doors)
    }

    private fun clsOf(k: Kind, s: Shape): Int = when (k) {
        Kind.NORMAL -> if (s == Shape.ONE) Cls.NORMAL else Cls.BIG
        Kind.ENTRANCE -> 8
        Kind.PUZZLE -> Cls.PUZZLE
        Kind.TRAP -> Cls.TRAP
        Kind.YELLOW -> Cls.YELLOW
        Kind.BLOOD -> Cls.BLOOD
        Kind.FAIRY -> Cls.FAIRY
        Kind.RARE -> Cls.RARE
    }

    // the map shows every door of the fairy room alike: the one in is a wither door (unless from the entrance), the way on
    // to blood too, the others plain
    private fun fairySide(v: MapView, a: Int, b: Int, depth: IntArray): Int {
        val fa = v.roomAt[a] >= 0 && v.kinds[v.roomAt[a]] == Kind.FAIRY
        val here = if (fa) a else b
        val there = if (fa) b else a
        val fairy = v.roomAt[here]
        val other = v.roomAt[there]
        if (other >= 0 && depth[here] > depth[there]) return if (v.kinds[other] == Kind.ENTRANCE) DOOR else WITHER
        if (other >= 0) {
            if (v.kinds[other] == Kind.BLOOD) return WITHER
            for (t in v.roomTiles[other]) for (n in v.neighbours(t)) {
                val k = v.edge(t, n)
                if (v.roomAt[n] != fairy && (k == Edge.WITHER || k == Edge.BLOOD)) return WITHER
            }
            return DOOR
        }
        var out = 0; var onward = false
        for (t in v.roomTiles[fairy]) for (n in v.neighbours(t)) {
            val o = v.roomAt[n]
            if (o == fairy || !Edge.isDoor(v.edge(t, n))) continue
            if (o < 0) out++ else if (depth[n] > depth[t] && fairySide(v, t, n, depth) == WITHER) onward = true
        }
        return if (!onward && out == 1) WITHER else DOOR
    }

    private fun features(v: MapView, onMap: BooleanArray): FloatArray {
        val ck = BooleanArray(N) { v.roomAt[it] >= 0 }
        val cls = IntArray(N) { t -> v.roomAt[t].let { if (it < 0) 0 else clsOf(v.kinds[it], v.shapes[it]) } }
        val door = Array(N) { BooleanArray(4) }
        for (t in 0 until N) for (dd in 0 until 4) {
            val n = NB[t][dd]
            if (n >= 0 && ck[t] && ck[n] && (v.roomAt[t] == v.roomAt[n] || Edge.isDoor(v.edge(t, n)))) door[t][dd] = true
        }
        val dist = IntArray(N) { if (ck[it] && cls[it] == 8) 0 else 99 }
        repeat(26) { for (t in 0 until N) if (ck[t]) for (dd in 0 until 4) if (door[t][dd]) dist[t] = min(dist[t], dist[NB[t][dd]] + 1) }
        val est = Array(N) { IntArray(4) }
        for (t in 0 until N) for (dd in 0 until 4) {
            val n = NB[t][dd]
            est[t][dd] = when {
                n < 0 || !onMap[t] || !onMap[n] -> E_OFF
                ck[t] && ck[n] && v.roomAt[t] == v.roomAt[n] -> E_SAME
                ck[t] || ck[n] -> when (v.edge(t, n)) {
                    Edge.DOOR -> DOOR
                    Edge.WITHER -> WITHER
                    Edge.BLOOD -> BLOOD
                    Edge.FAIRY -> fairySide(v, t, n, dist)
                    else -> WALL
                }
                else -> E_UNK
            }
        }
        val rank = IntArray(N) { -1 }
        if (v.rules.floor == 7 && v.puzzleSlots.size == v.puzzles) v.puzzleSlots.forEachIndexed { i, name ->
            if (name == null) return@forEachIndexed
            val j = v.kinds.indices.firstOrNull { v.kinds[it] == Kind.PUZZLE && MapPredictor.isTabPuzzle(v.names[it], name) } ?: return@forEachIndexed
            for (t in v.roomTiles[j]) rank[t] = i
        }
        var secLo = -1; var secHi = -1
        if (v.secretTotals.isNotEmpty()) {
            val byName = MapStats.NAMES.associateBy { it.name }
            var known = 0; var all = true
            for (i in v.kinds.indices) {
                val name = v.names[i] ?: when (v.kinds[i]) { Kind.ENTRANCE -> "Entrance"; Kind.BLOOD -> "Blood"; Kind.FAIRY -> "Fairy"; else -> null }
                val rn = name?.let { byName[it] }
                if (rn == null) { all = false; break }
                known += rn.secrets
            }
            val lo = v.secretTotals.min() - known; val hi = v.secretTotals.max() - known
            if (all && lo >= 0 && hi - lo <= 8) { secLo = lo; secHi = hi }
        }
        val glob = FloatArray(10)
        for (t in 0 until N) { if (!ck[t] && onMap[t]) glob[0] += 1f / N; if (ck[t]) glob[cls[t] + 1] += 1f / 8 }
        val col = if (v.rules.puzzleColumn) v.rules.w else -1
        val x = FloatArray(N * feat)
        for (t in 0 until N) {
            val b = t * feat
            x[b + if (ck[t]) cls[t] + 1 else 0] = 1f
            var o = 10
            for (dd in 0 until 4) x[b + o + dd * 7 + est[t][dd]] = 1f
            o += 28; x[b + o + v.puzzles.coerceIn(2, 5) - 2] = 1f
            o += 4; x[b + o + if (ck[t] && dist[t] < 99) min(dist[t], DMAX - 1) else DMAX] = 1f
            o += DMAX + 1
            if (ck[t]) x[b + o + SHAPE[v.shapes[v.roomAt[t]].ordinal]] = 1f
            o += 6; System.arraycopy(glob, 0, x, b + o, 10)
            o += 10; if (!onMap[t]) x[b + o] = 1f
            o += 1; if (v.puzzles < 2) x[b + o + v.puzzles] = 1f
            o += 2; x[b + o + v.rules.floor - 1] = 1f
            o += 7; if (rank[t] >= 0 && cls[t] == Cls.PUZZLE) x[b + o + min(rank[t], 4)] = 1f
            o += 5; if (secLo >= 0) { x[b + o] = 1f; x[b + o + 1] = secLo / 50f; x[b + o + 2] = secHi / 50f }
            o += 3; if (t % W == col && onMap[t]) x[b + o] = 1f
        }
        return x
    }

    /** tile chances [36 x 9], side chances and fairy logit [36 x 2 x 6], big rooms [36 x 12], what is behind [36 x 11]. */
    private fun run(x: FloatArray): Array<FloatArray> {
        val h = linear(x, feat, "inp", d)
        val pos = w.getValue("pos")
        for (i in h.indices) h[i] += pos[i]
        val dh = d / heads
        val scale = 1f / sqrt(dh.toFloat())
        val att = FloatArray(N * d)
        val sc = FloatArray(N)
        val s3 = 3 * d
        for (l in 0 until layers) {
            val qkv = linear(norm(h, "b$l.n1"), d, "b$l.qkv", s3)
            att.fill(0f)
            for (hd in 0 until heads) {
                val q0 = hd * dh; val k0 = d + hd * dh; val v0 = 2 * d + hd * dh
                for (t in 0 until N) {
                    var m = Float.NEGATIVE_INFINITY
                    for (u in 0 until N) {
                        var s = 0f
                        for (j in 0 until dh) s += qkv[t * s3 + q0 + j] * qkv[u * s3 + k0 + j]
                        s *= scale; sc[u] = s; if (s > m) m = s
                    }
                    var z = 0f
                    for (u in 0 until N) { sc[u] = exp(sc[u] - m); z += sc[u] }
                    for (u in 0 until N) {
                        val p = sc[u] / z
                        for (j in 0 until dh) att[t * d + q0 + j] += p * qkv[u * s3 + v0 + j]
                    }
                }
            }
            val pr = linear(att, d, "b$l.proj", d)
            for (i in h.indices) h[i] += pr[i]
            val f1 = linear(norm(h, "b$l.n2"), d, "b$l.f1", ff)
            for (i in f1.indices) f1[i] = gelu(f1[i])
            val f2 = linear(f1, ff, "b$l.f2", d)
            for (i in h.indices) h[i] += f2[i]
        }
        val hn = norm(h, "norm")
        val tile = linear(hn, d, "tile", 9); softmax(tile, 9)
        val big = linear(hn, d, "big", 12); softmax(big, 12)
        val split = linear(hn, d, "split", 11)
        for (t in 0 until N) for (k in 0 until 11) split[t * 11 + k] *= SPLIT_SCALE[k]
        val ea = linear(hn, d, "ea", 2 * d)
        val eb = linear(hn, d, "eb", 2 * d)
        val g = FloatArray(N * 2 * d)
        for (t in 0 until N) for (s in 0 until 2) {
            val n = max(NB[t][if (s == 0) 0 else 2], 0)
            for (j in 0 until d) g[(t * 2 + s) * d + j] = gelu(ea[t * 2 * d + s * d + j] + eb[n * 2 * d + s * d + j])
        }
        val eo = linear(g, d, "eout", 6)
        for (r in 0 until N * 2) {
            var m = Float.NEGATIVE_INFINITY
            for (k in 0 until 5) m = max(m, eo[r * 6 + k])
            var z = 0f
            for (k in 0 until 5) { eo[r * 6 + k] = exp(eo[r * 6 + k] - m); z += eo[r * 6 + k] }
            for (k in 0 until 5) eo[r * 6 + k] /= z
        }
        return arrayOf(tile, eo, big, split)
    }

    private fun linear(x: FloatArray, nIn: Int, name: String, nOut: Int): FloatArray {
        val wt = w.getValue("$name.w"); val bias = w.getValue("$name.b")
        val rows = x.size / nIn
        val y = FloatArray(rows * nOut)
        for (r in 0 until rows) {
            val yo = r * nOut
            System.arraycopy(bias, 0, y, yo, nOut)
            for (i in 0 until nIn) {
                val a = x[r * nIn + i]
                if (a == 0f) continue
                val wo = i * nOut
                for (j in 0 until nOut) y[yo + j] += a * wt[wo + j]
            }
        }
        return y
    }

    private fun norm(x: FloatArray, name: String): FloatArray {
        val g = w.getValue("$name.w"); val b = w.getValue("$name.b")
        val y = FloatArray(x.size)
        for (r in 0 until x.size / d) {
            var m = 0f
            for (j in 0 until d) m += x[r * d + j]
            m /= d
            var vv = 0f
            for (j in 0 until d) { val e = x[r * d + j] - m; vv += e * e }
            val inv = 1f / sqrt(vv / d + 1e-5f)
            for (j in 0 until d) y[r * d + j] = (x[r * d + j] - m) * inv * g[j] + b[j]
        }
        return y
    }

    enum class Size(val file: String) { BEST("best"), BALANCED("balanced"), FAST("fast"), FASTEST("fastest") }

    companion object {
        private const val W = 6
        private const val N = 36
        private const val DMAX = 16
        private const val E_OFF = 0
        private const val E_UNK = 1
        private const val WALL = 2
        private const val E_SAME = 3
        private const val DOOR = 4
        private const val WITHER = 5
        private const val BLOOD = 6
        /** [Shape] ordinal to the net's shape index (1x1, 1x2, 1x3, L, 1x4, 2x2). */
        private val SHAPE = intArrayOf(1, 2, 4, 5, 3, 0)
        private val SPLIT_SCALE = floatArrayOf(10f, 20f, 4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f, 30f)
        /** The big room types' tiles from their bounding box's first tile: 1x2, 1x3, 1x4 each across then down, 2x2, L without each corner. */
        private val BIG_TILES = arrayOf(intArrayOf(), intArrayOf(0, 1), intArrayOf(0, 6), intArrayOf(0, 1, 2), intArrayOf(0, 6, 12),
            intArrayOf(0, 1, 2, 3), intArrayOf(0, 6, 12, 18), intArrayOf(0, 1, 6, 7), intArrayOf(1, 6, 7), intArrayOf(0, 6, 7), intArrayOf(0, 1, 7), intArrayOf(0, 1, 6))
        private val NB = Array(N) { t ->
            intArrayOf(if (t % W + 1 < W) t + 1 else -1, if (t % W > 0) t - 1 else -1, if (t + W < N) t + W else -1, if (t >= W) t - W else -1)
        }

        @Volatile private var loaded: Pair<Size, RoomNet>? = null

        /** The net of that size, read from the mod's resources the first time (call it off the render thread). */
        fun of(size: Size): RoomNet {
            loaded?.let { if (it.first == size) return it.second }
            val net = RoomNet::class.java.getResourceAsStream("/assets/engineerclient/mappred/rooms-${size.file}.bin")!!.use { RoomNet(it) }
            loaded = size to net
            return net
        }

        private fun softmax(a: FloatArray, k: Int) {
            for (r in 0 until a.size / k) {
                var m = Float.NEGATIVE_INFINITY
                for (j in 0 until k) m = max(m, a[r * k + j])
                var z = 0f
                for (j in 0 until k) { a[r * k + j] = exp(a[r * k + j] - m); z += a[r * k + j] }
                for (j in 0 until k) a[r * k + j] /= z
            }
        }

        private fun gelu(x: Float): Float = 0.5f * x * (1f + erf(x / 1.4142135f))

        private fun erf(x: Float): Float {
            val a = kotlin.math.abs(x).toDouble()
            val t = 1.0 / (1.0 + 0.5 * a)
            val y = 1.0 - t * exp(-a * a - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
                t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))))
            return (if (x < 0) -y else y).toFloat()
        }
    }
}
