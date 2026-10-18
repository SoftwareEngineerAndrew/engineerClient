package com.engineerclient.p3sim.clear.blood.camp

import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * What a blood-camp mob is made of: its modifier, its HP, its name tag and its look. Pure; [WatcherCamp] turns a [CampMob]
 * into the extras the mob service reads. Every number is measured on the recorded camps (1443 camp NPCs and 113
 * giants in 104 camps) unless the comment says it is not.
 */
class CampMob(
    /** `player` (a fake player with a name-tag stand) or `giant`. */
    val entity: String,
    /** The name as the tag shows it, without modifier or HP. */
    val name: String,
    /** The modifier word, or null for none (the Wandering Soul always has Boomer). */
    val modifier: String?,
    val hp: Double,
    /** The icon characters in front of the tag. */
    val tagHead: String,
    /** True for the mobs that count toward the 15 kills; the Wandering Soul does not. */
    val counted: Boolean,
) {
    /** The tag text after the icons: " Healthy Mute 330k" style, without the heart. */
    val tagText: String get() = (modifier?.let { "$it " } ?: "") + name
}

object CampMobs {
    /** Modifier weights over the 1,270 tagged blood mobs: Speedy 316, Healthy 306, Stormy 201, Stealth 156, Golden 150, Boomer 141. */
    val MODIFIER_WEIGHTS: List<Pair<String, Int>> = listOf("Speedy" to 316, "Healthy" to 306, "Stormy" to 201, "Stealth" to 156, "Golden" to 150, "Boomer" to 141)

    /** F7 standard mob HP: uniform over the observed 248,000 to 256,000 (median 252,000). */
    const val STANDARD_HP_LOW = 248_000.0
    const val STANDARD_HP_HIGH = 256_000.0

    /** Healthy multiplies HP by 1.3, Ooze by 1.25; giants and bosses climb a x1.3 ladder. */
    const val HEALTHY = 1.3
    const val OOZE = 1.25
    const val LADDER = 1.3
    const val GIANT_HP = 15_000_000.0
    const val BOSS_HP = 10_000_000.0

    /**
     * UNMEASURED: why a giant or boss sits on rung k (0 to 4) of the ladder is not explained by the data, so k is drawn from
     * these weights (the observed counts for non-Healthy bonzo were 10/7/5/3). Change them here when the cause is found.
     */
    val LADDER_WEIGHTS = doubleArrayOf(0.30, 0.30, 0.20, 0.12, 0.08)

    /** Tag icon prefixes by kind (private use glyphs of the server's resource pack). */
    const val ICON_STANDARD = ""
    const val ICON_GIANT = ""
    const val ICON_BONZO_SCARF = ""
    const val ICON_SPIRIT_BEAR = ""

    /** Fake-player profile of every camp NPC, and their walking speed (a chase adds +0.3 sprinting). */
    const val PROFILE = "Undead"
    const val SPEED = 0.31

    /** The camp NPCs wear a stone sword and chainmail. */
    const val EQUIPMENT = "MAINHAND:stone_sword,FEET:chainmail_boots,LEGS:chainmail_leggings,CHEST:chainmail_chestplate"

    /** Camp mobs die with the player-style animation and are removed 21 ticks after the death event. */
    const val DEATH_DELAY_TICKS = 21

    /** A mob appears 5.5 to 7.6 blocks above the floor (y 74.5 to 76.6 over the floor at 69; median 74.97) and falls to the floor in about 13 ticks. */
    const val SPAWN_RISE_LOW = 5.5
    const val SPAWN_RISE_HIGH = 7.6

    fun modifier(rng: Random): String {
        var x = rng.nextInt(MODIFIER_WEIGHTS.sumOf { it.second })
        for ((m, w) in MODIFIER_WEIGHTS) { if (x < w) return m; x -= w }
        return MODIFIER_WEIGHTS.last().first
    }

    private fun rung(rng: Random): Int {
        var x = rng.nextDouble() * LADDER_WEIGHTS.sum()
        for (k in LADDER_WEIGHTS.indices) { if (x < LADDER_WEIGHTS[k]) return k; x -= LADDER_WEIGHTS[k] }
        return LADDER_WEIGHTS.size - 1
    }

    private fun pow(k: Int): Double { var v = 1.0; repeat(k) { v *= LADDER }; return v }

    /** Rolls the mob a wall head makes. */
    fun roll(station: WallStation, rng: Random): CampMob {
        val name = if (station.cls == WallClass.GIANT) "Giant" else station.mobName
        val mod = if (station.cls == WallClass.WANDERING_SOUL) "Boomer" else modifier(rng)
        val healthy = if (mod == "Healthy") HEALTHY else 1.0
        val hp: Double
        val head: String
        when (station.cls) {
            WallClass.GIANT -> { hp = GIANT_HP * pow(rung(rng)) * healthy; head = ICON_GIANT }
            WallClass.BOSS -> {
                hp = BOSS_HP * pow(rung(rng)) * healthy
                head = when (name) { "Bonzo", "Scarf" -> ICON_BONZO_SCARF; "Spirit Bear" -> ICON_SPIRIT_BEAR; else -> ICON_STANDARD }
            }
            else -> {
                val base = STANDARD_HP_LOW + rng.nextDouble() * (STANDARD_HP_HIGH - STANDARD_HP_LOW)
                hp = base * healthy * (if (name == "Ooze") OOZE else 1.0); head = ICON_STANDARD
            }
        }
        return CampMob(
            entity = if (station.cls == WallClass.GIANT) "giant" else "player",
            name = name, modifier = mod, hp = hp.roundToLong().toDouble(), tagHead = head,
            counted = station.cls != WallClass.WANDERING_SOUL,
        )
    }

    /** The extras handed to the mob service with the spawn (the entity kind, disguise, tag, gear, speed and death delay of a camp mob). */
    fun extras(m: CampMob, landing: String): Map<String, String> = buildMap {
        put("camp", "blood"); put("landing", landing)
        put("name", m.name)
        put("entity", m.entity)
        m.modifier?.let { put("modifier", it) }
        put("displayName", m.name)
        put("tagHead", m.tagHead); put("tagNameColor", "red")
        put("speed", SPEED.toString())
        put("equipment", EQUIPMENT)
        put("deathDelay", DEATH_DELAY_TICKS.toString())
        put("targetable", "true")
        put("counted", m.counted.toString())
        if (m.entity == "player") { put("disguise", "true"); put("uuidVersion", "2"); put("profile", PROFILE) }
    }
}
