package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.pack.mobs.MobModifier
import com.engineerclient.p3sim.pack.mobs.MobTag
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The name tag a starred mob wears, written exactly as Hypixel writes it (FORMAT-mobs 3.3): a legacy `§`-coded string whose
 * plain text is what Odin, EC and Devonian match with their star regexes, e.g. ` ✯ Zombie Knight 1.3M❤`. Pure: no Minecraft.
 *
 * What a player sees is a floating line over the mob: an icon group, the gold star, the name in red (bold light purple for a
 * miniboss), and the HP in green that turns yellow at half health. A prefix word (`Healthy`, `Flaming`...) comes before the
 * name and recolours it.
 */
object MobTags {
    /**
     * The regex Odin's `Highlight` and EC's `BrWaypoints2`/`RandomStuff` use to call a name tag a starred mob. Kept here only
     * so a test can run every generated tag through it (gate G6); the pattern text is copied from those sources.
     */
    val STARRED_REGEX = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")

    /**
     * The HP text of a tag: `1.3M`, `2M`, `900k`, `862.5k`, `90,000`, `1B`. One decimal, a trailing `.0` dropped, ties to even on
     * the exact binary value (1.25M shows `1.2M`, 1.35M `1.4M`, 5.95M `6M`; FORMAT-mobs 3.3, measured on 81,316 real pairs).
     * Under 100,000 it is the whole number with thousands commas (`94,500`).
     */
    fun formatHp(hp: Double): String {
        val v = if (hp < 0.0) 0.0 else hp
        return when {
            v >= 1_000_000_000.0 -> trim(v / 1e9) + "B"
            // A value that rounds up to 1000 of its unit (999,950 -> 999.95k) is written in the next unit up.
            v >= 1_000_000.0 -> if (rounds1(v / 1e6) >= 1000.0) trim(v / 1e9) + "B" else trim(v / 1e6) + "M"
            v >= 100_000.0 -> if (rounds1(v / 1e3) >= 1000.0) trim(v / 1e6) + "M" else trim(v / 1e3) + "k"
            else -> String.format(java.util.Locale.ROOT, "%,d", Math.round(v))
        }
    }

    private fun rounds1(x: Double): Double = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toDouble()

    private fun trim(x: Double): String {
        val r = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
        return if (r.endsWith(".0")) r.dropLast(2) else r
    }

    /** A colour field from the pack (`§c`, or `c`, or empty/null for none) as the two-character code, or "" for none. */
    fun code(c: String?): String = when {
        c.isNullOrEmpty() -> ""
        c.startsWith("§") -> c
        else -> "§$c"
    }

    /**
     * The head of an UNSTARRED tag: the starred head without its star part `§6✯ §r` (FORMAT-mobs 3.3). Heads that spell the star
     * with a space before it (`§6 ✯ §r`) lose "✯ " and then the empty colour pair `§6§r`... as the rule says; a head with no star is returned as is.
     */
    fun unstarredHead(head: String): String {
        if (!head.contains("✯")) return head
        if (head.contains("§6✯ §r")) return head.replace("§6✯ §r", "")
        return head.replace("✯ ", "").replace("§6§r", "")
    }

    /** The one-letter colour of the HP number: the modifier's own, or green above half health and yellow at or below it (the plain row). */
    fun hpColor(modifier: MobModifier?, hp: Double, maxHp: Double): String {
        if (modifier == null) return if (maxHp > 0 && hp <= 0.5 * maxHp) "e" else "a"
        val hurt = modifier.hpColorHurt
        val limit = modifier.hpThreshold
        if (hurt != null && limit != null && maxHp > 0 && hp <= limit * maxHp) return hurt.removePrefix("§")
        return (modifier.hpColor ?: "a").removePrefix("§")
    }

    /**
     * The whole tag in legacy form.
     *
     * `starred`: `head + [colour [bold]] + [word ] + name + "§r §" + hpColour + hpText + "§r§c❤"`. The word is the modifier id
     * ("" or null for none); a word recolours the name to the modifier's colour. The Flaming row has no colour and drops the
     * head's trailing `§r`, so its word takes the star's gold (`headReset = false`). An archetype whose tag is `modSplit`
     * (the trap-room Deathmite) writes the word as its own segment: `head + wordColour + Word + " §r" + colour + bold + name + ...`.
     */
    fun nametagLegacy(tag: MobTag, name: String, modifier: MobModifier?, hp: Double, maxHp: Double, starred: Boolean): String {
        val word = modifier?.id.orEmpty()
        var head = if (starred) tag.head else unstarredHead(tag.head)
        val archColour = code(tag.nameColor)
        val bold = if (tag.bold) "§l" else ""
        val tail = "§r §" + hpColor(modifier, hp, maxHp) + formatHp(hp) + "§r§c❤"
        if (word.isEmpty()) return head + archColour + bold + name + tail
        if (tag.modSplit) {
            val wordColour = code(modifier?.nameColor).ifEmpty { if (word == "Flaming") "§6" else "" }
            return head + wordColour + word + " §r" + archColour + bold + name + tail
        }
        if (modifier?.headReset == false && head.endsWith("§r")) head = head.dropLast(2)
        // A word recolours the name; the pack's null (no row colour) keeps the archetype's, an empty string means no code at all.
        val colour = if (modifier?.nameColor == null) archColour else code(modifier.nameColor)
        return head + colour + bold + "$word " + name + tail
    }

    /** The plain text a client reads off a legacy string: every `§x` pair removed. */
    fun plain(legacy: String): String {
        val sb = StringBuilder(legacy.length)
        var i = 0
        while (i < legacy.length) {
            if (legacy[i] == '§' && i + 1 < legacy.length) { i += 2; continue }
            sb.append(legacy[i]); i++
        }
        return sb.toString()
    }

    /** Whether the tag is one that Odin and EC take for a starred mob (the regex of gate G6) once its colour codes are gone. */
    fun looksStarred(legacy: String): Boolean = STARRED_REGEX.matches(plain(legacy))
}
