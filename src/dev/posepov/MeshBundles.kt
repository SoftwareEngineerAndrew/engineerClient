package com.devgineerclient.posepov

import com.engineerclient.ads.pack.Rot
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
        /** A number of its own, for the GPU pool's keys. */
        val id = ids.incrementAndGet()
        @Volatile private var spriteIdx: IntArray? = null
        @Volatile private var spriteGen = -1
        /** Its textures as numbers in the sprite table (any thread, once the table is made); null until then. */
        fun spriteIndices(): IntArray? {
            val gen = CompactMesh.SpriteTable.generation
            spriteIdx?.takeIf { spriteGen == gen }?.let { return it }
            if (!CompactMesh.SpriteTable.ready) return null
            val sp = MeshCodec.sprites() ?: return null
            return IntArray(names.size) { CompactMesh.SpriteTable.of(sp.named(names[it])) }.also { spriteIdx = it; spriteGen = gen }
        }
    }

    private val ids = java.util.concurrent.atomic.AtomicInteger()

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
            val got = runCatching { download(name) }.getOrNull()
            val b = got?.let { runCatching { parse(name, it) }.getOrNull() }
            if (b != null) { MeshDisk.write(key(name), 0, got); loaded[name] = b; state.remove(name) }
            else state[name] = BUILD
        }
        return null
    }

    private const val PREFETCH_BYTES_PER_S = 4_000_000L
    private val prefetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "pose pov prefetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var prefetched = ""

    /**
     * Every bundle in [names] onto disk ahead of need, one at a time and no faster than [PREFETCH_BYTES_PER_S] (never a
     * dent in the game's connection): after a player's first dungeon, every room is on disk before it's ever seen. Only
     * to disk; memory takes a bundle when a room is placed. Once per version.
     */
    fun prefetch(names: List<String>) {
        if (version.isEmpty() || offline || prefetched == version) return
        prefetched = version
        val v = version
        prefetcher.execute {
            for (name in names) {
                if (version != v || offline) return@execute
                if (loaded.containsKey(name) || MeshDisk.has(key(name)) || state.putIfAbsent(name, FETCHING) != null) continue
                val t0 = System.nanoTime()
                val got = runCatching { download(name) }.getOrNull()
                if (got != null && runCatching { parse(name, got) }.isSuccess) MeshDisk.write(key(name), 0, got)
                state.remove(name)
                // Paced: as long as these bytes take at the cap.
                val wait = (got?.size ?: 0) * 1000L / PREFETCH_BYTES_PER_S - (System.nanoTime() - t0) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
            }
        }
    }

    private fun download(name: String): ByteArray? {
        val url = SITE + version + "/" + URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
        val res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (res.statusCode() != 200) { res.body().close(); return null }
        return res.body().use { s -> s.readNBytes(MAX_BYTES + 1) }.takeIf { it.size <= MAX_BYTES }
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

    /** How many bundles are in memory (one more arriving changes what a feed draws). */
    val count get() = loaded.size
    /** Their bytes in memory (for the performance monitor). */
    val bytes get() = loaded.values.sumOf { it.data.size.toLong() }

    fun cached(name: String): ByteArray? = MeshDisk.read(key(name))?.second
}


/**
 * The pieces on the GPU, in [CompactMesh]'s form: decoded and merged on worker threads only when a feed could show them (less
 * what nobody sees: [RoomCut]), then put into shared pools (one buffer holding many pieces, each piece's groups one after
 * another), already placed where they stand in the world (the frame at its corner), so a view draws a whole pool in one call.
 * A room's pieces go next to each other where there's room, so a view's draws of them run on into one. A piece nothing has
 * drawn for [IDLE_MS] gives its room back, and when the pools are at [MAX_POOLS] the ones unused longest go first, so the
 * memory stays under the cap however long the game runs. Render thread, except the decoding.
 */
object PiecePool {
    private const val POOL_VERTICES = 1 shl 21
    /** 8 pools of 32 MB: 256 MB at most (few, so a view's blocks are a few draws). */
    private const val MAX_POOLS = 8
    private const val IDLE_MS = 60_000L
    /** When the pools are full, pieces unused this long make room. */
    private const val CROWDED_IDLE_MS = 2_000L
    private const val IN_FLIGHT = 64

    class Resident internal constructor(val buffer: GpuBuffer, val first: Int, val counts: IntArray, internal val pool: Pool, internal val vertices: Int) {
        internal var usedAt = 0L
    }
    internal class Pool(val buffer: GpuBuffer, val vertices: Int) {
        /** Free runs: first vertex to length. */
        val free = java.util.TreeMap<Int, Int>().apply { put(0, vertices) }
        var used = 0

        fun alloc(n: Int): Int {
            for ((at, len) in free) if (len >= n) {
                free.remove(at)
                if (len > n) free[at + n] = len - n
                used += n
                return at
            }
            return -1
        }

        /** A run of [n] starting exactly at [at] when that's free; -1 otherwise. */
        fun allocAt(at: Int, n: Int): Int {
            val (s, len) = free.floorEntry(at) ?: return -1
            if (s + len < at + n) return -1
            free.remove(s)
            if (at > s) free[s] = at - s
            if (s + len > at + n) free[at + n] = s + len - at - n
            used += n
            return at
        }

        fun release(at: Int, n: Int) {
            used -= n
            var start = at; var len = n
            free.floorEntry(at)?.let { (s, l) -> if (s + l == at) { free.remove(s); start = s; len += l } }
            free[start + len]?.let { l -> free.remove(start + len); len += l }
            free[start] = len
        }
    }
    private class Want(val bundle: MeshBundles.Bundle, val piece: Int, val rot: Rot, val w: Int, val d: Int, val room: KnownWorld.Room?, val pockets: Boolean, var priority: Double)
    private class Done(val key: Long, val epoch: Int, val packed: CompactMesh.Packed?, val room: Long)

    private val pools = ArrayList<Pool>()
    private val resident = HashMap<Long, Resident>()
    /** Pieces with nothing in them (never take room). */
    private val empty = HashSet<Long>()
    private val wanted = HashMap<Long, Want>()
    private val inFlight = HashSet<Long>()
    private val done = java.util.concurrent.ConcurrentLinkedQueue<Done>()
    private var epoch = 0
    private val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "pose pov decode").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val quads = ThreadLocal.withInitial { CompactMesh.Quads(1024) }
    private var now = 0L
    private var idleCheckAt = 0L
    /** Bumped whenever what's on the GPU changes (a piece arrives or goes): what a feed resolved to draw is stale. */
    var version = 0L; private set
    /** The most indices one piece needs. */
    var mostIndices = 0; private set
    var evictions = 0L; private set
    /** Bumped for every ask for a piece that isn't on the GPU yet (one that is never: nothing is missing). */
    var asked = 0L; private set

    /** A piece of [bundle] turned [rot], its frame's corner at world ([ox], [oz]), with or without its room's sealed pockets. */
    fun key(bundle: MeshBundles.Bundle, piece: Int, rot: Rot, ox: Int, oz: Int, pockets: Boolean): Long =
        (bundle.id.toLong() shl 44) or (piece.toLong() shl 28) or (rot.ordinal.toLong() shl 26) or ((if (pockets) 1L else 0L) shl 25) or
            (((ox + 512).toLong() and 1023L) shl 10) or ((oz + 512).toLong() and 1023L)

    /**
     * The piece when it's on the GPU (and now in use); otherwise asked for (nearest first: lower [priority]) and null. A [room]'s
     * piece is placed with its box's corner where the room's is, less what nobody sees of it ([KnownWorld.Room.cut]); the arena's
     * (no room) where it is.
     */
    fun get(bundle: MeshBundles.Bundle, piece: Int, rot: Rot, w: Int, d: Int, room: KnownWorld.Room?, priority: Double): Resident? {
        val pockets = room?.cutPockets ?: false
        val k = key(bundle, piece, rot, room?.x0 ?: 0, room?.z0 ?: 0, pockets)
        resident[k]?.let { it.usedAt = now; return it }
        if (k in empty || bundle.lens[piece] == 0) return null
        asked++
        if (k in inFlight) return null
        val prev = wanted[k]
        if (prev == null) wanted[k] = Want(bundle, piece, rot, w, d, room, pockets, priority) else if (priority < prev.priority) prev.priority = priority
        return null
    }

    /** Uploads what the workers finished for at most [budgetNs], then hands them what was asked for since, nearest first. */
    fun load(budgetNs: Long) {
        now = System.currentTimeMillis()
        if (!CompactMesh.SpriteTable.ensure()) return
        val start = System.nanoTime()
        while (System.nanoTime() - start < budgetNs) {
            val d = done.poll() ?: break
            if (d.epoch != epoch) continue
            inFlight.remove(d.key)
            val p = d.packed
            if (p == null || p.quads == 0) { empty += d.key; continue }
            val t0 = System.nanoTime()
            upload(d.key, p, d.room)
            PovPerf.add(PovPerf.UPLOAD, System.nanoTime() - t0)
            PovPerf.count(PovPerf.C_UPLOADS); PovPerf.count(PovPerf.C_UPLOAD_BYTES, p.bytes.remaining().toLong())
        }
        if (now - idleCheckAt > 1000) { idleCheckAt = now; evict(IDLE_MS, Int.MAX_VALUE) }
        if (wanted.isEmpty()) return
        val room = IN_FLIGHT - inFlight.size
        if (room > 0) for ((k, w) in wanted.entries.sortedBy { it.value.priority }.take(room)) {
            inFlight += k
            val e = epoch
            workers.execute {
                val t0 = System.nanoTime()
                val packed = runCatching {
                    val b = w.bundle
                    val sprites = b.spriteIndices() ?: return@runCatching null
                    val q = quads.get()
                    MeshCodec.decode(b.data, b.offs[w.piece], b.lens[w.piece], sprites, w.rot, w.w, w.d, q, w.room?.cut(), w.pockets)
                    CompactMesh.pack(q, -(w.room?.x0 ?: 0).toFloat(), -(w.room?.z0 ?: 0).toFloat())
                }.getOrNull()
                PovPerf.add(PovPerf.DECODE, System.nanoTime() - t0)
                // Its room: the key less the piece, so the room's pieces can go side by side.
                done += Done(k, e, packed, k and (0xFFFFL shl 28).inv())
            }
        }
        // What wasn't handed out is asked for again by the next frame that still wants it.
        wanted.clear()
    }

    /** The last piece of each room put up (see [Done.room]): the next goes right after it when that's free. */
    private val lastOfRoom = HashMap<Long, Resident>()

    private fun upload(k: Long, p: CompactMesh.Packed, room: Long) {
        val r = place(p, lastOfRoom[room]?.takeIf { it.pool in pools }) ?: return
        r.usedAt = now
        resident[k] = r
        if (lastOfRoom.size > 2048) lastOfRoom.clear()
        lastOfRoom[room] = r
    }

    /**
     * [p] into the pools (right after [after] when that's free, else the first space; a new pool, or room made from the
     * longest unused pieces, when none has any); null when it can't fit.
     */
    private fun place(p: CompactMesh.Packed, after: Resident? = null): Resident? {
        val vertices = p.quads * 4
        var pool: Pool? = null; var at = -1
        fun tryAlloc(): Boolean {
            if (after != null) { val a = after.pool.allocAt(after.first + after.vertices, vertices); if (a >= 0) { pool = after.pool; at = a; return true } }
            for (pl in pools) { val a = pl.alloc(vertices); if (a >= 0) { pool = pl; at = a; return true } }
            return false
        }
        if (!tryAlloc()) {
            if (pools.size < MAX_POOLS) newPool(vertices)
            else evict(CROWDED_IDLE_MS, vertices)
            if (!tryAlloc()) return null
        }
        val pl = pool!!
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(pl.buffer.slice(at.toLong() * CompactMesh.VERTEX, p.bytes.remaining().toLong()), p.bytes)
        version++
        if (p.quads * 6 > mostIndices) mostIndices = p.quads * 6
        return Resident(pl.buffer, at, p.counts, pl, vertices)
    }

    /**
     * This run's own geometry (world sections, a room's changed pieces) in the same pools as the pieces, so a feed draws it
     * with them in a few calls rather than one buffer each. Never evicted: [release] gives it back.
     */
    fun putOwn(p: CompactMesh.Packed): Resident? = place(p)

    fun release(r: Resident) {
        if (r.pool !in pools) return
        r.pool.release(r.first, r.vertices); version++
    }

    /** Frees pieces unused for [idleMs], longest unused first, until a run of [need] vertices is free somewhere (or all of them). */
    private fun evict(idleMs: Long, need: Int) {
        val old = resident.entries.filter { now - it.value.usedAt > idleMs }.sortedBy { it.value.usedAt }
        for ((k, r) in old) {
            resident.remove(k); r.pool.release(r.first, r.vertices)
            evictions++; version++
            if (need != Int.MAX_VALUE && r.pool.free.values.any { it >= need }) return
        }
    }

    private fun newPool(atLeast: Int) {
        val n = maxOf(POOL_VERTICES, atLeast)
        val buf = RenderSystem.getDevice().createBuffer({ "devgineerclient pose pov pieces" }, GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST, n.toLong() * CompactMesh.VERTEX)
        pools += Pool(buf, n)
    }

    /** Everything off the GPU (an atlas change): pieces come back as they're wanted. */
    fun clear() {
        for (p in pools) p.buffer.close()
        pools.clear(); resident.clear(); empty.clear(); wanted.clear(); inFlight.clear(); done.clear(); lastOfRoom.clear()
        epoch++; version++
    }

    val poolCount get() = pools.size
    val residentCount get() = resident.size
    /** Bytes the pieces take, and the pools hold. */
    val usedBytes get() = pools.sumOf { it.used.toLong() } * CompactMesh.VERTEX
    val poolBytes get() = pools.sumOf { it.vertices.toLong() } * CompactMesh.VERTEX
    const val CAP_BYTES = MAX_POOLS.toLong() * POOL_VERTICES * CompactMesh.VERTEX
    /** Pieces being decoded or waiting to go up. */
    val queued get() = inFlight.size
}
