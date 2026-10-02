package com.engineerclient.recorder

import com.google.gson.JsonPrimitive
import com.odtheking.odin.utils.itemId
import io.netty.buffer.ByteBuf
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.core.Vec3i
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.BitSet
import java.util.Optional
import java.util.OptionalInt
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Any object (a packet, mostly) as JSON, read by reflection: every instance field by its real name
 * (26.1 is unobfuscated, so names are Mojang's), with the game's own types written the way a person
 * reads them - text as text, items as their id, name and lore, block states as `minecraft:stone[...]`,
 * positions as arrays, registry entries by name. Bulk data (byte buffers, chunk payloads) is written
 * as its size. Depth, list length and string length are capped so one odd packet can't blow up a log.
 */
object PacketJson {

    private const val MAX_DEPTH = 6
    private const val MAX_ITEMS = 128
    private const val MAX_STRING = 4000

    private val fields = ConcurrentHashMap<Class<*>, List<Field>>()

    fun write(v: Any?): String = StringBuilder().also { write(it, v, 0) }.toString()

    fun write(sb: StringBuilder, v: Any?, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(v)
            is Float -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Double -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Number -> sb.append(v)
            is Char -> str(sb, v.toString())
            is Enum<*> -> str(sb, v.name)
            is Component -> str(sb, v.string)
            is ItemStack -> item(sb, v)
            is BlockState -> str(sb, BlockStateParser.serialize(v))
            is BlockPos -> sb.append('[').append(v.x).append(',').append(v.y).append(',').append(v.z).append(']')
            is SectionPos -> sb.append('[').append(v.x()).append(',').append(v.y()).append(',').append(v.z()).append(']')
            is Vec3i -> sb.append('[').append(v.x).append(',').append(v.y).append(',').append(v.z).append(']')
            is Vec3 -> sb.append('[').append(num(v.x)).append(',').append(num(v.y)).append(',').append(num(v.z)).append(']')
            is Vec2 -> sb.append('[').append(num(v.x.toDouble())).append(',').append(num(v.y.toDouble())).append(']')
            is ChunkPos -> sb.append('[').append(v.x).append(',').append(v.z).append(']')
            is UUID, is Identifier -> str(sb, v.toString())
            is ResourceKey<*> -> str(sb, v.identifier().toString())
            is Holder<*> -> if (v.unwrapKey().isPresent) str(sb, v.unwrapKey().get().identifier().toString()) else write(sb, v.value(), depth + 1)
            is SynchedEntityData.DataValue<*> -> { sb.append('[').append(v.id()).append(','); write(sb, v.value(), depth + 1); sb.append(']') }
            is Optional<*> -> write(sb, v.orElse(null), depth)
            is OptionalInt -> if (v.isPresent) sb.append(v.asInt) else sb.append("null")
            is ByteBuf -> sb.append("{\"bytes\":").append(runCatching { v.readableBytes() }.getOrDefault(-1)).append('}')
            is ByteArray -> sb.append("{\"bytes\":").append(v.size).append('}')
            is BitSet -> sb.append("{\"bits\":").append(v.cardinality()).append('}')
            is IntArray -> list(sb, v.asList(), depth)
            is LongArray -> list(sb, v.asList(), depth)
            is ShortArray -> list(sb, v.asList(), depth)
            is FloatArray -> list(sb, v.asList(), depth)
            is DoubleArray -> list(sb, v.asList(), depth)
            is BooleanArray -> list(sb, v.asList(), depth)
            is Array<*> -> list(sb, v.asList(), depth)
            is Map<*, *> -> map(sb, v, depth)
            is Iterable<*> -> list(sb, v, depth)
            is Class<*> -> str(sb, v.simpleName)
            else -> obj(sb, v, depth)
        }
    }

    /** A packet's type: its protocol id, e.g. `minecraft:move_entity_pos`. */
    fun type(p: net.minecraft.network.protocol.Packet<*>): String =
        runCatching { p.type().id().toString() }.getOrElse { p.javaClass.simpleName }

    private fun obj(sb: StringBuilder, v: Any, depth: Int) {
        val c = v.javaClass
        val name = c.name
        if (depth >= MAX_DEPTH || name.startsWith("java.") || name.startsWith("kotlin.") || name.startsWith("com.mojang.serialization")) {
            str(sb, v.toString()); return
        }
        sb.append('{')
        var first = true
        for (f in fieldsOf(c)) {
            val value = runCatching { f.get(v) }.getOrElse { continue }
            if (!first) sb.append(',')
            first = false
            str(sb, f.name); sb.append(':')
            runCatching { write(sb, value, depth + 1) }.onFailure { sb.append("null") }
        }
        sb.append('}')
    }

    /** Every instance field up the class chain, skipping codecs and the like; cached per class. */
    private fun fieldsOf(c: Class<*>): List<Field> = fields.getOrPut(c) {
        val out = ArrayList<Field>()
        var k: Class<*>? = c
        while (k != null && k != Any::class.java && !k.name.startsWith("java.")) {
            for (f in k.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                val t = f.type.name
                if (t.contains("StreamCodec") || t.contains("serialization.Codec") || t.endsWith("PacketType")) continue
                if (runCatching { f.isAccessible = true }.isFailure) continue
                out += f
            }
            k = k.superclass
        }
        out
    }

    private fun list(sb: StringBuilder, items: Iterable<*>, depth: Int) {
        sb.append('[')
        var i = 0
        for (x in items) {
            if (i == MAX_ITEMS) { sb.append(",\"...\""); break }
            if (i > 0) sb.append(',')
            write(sb, x, depth + 1)
            i++
        }
        sb.append(']')
    }

    private fun map(sb: StringBuilder, m: Map<*, *>, depth: Int) {
        sb.append('{')
        var i = 0
        for ((k, x) in m) {
            if (i == MAX_ITEMS) break
            if (i > 0) sb.append(',')
            str(sb, when (k) { is Holder<*> -> k.unwrapKey().map { it.identifier().toString() }.orElse(k.toString()); else -> k.toString() })
            sb.append(':')
            write(sb, x, depth + 1)
            i++
        }
        sb.append('}')
    }

    /** An item: its vanilla id, count, name, Skyblock id and lore. */
    fun item(sb: StringBuilder, s: ItemStack) {
        if (s.isEmpty) { sb.append("null"); return }
        sb.append("{\"id\":"); str(sb, BuiltInRegistries.ITEM.getKey(s.item).toString())
        if (s.count != 1) sb.append(",\"count\":").append(s.count)
        sb.append(",\"name\":"); str(sb, s.hoverName.string)
        runCatching { s.itemId }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { sb.append(",\"sb\":"); str(sb, it) }
        s.get(DataComponents.LORE)?.lines()?.takeIf { it.isNotEmpty() }?.let { lore ->
            sb.append(",\"lore\":["); lore.take(30).forEachIndexed { i, l -> if (i > 0) sb.append(','); str(sb, l.string) }; sb.append(']')
        }
        sb.append('}')
    }

    fun str(sb: StringBuilder, s: String) {
        sb.append(JsonPrimitive(if (s.length > MAX_STRING) s.take(MAX_STRING) + "..." else s).toString())
    }

    private fun num(d: Double) = if (d.isFinite()) String.format(java.util.Locale.ROOT, "%.4f", d).trimEnd('0').trimEnd('.') else "null"
}
