package com.engineerclient.p3sim.blood

import com.engineerclient.p3sim.clear.blood.BloodGeometry
import com.engineerclient.p3sim.clear.blood.HeadTextures
import com.engineerclient.p3sim.clear.blood.WatcherEntity
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.rooms.Placement
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Optional: the stations and the exit alcove of the REAL blood builds, when ADS_REAL_PACKS names the folder with the pack files (the
 * same switch as `RealPackTest`). Reads the pack, copies nothing into the repo.
 */
class RealBloodPackTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    @Test fun everyBloodBuildHasThirtyStationsAndAnAlcoveWithAnObsidianFrameInEveryRotation() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        val rooms = assertNotNull(set.rooms)
        val builds = rooms.designsOfFamily("Blood")
        assertTrue(builds.isNotEmpty())
        for (d in builds) {
            val v = assertNotNull(rooms.volume(d), d.id)
            assertEquals(69, BloodGeometry.standY(v), "${d.id} floor")
            // The alcove at the back of the room: obsidian at x13 and x17 (y69..72) and along y68 and y73 (x14..16), around an open 3 x 4
            // doorway at z29. A build recorded after the fight still has the portal blocks standing in it (the room clears them on opening).
            fun open(x: Int, y: Int) = v.isAir(x, y, BloodGeometry.EXIT_Z) || v.stateAt(x, y, BloodGeometry.EXIT_Z).startsWith("minecraft:nether_portal")
            for (y in 69..72) for (x in 14..16) assertTrue(open(x, y), "${d.id} portal cell ($x, $y) is open")
            for (y in 69..72) for (x in listOf(13, 17)) assertEquals("minecraft:obsidian", v.stateAt(x, y, BloodGeometry.EXIT_Z), "${d.id} frame ($x, $y)")
            for (x in 14..16) for (y in listOf(68, 73)) assertEquals("minecraft:obsidian", v.stateAt(x, y, BloodGeometry.EXIT_Z), "${d.id} frame ($x, $y)")
            for (rot in Rot.entries) {
                val p = Placement.of(d.shape, d.w, d.d, rot, listOf(Tile(2, 5)))
                val slots = BloodGeometry.worldSlots(v, p)
                assertEquals(30, slots.size, "${d.id} $rot")
                val site = BloodGeometry.exitSite(p, 69)
                assertEquals(12, site.cells.size)
                for (c in site.cells) {
                    val (cx, cz) = assertNotNull(p.toCanonical(c.x, c.z))
                    assertTrue(v.isAir(cx, c.y, cz) || v.stateAt(cx, c.y, cz).startsWith("minecraft:nether_portal"), "${d.id} $rot portal cell $c is open")
                }
            }
        }
        set.close()
    }

    @Test fun theWatchersAndTheEyesHeadsAreInThePackByRole() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        val rooms = assertNotNull(set.rooms)
        assumeTrue(rooms.textures.any { it.role == WatcherEntity.ROLE_WATCHER }, "pack built without packs/rooms/add_blood_textures.py")
        for (role in listOf(WatcherEntity.ROLE_WATCHER, WatcherEntity.ROLE_EYE)) {
            val tex = assertNotNull(HeadTextures.resolve(role, rooms.textures), role)
            assertTrue(tex.value.isNotBlank())
        }
        assertTrue(HeadTextures.resolve(WatcherEntity.ROLE_WATCHER, rooms.textures)!!.value != HeadTextures.resolve(WatcherEntity.ROLE_EYE, rooms.textures)!!.value)
        set.close()
    }
}
