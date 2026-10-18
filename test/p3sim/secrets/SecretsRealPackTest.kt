package com.engineerclient.p3sim.secrets

import com.engineerclient.p3sim.clear.blood.HeadTextures
import com.engineerclient.p3sim.clear.score.ScoreFormula
import com.engineerclient.p3sim.clear.score.ScoreInputs
import com.engineerclient.p3sim.clear.secrets.CellGroups
import com.engineerclient.p3sim.clear.secrets.SecretBook
import com.engineerclient.p3sim.clear.secrets.SecretPlan
import com.engineerclient.p3sim.clear.secrets.SecretWorld
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.RunBus
import com.engineerclient.p3sim.run.RunState
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Optional checks against the REAL, built packs (kept outside this repo), only when ADS_REAL_PACKS names the folder that holds them.
 * They put counts and room names in the report (ADS_REAL_REPORT), never recorded values: the point is to see where the secrets code and
 * the packs disagree before a run does. Skipped otherwise.
 */
class SecretsRealPackTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
    private val report = StringBuilder()
    private fun say(s: String) { report.appendLine(s) }
    private fun flush() { System.getenv("ADS_REAL_REPORT")?.let { Files.writeString(Path.of(it), report.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); report.setLength(0) } }

    @Test fun everyPoolLayoutPlacesItsSecretsInsideItsRooms() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms)
            var plans = 0; var sites = 0; var printed = 0; var placeable = 0; var outside = 0; var groups = 0; var groupOutside = 0
            val missingRooms = HashMap<String, Int>()
            for (l in layouts.pool()) {
                val plan = (DungeonPlan.build(l, 7L, rooms, set.runstate) as? DungeonPlan.Result.Ok)?.plan ?: continue
                plans++
                val sp = SecretPlan.build(plan, Random(7))
                val g = CellGroups.build(plan)
                groups += g.groups.size
                for (r in plan.rooms) {
                    val rs = sp.room(r.index)!!
                    printed += rs.max; placeable += rs.placeable; sites += rs.sites.size
                    if (rs.missing > 0) missingRooms.merge(r.name, rs.missing, Int::plus)
                    assertTrue(rs.placeable <= rs.max, "${l.id}/${r.name}")
                    for (s in rs.sites) {
                        // The site must stand inside the room's own cells in the world (a gap column inside a multi-tile room counts).
                        if (com.engineerclient.p3sim.clear.secrets.BreakerRules.cellRoom(plan, s.x, s.z) != r.index) { outside++; say("SITE OUTSIDE ${l.id}/${r.name} ${s.key} ${s.x},${s.z}") }
                    }
                }
                for (c in g.groups) for (p in c.cells) {
                    if (com.engineerclient.p3sim.clear.secrets.BreakerRules.cellRoom(plan, p.x, p.z) != c.room) { groupOutside++; break }
                }
            }
            say("secrets: $plans plans, $sites sites placed of $printed printed (${"%.1f".format(100.0 * placeable / printed)}% placeable), sites outside their room $outside; $groups crypt/wall groups, $groupOutside outside their room")
            say("rooms whose data lacks secrets (total missing over all plans): ${missingRooms.entries.sortedByDescending { it.value }.take(14)}")
            assertEquals(0, outside, "sites outside their rooms")
            assertEquals(0, groupOutside, "crypt/wall groups outside their rooms")
        } finally { set.close(); flush() }
    }

    @Test fun theKeyRoomsHaveTheirGateSocketDoorAndChestsInsideTheRoomAndTheDoorIsRedstone() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms)
            val seen = HashMap<String, Int>()
            for (l in layouts.pool()) {
                val plan = (DungeonPlan.build(l, 5L, rooms, set.runstate) as? DungeonPlan.Result.Ok)?.plan ?: continue
                val sp = SecretPlan.build(plan, Random(5))
                for (r in plan.rooms) {
                    val spec = com.engineerclient.p3sim.clear.secrets.RedstoneKeys.forDesign(r.design?.id) ?: continue
                    seen.merge(spec.designId, 1, Int::plus)
                    val rs = sp.room(r.index)!!
                    fun inside(x: Int, z: Int) = com.engineerclient.p3sim.clear.secrets.BreakerRules.cellRoom(plan, x, z) == r.index
                    assertEquals(1, rs.gates.size, "${l.id}/${r.name}: one key skull")
                    assertTrue(inside(rs.gates[0].x, rs.gates[0].z), "gate inside ${r.name}")
                    val socketSite = rs.sites.single { it.socket != null }
                    val socket = socketSite.socket!!
                    assertTrue(inside(socketSite.x, socketSite.z) && inside(socket.head.pos.x, socket.head.pos.z), "socket and head inside ${r.name}")
                    assertTrue(socket.opens.isNotEmpty() && socket.opens.all { inside(it.x, it.z) }, "door inside ${r.name}")
                    assertTrue((rs.sites + rs.bonus).filter { it.hidden }.all { inside(it.x, it.z) }, "revealed chests inside ${r.name}")
                    assertEquals(0, rs.missing, "${r.name}: the socket and the room's own secrets reach the printed count")
                    // The door is made of the redstone blocks the room's own volume holds.
                    val vol = rooms.volume(r.design!!)
                    if (vol != null) for (c in spec.opens) assertTrue(vol.stateAt(c.x, c.y, c.z).contains("redstone_block"), "${r.name} door cell $c is ${vol.stateAt(c.x, c.y, c.z)}")
                    // Two of the three rooms' volumes hold air where the socket block stands on Hypixel; the module places the redstone block itself.
                    if (vol != null) assertTrue(vol.stateAt(spec.socket.x, spec.socket.y, spec.socket.z).let { it.contains("redstone_block") || it.endsWith("air") }, "${r.name} socket is ${vol.stateAt(spec.socket.x, spec.socket.y, spec.socket.z)}")
                }
            }
            say("key rooms checked in the pool layouts: $seen")
        } finally { set.close(); flush() }
    }

    @Test fun findingEverythingTurnsEveryRoomsCountFull() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms)
            val me = UUID(0, 9)
            var checked = 0
            for (l in layouts.pool().take(60)) {
                val plan = (DungeonPlan.build(l, 3L, rooms, set.runstate) as? DungeonPlan.Result.Ok)?.plan ?: continue
                val bus = RunBus()
                val state = RunState(plan, bus) { 0 }
                val sp = SecretPlan.build(plan, Random(3))
                val book = SecretBook(sp, state, bus, { 0 }, { true })
                for (r in plan.rooms) {
                    for (s in sp.room(r.index)!!.sites) book.claim(r.index, s.kind, me, s.x, s.y, s.z)
                    book.roomCleared(r.index, me, 0, 70, 0)
                    // A puzzle's missing secret is its reward chest: the puzzles module claims it.
                    if (state.rooms[r.index].secretsFound < r.secretsMax) book.claim(r.index, com.engineerclient.p3sim.run.SecretKind.CHEST, me, 1, 1, 1)
                    assertEquals(r.secretsMax, state.rooms[r.index].secretsFound, "${l.id}/${r.name}")
                }
                assertEquals(plan.secretsTotal, state.secrets.teamFound, l.id)
                checked++
            }
            say("every secret found: $checked layouts, each room reached its printed maximum")
            assertTrue(checked > 0)
        } finally { set.close(); flush() }
    }

    @Test fun theScoreReplaysThePacksFitPointsWithinTheMeasuredResidual() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val rs = assertNotNull(set.runstate); val model = assertNotNull(rs.score)
            val fit = rs.section("score")!!.getAsJsonObject("fit")
            val cols = fit.getAsJsonArray("pointsColumns").map { it.asString }
            val pts = fit.getAsJsonArray("points")
            say("score fit columns: $cols, points ${pts.size()}")
            fun idx(n: String) = cols.indexOf(n).also { assertTrue(it >= 0, "column $n in $cols") }
            val c = idx("completedTiles"); val sf = idx("secretsFound"); val st = idx("secretsTotal"); val cr = idx("crypts"); val mi = idx("mimic"); val pr = idx("prince"); val ba = idx("bat")
            val de = idx("deaths"); val pu = idx("puzzlesUnsolved"); val ts = idx("tabTimeSec"); val s = idx("S")
            val res = ArrayList<Double>()
            val byC = HashMap<Int, MutableList<Double>>()
            for (e in pts) {
                val a = e.asJsonArray
                val inp = ScoreInputs(a[c].asInt, a[sf].asInt, a[st].asInt, a[cr].asInt, a[mi].asDouble > 0, a[pr].asDouble > 0, a[ba].asDouble > 0, a[de].asInt, a[pu].asInt, a[ts].asDouble)
                val got = ScoreFormula.fitted(model, inp).total
                val r = got - a[s].asDouble
                res += r
                byC.getOrPut(inp.completedTiles) { ArrayList() } += r
            }
            val mean = res.average(); val rmse = Math.sqrt(res.sumOf { it * it } / res.size)
            val mae = res.sumOf { Math.abs(it) } / res.size
            say("score replay over all ${res.size} fit points: mean error ${"%.2f".format(mean)}, rmse ${"%.2f".format(rmse)}, mean abs ${"%.2f".format(mae)}; within 5 points: ${"%.0f".format(100.0 * res.count { Math.abs(it) <= 5 } / res.size)}%")
            say("  by completed tiles (mean error): " + byC.toSortedMap().entries.joinToString(" ") { "${it.key}:${"%.1f".format(it.value.average())}(n${it.value.size})" })
            // The pack's own residual was rmse 3.7 on stable steps; all steps include the tab-lag noise, so the bar is looser.
            assertTrue(Math.abs(mean) < 2.0, "mean error $mean")
            assertTrue(rmse < 9.0, "rmse $rmse")
        } finally { set.close(); flush() }
    }

    /** The item and key heads come from the pack by role, never from the code: a pack built with `add_blood_textures.py` has all five. */
    @Test fun theItemAndKeyHeadsAreInThePackByRole() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val textures = assertNotNull(set.rooms).textures
            val roles = listOf(SecretWorld.ESSENCE_SKIN_ROLE, "revive_stone", "training_weights", "wither_key", "blood_key")
            assumeTrue(textures.any { it.role == "wither_key" }, "pack built without the item heads (packs/rooms/add_blood_textures.py)")
            val values = roles.associateWith { assertNotNull(HeadTextures.resolve(it, textures), it).value }
            assertTrue(values.values.all { it.isNotBlank() })
            // The wither key and the essence skull wear the same skin; every other head has its own.
            assertEquals(values.getValue("wither_key"), values.getValue(SecretWorld.ESSENCE_SKIN_ROLE))
            assertEquals(roles.size - 1, values.values.toSet().size)
            say("item and key heads: ${roles.size} roles, ${values.values.toSet().size} distinct skins")
        } finally { set.close(); flush() }
    }
}
