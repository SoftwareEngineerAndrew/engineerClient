package com.engineerclient.p3sim.clear.score

import com.engineerclient.p3sim.run.api.EndScreen
import kotlin.math.ceil

/**
 * What the end screen shows about one player and their class: their experience, damage, healing, kills, own secrets and deaths.
 * Experience, damage and healing are an UNMEASURED model (the sim has no experience system and no damage tally yet), see
 * [ScoreModule]; the rest is counted.
 */
data class EndScreenPlayer(
    /** `Mage`, `Healer`, ... as the class is printed. */
    val playerClass: String = "Mage",
    val cataXp: Double = 0.0,
    val bits: Int = 0,
    val damage: Long = 0,
    val healing: Long = 0,
    /** Enemies this player killed. */
    val killed: Int = 0,
    /** Secrets this player found themselves (the team count is in [EndScreen]). */
    val secretsFound: Int = 0,
)

/**
 * The chat lines of the end-of-run summary, as Hypixel prints them (client-state 3.6 E): a rule, the floor name, the Team Score line
 * with its letter, the "Defeated" line with the run time, the "EXTRA STATS" header Odin looks for and the experience lines, then
 * a second screen with the stats block. Every body line is centred by glyph width, as Hypixel does. Lines are plain `§` chat text. Pure.
 */
object EndScreenText {
    /** Sixty-four box-drawing characters, bold green, as the rule lines are. */
    val RULE: String = "§a§l" + "▬".repeat(64)

    /** Hypixel's centring: a `§f`, then spaces to put the text's middle at 160 px (4 px a space, bold glyphs 1 px wider). */
    fun centre(text: String): String {
        var w = 0; var bold = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '§' && i + 1 < text.length) { val f = text[i + 1]; if (f == 'l') bold = true else if (f in "0123456789abcdefr") bold = false; i += 2; continue }
            w += when (c) {
                '!', ',', '.', ':', ';', '\'', '|' -> 2
                'i' -> 2; 'l', '`' -> 3; ' ', 'I', '[', ']', '"' -> 4
                't', 'f', 'k', '(', ')', '<', '>', '*', '{', '}', '☠' -> 5
                '@' -> 7
                else -> 6
            } + if (bold) 1 else 0
            i++
        }
        return "§f" + " ".repeat(ceil((160 - w / 2.0) / 4).toInt().coerceAtLeast(0)) + text
    }

    /** `04m 10s` (minutes and seconds with two digits, as the clock prints them), or `10s`. */
    fun time(ticks: Int): String {
        val s = (ticks / 20).coerceAtLeast(0)
        return if (s < 60) "%02ds".format(s) else "%02dm %02ds".format(s / 60, s % 60)
    }

    /**
     * The colour of the grade letter: S+ bold aqua, S aqua, D red are measured; A green, B yellow and C gold are the
     * next grades' samples as seen (UNMEASURED beyond one or two screens each).
     */
    fun letterColour(letter: String) = when (letter) { "S+" -> "§b§l"; "S" -> "§b"; "A" -> "§a"; "B" -> "§e"; "C" -> "§6"; else -> "§c" }

    private fun comma(v: Long) = "%,d".format(java.util.Locale.US, v)

    /** `49,476` or `37,613.3`: commas, one decimal, no trailing `.0`. */
    fun xp(v: Double) = "%,.1f".format(java.util.Locale.US, v).removeSuffix(".0")

    /** The five dungeon classes, in the order the screen lists the others. */
    val CLASSES = listOf("Healer", "Berserk", "Archer", "Tank", "Mage")

    /**
     * The lines for [e]. [bosses] are the names after "Defeated" (F7: `Maxor, Storm, Goldor, and Necron`); [floorName] the title
     * (`The Catacombs - Floor VII`). [me] is the viewer's own numbers; when it is null the personal lines (experience, damage,
     * healing) are left out and [killed] is the enemies killed line.
     */
    fun lines(e: EndScreen, bosses: String = "Maxor, Storm, Goldor, and Necron", killed: Int = 0, floorName: String = "The Catacombs - Floor VII", me: EndScreenPlayer? = null): List<String> {
        val dungeon = floorName.substringBefore(" - ")
        val floor = if (" - " in floorName) floorName.substringAfter(" - ") else ""
        fun title(suffix: String) = "§r§c$dungeon §r§8- §r§e$floor$suffix"
        val scoreLine = "Team Score: §r§a${e.score} §r§f(§r${letterColour(e.letter)}${e.letter}§r§f)"
        val defeated = "§r§c☠ §r§eDefeated §r§c$bosses §r§ein §r§a${time(e.ticks)}"
        val out = ArrayList<String>()
        out += RULE
        out += centre(title("")); out += ""; out += centre(scoreLine); out += centre(defeated)
        out += "§f" + " ".repeat(29) + "§6> §e§lEXTRA STATS §6<"
        if (me != null) {
            if (me.bits > 0) out += centre("§r§8+§r§b${me.bits} Bits")
            out += centre("§r§8+§r§3${xp(me.cataXp)} Catacombs Experience")
            out += centre("§r§8+§r§3${xp(me.cataXp * CLASS_SHARE)} ${me.playerClass} Experience")
            for (c in CLASSES) if (c != me.playerClass) out += centre("§r§8+§r§3${xp(me.cataXp * TEAM_BONUS_SHARE)} $c Experience §r§b(Team Bonus)")
        }
        out += RULE
        out += RULE
        out += centre(title(" Stats")); out += ""; out += centre(scoreLine); out += centre(defeated); out += ""
        if (me != null) {
            out += centre("Total Damage as ${me.playerClass}: §r§a${comma(me.damage)}")
            out += centre("Ally Healing: §r§a${comma(me.healing)}")
        }
        out += centre("Enemies Killed: §r§a${me?.killed ?: killed}")
        out += centre("Deaths: §r§c${e.deaths}")
        out += centre("Secrets Found: §r§b${me?.secretsFound ?: e.secretsFound}")
        out += ""
        out += RULE
        return out
    }

    /** The class's own experience is this share of the Catacombs experience; each other class gets the team-bonus share (as recorded: 0.76 and 0.19). */
    const val CLASS_SHARE = 0.76
    const val TEAM_BONUS_SHARE = 0.19
}
