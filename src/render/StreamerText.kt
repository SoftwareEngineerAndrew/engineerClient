package com.engineerclient.render

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.FormattedCharSink
import java.util.Optional

object StreamerText {
    private val SERVER_ID = Regex("""(\d{2}/\d{2}/\d{2}) ([Mm]\d{1,4}[A-Z]{0,4})""")
    private const val IP = "www.hypixel.net"
    private const val CACHE_LIMIT = 512

    @JvmField @Volatile var active = false

    private class Rules(val nameFrom: String?, val nameTo: String?, val ipTo: String?, val serverTo: String?) {
        val froms: Array<String>
        val tos: Array<String>

        init {
            val f = ArrayList<String>(2)
            val t = ArrayList<String>(2)
            if (nameFrom != null) { f += nameFrom; t += nameTo!! }
            if (ipTo != null) { f += IP; t += ipTo }
            froms = f.toTypedArray()
            tos = t.toTypedArray()
        }

        fun same(from: String?, to: String?, ip: String?, sid: String?) = from == nameFrom && to == nameTo && ip == ipTo && sid == serverTo
    }

    @Volatile private var rules = Rules(null, null, null, null)

    private var renderThread: Thread? = null
    private val strings = HashMap<String, String>()
    private val seqs = HashMap<String, SeqEntry>()
    private val shared = Scratch()

    private class SeqEntry(val styles: Array<Style?>, val out: FormattedCharSequence?)

    private class Scratch : FormattedCharSink, FormattedText.ContentConsumer<Unit> {
        val sb = StringBuilder()
        var styles = arrayOfNulls<Style>(64)
        var busy = false

        fun reset() { sb.setLength(0) }

        override fun accept(pos: Int, style: Style, cp: Int): Boolean {
            val at = sb.length
            sb.appendCodePoint(cp)
            val end = sb.length
            if (end > styles.size) styles = styles.copyOf(maxOf(end, styles.size * 2))
            for (k in at until end) styles[k] = style
            return true
        }

        override fun accept(text: String): Optional<Unit> {
            sb.append(text)
            return Optional.empty()
        }
    }

    init {
        refresh()
        ClientTickEvents.START_CLIENT_TICK.register { refresh() }
    }

    private fun realName(): String? = net.minecraft.client.Minecraft.getInstance().player?.gameProfile?.name

    private fun refresh() {
        renderThread = Thread.currentThread()
        var from: String? = null
        var to: String? = null
        if (ScoreboardNametags.namesOn()) {
            val real = realName()
            val fake = ScoreboardNametags.fakeName
            if (!real.isNullOrEmpty() && fake != real && !fake.contains(real)) { from = real; to = fake }
        }
        val server = ScoreboardNametags.serverOn()
        val ip = if (server && ScoreboardNametags.hideIp) ScoreboardNametags.ipReplacement else null
        val sid = if (server) ScoreboardNametags.serverReplacement else null
        if (rules.same(from, to, ip, sid)) return
        val r = Rules(from, to, ip, sid)
        rules = r
        strings.clear()
        seqs.clear()
        active = r.froms.isNotEmpty() || sid != null
    }

    private fun candidate(s: String, r: Rules): Boolean {
        for (from in r.froms) if (s.contains(from)) return true
        return r.serverTo != null && s.indexOf('/') >= 0
    }

    private fun candidate(sb: StringBuilder, r: Rules): Boolean {
        for (from in r.froms) if (sb.indexOf(from) >= 0) return true
        return r.serverTo != null && sb.indexOf("/") >= 0
    }

    private fun apply(text: String, r: Rules): String {
        var s = text
        val f = r.froms
        val t = r.tos
        for (k in f.indices) if (s.contains(f[k])) s = s.replace(f[k], t[k])
        val sid = r.serverTo
        if (sid != null && s.indexOf('/') >= 0) s = SERVER_ID.replace(s) { "${it.groupValues[1]} $sid" }
        return s
    }

    private fun onRenderThread() = Thread.currentThread() === renderThread

    @JvmStatic
    fun rewrite(text: String): String {
        if (!active) return text
        val r = rules
        if (!candidate(text, r)) return text
        if (!onRenderThread()) return apply(text, r)
        strings[text]?.let { return it }
        val out = apply(text, r)
        if (strings.size >= CACHE_LIMIT) strings.clear()
        strings[text] = out
        return out
    }

    /** Whether [text] reads differently once rewritten, without building its string when it can't. */
    @JvmStatic
    fun changes(text: FormattedText): Boolean {
        if (!active) return false
        val sc = scratch()
        try {
            sc.reset()
            text.visit(sc)
            if (!candidate(sc.sb, rules)) return false
            val s = sc.sb.toString()
            return rewrite(s) != s
        } finally {
            sc.busy = false
        }
    }

    private fun scratch(): Scratch {
        if (onRenderThread() && !shared.busy) return shared.also { it.busy = true }
        return Scratch().also { it.busy = true }
    }

    @JvmStatic
    fun rewrite(seq: FormattedCharSequence): FormattedCharSequence {
        if (!active) return seq
        val r = rules
        val sc = scratch()
        try {
            sc.reset()
            seq.accept(sc)
            if (!candidate(sc.sb, r)) return seq
            val original = sc.sb.toString()
            val styles = sc.styles
            val cache = sc === shared
            if (cache) {
                val hit = seqs[original]
                if (hit != null && sameStyles(hit.styles, styles, original.length)) return hit.out ?: seq
            }
            val out = build(original, styles, r)
            if (cache) {
                if (seqs.size >= CACHE_LIMIT) seqs.clear()
                seqs[original] = SeqEntry(styles.copyOf(original.length), out)
            }
            return out ?: seq
        } finally {
            sc.busy = false
        }
    }

    private fun sameStyles(a: Array<Style?>, b: Array<Style?>, n: Int): Boolean {
        for (k in 0 until n) if (a[k] !== b[k] && a[k] != b[k]) return false
        return true
    }

    private fun build(original: String, styles: Array<Style?>, r: Rules): FormattedCharSequence? {
        val f = r.froms
        val t = r.tos
        val sid = r.serverTo
        if (f.none { original.contains(it) } && !(sid != null && SERVER_ID.containsMatchIn(original))) return null
        val out = StringBuilder()
        val outStyles = ArrayList<Style>()
        var i = 0
        val matches = ArrayList<Triple<Int, Int, String>>()
        for (k in f.indices) {
            val from = f[k]
            var at = original.indexOf(from)
            while (at >= 0) { matches += Triple(at, at + from.length, t[k]); at = original.indexOf(from, at + from.length) }
        }
        if (sid != null) SERVER_ID.findAll(original).forEach { m ->
            val g = m.groups[2]!!
            matches += Triple(g.range.first, g.range.last + 1, sid)
        }
        matches.sortBy { it.first }
        for ((start, end, to) in matches) {
            if (start < i) continue
            while (i < start) { out.append(original[i]); outStyles += styles[i]!!; i++ }
            for (c in to) { out.append(c); outStyles += styles[start]!! }
            i = end
        }
        while (i < original.length) { out.append(original[i]); outStyles += styles[i]!!; i++ }
        val text = out.toString()
        return FormattedCharSequence { sink ->
            var k = 0
            while (k < text.length) {
                val cp = text.codePointAt(k)
                if (!sink.accept(k, outStyles[k], cp)) return@FormattedCharSequence false
                k += Character.charCount(cp)
            }
            true
        }
    }
}
