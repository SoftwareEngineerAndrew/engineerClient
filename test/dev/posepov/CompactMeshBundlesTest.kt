package com.devgineerclient.posepov

import com.engineerclient.p3sim.pack.Rot
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Every exported bundle's pieces, merged: the faces cover exactly the area they did, side by side, and how much smaller it all
 * gets. Only with POSE_BUNDLES set to an export folder (devgineerclient/posepov-export/<version>).
 */
class CompactMeshBundlesTest {
    @Test fun mergedPiecesCoverTheSameFaces() {
        val dir = System.getenv("POSE_BUNDLES")
        assumeTrue(dir != null, "POSE_BUNDLES isn't set")
        var before = 0L; var after = 0L; var pieces = 0; var shown = 0
        val q = CompactMesh.Quads(4096)
        for (f in Files.list(Path.of(dir!!)).use { it.toList() }.sorted()) {
            val b = MeshBundles.parse(f.fileName.toString(), Files.readAllBytes(f))
            val sprites = IntArray(b.names.size) { it }
            for (i in b.places.indices) {
                if (b.lens[i] == 0) continue
                MeshCodec.decode(b.data, b.offs[i], b.lens[i], sprites, Rot.EAST, 64, 64, q)
                val p = CompactMesh.pack(q)
                if (shown < 12) for (k in 0 until q.n) {
                    val s = q.dir[k].toInt(); if (s !in 0..5) continue
                    val ax = intArrayOf(1, 1, 2, 2, 0, 0)[s]
                    val v = q.pos[k * 12 + ax]
                    if (Math.abs(v - Math.round(v)) > 1e-3 && shown < 12) {
                        shown++
                        println("odd: ${b.name} side $s ${b.names[(0 until b.names.size).first { sprites[it] == q.sprite[k] }]} pos ${q.pos.copyOfRange(k * 12, k * 12 + 12).joinToString(",")} uv ${q.uv.copyOfRange(k * 8, k * 8 + 8).joinToString(",")}")
                    }
                }
                val want = DoubleArray(7); val got = DoubleArray(7); val n = IntArray(7)
                for (k in 0 until q.n) { val s = q.dir[k].toInt().coerceIn(0, 6); want[s] += area(q.pos, k * 12); n[s]++ }
                val d = p.bytes.duplicate().order(p.bytes.order())
                var g = 0; var left = p.counts[0]
                val pos = FloatArray(12)
                for (k in 0 until p.quads) {
                    while (left == 0) left = p.counts[++g]
                    left--
                    for (c in 0 until 4) {
                        for (a in 0 until 3) pos[c * 3 + a] = (d.short.toInt() and 0xFFFF) / 64f - 256f
                        d.short; d.short; d.short; d.int
                    }
                    got[CompactMesh.groupDir(g).let { if (it < 0) 6 else it }] += area(pos, 0)
                }
                // (Corners are kept to 1/64 of a block, the bundles' to 1/128: a quad's area can move by its rounding, no more.)
                for (s in 0 until 7) assertEquals(want[s], got[s], 0.01 + n[s] * 0.02, "${b.name} piece $i side $s")
                before += q.n; after += p.quads; pieces++
            }
        }
        println("merged: $pieces pieces, $before quads -> $after (%.1f%%); %.1f MB -> %.1f MB on the GPU".format(
            after * 100.0 / before, before * 96 / 1048576.0, after * 64 / 1048576.0))
    }

    private fun area(p: FloatArray, o: Int): Double {
        val ax = p[o + 6] - p[o]; val ay = p[o + 7] - p[o + 1]; val az = p[o + 8] - p[o + 2]
        val bx = p[o + 9] - p[o + 3]; val by = p[o + 10] - p[o + 4]; val bz = p[o + 11] - p[o + 5]
        val cx = ay * bz - az * by; val cy = az * bx - ax * bz; val cz = ax * by - ay * bx
        return Math.sqrt((cx * cx + cy * cy + cz * cz).toDouble()) / 2
    }
}
