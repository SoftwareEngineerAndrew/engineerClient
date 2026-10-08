package com.devgineerclient.recorder

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import net.minecraft.client.Minecraft
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.Vec3i
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket
import net.minecraft.network.protocol.login.ClientboundHelloPacket
import net.minecraft.network.protocol.login.ServerboundKeyPacket
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.BitSet
import java.util.IdentityHashMap
import java.util.Optional
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.OptionalLong
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Any object (a packet, mostly) as JSON, read by reflection, losing nothing: every instance field by
 * its real name (26.1 is unobfuscated, so names are Mojang's), each reflected object tagged with its
 * concrete class ("@c"), and the game's own types written through [RichJson] - text with its styles,
 * items through the item codec, particles with their options, registry entries by id, NBT as SNBT,
 * bytes as base64. Numbers are exact (shortest round-trip doubles; longs past 2^53 as strings).
 *
 * There are no caps on lists, strings or depth: one odd packet may make a long line, which is the
 * point. Only a real cycle ("@cycle") or a 64-deep nest ("@depth") stops the walk. A field that
 * fails to read takes back what it wrote and leaves `{"@error":...}`, so a line is always valid JSON.
 *
 * Packets holding ItemStacks or other mutable state are written in full on the thread that taps them
 * ([capture]); everything else is written later on the recorder's writer thread.
 */
object PacketJson {

    private const val SAFETY_DEPTH = 64
    private const val MAX_SAFE = 1L shl 53

    /** Server cookies may hold session tokens: their bytes are written only when this is on ('Cookie Payloads'). */
    @Volatile var cookiePayloads = false

    private class ClassInfo(val fields: List<Field>, val inaccessible: Boolean)
    private val classes = ConcurrentHashMap<Class<*>, ClassInfo>()
    private val notedEnums = ConcurrentHashMap.newKeySet<Class<*>>()
    private val hasByteBuf = ConcurrentHashMap<Class<*>, Boolean>()

    /** A packet's type: its protocol id, e.g. `minecraft:move_entity_pos`. */
    fun type(p: Packet<*>): String =
        runCatching { p.type().id().toString() }.getOrElse { p.javaClass.simpleName }

    // ------------------------------------------------------------------ capture

    /**
     * The body of a packet line (its "f" object, decoded extras included) as a builder. Called on the
     * thread that taps the packet (network thread inbound, the sender outbound). Packets that hold
     * ItemStacks, entity data or a ByteBuf are written now, on this thread, before the game applies
     * (and mutates) them; the rest return a builder that runs later on the writer thread. Never throws.
     */
    fun capture(p: Packet<*>): () -> String {
        val dec = try { PacketDecode.capture(p) } catch (t: Throwable) { PacketDecode.failed(t) }
        if (freezes(p)) {
            val s = body(p, dec)
            // A finished string held in the queue: the memory cap must count all of it.
            return Sized(2 * s.length) { s }
        }
        return { body(p, dec) }
    }

    /** Whether [p] must be written on the tapping thread. ServerboundContainerClickPacket is left lazy: its HashedStacks are immutable. */
    fun freezes(p: Any): Boolean = when (p) {
        is ClientboundContainerSetContentPacket, is ClientboundContainerSetSlotPacket, is ClientboundSetCursorItemPacket,
        is ClientboundSetPlayerInventoryPacket, is ClientboundSetEquipmentPacket, is ClientboundSetEntityDataPacket,
        is ClientboundMerchantOffersPacket, is ServerboundSetCreativeModeSlotPacket -> true
        else -> hasByteBuf.getOrPut(p.javaClass) { info(p.javaClass).fields.any { ByteBuf::class.java.isAssignableFrom(it.type) } }
    }

    private fun body(p: Any, dec: PacketDecode.Decoded?): String {
        val sb = StringBuilder(256)
        try {
            Writer(sb).obj(p, 0, dec)
        } catch (t: Throwable) {
            sb.setLength(0); error(sb, t)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ generic writer

    fun writeNow(v: Any?): String = StringBuilder().also { write(it, v, 0) }.toString()

    /** [v] at nesting [depth], appended to [sb]. Never leaves half a value: a failure becomes `{"@error":...}`. */
    fun write(sb: StringBuilder, v: Any?, depth: Int) {
        val mark = sb.length
        try { Writer(sb).value(v, depth) } catch (t: Throwable) { sb.setLength(mark); error(sb, t) }
    }

    /** One walk: the stack of objects being written, for the cycle guard (a DAG's shared parts are written each time). */
    private class Writer(val sb: StringBuilder) {
        private val path: MutableSet<Any> = java.util.Collections.newSetFromMap(IdentityHashMap())

        fun value(v: Any?, depth: Int) {
            if (depth >= SAFETY_DEPTH && v != null && !isLeaf(v)) {
                sb.append("{\"@depth\":"); str(sb, runCatching { v.toString() }.getOrElse { it.toString() }); sb.append('}'); return
            }
            when (v) {
                null -> sb.append("null")
                is String -> str(sb, v)
                is Boolean -> sb.append(v)
                is Char -> str(sb, v.toString())
                is Number -> num(sb, v)
                is Enum<*> -> enumName(v)
                is Class<*> -> str(sb, v.name)
                is UUID, is Identifier -> str(sb, v.toString())
                is Component -> RichJson.component(sb, v)
                is ItemStack -> RichJson.item(sb, v)
                is ItemStackTemplate -> RichJson.template(sb, v)
                is ParticleOptions -> RichJson.particle(sb, v)
                is BlockState -> str(sb, BlockStateParser.serialize(v))
                is BlockPos -> sb.append('[').append(v.x).append(',').append(v.y).append(',').append(v.z).append(']')
                is Vec3i -> sb.append('[').append(v.x).append(',').append(v.y).append(',').append(v.z).append(']')
                is Vec3 -> { sb.append('['); num(sb, v.x); sb.append(','); num(sb, v.y); sb.append(','); num(sb, v.z); sb.append(']') }
                is Vec2 -> { sb.append('['); num(sb, v.x); sb.append(','); num(sb, v.y); sb.append(']') }
                is ChunkPos -> sb.append('[').append(v.x).append(',').append(v.z).append(']')
                is ResourceKey<*> -> str(sb, v.identifier().toString())
                is Holder<*> -> { val k = v.unwrapKey(); if (k.isPresent) str(sb, k.get().identifier().toString()) else value(v.value(), depth + 1) }
                is SynchedEntityData.DataValue<*> -> {
                    sb.append('[').append(v.id()).append(',').append(EntityDataSerializers.getSerializedId(v.serializer())).append(',')
                    element(v.value(), depth + 1); sb.append(']')
                }
                is com.mojang.authlib.GameProfile -> profile(v)
                is Tag -> { sb.append("{\"snbt\":"); str(sb, v.toString()); sb.append('}') }
                is Optional<*> -> value(v.orElse(null), depth)
                is OptionalInt -> if (v.isPresent) sb.append(v.asInt) else sb.append("null")
                is OptionalLong -> if (v.isPresent) num(sb, v.asLong) else sb.append("null")
                is OptionalDouble -> if (v.isPresent) num(sb, v.asDouble) else sb.append("null")
                is ByteBuf -> RichJson.b64(sb, ByteBufUtil.getBytes(v, v.readerIndex(), v.readableBytes()))
                is ByteArray -> RichJson.b64(sb, v)
                is BitSet -> { sb.append("{\"bits\":["); v.toLongArray().forEachIndexed { i, l -> if (i > 0) sb.append(','); num(sb, l) }; sb.append("]}") }
                is IntArray -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append(x) }; sb.append(']') }
                is LongArray -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); num(sb, x) }; sb.append(']') }
                is ShortArray -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append(x) }; sb.append(']') }
                is FloatArray -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); num(sb, x) }; sb.append(']') }
                is DoubleArray -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); num(sb, x) }; sb.append(']') }
                is BooleanArray -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append(x) }; sb.append(']') }
                is CharArray -> str(sb, String(v))
                is Array<*> -> nested(v, depth) { list(v.asIterable(), depth) }
                is Map<*, *> -> nested(v, depth) { map(v, depth) }
                is com.google.common.collect.Multimap<*, *> -> nested(v, depth) { map(v.asMap(), depth) }
                is com.mojang.datafixers.util.Pair<*, *> -> { sb.append('['); element(v.first, depth + 1); sb.append(','); element(v.second, depth + 1); sb.append(']') }
                is Iterable<*> -> nested(v, depth) { list(v, depth) }
                else -> {
                    val id = RichJson.registryId(v)
                    if (id != null) str(sb, id) else obj(v, depth, null)
                }
            }
        }

        private fun isLeaf(v: Any) = v is String || v is Number || v is Boolean || v is Enum<*> || v is UUID || v is Identifier

        private inline fun nested(v: Any, depth: Int, body: () -> Unit) {
            if (!path.add(v)) { sb.append("{\"@cycle\":"); str(sb, simpleName(v.javaClass)); sb.append('}'); return }
            try { body() } finally { path.remove(v) }
        }

        /** One element, field or map value: on failure, what it wrote is taken back and an error left in its place. */
        fun element(v: Any?, depth: Int) {
            val mark = sb.length
            try { value(v, depth) } catch (t: Throwable) { sb.setLength(mark); error(sb, t) }
        }

        private fun list(items: Iterable<*>, depth: Int) {
            sb.append('[')
            var first = true
            val it = items.iterator()
            while (true) {
                // A collection whose iterator fails part way keeps what it gave and ends with the error.
                val x = try { if (!it.hasNext()) break; it.next() } catch (t: Throwable) {
                    if (!first) sb.append(',')
                    error(sb, t); break
                }
                if (!first) sb.append(',')
                first = false
                element(x, depth + 1)
            }
            sb.append(']')
        }

        private fun map(m: Map<*, *>, depth: Int) {
            sb.append('{')
            var first = true
            for ((k, x) in m) {
                if (!first) sb.append(',')
                first = false
                str(sb, key(k)); sb.append(':')
                element(x, depth + 1)
            }
            sb.append('}')
        }

        private fun key(k: Any?): String = when (k) {
            null -> "null"
            is Holder<*> -> k.unwrapKey().map { it.identifier().toString() }.orElseGet { k.toString() }
            is ResourceKey<*> -> k.identifier().toString()
            is Enum<*> -> k.name
            else -> RichJson.registryId(k) ?: k.toString()
        }

        private fun enumName(v: Enum<*>) {
            val c = v.declaringJavaClass
            if (notedEnums.add(c)) RecorderFiles.noteEnum(c)
            str(sb, v.name)
        }

        private fun profile(g: com.mojang.authlib.GameProfile) {
            sb.append("{\"id\":"); str(sb, g.id().toString())
            sb.append(",\"name\":"); str(sb, g.name())
            sb.append(",\"props\":[")
            var first = true
            for (p in g.properties().values()) {
                if (!first) sb.append(',')
                first = false
                sb.append("{\"name\":"); str(sb, p.name()); sb.append(",\"value\":"); str(sb, p.value())
                sb.append(",\"sig\":"); val sig: String? = p.signature(); if (sig == null) sb.append("null") else str(sb, sig)
                sb.append('}')
            }
            sb.append("]}")
        }

        /**
         * A reflected object: `{"@c":"<class>",field:value,...}`. With [dec] (a packet's top level) the
         * fields it replaces are left out and its decoded members are added at the end.
         */
        fun obj(v: Any, depth: Int, dec: PacketDecode.Decoded?) {
            val c = v.javaClass
            // Objects that are not data: codecs and lambdas say what they are; the game's live objects
            // (a level, an entity, the client) would drag the whole game into one line.
            if (c.name.startsWith("com.mojang.serialization.") || c.isSynthetic || c.isHidden || v is Level || v is Minecraft || v is Entity) {
                sb.append("{\"@c\":"); str(sb, simpleName(c))
                if (v is Entity) sb.append(",\"id\":").append(v.id)
                sb.append(",\"str\":"); str(sb, runCatching { v.toString() }.getOrElse { it.toString() }); sb.append('}')
                return
            }
            val info = info(c)
            if (info.inaccessible) {
                sb.append("{\"@c\":"); str(sb, simpleName(c)); sb.append(",\"@inaccessible\":true,\"str\":")
                str(sb, runCatching { v.toString() }.getOrElse { it.toString() })
                if (v is java.security.Key) RichJson.member(sb, "b64") { val e = v.encoded; if (e == null) false else { RichJson.b64(sb, e); true } }
                sb.append('}')
                return
            }
            nested(v, depth) {
                sb.append("{\"@c\":"); str(sb, simpleName(c))
                for (f in info.fields) {
                    if (dec != null && f.name in dec.skip) continue
                    sb.append(','); str(sb, f.name); sb.append(':')
                    val mark = sb.length
                    try {
                        val x = f.get(v)
                        if (!(x is ByteArray && special(v, x))) value(x, depth + 1)
                    } catch (t: Throwable) { sb.setLength(mark); error(sb, t) }
                }
                if (dec != null) for ((k, w) in dec.members) {
                    val mark = sb.length
                    try {
                        sb.append(','); str(sb, k); sb.append(':')
                        val inner = sb.length
                        try { w(sb) } catch (t: Throwable) { sb.setLength(inner); error(sb, t) }
                    } catch (t: Throwable) { sb.setLength(mark) }
                }
                sb.append('}')
            }
        }

        /** Bytes that are not written as themselves: the login crypto handshake, and cookies unless allowed. */
        private fun special(owner: Any, b: ByteArray): Boolean {
            when (owner) {
                is ClientboundHelloPacket, is ServerboundKeyPacket -> sb.append("{\"len\":").append(b.size).append(",\"withheld\":\"crypto\"}")
                is ClientboundStoreCookiePacket, is ServerboundCookieResponsePacket -> {
                    if (cookiePayloads) return false
                    sb.append("{\"len\":").append(b.size).append(",\"sha256\":\"").append(sha256(b)).append("\"}")
                }
                else -> return false
            }
            return true
        }
    }

    /**
     * Every instance field up the class chain (Object and, for game classes, the JDK classes they
     * extend are left out), skipping codecs and the packet-type marker; cached per class and noted
     * for schema.json. A JDK class whose fields the module system will not open is "inaccessible".
     */
    private fun info(c: Class<*>): ClassInfo = classes.getOrPut(c) {
        val out = ArrayList<Field>()
        val jdk = isJdk(c)
        var inaccessible = false
        var k: Class<*>? = c
        while (k != null && k != Any::class.java && (jdk || !isJdk(k))) {
            for (f in k.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                val t = f.type.name
                if (t.contains("StreamCodec") || t.contains("serialization.Codec") || t.endsWith("PacketType")) continue
                if (!runCatching { f.trySetAccessible() }.getOrDefault(false)) { inaccessible = true; continue }
                out += f
            }
            k = k.superclass
        }
        if (!inaccessible) RecorderFiles.noteClass(c, out.map { it.name })
        ClassInfo(out, inaccessible)
    }

    private fun isJdk(c: Class<*>) = c.name.let { it.startsWith("java.") || it.startsWith("javax.") || it.startsWith("jdk.") || it.startsWith("sun.") || it.startsWith("kotlin.") }

    /** The binary name after the last '.', e.g. `ClientboundGameEventPacket$Type`. */
    fun simpleName(c: Class<*>): String = c.name.substringAfterLast('.')

    // ------------------------------------------------------------------ scalars

    /** A JSON string literal, every character kept (lone surrogates as \u escapes). */
    fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        val n = s.length
        while (i < n) {
            val ch = s[i]
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' || ch == ' ' || ch == ' ' || ch == '\u007f' -> hex(sb, ch)
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 < n && Character.isLowSurrogate(s[i + 1])) { sb.append(ch).append(s[i + 1]); i++ } else hex(sb, ch)
                }
                Character.isLowSurrogate(ch) -> hex(sb, ch)
                else -> sb.append(ch)
            }
            i++
        }
        sb.append('"')
    }

    private fun hex(sb: StringBuilder, ch: Char) {
        val h = Integer.toHexString(ch.code)
        sb.append("\\u"); repeat(4 - h.length) { sb.append('0') }; sb.append(h)
    }

    /**
     * A number, exactly: doubles and floats in their shortest round-trip form, NaN and the infinities
     * as strings ("NaN", "Infinity", "-Infinity"), longs beyond 2^53 (where JSON readers start rounding)
     * as strings.
     */
    fun num(sb: StringBuilder, n: Number) {
        when (n) {
            is Double -> num(sb, n)
            is Float -> num(sb, n)
            is Long -> num(sb, n)
            is Int, is Short, is Byte -> sb.append(n.toString())
            else -> {
                val s = n.toString()
                // BigDecimal, LazilyParsedNumber and friends: as a number if it is one, else as text.
                if (s.matches(NUMBER)) sb.append(s) else str(sb, s)
            }
        }
    }

    private val NUMBER = Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")

    fun num(sb: StringBuilder, d: Double) {
        when {
            d.isNaN() -> sb.append("\"NaN\"")
            d == Double.POSITIVE_INFINITY -> sb.append("\"Infinity\"")
            d == Double.NEGATIVE_INFINITY -> sb.append("\"-Infinity\"")
            else -> sb.append(d.toString())
        }
    }

    fun num(sb: StringBuilder, f: Float) {
        when {
            f.isNaN() -> sb.append("\"NaN\"")
            f == Float.POSITIVE_INFINITY -> sb.append("\"Infinity\"")
            f == Float.NEGATIVE_INFINITY -> sb.append("\"-Infinity\"")
            else -> sb.append(f.toString())
        }
    }

    fun num(sb: StringBuilder, l: Long) {
        if (l > MAX_SAFE || l < -MAX_SAFE) sb.append('"').append(l).append('"') else sb.append(l)
    }

    /** `{"@error":"<exception>"}`. */
    fun error(sb: StringBuilder, t: Throwable) {
        sb.append("{\"@error\":"); str(sb, t.toString()); sb.append('}')
    }

    fun sha256(b: ByteArray): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
}
