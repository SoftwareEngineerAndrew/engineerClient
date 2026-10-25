package com.devgineerclient.posepov

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.world.level.block.Blocks
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The shared pieces ([BlockMesh]'s room designs and arena sections) kept on disk across game restarts, so a room is built once
 * ever rather than once per launch. A piece's vertices hold texture coordinates in the block atlas, which moves with the
 * resource packs, so each atlas layout gets its own file (named by [fingerprint]); a pack change starts a new one.
 *
 * One append-only file of entries: key (UTF), index count (int), byte length (int), the vertex bytes. Its index is read once
 * in the background; pieces are read on demand. Writes go through one background thread and are indexed once written.
 */
object MeshDisk {
    /** Bumped whenever what a piece looks like changes, so old files are never read. */
    private const val FORMAT = 2

    class Entry(val offset: Long, val indexCount: Int, val length: Int)

    private val dir: Path = FabricLoader.getInstance().gameDir.resolve("devgineerclient").resolve("posepov-cache")
    private val index = ConcurrentHashMap<String, Entry>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pose pov mesh cache").apply { isDaemon = true } }
    @Volatile private var file: Path? = null
    @Volatile private var reader: RandomAccessFile? = null
    @Volatile var ready = false; private set
    private var opened: String? = null
    private var checkedAt = 0L

    /**
     * The atlas layout: the game version, the packs in use, and where a few common textures sit in the block atlas (any change
     * to the atlas moves them).
     */
    private fun fingerprint(): String {
        val mc = Minecraft.getInstance()
        val models = mc.modelManager.blockStateModelSet
        val sb = StringBuilder().append(FORMAT).append('|').append(SharedConstants.getCurrentVersion().name()).append('|')
        sb.append(mc.resourcePackRepository.selectedIds.joinToString(","))
        for (b in listOf(Blocks.STONE, Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.GLASS, Blocks.BEDROCK, Blocks.WATER)) {
            val s = models.getParticleMaterial(b.defaultBlockState()).sprite()
            sb.append('|').append(s.u0).append(',').append(s.v0).append(',').append(s.u1).append(',').append(s.v1)
        }
        return Integer.toHexString(sb.toString().hashCode()) + "-" + Integer.toHexString(sb.reverse().toString().hashCode())
    }

    /**
     * Opens the file for the current atlas (reading its index in the background); true when that is a different one from
     * before, meaning the pieces built so far are for another layout.
     */
    fun check(): Boolean {
        // Packs only change on a reload: a look a second is plenty.
        val now = System.currentTimeMillis()
        if (opened != null && now - checkedAt < 1000) return false
        checkedAt = now
        val fp = fingerprint()
        if (fp == opened) return false
        val first = opened == null
        opened = fp
        ready = false
        index.clear()
        reader?.close(); reader = null
        val path = dir.resolve("$fp.bin")
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
            // Only this layout's file: the others are for packs no longer in use.
            runCatching { Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".bin") && it != path }.forEach { Files.deleteIfExists(it) } } }
            ready = true
        }
        return !first
    }

    /** Whether the piece is on disk: null when it isn't, otherwise its index count (0 for an empty piece) and vertex bytes. */
    fun read(key: String): Pair<Int, ByteBuffer?>? {
        val e = index[key] ?: return null
        if (e.length == 0) return 0 to null
        val r = reader ?: return null
        val bytes = ByteArray(e.length)
        synchronized(r) { r.seek(e.offset); r.readFully(bytes) }
        return e.indexCount to ByteBuffer.allocateDirect(e.length).put(bytes).flip()
    }

    /** Keeps a built piece ([vertices] null for an empty one); [vertices] is copied before this returns. */
    fun write(key: String, indexCount: Int, vertices: ByteBuffer?) {
        val path = file ?: return
        val copy = vertices?.let { v -> ByteArray(v.remaining()).also { v.duplicate().get(it) } } ?: ByteArray(0)
        writer.execute {
            if (file != path || index.containsKey(key)) return@execute
            runCatching {
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
}
