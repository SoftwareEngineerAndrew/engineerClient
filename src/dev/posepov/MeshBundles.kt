package com.devgineerclient.posepov

import com.engineerclient.p3sim.pack.Rot
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A room design's (or a variant's, or the arena's) geometry in 16³ pieces, each deflated on its own ([MeshCodec]): read from
 * disk, else fetched ready-made from undonecoffee.com (built once in a game and uploaded, the same for everyone), else built
 * here ([BlockMesh]) and kept. A design's bundle has every piece of its unturned box; a variant's only the pieces it changes.
 *
 * File: magic, the texture names (u16 count, UTF each), the pieces (int count; each its place lx ly lz in a packed int and
 * its byte length), what each piece can see (int bytes per piece, then a bitset per piece over the pieces, or 0), then the
 * pieces' bytes one after another.
 */
object MeshBundles {
    private const val MAGIC = 0x50505632
    private const val SITE = "https://undonecoffee.com/adr/mesh/"
    private const val MAX_BYTES = 48 shl 20

    class Bundle(val name: String, val names: Array<String>, val places: IntArray, val offs: IntArray, val lens: IntArray, val data: ByteArray, private val visAt: Int, private val visLen: Int) {
        /** Whether piece [to] can be seen from anywhere in piece [from] (pieces in the design's grid order); true when not worked out. */
        fun sees(from: Int, to: Int): Boolean =
            visLen == 0 || from !in places.indices || to < 0 || to >= visLen * 8 || ((data[visAt + from * visLen + (to shr 3)].toInt() shr (to and 7)) and 1) != 0
        val hasSight get() = visLen > 0
        private val byPlace = HashMap<Int, Int>(places.size * 2).also { m -> places.forEachIndexed { i, p -> m[p] = i } }
        fun find(lx: Int, ly: Int, lz: Int): Int = byPlace[place(lx, ly, lz)] ?: -1
        @Volatile var sprites: Array<TextureAtlasSprite>? = null
        fun sprites(): Array<TextureAtlasSprite>? = sprites ?: MeshCodec.sprites()?.let { sp -> Array(names.size) { sp.named(names[it]) } }?.also { sprites = it }
    }

    fun place(lx: Int, ly: Int, lz: Int) = ((lx and 0xFF) shl 16) or ((ly and 0xFF) shl 8) or (lz and 0xFF)
    fun placeX(p: Int) = (p shr 16) and 0xFF
    fun placeY(p: Int) = (p shr 8).toByte().toInt()
    fun placeZ(p: Int) = p and 0xFF

    fun serialize(names: Map<String, Int>, places: IntArray, pieces: Array<ByteArray>, sight: Array<ByteArray>?): ByteArray {
        val out = ByteArrayOutputStream(pieces.sumOf { it.size } + 4096)
        DataOutputStream(out).use { d ->
            d.writeInt(MAGIC)
            val ordered = names.entries.sortedBy { it.value }
            d.writeShort(ordered.size); for (e in ordered) d.writeUTF(e.key)
            d.writeInt(places.size)
            for (i in places.indices) { d.writeInt(places[i]); d.writeInt(pieces[i].size) }
            val visLen = sight?.firstOrNull()?.size ?: 0
            d.writeInt(visLen)
            if (visLen > 0) for (s in sight!!) { require(s.size == visLen); d.write(s) }
            for (p in pieces) d.write(p)
        }
        return out.toByteArray()
    }

    fun parse(name: String, bytes: ByteArray): Bundle {
        val d = DataInputStream(ByteArrayInputStream(bytes))
        require(d.readInt() == MAGIC) { "not a bundle" }
        val names = Array(d.readUnsignedShort()) { d.readUTF() }
        val n = d.readInt()
        require(n in 0..100_000)
        val places = IntArray(n); val lens = IntArray(n)
        for (i in 0 until n) { places[i] = d.readInt(); lens[i] = d.readInt(); require(lens[i] >= 0) }
        val visLen = d.readInt()
        require(visLen in 0..4096)
        val visAt = bytes.size - d.available()
        val start = visAt + visLen * n
        val offs = IntArray(n)
        var at = start
        for (i in 0 until n) { offs[i] = at; at += lens[i] }
        require(at == bytes.size) { "bundle length" }
        return Bundle(name, names, places, offs, lens, bytes, visAt, visLen)
    }

    // --- where a bundle comes from ----------------------------------------------------------------------------------------

    private const val FETCHING = 1
    private const val BUILD = 2
    private val loaded = ConcurrentHashMap<String, Bundle>()
    private val state = ConcurrentHashMap<String, Int>()
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "pose pov bundles").apply { isDaemon = true } }
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build() }
    /** Build everything here, never fetch (the export). */
    @Volatile var offline = false
    /** The pack and format the bundles are for: part of their address and disk key. */
    @Volatile var version = ""

    private fun key(name: String) = "b/$version/$name"

    /** The bundle when it's in memory; otherwise starts getting it (null meanwhile). Any thread. */
    fun get(name: String): Bundle? {
        loaded[name]?.let { return it }
        if (version.isEmpty() || !MeshDisk.ready || state.putIfAbsent(name, FETCHING) != null) return null
        io.execute {
            val disk = MeshDisk.read(key(name))?.second?.let { b -> runCatching { parse(name, b) }.getOrNull() }
            if (disk != null) { loaded[name] = disk; state.remove(name); return@execute }
            if (offline) { state[name] = BUILD; return@execute }
            val got = runCatching {
                val url = SITE + version + "/" + URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
                val res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                if (res.statusCode() != 200) { res.body().close(); null }
                else res.body().use { s -> s.readNBytes(MAX_BYTES + 1) }.takeIf { it.size <= MAX_BYTES }
            }.getOrNull()
            val b = got?.let { runCatching { parse(name, it) }.getOrNull() }
            if (b != null) { MeshDisk.write(key(name), 0, got); loaded[name] = b; state.remove(name) }
            else state[name] = BUILD
        }
        return null
    }

    /** Whether this bundle has to be built here (not on disk, not to be had from the site). */
    fun needsBuild(name: String) = state[name] == BUILD

    /** A bundle built here: kept on disk and in memory. */
    fun built(name: String, bytes: ByteArray): Bundle {
        val b = parse(name, bytes)
        MeshDisk.write(key(name), 0, bytes)
        loaded[name] = b; state.remove(name)
        return b
    }

    /** The texture lookups are for the old atlas. */
    fun atlasChanged() { for (b in loaded.values) b.sprites = null }

    /** How many bundles are in memory (one more arriving changes what a feed draws). */
    val count get() = loaded.size

    fun cached(name: String): ByteArray? = MeshDisk.read(key(name))?.second
}

/**
 * The pieces on the GPU: decoded only when a feed could show them, into shared pools (one buffer holding many pieces), each
 * piece's groups (by the way they face) one after another. Render thread.
 */
object PiecePool {
    private const val POOL_VERTICES = 1 shl 19
    private const val MAX_POOLS = 16

    class Resident(val buffer: GpuBuffer, val first: Int, val quads: IntArray, val dirs: IntArray)
    private class Pool(val buffer: GpuBuffer, val vertices: Int) { var used = 0 }
    private class Want(val bundle: MeshBundles.Bundle, val piece: Int, val rot: Rot, val w: Int, val d: Int, var priority: Double)

    private val pools = ArrayList<Pool>()
    private val resident = HashMap<String, Resident>()
    /** Pieces with nothing in them (never take room). */
    private val empty = HashSet<String>()
    private val wanted = HashMap<String, Want>()
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.nativeOrder())
    /** Bumped whenever what's on the GPU changes (for feeds that would otherwise look the same as last frame). */
    var uploads = 0L; private set
    /** The most indices one draw can need: a whole pool. */
    var mostIndices = 0; private set

    fun key(bundle: MeshBundles.Bundle, piece: Int, rot: Rot) = "${bundle.name}|${rot.ordinal}|$piece"

    /** The piece when it's on the GPU; otherwise asked for (nearest first: lower [priority]) and null. */
    fun get(bundle: MeshBundles.Bundle, piece: Int, rot: Rot, w: Int, d: Int, priority: Double): Resident? {
        val k = key(bundle, piece, rot)
        resident[k]?.let { return it }
        if (k in empty || bundle.lens[piece] == 0) return null
        val prev = wanted[k]
        if (prev == null) wanted[k] = Want(bundle, piece, rot, w, d, priority) else if (priority < prev.priority) prev.priority = priority
        return null
    }

    /** Decodes and uploads what was asked for, nearest first, for at most [budgetNs]. */
    fun load(budgetNs: Long) {
        if (wanted.isEmpty()) return
        val start = System.nanoTime()
        val order = wanted.entries.sortedBy { it.value.priority }
        for ((k, w) in order) {
            if (System.nanoTime() - start > budgetNs) break
            wanted.remove(k)
            runCatching { upload(k, w) }.onFailure { empty += k }
        }
        // What wasn't reached is asked for again by the next frame that still wants it.
        wanted.clear()
    }

    private fun upload(k: String, w: Want) {
        val b = w.bundle
        val sprites = b.sprites() ?: return
        val quads = MeshCodec.counts(b.data, b.offs[w.piece], b.lens[w.piece]).sum()
        if (quads == 0) { empty += k; return }
        val bytes = quads * 4 * MeshCodec.VERTEX
        if (scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(Integer.highestOneBit(bytes) shl 1).order(ByteOrder.nativeOrder())
        scratch.clear()
        val counts = MeshCodec.decode(b.data, b.offs[w.piece], b.lens[w.piece], sprites, w.rot, w.w, w.d, scratch)
        scratch.flip()
        val vertices = quads * 4
        val pool = pools.firstOrNull { it.vertices - it.used >= vertices } ?: newPool(vertices) ?: return
        val first = pool.used
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(pool.buffer.slice(first.toLong() * MeshCodec.VERTEX, bytes.toLong()), scratch)
        pool.used += vertices
        resident[k] = Resident(pool.buffer, first, counts, IntArray(MeshCodec.GROUPS) { MeshCodec.turn(it, w.rot) })
        uploads++
    }

    private fun newPool(atLeast: Int): Pool? {
        if (pools.size >= MAX_POOLS) { clear(); return null }
        val n = maxOf(POOL_VERTICES, atLeast)
        val buf = RenderSystem.getDevice().createBuffer({ "devgineerclient pose pov pieces" }, GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST, n.toLong() * MeshCodec.VERTEX)
        if (n / 4 * 6 > mostIndices) mostIndices = n / 4 * 6
        return Pool(buf, n).also { pools += it }
    }

    /** Everything off the GPU (an atlas change, or the pools are full): pieces come back as they're wanted. */
    fun clear() {
        for (p in pools) p.buffer.close()
        pools.clear(); resident.clear(); empty.clear(); wanted.clear()
        uploads++
    }

    val poolCount get() = pools.size
}
