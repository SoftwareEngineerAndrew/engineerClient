package com.devgineerclient.betterrecorder

import net.minecraft.ChatFormatting
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional

/**
 * A small JSON line builder for Better Recorder: members are appended in order, numbers are written short (positions to 1/1000 of a
 * block, angles to 1/10 of a degree), text keeps its colours as § codes. Not a general JSON library: just what the lines need.
 */
class BrJson(cap: Int = 128) {
    val sb = StringBuilder(cap)
    private var first = true

    private fun key(k: String): StringBuilder {
        if (first) first = false else sb.append(',')
        sb.append('"').append(k).append("\":")
        return sb
    }

    fun s(k: String, v: String?) = apply { key(k); str(sb, v) }
    fun i(k: String, v: Int?) = apply { key(k); sb.append(v ?: "null") }
    fun l(k: String, v: Long?) = apply { key(k); sb.append(v ?: "null") }
    fun b(k: String, v: Boolean?) = apply { key(k); sb.append(v ?: "null") }
    /** A coordinate or other fine number: 3 decimals at most. */
    fun d(k: String, v: Double?) = apply { key(k); if (v == null) sb.append("null") else num(sb, v, 1000.0) }
    /** An angle or a coarse number: 1 decimal at most. */
    fun a(k: String, v: Float?) = apply { key(k); if (v == null) sb.append("null") else num(sb, v.toDouble(), 10.0) }
    fun f(k: String, v: Float?) = apply { key(k); if (v == null) sb.append("null") else num(sb, v.toDouble(), 1000.0) }
    fun xyz(k: String, x: Double, y: Double, z: Double) = apply { key(k); sb.append('['); num(sb, x, 1000.0); sb.append(','); num(sb, y, 1000.0); sb.append(','); num(sb, z, 1000.0); sb.append(']') }
    fun bp(k: String, p: BlockPos?) = apply { key(k); if (p == null) sb.append("null") else sb.append('[').append(p.x).append(',').append(p.y).append(',').append(p.z).append(']') }
    fun ints(k: String, v: IntArray) = apply { key(k); sb.append('['); v.forEachIndexed { n, x -> if (n > 0) sb.append(','); sb.append(x) }; sb.append(']') }
    /** A member whose value is already JSON. */
    fun raw(k: String, json: String?) = apply { key(k); sb.append(json ?: "null") }
    fun txt(k: String, c: Component?) = apply { key(k); str(sb, c?.let { legacy(it) }) }

    override fun toString(): String = sb.toString()

    companion object {
        fun str(sb: StringBuilder, v: String?) {
            if (v == null) { sb.append("null"); return }
            sb.append('"')
            for (ch in v) when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' || ch == ' ' || ch == ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
            sb.append('"')
        }

        fun q(v: String?): String = StringBuilder((v?.length ?: 4) + 2).also { str(it, v) }.toString()

        /** [v] rounded to 1/[scale], written without a trailing ".0". */
        fun num(sb: StringBuilder, v: Double, scale: Double) {
            if (v.isNaN() || v.isInfinite()) { sb.append("null"); return }
            val r = Math.round(v * scale)
            val s = scale.toLong()
            if (r % s == 0L) sb.append(r / s) else sb.append(r / scale)
        }

        fun n3(v: Double): String = StringBuilder(12).also { num(it, v, 1000.0) }.toString()

        /** A chat component with its formatting as § codes (the nearest legacy colour, or §#rrggbb), as ADR writes chat. */
        fun legacy(message: Component): String {
            val sb = StringBuilder()
            var last = ""
            message.visit(FormattedText.StyledContentConsumer<Unit> { style, text ->
                if (text.isNotEmpty()) {
                    val codes = styleCodes(style)
                    if (codes != last) { if (last.isNotEmpty()) sb.append("§r"); sb.append(codes); last = codes }
                    sb.append(text)
                }
                Optional.empty()
            }, Style.EMPTY)
            return sb.toString()
        }

        private fun styleCodes(style: Style): String {
            val sb = StringBuilder()
            style.color?.let { sb.append(colorCode(it)) }
            if (style.isObfuscated) sb.append("§k")
            if (style.isBold) sb.append("§l")
            if (style.isStrikethrough) sb.append("§m")
            if (style.isUnderlined) sb.append("§n")
            if (style.isItalic) sb.append("§o")
            return sb.toString()
        }

        private fun colorCode(color: TextColor): String {
            val legacy = ChatFormatting.entries.firstOrNull { TextColor.fromLegacyFormat(it)?.value == color.value }
            return legacy?.toString() ?: ("§#" + String.format(java.util.Locale.ROOT, "%06x", color.value and 0xFFFFFF))
        }

        /** A block state as its command form without the minecraft: prefix ("stone_bricks", "lever[face=wall,...]"). */
        fun state(s: BlockState): String = BlockStateParser.serialize(s).removePrefix("minecraft:")

        /**
         * An item, short: `[sbId or item id, count, name, dye rgb or null, head texture value or null, glint]`. The Skyblock id is the
         * custom data's "id"; the name keeps its colours. An empty stack is null.
         */
        fun item(s: ItemStack): String {
            if (s.isEmpty) return "null"
            val sb = StringBuilder(96).append('[')
            val sbId = s.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.takeIf { it.isNotEmpty() }
            str(sb, sbId ?: BuiltInRegistries.ITEM.getKey(s.item).path)
            sb.append(',').append(s.count).append(',')
            str(sb, runCatching { legacy(s.hoverName) }.getOrNull())
            sb.append(',')
            val dye = s.get(DataComponents.DYED_COLOR)
            if (dye == null) sb.append("null") else str(sb, String.format("%06x", dye.rgb() and 0xFFFFFF))
            sb.append(',')
            val tex = s.get(DataComponents.PROFILE)?.partialProfile()?.properties()?.get("textures")?.firstOrNull()?.value()
            str(sb, tex)
            sb.append(',').append(s.hasFoil())
            if (sbId != null) { sb.append(','); str(sb, BuiltInRegistries.ITEM.getKey(s.item).path) }
            return sb.append(']').toString()
        }
    }
}
