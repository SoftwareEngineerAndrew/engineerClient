package com.engineerclient.mappred

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * Optional: predictions for partial maps from a file outside the repo (ADS_MAP_SNAPSHOTS, one map a
 * line: "w h puzzles [floor]|KIND:t,t;...|a,b,edge;..."; F7 when there's no floor), written next to it
 * as <file>.out for scoring by the analysis scripts. Skipped when the variable isn't set. Each is run as
 * the game's map prediction worker runs it (OdinMapPrediction: chunks until enough samples, the prediction
 * taken after every chunk and the last one that was there kept), its own seed (5 + its line), by the
 * floor's default model, or the classic one with ADS_MAP_MODEL=classic.
 */
class MapSnapshotsCheck {
    @Test fun predictSnapshots() {
        val path = System.getenv("ADS_MAP_SNAPSHOTS")
        assumeTrue(path != null && File(path).isFile)
        val classic = System.getenv("ADS_MAP_MODEL") == "classic"
        val out = StringBuilder()
        var total = 0L
        var n = 0
        for (line in File(path!!).readLines()) {
            if (line.isBlank()) continue
            val (head, rooms, edges) = line.split('|')
            val nums = head.split(' ').map { it.toInt() }
            val (w, h, puzzles) = nums
            val v = MapView(w, h, puzzles, FloorRules.of(nums.getOrElse(3) { 7 }) ?: FloorRules.F7)
            for (r in rooms.split(';').filter { it.isNotEmpty() }) {
                val (k, t) = r.split(':')
                v.addRoom(Kind.valueOf(k), t.split(',').map { it.toInt() }.toIntArray())
            }
            for (e in edges.split(';').filter { it.isNotEmpty() }) {
                val (a, b, k) = e.split(',').map { it.toInt() }
                v.setEdge(a, b, k.toByte())
            }
            val t0 = System.nanoTime()
            val p = MapPredictor(v, gameProcess = !classic)
            val rnd = Random(5 + n++)
            // as the worker: a prediction after every chunk, the last one that was there stays up
            var shown: Prediction? = null
            while (p.accepted < 2000) {
                val kept = p.run(100, 40_000, rnd)
                p.prediction()?.let { shown = it }
                if (kept == 0 && (p.accepted > 0 || p.level > 2)) break
            }
            val ns = System.nanoTime() - t0
            total += ns
            val pr = shown
            if (pr == null) { out.append("none\n"); continue }
            out.append(pr.level).append('|')
            out.append(pr.probs.joinToString(";") { it?.joinToString(",") ?: "" }).append('|')
            out.append(pr.splits.joinToString(";") { s -> "${s.tile},${s.door},${s.rooms},${s.tiles}," + s.expect.joinToString(",") })
            // what it took: ms, samples, the sampler (g: game process, c: classic)
            out.append('|').append(ns / 1_000_000).append(',').append(pr.samples).append(',').append(if (p.usesGameProcess) 'g' else 'c').append('\n')
        }
        File("$path.out").writeText(out.toString())
        println("map snapshots: ${total / 1_000_000} ms")
    }
}
