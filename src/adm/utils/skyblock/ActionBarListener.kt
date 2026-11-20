package com.adm.utils.skyblock

import com.adm.AdmMod.mc
import com.adm.events.MessageEvent
import com.adm.events.RoomEnterEvent
import com.adm.events.SecretsUpdateEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.utils.noControlCodes
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.ai.attributes.Attributes
import kotlin.math.floor

object ActionBarListener {
    enum class Stat(val title: String) {
        HEALTH("Health"),
        DEFENSE("Defense"),
        MANA("Mana"),
        VITALITY("Vitality"),
        SECRETS("Secrets"),
        TRUE_DEFENSE("True Defense"),
        ARMOR_STACKS("Armor Stacks"),
        TERMINATOR("Terminator Stage"),
        SKILL_XP("Skill XP"),
        MANA_USE("Mana Use"),
        ESSENCE("Essence"),
        ABILITY("Ability Status"),
        DRILL_FUEL("Drill Fuel"),
        RIFT_TIME("Rift Time"),
        WATER("Water"),
        GECKO_COMBO("Gecko Combo"),
        AURORA_RUNE("Aurora Rune"),
        INVULNERABLE("Invulnerable"),
        COOKIE_BITS("Cookie Bits"),
        ZONE("Zone"),
    }

    private val SECRETS_REGEX = Regex("(\\d+)/(\\d+) Secrets")
    private const val SHOWN_MS = 3000L
    private const val MAX_PIECES = 24
    private const val STACK_ICONS = "⁑ᝐ⚶Ѫ҉"
    private val STATS = Stat.entries

    var currentHealth: Int = 0
        private set
    var maxHealth: Int = 0
        private set
    var currentMana: Int = 0
        private set
    var maxMana: Int = 0
        private set
    var currentSpeed: Int = 0
        private set
    var currentDefense: Int = 0
        private set
    var overflowMana: Int = 0
        private set
    var effectiveHP: Int = 0
        private set
    var currentVitality: Int = 0
        private set
    var maxVitality: Int = 0
        private set
    var isVitalityShown: Boolean = false
        private set
    var trueDefense: Int = 0
        private set
    var armorStacks: Int = 0
        private set
    var terminatorStage: Int = 0
        private set
    var secretsFound: Int = 0
        private set
    var secretsTotal: Int = 0
        private set

    private val texts = arrayOfNulls<String>(STATS.size)
    private val seenAt = LongArray(STATS.size)
    private var present = 0

    private var lastBar: String? = null
    private var pieceCount = 0
    private val pieceStart = IntArray(MAX_PIECES)
    private val pieceEnd = IntArray(MAX_PIECES)
    private val pieceStat = IntArray(MAX_PIECES)

    private var skipNextSecrets = false
    private var lastSecretsMax: Int? = null

    /** The bar's own text for [stat] (with its colour codes), as last seen. */
    fun text(stat: Stat): String? = texts[stat.ordinal]

    /** Whether [stat] was on the action bar within the last few seconds. */
    fun shown(stat: Stat): Boolean = texts[stat.ordinal] != null && System.currentTimeMillis() - seenAt[stat.ordinal] < SHOWN_MS

    /** The room's "found/total Secrets"; Secret Counter hands it over here before taking it off the action bar. */
    fun secrets(message: String) {
        val (found, max) = SECRETS_REGEX.find(message)?.destructured ?: return
        val maxSecrets = max.toIntOrNull() ?: return
        val sameMax = maxSecrets == lastSecretsMax
        lastSecretsMax = maxSecrets
        if (skipNextSecrets && sameMax) {
            skipNextSecrets = false
            return
        }
        skipNextSecrets = false
        val room = DungeonUtils.currentRoom ?: return
        val updated = found.toIntOrNull() ?: return
        if (room.data?.maxSecrets != maxSecrets || (room.foundSecrets ?: -1) >= updated) return
        room.foundSecrets = updated
        SecretsUpdateEvent(room, updated).postAndCatch()
    }

    /** [bar] with every piece whose stat bit is set in [hidden] cut out; only for the bar last parsed. */
    internal fun without(bar: String, hidden: Int): String {
        if (bar != lastBar || hidden and present == 0) return bar
        val sb = StringBuilder(bar.length)
        for (i in 0 until pieceCount) {
            val stat = pieceStat[i]
            if (stat < 0 || hidden and (1 shl stat) == 0) sb.append(bar, pieceStart[i], pieceEnd[i])
        }
        return sb.toString()
    }

    private fun parse(bar: String) {
        if (bar != lastBar) {
            lastBar = bar
            present = 0
            pieceCount = 0
            split(bar)
            isVitalityShown = present and (1 shl Stat.VITALITY.ordinal) != 0
        }
        val now = System.currentTimeMillis()
        var m = present
        while (m != 0) {
            seenAt[Integer.numberOfTrailingZeros(m)] = now
            m = m and (m - 1)
        }
    }

    // Stats sit five spaces apart and add-ons (T3!, ⁑ stacks) two apart; the colour codes before a gap belong to the piece after it.
    private fun split(bar: String) {
        var start = 0
        var i = 0
        val n = bar.length
        while (i < n) {
            if (bar[i] == ' ' && i + 1 < n && bar[i + 1] == ' ') {
                var cut = i
                while (cut >= start + 2 && bar[cut - 2] == '§') cut -= 2
                piece(bar, start, cut)
                start = cut
                i += 2
                while (i < n && bar[i] == ' ') i++
            } else i++
        }
        piece(bar, start, n)
    }

    private fun piece(bar: String, start: Int, end: Int) {
        if (start >= end || pieceCount == MAX_PIECES) return
        val raw = bar.substring(start, end)
        val plain = raw.noControlCodes.trim()
        val stat = if (plain.isEmpty()) null else classify(plain)
        pieceStart[pieceCount] = start
        pieceEnd[pieceCount] = end
        pieceStat[pieceCount] = stat?.ordinal ?: -1
        pieceCount++
        if (stat == null) return
        present = present or (1 shl stat.ordinal)
        texts[stat.ordinal] = display(raw)
        store(stat, plain)
    }

    private fun classify(p: String): Stat? {
        val last = p.last()
        return when {
            p.indexOf('') >= 0 -> Stat.HEALTH
            p.indexOf('') >= 0 || p.indexOf('') >= 0 -> Stat.MANA
            p.indexOf('') >= 0 -> Stat.DEFENSE
            p.indexOf('') >= 0 -> Stat.VITALITY
            p.indexOf('') >= 0 || last == '❂' -> Stat.TRUE_DEFENSE
            p.endsWith(" Secrets") -> Stat.SECRETS
            STACK_ICONS.indexOf(last) >= 0 -> Stat.ARMOR_STACKS
            p == "T1" || p == "T2" || p == "T3!" -> Stat.TERMINATOR
            p.startsWith("CASTING") || p == "CANCELLED" || p.startsWith("NOT ENOUGH ") -> Stat.ABILITY
            p[0] == '+' && p.endsWith(" Essence") -> Stat.ESSENCE
            p.endsWith(" Bits from Cookie Buff!") -> Stat.COOKIE_BITS
            p[0] == '-' && last == ')' && p.contains(" Mana (") -> Stat.MANA_USE
            p[0] == '+' && last == ')' -> Stat.SKILL_XP
            p.endsWith(" Drill Fuel") -> Stat.DRILL_FUEL
            p.endsWith(" Left") -> Stat.RIFT_TIME
            p.endsWith(" Water") -> Stat.WATER
            p[0] == '[' && last == ']' -> Stat.GECKO_COMBO
            p == "Defender" || p == "Virtuoso" || p == "Mediator" -> Stat.AURORA_RUNE
            p.endsWith("INVULNERABLE") -> Stat.INVULNERABLE
            p[0] == '' || p[0] == '⏣' -> Stat.ZONE
            else -> null
        }
    }

    private fun store(stat: Stat, p: String) {
        when (stat) {
            Stat.HEALTH -> number(p, p.indexOf('/') + 1).let { if (it >= 0) maxHealth = it }
            Stat.MANA -> {
                val icon = p.indexOf('')
                if (icon >= 0) {
                    number(p, 0).let { if (it >= 0) currentMana = it }
                    number(p, p.indexOf('/') + 1).let { if (it >= 0) maxMana = it }
                }
                val overflow = p.indexOf('')
                overflowMana = if (overflow < 0) 0 else number(p, p.lastIndexOf(' ', overflow) + 1).coerceAtLeast(0)
            }
            Stat.DEFENSE -> number(p, 0).let { if (it >= 0) currentDefense = it }
            Stat.VITALITY -> {
                number(p, 0).let { if (it >= 0) currentVitality = it }
                number(p, p.indexOf('/') + 1).let { if (it >= 0) maxVitality = it }
            }
            Stat.SECRETS -> {
                secretsFound = number(p, 0).coerceAtLeast(0)
                secretsTotal = number(p, p.indexOf('/') + 1).coerceAtLeast(0)
            }
            Stat.TRUE_DEFENSE -> trueDefense = number(p, 0).coerceAtLeast(0)
            Stat.ARMOR_STACKS -> armorStacks = number(p, 0).coerceAtLeast(0)
            Stat.TERMINATOR -> terminatorStage = p[1] - '0'
            else -> {}
        }
    }

    // Digits and thousands commas from [from]; a decimal part is cut off. -1 when there is no number there.
    private fun number(s: String, from: Int): Int {
        var v = 0L
        var any = false
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c in '0'..'9') {
                if (v < Int.MAX_VALUE) v = v * 10 + (c - '0')
                any = true
            } else if (c != ',') break
            i++
        }
        return if (any) v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else -1
    }

    // The piece without the gap's spaces around its leading colour codes.
    private fun display(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '§' && i + 1 < raw.length) sb.append(c).append(raw[++i])
            else if (c != ' ') break
            i++
        }
        return sb.append(raw, i, raw.length).trimEnd().toString()
    }

    init {
        on<RoomEnterEvent> { skipNextSecrets = true }

        on<TickEvent.End> {
            currentHealth = (mc.player?.let { player -> (maxHealth * player.health / player.maxHealth).toInt() } ?: 0)
            currentSpeed = floor((mc.player?.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue?.toFloat() ?: 0f) * 1000f).toInt()
        }

        on<MessageEvent.Overlay> {
            // Every bar, even an unchanged one: the room-entry skip counts bars.
            secrets(message)
            parse(component.string)
            effectiveHP = (currentHealth * (1 + currentDefense / 100f)).toInt()
        }
    }
}
