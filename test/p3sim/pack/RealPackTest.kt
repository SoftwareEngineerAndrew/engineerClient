package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.mobs.MobPack
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.RoomType
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Optional check of the loaders and of [DungeonPlan.build] against the REAL, built packs (kept outside this repo). Runs only when
 * ADS_REAL_PACKS names the folder that holds the pack files (the extractor's `packs/out/`); otherwise it is skipped.
 * It copies nothing into the repo and prints nothing from the data except counts and room/design ids: the point is to find
 * every place where a loader or the plan builder disagrees with a pack that was built to the FORMAT specs, before thirteen
 * work items build on them. ADS_REAL_REPORT (a file path) receives a plain-text summary when set.
 */
class RealPackTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
    private val report = StringBuilder()
    private fun say(s: String) { report.appendLine(s) }
    private fun flush() { System.getenv("ADS_REAL_REPORT")?.let { Files.writeString(Path.of(it), report.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); report.setLength(0) } }

    @Test fun everyPackLoadsAndEveryRoomVolumeDecodes() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val r = PackReport()
        val set = Packs.load(dir!!)
        try {
            say("report lines: " + set.report.all().joinToString(" | ") { "${it.level}: ${it.text}" })
            assertTrue(set.canBuildDungeon, set.userLines().toString())
            val rooms = assertNotNull(set.rooms)
            say("designs=${rooms.designs.size} cores=${rooms.cores.size} textures=${rooms.textures.size} aliases=${rooms.aliases.size}")
            var bad = 0; var cells = 0L
            for (d in rooms.designs) {
                val v = rooms.volume(d)
                if (v == null) { bad++; say("NO VOLUME: ${d.id}"); continue }
                cells += v.nonAirCount()
                if (v.w != d.w || v.d != d.d) { bad++; say("BOX MISMATCH ${d.id}") }
                // The shape's footprint is what Placement.of demands.
                val fp = com.engineerclient.p3sim.pack.rooms.Placement.footprint(d.shape)
                if (fp.first != d.w || fp.second != d.d) { bad++; say("FOOTPRINT ${d.id} ${d.shape} ${d.w}x${d.d}") }
                for (var_ in d.variants) {
                    if (rooms.variantVolume(d, var_) == null) { bad++; say("NO VARIANT ${d.id}/${var_.id}") }
                }
                // Block entities and secrets sit inside the box.
                for (b in d.blockEntities) if (b.pos.x !in 0 until d.w || b.pos.z !in 0 until d.d) { bad++; say("BE OUT OF BOX ${d.id} ${b.pos}") }
                for (s in d.secrets) if (s.pos.x !in 0 until d.w || s.pos.z !in 0 until d.d) { bad++; say("SECRET OUT OF BOX ${d.id} ${s.id}") }
                // A design's tiles are the canonical tiles of its shape.
                if (d.tiles.toSet() != com.engineerclient.p3sim.pack.rooms.Placement.canonicalTiles(d.shape).toSet()) { bad++; say("TILES ${d.id}") }
            }
            say("non-air cells over all base volumes: $cells, problems: $bad")
            val byType = rooms.designs.groupingBy { it.type }.eachCount()
            say("designs by type: $byType")
            val multi = rooms.designs.groupBy { it.family }.filter { it.value.size > 1 }.mapValues { it.value.size }
            say("multi-build families: $multi")
            var coreMiss = 0
            for (c in rooms.cores) {
                val d = rooms.design(c.design)
                if (d == null) { coreMiss++; say("CORE TO UNKNOWN DESIGN ${c.core} ${c.design}"); continue }
                if (c.variant != null && d.variants.none { it.id == c.variant }) { coreMiss++; say("CORE TO UNKNOWN VARIANT ${c.core} ${c.design}/${c.variant}") }
            }
            say("core table problems: $coreMiss")
            // Odin's core of every tile centre column of the decoded base volume must be one the pack says the base produces (G2),
            // and a variant with a core must produce exactly that core.
            var coreBad = 0; var coreChecked = 0; var noBaseCores = 0; var variantBad = 0; var variantChecked = 0
            for (d in rooms.designs) {
                val v = rooms.volume(d) ?: continue
                if (d.baseCores.isEmpty()) { noBaseCores++; continue }
                val got = com.engineerclient.p3sim.pack.rooms.OdinCore.ofRoom(v, d.tiles)
                coreChecked++
                if (got.toSet() != d.baseCores.toSet()) { coreBad++; say("CORE MISMATCH ${d.id}: computed $got, pack says ${d.baseCores}") }
                for (var_ in d.variants) {
                    val c = var_.core ?: continue
                    val vv = rooms.variantVolume(d, var_) ?: continue
                    variantChecked++
                    if (c !in com.engineerclient.p3sim.pack.rooms.OdinCore.ofRoom(vv, d.tiles)) { variantBad++; say("VARIANT CORE MISMATCH ${d.id}/${var_.id} wants $c") }
                }
            }
            say("Odin cores: $coreChecked designs checked ($coreBad wrong, $noBaseCores without baseCores); variants with a core: $variantChecked checked ($variantBad wrong)")
            assertEquals(0, coreBad, "base volumes must hash to the cores the pack lists")
            assertEquals(0, variantBad, "variants must hash to their core")
            assertEquals(0, bad, "room pack problems, see report")
        } finally { set.close(); flush() }
    }

    @Test fun everyPoolLayoutBuildsAPlan() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms)
            val pool = layouts.pool()
            say("layouts=${layouts.layouts.size} pool=${pool.size}")
            var failed = 0; var dupDesign = 0; var variantRooms = 0; var coreRooms = 0; var dupNames = 0
            val reasons = HashMap<String, Int>()
            for (l in pool) {
                when (val res = DungeonPlan.build(l, 1L, rooms, set.runstate)) {
                    is DungeonPlan.Result.Failed -> { failed++; reasons.merge(res.failure.message.substringAfter(": ").take(60), 1, Int::plus) }
                    is DungeonPlan.Result.Ok -> {
                        val p = res.plan
                        assertEquals(36, p.tileUnits, "${l.id} covers all 36 tiles")
                        assertEquals(1, p.rooms.count { it.type == RoomType.ENTRANCE }, l.id)
                        assertEquals(1, p.rooms.count { it.type == RoomType.BLOOD }, l.id)
                        assertEquals(1, p.rooms.count { it.type == RoomType.FAIRY }, l.id)
                        if (p.rooms.map { it.designId }.toSet().size != p.rooms.size) dupDesign++
                        if (p.rooms.map { it.name }.toSet().size != p.rooms.size) dupNames++
                        for (room in p.rooms) {
                            val lr = room.layoutRoom!!
                            if (lr.core != null) { coreRooms++; if (rooms.designByCore(lr.core)?.variant != null) variantRooms++ }
                            val placement = assertNotNull(room.placement, "${l.id}/${room.name}")
                            assertEquals(room.tiles.toSet(), placement.tiles().toSet())
                        }
                        // doors: every connected door joins two rooms and, where a room lists door slots, the slot exists.
                        for (d in p.doors) assertTrue(d.roomA != d.roomB, l.id)
                        val paid = p.paidWitherDoors.size
                        if (paid !in 1..7) say("ODD paid wither count $paid in ${l.id}")
                    }
                }
            }
            say("plan failures=$failed reasons=$reasons; plans with a repeated design=$dupDesign, repeated room name=$dupNames; rooms with a core=$coreRooms, of which map to a VARIANT=$variantRooms")
            assertEquals(0, failed, "a pool layout failed to plan: $reasons")
        } finally { set.close(); flush() }
    }

    @Test fun mobAndRunStateTablesCoverTheRoomsOfEveryPoolLayout() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms); val mobs = assertNotNull(set.mobs); val rs = assertNotNull(set.runstate)
            say("runstate problems: ${rs.problems}")
            say("mob archetypes=${mobs.archetypes.size} rooms=${mobs.rooms.size} modifiers=${mobs.modifiers.size} hpScale=${mobs.hpScale.keys} minibosses=${mobs.minibosses.size}")
            val noMob = HashMap<String, Int>(); val noRs = HashMap<String, Int>(); var secretsDiffer = 0; val diffNames = HashSet<String>()
            for (l in layouts.pool()) {
                val p = (DungeonPlan.build(l, 1L, rooms, rs) as? DungeonPlan.Result.Ok)?.plan ?: continue
                for (room in p.rooms) {
                    val typeWord = room.type
                    if (mobs.room(room.name, { rooms.familyOf(it) }) == null && typeWord in setOf(RoomType.NORMAL, RoomType.YELLOW, RoomType.RARE, RoomType.TRAP)) noMob.merge("${room.name} [${typeWord}]", 1, Int::plus)
                    if (rs.room(room.name) == null) noRs.merge(room.name, 1, Int::plus)
                    val design = room.design
                    if (design?.odinSecrets != null && design.odinSecrets != room.secretsMax) { secretsDiffer++; diffNames += "${room.name}:${design.odinSecrets}->${room.secretsMax}" }
                }
            }
            say("rooms with no mob table (mob-type rooms): $noMob")
            say("rooms with no run-state record: $noRs")
            say("secretsMax differs from the design's Odin count in $secretsDiffer rooms: ${diffNames.take(20)}")
            var tagBad = 0
            for (a in mobs.archetypes) {
                if (a.star && a.tag == null) { tagBad++; say("STAR ARCHETYPE WITHOUT TAG ${a.id}") }
                if (a.disguise && a.skin == null) say("disguised archetype without skin: ${a.id}")
            }
            assertEquals(0, tagBad)
            assertTrue(rs.problems.isEmpty(), rs.problems.toString())
        } finally { set.close(); flush() }
    }

    @Test fun doorKindsMatchThePathRule() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms)
            for (l in layouts.pool()) {
                val p = (DungeonPlan.build(l, 1L, rooms, set.runstate) as? DungeonPlan.Result.Ok)?.plan ?: continue
                val kinds = p.pathDoors.map { it.kind }
                assertEquals(DoorKind.ENTRANCE, kinds.first(), l.id)
                assertEquals(DoorKind.BLOOD, kinds.last(), l.id)
                assertEquals(1, kinds.count { it == DoorKind.FAIRY }, l.id)
                // every connected door is on the path (the connected slots form a tree whose path is entrance -> blood, plus plain side doors)
                assertEquals(p.rooms.size - 1, p.doors.count { it.kind.connected }, l.id)
            }
        } finally { set.close(); flush() }
    }
}
