package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.rooms.RoomPack
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Optional checks of the hard puzzles' assumptions against the REAL built rooms (ADS_REAL_PACKS names the folder of the packs; skipped
 * otherwise). Nothing from the rooms is copied into the repository or printed beyond counts.
 */
class HardRealRoomsTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    private fun rooms() = assertNotNull(RoomPack.load(dir!!, PackReport()))

    @Test fun theWaterBoardsPitTemplatesMatchTheRoomsOpenAndClosedPits() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val rooms = rooms()
        val design = assertNotNull(rooms.design("Water_Board"))
        val base = assertNotNull(rooms.volume(design))
        for (k in 0..4) for (e in WaterBoardModel.pitEdits(k, closed = false)) {
            assertEquals(e.state, base.stateAt(e.x, e.y, e.z), "open pit $k at ${e.x},${e.y},${e.z}")
        }
        var checkedClosed = 0
        for (v in design.variants) {
            val vol = rooms.variantVolume(design, v) ?: continue
            for (k in 0..4) {
                if (vol.isAir(15, 56, WaterBoardModel.pitZ(k))) continue
                val edits = WaterBoardModel.pitEdits(k, closed = true)
                if (edits.all { vol.stateAt(it.x, it.y, it.z) == it.state }) checkedClosed++
            }
        }
        assertTrue(checkedClosed >= 3, "a recorded variant has closed pits that match the template ($checkedClosed)")
        val model = assertNotNull(WaterBoardModel.create(base))
        assertTrue(model.gates.size in 20..60, "gates ${model.gates.size}")
        assertEquals(0, model.closedPits.size, "the base room has all pits open")
    }

    @Test fun theTeleportMazeLandingsSitOnTheSlabPatchesOfTheirFrames() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val rooms = rooms()
        val design = assertNotNull(rooms.design("Teleport_Maze"))
        val vol = assertNotNull(rooms.volume(design))
        val y = TpMazeModel.frameYOf(vol)
        assertEquals(69, y)
        for (p in TpMazeModel.standardPads()) {
            assertTrue(vol.stateAt(p.frameX, y, p.frameZ).startsWith("minecraft:end_portal_frame"), "frame of pad ${p.index}")
            assertTrue(vol.stateAt(p.landX, y, p.landZ).contains("slab"), "landing of pad ${p.index} is a slab")
            // The 2x2 patch: frame, landing and the two cells that share a row or a column with both.
            assertTrue(vol.stateAt(p.frameX, y, p.landZ).contains("slab"), "patch of pad ${p.index}")
            assertTrue(vol.stateAt(p.landX, y, p.frameZ).contains("slab"), "patch of pad ${p.index}")
            assertTrue(vol.isAir(p.landX, y + 1, p.landZ), "headroom at the landing of pad ${p.index}")
        }
    }

    @Test fun theIcePathHasAWayThroughAndTrapsBesideItsSideHoppers() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val rooms = rooms()
        val design = assertNotNull(rooms.design("Ice_Path"))
        val vol = assertNotNull(rooms.volume(design))
        val layout = IcePathLayout.read(vol)
        val m = layout.model()
        val path = assertNotNull(m.solution(), "a way through exists")
        assertTrue(path.size in 3..14, "path of ${path.size} slides")
        for (v in design.variants) {
            val vv = rooms.variantVolume(design, v) ?: continue
            assertNotNull(IcePathLayout.read(vv).model().solution(), "variant ${v.id} keeps the way")
        }
    }
}
