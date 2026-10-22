package com.engineerclient.p3sim.mobs

import com.engineerclient.p3sim.clear.mobs.MobPlanner
import com.engineerclient.p3sim.clear.mobs.MobRolls
import com.engineerclient.p3sim.clear.mobs.MobTags
import com.engineerclient.p3sim.clear.mobs.PlanParams
import com.engineerclient.p3sim.clear.mobs.SpawnMode
import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.mobs.MobPack
import com.engineerclient.p3sim.pack.rooms.Placement
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Optional checks against the REAL mob pack (kept outside this repo). Runs only when ADS_REAL_PACKS names the packs folder; otherwise
 * skipped. Nothing from the data is copied anywhere; the numbers go to ADS_REAL_REPORT when set.
 */
class RealMobsTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
    private val out = StringBuilder()
    private fun say(s: String) { out.appendLine(s) }
    private fun flush() { System.getenv("ADS_REAL_REPORT")?.let { Files.writeString(Path.of(it), out.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); out.setLength(0) } }

    private fun pack(): MobPack { assumeTrue(dir != null, "ADS_REAL_PACKS not set"); return MobPack.load(dir!!, PackReport())!! }

    @Test fun everyRealTagPassesTheStarRegexes() {
        val pack = pack()
        var n = 0; var bad = 0
        for (a in pack.archetypes) {
            val tag = a.tag ?: continue
            if (!a.star) continue
            for (m in listOf(null) + pack.modifiers.filter { it.id.isNotEmpty() }) for (hp in listOf(420_000.0, 483_000.0, 1_200_000.0, 94_500.0, 90_000.0)) {
                n++
                val legacy = MobTags.nametagLegacy(tag, a.name, m, hp, hp, true)
                if (!MobTags.looksStarred(legacy)) { bad++; say("NO MATCH ${a.id}/${m?.id}/$hp: ${MobTags.plain(legacy)}") }
            }
        }
        say("tags checked=$n bad=$bad")
        flush()
        assertTrue(bad == 0)
    }

    /** Per-room starred counts of the "layouts" sampler and the "cells" sampler against the recorded mean, in-sample (the layouts ARE the recording). */
    @Test fun sampledStarCountsFollowTheRecordedMeans() {
        val pack = pack()
        val rng = Random(2026)
        val place = Placement.of("1x1", 31, 31, Rot.SOUTH, listOf(Tile(0, 0)))
        var rooms = 0; var layoutsOk = 0; var cellsOk = 0
        for (r in pack.rooms) {
            val s = r.starred ?: continue
            if (r.visits < 10 || s.mean < 1.0) continue
            rooms++
            val n = 300
            val byLayouts = (0 until n).sumOf { MobRolls.rollRoom(r, "F7", rng, SpawnMode.LAYOUTS).count { m -> m.star } } / n.toDouble()
            val byCells = MobRolls.expectedStarred(r)
            if (Math.abs(byLayouts - s.mean) <= 0.25 * s.mean + 0.5) layoutsOk++ else say("layouts off: ${r.name} recorded=${"%.1f".format(s.mean)} layouts=${"%.1f".format(byLayouts)}")
            if (Math.abs(byCells - s.mean) <= 0.25 * s.mean + 0.5) cellsOk++ else say("cells off: ${r.name} recorded=${"%.1f".format(s.mean)} cells=${"%.1f".format(byCells)}")
        }
        say("rooms=$rooms layoutsWithin25%=$layoutsOk cellsWithin25%=$cellsOk")
        flush()
        assertTrue(layoutsOk >= rooms * 0.8, "layouts sampler: $layoutsOk of $rooms")
        assertTrue(cellsOk >= rooms * 0.6, "cells sampler: $cellsOk of $rooms")
    }

    @Test fun everyRoomPlansWithoutTrouble() {
        val pack = pack()
        val place = Placement.of("1x1", 31, 31, Rot.SOUTH, listOf(Tile(0, 0)))
        var empty = 0; var total = 0L
        for (r in pack.rooms) {
            // A canonical-frame cell outside a 1x1 box is just a number to the planner; this checks it never throws and keeps HP positive.
            val plans = MobPlanner.planFrom(pack, r, place, PlanParams(), Random(r.name.hashCode().toLong()))
            if (plans.isEmpty()) empty++
            total += plans.size
            for (p in plans) assertTrue(p.maxHp > 0.0 && p.id.version() in 2..4)
            val bosses = plans.count { pack.archetype(it.arch)!!.cls == "miniboss" }
            if (r.clear == "miniboss") assertTrue(bosses <= 1, "${r.name}: $bosses minibosses")
        }
        say("rooms=${pack.rooms.size} emptyPlans=$empty mobs=$total")
        flush()
    }
}
