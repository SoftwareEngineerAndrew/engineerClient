package com.engineerclient.p3sim.secrets

import com.engineerclient.p3sim.clear.secrets.SecretPlan
import com.engineerclient.p3sim.clear.secrets.SpawnRanges
import com.engineerclient.p3sim.pack.Packs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The measured spawn table: reading it, what the plan takes from it (invented sites), and the bundled file against the bundled rooms pack (no values printed). */
class SpawnRangesTest {
    private val invented = """
        {"format":"ads-secret-spawns","version":1,"defaults":{"item":9.5,"bat":8,"essence":7},"sites":[
        {"room":"R0","design":"R0","type":"bat","secret":"s3","pos":[14,73,9],"r":7.5,"n":40},
        {"room":"R0","design":"R0","type":"bat","secret":"s2","pos":[1,70,1],"r":3,"n":40},
        {"room":"R0","design":"R0","type":"essence","secret":"s4","pos":[15,70,15],"r":null,"spawns":"on an event, not on approach","n":40},
        {"room":"R0","design":"R0","type":"item","secret":null,"pos":[2,70,2],"r":4,"n":40}
        ]}
    """.trimIndent()

    @AfterEach fun cleanUp() { Packs.unload() }

    @Test fun readsSitesAndDefaultsAndSkipsSitesThePackHasNoIdFor() {
        val t = SpawnRanges.parse(invented)
        assertEquals(3, t.sites.size)
        assertEquals(7.5, t.of("R0", "s3")?.radius)
        assertNull(t.of("R0", "s4")!!.radius, "an event secret has no radius")
        assertNull(t.of("R1", "s3"))
        assertNull(t.of(null, "s3"))
        assertEquals(9.5, t.default("item"))
    }

    @Test fun thePlanGivesEachSiteItsRadiusAndABatItsSpawnSpot() {
        val sp = SecretPlan.build(SecretFixtures.standard(), Random(1), SpawnRanges.parse(invented))
        val room = sp.room(0)!!
        // Room 0 is SOUTH at tile (1,1): canonical (x, z) lands at (-168 + x, -168 + z).
        val bat = room.sites.first { it.id == "s3" }
        assertEquals(Triple(-154, 73, -159), Triple(bat.x, bat.y, bat.z), "the bat stands where it spawns, not at the pack's spot")
        assertEquals(7.5, bat.spawnRange)
        val item = room.sites.first { it.id == "s2" }
        assertNull(item.spawnRange, "a row of another type is not this secret's")
        assertEquals(Triple(-158, 70, -158), Triple(item.x, item.y, item.z))
        assertNull(room.sites.first { it.id == "s4" }.spawnRange)
        // Without a table nothing moves.
        val plain = SecretPlan.build(SecretFixtures.standard(), Random(1), SpawnRanges.EMPTY).room(0)!!.sites.first { it.id == "s3" }
        assertEquals(Triple(-156, 72, -156), Triple(plain.x, plain.y, plain.z))
        assertNull(plain.spawnRange)
    }

    @Test fun theBundledTableNamesRealSecretsOfTheBundledRoomsPack(@TempDir tmp: Path) {
        val table = SpawnRanges.bundled
        assertTrue(table.sites.size > 100, "sites: ${table.sites.size}")
        for (t in listOf("item", "bat", "essence")) assertNotNull(table.default(t), t)
        val set = Packs.load(tmp.resolve("config/engineerclient/ads/packs"))
        val rooms = assertNotNull(set.rooms)
        for ((key, site) in table.sites) {
            val (designId, secretId) = key.split('|')
            val design = assertNotNull(rooms.design(designId), "no design $designId")
            val secret = assertNotNull(design.secrets.firstOrNull { it.id == secretId }, "$designId has no $secretId")
            assertEquals(secret.type, site.type, key)
            site.radius?.let { assertTrue(it in 1.0..40.0, "$key: $it") }
        }
        set.close()
    }
}
