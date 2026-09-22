package com.engineerclient.chat

import com.google.gson.GsonBuilder
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The chat-hider rule set, plain Kotlin so it loads and tests headlessly.
 *
 * Shape of `chat/hidden.json` (written by tools/chat-hider/export.py from the Dungeon Chat
 * Hider page): `custom` rules first — a hand-written regex with `show` or `block`, matched
 * anywhere in the line, first match wins — then `block`, the templates the page marked
 * Block, each an anchored regex over the whole message. Colour codes are stripped before
 * matching; a multi-line message is matched as one string with `\n` in it.
 */
class ChatRules(val custom: List<Custom>, val block: List<Block>) {

    data class Custom(val regex: String = "", val action: String = "block")
    data class Block(val regex: String = "", val template: String = "", val family: String = "")

    private class Compiled(val pattern: Pattern, val block: Boolean, val label: String, val anchored: Boolean)

    private val compiled: List<Compiled>
    val invalid: List<String>

    init {
        val ok = ArrayList<Compiled>()
        val bad = ArrayList<String>()
        for (c in custom) {
            try { ok += Compiled(Pattern.compile(c.regex), c.action == "block", "custom ${c.regex}", anchored = false) }
            catch (e: PatternSyntaxException) { bad += c.regex }
        }
        for (b in block) {
            try { ok += Compiled(Pattern.compile(b.regex), true, b.template, anchored = true) }
            catch (e: PatternSyntaxException) { bad += b.regex }
        }
        compiled = ok
        invalid = bad
    }

    val size: Int get() = compiled.size

    /** The label of the rule that hides [text], or null when nothing does. [text] has no § codes. */
    fun hides(text: String): String? {
        for (r in compiled) {
            val m = r.pattern.matcher(text)
            val hit = if (r.anchored) m.matches() else m.find()
            if (hit) return if (r.block) r.label else null
        }
        return null
    }

    companion object {
        private val gson = GsonBuilder().create()
        private val formatting = Regex("§.")

        /**
         * What rules match against: colour codes removed and the whole message trimmed. Hypixel
         * centres many lines with leading spaces, and the rules were built from trimmed log lines.
         */
        fun strip(text: String): String = formatting.replace(text, "").trim()

        fun parse(json: String): ChatRules {
            val file = gson.fromJson(json, RulesFile::class.java) ?: RulesFile()
            return ChatRules(file.custom, file.block)
        }

        /** The rules shipped in the jar. */
        fun bundled(): ChatRules {
            val text = ChatRules::class.java.getResourceAsStream("/chat/hidden.json")?.bufferedReader()?.use { it.readText() }
                ?: return ChatRules(emptyList(), emptyList())
            return parse(text)
        }
    }

    private class RulesFile(
        val version: Int = 0,
        val custom: List<Custom> = emptyList(),
        val block: List<Block> = emptyList(),
    )
}
