package com.engineerclient.p3sim.clear.hud

import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.RunState
import java.util.UUID

/** One extra piece of the action bar, set through `ChannelService.actionBarSegment`. */
class BarSegment(val key: String, val text: String, val priority: Int, val expiresTick: Int, val audience: UUID?, val setTick: Int)

/**
 * The live extra segments of the action bar. A segment lives until its expiry tick or until replaced under the same key. Pure; the tick
 * is the server tick, passed in. Not thread safe: server thread only.
 */
class BarSegments {
    private val map = LinkedHashMap<String, BarSegment>()

    /** [to] null: every viewer sees it. */
    fun set(key: String, text: String, priority: Int, tick: Int, ttlTicks: Int, to: UUID?) {
        map[key + "\u0000" + (to ?: "")] = BarSegment(key, text, priority, tick + Math.max(1, ttlTicks), to, tick)
    }

    fun clear(key: String) { map.keys.removeIf { it.substringBefore('\u0000') == key } }

    fun clearAll() = map.clear()

    /** Drops what has expired and returns what [viewer] sees, strongest first (priority, then the newest). */
    fun live(tick: Int, viewer: UUID?): List<BarSegment> {
        map.values.removeIf { it.expiresTick <= tick }
        return map.values.filter { it.audience == null || it.audience == viewer }.sortedWith(compareByDescending<BarSegment> { it.priority }.thenByDescending { it.setTick })
    }
}

/**
 * The action bar as Hypixel lays it out (scout-client-state 3.5): segments separated by five spaces, in the order health, defense,
 * mana (with overflow mana), vitality, and last `N/M Secrets` when the player stands in a room that has secrets. A temporary segment of
 * priority [REPLACES_DEFENSE] or more (essence found, a skill XP gain) takes the place of the defense segment while it lives; any other
 * extra segment is added before the secrets segment. Pure.
 *
 * Odin reads: `([\d|,]+)/([\d|,]+)` health, `` mana, `` overflow, `([\d|,]+)( Defense)?`, `` vitality and
 * `(\d+)/(\d+) Secrets`; it only counts a secret when M equals the room database's maximum, so M is the room's [secretsMax].
 */
object ActionBarModel {
    /** Priority from which a segment replaces the defense segment. */
    const val REPLACES_DEFENSE = 100

    /** The overflow mana every recorded dungeon bar carries (19,754 of 19,990 bars read 600). */
    const val OVERFLOW_MANA_DEFAULT = 600

    private const val GAP = "     "

    /**
     * @param extras the live segments (see [BarSegments.live]).
     * @param manaNow current mana; the sim does not model mana, so this is normally the maximum.
     * @param overflowMana shown only above zero; Hypixel shows 600 on nearly every bar of a dungeon run.
     * @param vitalityNow current vitality, at most the maximum; the maximum when null.
     */
    fun compose(
        stats: HudStats,
        me: PlayerRecord?,
        state: RunState,
        extras: List<BarSegment>,
        manaNow: Int = stats.maxMana,
        overflowMana: Int = OVERFLOW_MANA_DEFAULT,
        vitalityNow: Int? = null,
        showSecrets: Boolean = true,
    ): String {
        // A ghost's SkyBlock health stays full (the recorded bar reads full health while dead).
        val hp = if (me == null || me.ghost) stats.maxHp else Math.round(stats.maxHp * Math.max(0.0, me.hpPercent) / 100.0).toInt()
        val parts = ArrayList<String>()
        parts += "§c${HudFormat.commas(hp)}/${HudFormat.commas(stats.maxHp)}${HudGlyphs.HEALTH}"
        val replacement = extras.firstOrNull { it.priority >= REPLACES_DEFENSE }
        parts += replacement?.text ?: "§a${HudFormat.commas(stats.defense)}${HudGlyphs.DEFENSE}"
        val mana = "§b${HudFormat.commas(manaNow)}/${HudFormat.commas(stats.maxMana)}${HudGlyphs.MANA}"
        parts += if (overflowMana > 0) "$mana §3${HudFormat.commas(overflowMana)}${HudGlyphs.OVERFLOW_MANA}" else mana
        stats.vitality?.let { parts += "§4${(vitalityNow ?: it).coerceIn(0, it)}/$it${HudGlyphs.VITALITY}" }
        for (e in extras) if (e !== replacement) parts += e.text
        val base = parts.joinToString(GAP) + GAP
        // Recorded: the secrets segment follows the baseline's own trailing gap with a gap of its own, and has no gap after it.
        val secrets = if (showSecrets && me != null) secretsSegment(state, me) else null
        return if (secrets != null) base + GAP + secrets else base
    }

    /** `0/5 Secrets` for the room the player stands in, or null in a gap, a corridor, a room without secrets or outside the dungeon. */
    fun secretsSegment(state: RunState, me: PlayerRecord): String? {
        val room = state.roomAt(me.x, me.z) ?: return null
        if (room.secretsMax <= 0) return null
        return "§7${room.secretsFound}/${room.secretsMax} Secrets"
    }
}
