package com.engineerclient.p3sim.clear.layout

/** A room the spike built and what Odin should make of it: Odin's room name, its rotation word, and the world tiles it covers. */
data class ExpectedRoom(val name: String, val rotation: String, val tiles: Set<Pair<Int, Int>>)

/** A room as Odin's scanner holds it (read from `DungeonScan.rooms`). */
data class SeenRoom(val name: String?, val rotation: String?, val tiles: Set<Pair<Int, Int>>, val type: String?, val shape: String?)

/** One line of the probe's answer: [ok] is true for a room Odin got right. */
data class ProbeLine(val ok: Boolean, val text: String)

/** Compares what the spike built with what Odin recognised. Pure; no Odin types, so it is tested headless. */
object ProbeReport {
    fun compare(expected: List<ExpectedRoom>, seen: List<SeenRoom>): List<ProbeLine> = expected.map { e ->
        val byName = seen.filter { it.name == e.name }
        val same = byName.firstOrNull()
        when {
            same == null -> {
                val here = seen.filter { it.tiles.any { t -> t in e.tiles } }
                ProbeLine(false, "${e.name}: Odin has not named it" + if (here.isEmpty()) " (it has no room on tiles ${tilesText(e.tiles)} yet)." else " (on tiles ${tilesText(e.tiles)} it holds ${here.joinToString { it.name ?: "an unnamed room" }}).")
            }
            same.rotation == null -> ProbeLine(false, "${e.name}: Odin named it but found no rotation yet (expected ${e.rotation}).")
            same.rotation != e.rotation -> ProbeLine(false, "${e.name}: Odin says rotation ${same.rotation}, built ${e.rotation}.")
            same.tiles != e.tiles -> ProbeLine(false, "${e.name}: Odin has it on tiles ${tilesText(same.tiles)}, built on ${tilesText(e.tiles)}.")
            else -> ProbeLine(true, "${e.name}: Odin names it, rotation ${same.rotation}, ${same.shape ?: "?"} on tiles ${tilesText(e.tiles)}.")
        }
    }

    fun allOk(lines: List<ProbeLine>): Boolean = lines.isNotEmpty() && lines.all { it.ok }

    private fun tilesText(t: Set<Pair<Int, Int>>) = t.sortedWith(compareBy({ it.second }, { it.first })).joinToString(" ") { "(${it.first},${it.second})" }
}
