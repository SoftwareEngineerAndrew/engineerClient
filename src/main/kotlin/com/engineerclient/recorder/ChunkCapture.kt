package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo
import net.minecraft.util.Mth
import net.minecraft.util.SimpleBitStorage
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainerFactory
import net.minecraft.world.level.chunk.PalettedContainerRO
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Chunk packets decoded block for block: every section's block states and biomes, every block
 * entity with its full NBT (skull textures included), the heightmaps as plain heights and the light
 * arrays. The packet only carries these as one packed byte buffer, which says nothing to a reader
 * without a Minecraft to decode it; here they become a palette and a run-length list per section.
 *
 * Threads: [capture] runs on the network thread and only takes what the packet hands out (the
 * packet's own byte[] is never changed after decoding; the light arrays, heightmaps and block entity
 * tags, which the game goes on to use, are copied). The decoding itself runs later on the writer
 * thread over private [LevelChunkSection]s that nothing else sees, so their thread checks never fire.
 *
 * Line: `{"k":"in","p":"minecraft:level_chunk_with_light",...,"f":{"x","z","minSy","s":[{"i","y","n",
 * "fl","pal","rle"?,"bio","bioRle"?}],"be":[[x,y,z,type,snbt]],"hm":{type:[256 heights]},"light":{..}}}`.
 * The packet's raw frame is in the raw sidecar as well.
 */
object ChunkCapture {

    /** The Chunk Data setting: off writes only the chunk's coordinates (pushed in by DungeonRecorder). */
    @Volatile var enabled = true

    // ------------------------------------------------------------------ inputs taken on the network thread

    @Volatile private var factoryFor: RegistryAccess? = null
    @Volatile private var factoryCached: PalettedContainerFactory? = null

    /**
     * The palette factory for this connection's registries (block states and the server's biomes),
     * built once per registry access. The registry access is frozen, so reading it off the game
     * thread is safe.
     */
    fun factory(): PalettedContainerFactory? {
        val ra = runCatching { EngineerClient.mc.connection?.registryAccess() }.getOrNull() ?: return null
        if (factoryFor === ra) factoryCached?.let { return it }
        val f = runCatching { PalettedContainerFactory.create(ra) }.getOrNull() ?: return null
        factoryCached = f; factoryFor = ra
        return f
    }

    /** World bottom and height as told by a login or respawn packet, for the session it arrived in. */
    private class Dim(val minY: Int, val height: Int, val session: RecorderSession?)
    @Volatile private var dim: Dim? = null

    /**
     * Login and respawn packets (network thread): the dimension's bottom and height. Chunk packets for
     * a new dimension reach the network thread before the game thread has switched levels, so the
     * level the client holds then is the old one; the packet that announced the dimension is right.
     */
    fun observe(p: Packet<*>) {
        val info: CommonPlayerSpawnInfo = when (p) {
            is ClientboundLoginPacket -> p.commonPlayerSpawnInfo()
            is ClientboundRespawnPacket -> p.commonPlayerSpawnInfo()
            else -> return
        }
        runCatching { info.dimensionType().value() }.getOrNull()?.let { dim = Dim(it.minY(), it.height(), Rec.session) }
    }

    /** (minY, height): from this session's login/respawn packet, else the level the client holds. */
    fun worldHeight(): Pair<Int, Int>? {
        dim?.takeIf { it.session === Rec.session }?.let { return it.minY to it.height }
        val level = runCatching { EngineerClient.mc.level }.getOrNull() ?: return null
        return level.minY to level.height
    }

    private class BeRow(val x: Int, val y: Int, val z: Int, val type: String, val tag: CompoundTag?)

    /**
     * The body of a chunk packet's line, as a builder for the writer thread. Never throws: anything
     * that cannot be taken falls back to the generic packet writer (the packet's bytes in base64).
     */
    fun capture(p: ClientboundLevelChunkWithLightPacket): () -> String {
        val x = p.x; val z = p.z
        try { WorldCapture.chunkPacket(x, z) } catch (_: Throwable) {}
        if (!enabled) return { "{\"x\":$x,\"z\":$z,\"off\":true}" }
        return try {
            val factory = factory() ?: return PacketJson.capture(p)
            val (minY, height) = worldHeight() ?: return PacketJson.capture(p)
            val data = p.chunkData
            // The game hands these to the chunk (heightmaps copied, tags read), but copying is cheap.
            val hm = data.heightmaps.entries.map { it.key to it.value.clone() }
            val bes = ArrayList<BeRow>()
            data.getBlockEntitiesTagsConsumer(x, z).accept(ClientboundLevelChunkPacketData.BlockEntityTagOutput { pos, type, tag ->
                // pos is one mutable position reused for every entry: read it now.
                bes += BeRow(pos.x, pos.y, pos.z, BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type)?.toString() ?: type.toString(), tag?.copy())
            })
            val light = RichJson.copyLight(p.lightData)
            val minSy = minY shr 4
            ({ chunkBody(x, z, minY, height, minSy, factory, data, hm, bes, light) })
        } catch (t: Throwable) {
            val msg = t.toString()
            ({ "{\"x\":$x,\"z\":$z,\"@error\":${RecorderFiles.q(msg)}}" })
        }
    }

    private fun chunkBody(
        x: Int, z: Int, minY: Int, height: Int, minSy: Int, factory: PalettedContainerFactory,
        data: ClientboundLevelChunkPacketData, hm: List<Pair<Heightmap.Types, LongArray>>, bes: List<BeRow>, light: RichJson.LightCopy,
    ): String {
        val sb = StringBuilder(16384)
        sb.append("{\"x\":").append(x).append(",\"z\":").append(z).append(",\"minSy\":").append(minSy)
        RichJson.member(sb, "s") { sections(sb, data.readBuffer, minSy, factory); true }
        RichJson.member(sb, "be") {
            sb.append('[')
            bes.forEachIndexed { i, b ->
                if (i > 0) sb.append(',')
                sb.append('[').append(b.x).append(',').append(b.y).append(',').append(b.z).append(',')
                PacketJson.str(sb, b.type); sb.append(',')
                if (b.tag == null) sb.append("null") else PacketJson.str(sb, b.tag.toString())
                sb.append(']')
            }
            sb.append(']'); true
        }
        RichJson.member(sb, "hm") { heightmaps(sb, hm, minY, height); true }
        RichJson.member(sb, "light") { RichJson.writeLight(sb, light, minSy); true }
        sb.append('}')
        return sb.toString()
    }

    /**
     * Every section in the buffer, bottom up. The two counts at the front of each section (non-air
     * blocks, fluid blocks) are read before the section consumes them, since it keeps them private.
     */
    private fun sections(sb: StringBuilder, buf: FriendlyByteBuf, minSy: Int, factory: PalettedContainerFactory) {
        sb.append('[')
        var i = 0
        while (buf.readableBytes() > 0) {
            if (i > 0) sb.append(',')
            val at = buf.readerIndex()
            val nonEmpty = if (buf.readableBytes() >= 4) buf.getShort(at).toInt() else -1
            val fluid = if (buf.readableBytes() >= 4) buf.getShort(at + 2).toInt() else -1
            val sec = LevelChunkSection(factory)
            try {
                sec.read(buf)
            } catch (t: Throwable) {
                // A section that does not parse leaves the rest of the buffer unreadable; keep it raw.
                buf.readerIndex(at)
                val rest = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
                sb.append("{\"i\":").append(i).append(",\"y\":").append(minSy + i).append(",\"@error\":")
                PacketJson.str(sb, t.toString()); sb.append(",\"rest\":"); RichJson.b64(sb, rest); sb.append('}')
                break
            }
            sb.append("{\"i\":").append(i).append(",\"y\":").append(minSy + i)
                .append(",\"n\":").append(nonEmpty).append(",\"fl\":").append(fluid)
            section(sb, sec, factory)
            sb.append('}')
            i++
        }
        sb.append(']')
    }

    /** `,"pal":[..],"rle":[..],"bio":[..],"bioRle":[..]` of a private section (packed on this thread). */
    fun section(sb: StringBuilder, sec: LevelChunkSection, factory: PalettedContainerFactory) {
        RichJson.member(sb, "pal") { container(sb, "rle", sec.states.pack(factory.blockStatesStrategy()), 4096) { BlockStateParser.serialize(it) }; true }
        RichJson.member(sb, "bio") { container(sb, "bioRle", sec.biomes.pack(factory.biomeStrategy()), 64, ::biomeId); true }
    }

    fun biomeId(h: Holder<Biome>): String = h.unwrapKey().map { it.identifier().toString() }.orElse("@unregistered")

    /**
     * A packed container as its palette (`[names]`, already open after `"key":`) and, with more than
     * one entry, `,"<rleKey>":[count, paletteIndex, ...]` over its [size] entries in index order
     * (y, then z, then x fastest; 16 per axis for blocks, 4 for biomes).
     */
    fun <T : Any> container(sb: StringBuilder, rleKey: String, packed: PalettedContainerRO.PackedData<T>, size: Int, name: (T) -> String) {
        val pal = packed.paletteEntries()
        sb.append('[')
        pal.forEachIndexed { i, e -> if (i > 0) sb.append(','); PacketJson.str(sb, name(e)) }
        sb.append(']')
        if (pal.size <= 1) return
        sb.append(",\"").append(rleKey).append("\":")
        writeRle(sb, indices(packed, size))
    }

    /** The palette index of every entry of [packed] (all 0 when it has no storage: one entry). */
    fun <T : Any> indices(packed: PalettedContainerRO.PackedData<T>, size: Int): IntArray {
        val bits = packed.bitsPerEntry()
        val longs = packed.storage().map { it.toArray() }.orElse(null)
        if (longs == null || bits <= 0) return IntArray(size)
        val storage = SimpleBitStorage(bits, size, longs)
        return IntArray(size) { storage.get(it) }
    }

    /** `[count, value, count, value, ...]`: runs of equal values in order. */
    fun rle(values: IntArray): IntArray {
        if (values.isEmpty()) return IntArray(0)
        val out = ArrayList<Int>()
        var cur = values[0]; var n = 1
        for (i in 1 until values.size) {
            if (values[i] == cur) n++ else { out += n; out += cur; cur = values[i]; n = 1 }
        }
        out += n; out += cur
        return out.toIntArray()
    }

    /** The inverse of [rle]. */
    fun unrle(runs: IntArray): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 1 < runs.size) { repeat(runs[i]) { out += runs[i + 1] }; i += 2 }
        return out.toIntArray()
    }

    fun writeRle(sb: StringBuilder, values: IntArray) {
        val r = rle(values)
        sb.append('[')
        r.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(v) }
        sb.append(']')
    }

    /**
     * `{"<type>":[256 heights]}` in index order x + 16z, each the absolute y of the first free block
     * above the top one (as the game stores it, plus minY). A heightmap whose array does not fit the
     * world height is kept as its raw longs.
     */
    private fun heightmaps(sb: StringBuilder, hm: List<Pair<Heightmap.Types, LongArray>>, minY: Int, height: Int) {
        sb.append('{')
        hm.forEachIndexed { i, (type, longs) ->
            if (i > 0) sb.append(',')
            PacketJson.str(sb, type.serializationKey); sb.append(':')
            val heights = try { heights(longs, minY, height) } catch (_: Throwable) { null }
            if (heights == null) {
                sb.append("{\"raw\":["); longs.forEachIndexed { j, l -> if (j > 0) sb.append(','); PacketJson.num(sb, l) }; sb.append("]}")
            } else {
                sb.append('['); heights.forEachIndexed { j, h -> if (j > 0) sb.append(','); sb.append(h) }; sb.append(']')
            }
        }
        sb.append('}')
    }

    /** A packed heightmap's 256 heights as absolute y (throws if the array does not fit [height]). */
    fun heights(longs: LongArray, minY: Int, height: Int): IntArray {
        val storage = SimpleBitStorage(Mth.ceillog2(height + 1), 256, longs)
        return IntArray(256) { storage.get(it) + minY }
    }

    // ------------------------------------------------------------------ chunk biome updates

    /** `{"chunks":[{"x","z","minSy","s":[{"i","y","bio","bioRle"?}]}]}`: a biome-only update, decoded the same way. */
    fun biomes(p: ClientboundChunksBiomesPacket): () -> String {
        if (!enabled) return PacketJson.capture(p)
        return try {
            val factory = factory() ?: return PacketJson.capture(p)
            val (minY, _) = worldHeight() ?: return PacketJson.capture(p)
            val list = p.chunkBiomeData()
            val minSy = minY shr 4
            ({
                val sb = StringBuilder(1024)
                sb.append("{\"chunks\":[")
                list.forEachIndexed { k, d ->
                    if (k > 0) sb.append(',')
                    sb.append("{\"x\":").append(d.pos().x).append(",\"z\":").append(d.pos().z).append(",\"minSy\":").append(minSy)
                    RichJson.member(sb, "s") {
                        val buf = d.readBuffer
                        sb.append('[')
                        var i = 0
                        while (buf.readableBytes() > 0) {
                            if (i > 0) sb.append(',')
                            val sec = LevelChunkSection(factory)
                            sec.readBiomes(buf)
                            sb.append("{\"i\":").append(i).append(",\"y\":").append(minSy + i)
                            RichJson.member(sb, "bio") { container(sb, "bioRle", sec.biomes.pack(factory.biomeStrategy()), 64, ::biomeId); true }
                            sb.append('}')
                            i++
                        }
                        sb.append(']'); true
                    }
                    sb.append('}')
                }
                sb.append("]}")
                sb.toString()
            })
        } catch (t: Throwable) {
            PacketJson.capture(p)
        }
    }

    /** For keyframes: a block state's name (immutable, so any thread). */
    fun stateName(s: BlockState): String = BlockStateParser.serialize(s)
}
