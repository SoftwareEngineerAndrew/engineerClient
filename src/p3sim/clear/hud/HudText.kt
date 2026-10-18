package com.engineerclient.p3sim.clear.hud

/*
 * Pure text helpers for the client channels: number and time formats, the private-use glyphs of Hypixel's action bar, colour-code
 * handling, and the way Hypixel cuts a sidebar line into a team's prefix and suffix. No Minecraft types, so all of it is unit-tested.
 */

/** The private-use glyphs Hypixel's resource pack draws in the action bar and sidebar (Odin's regexes read these exact code points). */
object HudGlyphs {
    const val HEALTH = ''
    const val DEFENSE = ''
    const val MANA = ''
    const val OVERFLOW_MANA = ''
    const val VITALITY = ''
    /** The location pin in front of the floor line of the sidebar. */
    const val LOCATION = ''
    const val HEART = '❤'
    const val SKULL = '☠'
    const val DEAD_EMBLEM = '☠'
    const val BLOCK = '■'
    const val CROSS = '✗'
    const val TICK = '✓'
}

object HudFormat {
    const val SECTION = '§'

    /** `13879` -> `13,879`. */
    fun commas(n: Long): String {
        val s = Math.abs(n).toString()
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return (if (n < 0) "-" else "") + sb
    }

    fun commas(n: Int): String = commas(n.toLong())

    /** `1,234` style for a double rounded to a whole number. */
    fun commas(d: Double): String = commas(Math.round(d))

    /** Team damage and healing as Hypixel's tab abbreviates them: `3k`, `526M`, `1B`. Below 1,000 the plain number. */
    fun abbreviate(n: Long): String = when {
        n < 1_000L -> n.toString()
        n < 1_000_000L -> "${n / 1_000L}k"
        n < 1_000_000_000L -> "${n / 1_000_000L}M"
        else -> "${n / 1_000_000_000L}B"
    }

    /**
     * Elapsed time as the sidebar and tab print it: `13s` under a minute (two digits: `00s`), `04m 10s` from a minute, `1h 04m 10s` from
     * an hour. Odin reads it with `((\d+h ?)?(\d+m ?)?\d+s)`. The hour form is not measured (no recorded clear lasts an hour).
     */
    fun elapsed(ticks: Int): String {
        val total = Math.max(0, ticks) / 20
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        return when {
            h > 0 -> "${h}h ${two(m)}m ${two(s)}s"
            m > 0 -> "${two(m)}m ${two(s)}s"
            else -> "${two(s)}s"
        }
    }

    /** `m:ss` of the countdown (`0:04`), whole seconds rounded UP as the lobby counter does (0:01 until the last tick). */
    fun countdown(ticksLeft: Int): String {
        val s = (Math.max(0, ticksLeft) + 19) / 20
        return "${s / 60}:${two(s % 60)}"
    }

    fun two(n: Int): String = if (n < 10) "0$n" else n.toString()

    /** Roman numerals for a class level (1..99). */
    fun roman(n: Int): String {
        var left = n.coerceIn(1, 3999)
        val sb = StringBuilder()
        val table = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        for ((v, r) in table) while (left >= v) { sb.append(r); left -= v }
        return sb.toString()
    }

    /** Removes every `§x` pair, as Odin's `noControlCodes` does. */
    fun strip(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == SECTION && i + 1 < text.length) { i += 2; continue }
            sb.append(text[i]); i++
        }
        return sb.toString()
    }

    /** The colour and style codes in force at the end of [text] (`§a`, `§c§l`...), as Minecraft's `ChatFormatting.getLastColors` gives them. */
    fun lastColors(text: String): String {
        var result = ""
        var i = text.length - 2
        while (i >= 0) {
            if (text[i] == SECTION) {
                val c = text[i + 1].lowercaseChar()
                if (c in "0123456789abcdef") return "$SECTION$c$result"
                if (c == 'r') return result
                if (c in "klmno") result = "$SECTION$c$result"
                i -= 2
            } else i--
        }
        return result
    }

    /**
     * Hypixel's sidebar limit: a line is one team, shown as prefix + suffix, 16 characters each (the 1.8 limit its scoreboard still
     * obeys). The text is cut at 16 (never between a `§` and its code); the suffix starts with the colour in force at the cut, which is
     * the stray doubled colour code seen in recordings (`Time Elapsed: §a§a13s`). That carried colour is counted against the suffix's 16:
     * when the rest of a long line only fits without it, it is left out (the tail then shows in the default colour, but the text, which
     * is what the parsers read, stays whole). A suffix is cut to 16 as well, never between a `§` and its code, so a line longer than
     * 32 characters loses its end. Parsers read prefix + suffix with codes stripped, which equals the original text for any line of up to 32 characters.
     */
    fun splitLine(text: String, limit: Int = 16): Pair<String, String> {
        if (text.length <= limit) return text to ""
        var cut = limit
        if (text[cut - 1] == SECTION) cut--
        val prefix = text.substring(0, cut)
        val rest = text.substring(cut)
        val carry = lastColors(prefix)
        var suffix = if (carry.length + rest.length <= limit) carry + rest else rest
        if (suffix.length > limit) {
            suffix = suffix.substring(0, limit)
            if (suffix.last() == SECTION) suffix = suffix.dropLast(1)
        }
        return prefix to suffix
    }

    /** The Hypixel class name for a stored class string (any case, `Berserker` accepted), or null when it is not one. */
    fun className(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        "archer" -> "Archer"
        "berserk", "berserker" -> "Berserk"
        "healer" -> "Healer"
        "mage" -> "Mage"
        "tank" -> "Tank"
        else -> null
    }

    /** The letter the sidebar shows in front of a member: `[H] [M] [B] [T] [A]`. */
    fun classLetter(raw: String?): Char = when (className(raw)) {
        "Archer" -> 'A'
        "Berserk" -> 'B'
        "Healer" -> 'H'
        "Mage" -> 'M'
        "Tank" -> 'T'
        else -> 'M'
    }

    /** Cuts a name to the sidebar's width (recordings show names of 12 characters or fewer). */
    fun sidebarName(name: String, max: Int = HudConstants.SIDEBAR_NAME_MAX): String = if (name.length <= max) name else name.substring(0, max)
}

/**
 * Cosmetic and unmeasured values of the channels, named in one place. Anything a player might want to change is also a setting of
 * the HUD module; the pure code takes these as parameters where it matters.
 */
object HudConstants {
    /** The colour of a member's name on the sidebar (the recorded lines show the rank colour, `§b` for a plain rank). UNMEASURED per rank. */
    const val NAME_COLOUR = "§b"

    /** Discoveries per secret found: recorded ratios run 1.00 to 1.17 (non-secret finds count). UNMEASURED beyond that range. */
    const val DISCOVERY_RATIO = 1.1

    /** Sidebar name width: measured from one four-line sample (a 14 character name shows 12). UNMEASURED beyond that. */
    const val SIDEBAR_NAME_MAX = 12

    /** Maximum vitality of the action bar when the stat preset has none (recorded maximums run 115 to 127). */
    const val DEFAULT_VITALITY = 127

    /** The SkyBlock level printed in front of a name in the tab list. Cosmetic: Odin only reads the number. */
    const val DEFAULT_SB_LEVEL = 250

    /** The class level in the tab (`Berserk L`). The sim's own bridge uses 50 for everyone. */
    const val DEFAULT_CLASS_LEVEL = 50

    /** Sidebar title line. */
    const val TITLE = "§e§lSKYBLOCK"

    /** The first and the last sidebar footer line as Hypixel prints them. */
    const val FOOTER = "§ewww.hypixel.net"

    /** SkyBlock calendar text of the sidebar. Cosmetic: nothing parses it. */
    const val SEASON = " Early Autumn 19th"

    /** Run-start SkyBlock clock, in minutes after midnight (6:10pm). */
    const val CLOCK_START_MINUTES = 18 * 60 + 10

    /** Server ticks per 10 SkyBlock minutes on the sidebar clock (recordings: 160 or close to it in nearly every step, never 166). */
    const val CLOCK_TICKS_PER_STEP = 160

    /** `Cleared` colour bands (the run-state pack's `hud.clearedColour` overrides): 0-39 red, 40-74 gold, 75-100 green. */
    const val CLEARED_GOLD_FROM = 40
    const val CLEARED_GREEN_FROM = 75

    /** Teammate health colour bands as a percent of max health. UNMEASURED: every recorded sample shows green. */
    const val HP_GREEN_FROM = 50.0
    const val HP_YELLOW_FROM = 25.0

    /** The fake server id printed next to the date in the pre-start sidebar. Cosmetic. */
    const val LOBBY_SERVER = "m100AV"

    /** Ticks between tab list refreshes (the run-state pack's `counters.tabRefreshTicks` overrides). */
    const val TAB_REFRESH_TICKS = 20

    /** Ticks between map decoration packets, and between checks for a changed room picture. */
    const val MAP_TICKS = 5

    /** Ticks between action bar packets. */
    const val ACTION_BAR_TICKS = 10

    /** Map id of the dungeon map: Odin and Devonian accept ids with `(id and 1000) == 0`. */
    const val MAP_ID = 1024
}
