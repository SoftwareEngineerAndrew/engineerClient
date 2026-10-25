package com.devgineerclient.posepov

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Blocks
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * The shared pieces ([BlockMesh]'s room designs and arena sections) kept on disk across game restarts, so a room is built once
 * ever rather than once per launch. A piece is kept apart from the block atlas: each quad names its texture and has its
 * texture coordinates within it, and is put back into whatever atlas the packs make now when read. So the file is the same for
 * every pack (and every player on this game version), and only the pieces in memory are built again when the atlas changes.
 *
 * One append-only file of entries: key (UTF), index count (int), byte length (int), the bytes (deflated). Its index is read
 * once in the background; pieces are read on demand. Writes go through one background thread and are indexed once written.
 */
object MeshDisk {
    /** Bumped whenever what a piece looks like changes, so old files are never read. */
    private const val FORMAT = 3

    class Entry(val offset: Long, val indexCount: Int, val length: Int)

    private val dir: Path = FabricLoader.getInstance().gameDir.resolve("devgineerclient").resolve("posepov-cache")
    private val index = ConcurrentHashMap<String, Entry>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pose pov mesh cache").apply { isDaemon = true } }
    @Volatile private var file: Path? = null
    @Volatile private var reader: RandomAccessFile? = null
    @Volatile var ready = false; private set
    private var atlasSeen: String? = null
    private var checkedAt = 0L
    @Volatile private var sprites: Sprites? = null

    /** Where a few common textures sit in the block atlas: any change to the atlas moves them. */
    private fun atlasPrint(): String {
        val models = Minecraft.getInstance().modelManager.blockStateModelSet
        val sb = StringBuilder()
        for (b in listOf(Blocks.STONE, Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.GLASS, Blocks.BEDROCK, Blocks.WATER)) {
            val s = models.getParticleMaterial(b.defaultBlockState()).sprite()
            sb.append('|').append(s.u0).append(',').append(s.v0).append(',').append(s.u1).append(',').append(s.v1)
        }
        return sb.toString()
    }

    /**
     * Opens the file the first time, and looks for an atlas change (a pack reload): true when the atlas is a different one from
     * before, meaning the pieces built so far point at the wrong textures.
     */
    fun check(): Boolean {
        // Packs only change on a reload: a look a second is plenty.
        val now = System.currentTimeMillis()
        if (atlasSeen != null && now - checkedAt < 1000) return false
        checkedAt = now
        val ap = atlasPrint()
        if (ap == atlasSeen) return false
        val first = atlasSeen == null
        atlasSeen = ap
        sprites = null
        if (first) open()
        return !first
    }

    private fun open() {
        val name = Integer.toHexString("$FORMAT|${SharedConstants.getCurrentVersion().name()}".hashCode())
        val path = dir.resolve("$name.bin")
        file = path
        writer.execute {
            runCatching {
                Files.createDirectories(dir)
                if (Files.exists(path)) DataInputStream(Files.newInputStream(path).buffered()).use { inp ->
                    var at = 0L
                    while (true) {
                        val key = runCatching { inp.readUTF() }.getOrNull() ?: break
                        val count = inp.readInt(); val len = inp.readInt()
                        val head = 2 + key.toByteArray(Charsets.UTF_8).size + 8
                        val data = at + head
                        inp.skipNBytes(len.toLong())
                        index[key] = Entry(data, count, len)
                        at = data + len
                    }
                }
                reader = if (Files.exists(path)) RandomAccessFile(path.toFile(), "r") else null
            }
            // Only this version's file: the others are older formats or game versions.
            runCatching { Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".bin") && it != path }.forEach { Files.deleteIfExists(it) } } }
            ready = true
        }
    }

    /**
     * Whether the piece is on disk: null when it isn't, otherwise its index count (0 for an empty piece) and bytes, the
     * vertices put into the current atlas unless [raw].
     */
    fun read(key: String, raw: Boolean = false): Pair<Int, ByteBuffer?>? {
        val e = index[key] ?: return null
        if (e.length == 0) return 0 to null
        val r = reader ?: return null
        val bytes = ByteArray(e.length)
        synchronized(r) { r.seek(e.offset); r.readFully(bytes) }
        val plain = runCatching { InflaterInputStream(ByteArrayInputStream(bytes)).readAllBytes() }.getOrNull() ?: return null
        if (raw) return e.indexCount to ByteBuffer.allocateDirect(plain.size).put(plain).flip()
        return e.indexCount to (runCatching { decode(plain) }.getOrNull() ?: return null)
    }

    /** Keeps a built piece ([bytes] null for an empty one; vertices unless [raw]); [bytes] is copied before this returns. */
    fun write(key: String, indexCount: Int, bytes: ByteBuffer?, raw: Boolean = false) {
        val path = file ?: return
        val plain = if (bytes == null) ByteArray(0) else if (raw) ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
            else encode(bytes) ?: return
        writer.execute {
            if (file != path || index.containsKey(key)) return@execute
            runCatching {
                val copy = if (plain.isEmpty()) plain else ByteArrayOutputStream().also { o -> DeflaterOutputStream(o).use { it.write(plain) } }.toByteArray()
                val start = if (Files.exists(path)) Files.size(path) else 0L
                DataOutputStream(Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND).buffered()).use { out ->
                    out.writeUTF(key); out.writeInt(indexCount); out.writeInt(copy.size); out.write(copy)
                }
                val head = 2 + key.toByteArray(Charsets.UTF_8).size + 8
                if (reader == null) reader = RandomAccessFile(path.toFile(), "r")
                index[key] = Entry(start + head, indexCount, copy.size)
            }
        }
    }

    // --- away from the atlas and back -------------------------------------------------------------------------------------

    // POSITION_TEX_COLOR: x y z u v as floats, then the colour's 4 bytes.
    private const val VERTEX = 24

    /** The block atlas's sprites, found by atlas coordinates (a 512 x 512 grid of who covers each cell) and by name. */
    private class Sprites(val list: List<TextureAtlasSprite>, val atlas: TextureAtlas) {
        private val grid = HashMap<Int, ArrayList<TextureAtlasSprite>>()
        init {
            for (s in list) for (gx in cell(s.u0)..cell(s.u1)) for (gy in cell(s.v0)..cell(s.v1)) grid.getOrPut(gx * 1024 + gy) { ArrayList(2) } += s
        }
        private fun cell(f: Float) = (f * 512).toInt().coerceIn(0, 511)
        fun at(u: Float, v: Float): TextureAtlasSprite? = grid[cell(u) * 1024 + cell(v)]?.firstOrNull { u >= it.u0 && u <= it.u1 && v >= it.v0 && v <= it.v1 }
        fun named(id: Identifier): TextureAtlasSprite = atlas.getSprite(id)
    }

    private fun sprites(): Sprites? = sprites ?: runCatching {
        val atlas = Minecraft.getInstance().textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS) as TextureAtlas
        @Suppress("UNCHECKED_CAST")
        val list = TextureAtlas::class.java.getDeclaredField("sprites").apply { isAccessible = true }.get(atlas) as List<TextureAtlasSprite>
        Sprites(list, atlas)
    }.getOrNull()?.also { sprites = it }

    private fun q(p: Float) = ((p + 64f) * 128f + 0.5f).toInt().coerceIn(0, 65535)
    private fun unq(i: Int) = i / 128f - 64f
    private fun local(f: Float, a: Float, b: Float) = if (b <= a) 0 else ((f - a) / (b - a) * 65535f + 0.5f).toInt().coerceIn(0, 65535)

    /**
     * Quads in the atlas to quads on their own: the textures named once, then per quad its texture's number and per corner
     * x y z (1/128 of a block, from -64), u v within the texture (1/65535) and the colour. Null when a texture isn't found.
     */
    private fun encode(vertices: ByteBuffer): ByteArray? {
        val sp = sprites() ?: return null
        val b = vertices.duplicate().order(ByteOrder.nativeOrder())
        val quads = b.remaining() / (VERTEX * 4)
        val names = LinkedHashMap<Identifier, Int>()
        val body = ByteBuffer.allocate(quads * 58).order(ByteOrder.BIG_ENDIAN)
        val base = b.position()
        for (qi in 0 until quads) {
            val at = base + qi * VERTEX * 4
            var cu = 0f; var cv = 0f
            for (k in 0 until 4) { cu += b.getFloat(at + k * VERTEX + 12); cv += b.getFloat(at + k * VERTEX + 16) }
            val s = sp.at(cu / 4, cv / 4) ?: return null
            body.putShort(names.getOrPut(s.contents().name()) { names.size }.toShort())
            for (k in 0 until 4) {
                val v = at + k * VERTEX
                body.putShort(q(b.getFloat(v)).toShort()).putShort(q(b.getFloat(v + 4)).toShort()).putShort(q(b.getFloat(v + 8)).toShort())
                body.putShort(local(b.getFloat(v + 12), s.u0, s.u1).toShort()).putShort(local(b.getFloat(v + 16), s.v0, s.v1).toShort())
                body.putInt(b.getInt(v + 20))
            }
        }
        val out = ByteArrayOutputStream(body.position() + names.size * 32)
        DataOutputStream(out).use { d ->
            d.writeShort(names.size); for (n in names.keys) d.writeUTF(n.toString())
            d.writeInt(quads); d.write(body.array(), 0, body.position())
        }
        return out.toByteArray()
    }

    /** [encode] undone into the atlas the packs make now (a texture it no longer has shows as the missing one). */
    private fun decode(plain: ByteArray): ByteBuffer? {
        val sp = sprites() ?: return null
        val d = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN)
        val inp = DataInputStream(ByteArrayInputStream(plain))
        val n = inp.readUnsignedShort()
        val list = Array(n) { sp.named(Identifier.parse(inp.readUTF())) }
        val quads = inp.readInt()
        d.position(plain.size - inp.available())
        val out = ByteBuffer.allocateDirect(quads * VERTEX * 4).order(ByteOrder.nativeOrder())
        for (qi in 0 until quads) {
            val s = list[d.getShort().toInt() and 0xFFFF]
            for (k in 0 until 4) {
                out.putFloat(unq(d.getShort().toInt() and 0xFFFF)).putFloat(unq(d.getShort().toInt() and 0xFFFF)).putFloat(unq(d.getShort().toInt() and 0xFFFF))
                out.putFloat(s.u0 + (d.getShort().toInt() and 0xFFFF) / 65535f * (s.u1 - s.u0))
                out.putFloat(s.v0 + (d.getShort().toInt() and 0xFFFF) / 65535f * (s.v1 - s.v0))
                out.putInt(d.getInt())
            }
        }
        return out.flip()
    }
}
