package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.pack.mobs.MobArchetype
import com.engineerclient.p3sim.pack.mobs.MobSkin
import com.engineerclient.p3sim.pack.mobs.MobTag

/**
 * The F7 room mobs with a SkyBlock level tag that the mob pack has no archetype for (its recordings predate them): each is an archetype
 * of its own here, outside the pack's list (indices from [BASE]). Measured on the Dungeon Recorder (139 F7 runs, 2026-10-03 to 10-16)
 * and ADR:
 *
 * - Undead Skeleton: a skeleton with a bow (1,441 of 1,441 bodies), its tag at body id + 1 and 2.1875 up, 360k HP (1,594 of 1,603 tags;
 *   the rest 1.4M, a room a WITHER door scaled), never starred, never a prefix word. About 11.5 a run, mostly alone in ordinary rooms.
 * - Skeletor Prime: a fake player holding a bone (it throws it: the hand is empty about half the time), one skin (ADR, 136 of 136), its tag
 *   at body id + 1 and 2.125 up, 3.1M HP (597 of 597), never starred, never a prefix word. About 4.3 a run.
 * - The tag: `§8[§r§7Lv` level `§r§8] §r` + icons + ` §r§c` name `§r §` HP colour + HP `§r§f/§r§a` max HP `§r§c❤` (ADR, Skeletor Prime);
 *   the HP is green, yellow under half (ADR: every reading at 0.50 or more green, every one under yellow). The levels went up on
 *   2026-10-03 (Lv120 both before; Lv173 and Lv277 since). The Undead Skeleton's icons are U+E084 U+E081 (the Dungeon Recorder keeps no
 *   colour codes): the zombie icon is `§2` in every pack tag and the skeleton icon `§f` in Skeletor Prime's and the Skeleton Master's, so
 *   its colours are those, not recorded ones.
 */
object LevelLooks {
    const val BASE = 101_000
    const val PREFIX = "lv:"

    private class Look(val id: String, val name: String, val entity: String, val level: Int, val hp: Long, val icons: String, val dy: Double, val skin: String?, val hand: String)

    private val LOOKS: List<Look> = listOf(
        Look("undead_skeleton", "Undead Skeleton", "minecraft:skeleton", 173, 360_000, "§2§r§f", 2.1875, null, "minecraft:bow"),
        Look("skeletor_prime", "Skeletor Prime", "minecraft:player", 277, 3_100_000, "§f", 2.125, "1bd3a4f8e22db15e486acac5d456ff35f34ed9ab5c64eec057afd2866d559401", "minecraft:bone"),
    )

    private val archetypes: List<MobArchetype> = LOOKS.mapIndexed { i, l ->
        MobArchetype(
            index = BASE + i, id = PREFIX + l.id, name = l.name, cls = "clear",
            entity = l.entity, disguise = l.entity == "minecraft:player",
            skin = l.skin?.let { MobSkin(it, BloodLooks.skinValue(it, false), null, false) },
            bodyName = null, equipment = null, hp = mapOf("F7" to l.hp), star = false,
            tag = MobTag(idOffset = 1, dy = l.dy, head = "", nameColor = "§c", bold = false),
            movement = "static", wake = null, modifierWeights = emptyMap(), seen = emptyMap(),
        )
    }

    /** Every archetype of this object, for matching a recorded tag's name. */
    val all: List<MobArchetype> get() = archetypes

    /** The archetype at [index] (one of this object's), or null. */
    fun archetype(index: Int): MobArchetype? = archetypes.getOrNull(index - BASE)

    fun isLevel(arch: MobArchetype): Boolean = arch.id.startsWith(PREFIX)

    /** The item id the mob holds. */
    fun hand(arch: MobArchetype): String? = LOOKS.getOrNull(arch.index - BASE)?.hand

    /** The tag at [hp] of [maxHp]. */
    fun tag(arch: MobArchetype, hp: Double, maxHp: Double): String {
        val l = LOOKS.getOrNull(arch.index - BASE) ?: return arch.name
        val colour = if (hp * 2 < maxHp) "e" else "a"
        return "§8[§r§7Lv${l.level}§r§8] §r${l.icons} §r§c${l.name}§r §$colour${MobTags.formatHp(hp)}§r§f/§r§a${MobTags.formatHp(maxHp)}§r§c❤"
    }
}
