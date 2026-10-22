package com.engineerclient.p3sim.clear.hud

import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.PuzzleStatus
import java.util.UUID

/**
 * The dungeon tab list: 80 rows (4 columns of 20) of invisible fake players whose display names carry the text, exactly as
 * Hypixel lays them out (scout-client-state 3.3). Pure; the packets are built in [HudPackets].
 *
 * Odin reads these rows with `^\[(\d+)] (?:\[\w+] )*(\w+) .*?\((\w+)(?: (\w+))*\)$` for members and the `Secrets Found`, `Opened Rooms`,
 * `Completed Rooms`, `Crypts`, `Team Deaths`, `Puzzles: (n)` and ` Name: [x]` forms for the rest; every row here is checked against
 * those regexes in the tests. The text is plain (no `§`): the packet builder turns `§` codes into styles, so `displayName.string` is clean.
 */
object TabModel {
    const val ROWS = 80
    const val COLUMN = 20
    const val PARTY_ROWS = 16

    /** The profile name of row [i]: `!A-a` ... `!D-t`. The vanilla list sorts by name, which keeps them in this order. */
    fun profileName(i: Int): String = "!${'A' + i / COLUMN}-${'a' + i % COLUMN}"

    /** A stable fake UUID per row (a name-based, version 3 UUID, never a random v4, so mods read them as fake players). */
    fun profileId(i: Int): UUID = UUID.nameUUIDFromBytes("ads:tab:${profileName(i)}".toByteArray())

    /** Hypixel's blank tab rows are empty text, not a space. */
    private const val BLANK = ""

    /** Where each block starts (recorded, identical in all 70 sessions): the party, the player stats, the dungeon stats, the account. */
    const val PLAYER_STATS_AT = 20
    const val DUNGEON_STATS_AT = 40
    const val ACCOUNT_AT = 60

    /** The most puzzle rows the block has room for (rows 48 to 52 are followed by blanks). */
    const val PUZZLE_ROWS = 5

    /** A percent as the tab prints it: one decimal with a trailing `.0` dropped (`0%`, `2%`, `12.5%`). */
    fun percent(v: Double): String = "%.1f".format(java.util.Locale.ROOT, v).removeSuffix(".0") + "%"

    private fun ArrayList<String>.padTo(n: Int) { while (size < n) add(BLANK) }

    /** The 80 rows for [viewer]'s tab: the members first, then the stats blocks, each at its recorded row. Shorter content is padded with blank rows. */
    fun rows(ctx: HudContext, viewer: UUID?): List<String> {
        val st = ctx.state
        val me = st.players[viewer]
        val rows = ArrayList<String>(ROWS)
        val members = ctx.membersFor(viewer)

        // Column A: the party, a header and five slots of four rows (the last slot has no blank row after it).
        rows += "         Party (${members.size})"
        for (slot in 0 until 5) {
            val p = members.getOrNull(slot)
            if (p == null) { rows += BLANK; rows += BLANK; rows += BLANK }
            else {
                rows += memberRow(ctx, p)
                rows += ultimateRow(ctx, p)
                rows += " Revive Stones: ${reviveStones(ctx, p)}"
            }
            if (slot < 4) rows += BLANK
        }

        // Column B: the player stats, then the team numbers.
        rows.padTo(PLAYER_STATS_AT)
        rows += "       Player Stats"
        rows += "Downed: NONE"
        rows += " Time: N/A"
        rows += " Revive: N/A"
        rows.padTo(PLAYER_STATS_AT + 5)
        rows += "Team Deaths: ${st.deaths.teamDeaths}"
        rows += " Team Damage Dealt: ${HudFormat.abbreviate(ctx.teamDamage)}${HudGlyphs.HEART}"
        rows += " Team Healing Done: ${HudFormat.abbreviate(ctx.teamHealing)}${HudGlyphs.HEART}"
        val milestone = (me?.extras?.get("milestone") as? Number)?.toInt()
        rows += if (!ctx.started || milestone == null) " Your Milestone: ${HudGlyphs.SKULL}${MILESTONES[0]}" else " Your Milestone: ${HudGlyphs.SKULL}${MILESTONES[milestone.coerceIn(0, MILESTONES.size - 1)]}"
        val pct = (me?.extras?.get("milestonePct") as? Number)?.toDouble() ?: 0.0
        val next = ((milestone ?: 0) + 1).coerceIn(0, MILESTONES.size - 1)
        rows += if ((milestone ?: 0) >= MILESTONES.size - 1) BLANK else " (${percent(pct)} to ${MILESTONES[next]})"
        val own = me?.secretsFound ?: 0
        rows += "Discoveries: ${discoveries(own)}"
        rows += " Secrets Found: $own"
        rows += " Crypts: ${st.secrets.crypts}"

        // Column C: the dungeon stats and the puzzles.
        rows.padTo(DUNGEON_STATS_AT)
        rows += "       Dungeon Stats"
        rows += "Dungeon: Catacombs"
        rows += " Opened Rooms: ${st.counters.opened}"
        rows += " Completed Rooms: ${st.counters.completed}"
        rows += " Secrets Found: ${percent(st.secrets.percent)}"
        rows += " Time: ${timeRow(ctx)}"
        rows += BLANK
        rows += "Puzzles: (${st.puzzles.size})"
        for ((i, pz) in st.puzzles.take(PUZZLE_ROWS).withIndex()) rows += puzzleRow(pz.revealed, pz.tabName, pz.status, ctx.failedBy[i])
        rows += ctx.extraTab
        if (rows.size > ACCOUNT_AT) rows.subList(ACCOUNT_AT, rows.size).clear()

        // Column D: the account block is the player's own SkyBlock profile, which the sim does not have. Plain filler keeps
        // the slots used the way they are on Hypixel; Devonian matches rows by regex so the exact filler is free.
        rows.padTo(ACCOUNT_AT)
        rows += "       Account Info"
        rows += "Profile: Practice"
        rows += " Pet Sitter: N/A"
        rows += "Skills: Combat 60: MAX"
        rows += " Speed: 700"
        rows.padTo(ROWS)
        return rows.take(ROWS)
    }

    /** The discoveries row: the player's secrets plus the non-secret finds (recorded ratio 1.00 to 1.17, see [HudConstants.DISCOVERY_RATIO]). */
    fun discoveries(secrets: Int): Int = Math.ceil(secrets * HudConstants.DISCOVERY_RATIO).toInt()

    private val MILESTONES = listOf("⓿", "❶", "❷", "❸", "❹", "❺", "❻", "❼", "❽", "❾", "❿")

    private fun timeRow(ctx: HudContext): String = if (!ctx.started) "Soon!" else HudFormat.elapsed(ctx.elapsedTicks)

    /** The [PlayerRecord.extras] key of the ticks an ultimate still has to cool down (the damage module writes it when a member uses theirs). */
    const val ULTIMATE_COOLDOWN_KEY = "ultimateCooldownTicks"

    /** The [PlayerRecord.extras] key of a member's Revive Stone count, for members the HUD cannot count itself (bots). */
    const val REVIVE_STONES_KEY = "reviveStones"

    /**
     * The ultimate row. Recorded (193 members in 59 runs): `Ultimate: N/A` until T0, `Ready` on the very tick the class names appear
     * (0 to 2 ticks apart in every run), and after a use a clock that counts down one a second (`04m 14s`, `42s`, `00s`) and goes back
     * to `Ready` 20 ticks after it reads `00s`. The ready chat line comes about 21 seconds later, but the row does not wait for it.
     */
    fun ultimateRow(ctx: HudContext, p: PlayerRecord): String {
        if (!ctx.started) return " Ultimate: N/A"
        val left = (p.extras[ULTIMATE_COOLDOWN_KEY] as? Number)?.toInt() ?: 0
        return if (left > 0) " Ultimate: ${HudFormat.elapsed(left)}" else " Ultimate: Ready"
    }

    /**
     * The Revive Stones a member carries (recorded: it rises by one for each pickup and falls when a stone is used; 0 for about half of the
     * members at the Maxor line, 1 for 29%, 2 for 13%). The HUD counts a human's inventory ([HudContext.reviveStones]); a bot's count is
     * [REVIVE_STONES_KEY] on its record, which the secrets module raises when the bot finds one.
     */
    fun reviveStones(ctx: HudContext, p: PlayerRecord): Int =
        ctx.reviveStones[p.uuid] ?: (p.extras[REVIVE_STONES_KEY] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0

    fun memberRow(ctx: HudContext, p: PlayerRecord): String {
        val cls = HudFormat.className(p.dungeonClass)
        // Until T0 every member reads EMPTY; the class (and its level) shows from the start of the run.
        val level = (p.extras["classLevel"] as? Number)?.toInt() ?: ctx.stats.classLevel
        val tail = when {
            p.ghost -> "DEAD"
            cls == null || !ctx.started -> "EMPTY"
            else -> "$cls ${HudFormat.roman(level)}"
        }
        val sb = (p.extras["sbLevel"] as? Number)?.toInt() ?: ctx.stats.sbLevel
        // A bot's identity carries the glyph real players wear after their name (none for some); everyone else reads the skull.
        val glyph = (p.extras["glyph"] as? String)?.replace(Regex("§."), "")
        val mid = when {
            glyph == null -> " ${HudGlyphs.SKULL}"
            glyph.isEmpty() -> ""
            else -> " $glyph"
        }
        return "[$sb] ${p.name}$mid ($tail)"
    }

    /**
     * ` ???: [✦]` (no trailing space) before the puzzle is known, ` Boulder: [✦] ` once it is, ` Boulder: [✔] ` when done (never a name),
     * and ` Boulder: [✖] (name)` when failed, with the one who failed it when known.
     */
    fun puzzleRow(revealed: Boolean, tabName: String, status: PuzzleStatus, failedBy: String?): String {
        val mark = when (status) { PuzzleStatus.SOLVED -> "✔"; PuzzleStatus.FAILED -> "✖"; PuzzleStatus.UNSOLVED -> "✦" }
        if (!revealed) return " ???: [$mark]"
        return if (status == PuzzleStatus.FAILED && failedBy != null) " $tabName: [$mark] ($failedBy)" else " $tabName: [$mark] "
    }

    /** The tab header (Hypixel's two-line text). */
    const val HEADER = "§bYou are playing on §e§lMC.HYPIXEL.NET"

    private val BLESSING_ORDER = listOf("Power", "Life", "Wisdom", "Stone", "Time")

    private const val STORE_LINE = "§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET"

    /** Ticks before T0 at which the Dungeon Buffs block appears (recorded: 15). */
    private const val BUFFS_BEFORE_T0 = 15

    /**
     * The footer: before the run just the store line; the active effects follow, and the Dungeon Buffs block a moment before T0 with
     * every blessing found (Odin reads `Blessing of Power V`; the blessing lines are white).
     */
    fun footer(ctx: HudContext): String {
        if (ctx.stage == com.engineerclient.p3sim.run.RunStage.COUNTDOWN && !(ctx.t0Known && ctx.ticksToStart <= BUFFS_BEFORE_T0)) return STORE_LINE
        val b = ctx.state.secrets.blessings
        val found = BLESSING_ORDER.mapNotNull { name ->
            val lvl = b.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: 0
            if (lvl > 0) "§fBlessing of $name ${HudFormat.roman(lvl)}" else null
        }
        val buffs = if (found.isEmpty()) listOf("§7No Buffs active. Find them by exploring the Dungeon!") else found
        return (listOf("", "§a§lActive Effects", "§7No effects active.", "", "§6§lDungeon Buffs") + buffs + listOf("", STORE_LINE)).joinToString("\n")
    }
}
