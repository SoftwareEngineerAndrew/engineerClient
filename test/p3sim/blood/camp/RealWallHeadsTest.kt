package com.engineerclient.p3sim.blood.camp

import com.engineerclient.p3sim.clear.blood.HeadTextures
import com.engineerclient.p3sim.clear.blood.camp.Vec3
import com.engineerclient.p3sim.clear.blood.camp.WallClass
import com.engineerclient.p3sim.clear.blood.camp.WallLook
import com.engineerclient.p3sim.clear.blood.camp.WallPlan
import com.engineerclient.p3sim.pack.Packs
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Optional: the wall heads of the Watcher's camp against the REAL rooms pack, when ADS_REAL_PACKS names the folder with the pack files
 * (the same switch as `RealPackTest`). The textures live in the local pack only; a pack built without them is skipped.
 */
class RealWallHeadsTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    @Test fun everyHeadOfAFullWallHasATextureInThePack() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        val rooms = assertNotNull(set.rooms)
        assumeTrue(rooms.textures.any { it.role == WallLook.GIANT }, "pack built without the wall heads (packs/rooms/add_blood_textures.py)")
        val look = WallLook.of(rooms.textures)
        val wall = WallPlan.assign((0 until 30).map { Vec3(it.toDouble(), 70.0, 0.0) }, Random(5))
        val rng = Random(1)
        val keys = wall.associateWith { look.keyFor(it, rng) }
        assertTrue(keys.none { it.value == HeadTextures.PLAIN }, "every head of a full wall is dressed")
        val values = keys.mapValues { assertNotNull(HeadTextures.resolve(it.value, rooms.textures), it.value).value }
        assertTrue(values.values.all { it.isNotBlank() })
        // Each of the 17 standard mobs, the giant, the four bosses and the soul wear a texture of their own.
        val own = values.filter { it.key.cls != WallClass.DECOR }.values
        assertEquals(own.size, own.toSet().size)
        assertEquals(30 - WallPlan.DECOR_PER_30, own.size)
        set.close()
    }
}
