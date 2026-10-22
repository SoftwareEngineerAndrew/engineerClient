package com.engineerclient.mappred

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * Optional: predictions for partial maps from a file outside the repo (ADS_MAP_SNAPSHOTS, one map a
 * line: "w h puzzles|KIND:t,t;...|a,b,edge;..."), written next to it as <file>.out for scoring by
 * the analysis scripts. Skipped when the variable isn't set.
 */
class MapSnapshotsCheck {
    @Test fun predictSnapshots() {
        val path = System.getenv("ADS_MAP_SNAPSHOTS")
        assumeTrue(path != null && File(path).isFile)
        val out = StringBuilder()
        var total = 0L
        for (line in File(path!!).readLines()) {
            if (line.isBlank()) continue
            val (head, rooms, edges) = line.split('|')
            val (w, h, puzzles) = head.split(' ').map { it.toInt() }
            val v = MapView(w, h, puzzles)
            for (r in rooms.split(';').filter { it.isNotEmpty() }) {
                val (k, t) = r.split(':')
                v.addRoom(Kind.valueOf(k), t.split(',').map { it.toInt() }.toIntArray())
            }
            for (e in edges.split(';').filter { it.isNotEmpty() }) {
                val (a, b, k) = e.split(',').map { it.toInt() }
                v.setEdge(a, b, k.toByte())
            }
            val t0 = System.nanoTime()
            val p = MapPredictor(v)
            p.run(400, 400_000, Random(5))
            total += System.nanoTime() - t0
            val pr = p.prediction()
            if (pr == null) { out.append("none\n"); continue }
            out.append(pr.level).append('|')
            out.append(pr.probs.joinToString(";") { it?.joinToString(",") ?: "" }).append('|')
            out.append(pr.splits.joinToString(";") { s -> "${s.tile},${s.door},${s.rooms},${s.tiles}," + s.expect.joinToString(",") }).append('\n')
        }
        File("$path.out").writeText(out.toString())
        println("map snapshots: ${total / 1_000_000} ms")
    }
}
