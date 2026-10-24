package com.devgineerclient.posepov

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The world as Pose POV draws it: one flat-coloured mesh per 16³ section, read straight off the
 * client's loaded chunks. A block is its map colour, shaded by face; a face is drawn only where the
 * block next to it doesn't hide it. No textures, no lighting, no transparency.
 *
 * Everything here runs on the render thread except [dirty] and [forget], which the packet
 * listeners call off the network thread.
 */
object BlockMesh {
    class Section(val buffer: GpuBuffer?, val indexCount: Int)

    private val sections = HashMap<Long, Section>()
    /** Sections whose blocks changed, and when: rebuilt [DIRTY_DELAY_MS] later, once the packet has been applied. */
    private val dirtyAt = ConcurrentHashMap<Long, Long>()
    private val forgotten = ConcurrentLinkedQueue<Long>()

    private const val DIRTY_DELAY_MS = 100L
    private const val MAX_SECTIONS = 4096
    private const val BUILD_BUDGET_NS = 4_000_000L

    private val SHADE = floatArrayOf(0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f) // down, up, north, south, west, east
    private val DX = intArrayOf(0, 0, 0, 0, -1, 1)
    private val DY = intArrayOf(-1, 1, 0, 0, 0, 0)
    private val DZ = intArrayOf(0, 0, -1, 1, 0, 0)

    fun get(key: Long): Section? = sections[key]

    fun dirty(pos: BlockPos) {
        val now = System.currentTimeMillis()
        val sx = pos.x shr 4; val sy = pos.y shr 4; val sz = pos.z shr 4
        val lx = pos.x and 15; val ly = pos.y and 15; val lz = pos.z and 15
        dirtyAt[SectionPos.asLong(sx, sy, sz)] = now
        // A neighbour's face against this block may have just appeared or gone.
        if (lx == 0) dirtyAt[SectionPos.asLong(sx - 1, sy, sz)] = now
        if (lx == 15) dirtyAt[SectionPos.asLong(sx + 1, sy, sz)] = now
        if (ly == 0) dirtyAt[SectionPos.asLong(sx, sy - 1, sz)] = now
        if (ly == 15) dirtyAt[SectionPos.asLong(sx, sy + 1, sz)] = now
        if (lz == 0) dirtyAt[SectionPos.asLong(sx, sy, sz - 1)] = now
        if (lz == 15) dirtyAt[SectionPos.asLong(sx, sy, sz + 1)] = now
    }

    fun dirtyColumn(cx: Int, cz: Int, minSy: Int, maxSy: Int) {
        val now = System.currentTimeMillis()
        for (sy in minSy..maxSy) dirtyAt[SectionPos.asLong(cx, sy, cz)] = now
    }

    fun forget(cx: Int, cz: Int) { forgotten.add(SectionPos.asLong(cx, 0, cz)) }

    /**
     * Builds what [wanted] (nearest first) is missing or has gone stale, for at most
     * [BUILD_BUDGET_NS] this frame.
     */
    fun update(level: ClientLevel, wanted: List<Long>) {
        while (true) {
            val col = forgotten.poll() ?: break
            val cx = SectionPos.x(col); val cz = SectionPos.z(col)
            sections.keys.filter { SectionPos.x(it) == cx && SectionPos.z(it) == cz }.forEach { free(it) }
        }
        val start = System.nanoTime()
        val now = System.currentTimeMillis()
        for (key in wanted) {
            val stale = dirtyAt[key]?.let { now - it >= DIRTY_DELAY_MS } == true
            if (key in sections && !stale) continue
            if (key !in sections && dirtyAt[key]?.let { now - it < DIRTY_DELAY_MS } == true) continue
            val sx = SectionPos.x(key); val sz = SectionPos.z(key)
            if (!level.hasChunk(sx, sz)) continue
            dirtyAt.remove(key)
            free(key)
            sections[key] = build(level, sx, SectionPos.y(key), sz)
            if (System.nanoTime() - start > BUILD_BUDGET_NS) break
        }
        if (sections.size > MAX_SECTIONS) {
            val keep = wanted.toHashSet()
            sections.keys.filter { it !in keep }.forEach { free(it) }
        }
    }

    private fun free(key: Long) { sections.remove(key)?.buffer?.close() }

    fun clear() {
        sections.values.forEach { it.buffer?.close() }
        sections.clear(); dirtyAt.clear(); forgotten.clear()
    }

    private fun build(level: ClientLevel, sx: Int, sy: Int, sz: Int): Section {
        if (sy < level.minSectionY || sy > level.maxSectionY) return Section(null, 0)
        val section = level.getChunk(sx, sz).sections[level.getSectionIndexFromSectionY(sy)]
        if (section.hasOnlyAir()) return Section(null, 0)
        val format = DefaultVertexFormat.POSITION_COLOR
        val pos = BlockPos.MutableBlockPos()
        val near = BlockPos.MutableBlockPos()
        ByteBufferBuilder(format.vertexSize * 4 * 1024).use { bytes ->
            val bb = BufferBuilder(bytes, PrimitiveTopology.QUADS, format)
            for (ly in 0..15) for (lz in 0..15) for (lx in 0..15) {
                val state = section.getBlockState(lx, ly, lz)
                if (state.isAir) continue
                val x = (sx shl 4) + lx; val y = (sy shl 4) + ly; val z = (sz shl 4) + lz
                pos.set(x, y, z)
                val rgb = state.getMapColor(level, pos).col
                if (rgb == 0) continue
                for (f in 0..5) {
                    near.set(x + DX[f], y + DY[f], z + DZ[f])
                    val inside = lx + DX[f] in 0..15 && ly + DY[f] in 0..15 && lz + DZ[f] in 0..15
                    val other = if (inside) section.getBlockState(lx + DX[f], ly + DY[f], lz + DZ[f]) else level.getBlockState(near)
                    if (other.isSolidRender) continue
                    face(bb, f, x.toFloat(), y.toFloat(), z.toFloat(), shade(rgb, SHADE[f]))
                }
            }
            val data = bb.build() ?: return Section(null, 0)
            data.use {
                return Section(RenderSystem.getDevice().createBuffer({ "devgineerclient pose pov section" }, GpuBuffer.USAGE_VERTEX, it.vertexBuffer()), it.drawState().indexCount())
            }
        }
    }

    private fun shade(rgb: Int, k: Float): Int {
        val r = ((rgb shr 16 and 255) * k).toInt(); val g = ((rgb shr 8 and 255) * k).toInt(); val b = ((rgb and 255) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun face(bb: BufferBuilder, f: Int, x: Float, y: Float, z: Float, c: Int) {
        val X = x + 1; val Y = y + 1; val Z = z + 1
        when (f) {
            0 -> { v(bb, x, y, z, c); v(bb, X, y, z, c); v(bb, X, y, Z, c); v(bb, x, y, Z, c) }
            1 -> { v(bb, x, Y, z, c); v(bb, x, Y, Z, c); v(bb, X, Y, Z, c); v(bb, X, Y, z, c) }
            2 -> { v(bb, x, y, z, c); v(bb, x, Y, z, c); v(bb, X, Y, z, c); v(bb, X, y, z, c) }
            3 -> { v(bb, x, y, Z, c); v(bb, X, y, Z, c); v(bb, X, Y, Z, c); v(bb, x, Y, Z, c) }
            4 -> { v(bb, x, y, z, c); v(bb, x, y, Z, c); v(bb, x, Y, Z, c); v(bb, x, Y, z, c) }
            else -> { v(bb, X, y, z, c); v(bb, X, Y, z, c); v(bb, X, Y, Z, c); v(bb, X, y, Z, c) }
        }
    }

    private fun v(bb: BufferBuilder, x: Float, y: Float, z: Float, c: Int) { bb.addVertex(x, y, z).setColor(c) }
}
