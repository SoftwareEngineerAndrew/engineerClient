package com.devgineerclient.posepov

import com.engineerclient.dungeonpack.pack.DungeonFrame
import com.engineerclient.dungeonpack.pack.PackReport
import com.engineerclient.dungeonpack.pack.rooms.Placement
import com.engineerclient.dungeonpack.pack.rooms.RoomPack
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Every design of the bundled pack, at every turn, named from the strip of its first tile a single chunk column shows. */
class RoomEdgesTest {
    @Test fun designsAreNamedFromAnEdgeStrip() {
        val pack = assertNotNull(RoomPack.load(Path.of("src/resources/assets/engineerclient/dungeonpack/packs"), PackReport()))
        val ds = pack.designs
        val (hs, ps) = RoomEdges.build(pack, ds)
        var right = 0; var wrong = 0; var none = 0
        val misses = ArrayList<String>()
        for (d in ds) {
            val vol = pack.volume(d) ?: continue
            val rots = if (d.type.equals("fairy", true)) listOf(com.engineerclient.dungeonpack.pack.Rot.SOUTH) else d.rotations.ifEmpty { Placement.legalRotations(d.shape) }
            for (rot in rots) {
                val tiles = Placement.tilesFor(d.shape, rot, 1, 1)
                val p = runCatching { d.place(rot, tiles) }.getOrNull() ?: continue
                // A 16-wide strip on the west side of the room's first tile, every other column.
                val t = tiles[0]
                val x0 = DungeonFrame.ORIGIN + t.x * 32; val z0 = DungeonFrame.ORIGIN + t.z * 32
                val cols = ArrayList<IntArray>()
                for (x in x0..x0 + 14 step 2) for (z in z0..z0 + 30 step 2) {
                    val (cx, cz) = p.toCanonical(x, z) ?: continue
                    cols += intArrayOf(x, z, RoomEdges.hashOf(vol, cx, cz))
                }
                val f = RoomEdges.identify(cols, pack, hs, ps, ds) { false }
                when {
                    f == null -> { none++; misses += "${d.id} $rot none" }
                    f.design.id == d.id && f.rot == rot && f.tiles.toSet() == tiles.toSet() -> right++
                    f.design.name == d.name && f.tiles.toSet() == tiles.toSet() -> right++
                    else -> { wrong++; misses += "${d.id} $rot -> ${f.design.id} ${f.rot} ${f.tiles}" }
                }
            }
        }
        println("edges: ${hs.size} columns indexed; $right right, $wrong wrong, $none not named")
        misses.take(40).forEach { println("  $it") }
        assertTrue(wrong == 0, "$wrong named wrong")
    }
}
