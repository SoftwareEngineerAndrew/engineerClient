package com.devgineerclient.posepov

import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomPack
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Pieces kept unturned and turned as they're read land where the room pack places the room's cells, facing the right way. */
class MeshTurnTest {
    @Test fun turnedCellsLandWherePlacementPutsThem() {
        val pack = assertNotNull(RoomPack.load(Path.of("src/resources/assets/engineerclient/ads/packs"), PackReport()))
        var checked = 0
        for (d in pack.designs) for (rot in d.rotations.ifEmpty { Placement.legalRotations(d.shape) }) {
            val tiles = Placement.tilesFor(d.shape, rot, 1, 1)
            val p = runCatching { d.place(rot, tiles) }.getOrNull() ?: continue
            for (x in 0 until d.w step 7) for (z in 0 until d.d step 5) {
                val wp = p.toWorld(x, 0, z)
                // The cell's middle, turned, is the placed cell's middle.
                assertEquals(wp.x - p.originX + 0.5f, MeshCodec.turnX(rot, x + 0.5f, z + 0.5f, d.w, d.d), "${d.id} $rot x")
                assertEquals(wp.z - p.originZ + 0.5f, MeshCodec.turnZ(rot, x + 0.5f, z + 0.5f, d.w, d.d), "${d.id} $rot z")
                // A face's way, turned, is the way from the cell to its turned neighbour.
                for ((dir, dx, dz) in listOf(Triple(2, 0, -1), Triple(3, 0, 1), Triple(4, -1, 0), Triple(5, 1, 0))) {
                    val n = p.toWorld(x + dx, 0, z + dz)
                    val want = when { n.z < wp.z -> 2; n.z > wp.z -> 3; n.x < wp.x -> 4; else -> 5 }
                    assertEquals(want, MeshCodec.turn(dir, rot), "${d.id} $rot dir $dir")
                }
                checked++
            }
        }
        println("turn: $checked cells checked")
    }
}
