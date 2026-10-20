package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.pack.mobs.MobArchetype
import com.engineerclient.p3sim.pack.mobs.MobSkin
import com.engineerclient.p3sim.pack.mobs.MobTag

/**
 * The F7 mobs the mob pack has no archetype for (its recordings predate them or never caught them): each is an archetype of its own here,
 * outside the pack's list (indices from [BASE]). Measured on the Dungeon Recorder (139 F7 runs, 2026-10-03 to 10-16) and ADR:
 *
 * - Undead Skeleton: a skeleton with a bow (1,441 of 1,441 bodies), its tag at body id + 1 and 2.1875 up, 360k HP (1,594 of 1,603 tags;
 *   the rest 1.4M, a room a WITHER door scaled), never starred, never a prefix word. About 11.5 a run, mostly alone in ordinary rooms.
 * - Skeletor Prime: a fake player holding a bone (it throws it: the hand is empty about half the time), one skin (ADR, 136 of 136), its tag
 *   at body id + 1 and 2.125 up, 3.1M HP (597 of 597), never starred, never a prefix word. About 4.3 a run.
 * - Lonely Spider (the Spider room's 97 and Pressure Plates' 12, the same spots every visit): a spider with nothing worn, its tag at body id
 *   + 1 and 1.1875 up, 120k HP before its prefix word (it takes the pack's words: Healthy, Speedy...), never starred. Its tag is a dungeon
 *   mob's, not a levelled one: icon U+E074 (`§4`, as ADR's Parasite tags colour it), the word and the name, the HP.
 * - Parasite: a camp Parasite that dies splits 20 ticks later (108 of 138 recorded splits; the rest 19 or 21) into three silverfish of
 *   33,333 HP on the floor where it died (each up to 0.7 blocks off that spot: the spread is not matched), tag at body id + 1 and 0.375 up,
 *   icon U+E074 `§4` (ADR). Lv30 before the update of 2026-10-03, Lv79 since.
 * - A levelled tag: `§8[§r§7Lv` level `§r§8] §r` + icons + ` §r§c` name `§r §` HP colour + HP `§r§f/§r§a` max HP `§r§c❤` (ADR, Skeletor
 *   Prime and Parasite); the HP is green, yellow under half (ADR: every reading at 0.50 or more green, every one under yellow). The levels
 *   went up on 2026-10-03 (Lv120 for both before; Lv173 and Lv277 since). The Undead Skeleton's icons are U+E084 U+E081 (the Dungeon
 *   Recorder keeps no colour codes): the zombie icon is `§2` in every pack tag and the skeleton icon `§f` in Skeletor Prime's and the
 *   Skeleton Master's, so its colours are those, not recorded ones.
 */
object LevelLooks {
    const val BASE = 101_000
    const val PREFIX = "lv:"

    /** The camp Parasite's split: [PARASITE_SPLIT] of these, [PARASITE_SPLIT_TICKS] after it dies. */
    const val PARASITE_ID = PREFIX + "parasite"
    const val PARASITE_SPLIT = 3
    const val PARASITE_SPLIT_TICKS = 20

    /** [level] null: a dungeon mob's tag (the pack's format), not a levelled one. */
    private class Look(val id: String, val name: String, val entity: String, val level: Int?, val hp: Long, val icons: String, val dy: Double, val skin: String?, val hand: String?)

    private val LOOKS: List<Look> = listOf(
        Look("undead_skeleton", "Undead Skeleton", "minecraft:skeleton", 173, 360_000, "§2§r§f", 2.1875, null, "minecraft:bow"),
        Look("skeletor_prime", "Skeletor Prime", "minecraft:player", 277, 3_100_000, "§f", 2.125, "1bd3a4f8e22db15e486acac5d456ff35f34ed9ab5c64eec057afd2866d559401", "minecraft:bone"),
        Look("lonely_spider", "Lonely Spider", "minecraft:spider", null, 120_000, "§4", 1.1875, null, null),
        Look("parasite", "Parasite", "minecraft:silverfish", 79, 33_333, "§4", 0.375, null, null),
    )

    private val archetypes: List<MobArchetype> = LOOKS.mapIndexed { i, l ->
        MobArchetype(
            index = BASE + i, id = PREFIX + l.id, name = l.name, cls = "clear",
            entity = l.entity, disguise = l.entity == "minecraft:player",
            skin = l.skin?.let { MobSkin(it, BloodLooks.skinValue(it, false), null, false) },
            bodyName = null, equipment = null, hp = mapOf("F7" to l.hp), star = false,
            tag = MobTag(idOffset = 1, dy = l.dy, head = if (l.level == null) l.icons + " §r" else "", nameColor = "§c", bold = false),
            movement = "static", wake = null, modifierWeights = null, seen = emptyMap(),
        )
    }

    /** Every archetype of this object, for matching a recorded tag's name. */
    val all: List<MobArchetype> get() = archetypes

    /** The archetype at [index] (one of this object's), or null. */
    fun archetype(index: Int): MobArchetype? = archetypes.getOrNull(index - BASE)

    /** The archetype of id [id] (`lv:parasite`), or null. */
    fun byId(id: String): MobArchetype? = archetypes.firstOrNull { it.id == id }

    fun isLevel(arch: MobArchetype): Boolean = arch.id.startsWith(PREFIX)

    /** Whether the archetype's tag is a levelled one (`[Lv79] ...`); the others are written as the pack's mobs are ([MobTags.nametagLegacy]). */
    fun levelled(arch: MobArchetype): Boolean = LOOKS.getOrNull(arch.index - BASE)?.level != null

    /** The item id the mob holds, or null for none. */
    fun hand(arch: MobArchetype): String? = LOOKS.getOrNull(arch.index - BASE)?.hand

    /** The levelled tag at [hp] of [maxHp] (the archetype's name for one that is not levelled). */
    fun tag(arch: MobArchetype, hp: Double, maxHp: Double): String {
        val l = LOOKS.getOrNull(arch.index - BASE) ?: return arch.name
        val level = l.level ?: return arch.name
        val colour = if (hp * 2 < maxHp) "e" else "a"
        return "§8[§r§7Lv$level§r§8] §r${l.icons} §r§c${l.name}§r §$colour${MobTags.formatHp(hp)}§r§f/§r§a${MobTags.formatHp(maxHp)}§r§c❤"
    }
}
