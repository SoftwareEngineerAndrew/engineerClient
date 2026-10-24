package com.devgineerclient.posepov

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue
import net.minecraft.world.level.block.state.BlockState

/**
 * Light levels (0-15) over a box of known blocks, worked out the game's way so a room or the arena is lit without its chunks:
 * sky light falls straight down through blocks that let it (air, glass) and spreads from there, block light spreads from what
 * glows (torches, glowstone, sea lanterns), each losing at least one level per block and more through blocks that dampen it.
 * Outside the box is open sky (the gaps between dungeon rooms are, and so is everything above a room or the arena), so sky light
 * also comes in at the sides. [get] is the brighter of the two, which is all the flat shading needs.
 */
class LightGrid private constructor(private val w: Int, private val h: Int, private val d: Int, private val light: ByteArray) {

    /** The light at box cell ([x], [y], [z]); outside the box, open sky. */
    fun get(x: Int, y: Int, z: Int): Int =
        if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) 15 else light[(y * d + z) * w + x].toInt()

    companion object {
        /** Lights the [w] x [h] x [d] box whose block at box cell (x, y, z) is [at]. */
        fun of(w: Int, h: Int, d: Int, at: (Int, Int, Int) -> BlockState): LightGrid {
            val n = w * h * d
            val damp = ByteArray(n); val skyDown = BooleanArray(n); val emit = ByteArray(n)
            for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
                val s = at(x, y, z); val i = (y * d + z) * w + x
                damp[i] = s.lightDampening.toByte(); skyDown[i] = s.propagatesSkylightDown(); emit[i] = s.lightEmission.toByte()
            }
            fun idx(x: Int, y: Int, z: Int) = (y * d + z) * w + x

            val sky = ByteArray(n)
            val queue = IntArrayFIFOQueue()
            // Straight down from the open sky above, full strength while nothing stops it.
            for (z in 0 until d) for (x in 0 until w) {
                var y = h - 1
                while (y >= 0 && skyDown[idx(x, y, z)]) { sky[idx(x, y, z)] = 15; queue.enqueue(idx(x, y, z)); y-- }
            }
            // In from the open sides.
            for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
                if (x != 0 && z != 0 && x != w - 1 && z != d - 1) continue
                val i = idx(x, y, z)
                val v = 15 - maxOf(1, damp[i].toInt())
                if (v > sky[i]) { sky[i] = v.toByte(); queue.enqueue(i) }
            }
            spread(sky, damp, queue, w, h, d)

            val block = ByteArray(n)
            for (i in 0 until n) if (emit[i] > 0) { block[i] = emit[i]; queue.enqueue(i) }
            spread(block, damp, queue, w, h, d)

            for (i in 0 until n) if (block[i] > sky[i]) sky[i] = block[i]
            return LightGrid(w, h, d, sky)
        }

        private fun spread(light: ByteArray, damp: ByteArray, queue: IntArrayFIFOQueue, w: Int, h: Int, d: Int) {
            val plane = w * d
            while (!queue.isEmpty) {
                val i = queue.dequeueInt()
                val v = light[i].toInt()
                if (v <= 1) continue
                val x = i % w; val z = (i / w) % d; val y = i / plane
                fun to(j: Int) {
                    val nv = v - maxOf(1, damp[j].toInt())
                    if (nv > light[j]) { light[j] = nv.toByte(); queue.enqueue(j) }
                }
                if (x > 0) to(i - 1); if (x < w - 1) to(i + 1)
                if (z > 0) to(i - w); if (z < d - 1) to(i + w)
                if (y > 0) to(i - plane); if (y < h - 1) to(i + plane)
            }
        }
    }
}
