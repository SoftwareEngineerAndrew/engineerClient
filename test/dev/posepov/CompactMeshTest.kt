package com.devgineerclient.posepov

import com.engineerclient.p3sim.pack.DungeonFrame
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Greedy merging keeps every face where it was with its texture repeated as before; portal edges are one number from both sides. */
class CompactMeshTest {
    private val up = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f))

    /** An up face over cell (x, z) at height y, the whole texture across it. */
    private fun upFace(q: CompactMesh.Quads, x: Int, y: Int, z: Int, sprite: Int = 3, colour: Int = -1, uvTurn: Int = 0) {
        val i = q.add(sprite, 1)
        for (k in 0 until 4) {
            val (dx, dz) = up[k].let { it[0] to it[1] }
            q.pos[i * 12 + k * 3] = x + dx; q.pos[i * 12 + k * 3 + 1] = y.toFloat(); q.pos[i * 12 + k * 3 + 2] = z + dz
            val uv = up[(k + uvTurn) % 4]
            q.uv[i * 8 + k * 2] = uv[0]; q.uv[i * 8 + k * 2 + 1] = uv[1]
            q.col[i * 4 + k] = colour
        }
    }

    private class V(val x: Float, val y: Float, val z: Float, val sprite: Int, val u: Float, val v: Float, val col: Int)
    private fun read(b: ByteBuffer): List<V> {
        val d = b.duplicate().order(b.order())
        return List(d.remaining() / CompactMesh.VERTEX) {
            fun p() = (d.short.toInt() and 0xFFFF) / 64f - 256f
            val x = p(); val y = p(); val z = p(); val s = d.short.toInt() and 0xFFFF
            val u = (d.short.toInt() and 0xFFFF) / 2048f; val v = (d.short.toInt() and 0xFFFF) / 2048f
            V(x, y, z, s, u, v, d.int)
        }
    }

    @Test fun floorMergesIntoOneQuadRepeatingTheTexture() {
        val q = CompactMesh.Quads()
        for (x in 0 until 4) for (z in 0 until 3) upFace(q, 10 + x, 70, 5 + z)
        val p = CompactMesh.pack(q, 8f, 4f)
        assertEquals(1, p.quads)
        assertEquals(1, p.counts[1])
        val vs = read(p.bytes)
        assertEquals(setOf(2f, 6f), vs.map { it.x }.toSet())
        assertEquals(setOf(1f, 4f), vs.map { it.z }.toSet())
        assertTrue(vs.all { it.y == 70f && it.sprite == 3 })
        // The texture across each cell as before: u, v step 1 per block from the same corner.
        for (v in vs) { assertEquals(v.x - 2f, v.u, 1e-3f); assertEquals(v.z - 1f, v.v, 1e-3f) }
    }

    @Test fun turnedTexturesStayTurned() {
        for (turn in 0 until 4) {
            val q = CompactMesh.Quads()
            for (x in 0 until 3) for (z in 0 until 2) upFace(q, x, 64, z, uvTurn = turn)
            val p = CompactMesh.pack(q)
            assertEquals(1, p.quads, "turn $turn")
            // Each corner's uv is what one face's corner had there, scaled by the run's length that way (3 along x, 2 along z).
            fun one(a: Int, b: Int): FloatArray = up[((0 until 4).first { up[it][0] == a.toFloat() && up[it][1] == b.toFloat() } + turn) % 4]
            val uAlongX = one(1, 0)[0] != one(0, 0)[0]
            for (v in read(p.bytes)) {
                val a = if (v.x > 1.5f) 1 else 0; val b = if (v.z > 1f) 1 else 0
                val want = one(a, b)
                assertEquals(want[0] * (if (uAlongX) 3 else 2), v.u, 1e-3f, "turn $turn u at $a $b")
                assertEquals(want[1] * (if (uAlongX) 2 else 3), v.v, 1e-3f, "turn $turn v at $a $b")
            }
        }
    }

    @Test fun differentColoursAndLongRunsSplit() {
        val q = CompactMesh.Quads()
        for (x in 0 until 20) upFace(q, x, 64, 0)
        upFace(q, 0, 64, 1, colour = 0x7F7F7F7F)
        upFace(q, 1, 64, 1)
        val p = CompactMesh.pack(q)
        // 20 long: 16 + 4 (u stays inside 16 bits); the odd colour alone; its neighbour alone (it can't join the row above: not a rect).
        assertEquals(4, p.quads)
    }

    @Test fun smallAndSlantedFacesGoToTheirGroups() {
        val q = CompactMesh.Quads()
        val i = q.add(1, 2)
        val small = floatArrayOf(0f, 0f, 0f, 0.25f, 0f, 0f, 0.25f, 0.25f, 0f, 0f, 0.25f, 0f)
        small.copyInto(q.pos, i * 12)
        val j = q.add(1, 6)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 0f).copyInto(q.pos, j * 12)
        val p = CompactMesh.pack(q)
        assertEquals(1, p.counts[7 + 2]); assertEquals(1, p.counts[6])
        assertEquals(-1, CompactMesh.groupDir(6)); assertEquals(2, CompactMesh.groupDir(9))
    }

    @Test fun brokenWallsMarkTheirEdgeFromEitherSide() {
        for (tx in 0..4) for (tz in 0..5) {
            assertEquals(PovCull.edge(tx, tz, 0), PovCull.edge(tx + 1, tz, 1))
            assertEquals(PovCull.edge(tz, tx, 2), PovCull.edge(tz, tx + 1, 3))
        }
        assertEquals(60, (0..4).flatMap { a -> (0..5).flatMap { b -> listOf(PovCull.edge(a, b, 0), PovCull.edge(b, a, 2)) } }.toSet().size)
        // A block gone from tile (1, 2)'s east wall, away from the door: that edge opens; one in the door doesn't.
        val gapX = DungeonFrame.tileMin(1) + 31 - DungeonFrame.ORIGIN
        for (dx in -2..2) {
            val b = BooleanArray(60)
            PovCull.Portals.mark(b, gapX + dx, DungeonFrame.tileMin(2) + 4 - DungeonFrame.ORIGIN, 80, 0)
            assertTrue(b[PovCull.edge(1, 2, 0)] && b.count { it } == 1, "dx $dx")
        }
        val door = BooleanArray(60)
        PovCull.Portals.mark(door, gapX, DungeonFrame.tileCentre(2) - DungeonFrame.ORIGIN, 70, 0)
        assertTrue(door.none { it })
        val far = BooleanArray(60)
        PovCull.Portals.mark(far, gapX + 6, DungeonFrame.tileMin(2) + 4 - DungeonFrame.ORIGIN, 80, 0)
        assertTrue(far.none { it })
    }
}
