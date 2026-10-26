package com.devgineerclient.posepov

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.vertex.VertexFormat
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pose POV's geometry as it sits on the GPU: 16 bytes a corner instead of the game's 24, and flat runs of the same face merged
 * into one quad with the texture repeated across it (greedy meshing), so a wall of stone is a few quads, not hundreds.
 *
 * A corner: x y z (1/64 of a block, from -256) and the texture's number in the sprite table (four u16), u v across the texture
 * (1/2048 of it, so a merged face repeats it up to 32 times: two u16), the colour (shade, corner shading and tint baked in:
 * four bytes). The shader ([PoseShader], assets/engineerclient/shaders/core/pose_pov) looks the texture's place in the block
 * atlas up in [SpriteTable] and repeats it with fract(), sampling the mip level the unrepeated coordinates call for (a seam
 * never drops to the smallest mip). Fog fades the last stretch before the view distance into the sky.
 *
 * Quads are grouped so a frame can leave out what it doesn't need: faces of at least half a block by the way they face (down
 * up north south west east), the rest that aren't square to an axis, then the small ones the same way. Far away only the first
 * seven groups are drawn (level of detail: no torches, plants, fence posts, buttons out there).
 */
object CompactMesh {
    const val VERTEX = 16
    const val GROUPS = 14
    /** The groups drawn far away: the big faces (by side, then the rest). */
    const val FAR_GROUPS = 7
    private const val POS_SCALE = 64f
    private const val POS_OFFSET = 256f
    private const val UV_SCALE = 2048f
    private const val EPS = 1e-3f
    /** A merged face is at most this many cells each way (u v stay inside their 16 bits). */
    private const val MAX_RUN = 16
    /** Planes told apart to 1/128 of a block (a slab's top, a stair's step: faces on them merge too). */
    private const val PLANE_STEPS = 128f

    /** The side a group's faces look to (Direction order), or -1 when they look every way. */
    fun groupDir(g: Int): Int = when { g < 6 -> g; g == 6 || g == 13 -> -1; else -> g - 7 }

    // --- the sprite table ---------------------------------------------------------------------------------------------------

    /** Every block atlas sprite's place (u0 v0 and size) as a texel buffer, and each sprite's number in it. Render thread. */
    object SpriteTable {
        @Volatile private var index: java.util.IdentityHashMap<TextureAtlasSprite, Int>? = null
        @Volatile var buffer: GpuBuffer? = null; private set
        /** Bumped when the table is made again (an atlas change): numbers baked into what's on the GPU are stale. */
        @Volatile var generation = 0; private set

        /** The table, made when first wanted (and after an atlas change); null until the atlas can be read. */
        fun ensure(): Boolean {
            if (buffer != null && index != null) return true
            val sp = MeshCodec.sprites() ?: return false
            val list = sp.list
            val b = ByteBuffer.allocateDirect(maxOf(1, list.size) * 16).order(ByteOrder.nativeOrder())
            val idx = java.util.IdentityHashMap<TextureAtlasSprite, Int>(list.size * 2)
            for ((i, s) in list.withIndex()) { b.putFloat(s.u0).putFloat(s.v0).putFloat(s.u1 - s.u0).putFloat(s.v1 - s.v0); idx[s] = i }
            if (list.isEmpty()) b.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f)
            b.flip()
            buffer?.close()
            buffer = RenderSystem.getDevice().createBuffer({ "devgineerclient pose pov sprites" }, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, b)
            index = idx
            generation++
            return true
        }

        val ready get() = buffer != null && index != null

        /** A sprite's number (any thread, once [ensure] made the table); 0 for one not in it. */
        fun of(s: TextureAtlasSprite): Int = index?.get(s) ?: 0

        fun clear() { buffer?.close(); buffer = null; index = null }
    }

    // --- quads before packing -----------------------------------------------------------------------------------------------

    /** Quads as read: per quad its sprite number and the side it faces (0..5, 6 when none), per corner x y z, u v (of the sprite) and colour. */
    class Quads(capacity: Int = 256) {
        var n = 0; private set
        var sprite = IntArray(capacity); private set
        var dir = ByteArray(capacity); private set
        var pos = FloatArray(capacity * 12); private set
        var uv = FloatArray(capacity * 8); private set
        var col = IntArray(capacity * 4); private set

        fun clear() { n = 0 }

        /** Room for one more; its index (fill [pos], [uv], [col] at it). */
        fun add(spriteIndex: Int, side: Int): Int {
            if (n == sprite.size) {
                val c = n * 2
                sprite = sprite.copyOf(c); dir = dir.copyOf(c); pos = pos.copyOf(c * 12); uv = uv.copyOf(c * 8); col = col.copyOf(c * 4)
            }
            sprite[n] = spriteIndex; dir[n] = side.toByte()
            return n++
        }
    }

    /** What [pack] made: the bytes (native order) and the quads per group, the groups in the order written. */
    class Packed(val bytes: ByteBuffer, val counts: IntArray) { val quads get() = counts.sum() }

    // In-plane axes per side: A, B and the axis across (0 x, 1 y, 2 z).
    private val AXIS_A = intArrayOf(0, 0, 0, 0, 2, 2)
    private val AXIS_B = intArrayOf(2, 2, 1, 1, 1, 1)
    private val AXIS_N = intArrayOf(1, 1, 2, 2, 0, 0)


    /**
     * Merges and packs [q], every position less ([ox], 0, [oz]) (the frame it's drawn in). A flat face inside one cell, in
     * one colour, whose texture follows its position the way a block model's does by default (a whole block's side, a slab's
     * top or side, a stair's step), is merged with its like neighbours (same side, plane, shape in the cell, texture, colour
     * and texture turn) into the biggest rectangles going, along the ways it fills its cell; everything else is kept as it was.
     */
    fun pack(q: Quads, ox: Float = 0f, oz: Float = 0f): Packed {
        // Faces by what may merge: key -> their cells (a, b).
        class Run(val side: Int, val plane: Int, val sprite: Int, val colour: Int, val orient: Int, val pattern: IntArray, val frac: FloatArray) {
            val cells = HashSet<Long>()
            val fullA get() = frac[0] < EPS && frac[1] > 1 - EPS
            val fullB get() = frac[2] < EPS && frac[3] > 1 - EPS
        }
        val runs = HashMap<String, Run>()
        val keep = BooleanArray(q.n)
        val pattern = IntArray(8)
        val frac = FloatArray(4)
        val cell = IntArray(2)
        for (i in 0 until q.n) {
            val side = q.dir[i].toInt()
            if (side !in 0..5 || !cellFace(q, i, side, ox, oz, pattern, frac, cell)) { keep[i] = true; continue }
            val c = q.col[i * 4]
            if (q.col[i * 4 + 1] != c || q.col[i * 4 + 2] != c || q.col[i * 4 + 3] != c) { keep[i] = true; continue }
            val orient = orientation(q, i, pattern, frac)
            if (orient < 0) { keep[i] = true; continue }
            val n = AXIS_N[side]; val plane = Math.round((q.pos[i * 12 + n] - (if (n == 0) ox else if (n == 2) oz else 0f)) * PLANE_STEPS)
            val shape = "${Math.round(frac[0] * PLANE_STEPS)},${Math.round(frac[1] * PLANE_STEPS)},${Math.round(frac[2] * PLANE_STEPS)},${Math.round(frac[3] * PLANE_STEPS)}"
            val key = "$side|$plane|${q.sprite[i]}|$c|$orient|$shape"
            val run = runs.getOrPut(key) { Run(side, plane, q.sprite[i], c, orient, pattern.copyOf(), frac.copyOf()) }
            run.cells += (cell[0].toLong() shl 32) or (cell[1].toLong() and 0xFFFFFFFFL)
        }

        // Out, by group: merged rectangles and kept quads by their side and size.
        val groups = Array(GROUPS) { ArrayList<Out>() }
        fun add(side: Int, quad: Out) {
            val big = area(quad.pos, 0) >= 0.5f - EPS
            groups[if (side in 0..5) (if (big) side else 7 + side) else (if (big) 6 else 13)] += quad
        }
        for (i in 0 until q.n) if (keep[i]) add(q.dir[i].toInt(), quadOf(q, i, ox, oz))
        for (run in runs.values) for (rect in greedy(run.cells, if (run.fullA) MAX_RUN else 1, if (run.fullB) MAX_RUN else 1)) {
            add(run.side, merged(run.side, run.plane, run.sprite, run.colour, run.orient, run.pattern, run.frac, rect))
        }

        val total = groups.sumOf { it.size }
        val out = ByteBuffer.allocateDirect(maxOf(1, total) * 4 * VERTEX).order(ByteOrder.nativeOrder())
        val counts = IntArray(GROUPS)
        for (g in 0 until GROUPS) {
            counts[g] = groups[g].size
            for (quad in groups[g]) for (k in 0 until 4) {
                out.putShort(p16(quad.pos[k * 3])).putShort(p16(quad.pos[k * 3 + 1])).putShort(p16(quad.pos[k * 3 + 2])).putShort(quad.sprite.toShort())
                out.putShort(uv16(quad.uv[k * 2])).putShort(uv16(quad.uv[k * 2 + 1]))
                out.putInt(quad.col[k])
            }
        }
        out.flip()
        return Packed(out, counts)
    }

    private fun p16(v: Float) = ((v + POS_OFFSET) * POS_SCALE + 0.5f).toInt().coerceIn(0, 65535).toShort()
    private fun uv16(v: Float) = (v * UV_SCALE + 0.5f).toInt().coerceIn(0, 65535).toShort()

    private fun coord(q: Quads, i: Int, k: Int, axis: Int, ox: Float, oz: Float) =
        q.pos[i * 12 + k * 3 + axis] - (if (axis == 0) ox else if (axis == 2) oz else 0f)

    /** A quad as written, in the frame it is drawn in. */
    private class Out(val sprite: Int) { val pos = FloatArray(12); val uv = FloatArray(8); val col = IntArray(4) }

    private fun quadOf(q: Quads, i: Int, ox: Float, oz: Float): Out {
        val f = Out(q.sprite[i])
        for (k in 0 until 4) {
            f.pos[k * 3] = q.pos[i * 12 + k * 3] - ox; f.pos[k * 3 + 1] = q.pos[i * 12 + k * 3 + 1]; f.pos[k * 3 + 2] = q.pos[i * 12 + k * 3 + 2] - oz
            f.uv[k * 2] = q.uv[i * 8 + k * 2]; f.uv[k * 2 + 1] = q.uv[i * 8 + k * 2 + 1]
            f.col[k] = q.col[i * 4 + k]
        }
        return f
    }

    /** Area of the quad at [o] in [p] (half its diagonals' cross product). */
    private fun area(p: FloatArray, o: Int): Float {
        val ax = p[o + 6] - p[o]; val ay = p[o + 7] - p[o + 1]; val az = p[o + 8] - p[o + 2]
        val bx = p[o + 9] - p[o + 3]; val by = p[o + 10] - p[o + 4]; val bz = p[o + 11] - p[o + 5]
        val cx = ay * bz - az * by; val cy = az * bx - ax * bz; val cz = ax * by - ay * bx
        return Math.sqrt((cx * cx + cy * cy + cz * cz).toDouble()).toFloat() / 2f
    }

    /**
     * Whether quad [i] is a flat rectangle square to the grid, inside one cell (on any plane). Fills [cell] with that cell (a,
     * b), [frac] with where the rectangle is in it (a from, a to, b from, b to: 0..1) and [pattern] with each corner's side of
     * it (0 low, 1 high, a then b).
     */
    private fun cellFace(q: Quads, i: Int, side: Int, ox: Float, oz: Float, pattern: IntArray, frac: FloatArray, cell: IntArray): Boolean {
        val na = AXIS_A[side]; val nb = AXIS_B[side]; val nn = AXIS_N[side]
        val plane = coord(q, i, 0, nn, ox, oz)
        var amin = Float.MAX_VALUE; var bmin = Float.MAX_VALUE; var amax = -Float.MAX_VALUE; var bmax = -Float.MAX_VALUE
        for (k in 0 until 4) {
            if (Math.abs(coord(q, i, k, nn, ox, oz) - plane) > EPS) return false
            val a = coord(q, i, k, na, ox, oz); val b = coord(q, i, k, nb, ox, oz)
            amin = minOf(amin, a); amax = maxOf(amax, a); bmin = minOf(bmin, b); bmax = maxOf(bmax, b)
        }
        if (amax - amin < EPS || bmax - bmin < EPS) return false
        val ca = Math.floor((amin + EPS).toDouble()).toInt(); val cb = Math.floor((bmin + EPS).toDouble()).toInt()
        if (amax > ca + 1 + EPS || bmax > cb + 1 + EPS) return false
        for (k in 0 until 4) {
            val a = coord(q, i, k, na, ox, oz); val b = coord(q, i, k, nb, ox, oz)
            // Every corner on the rectangle's corners.
            val lowA = Math.abs(a - amin) < EPS; val highA = Math.abs(a - amax) < EPS
            val lowB = Math.abs(b - bmin) < EPS; val highB = Math.abs(b - bmax) < EPS
            if (!(lowA || highA) || !(lowB || highB)) return false
            pattern[k * 2] = if (highA) 1 else 0
            pattern[k * 2 + 1] = if (highB) 1 else 0
        }
        // Four different corners.
        var seen = 0; for (k in 0 until 4) seen = seen or (1 shl (pattern[k * 2] * 2 + pattern[k * 2 + 1]))
        if (seen != 15) return false
        cell[0] = ca; cell[1] = cb
        frac[0] = amin - ca; frac[1] = amax - ca; frac[2] = bmin - cb; frac[3] = bmax - cb
        return true
    }

    /**
     * How the texture lies on a face: u from a or b (flipped or not), v from the other, each the corner's place in its cell.
     * 0..7, or -1 when u and v don't follow the corners that way (a stretched, shifted or turned texture: kept unmerged).
     */
    private fun orientation(q: Quads, i: Int, pattern: IntArray, frac: FloatArray): Int {
        for (o in 0 until 8) {
            var ok = true
            for (k in 0 until 4) {
                val a = if (pattern[k * 2] == 1) frac[1] else frac[0]; val b = if (pattern[k * 2 + 1] == 1) frac[3] else frac[2]
                val uAlongB = o and 4 != 0
                val uRaw = if (uAlongB) b else a; val vRaw = if (uAlongB) a else b
                val u = if (o and 2 != 0) 1 - uRaw else uRaw; val v = if (o and 1 != 0) 1 - vRaw else vRaw
                if (Math.abs(q.uv[i * 8 + k * 2] - u) > EPS || Math.abs(q.uv[i * 8 + k * 2 + 1] - v) > EPS) { ok = false; break }
            }
            if (ok) return o
        }
        return -1
    }

    /** Rectangles (a, b, width, height) covering [cells] exactly, at most [maxW] by [maxH]: rows first, then as many whole rows as fit. */
    private fun greedy(cells: Set<Long>, maxW: Int, maxH: Int): List<IntArray> {
        val sorted = cells.sortedWith(compareBy({ (it and 0xFFFFFFFFL).toInt() }, { (it shr 32).toInt() }))
        val used = HashSet<Long>()
        val out = ArrayList<IntArray>()
        fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
        fun free(a: Int, b: Int) = key(a, b).let { it in cells && it !in used }
        for (c in sorted) {
            if (c in used) continue
            val a = (c shr 32).toInt(); val b = (c and 0xFFFFFFFFL).toInt()
            var w = 1
            while (w < maxW && free(a + w, b)) w++
            var h = 1
            rows@ while (h < maxH) { for (x in a until a + w) if (!free(x, b + h)) break@rows; h++ }
            for (x in a until a + w) for (y in b until b + h) used += key(x, y)
            out += intArrayOf(a, b, w, h)
        }
        return out
    }

    /**
     * A merged rectangle as a quad, its corners in [pattern]'s order: from the first cell's [frac] start to the last cell's end
     * each way, the texture repeated across it as on each cell (flipped against the run's length when it's flipped).
     */
    private fun merged(side: Int, plane: Int, sprite: Int, colour: Int, orient: Int, pattern: IntArray, frac: FloatArray, r: IntArray): Out {
        val f = Out(sprite)
        val na = AXIS_A[side]; val nb = AXIS_B[side]; val nn = AXIS_N[side]
        val w = r[2]; val h = r[3]
        val uAlongB = orient and 4 != 0
        for (k in 0 until 4) {
            // Each way, the corner's place from the first cell's low side.
            val a = if (pattern[k * 2] == 1) w - 1 + frac[1] else frac[0]
            val b = if (pattern[k * 2 + 1] == 1) h - 1 + frac[3] else frac[2]
            val p = FloatArray(3)
            p[na] = r[0] + a; p[nb] = r[1] + b; p[nn] = plane / PLANE_STEPS
            f.pos[k * 3] = p[0]; f.pos[k * 3 + 1] = p[1]; f.pos[k * 3 + 2] = p[2]
            val uRaw = if (uAlongB) b else a; val vRaw = if (uAlongB) a else b
            val uSpan = if (uAlongB) h else w; val vSpan = if (uAlongB) w else h
            f.uv[k * 2] = if (orient and 2 != 0) uSpan - uRaw else uRaw
            f.uv[k * 2 + 1] = if (orient and 1 != 0) vSpan - vRaw else vRaw
            f.col[k] = colour
        }
        return f
    }

    // --- from the game's own vertices ---------------------------------------------------------------------------------------

    /**
     * POSITION_TEX_COLOR quads (as [BlockMesh] builds this run's own geometry) into [into]: each quad's texture found by where
     * it sits in the atlas, its u v made the texture's own (a quad whose texture isn't in the atlas is left out). False before
     * the atlas can be read.
     */
    fun fromVertices(vertices: ByteBuffer, into: Quads): Boolean {
        val sp = MeshCodec.sprites() ?: return false
        val b = vertices.duplicate().order(ByteOrder.nativeOrder())
        val base = b.position()
        val quads = b.remaining() / (24 * 4)
        for (qi in 0 until quads) {
            val at = base + qi * 96
            var cu = 0f; var cv = 0f
            for (k in 0 until 4) { cu += b.getFloat(at + k * 24 + 12); cv += b.getFloat(at + k * 24 + 16) }
            val s = sp.at(cu / 4, cv / 4) ?: continue
            val i = into.add(SpriteTable.of(s), MeshCodec.facing(b, at))
            for (k in 0 until 4) {
                val v = at + k * 24
                into.pos[i * 12 + k * 3] = b.getFloat(v); into.pos[i * 12 + k * 3 + 1] = b.getFloat(v + 4); into.pos[i * 12 + k * 3 + 2] = b.getFloat(v + 8)
                into.uv[i * 8 + k * 2] = if (s.u1 > s.u0) (b.getFloat(v + 12) - s.u0) / (s.u1 - s.u0) else 0f
                into.uv[i * 8 + k * 2 + 1] = if (s.v1 > s.v0) (b.getFloat(v + 16) - s.v0) / (s.v1 - s.v0) else 0f
                into.col[i * 4 + k] = b.getInt(v + 20)
            }
        }
        return true
    }
}

/**
 * [CompactMesh]'s pipeline: its vertex format, the sprite table's binding and the shaders (assets/engineerclient/shaders/core/
 * pose_pov). Registered with the game's pipelines when [PosePov] loads, apart from the packing so that runs anywhere.
 */
object PoseShader {
    val FORMAT: VertexFormat = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGBA16_UINT)
        .addAttribute("UV0", GpuFormat.RG16_UINT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .build()

    private val SPRITES: BindGroupLayout = BindGroupLayout.builder().withUniform("PoseSprites", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT).build()

    val PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("devgineerclient", "pipeline/pose_pov_compact"))
            .withVertexShader(Identifier.fromNamespaceAndPath("engineerclient", "core/pose_pov"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("engineerclient", "core/pose_pov"))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withBindGroupLayout(SPRITES)
            .withVertexBinding(0, FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withCull(false)
            // Reversed Z: depth is cleared to 0 and nearer is greater.
            .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .build()
    )

}
