package com.engineerclient.recorder

/**
 * A small JSON members builder for the Odin state lines: `"a":1,"b":"x"` without the braces, so the
 * result drops straight into [Rec.emit]. Strings and numbers go through [PacketJson.str] and
 * [PacketJson.num], so every character and every bit of a double is kept the same way the packet
 * lines keep them.
 *
 * Each [safe] member that throws (Odin's lists are mutated on the network thread, a getter can hit
 * a half-built object) is rewound and written as `{"@error":...}` instead, so one bad field never
 * costs the rest of the line.
 */
internal class OdinJs(cap: Int = 256) {
    val sb = StringBuilder(cap)
    private var first = true

    private fun key(name: String): OdinJs {
        if (!first) sb.append(',')
        first = false
        PacketJson.str(sb, name)
        sb.append(':')
        return this
    }

    fun s(name: String, v: String?): OdinJs { key(name); str(sb, v); return this }
    fun n(name: String, v: Number?): OdinJs { key(name); num(sb, v); return this }
    fun b(name: String, v: Boolean?): OdinJs { key(name); sb.append(v?.toString() ?: "null"); return this }
    fun raw(name: String, json: String?): OdinJs { key(name); sb.append(json ?: "null"); return this }

    /** A nested object. */
    fun obj(name: String, fill: OdinJs.() -> Unit): OdinJs {
        key(name)
        sb.append('{')
        first = true
        fill()
        sb.append('}')
        first = false
        return this
    }

    /** A member written straight into [sb] by [write]; if it throws, `{"@error":...}` takes its place. */
    fun safe(name: String, write: (StringBuilder) -> Unit): OdinJs {
        key(name)
        val mark = sb.length
        try { write(sb) } catch (t: Throwable) { sb.setLength(mark); PacketJson.error(sb, t) }
        return this
    }

    /** An array of [items], each written by [each] (which must write exactly one JSON value). */
    fun <T> arr(name: String, items: Iterable<T>?, each: (StringBuilder, T) -> Unit): OdinJs =
        safe(name) { out -> if (items == null) out.append("null") else array(out, items, each) }

    override fun toString(): String = sb.toString()

    companion object {
        fun str(sb: StringBuilder, v: String?) { if (v == null) sb.append("null") else PacketJson.str(sb, v) }
        fun num(sb: StringBuilder, v: Number?) { if (v == null) sb.append("null") else PacketJson.num(sb, v) }

        fun <T> array(sb: StringBuilder, items: Iterable<T>, each: (StringBuilder, T) -> Unit) {
            sb.append('[')
            var f = true
            for (x in items) { if (!f) sb.append(','); f = false; each(sb, x) }
            sb.append(']')
        }

        /** `[a,b,...]` of numbers. */
        fun nums(sb: StringBuilder, vararg v: Number?) {
            sb.append('[')
            v.forEachIndexed { i, x -> if (i > 0) sb.append(','); num(sb, x) }
            sb.append(']')
        }

        fun strings(sb: StringBuilder, items: Iterable<String?>) = array(sb, items) { o, s -> str(o, s) }
    }
}
