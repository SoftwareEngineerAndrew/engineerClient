package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.pack.layouts.LayoutPack
import com.engineerclient.p3sim.pack.mobs.MobPack
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.pack.runstate.RunStatePack
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Optional check against the data-format designer's SAMPLE packs (real, anonymised, kept outside this repo). Runs only when
 * the environment variable ADS_SAMPLE_PACKS names the folder that holds them; otherwise it is skipped. It never copies any
 * of that data into the repo. The numbers asserted are the ones the sample is documented to have (FORMAT-rooms 12).
 */
class SamplePackTest {
    private val dir: Path? = System.getenv("ADS_SAMPLE_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    @Test fun roomsSample() {
        assumeTrue(dir != null, "ADS_SAMPLE_PACKS not set")
        val r = PackReport()
        val pack = assertNotNull(RoomPack.load(dir!!, r), r.errors().toString())
        assertEquals(2, pack.designs.size)
        val admin = pack.design("Admin")!!
        assertEquals(31, admin.w); assertEquals(47, admin.h); assertEquals(66, admin.y0)
        val v = assertNotNull(pack.volume(admin))
        assertEquals(28185, v.nonAirCount())
        assertEquals(1, admin.variants.size)
        val vv = assertNotNull(pack.variantVolume(admin, admin.variants[0]))
        assertTrue(vv.w == 31)
        val dino = pack.design("Dino_Site")!!
        assertEquals(130928, assertNotNull(pack.volume(dino)).nonAirCount())
        assertEquals(3, dino.tiles.size); assertEquals("L", dino.shape)
        assertEquals(4, dino.secrets.size)
        assertEquals(34, admin.blockEntities.size); assertEquals(207, dino.blockEntities.size)
        assertEquals(518379920, pack.designByCore(518379920)!!.let { it.core })
        pack.close()
    }

    @Test fun layoutsSample() {
        assumeTrue(dir != null, "ADS_SAMPLE_PACKS not set")
        val r = PackReport()
        val pack = assertNotNull(LayoutPack.load(dir!!, null, r), r.errors().toString())
        assertEquals(3, pack.layouts.size)
        val l = pack.layouts.first()
        assertEquals(21, l.rooms.size)
        assertTrue(l.doorsComplete)
        assertEquals(7, l.path!!.size)
        assertEquals(4, l.keys.size)
        assertEquals(-41 to -121, l.doorSpot('E', 4, 2))
        for (room in l.rooms) assertTrue(room.rot != null, room.name)
    }

    @Test fun mobsSample() {
        assumeTrue(dir != null, "ADS_SAMPLE_PACKS not set")
        val r = PackReport()
        val pack = assertNotNull(MobPack.load(dir!!, r), r.errors().toString())
        assertEquals(3, pack.archetypes.size)
        assertEquals(2, pack.rooms.size)
        assertTrue(pack.byName("Withermancer")!!.tag!!.idOffset == 3)
        assertTrue(pack.playerDamage!!.melee.containsKey("F7"))
    }

    @Test fun runstateSample() {
        assumeTrue(dir != null, "ADS_SAMPLE_PACKS not set")
        val r = PackReport()
        val pack = assertNotNull(RunStatePack.load(dir!!, r), r.errors().toString())
        assertTrue(pack.rooms.containsKey("Dino Site"))
        assertEquals(14, pack.doors!!.barrierTicks)
        assertEquals(37, pack.score!!.roomsTable.size)
        assertTrue(pack.lag!!.tiers.containsKey("F7"))
        println("run-state sample problems: " + pack.problems)
    }
}
