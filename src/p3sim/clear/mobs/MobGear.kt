package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.SimItems
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.DyedItemColor
import java.util.Base64
import java.util.UUID

/**
 * What each clear mob wears and holds on Hypixel, as the Dungeon Recorder saw it (223 F7 runs, the equipment packets of every tagged
 * body): the dyed leather of the Zombie Soldier, Skeleton Master, Super Archer..., the Zombie Knight's gold, the Sniper's head, the
 * Commander's whip, and the minibosses' sets. Every row is the only gear recorded for that mob (100 percent of its bodies) unless
 * it says otherwise. The Lost Adventurer wears one of four dragon sets and the skin that goes with it ([lostSet]).
 */
object MobGear {
    private class Piece(val slot: EquipmentSlot, val item: Item, val name: String? = null, val dye: Int? = null, val head: String? = null, val glint: Boolean = false)

    private fun leather(prefix: String, rgb: Int, glint: Boolean = false, chestRgb: Int = rgb, legsRgb: Int = chestRgb, feetRgb: Int = rgb) = listOf(
        Piece(EquipmentSlot.HEAD, Items.LEATHER_HELMET, "$prefix Helmet", rgb, glint = glint),
        Piece(EquipmentSlot.CHEST, Items.LEATHER_CHESTPLATE, "$prefix Chestplate", chestRgb, glint = glint),
        Piece(EquipmentSlot.LEGS, Items.LEATHER_LEGGINGS, "$prefix Leggings", legsRgb, glint = glint),
        Piece(EquipmentSlot.FEET, Items.LEATHER_BOOTS, "$prefix Boots", feetRgb, glint = glint),
    )

    private fun gold(prefix: String) = listOf(
        Piece(EquipmentSlot.HEAD, Items.GOLDEN_HELMET, "$prefix Helmet"), Piece(EquipmentSlot.CHEST, Items.GOLDEN_CHESTPLATE, "$prefix Chestplate"),
        Piece(EquipmentSlot.LEGS, Items.GOLDEN_LEGGINGS, "$prefix Leggings"), Piece(EquipmentSlot.FEET, Items.GOLDEN_BOOTS, "$prefix Boots"),
    )

    private fun hand(item: Item, name: String? = null, glint: Boolean = false) = Piece(EquipmentSlot.MAINHAND, item, name, glint = glint)

    private val GEAR: Map<String, List<Piece>> = mapOf(
        "zombie_soldier" to leather("Zombie Soldier", 0xd07f00) + hand(Items.IRON_SWORD, "Zombie Soldier Cutlass"),
        "skeleton_master" to leather("Skeleton Master", 0xff6b0b) + hand(Items.BOW),
        "skeleton_soldier" to leather("Skeleton Soldier", 0xffbc0b) + hand(Items.BOW),
        "super_archer" to leather("Bouncy", 0xadff2f) + hand(Items.BOW),
        "super_tank_zombie" to leather("Super Heavy", 0xe6e6e6, chestRgb = 0x5a6464) + hand(Items.STONE_SWORD, "Earthen Blade"),
        "zombie_knight" to gold("Zombie Knight"),
        "zombie_commander" to leather("Zombie Commander", 0xd51230) + hand(Items.FISHING_ROD, "Zombie Commander Whip"),
        // The Lords' leather is purple (green) in 94 (93) percent, near black in the rest; the helmet is gold.
        "zombie_lord" to listOf(Piece(EquipmentSlot.HEAD, Items.GOLDEN_HELMET, "Zombie Lord Helmet")) + leather("Zombie Lord", 0x9b01c1).drop(1) + hand(Items.DIAMOND_SWORD),
        "skeleton_lord" to listOf(Piece(EquipmentSlot.HEAD, Items.GOLDEN_HELMET, "Skeleton Lord Helmet")) + leather("Skeleton Lord", 0x268105).drop(1) + hand(Items.BOW),
        "sniper" to listOf(Piece(EquipmentSlot.HEAD, Items.PLAYER_HEAD, "Sniper Helmet", head = "b18c071f080dba50a62a6263ff724edc157ce4fb4883cceff2491d5bbde830c1"), hand(Items.BOW)),
        "crypt_dreadlord" to listOf(hand(Items.IRON_SWORD)),
        "crypt_souleater" to listOf(hand(Items.BOW)),
        "crypt_undead" to listOf(hand(Items.BONE)),
        "withermancer" to listOf(hand(Items.STONE_SWORD)),
        "angry_archaeologist" to listOf(
            Piece(EquipmentSlot.HEAD, Items.DIAMOND_HELMET, "Perfect Helmet - Tier XII", glint = true), Piece(EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE, "Perfect Chestplate - Tier XII", glint = true),
            Piece(EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS, "Perfect Leggings - Tier XII", glint = true), Piece(EquipmentSlot.FEET, Items.DIAMOND_BOOTS, "Perfect Boots - Tier XII", glint = true),
            hand(Items.DIAMOND_SWORD, glint = true),
        ),
        "frozen_adventurer" to listOf(Piece(EquipmentSlot.HEAD, Items.PLAYER_HEAD, "Frozen Blaze Helmet", head = "70310b55eed958fee8ffaaf718467167deb1a7c9046d67b25887b596925636bb")) +
            leather("Frozen Blaze", 0xa0daef, glint = true).drop(1) + hand(Items.STICK, "Ice Spray Wand", glint = true),
        "king_midas" to gold("Golden") + hand(Items.GOLDEN_SWORD),
    )

    /** A Lost Adventurer's dragon set: its helmet head, leather colour (boots differ for Superior) and the skin its body wears. */
    class LostSet(val word: String, val helmet: String, val rgb: Int, val feetRgb: Int, val skin: String, val weight: Int)

    /** Dungeon Recorder, 596 Lost Adventurers: Holy 277, Young 159, Unstable 119, Superior 40, each with one skin. */
    val LOST_SETS = listOf(
        LostSet("Holy", "99cc8ba26793218f6f6491947a1837fd52878701f33f76bbe2883a45aba8ab8f", 0x47d147, 0x47d147, "3a26cf66af42a48f6be52a0a48a8eb598f3a23639b7a5a8ee171c13999ee94c9", 277),
        LostSet("Young", "a0e81ed07dfb0244d56f4d5f2b37553ec026fb4796f0fb0c57a8eb264983e1e0", 0xdde4f0, 0xdde4f0, "36a0383a527803d99b662ad18bcac78c119250ebbfb14475b4eb4d4a662796b4", 159),
        LostSet("Unstable", "605fc4e2b68c45059afef3999e9a95aa3d8943597a7894e569397d6cf5d1672a", 0xb212e3, 0xb212e3, "e381a1875efeb2a260d357984c3e75a7dfbc7a22b582bfe9990421cbb445acc", 119),
        LostSet("Superior", "1e0329f42192ee7c1a0c704f82dbbbe7c00fbfa3f020c1067a208630b991b988", 0xf2df11, 0xf25d18, "36a0383a527803d99b662ad18bcac78c119250ebbfb14475b4eb4d4a662796b4", 40),
    )

    /** The set a Lost Adventurer wears, drawn from its UUID (so its skin and armour agree, and stay the same when it is rebuilt). */
    fun lostSet(uuid: UUID): LostSet {
        var pick = Math.floorMod(uuid.leastSignificantBits xor uuid.mostSignificantBits, 1_000_003L) / 1_000_003.0 * LOST_SETS.sumOf { it.weight }
        for (s in LOST_SETS) { pick -= s.weight; if (pick < 0) return s }
        return LOST_SETS.last()
    }

    private fun lostGear(uuid: UUID): List<Piece> {
        val s = lostSet(uuid)
        return listOf(Piece(EquipmentSlot.HEAD, Items.PLAYER_HEAD, "${s.word} Dragon Helmet", head = s.helmet)) +
            leather("${s.word} Dragon", s.rgb, glint = true, feetRgb = s.feetRgb).drop(1) + hand(Items.DIAMOND_SWORD, "Aspect of the Dragons", glint = true)
    }

    /** Whether the mob of [archId] has recorded gear. */
    fun has(archId: String): Boolean = archId == "lost_adventurer" || archId in GEAR

    /** Puts [archId]'s recorded gear on [body] (the mob's [uuid] picks a Lost Adventurer's set; a Crypt Lurker holds a bone, or rotten flesh 28 percent of the time). */
    fun dress(body: LivingEntity, archId: String, uuid: UUID) {
        val pieces = when (archId) {
            "lost_adventurer" -> lostGear(uuid)
            "crypt_lurker" -> listOf(hand(if (Math.floorMod(uuid.leastSignificantBits, 100L) < 28) Items.ROTTEN_FLESH else Items.BONE))
            else -> GEAR[archId] ?: return
        }
        for (p in pieces) body.setItemSlot(p.slot, stack(p))
    }

    private fun stack(p: Piece): ItemStack {
        val s = if (p.head != null) SimItems.head(texValue(p.head), p.name ?: "Player Head") else ItemStack(p.item)
        if (p.head == null && p.name != null) s.set(DataComponents.CUSTOM_NAME, Component.literal(p.name).withStyle { it.withItalic(false) })
        p.dye?.let { s.set(DataComponents.DYED_COLOR, DyedItemColor(it)) }
        if (p.glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return s
    }

    private fun texValue(hash: String): String =
        Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$hash\"}}}".toByteArray(Charsets.UTF_8))
}
