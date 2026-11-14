package com.engineerclient.render

import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence

object StreamerText {
    private val SERVER_ID = Regex("""(\d{2}/\d{2}/\d{2}) ([Mm]\d{1,4}[A-Z]{0,4})""")
    private const val IP = "www.hypixel.net"

    private fun realName(): String? = net.minecraft.client.Minecraft.getInstance().player?.gameProfile?.name

    private fun replacements(): List<Pair<String, String>>? {
        val out = ArrayList<Pair<String, String>>(2)
        if (ScoreboardNametags.namesOn()) {
            val real = realName()
            val fake = ScoreboardNametags.fakeName
            if (!real.isNullOrEmpty() && fake != real && !fake.contains(real)) out += real to fake
        }
        if (ScoreboardNametags.serverOn() && ScoreboardNametags.hideIp) out += IP to ScoreboardNametags.ipReplacement
        return out.takeIf { it.isNotEmpty() || ScoreboardNametags.serverOn() }
    }

    private fun apply(text: String, reps: List<Pair<String, String>>): String {
        var s = text
        for ((from, to) in reps) if (s.contains(from)) s = s.replace(from, to)
        if (ScoreboardNametags.serverOn()) s = SERVER_ID.replace(s) { "${it.groupValues[1]} ${ScoreboardNametags.serverReplacement}" }
        return s
    }

    @JvmStatic
    fun rewrite(text: String): String {
        val reps = replacements() ?: return text
        return apply(text, reps)
    }

    @JvmStatic
    fun rewrite(seq: FormattedCharSequence): FormattedCharSequence {
        val reps = replacements() ?: return seq
        val styles = ArrayList<Style>()
        val plain = StringBuilder()
        seq.accept { _, style, cp ->
            val chars = Character.toChars(cp)
            for (c in chars) { plain.append(c); styles += style }
            true
        }
        val original = plain.toString()
        if (reps.none { original.contains(it.first) } && !(ScoreboardNametags.serverOn() && SERVER_ID.containsMatchIn(original))) return seq
        val out = StringBuilder()
        val outStyles = ArrayList<Style>()
        var i = 0
        val matches = ArrayList<Triple<Int, Int, String>>()
        for ((from, to) in reps) {
            var at = original.indexOf(from)
            while (at >= 0) { matches += Triple(at, at + from.length, to); at = original.indexOf(from, at + from.length) }
        }
        if (ScoreboardNametags.serverOn()) SERVER_ID.findAll(original).forEach { m ->
            val g = m.groups[2]!!
            matches += Triple(g.range.first, g.range.last + 1, ScoreboardNametags.serverReplacement)
        }
        matches.sortBy { it.first }
        for ((start, end, to) in matches) {
            if (start < i) continue
            while (i < start) { out.append(original[i]); outStyles += styles[i]; i++ }
            for (c in to) { out.append(c); outStyles += styles[start] }
            i = end
        }
        while (i < original.length) { out.append(original[i]); outStyles += styles[i]; i++ }
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
