package com.engineerclient.recorder

import com.google.gson.JsonElement
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.Encoder
import com.mojang.serialization.JsonOps
import com.odtheking.odin.utils.itemId
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.EntityType
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import java.util.Base64
import java.util.BitSet

/**
 * The game's rich values written without losing anything: text with its styles, click and hover
 * events and translation keys; items with every component; particles with their options; light
 * arrays byte for byte. Each goes through the game's own codec where one exists (so a reader can
 * decode it back exactly) next to a plain form a person or an LLM can read at a glance.
 *
 * Everything here is safe to call from any thread for immutable inputs. ItemStacks are mutable:
 * call [itemNow] on the thread that owns the stack (the network thread for a packet before the game
 * applies it, the game thread for inventory), never on the writer thread.
 */
object RichJson {

    // ------------------------------------------------------------------ text

    /**
     * `{"t":plain,"j":json}`. j is the component as the game's own JSON (styles, click/hover, translate
     * keys and their arguments); it is left out when it is just the plain string again.
     */
    fun component(sb: StringBuilder, c: Component) {
        val t = c.string
        sb.append("{\"t\":"); PacketJson.str(sb, t)
        val j = encode(ComponentSerialization.CODEC, c).first
        if (j != null && !(j.isJsonPrimitive && j.asJsonPrimitive.isString && j.asString == t)) {
            val mark = sb.length
            try { sb.append(",\"j\":"); json(sb, j) } catch (e: Throwable) { sb.setLength(mark) }
        }
        sb.append('}')
    }

    // ------------------------------------------------------------------ items

    /** [item] as a finished string, for freezing a stack on the thread that owns it. */
    fun itemNow(s: ItemStack): String = StringBuilder(256).also { item(it, s) }.toString()

    /**
     * `{"id","count","sb","name","lore","full","cd"}`: the vanilla id and count, Odin's Skyblock id,
     * the plain name and every lore line, the whole stack through the game's codec ("full"; if the
     * codec refuses an odd server stack, its component patch as "patch" instead) and the custom data
     * as SNBT, which is where Skyblock keeps uuids, enchants and the like. An empty stack is null.
     */
    fun item(sb: StringBuilder, s: ItemStack) {
        if (s.isEmpty) { sb.append("null"); return }
        sb.append("{\"id\":"); PacketJson.str(sb, BuiltInRegistries.ITEM.getKey(s.item).toString())
        sb.append(",\"count\":").append(s.count)
        member(sb, "sb") {
            val id = runCatching { s.itemId }.getOrNull()
            if (id.isNullOrEmpty()) false else { PacketJson.str(sb, id); true }
        }
        member(sb, "name") { PacketJson.str(sb, s.hoverName.string); true }
        member(sb, "lore") {
            val lines = s.get(DataComponents.LORE)?.lines()
            if (lines.isNullOrEmpty()) false else {
                sb.append('['); lines.forEachIndexed { i, l -> if (i > 0) sb.append(','); PacketJson.str(sb, l.string) }; sb.append(']'); true
            }
        }
        val (full, err) = encode(ItemStack.OPTIONAL_CODEC, s)
        if (full != null) member(sb, "full") { json(sb, full); true }
        else {
            member(sb, "patch") { val p = encode(DataComponentPatch.CODEC, s.componentsPatch).first; if (p == null) false else { json(sb, p); true } }
            if (err != null) member(sb, "fullErr") { PacketJson.str(sb, err); true }
        }
        member(sb, "cd") {
            val cd = s.get(DataComponents.CUSTOM_DATA)
            if (cd == null) false else { PacketJson.str(sb, cd.copyTag().toString()); true }
        }
        sb.append('}')
    }

    /** An item as a recipe or loot result: tagged, since it is not a stack in anyone's inventory. */
    fun template(sb: StringBuilder, t: ItemStackTemplate) {
        sb.append("{\"@c\":\"ItemStackTemplate\"")
        member(sb, "id") { PacketJson.str(sb, t.item().unwrapKey().map { it.identifier().toString() }.orElse(t.item().value().toString())); true }
        sb.append(",\"count\":").append(t.count())
        val (full, _) = encode(ItemStackTemplate.CODEC, t)
        if (full != null) member(sb, "full") { json(sb, full); true }
        else member(sb, "patch") { val p = encode(DataComponentPatch.CODEC, t.components()).first; if (p == null) false else { json(sb, p); true } }
        sb.append('}')
    }

    // ------------------------------------------------------------------ particles, registries

    /** `{"type":id,"opts":json}`: the particle's registry id and its options (colour, block, item...). */
    fun particle(sb: StringBuilder, p: ParticleOptions) {
        sb.append("{\"type\":")
        val key = runCatching { BuiltInRegistries.PARTICLE_TYPE.getKey(p.type) }.getOrNull()
        if (key == null) sb.append("null") else PacketJson.str(sb, key.toString())
        val opts = encode(ParticleTypes.CODEC, p).first
        if (opts != null) member(sb, "opts") { json(sb, opts); true }
        sb.append('}')
    }

    /**
     * A built-in registry entry's id, or null if [v] is not one (or not registered). These classes hold
     * their whole registry neighbourhood (block sets, factories), so reflecting them is both huge and
     * useless; the id is what a packet means by them.
     */
    fun registryId(v: Any): String? = when (v) {
        is MenuType<*> -> BuiltInRegistries.MENU.getKey(v)
        is EntityType<*> -> BuiltInRegistries.ENTITY_TYPE.getKey(v)
        is Block -> BuiltInRegistries.BLOCK.getKey(v)
        is Item -> BuiltInRegistries.ITEM.getKey(v)
        is BlockEntityType<*> -> BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(v)
        is ParticleType<*> -> BuiltInRegistries.PARTICLE_TYPE.getKey(v)
        is DataComponentType<*> -> BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(v)
        is SoundEvent -> BuiltInRegistries.SOUND_EVENT.getKey(v)
        is MobEffect -> BuiltInRegistries.MOB_EFFECT.getKey(v)
        else -> null
    }?.toString()

    // ------------------------------------------------------------------ light

    /**
     * The light arrays of a chunk or light packet, copied. The client hands these byte arrays to its
     * light engine without copying and goes on to change them, so they are copied on the network
     * thread (before the game applies the packet) and written later.
     */
    class LightCopy(
        val skyMask: LongArray, val skyEmpty: LongArray, val sky: List<ByteArray>,
        val blockMask: LongArray, val blockEmpty: LongArray, val block: List<ByteArray>,
    ) {
        /** Bytes this copy holds (for the queue's memory cap). */
        val bytes: Int get() = 8 * (skyMask.size + skyEmpty.size + blockMask.size + blockEmpty.size) + sky.sumOf { it.size } + block.sumOf { it.size }
    }

    fun copyLight(d: ClientboundLightUpdatePacketData) = LightCopy(
        d.skyYMask.toLongArray(), d.emptySkyYMask.toLongArray(), d.skyUpdates.map { it.clone() },
        d.blockYMask.toLongArray(), d.emptyBlockYMask.toLongArray(), d.blockUpdates.map { it.clone() },
    )

    /** [writeLight] of a copy taken now (call on the thread that owns [d]). */
    fun light(sb: StringBuilder, d: ClientboundLightUpdatePacketData, minSectionY: Int?) = writeLight(sb, copyLight(d), minSectionY)

    /**
     * `{"sky":{"mask":[longs],"empty":[longs],"arrays":{"<bit>":"b64"}},"block":{...},"y0"?}`. Bit i
     * of a mask is light section i, which is section y = y0 + i (the bottom light section sits one
     * below the world); arrays are keyed by that bit, each 2048 bytes of nibbles.
     */
    fun writeLight(sb: StringBuilder, l: LightCopy, minSectionY: Int?) {
        sb.append("{\"sky\":"); lightLayer(sb, l.skyMask, l.skyEmpty, l.sky)
        sb.append(",\"block\":"); lightLayer(sb, l.blockMask, l.blockEmpty, l.block)
        if (minSectionY != null) sb.append(",\"y0\":").append(minSectionY - 1)
        sb.append('}')
    }

    private fun lightLayer(sb: StringBuilder, mask: LongArray, empty: LongArray, arrays: List<ByteArray>) {
        sb.append("{\"mask\":"); longs(sb, mask)
        sb.append(",\"empty\":"); longs(sb, empty)
        sb.append(",\"arrays\":{")
        val bits = BitSet.valueOf(mask)
        var i = bits.nextSetBit(0)
        var k = 0
        while (i >= 0 && k < arrays.size) {
            if (k > 0) sb.append(',')
            sb.append('"').append(i).append("\":\"").append(Base64.getEncoder().encodeToString(arrays[k])).append('"')
            k++
            i = bits.nextSetBit(i + 1)
        }
        sb.append('}')
        // More arrays than mask bits would be a malformed packet; keep them rather than lose them.
        if (k < arrays.size) {
            sb.append(",\"extra\":[")
            for (j in k until arrays.size) { if (j > k) sb.append(','); sb.append('"').append(Base64.getEncoder().encodeToString(arrays[j])).append('"') }
            sb.append(']')
        }
        sb.append('}')
    }

    private fun longs(sb: StringBuilder, a: LongArray) {
        sb.append('['); a.forEachIndexed { i, v -> if (i > 0) sb.append(','); PacketJson.num(sb, v) }; sb.append(']')
    }

    // ------------------------------------------------------------------ bytes and JSON

    /** `{"len":n,"b64":"..."}`. */
    fun b64(sb: StringBuilder, bytes: ByteArray) {
        sb.append("{\"len\":").append(bytes.size).append(",\"b64\":\"").append(Base64.getEncoder().encodeToString(bytes)).append("\"}")
    }

    /** A Gson tree written with the recorder's own number and string rules (NaN as a string, no HTML escaping). */
    fun json(sb: StringBuilder, e: JsonElement?) {
        when {
            e == null || e.isJsonNull -> sb.append("null")
            e.isJsonPrimitive -> {
                val p = e.asJsonPrimitive
                when {
                    p.isBoolean -> sb.append(p.asBoolean)
                    p.isNumber -> PacketJson.num(sb, p.asNumber)
                    else -> PacketJson.str(sb, p.asString)
                }
            }
            e.isJsonArray -> {
                sb.append('[')
                e.asJsonArray.forEachIndexed { i, x -> if (i > 0) sb.append(','); json(sb, x) }
                sb.append(']')
            }
            else -> {
                sb.append('{')
                var first = true
                for ((k, x) in e.asJsonObject.entrySet()) {
                    if (!first) sb.append(',')
                    first = false
                    PacketJson.str(sb, k); sb.append(':'); json(sb, x)
                }
                sb.append('}')
            }
        }
    }

    fun jsonNow(e: JsonElement?): String = StringBuilder().also { json(it, e) }.toString()

    // ------------------------------------------------------------------ helpers

    @Volatile private var opsFor: Any? = null
    @Volatile private var opsCached: DynamicOps<JsonElement>? = null

    /**
     * Registry-aware JSON ops while connected (so registry entries encode by name and dynamic ones,
     * like enchantments, encode at all), plain JSON otherwise. Cached per registry access: building
     * the context per stack would cost more than the encoding.
     */
    fun ops(): DynamicOps<JsonElement> {
        val ra = runCatching { (Minecraft.getInstance() as Minecraft?)?.connection?.registryAccess() }.getOrNull() ?: return JsonOps.INSTANCE
        if (opsFor === ra) opsCached?.let { return it }
        val o = runCatching { ra.createSerializationContext(JsonOps.INSTANCE) }.getOrNull() ?: return JsonOps.INSTANCE
        opsCached = o; opsFor = ra
        return o
    }

    /**
     * [v] through [codec]: with the registry-aware ops first, plain JSON ops if that fails. Returns
     * the JSON, or null and the codec's error message.
     */
    fun <T> encode(codec: Encoder<T>, v: T): Pair<JsonElement?, String?> {
        var err: String? = null
        val first = ops()
        try {
            val r = codec.encodeStart(first, v)
            r.result().orElse(null)?.let { return it to null }
            err = r.error().map { it.message() }.orElse(null)
        } catch (t: Throwable) { err = t.toString() }
        if (first !== JsonOps.INSTANCE) {
            try {
                val r = codec.encodeStart(JsonOps.INSTANCE, v)
                r.result().orElse(null)?.let { return it to null }
            } catch (_: Throwable) {}
        }
        return null to err
    }

    /**
     * `,"key":value` written by [body], which returns false to leave the member out. A failure takes
     * back whatever was written and leaves `"key":{"@error":...}` instead, so the line stays valid.
     */
    internal inline fun member(sb: StringBuilder, key: String, body: () -> Boolean) {
        val mark = sb.length
        try {
            sb.append(",\"").append(key).append("\":")
            if (!body()) sb.setLength(mark)
        } catch (t: Throwable) {
            sb.setLength(mark)
            sb.append(",\"").append(key).append("\":")
            PacketJson.error(sb, t)
        }
    }
}
