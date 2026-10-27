package com.devgineerclient.posepov

import com.engineerclient.ads.pack.Rot
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.resources.Identifier
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/**
 * A piece's quads away from the block atlas and back. Kept: per quad its texture (a number into the bundle's names), the side
 * it was shaded for, and per corner x y z (1/128 of a block, from -64), u v within the texture (1/65535) and the colour.
 * Quads are grouped by the way they face (down up north south west east, then the rest), so a frame can leave out the
 * groups facing away from the eye.
 *
 * Rooms are kept unturned and turned here, as they're read: positions turn with the room, and a side's shade is redone for
 * the side it now faces (the game shades north and south 0.8, west and east 0.6).
 */
object MeshCodec {
    /** POSITION_TEX_COLOR (what [encode] reads): x y z u v as floats, then the colour's 4 bytes. */
    const val VERTEX = 24
    const val GROUPS = 7
    private const val QUAD = 3 + 4 * 14
    private val SHADE = floatArrayOf(0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f)

    // --- the atlas --------------------------------------------------------------------------------------------------------

    /** The block atlas's sprites, found by atlas coordinates (a 512 x 512 grid of who covers each cell). */
    class Sprites(val list: List<TextureAtlasSprite>, private val atlas: TextureAtlas) {
        private val grid = HashMap<Int, ArrayList<TextureAtlasSprite>>()
        init {
            for (s in list) for (gx in cell(s.u0)..cell(s.u1)) for (gy in cell(s.v0)..cell(s.v1)) grid.getOrPut(gx * 1024 + gy) { ArrayList(2) } += s
        }
        private fun cell(f: Float) = (f * 512).toInt().coerceIn(0, 511)
        fun at(u: Float, v: Float): TextureAtlasSprite? = grid[cell(u) * 1024 + cell(v)]?.firstOrNull { u >= it.u0 && u <= it.u1 && v >= it.v0 && v <= it.v1 }
        fun named(id: String): TextureAtlasSprite = Identifier.tryParse(id)?.let { atlas.getSprite(it) } ?: atlas.missingSprite()
    }

    @Volatile private var sprites: Sprites? = null
    /** The atlas changed (a pack reload): look it up again. */
    fun atlasChanged() { sprites = null }

    fun sprites(): Sprites? = sprites ?: runCatching {
        val atlas = Minecraft.getInstance().textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS) as TextureAtlas
        @Suppress("UNCHECKED_CAST")
        val list = TextureAtlas::class.java.getDeclaredField("sprites").apply { isAccessible = true }.get(atlas) as List<TextureAtlasSprite>
        Sprites(list, atlas)
    }.getOrNull()?.also { sprites = it }

    // --- away -------------------------------------------------------------------------------------------------------------

    private fun q(p: Float) = ((p + 64f) * 128f + 0.5f).toInt().coerceIn(0, 65535)
    private fun local(f: Float, a: Float, b: Float) = if (b <= a) 0 else ((f - a) / (b - a) * 65535f + 0.5f).toInt().coerceIn(0, 65535)

    /** The way a quad faces by its corners (Direction order), or 6 when it isn't square to an axis (plants, rotated parts). */
    fun facing(b: ByteBuffer, at: Int): Int {
        fun c(k: Int, o: Int) = b.getFloat(at + k * VERTEX + o)
        val ax = c(1, 0) - c(0, 0); val ay = c(1, 4) - c(0, 4); val az = c(1, 8) - c(0, 8)
        val bx = c(2, 0) - c(0, 0); val by = c(2, 4) - c(0, 4); val bz = c(2, 8) - c(0, 8)
        val nx = ay * bz - az * by; val ny = az * bx - ax * bz; val nz = ax * by - ay * bx
        val len = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
        if (len < 1e-6f) return 6
        return when {
            ny / len > 0.999f -> 1; ny / len < -0.999f -> 0
            nz / len > 0.999f -> 3; nz / len < -0.999f -> 2
            nx / len > 0.999f -> 5; nx / len < -0.999f -> 4
            else -> 6
        }
    }

    /**
     * A built piece ([vertices], [shades]: per quad the side it was shaded for) away from the atlas, deflated; textures are
     * numbered into [names]. Null when a texture isn't in the atlas.
     */
    fun encode(vertices: ByteBuffer, shades: ByteArray, names: MutableMap<String, Int>): ByteArray? {
        val sp = sprites() ?: return null
        val b = vertices.duplicate().order(ByteOrder.nativeOrder())
        val base = b.position()
        val quads = b.remaining() / (VERTEX * 4)
        val group = IntArray(quads) { facing(b, base + it * VERTEX * 4) }
        val counts = IntArray(GROUPS); for (g in group) counts[g]++
        val body = ByteBuffer.allocate(4 * GROUPS + quads * QUAD).order(ByteOrder.BIG_ENDIAN)
        for (c in counts) body.putInt(c)
        for (g in 0 until GROUPS) for (qi in 0 until quads) {
            if (group[qi] != g) continue
            val at = base + qi * VERTEX * 4
            var cu = 0f; var cv = 0f
            for (k in 0 until 4) { cu += b.getFloat(at + k * VERTEX + 12); cv += b.getFloat(at + k * VERTEX + 16) }
            val s = sp.at(cu / 4, cv / 4) ?: return null
            body.putShort(names.getOrPut(s.contents().name().toString()) { names.size }.toShort())
            body.put(if (qi < shades.size) shades[qi] else 1)
            for (k in 0 until 4) {
                val v = at + k * VERTEX
                body.putShort(q(b.getFloat(v)).toShort()).putShort(q(b.getFloat(v + 4)).toShort()).putShort(q(b.getFloat(v + 8)).toShort())
                body.putShort(local(b.getFloat(v + 12), s.u0, s.u1).toShort()).putShort(local(b.getFloat(v + 16), s.v0, s.v1).toShort())
                body.putInt(b.getInt(v + 20))
            }
        }
        val out = ByteArrayOutputStream(body.position() / 3)
        DeflaterOutputStream(out).use { it.write(body.array(), 0, body.position()) }
        return out.toByteArray()
    }

    // --- back -------------------------------------------------------------------------------------------------------------

    /** Direction [d] (ordinal) turned like a room placed [rot]. */
    fun turn(d: Int, rot: Rot): Int {
        if (d < 2 || d > 5 || rot == Rot.SOUTH) return d
        var x = when (d) { 4 -> -1; 5 -> 1; else -> 0 }; var z = when (d) { 2 -> -1; 3 -> 1; else -> 0 }
        when (rot) {
            Rot.NORTH -> { x = -x; z = -z }
            Rot.WEST -> { val t = x; x = -z; z = t }
            Rot.EAST -> { val t = x; x = z; z = -t }
            else -> {}
        }
        return when { z < 0 -> 2; z > 0 -> 3; x < 0 -> 4; else -> 5 }
    }

    /** Point (x, z) of an unturned box [w] x [d], in the box placed [rot] (Placement.toWorld for points, less the origin). */
    fun turnX(rot: Rot, x: Float, z: Float, w: Int, d: Int): Float = when (rot) { Rot.SOUTH -> x; Rot.NORTH -> w - x; Rot.WEST -> d - z; Rot.EAST -> z }
    fun turnZ(rot: Rot, x: Float, z: Float, w: Int, d: Int): Float = when (rot) { Rot.SOUTH -> z; Rot.NORTH -> d - z; Rot.WEST -> x; Rot.EAST -> w - x }

    /** How many quads each group of a deflated piece has, without reading the rest. */
    fun counts(packed: ByteArray, off: Int, len: Int): IntArray {
        val head = ByteArray(4 * GROUPS)
        Inflater().run { setInput(packed, off, len); inflate(head); end() }
        val b = ByteBuffer.wrap(head)
        return IntArray(GROUPS) { b.getInt() }
    }

    /**
     * A deflated piece back into [out] (cleared first), turned [rot] in a box [w] x [d] (unturned size): per quad its texture
     * as a number in the sprite table ([sprites], by the bundle's own texture numbers), the side it now faces, and per corner
     * its turned position, u v across the texture and colour (its side's shade redone for the side it now faces). With [cut],
     * the quads nobody can see are left out ([RoomCut.drops]; [pockets]: sealed pockets too).
     */
    fun decode(packed: ByteArray, off: Int, len: Int, sprites: IntArray, rot: Rot, w: Int, d: Int, out: CompactMesh.Quads, cut: RoomCut? = null, pockets: Boolean = false) {
        out.clear()
        val inf = Inflater()
        val plain: ByteArray
        try {
            inf.setInput(packed, off, len)
            val o = ByteArrayOutputStream(len * 3)
            val chunk = ByteArray(64 * 1024)
            while (!inf.finished()) { val n = inf.inflate(chunk); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; o.write(chunk, 0, n) }
            plain = o.toByteArray()
        } finally { inf.end() }
        val b = ByteBuffer.wrap(plain)
        val counts = IntArray(GROUPS) { b.getInt() }
        val total = counts.sum()
        require(plain.size == 4 * GROUPS + total * QUAD) { "bad piece" }
        val ratio = FloatArray(6) { s -> SHADE[turn(s, rot)] / SHADE[s] }
        val at = corners.get()
        var group = 0; var left = counts[0]
        for (qi in 0 until total) {
            while (left == 0) left = counts[++group]
            left--
            val start = b.position()
            b.position(start + 3)
            for (c in 0 until 4) {
                at[c * 3] = (b.getShort().toInt() and 0xFFFF) / 128f - 64f
                at[c * 3 + 1] = (b.getShort().toInt() and 0xFFFF) / 128f - 64f
                at[c * 3 + 2] = (b.getShort().toInt() and 0xFFFF) / 128f - 64f
                b.position(b.position() + 8)
            }
            if (cut != null && cut.drops(group, at, pockets)) continue
            b.position(start)
            val name = b.getShort().toInt() and 0xFFFF
            val shade = b.get().toInt().coerceIn(0, 5)
            val k = ratio[shade]
            val i = out.add(sprites.getOrElse(name) { 0 }, if (group < 6) turn(group, rot) else 6)
            for (c in 0 until 4) {
                b.position(b.position() + 6)
                val x = at[c * 3]; val y = at[c * 3 + 1]; val z = at[c * 3 + 2]
                out.pos[i * 12 + c * 3] = turnX(rot, x, z, w, d); out.pos[i * 12 + c * 3 + 1] = y; out.pos[i * 12 + c * 3 + 2] = turnZ(rot, x, z, w, d)
                out.uv[i * 8 + c * 2] = (b.getShort().toInt() and 0xFFFF) / 65535f
                out.uv[i * 8 + c * 2 + 1] = (b.getShort().toInt() and 0xFFFF) / 65535f
                val col = b.getInt()
                out.col[i * 4 + c] = if (k == 1f) col else shaded(col, k)
            }
        }
    }
    private val corners = ThreadLocal.withInitial { FloatArray(12) }

    /** The colour's red, green and blue times [k] (the int as the vertex buffer holds it: native order, alpha in its top byte). */
    private fun shaded(c: Int, k: Float): Int {
        val native = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        // In memory the bytes are r g b a: as a little-endian int, a in the top byte and r in the bottom.
        fun ch(v: Int) = ((v and 255) * k + 0.5f).toInt().coerceIn(0, 255)
        return if (native) (c and 0xFF000000.toInt()) or (ch(c shr 16) shl 16) or (ch(c shr 8) shl 8) or ch(c)
        else (ch(c ushr 24) shl 24) or (ch(c shr 16) shl 16) or (ch(c shr 8) shl 8) or (c and 255)
    }
}
