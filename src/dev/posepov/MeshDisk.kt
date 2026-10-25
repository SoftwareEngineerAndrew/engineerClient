package com.devgineerclient.posepov

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.world.level.block.Blocks
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * What Pose POV keeps on disk across game restarts: room and arena bundles ([MeshBundles], away from the block atlas, so the
 * same for every pack and every player on this game version) and the arena's worked-out visibility.
 *
 * One append-only file of entries: key (UTF), a count (int), byte length (int), the bytes. Its index is read once in the
 * background; entries are read on demand. Writes go through one background thread and are indexed once written.
 */
object MeshDisk {
    /** Bumped whenever what an entry holds changes, so old files are never read. */
    private const val FORMAT = 4

    class Entry(val offset: Long, val count: Int, val length: Int)

    val dir: Path = FabricLoader.getInstance().gameDir.resolve("devgineerclient").resolve("posepov-cache")
    private val index = ConcurrentHashMap<String, Entry>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pose pov mesh cache").apply { isDaemon = true } }
    @Volatile private var file: Path? = null
    @Volatile private var reader: RandomAccessFile? = null
    @Volatile var ready = false; private set
    private var atlasSeen: String? = null
    private var checkedAt = 0L

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
     * before, meaning what's in memory points at the wrong textures.
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
        MeshCodec.atlasChanged()
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

    fun has(key: String) = index.containsKey(key)

    /** The entry: null when it isn't on disk, otherwise its count and bytes (empty for an empty one). Any thread. */
    fun read(key: String): Pair<Int, ByteArray>? {
        val e = index[key] ?: return null
        if (e.length == 0) return e.count to ByteArray(0)
        val r = reader ?: return null
        val bytes = ByteArray(e.length)
        synchronized(r) { r.seek(e.offset); r.readFully(bytes) }
        return e.count to bytes
    }

    /** Keeps an entry, once (a key already kept stays as it is). [bytes] must not change after. */
    fun write(key: String, count: Int, bytes: ByteArray) {
        val path = file ?: return
        writer.execute {
            if (file != path || index.containsKey(key)) return@execute
            runCatching {
                val start = if (Files.exists(path)) Files.size(path) else 0L
                DataOutputStream(Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND).buffered()).use { out ->
                    out.writeUTF(key); out.writeInt(count); out.writeInt(bytes.size); out.write(bytes)
                }
                val head = 2 + key.toByteArray(Charsets.UTF_8).size + 8
                if (reader == null) reader = RandomAccessFile(path.toFile(), "r")
                index[key] = Entry(start + head, count, bytes.size)
            }
        }
    }
}
