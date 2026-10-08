package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import kotlin.random.Random
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractBannerBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin

/**
 * A typical F7 boss hotbar, working the way the items do on Hypixel:
 *
 * 1 Superboom TNT (Hyperion outside P3) · 2 ⚚ Bonzo's Staff · 3 Spirit Shortbow · 4 Dungeonbreaker
 * · 5 Pet Rod · 6 Infinileap · 7 Jerry-chine Gun · 8 Wither Cloak Sword · 9 SkyBlock Menu
 * (opens the sim menu); in the inventory Hyperion, an Aspect of the Void (etherwarp merged) and a
 * Terminator.
 *
 * Teleports follow Hypixel's measured rules: etherwarp is a voxel DDA
 * from the sneak eye over 61 blocks onto a block with room above (+0.5, +1.05, +0.5), blinks step
 * whole blocks (AOTV 12, Hyperion 10) checking every quarter; look kept, velocity zeroed.
 */
object SimItems {
    // ------------------------------------------------------------------ the items

    private fun item(base: Item, id: String, name: String, lore: List<String> = emptyList(), glint: Boolean = false, extra: (CompoundTag) -> Unit = {}): ItemStack {
        val s = ItemStack(base)
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { it.withItalic(false) })
        if (lore.isNotEmpty()) s.set(DataComponents.LORE, ItemLore(lore.map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        s.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE)
        val tag = CompoundTag()
        tag.putString("id", id)
        tag.putBoolean("p3sim", true)
        extra(tag)
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        return s
    }

    /** Hypixel's item text as recorded from the server; {UXXXX} stands for a private-use glyph, {T}/{SEC} for Terror's piece count and stack seconds. */
    object Lore {
        private val GLYPH = Regex("\\{U([0-9A-F]{4})\\}")
        fun u(t: String) = GLYPH.replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
        private fun lines(raw: String) = raw.trim('\n').split('\n').map { u(it) }
        /** The component-hiding list Hypixel sends on every item (the Dungeonbreaker's has 14). */
        val HIDDEN = listOf("minecraft:jukebox_playable", "minecraft:painting/variant", "minecraft:map_id", "minecraft:fireworks", "minecraft:attribute_modifiers", "minecraft:unbreakable",
            "minecraft:written_book_content", "minecraft:banner_patterns", "minecraft:trim", "minecraft:potion_contents", "minecraft:dyed_color", "minecraft:charged_projectiles")
        val HIDDEN_BREAKER = listOf("minecraft:painting/variant", "minecraft:fireworks", "minecraft:attribute_modifiers", "minecraft:enchantments", "minecraft:stored_enchantments", "minecraft:trim",
            "minecraft:charged_projectiles", "minecraft:jukebox_playable", "minecraft:map_id", "minecraft:unbreakable", "minecraft:written_book_content", "minecraft:banner_patterns", "minecraft:potion_contents", "minecraft:dyed_color")
        val SUPERBOOM_TNT_NAME = u("§9Superboom TNT")
        val SUPERBOOM_TNT = lines("""
§7Blows up cracked brick walls and
§7crypts, which are typically found in
§cDungeons §7and the §5Crystal Hollows§7.

§9§lRARE
""")
        val HYPERION_NAME = u("§dHeroic Hyperion §6✪✪✪✪✪")
        val HYPERION = lines("""
§7Gear Score: §d5000
§7Damage: §c+2,195.2 §e(+30)
§7Strength: §c+1,472 §e(+30) §9(+50)
§7Crit Damage: §9+448%
§7Attack Speed: §e+10.85% §9(+7%)
§7Ferocity: §c+46.5
§7Intelligence: §b+4,064 §9(+125) §d(+60)
§7Gemstones: §6[§b{UE003}§6] §6[§b⚔§6]

§d§lSwarm V, §9Bane of Arthropods VI, §9Champion X
§9Cleave V, §9Critical VI, §9Cubism V
§9Ender Slayer VI, §9Experience IV, §9Fire Aspect III
§9First Strike IV, §9Giant Killer VII, §9Impaling III
§9Lethality VI, §9Life Steal V, §9Looting IV
§9Luck VI, §9Prosecute VI, §9Scavenger V
§9Smite VII, §9Tabasco III, §9Thunderlord VI
§9Vampirism VI, §9Venomous V

§7Deals §c+50% §7damage to §8{UE085} Wither §7mobs.
§7Grants §c+1 §c{UE050} Damage §7and §a+2 §b{UE003}
§bIntelligence §7per §cCatacombs §7level.

§aScroll Abilities:
§b§l⦾ §6Ability: Wither Impact  §e§lRIGHT CLICK
§7Teleport §a10 blocks§7 ahead of you
§7dealing §c1,798,815.7 §7damage to nearby
§7enemies. Also reduces your damage
§7taken and grants an absorption
§7shield for §e5 seconds§7.
§8Mana Cost: §b270{UE003}

§fKills: §61,304,218

§d§la §d§lMYTHIC DUNGEON SWORD §d§la
""")
        val TERMINATOR_NAME = u("§dPrecise Terminator §6✪✪✪✪✪§c➎")
        val TERMINATOR = lines("""
§7Gear Score: §d4947
§7Damage: §c+2,195.2 §e(+30)
§7Strength: §c+761.6 §e(+30) §6[+5] §9(+34)
§7Crit Chance: §9+46.5% §9(+15%)
§7Crit Damage: §9+2,080% §9(+70%)
§7Attack Speed: §e+62%
§7Shot Cooldown: §a0.5s

§d§lSoul Eater V, §9Chance V, §9Cubism V
§9Flame II, §9Gravity VI, §9Impaling V
§9Infinite Quiver X, §9Overload V, §9Piercing I
§9Power VII, §9Snipe IV, §9Tabasco III
§9Toxophilite X

§7Shoots §b3 §7arrows at once.
§7Can damage endermen.

§cDivides your §9{UE02C} Crit Chance §cby 4!

§6Ability: Salvation  §e§lLEFT CLICK
§7Can be cast after landing §63 §7hits.
§7Shoot a beam, penetrating up to §e5
§7enemies.
§7The beam always crits.
§8Soulflow Cost: §31⸎

§dShortbow: Instantly shoots!

§fKills: §6157,045

§9Precise Bonus
§7Deal §a+10% §7extra damage when
§7arrows hit the head of a mob.

§8§l* §8Co-op Soulbound §8§l*
§d§la §d§lMYTHIC DUNGEON BOW §d§la
""")
        val WITHER_CLOAK_NAME = u("§6Odd Wither Cloak Sword")
        val WITHER_CLOAK = lines("""
§7Gear Score: §d4964
§7Defense: §a+1,475
§7Damage: §c+1,121
§7Strength: §c+796.5
§7Crit Chance: §9+26.25% §9(+25%)
§7Crit Damage: §9+177% §9(+30%)
§7Intelligence: §b-295 §9(-50)

§c§l⦾ §6Ability: Creeper Veil  §e§lRIGHT CLICK
§7Spawns a layered veil that negates
§7damage for §a10s§7, as follows:

§8➤ §7Lose a layer on hit, consuming
§430§4{UE028} Vitality §7and block up to §c5,850
§7damage.
§8➤ §7The damage blocked scales with
§7your §5Dungeon Stat Boost §7while in §cThe
§cCatacombs§7.
§8➤ §7When the damage exceeds a
§7layer's limit, another layer is
§7consumed, until all damage is
§7accounted for.
§8➤ §7When you run out of §4{UE028} Vitality§7,
§7consume all §4{UE028} Vitality §7and take slight
§7knockback.
§8➤ §7You cannot attack or regenerate
§4{UE028} Vitality §7while the veil is up.

§e§lRIGHT CLICK §7while active to
§7deactivate. Cooldown is halved on
§7deactivation.
§8Vitality Cost: §430{UE028}
§8Cooldown: §a10s

§cThis item can be reclaimed! Use
§e/reclaim §cto take back what's
§crightfully yours!
§6§la §6§lLEGENDARY DUNGEON SWORD §6§la
""")
        val ASPECT_OF_THE_VOID_NAME = u("§6Heroic Aspect of the Void")
        val ASPECT_OF_THE_VOID = lines("""
§7Damage: §c+120
§7Strength: §c+140 §9(+40)
§7Attack Speed: §e+5% §9(+5%)
§7Intelligence: §b+100 §9(+100)
§7Gemstones: §8[§7{UE003}§8]

§d§lUltimate Wise V
§7Reduces the ability mana cost of this
§7item by §a50%§7.

§b§l⦾ §6Ability: Instant Transmission  §e§lRIGHT CLICK
§7Teleport §a12 blocks§7 ahead of you and
§7gain §a+50 §f{UE022} Speed§7 for §a3 seconds§7.
§8Mana Cost: §b18{UE003}

§6Ability: Ether Transmission  §e§lSNEAK RIGHT CLICK
§7Teleport to your targeted block up
§7to §a61 blocks §7away.
§8Soulflow Cost: §31
§8Mana Cost: §b73{UE003}

§6§la §6§lLEGENDARY SWORD §6§la
""")
        val JERRY_STAFF_NAME = u("§6Heroic Jerry-chine Gun §6✪✪✪✪✪")
        val JERRY_STAFF = lines("""
§7Damage: §c+88
§7Strength: §c+40 §9(+40)
§7Attack Speed: §e+5% §9(+5%)
§7Intelligence: §b+320 §9(+100)

§d§lUltimate Wise V
§7Reduces the ability mana cost of this
§7item by §a50%§7.

§c§l⦾ §6Ability: Rapid-fire  §e§lRIGHT CLICK
§7Shoots a Jerry bullet, dealing
§c1,347.2 §7damage on impact and
§7knocking you back.

§7Each shot costs §3+14 mana §7more than
§7the previous, resetting after §a4s §7of
§7not firing.

§8§l* §8Co-op Soulbound §8§l*
§6§la §6§lLEGENDARY SWORD §6§la
""")
        val MOSQUITO_BOW_NAME = u("§6Mosquito Shortbow")
        val MOSQUITO_BOW = lines("""
§7Damage: §c+319
§7Strength: §c+151
§7Crit Damage: §9+39%
§7Vitality: §4+20
§7Shot Cooldown: §a0.5s

§d§lDuplex I
§7Shoot an extra arrow dealing §c4% §7of the
§7first arrow's damage.
§7Targets hit take §c1.1x §7fire damage
§7for §a60s§7.
§9Flame II
§7Arrows ignite your enemies for §a4s§7,
§7dealing §a6% §7of your damage per
§7second.

§6Ability: Eggsecute 
§7Arrows fired from this weapon deal
§c3x §7damage to §aEgg Sacs §7spawned by
§7the §cTarantula Broodfather§7!

§6Ability: Nasty Bite  §e§lLEFT CLICK
§7Shoot an enhanced shot, healing you
§7for §c189❤ Health §7on hit.
§8Vitality Cost: §410{UE028}

§6Shortbow: Instantly shoots!

§8This item can be reforged!
§6§lLEGENDARY BOW
""")
        val ENDER_PEARL_NAME = u("§fEnder Pearl")
        val ENDER_PEARL = lines("""
§8Collection Item

§f§lCOMMON
""")
        val SKYBLOCK_MENU_NAME = u("§aSkyBlock Menu §7(Click)")
        val SKYBLOCK_MENU = lines("""
§7View all of your SkyBlock progress,
§7including your Skills, Collections,
§7Recipes, and more!

§eClick to open!
""")
        val INFINITE_SPIRIT_LEAP_NAME = u("§5Infinileap")
        val INFINITE_SPIRIT_LEAP = lines("""
§c§l⦾ §6Ability: Spirit Leap  §e§lRIGHT CLICK
§7Allows you to teleport to any teammate! Grants
§a1 §7second of immunity after teleporting,
§7immunity is cancelled upon dealing damage.
§8Cooldown: §a2s

§cDungeons only!

§5§lEPIC DUNGEON ITEM
""")
        val FISHING_ROD_NAME = u("§fFishing Rod")
        val FISHING_ROD = lines("""
§7Damage: §c+15
§7Strength: §c+15
§7Fishing Speed: §b+5
§7Sea Creature Chance: §3+1%

§9ථ Hook §8§lNONE
§9ꨃ Line §8§lNONE
§9࿉ Sinker §8§lNONE

§7Talk to §2Roddy §7in the §2Backwater
§2Bayou §7to apply parts to this rod.

§8This item can be reforged!
§f§lCOMMON FISHING ROD
""")
        val STARRED_SPIRIT_MASK_NAME = u("§d{UE068} Necrotic Spirit Mask §6✪✪✪✪✪")
        val STARRED_SPIRIT_MASK = lines("""
§7Gear Score: §d2569
§7Health: §c+857.6
§7Defense: §a+480
§7Intelligence: §b+1,440 §9(+200)
§7Health Regen: §c+15.75
§7Speed: §f+160
§7Respiration: §3+384

§d§lWisdom V, §9Hecatomb VIII, §9Respiration IV
§9Respite V, §9Transylvanian V, §9Vampiric Vitality X

§6Ability: Second Wind 
§7Instead of dying, gain §f+50{UE022} Speed
§7and damage immunity for §a3 §7seconds.
§7Also heals you for §a10% §7of your §c❤
§cHealth §7over §a5 §7seconds.
§8Cooldown: §a30s

§d§la §d§lMYTHIC DUNGEON HELMET §d§la
""")
        val STARRED_BONZO_MASK_NAME = u("§5{UE068} Sunny Bonzo's Mask §6✪✪✪✪✪")
        val STARRED_BONZO_MASK = lines("""
§7Gear Score: §d2730
§7Health: §c+960
§7Defense: §a+640
§7Intelligence: §b+960
§7Health Regen: §c+15.75
§7Speed: §f+38.4 §9(+6)
§7Farming Wisdom: §3+6.2 §9(+4)

§d§lWisdom V, §9Hecatomb V, §9Respite V
§9Transylvanian V, §9Vampiric Vitality X

§6Ability: Clownin' Around 
§7Instead of dying, gain damage
§7immunity and §a+40 §c{UE00D} Strength §7for §a3s
§7and fully replenish your health. The
§7cooldown and Strength bonus you
§7receive improve based on your
§aDungeoneering Skill §7level. This ability
§7only works while in §cThe Catacombs§7!
§8Cooldown: §a180s

§5§la §5§lEPIC DUNGEON HELMET §5§la
""")
        val TERROR_CHESTPLATE_NAME = u("§6Spiked Terror Chestplate")
        val TERROR_CHESTPLATE = lines("""
§7Health: §c+238 §9(+8)
§7Defense: §a+73 §9(+8)
§7Strength: §c+10 §9(+10)
§7Crit Chance: §9+10% §9(+10%)
§7Crit Damage: §9+60% §9(+10%)
§7Attack Speed: §e+14% §9(+14%)
§7Intelligence: §b+15 §9(+10)
§7Speed: §f+13 §9(+1)
§7Gemstones: §8[§8⚔§8] §8[§8⚔§8]

§7Mana Regeneration II§7, §7Vitality II

§6Tiered Bonus: Hydra Strike ({T}/4)
§7Every §a0.2s§7, arrow attacks grant §c1§7
§7stack of §6⁑ Hydra Strike§7. §8Lose 1 stack
§8after {SEC}s of not gaining a stack.
§7
§7Each stack grants §c+{DMG}% Damage§7 and §b+1%
§bArrow Speed§7.
§7
§7At §c10§7 stacks shoot §a+2§7 arrows that deal §c20%
§cArrow Damage§8.

§6§lLEGENDARY CHESTPLATE
""")
        val TERROR_LEGGINGS_NAME = u("§6Spiked Terror Leggings")
        val TERROR_LEGGINGS = lines("""
§7Health: §c+213 §9(+8)
§7Defense: §a+63 §9(+8)
§7Strength: §c+10 §9(+10)
§7Crit Chance: §9+10% §9(+10%)
§7Crit Damage: §9+60% §9(+10%)
§7Attack Speed: §e+14% §9(+14%)
§7Intelligence: §b+15 §9(+10)
§7Speed: §f+13 §9(+1)
§7Gemstones: §8[§8⚔§8] §8[§8⚔§8]

§7Breeze II§7, §7Speed I

§6Tiered Bonus: Hydra Strike ({T}/4)
§7Every §a0.2s§7, arrow attacks grant §c1§7
§7stack of §6⁑ Hydra Strike§7. §8Lose 1 stack
§8after {SEC}s of not gaining a stack.
§7
§7Each stack grants §c+{DMG}% Damage§7 and §b+1%
§bArrow Speed§7.
§7
§7At §c10§7 stacks shoot §a+2§7 arrows that deal §c20%
§cArrow Damage§8.

§6§lLEGENDARY LEGGINGS
""")
        val TERROR_BOOTS_NAME = u("§6Spiked Terror Boots")
        val TERROR_BOOTS = lines("""
§7Health: §c+138 §9(+8)
§7Defense: §a+48 §9(+8)
§7Strength: §c+10 §9(+10)
§7Crit Chance: §9+10% §9(+10%)
§7Crit Damage: §9+60% §9(+10%)
§7Attack Speed: §e+14% §9(+14%)
§7Intelligence: §b+15 §9(+10)
§7Speed: §f+13 §9(+1)
§7Gemstones: §8[§8⚔§8] §8[§8⚔§8]

§7Blazing Resistance I§7, §7Experience I

§6Tiered Bonus: Hydra Strike ({T}/4)
§7Every §a0.2s§7, arrow attacks grant §c1§7
§7stack of §6⁑ Hydra Strike§7. §8Lose 1 stack
§8after {SEC}s of not gaining a stack.
§7
§7Each stack grants §c+{DMG}% Damage§7 and §b+1%
§bArrow Speed§7.
§7
§7At §c10§7 stacks shoot §a+2§7 arrows that deal §c20%
§cArrow Damage§8.

§6§lLEGENDARY BOOTS
""")
        val SPEED_WITHER_BOOTS_NAME = u("§dAncient Maxor's Boots §6✪✪✪✪✪§c➎")
        val SPEED_WITHER_BOOTS = lines("""
§7Gear Score: §d4935
§7Health: §c+2,188.8 §e(+60) §c[+40] §9(+7)
§7Defense: §a+812.8 §e(+30) §9(+7)
§7Strength: §c+428.8 §9(+35) §d(+32)
§7Crit Chance: §9+23.25% §9(+15%)
§7Crit Damage: §9+608% §9(+50%)
§7Intelligence: §b+224 §9(+25)
§7Health Regen: §c+15.75
§7Speed: §f+230.4
§7Gemstones: §6[§d⚔§6] §6[§d⚔§6]

§d§lLegion V, §9Depth Strider III, §9Feather Falling X
§9Growth VI, §9Protection VI, §9Respite V
§9Sugar Rush III, §9Vivacious Vitality VIII

§7Reduces the damage you take from
§7withers by §c10% §7and increases your
§7arrow damage by §c5%§7.

§6Full Set Bonus: Witherborn §7(0/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§9Ancient Bonus
§7Grants §a+1 §9{UE007} Crit Damage §7per
§cCatacombs §7level.

§d§la §d§lMYTHIC DUNGEON BOOTS §d§la
""")
        val SPEED_WITHER_LEGGINGS_NAME = u("§dNecrotic Maxor's Leggings §6✪✪✪✪✪")
        val SPEED_WITHER_LEGGINGS = lines("""
§7Gear Score: §d4015
§7Health: §c+1,472
§7Defense: §a+672
§7Crit Damage: §9+288%
§7Intelligence: §b+1,888 §9(+200) §d(+60)
§7Health Regen: §c+15.75
§7Speed: §f+192
§7Gemstones: §6[§b⚔§6] §6[§b⚔§6]

§d§lWisdom V, §9Respite V, §9Smarty Pants V
§9Vampiric Vitality V

§7Reduces the damage you take from
§7withers by §c10% §7and increases your
§7arrow damage by §c5%§7.

§6Full Set Bonus: Witherborn §7(0/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§d§la §d§lMYTHIC DUNGEON LEGGINGS §d§la
""")
        val MITHRIL_COAT_NAME = u("§6Necrotic Mithril Coat")
        val MITHRIL_COAT = lines("""
§7Defense: §a+125
§7Intelligence: §b+150 §9(+150)
§7Speed: §f+15

§6Ability: Mithril's Protection 
§7Any damage taken is max §a40% §7of the wearer's
§c❤ Health§7. Gain §cRegeneration §7when this ability
§7activates.

§6§la §6§lLEGENDARY CHESTPLATE §6§la
""")
        val WISE_WITHER_BOOTS_NAME = u("§dNecrotic Storm's Boots §6✪✪✪✪✪")
        val WISE_WITHER_BOOTS = lines("""
§7Gear Score: §d4175
§7Health: §c+1,408
§7Defense: §a+544
§7Intelligence: §b+3,264 §9(+200) §d(+60)
§7Health Regen: §c+15.75
§7Speed: §f+38.4
§7Gemstones: §6[§b{UE003}§6] §6[§b⚔§6]

§d§lWisdom V, §9Depth Strider III, §9Feather Falling X
§9Growth V, §9Protection V, §9Respite V
§9Sugar Rush III, §9Vampiric Vitality V

§7Reduces the damage you take from
§7withers by §c10%§7.

§6Full Set Bonus: Witherborn §7(3/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§d§la §d§lMYTHIC DUNGEON BOOTS §d§la
""")
        val WISE_WITHER_LEGGINGS_NAME = u("§dNecrotic Storm's Leggings §6✪✪✪✪✪")
        val WISE_WITHER_LEGGINGS = lines("""
§7Gear Score: §d5000
§7Health: §c+2,336 §e(+60)
§7Defense: §a+992 §e(+30)
§7Intelligence: §b+3,424 §9(+200) §d(+60)
§7Health Regen: §c+15.75
§7Gemstones: §6[§b{UE003}§6] §6[§b⚔§6]

§d§lWisdom V, §9Growth V, §9Protection V
§9Respite V, §9Smarty Pants V

§7Reduces the damage you take from
§7withers by §c10%§7.

§6Full Set Bonus: Witherborn §7(3/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§d§la §d§lMYTHIC DUNGEON LEGGINGS §d§la
""")
        val WISE_WITHER_CHESTPLATE_NAME = u("§dLoving Storm's Chestplate §6✪✪✪✪✪")
        val WISE_WITHER_CHESTPLATE = lines("""
§7Gear Score: §d5000
§7Health: §c+2,617.6 §e(+60) §9(+14)
§7Defense: §a+1,177.6 §e(+30) §9(+14)
§7True Defense: §f+15.5
§7Intelligence: §b+2,816 §9(+120) §d(+60)
§7Health Regen: §c+15.75
§7Gemstones: §6[§b{UE003}§6] §6[§b⚔§6]

§d§lWisdom V, §9Growth V, §9Protection V
§9Reflection V, §9Respite V, §9True Protection I

§7Reduces the damage you take from
§7withers by §c10%§7.

§6Full Set Bonus: Witherborn §7(3/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§9Loving Bonus
§7Increases ability damage by §a5%§7.

§d§la §d§lMYTHIC DUNGEON CHESTPLATE §d§la
""")
        val WISE_WITHER_HELMET_NAME = u("§dShiny Ancient Storm's Helmet §6✪✪✪✪✪§c➊")
        val WISE_WITHER_HELMET = lines("""
§7Gear Score: §d5000
§7Health: §c+1,932.8 §9(+7)
§7Defense: §a+684.8 §9(+7)
§7Strength: §c+224 §9(+35)
§7Crit Chance: §9+23.25% §9(+15%)
§7Crit Damage: §9+396.8% §9(+50%) §d(+12%)
§7Intelligence: §b+3,072 §9(+25) §d(+30)
§7Health Regen: §c+5.25
§7Gemstones: §6[§b{UE003}§6] §6[§8⚔§6]

§d§lLegion V, §9Big Brain V, §9Growth V
§9Hecatomb X, §9Protection V, §9Rejuvenate V
§9Strong Vitality V

§7Reduces the damage you take from
§7withers by §c10%§7.

§6Full Set Bonus: Witherborn §7(3/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§9Ancient Bonus
§7Grants §a+1 §9{UE007} Crit Damage §7per
§cCatacombs §7level.

§d§la §d§lSHINY MYTHIC DUNGEON HELMET §d§la
""")
        val RACING_HELMET_NAME = u("§dRenowned Racing Helmet")
        val RACING_HELMET = lines("""
§7Health: §c+47 §9(+10)
§7Defense: §a+10 §9(+10)
§7Strength: §c+12 §9(+12)
§7Crit Chance: §9+12% §9(+12%)
§7Crit Damage: §9+12% §9(+12%)
§7Attack Speed: §e+15% §9(+15%)
§7Intelligence: §b+12 §9(+12)
§7Speed: §f+400 §9(+1)

§d§lWisdom V
§7Gain §b5 §7Intelligence for every §b5
§7levels of exp you have on you.
§7Capped at §b100 §7Intelligence.
§9Hecatomb IX §894
§7Gain §a+0.92% §cCatacombs §7XP & §a+1.84% §3Class §7XP,
§7doubled §7on §b§lS+ §7runs.
§7Grants §c+7.4{UE010} §7per 10 §cCatacombs §7levels.
§8100 S runs to tier up!

§7Grants §f+100{UE022} Speed Cap§7.

§8When horses are not fast enough,
§8use a Racing Helmet instead.

§7Purchased by: §6[MVP§9++§6] Player§f
§7Purchased for: §64,545,454,545 Coins

§8Auction #18
§8Bid #2
§8September 2025

§9Renowned Bonus
§7Increases all §cCombat §7stats and §b{UE01A}
§bMagic Find §7by §a+1%§7.

§d§la §d§lMYTHIC HELMET §d§la
""")
    }

    /**
     * The components Hypixel adds to every stack: item model (when [model] is given, under hypixel_skyblock:item/),
     * tooltip style, the hidden-components list; and no unbreakable where Hypixel sends none.
     */
    fun hy(s: ItemStack, model: String?, style: String, unbreakable: Boolean = true, hidden: List<String> = Lore.HIDDEN): ItemStack {
        if (model != null) s.set(DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.parse("hypixel_skyblock:item/$model"))
        s.set(DataComponents.TOOLTIP_STYLE, net.minecraft.resources.Identifier.parse("hypixel_skyblock:$style"))
        val types = java.util.LinkedHashSet<net.minecraft.core.component.DataComponentType<*>>()
        for (h in hidden) net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(net.minecraft.resources.Identifier.parse(h))?.let { types.add(it) }
        s.set(DataComponents.TOOLTIP_DISPLAY, net.minecraft.world.item.component.TooltipDisplay(false, types))
        if (!unbreakable) s.remove(DataComponents.UNBREAKABLE)
        return s
    }

    /** A teammate's name colour by rank (on Hypixel about 63% green, 33% aqua, 4% gold); fixed per name. */
    fun rankColour(name: String): String { val h = Math.floorMod(name.hashCode(), 100); return if (h < 63) "§a" else if (h < 96) "§b" else "§6" }

    /** Etherwarp's witch puff at the departure point. */
    fun etherPuff(at: Vec3) = Sim.level.sendParticles(ParticleTypes.WITCH, at.x, at.y + 1.0, at.z, 25, 0.25, 1.0, 0.25, 0.0)

    /** A destroyed gate: about 15 explosion puffs, each with its own BLOCKS-source sound, at random gate-block corners. */
    fun gatePuffs(centre: Vec3, box: net.minecraft.world.phys.AABB) {
        // The blasts sit on random blocks across the whole gate (5-16 blocks from its centre, y115-134), not within 3 of the middle.
        val alongX = box.xsize >= box.zsize
        repeat(8 + Random.nextInt(8) + Random.nextInt(8)) {
            val a = if (alongX) Random.nextInt(box.minX.toInt() + 2, box.maxX.toInt() - 2) else Random.nextInt(box.minZ.toInt() + 2, box.maxZ.toInt() - 2)
            val b = Random.nextInt(-1, 2)
            val x = if (alongX) a.toDouble() else Math.floor(centre.x) + b
            val z = if (alongX) Math.floor(centre.z) + b else a.toDouble()
            val c = Vec3(x, Math.floor(centre.y - 3 + Random.nextInt(0, 19)), z)
            Sim.level.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 3, 1.0, 1.0, 1.0, 0.0)
            Sim.sound(SoundEvents.GENERIC_EXPLODE, 0.5f, 0.49206f, c, net.minecraft.sounds.SoundSource.BLOCKS)
        }
    }

    /** A ghost-kit item: [id] is its SkyBlock id, or null for none (the Ghost Arrow). */
    fun ghostStack(base: Item, id: String?, name: String, count: Int = 1): ItemStack {
        val s = if (id != null) item(base, id, name) else ItemStack(base).also { it.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { st -> st.withItalic(false) }) }
        s.remove(DataComponents.UNBREAKABLE)
        s.count = count
        return s
    }

    fun idOf(s: ItemStack): String? = s.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.takeIf { it.isNotEmpty() }

    private const val LEAP_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTY1MjE0NjYxMjc0MiwKICAicHJvZmlsZUlkIiA6ICI5ZWU3NTUxOGQyZWE0Y2Q4OGJiNGI1YTZkNmVhNTFjYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaWNyb3MxMTgyIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzM3N2Q0YTIwNmQ3NzU3ZjQ3OWYzMzJlYzFhMmJiYmVlNTdjZWY5NzU2OGRkODhkZjgxZjQ4NjRhZWU3ZDNkOTgiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ=="

    fun head(tex: String, name: String): ItemStack {
        val s = ItemStack(Items.PLAYER_HEAD)
        val profile = GameProfile(UUID.nameUUIDFromBytes(tex.toByteArray()), "p3sim", PropertyMap(ImmutableMultimap.of("textures", Property("textures", tex))))
        s.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { it.withItalic(false) })
        return s
    }

    // Superboom is paper with Hypixel's item model: no BlockItem, so the client never
    // "places" it (no place sound, swing or resend). Still infinite (never consumed) and needs a block target.
    val SUPERBOOM get() = item(Items.PAPER, "SUPERBOOM_TNT", Lore.SUPERBOOM_TNT_NAME, Lore.SUPERBOOM_TNT, glint = true).also { it.count = 64; hy(it, "uncategorized/superboom_tnt", "rare", unbreakable = false) }
    val HYPERION get() = item(Items.IRON_SWORD, "HYPERION", Lore.HYPERION_NAME, Lore.HYPERION, glint = true).also { hy(it, "uncategorized/hyperion", "mythic") }
    /** Bonzo's Staff as Hypixel sends it: glyph + "Heroic" name, epic tooltip, fragged model, glint. Lore stats are one real item's. */
    val BONZO get() = item(Items.BLAZE_ROD, "STARRED_BONZO_STAFF", "§5\uE068 Heroic Bonzo's Staff §6✪✪✪✪✪", listOf(
        "§7Gear Score: §d845 §8(1,213)", "§7Damage: §c+250 §8(+1,350)", "§7Strength: §c+185 §8(+550)", "§7Intelligence: §a+300 §8(+700)", "",
        "§9Ferocity: §a+10", "", "§7§8This item can be reforged!", "",
        "§6Ability: Showtime  §e§lRIGHT CLICK", "§7Shoots balloons that create a large explosion", "§7on impact, dealing up to §c16,956.3 §7damage.", "§8Mana Cost: §341", "",
        "§5§lEPIC DUNGEON SWORD"), glint = true).also {
        it.set(DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.parse("hypixel_skyblock:item/island_relevant/dungeons/bonzos_staff_fragged"))
        it.set(DataComponents.TOOLTIP_STYLE, net.minecraft.resources.Identifier.parse("hypixel_skyblock:epic"))
    }
    val SPIRIT_BOW get() = item(Items.BOW, "ITEM_SPIRIT_BOW", "§5Spirit Shortbow", listOf("§7Shortbow: instantly shoots!"), glint = true)
    val DUNGEONBREAKER get() = item(Items.DIAMOND_PICKAXE, "DUNGEONBREAKER", "§cDungeonbreaker", breakerLore(charges), glint = true).also {
        hy(it, null, "special", unbreakable = false, hidden = Lore.HIDDEN_BREAKER)
        // As main sends it: tool {default_mining_speed:1024, rules:[]} and Efficiency X + Unbreaking X, hidden.
        // The sim's mining never reads them: a breaker hit is DungeonbreakerSimMixin's (SimItems.clientHitBlock), not vanilla's.
        it.set(DataComponents.TOOL, net.minecraft.world.item.component.Tool(emptyList(), 1024f, 1, true))
        SimServer.level?.registryAccess()?.lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)?.orElse(null)?.let { reg ->
            val e = net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY)
            reg.get(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY).ifPresent { h -> e.set(h, 10) }
            reg.get(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING).ifPresent { h -> e.set(h, 10) }
            it.set(DataComponents.ENCHANTMENTS, e.toImmutable())
        }
    }

    /** The Dungeonbreaker's lore as Hypixel sends it; the "Charges" line changes with every charge. */
    private fun breakerLore(n: Int) = listOf("§7Speed: §f+20", "", "§6Ability: Dungeon Breaker §e§lDIG",
        "§7While in §cThe Catacombs§7, consume §e1§c\u2e15", "§7charge to break a block. §320§7 blocks can", "§7be broken at a time, and re-appear after", "§a10s§7. §e2§c\u2e15§7 charges are regenerated each", "§7second.", "",
        "§7Charges: §e$n§7/§e$MAX_CHARGES§c\u2e15", "", "§8§l* §8Co-op Soulbound §8§l*", "§c§lSPECIAL DUNGEON PICKAXE")

    /** Rewrites the lore of every Dungeonbreaker in the player's inventory (the item is sent again on each charge change). */
    private fun refreshBreakerLore() {
        val inv = Sim.player?.inventory ?: return
        val lore = ItemLore(breakerLore(charges).map { l -> Component.literal(l).withStyle { it.withItalic(false) } })
        for (i in 0 until inv.containerSize) { val st = inv.getItem(i); if (!st.isEmpty && idOf(st) == "DUNGEONBREAKER") st.set(DataComponents.LORE, lore) }
    }
    val PEARLS get() = item(Items.ENDER_PEARL, "ENDER_PEARL", Lore.ENDER_PEARL_NAME, Lore.ENDER_PEARL).also { it.count = 12; hy(it, null, "common", unbreakable = false) }
    val LEAP get() = head(LEAP_TEX, "§5Infinileap").also { s ->
        s.set(DataComponents.LORE, ItemLore(Lore.INFINITE_SPIRIT_LEAP.map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        hy(s, null, "epic", unbreakable = false)
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", "INFINITE_SPIRIT_LEAP"); it.putBoolean("p3sim", true) }))
    }
    val JERRY get() = item(Items.GOLDEN_HORSE_ARMOR, "JERRY_STAFF", Lore.JERRY_STAFF_NAME, Lore.JERRY_STAFF, glint = true).also { hy(it, "community_center/mayor/jerry/jerrychine_gun", "legendary", unbreakable = false) }
    val CLOAK get() = item(Items.STONE_SWORD, "WITHER_CLOAK", Lore.WITHER_CLOAK_NAME, Lore.WITHER_CLOAK).also { hy(it, "uncategorized/wither_cloak_sword", "legendary") }
    val MENU get() = item(Items.NETHER_STAR, "SKYBLOCK_MENU", Lore.SKYBLOCK_MENU_NAME, Lore.SKYBLOCK_MENU).also { hy(it, null, "common", unbreakable = false) }

    // ------------------------------------------------------------------ Haste / Mining Fatigue by held slot

    /** Whether the Dungeonbreaker was held at the last [miningEffects] (null: not applied yet). */
    private var breakerHeld: Boolean? = null

    /**
     * Main: Haste 0 and Mining Fatigue 255 (flags 3, no icon) while the held slot is not the Dungeonbreaker,
     * both removed while it is; toggled on a held-slot change. Night vision is Fight.setup's and stays. [force]: apply
     * whatever the last state was (a setup). Block protection doesn't lean on the fatigue: the server refuses every vanilla
     * break (Sim.guardBlocks) and a Dungeonbreaker hit never starts a vanilla break (DungeonbreakerSimMixin).
     */
    fun miningEffects(p: ServerPlayer, force: Boolean = false) {
        val held = idOf(p.mainHandItem) == "DUNGEONBREAKER"
        // The cache alone would miss a fresh ServerPlayer (a void respawn) that has neither effect.
        if (!force && held == breakerHeld && (held || p.hasEffect(net.minecraft.world.effect.MobEffects.HASTE))) return
        breakerHeld = held
        if (held) {
            p.removeEffect(net.minecraft.world.effect.MobEffects.HASTE)
            p.removeEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE)
        } else {
            p.addEffect(net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.HASTE, -1, 0, true, true, false))
            p.addEffect(net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MINING_FATIGUE, -1, 255, true, true, false))
        }
    }

    // ------------------------------------------------------------------ hotbar slot 9 (index 8)

    /** Arrows left in the quiver: starts at a typical recorded count (2,769), one less per shot ([quiverShot]). */
    private var quiverArrows = QUIVER_START
    private const val QUIVER_START = 2769

    /** A bow shot from the quiver: one arrow less, the preview follows on the next tick. */
    fun quiverShot() { if (quiverArrows > 0) quiverArrows-- }

    /** Main's quiver preview (slot 9 while a bow is held): a Flint Arrow feather, custom_data {quiver_arrow:"true"}. */
    private fun quiverPreview(): ItemStack {
        val s = ItemStack(Items.FEATHER)
        s.count = quiverArrows.coerceIn(1, 64)
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        s.set(DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.parse("hypixel_skyblock:item/uncategorized/flint_arrow"))
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Flint Arrow").withStyle { it.withItalic(false).withColor(net.minecraft.ChatFormatting.WHITE) })
        val lore = listOf("§7Damage: §c+1", "", "§8Stats added when shot!", "§f§lCOMMON ARROW", "§8§m                                ",
            "§7Arrows Remaining: §a" + "%,d".format(java.util.Locale.ROOT, quiverArrows), "", "§8This item is a preview of your", "§8currently selected arrow.")
        s.set(DataComponents.LORE, ItemLore(lore.map { l -> Sim.legacy(l).copy().withStyle { it.withItalic(false) } }))
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("quiver_arrow", "true") }))
        return s
    }

    /** Main's Magical Map (slot 9 while the Infinileap is held): map_id 1024, custom_data {id:"MAP",dontSaveToProfile:1,dontUpdateStack:1}. */
    private fun magicalMap(): ItemStack {
        val s = ItemStack(Items.FILLED_MAP)
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Magical Map").withStyle { it.withItalic(false).withColor(net.minecraft.ChatFormatting.AQUA) })
        s.set(DataComponents.LORE, ItemLore(listOf("Shows the layout of the Dungeon as", "it is explored and completed.").map { l -> Component.literal(l).withStyle { it.withItalic(false).withColor(net.minecraft.ChatFormatting.GRAY) } }))
        s.set(DataComponents.MAP_ID, net.minecraft.world.level.saveddata.maps.MapId(1024))
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", "MAP"); it.putInt("dontSaveToProfile", 1); it.putInt("dontUpdateStack", 1) }))
        return s
    }

    private fun isQuiverPreview(s: ItemStack) = s.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("quiver_arrow", "") == "true"
    private fun isMagicalMap(s: ItemStack) = idOf(s) == "MAP" && s.get(DataComponents.CUSTOM_DATA)?.copyTag()?.contains("dontUpdateStack") == true

    /** Whether [s] is one of slot 9's three faces (the SkyBlock Menu, the quiver preview, the Magical Map): HotbarLayout saves it as the menu. */
    fun isSlot9(s: ItemStack) = idOf(s) == "SKYBLOCK_MENU" || isQuiverPreview(s) || isMagicalMap(s)

    /**
     * Slot 9 as main shows it: the quiver preview while a bow is held, the Magical Map while the Infinileap is, the SkyBlock
     * Menu otherwise. Only when slot 9 holds one of them (a saved layout may have moved the menu), and never in the ghost kit.
     */
    private fun tickSlot9() {
        val p = Sim.player ?: return
        if (Masks.ghost) return
        val inv = p.inventory
        val cur = inv.getItem(8)
        if (!isSlot9(cur)) return
        val held = inv.getItem(inv.selectedSlot)
        val heldId = idOf(held)
        val want = when {
            heldId != null && held.item is net.minecraft.world.item.BowItem -> quiverPreview()
            heldId == "INFINITE_SPIRIT_LEAP" -> magicalMap()
            else -> if (idOf(cur) == "SKYBLOCK_MENU") return else MENU
        }
        if (ItemStack.isSameItemSameComponents(cur, want) && cur.count == want.count) return
        inv.setItem(8, want)
    }
    val AOTV get() = item(Items.DIAMOND_SHOVEL, "ASPECT_OF_THE_VOID", Lore.ASPECT_OF_THE_VOID_NAME, Lore.ASPECT_OF_THE_VOID, glint = true) { it.putInt("ethermerge", 1); it.putInt("tuned_transmission", 4) }.also { hy(it, "slayer/enderman/aspect_of_the_void", "legendary") }
    val PET_ROD get() = item(Items.FISHING_ROD, "PET_ROD", Lore.FISHING_ROD_NAME, Lore.FISHING_ROD).also { hy(it, null, "common") }
    val TERMINATOR get() = item(Items.BOW, "TERMINATOR", Lore.TERMINATOR_NAME, Lore.TERMINATOR, glint = true).also { hy(it, "slayer/enderman/weapons/terminator", "mythic") }
    val MOSQUITO get() = item(Items.BOW, "MOSQUITO_BOW", Lore.MOSQUITO_BOW_NAME, Lore.MOSQUITO_BOW, glint = true).also { hy(it, "slayer/spider/weapons/mosquito_shortbow", "legendary") }

    /** A vanilla bow (draw it, release it) with Duplex; Hypixel's quiver means it needs no arrows ([quiverArrow]). */
    val LAST_BREATH get() = item(Items.BOW, Bows.LAST_BREATH, "§6Last Breath", listOf("§9Duplex I", "§7Shoot an extra arrow dealing §a4%§7 of the",
        "§7first arrow's damage.", "", "§7Draw and release, like a vanilla bow.", "", "§6§lLEGENDARY BOW"), glint = true)

    /** Common F7 armour sets: Maxor + Mithril at P3 start, Terror, and Wise Wither (the usual mid-P3 swap). */
    enum class ArmorSet { MAXOR, TERROR, WISE }

    private const val RACING_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTY1NTg2ODcxMjQwMCwKICAicHJvZmlsZUlkIiA6ICJmZTYxY2RiMjUyMTA0ODYzYTljY2E2ODAwZDRiMzgzZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJNeVNoYWRvd3MiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMmNlMDc0NmIxMmVlNDA1Mzk1OGUxNDBiYTI5NTkzMjcyYmQ4NGNhMzRiYWY1MGQwZDgwYjViYzNjNjE1ZTljNiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"
    private const val WISE_HELM_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTYwNTYyMzMzMzU2MSwKICAicHJvZmlsZUlkIiA6ICJjZGM5MzQ0NDAzODM0ZDdkYmRmOWUyMmVjZmM5MzBiZiIsCiAgInByb2ZpbGVOYW1lIiA6ICJSYXdMb2JzdGVycyIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS85Y2E2YWM4Mzk2YmEyZmE2NGIwZjI3MTFkY2EyMDIzMmM3YTUyOTEyNmI5NmRiNmVmYWE4ZDdmMmUxODQwZDEiCiAgICB9CiAgfQp9"

    /** One armour piece as Hypixel sends it: recorded name, lore and dye, tooltip style. [rgb] null = undyed. */
    private fun piece(base: Item, id: String, name: String, lore: List<String>, rgb: Int?, style: String): ItemStack =
        item(base, id, name, lore).also { s ->
            if (rgb != null) s.set(DataComponents.DYED_COLOR, net.minecraft.world.item.component.DyedItemColor(rgb))
            hy(s, null, style)
        }

    private fun terror(base: Item, id: String, rgb: Int): ItemStack {
        val n = 3 + (if (terrorHelmet) 1 else 0)  // worn pieces at equip time: chest+legs+boots, plus a Terror Helmet on the head (4/4); a mask head is 3/4
        val sec = when { n >= 4 -> 10; n == 3 -> 7; else -> 4 }
        val dmg = when { n >= 4 -> 6; n == 3 -> 4; else -> 2 }
        val lore = (when (id) { "TERROR_CHESTPLATE" -> Lore.TERROR_CHESTPLATE; "TERROR_LEGGINGS" -> Lore.TERROR_LEGGINGS; else -> Lore.TERROR_BOOTS })
            .map { it.replace("{T}", n.toString()).replace("{SEC}", sec.toString()).replace("{DMG}", dmg.toString()) }
        val name = when (id) { "TERROR_CHESTPLATE" -> Lore.TERROR_CHESTPLATE_NAME; "TERROR_LEGGINGS" -> Lore.TERROR_LEGGINGS_NAME; else -> Lore.TERROR_BOOTS_NAME }
        return piece(base, id, name, lore, rgb, "legendary")
    }

    /** Chest, legs and boots of [set]; the helmet slot stays the masks' ([Masks.equip]) unless [equipHelmet]. */
    fun equipArmor(p: ServerPlayer, set: ArmorSet? = null, terrorHelm: Boolean? = null) {
        terrorHelmet = terrorHelm ?: (idOf(p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)) == "TERROR_HELMET")
        val (chest, legs, feet) = armorStacks(set)
        wear(p, net.minecraft.world.entity.EquipmentSlot.CHEST, chest)
        wear(p, net.minecraft.world.entity.EquipmentSlot.LEGS, legs)
        wear(p, net.minecraft.world.entity.EquipmentSlot.FEET, feet)
        terrorHelmet = false
    }

    /** Whether the Terror Helmet is (about to be) worn with the pieces [equipArmor] is building: Hydra Strike's lore shows 4/4. */
    private var terrorHelmet = false

    /** One armour piece (chest / legs / boots) by its SkyBlock id, from any of the [ArmorSet]s; null for an id the sim doesn't know. */
    fun armorPiece(id: String): ItemStack? =
        ArmorSet.entries.firstNotNullOfOrNull { set -> armorStacks(set).toList().firstOrNull { idOf(it) == id } }

    private fun armorStacks(set: ArmorSet?): Triple<ItemStack, ItemStack, ItemStack> {
        val which = set ?: ArmorSet.MAXOR
        val chest: ItemStack; val legs: ItemStack; val feet: ItemStack
        when (which) {
            ArmorSet.TERROR -> {
                chest = terror(Items.LEATHER_CHESTPLATE, "TERROR_CHESTPLATE", 4064687)
                legs = terror(Items.LEATHER_LEGGINGS, "TERROR_LEGGINGS", 6104017)
                feet = terror(Items.LEATHER_BOOTS, "TERROR_BOOTS", 8144108)
            }
            ArmorSet.MAXOR -> {
                chest = piece(Items.CHAINMAIL_CHESTPLATE, "MITHRIL_COAT", Lore.MITHRIL_COAT_NAME, Lore.MITHRIL_COAT, null, "legendary")
                legs = piece(Items.LEATHER_LEGGINGS, "SPEED_WITHER_LEGGINGS", Lore.SPEED_WITHER_LEGGINGS_NAME, Lore.SPEED_WITHER_LEGGINGS, 6107065, "mythic")
                feet = piece(Items.LEATHER_BOOTS, "SPEED_WITHER_BOOTS", Lore.SPEED_WITHER_BOOTS_NAME, Lore.SPEED_WITHER_BOOTS, 9005512, "mythic")
            }
            ArmorSet.WISE -> {
                chest = piece(Items.LEATHER_CHESTPLATE, "WISE_WITHER_CHESTPLATE", Lore.WISE_WITHER_CHESTPLATE_NAME, Lore.WISE_WITHER_CHESTPLATE, 1545156, "mythic")
                legs = piece(Items.LEATHER_LEGGINGS, "WISE_WITHER_LEGGINGS", Lore.WISE_WITHER_LEGGINGS_NAME, Lore.WISE_WITHER_LEGGINGS, 1550532, "mythic")
                feet = piece(Items.LEATHER_BOOTS, "WISE_WITHER_BOOTS", Lore.WISE_WITHER_BOOTS_NAME, Lore.WISE_WITHER_BOOTS, 1889508, "mythic")
            }
        }
        return Triple(chest, legs, feet)
    }

    /** The two non-mask helmets: the Racing Helmet and the Wise Wither (Storm's) helmet. Masks come back with [Masks.equip]. */
    fun equipHelmet(p: ServerPlayer, wise: Boolean) {
        val h = if (wise) head(WISE_HELM_TEX, Lore.WISE_WITHER_HELMET_NAME) else head(RACING_TEX, Lore.RACING_HELMET_NAME)
        h.set(DataComponents.LORE, ItemLore((if (wise) Lore.WISE_WITHER_HELMET else Lore.RACING_HELMET).map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        h.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        h.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", if (wise) "WISE_WITHER_HELMET" else "RACING_HELMET"); it.putBoolean("p3sim", true) }))
        hy(h, null, "mythic")
        wear(p, net.minecraft.world.entity.EquipmentSlot.HEAD, h)
    }

    /**
     * Puts [s] in [p]'s armour [slot] straight through the inventory, without LivingEntity.onEquipItem: Hypixel's
     * loadout and armour equips play no item.armor.equip_* sound (the client still gets the slot on the next sync).
     */
    fun wear(p: ServerPlayer, slot: net.minecraft.world.entity.EquipmentSlot, s: ItemStack) = p.inventory.setItem(slot.getIndex(36), s)

    /**
     * The boss hotbar (P3's, or P1/P2's with a Hyperion in slot 1), and the extras in the inventory -
     * laid out as your saved layout for that part has them ([HotbarLayout]), else as below.
     */
    fun giveHotbar(p: ServerPlayer, p3: Boolean = true) {
        val inv = p.inventory
        inv.clearContent()
        val bar = listOf(if (p3) SUPERBOOM else HYPERION, BONZO, TERMINATOR, DUNGEONBREAKER, PET_ROD, LEAP, JERRY, CLOAK, MENU)
        // 9: the spare mask (Masks.equip, Real Masks).
        val extras = listOf(10 to (if (p3) HYPERION else SUPERBOOM), 11 to AOTV, 12 to SPIRIT_BOW, 13 to PEARLS, 14 to MOSQUITO, 15 to LAST_BREATH)
        val (items, held) = HotbarLayout.arrange(bar.mapIndexed { i, s -> i to s } + extras, p3)
        items.forEach { (slot, s) -> inv.setItem(slot, s) }
        Loadouts.applySaved(p)  // your saved gear and pet, else the defaults: Maxor + mask (+ Black Cat)
        inv.selectedSlot = held
        p.connection.send(net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(held))
        p.containerMenu.broadcastChanges()
        p.inventoryMenu.broadcastChanges()
    }

    // ------------------------------------------------------------------ hooks

    private fun simServer(level: Level) = !level.isClientSide && level is ServerLevel && level.server === SimServer.server && SimServer.server != null
    private fun simClient(level: Level) = level.isClientSide && P3Sim.inSim

    fun register() {
        UseItemCallback.EVENT.register { player, level, hand ->
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            val id = idOf(stack) ?: return@register InteractionResult.PASS
            if (simClient(level)) {
                // The menu opens here (client side); everything else is the server's.
                if (id == "SKYBLOCK_MENU") { mc.execute { mc.gui.setScreen(SimScreen()) }; return@register InteractionResult.FAIL }
                return@register InteractionResult.PASS
            }
            if (!simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            // Hypixel's click model: a block-aimed click fired from use_item_on; the client's own MAIN-hand use_item
            // for the same item, in the same or the next server tick, is that click's follow-up and does nothing.
            blockFired?.let { (bid, t) -> if (bid == id && Fight.serverTick == t) { blockFired = null; return@register InteractionResult.SUCCESS } }
            var result: InteractionResult = InteractionResult.PASS
            EngineerClient.safely("p3sim use $id") { result = use(player, id) }
            result
        }
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            val id = idOf(player.getItemInHand(hand))
            if (simClient(level)) {
                if (id == "SKYBLOCK_MENU") { mc.execute { mc.gui.setScreen(SimScreen()) }; return@register InteractionResult.FAIL }
                return@register InteractionResult.PASS
            }
            if (!simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            var result: InteractionResult = InteractionResult.PASS
            EngineerClient.safely("p3sim use block") { result = useBlock(player, hit.blockPos, id, hit.direction) }
            // The client already placed what it held (an Infinileap is a head) and took it off its hotbar: as on
            // Hypixel, the server sends the held stack back (vanilla re-sends the blocks itself). Only a block item can have
            // been placed: the staffs' resend is their own (Bonzo ~77%).
            if (player.mainHandItem.item is net.minecraft.world.item.BlockItem) player.connection.send(net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket(player.inventory.selectedSlot, player.mainHandItem.copy()))
            result
        }
        UseEntityCallback.EVENT.register { player, level, hand, entity, _ ->
            if (hand != InteractionHand.MAIN_HAND || !simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            var result: InteractionResult = InteractionResult.PASS
            EngineerClient.safely("p3sim use entity") { result = useEntity(player, entity) }
            result
        }
        AttackEntityCallback.EVENT.register { player, level, _, entity, _ ->
            if (simClient(level)) return@register if (entity is ItemFrame) InteractionResult.FAIL else InteractionResult.PASS
            if (!simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            var result: InteractionResult = InteractionResult.PASS
            EngineerClient.safely("p3sim hit entity") { result = useEntity(player, entity, left = true) }
            result
        }
    }

    /** The block the held left click last hit (null once the button is up): [clientHitBlock] handles it once a press. */
    private var hitHeld: BlockPos? = null

    /** Client tick: a released attack key ends the press. */
    @JvmStatic
    fun clientTick() { if (!mc.options.keyAttack.isDown) hitHeld = null }

    /**
     * A block hit on the client (DungeonbreakerSimMixin: adventure mode drops it before Fabric's
     * callback). In the sim with the Dungeonbreaker: mined on the sim's server; true = handled.
     */
    @JvmStatic
    fun clientHitBlock(pos: BlockPos, face: net.minecraft.core.Direction): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        if (!simClient(level)) return false
        val at = pos.immutable()
        // The client hits a block twice in the press tick (startAttack, then continueAttack restarting the cancelled
        // break) and again every tick the button is held; main sees one start_destroy per press. Once per press, per block.
        val again = at == hitHeld
        hitHeld = at
        // A left click on a lever credits it, toggling nothing.
        // With the Dungeonbreaker the click also reaches the mining refusal ("digging there" in S4's device).
        (Fight.phase as? GoldorPhase)?.let { ph -> if (ph.leverAt(at) != null || ph.devices.lights.isLever(at)) { if (!again) SimServer.run("lever left") { ph.devices.leftClick(at) }; if (idOf(player.mainHandItem) != "DUNGEONBREAKER") return true } }
        // Superboom: a left click on a gate blows it too.
        if (idOf(player.mainHandItem) == "SUPERBOOM_TNT") {
            if (again) return true
            SimServer.run("superboom") { Sim.player?.let { p -> Fight.afterPing("superboom") { Fight.later(1, "superboom") { superboom(p, at, face) } } } }
            return true
        }
        if (idOf(player.mainHandItem) != "DUNGEONBREAKER") return false
        val state = level.getBlockState(at)
        if (state.isAir) return true
        // The client breaks it at once (as Hypixel's do); the server keeps it or sends it back.
        if (state.getDestroySpeed(level, at) >= 0) level.destroyBlock(at, false)
        SimServer.run("dungeonbreaker") { Sim.player?.let { p -> Fight.afterPing("dungeonbreaker") { mine(p, at) } } }
        return true
    }

    /**
     * A drawn bow's arrows, in the sim (QuiverSimMixin on Player.getProjectile, client and server): Hypixel draws from your
     * quiver, so Last Breath draws with no arrows in your inventory. Null everywhere else.
     */
    @JvmStatic
    fun quiverArrow(p: Player, weapon: ItemStack): ItemStack? {
        if (idOf(weapon) != Bows.LAST_BREATH) return null
        val level = p.level()
        val sim = if (level.isClientSide) P3Sim.inSim else SimServer.isSimLevel(level)
        return if (sim) ItemStack(Items.ARROW) else null
    }

    /**
     * Last Breath released, on the sim's server (LastBreathSimMixin on BowItem.releaseUsing): vanilla's power for the time
     * drawn, then Bows fires Hypixel's arrows (Duplex, Terror) instead of vanilla's one. Null: not ours, vanilla goes on.
     */
    @JvmStatic
    fun releaseBow(stack: ItemStack, level: Level, entity: net.minecraft.world.entity.LivingEntity, remainingTime: Int): Boolean? {
        if (idOf(stack) != Bows.LAST_BREATH || !simServer(level) || entity !is ServerPlayer) return null
        val power = net.minecraft.world.item.BowItem.getPowerForTime(stack.getUseDuration(entity) - remainingTime)
        if (power < 0.1f) return false
        Bows.release(entity, Bows.LAST_BREATH, power)
        return true
    }

    /** A left click on the client (ShortbowSimMixin): the shortbows shoot on it too, as on Hypixel (the Mosquito's is Nasty Bite). */
    @JvmStatic
    fun clientLeftClick() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (!simClient(level)) return
        val id = idOf(player.mainHandItem)?.takeIf { it in Bows.SHORTBOWS } ?: return
        SimServer.run("left click") { Sim.player?.let { p -> Bows.click(p, id, left = true) } }
    }

    /**
     * The drop key in the sim (ArcherDropSimMixin): never drops an item; as Archer, Ctrl+Q (the whole stack) is
     * Explosive Shot ([volley]). Plain Q is the ultimate (Rapid Fire), not in the sim: nothing.
     * True: the drop is cancelled.
     */
    @JvmStatic
    fun clientDrop(fullStack: Boolean): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        if (!simClient(level)) return false
        if (fullStack && P3Sim.myClass == com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.ARCHER)
            SimServer.run("archer ability") { Sim.player?.let { p -> asClicked(p, "archer ability") { volley(p) } } }
        return true
    }

    /**
     * Runs [run] after the ping, aimed where [p] looked when they clicked (as Hypixel gets it from the click's packets),
     * with [clickPos] = where they stood then.
     */
    private fun asClicked(p: ServerPlayer, what: String, prior: Boolean = false, run: () -> Unit) {
        // [prior]: the rotation of the last movement packet, not the use_item's own (Jerry-chine): what the
        // player had at the head of handleUseItem ([Fight.lastRot]). A block click (use_item_on carries no rotation)
        // already has it as the player's own, so it never passes [prior].
        val xRot = if (prior) Fight.lastRot.first else p.xRot
        val yRot = if (prior) Fight.lastRot.second else p.yRot
        val pos = p.position()
        Fight.afterPing(what) {
            if (p.isRemoved || Sim.player !== p) return@afterPing
            val nowX = p.xRot; val nowY = p.yRot
            p.xRot = xRot; p.yRot = yRot
            clickPos = pos
            try { run() } finally { p.xRot = nowX; p.yRot = nowY; clickPos = null }
        }
    }

    /** While an [asClicked] action runs: the player's position at the click (the last move packet's). */
    private var clickPos: Vec3? = null

    /** The cloak and the arrows: nothing carries over from an earlier sim server. */
    fun reset() { rapidLast = -1000; resetBreaker(); cloakUntil = 0; cloakReady = 0; vitality = 0; discardVeil(); lastHype = -100; lastCure = -1000; bonzoLast = -100; jerryTick = -1; leapReady = 0; volleyReady = 0; arrows.clear(); lastMotion.clear(); lastPos.clear(); pendingMotion.clear(); blockFired = null; quiverArrows = QUIVER_START; breakerHeld = null; Bows.reset()
        if (liveRockets.isNotEmpty()) { Sim.player?.connection?.send(net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(it.unimi.dsi.fastutil.ints.IntArrayList(liveRockets))); liveRockets.clear() }
    }

    /** A right click with [id] in the air, or on a block that isn't the sim's ([fromBlock]: from use_item_on). */
    private fun use(p: ServerPlayer, id: String, fromBlock: Boolean = false): InteractionResult {
        (Fight.phase as? P1Maxor)?.let { if (it.usePylon(p.position())) return InteractionResult.SUCCESS }
        if (id == "HYPERION") (Fight.phase as? P2Storm)?.beam()
        when (id) {
            "ASPECT_OF_THE_VOID" -> { val sneak = p.isShiftKeyDown; asClicked(p, "aotv") { if (sneak) etherwarp(p) else blink(p, 12) } }
            "HYPERION" -> asClicked(p, "hype") { if (hypeReady()) { /* No Hyperion teleport anywhere in the boss; the AOTV keeps its blink as the sim's movement item. */ implode(p) } }
            // The boss room refuses a pearl: one off the stack, red line, no entity.
            "ENDER_PEARL" -> { p.mainHandItem.shrink(1); Sim.chat("§cA mystical force in this room prevents you from doing that!") }
            "STARRED_BONZO_STAFF" -> asClicked(p, "bonzo") { bonzo(p) }
            "JERRY_STAFF" -> asClicked(p, "jerry", prior = !fromBlock) { jerry(p) }
            "WITHER_CLOAK" -> asClicked(p, "cloak") { cloak(p) }
            "INFINITE_SPIRIT_LEAP" -> openLeap(p)
            "HAUNT_ABILITY" -> openHaunt(p)
            // A right click in the air does nothing: Hypixel needs a block target.
            "SUPERBOOM_TNT" -> {}
            in Bows.SHORTBOWS -> Bows.click(p, id, left = false)
            // Main: the cast lands one tick after the use_item, stand, splash, abilities, Autopet line and bobber together.
            "PET_ROD" -> Fight.afterPing("pet rod") { Fight.later(1, "pet rod cast") { if (!p.isRemoved && Sim.player === p) castRod(p) } }
            else -> return InteractionResult.PASS
        }
        // Keep the client's copy of the stack (the Infinileap is a head, a block item it may think it placed).
        p.containerMenu.broadcastChanges()
        return InteractionResult.SUCCESS
    }

    /** Vanilla's lever toggle (state flips, click sound, pitch by the new state); no chat, no credit. */
    fun vanillaLeverToggle(pos: BlockPos) {
        val st = Blocks.get(pos)?.takeIf { it.hasProperty(LeverBlock.POWERED) } ?: Sim.level.getBlockState(pos).takeIf { it.hasProperty(LeverBlock.POWERED) } ?: return
        val on = !st.getValue(LeverBlock.POWERED)
        Blocks.set(pos, st.setValue(LeverBlock.POWERED, on))
        Sim.sound(SoundEvents.LEVER_CLICK, 0.3f, if (on) 0.5873016f else 0.4920635f, Vec3.atCenterOf(pos), net.minecraft.sounds.SoundSource.BLOCKS)
    }

    private fun useBlock(p: ServerPlayer, pos: BlockPos, id: String?, hitFace: net.minecraft.core.Direction): InteractionResult {
        val phase = Fight.phase
        if (phase is P1Maxor && phase.usePylon(Vec3.atCenterOf(pos))) return InteractionResult.SUCCESS
        if (phase is GoldorPhase) {
            phase.leverAt(pos)?.let { st -> Fight.afterPing("lever") { phase.pullLever(st, Sim.me) }; return InteractionResult.SUCCESS }
            if (phase.devices.use(pos)) return InteractionResult.SUCCESS
            if (id == "SUPERBOOM_TNT") {
                Fight.afterPing("superboom") { Fight.later(1, "superboom") { superboom(p, pos, hitFace) } }
                // Paper (no BlockItem): the client follows this use_item_on with a use_item, swallowed like the staffs'.
                blockFired = id to Fight.serverTick
                return InteractionResult.SUCCESS
            }
        }
        val state = Sim.level.getBlockState(pos)
        // Anything else interactable (a stray lever or button) stays as built.
        // A lever before its section is vanilla's toggle with the click sound, no chat.
        if (state.block is LeverBlock) { Fight.afterPing("lever") { vanillaLeverToggle(pos) }; return InteractionResult.SUCCESS }
        if (state.block is ButtonBlock) return InteractionResult.SUCCESS
        if (id == null) return InteractionResult.SUCCESS
        // Every item fires here, from use_item_on (Hypixel's model): the client sends no use_item at all after a click
        // its own useItemOn took (a dispenser, hopper, anvil, beacon or sign opens/answers on the client; a sneaking
        // AOTV makes a path on dirt), so firing on use_item alone would fire nothing there. The client's follow-up
        // use_item, when it sends one, is swallowed by [blockFired] (one Jerry shot per block click, not 2).
        // The block items (Infinileap/Haunt heads, Superboom) are placed by the client, so there's no follow-up.
        val r = use(p, id, fromBlock = true)
        if (r != InteractionResult.PASS && p.mainHandItem.item !is net.minecraft.world.item.BlockItem) blockFired = id to Fight.serverTick
        // Its SUCCESS keeps vanilla's block use off (the world stays as built); the client's flow doesn't read it.
        return r
    }

    /** The item a block click just fired and its server tick: the client's use_item for it in that tick or the next is skipped. */
    private var blockFired: Pair<String, Int>? = null

    /** The server tick of each terminal's last accepted click (same-tick dedupe). */
    private val termClickTick = java.util.WeakHashMap<Station, Int>()

    private fun useEntity(p: ServerPlayer, e: net.minecraft.world.entity.Entity, left: Boolean = false): InteractionResult {
        if (e is net.minecraft.world.entity.boss.enderdragon.EndCrystal) { (Fight.phase as? P1Maxor)?.useCrystal(e); return InteractionResult.SUCCESS }
        // P1's crystal and pylon stands ("CLICK HERE"): pick up / place.
        if (e is ArmorStand && (Fight.phase as? P1Maxor)?.useCrystal(e) == true) return InteractionResult.SUCCESS
        if (left && idOf(p.mainHandItem) == "HYPERION") (Fight.phase as? P2Storm)?.beam()
        val phase = Fight.phase as? GoldorPhase
        if (phase != null) {
            if (e is ItemFrame && phase.devices.arrows.owns(e)) { if (!left) phase.devices.arrows.use(e); return InteractionResult.SUCCESS }
            if (e is ArmorStand) {
                val st = phase.stations.firstOrNull { it.owns(e) }
                if (st != null && st.kind == Station.Kind.TERMINAL) {
                    // One response per terminal per server tick: attack+interact or repeated interacts in the same tick (as on Hypixel).
                    if (termClickTick[st] != Fight.serverTick) {
                        termClickTick[st] = Fight.serverTick
                        Fight.afterPing("terminal") { phase.useTerminal(st) }
                    }
                    return InteractionResult.SUCCESS
                }
                return InteractionResult.SUCCESS
            }
        }
        if (e is ItemFrame || e is ArmorStand) return InteractionResult.SUCCESS
        if (e.entityTags().contains(Sim.TAG)) return InteractionResult.SUCCESS
        return InteractionResult.PASS
    }

    // ------------------------------------------------------------------ teleports

    private fun look(p: Player): Vec3 {
        val yaw = Math.toRadians(p.yRot.toDouble()); val pitch = Math.toRadians(p.xRot.toDouble())
        return Vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
    }

    private fun state(x: Int, y: Int, z: Int): BlockState = Sim.level.getBlockState(BlockPos(x, y, z))

    private fun collides(s: BlockState, pos: BlockPos) = !s.getCollisionShape(Sim.level, pos, CollisionContext.empty()).isEmpty

    /** The top of a cell's collision under its centre (0 = none). */
    private fun top(x: Int, y: Int, z: Int): Double {
        val pos = BlockPos(x, y, z)
        val shape = state(x, y, z).getCollisionShape(Sim.level, pos, CollisionContext.empty())
        if (shape.isEmpty) return 0.0
        var best = 0.0
        for (b in shape.toAabbs()) if (b.minX <= 0.5 && b.maxX >= 0.5 && b.minZ <= 0.5 && b.maxZ >= 0.5) best = maxOf(best, b.maxY)
        return if (best > 0) best else shape.max(net.minecraft.core.Direction.Axis.Y)
    }

    private fun passThrough(s: BlockState) = s.block is SkullBlock || s.block is WallSkullBlock || s.block is LadderBlock || s.block is FlowerPotBlock || s.block is ButtonBlock || s.block is LeverBlock
    private fun stopsAnyway(s: BlockState) = s.block is SignBlock || s.block is AbstractBannerBlock || s.block is TripWireHookBlock

    /** Etherwarp's ray stops at this cell. */
    private fun rayStops(x: Int, y: Int, z: Int): Boolean {
        val s = state(x, y, z)
        if (passThrough(s)) return false
        if (stopsAnyway(s)) return true
        return collides(s, BlockPos(x, y, z))
    }

    /** A body fits in this cell. */
    private fun bodyPasses(x: Int, y: Int, z: Int): Boolean {
        val s = state(x, y, z)
        if (stopsAnyway(s)) return true
        if (s.block is SkullBlock || s.block is WallSkullBlock || s.block is LadderBlock || s.block is FlowerPotBlock) return false
        return !collides(s, BlockPos(x, y, z))
    }

    fun etherwarp(p: ServerPlayer, range: Double = 61.0) {
        // Cast from where the server saw you (about one one-way latency ago).
        val seen = Fight.seenPos(p)
        val eye = Vec3(seen.x, seen.y + 1.27, seen.z)
        val dir = look(p)
        val hit = dda(eye, dir, range)
        if (hit == null) { return }
        val (x, y, z) = hit
        val s = state(x, y, z)
        val above = state(x, y + 1, z)
        val bad = stopsAnyway(s) || s.block is SkullBlock || s.block is LadderBlock || s.block is FlowerPotBlock ||
            above.block is SkullBlock || above.block is WallSkullBlock || above.block is LadderBlock || above.block is FlowerPotBlock || top(x, y, z) < 0.03
        val need = if (top(x, y, z) > 1.0) 3 else 2
        if (bad || (1..need).any { !bodyPasses(x, y + it, z) } || rayStops(floor(eye.x).toInt(), floor(eye.y).toInt(), floor(eye.z).toInt())) {
            Sim.chat("§cThere are blocks in the way!")
            return
        }
        etherPuff(p.position())
        Sim.tp(p, x + 0.5, y + 1.05, z + 0.5)
        // Etherwarp is ender_dragon.hurt 1/0.54 only, HOSTILE; enderman.teleport is the blink's.
        Sim.sound(SoundEvents.ENDER_DRAGON_HURT, 1f, 0.54f, p.position(), net.minecraft.sounds.SoundSource.HOSTILE)
    }

    /** Amanatides-Woo DDA with the corner guard; the cell the ray stops in, or null. */
    private fun dda(eye: Vec3, dir: Vec3, range: Double): Triple<Int, Int, Int>? {
        var bx = floor(eye.x).toInt(); var by = floor(eye.y).toInt(); var bz = floor(eye.z).toInt()
        val sx = sign(dir.x).toInt(); val sy = sign(dir.y).toInt(); val sz = sign(dir.z).toInt()
        val dx = if (dir.x != 0.0) abs(1 / dir.x) else Double.MAX_VALUE
        val dy = if (dir.y != 0.0) abs(1 / dir.y) else Double.MAX_VALUE
        val dz = if (dir.z != 0.0) abs(1 / dir.z) else Double.MAX_VALUE
        fun first(o: Double, b: Int, s: Int, d: Double) = if (s > 0) (b + 1 - o) * d else if (s < 0) (o - b) * d else Double.MAX_VALUE
        var tx = first(eye.x, bx, sx, dx); var ty = first(eye.y, by, sy, dy); var tz = first(eye.z, bz, sz, dz)
        repeat(250) {
            val t = minOf(tx, ty, tz)
            if (t > range) return null
            val cx = tx <= t + 1e-4; val cy = ty <= t + 1e-4; val cz = tz <= t + 1e-4
            if ((if (cx) 1 else 0) + (if (cy) 1 else 0) + (if (cz) 1 else 0) >= 2) {
                if (cx && rayStops(bx + sx, by, bz)) return Triple(bx + sx, by, bz)
                if (cy && rayStops(bx, by + sy, bz)) return Triple(bx, by + sy, bz)
                if (cz && rayStops(bx, by, bz + sz)) return Triple(bx, by, bz + sz)
            }
            if (cx) { bx += sx; tx += dx }
            if (cy) { by += sy; ty += dy }
            if (cz) { bz += sz; tz += dz }
            if (rayStops(bx, by, bz)) return Triple(bx, by, bz)
        }
        return null
    }

    private fun blinkBlocks(x: Int, y: Int, z: Int): Boolean {
        val s = state(x, y, z)
        if (s.block is SignBlock || s.block is AbstractBannerBlock) return false
        if (rayStops(x, y, z) && top(x, y, z) >= 0.5) return true
        return top(x, y - 1, z) > 1.0
    }

    private fun feetPass(x: Int, y: Int, z: Int) = (bodyPasses(x, y, z) || top(x, y, z) < 0.5) && top(x, y - 1, z) <= 1.0

    /** AOTV (12) / Hyperion (10): whole-block steps, every quarter checked; "There are blocks in the way!" when cut short. */
    fun blink(p: ServerPlayer, range: Int) {
        val eye = p.eyePosition
        val dir = look(p)
        var last = 0
        var px = floor(eye.x).toInt(); var pz = floor(eye.z).toInt()
        var cut = false
        loop@ for (i in 1..range) {
            for (k in 1..4) {
                val q = eye.add(dir.scale((i - 1) + k * 0.25))
                if (blinkBlocks(floor(q.x).toInt(), floor(q.y).toInt(), floor(q.z).toInt())) { cut = true; break@loop }
            }
            val c = eye.add(dir.scale(i.toDouble()))
            val cx = floor(c.x).toInt(); val cy = floor(c.y).toInt(); val cz = floor(c.z).toInt()
            if (cx != px && cz != pz && blinkBlocks(px, cy, cz) && blinkBlocks(cx, cy, pz)) { cut = true; break }
            last = i; px = cx; pz = cz
        }
        if (last == 0) { Sim.chat("§cThere are blocks in the way!"); return }
        val c = eye.add(dir.scale(last.toDouble()))
        val cx = floor(c.x).toInt(); val cy = floor(c.y).toInt(); val cz = floor(c.z).toInt()
        val feetY = if (feetPass(cx, cy - 1, cz)) cy - 1 else cy
        if (cx == floor(p.x).toInt() && cz == floor(p.z).toInt() && (feetY == floor(p.y + 0.05).toInt() || Vec3(cx + 0.5, feetY.toDouble(), cz + 0.5).distanceTo(p.position()) < 1.5)) {
            Sim.chat("§cThere are blocks in the way!"); return
        }
        if (cut) Sim.chat("§cThere are blocks in the way!")
        Sim.tp(p, cx + 0.5, feetY.toDouble(), cz + 0.5)
        Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, p.position(), net.minecraft.sounds.SoundSource.HOSTILE)
    }

    /** Wither Impact's server cooldown: a second cast within 2 server ticks is discarded, blink and Implosion both. */
    private var lastHype = -100

    private fun hypeReady(): Boolean {
        val now = Fight.serverTick
        if (now - lastHype < 2) return false
        lastHype = now
        return true
    }

    /**
     * A Hyperion Implosion's hit on a boss wither, for the chat line (no damage model in the sim):
     * real one-enemy lines are ~30-43M (median ~34M; 2 enemies 64-84M).
     */
    private const val IMPLOSION_DAMAGE = 34_000_000.0

    private var lastCure = -1000

    /** Wither Shield: absorption back to 16 two ticks on; every ~5 s the cure sound and a ring of 16 witch particles. */
    private fun witherShield(p: ServerPlayer) {
        Fight.later(2, "wither shield") {
            if (p.isRemoved) return@later
            // 26.1.2 clamps absorption to MAX_ABSORPTION: make room for the 16 first.
            p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION)?.let { if (it.baseValue < 16.0) it.baseValue = 16.0 }
            p.absorptionAmount = 16f
        }
        val now = Fight.serverTick
        if (now - lastCure < 100) return
        lastCure = now
        Sim.sound(SoundEvents.ZOMBIE_VILLAGER_CURE, 1f, 0.6984127f, p.position(), net.minecraft.sounds.SoundSource.HOSTILE)
        val look = p.lookAngle
        var right = Vec3(-look.z, 0.0, look.x)
        right = if (right.lengthSqr() < 1e-6) Vec3(1.0, 0.0, 0.0) else right.normalize()
        val up = look.cross(right).normalize()
        val c = Vec3(p.x, p.y + 1.5, p.z).add(look.scale(0.5))
        for (i in 0 until 16) {
            val a = i * Math.PI * 2 / 16
            val q = c.add(right.scale(0.7 * Math.cos(a))).add(up.scale(0.7 * Math.sin(a)))
            Fight.later(Random.nextInt(0, 5), "shield ring") { Sim.level.sendParticles(ParticleTypes.WITCH, q.x, q.y, q.z, 1, 0.0, 0.0, 0.0, 0.0) }
        }
    }

    /**
     * Implosion: at your final position, every mob whose hitbox is within
     * ±6 x/z, +7 up and -6 down of your eye, through walls, full damage each. The boss withers are
     * the only mobs here; with none in the box there is no message.
     */
    private fun implode(p: ServerPlayer) {
        // Main's Implosion puff: 8 explosion at your feet, speed 8, no spread, override limiter + always show.
        Sim.level.sendParticles(ParticleTypes.EXPLOSION, true, true, p.x, p.y, p.z, 8, 0.0, 0.0, 0.0, 8.0)
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 1f, 1f, p.position(), net.minecraft.sounds.SoundSource.BLOCKS)
        witherShield(p)
        // A P3 cast never hits Goldor on Hypixel (no hit line, even with him in range).
        if (Fight.phase is GoldorPhase) return
        val box = net.minecraft.world.phys.AABB(p.x - 6, p.eyeY - 6, p.z - 6, p.x + 6, p.eyeY + 7, p.z + 6)
        val n = Sim.level.getEntitiesOfClass(net.minecraft.world.entity.boss.wither.WitherBoss::class.java, box) { it.isAlive }.size
        if (n == 0) return
        // The hit's ding (experience_orb.pickup 1.0/1.492 on the tp tick).
        Sim.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.492f, p.position(), net.minecraft.sounds.SoundSource.PLAYERS)
        // Hypixel's exact line: "§7Your Implosion hit §r§c1 §r§7enemy for §r§c32,710,591.3 §r§7damage.", a whole number without ".0".
        val dmg = (1..n).sumOf { IMPLOSION_DAMAGE * (0.9 + Random.nextDouble() * 0.25) }
        val shown = "%,.1f".format(java.util.Locale.ROOT, dmg).removeSuffix(".0")
        Sim.chat("§7Your Implosion hit §r§c$n §r§7${if (n == 1) "enemy" else "enemies"} for §r§c$shown §r§7damage.")
    }

    // ------------------------------------------------------------------ movement items

    /**
     * Sets [p]'s motion to [v] (a SET, as Hypixel's). The packet goes straight to the player at the end of this
     * [Fight.tick], the burst's own tick: hurtMarked would wait for the next tick's entity tracker. Several pushes in
     * one tick are one packet carrying the last vector. hurtMarked stays off, so no duplicate.
     */
    private fun push(p: ServerPlayer, v: Vec3) {
        p.deltaMovement = v
        pendingMotion[p] = v
    }

    private val pendingMotion = LinkedHashMap<ServerPlayer, Vec3>()

    /** Sends this tick's [push]es (Fight.tick's last step). */
    fun flushMotion() {
        if (pendingMotion.isEmpty()) return
        pendingMotion.forEach { (p, v) -> if (!p.isRemoved) p.connection.send(net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p.id, v)) }
        pendingMotion.clear()
    }

    /**
     * Bonzo's Staff, as measured on Hypixel: a hidden projectile flies from feet+1.0+0.2d
     * at 0.75 a tick (no gravity), drawn as a marker stand 1.5 below it; the firework bursts on the first 0.75
     * lattice point whose block cell isn't passable, k+1 ticks after the stand, and in that tick your motion is
     * *replaced* by 1.5 flat away from it and 0.5 up iff it is within 3.5 of feet+1.5. Hard 4-tick gate.
     * The burst's sound is the client's own.
     */
    private fun bonzo(p: ServerPlayer) {
        // Hard 4-tick gate: never 1-3 ticks apart; a refused click sets nothing.
        val gap = Fight.serverTick - bonzoLast
        if (gap < 4) return
        // The held item is sent again on ~77% of accepted clicks, by idle time: ~50% under 8 ticks since the last
        // balloon, ~81% over 10. First, before the stand bundle.
        val resend = when { gap < 8 -> 0.5; gap > 10 -> 0.81; else -> 0.65 }
        bonzoLast = Fight.serverTick
        if (Random.nextDouble() < resend) p.connection.send(net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(0, p.inventoryMenu.incrementStateId(), 36 + p.inventory.selectedSlot, p.getItemInHand(InteractionHand.MAIN_HAND).copy()))
        // P = the last move packet's position (the server's at the click, asClicked), d = the look the click aims with:
        // a block click's is the last move packet's (use_item_on has none), an air click's the use_item's.
        val pos = clickPos ?: p.position()
        val dir = look(p)
        // Projectile pos(k) = P + (0,1,0) + 0.2d + 0.75k d, feet+1.0 sneaking too.
        val o = pos.add(0.0, 1.0, 0.0).add(dir.scale(0.2))
        // Stand S0 = floor32(P + 0.2d - 0.5y) = pos(0) - 1.5y, a marker (flags 32/18),
        // rotated to the look the server used.
        val s0 = floor32(pos.add(dir.scale(0.2)).add(0.0, -0.5, 0.0))
        val stand = ArmorStand(Sim.level, s0.x, s0.y, s0.z)
        stand.isInvisible = true
        stand.isSilent = true
        stand.setNoGravity(true)
        Station.setMarker(stand)
        Station.setLeverFlags(stand)
        stand.yRot = p.yRot; stand.xRot = p.xRot
        stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, balloonHead())
        Sim.spawn(stand)
        // Then the ghast sound, at P (after the bundle, same tick).
        Sim.sound(SoundEvents.GHAST_AMBIENT, 1f, (88 + Random.nextInt(26)) / 63f, pos, net.minecraft.sounds.SoundSource.HOSTILE)
        // The burst lattice index k: first k >= 1 whose cell isn't passable; k = 2 when pos(0)'s cell is already
        // solid (e.g. fired from inside a ghost block). Overshoot is the lattice, 0..0.75.
        // Each cell is tested in its own flight tick, so a block that changes mid-flight counts (a gate, a Dungeonbreaker
        // hole); on a still world it's the same k as testing them all at the click.
        val startsInside = bonzoSolid(o)
        fun fly(j: Int) {
            Fight.later(1, "bonzo flight") {
                if (stand.isRemoved) return@later
                // Step k = j - 1 bursts in tick j: k+1 ticks after the stand (no clamp).
                val k = j - 1
                if (k >= 1 && k <= BONZO_STEPS && (if (startsInside) k == 2 else bonzoSolid(o.add(dir.scale(BONZO_SPEED * k))))) {
                    bonzoBurst(p, o.add(dir.scale(BONZO_SPEED * k)), stand); return@later
                }
                // No block within ~128: the stand flies on and goes with no burst.
                if (j > BONZO_STEPS) { stand.discard(); return@later }
                // Drawn 1.5 below the projectile (the vanilla tracker sends updates on its 3-tick cadence, the
                // first about spawn+4, as Hypixel's).
                val q = o.add(dir.scale(BONZO_SPEED * j))
                stand.setPos(q.x, q.y - 1.5, q.z)
                fly(j + 1)
            }
        }
        fly(1)
    }

    /**
     * The balloon bursts at [bTrue]: firework at floor32(bTrue) with one entity_event 17 and no server sound,
     * gone with the stand at burst+1; your boost in the same tick from the unquantised point.
     */
    private fun bonzoBurst(p: ServerPlayer, bTrue: Vec3, stand: ArmorStand) {
        val colours = { Random.nextInt(0x1000000) }
        val rocket = ItemStack(Items.FIREWORK_ROCKET)
        rocket.set(DataComponents.FIREWORKS, net.minecraft.world.item.component.Fireworks(0, listOf(net.minecraft.world.item.component.FireworkExplosion(
            net.minecraft.world.item.component.FireworkExplosion.Shape.SMALL_BALL, it.unimi.dsi.fastutil.ints.IntArrayList(intArrayOf(colours(), colours())), it.unimi.dsi.fastutil.ints.IntArrayList(), false, false))))
        val b32 = floor32(bTrue)
        // The rocket is packets to you only, never a level entity: a firework's tracking range (64) dropped far
        // bursts, and a ticking one launches and explodes by itself. Add (zero motion), its item, event 17 exactly once
        // (the client draws the burst and plays the blast itself), and gone with the stand at burst+1.
        val fw = net.minecraft.world.entity.projectile.FireworkRocketEntity(Sim.level, b32.x, b32.y, b32.z, rocket)
        val conn = p.connection
        conn.send(net.minecraft.network.protocol.game.ClientboundAddEntityPacket(fw.id, fw.uuid, b32.x, b32.y, b32.z, 0f, 0f,
            net.minecraft.world.entity.EntityTypes.FIREWORK_ROCKET, 0, Vec3.ZERO, 0.0))
        fw.entityData.nonDefaultValues?.let { conn.send(net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(fw.id, it)) }
        conn.send(net.minecraft.network.protocol.game.ClientboundEntityEventPacket(fw, 17.toByte()))
        liveRockets += fw.id
        Fight.later(1, "bonzo cleanup") { liveRockets.rem(fw.id); conn.send(net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(fw.id)); stand.discard() }
        // The server's position of you (from the move packets that arrived), for the gate and the push.
        val pos = Fight.seenPos(p)
        // Boost iff the burst is within 3.5 of feet+1.5 (no random roll).
        if (bTrue.distanceTo(pos.add(0.0, 1.5, 0.0)) > 3.5) return
        // SET 1.5 * unit(P.xz - B.xz), vy 0.5; exactly over it: (0, 0.5, 0).
        val dx = pos.x - bTrue.x; val dz = pos.z - bTrue.z
        val h = Math.sqrt(dx * dx + dz * dz)
        push(p, if (h > 0.0) Vec3(1.5 * dx / h, 0.5, 1.5 * dz / h) else Vec3(0.0, 0.5, 0.0))
    }

    /** floor(v*32)/32 per axis: the 1/32 grid Hypixel's packets put stand and firework on. */
    private fun floor32(v: Vec3) = Vec3(floor(v.x * 32) / 32, floor(v.y * 32) / 32, floor(v.z * 32) / 32)

    /**
     * A balloon cell that stops it: cell level, so stairs, slabs and walls are solid. Passable:
     * air, liquids, fire, levers, buttons, signs, torches, tripwire, pressure plates, carpet, heads, grass, light,
     * ladders, vines and glass panes (TNT, plants, doors and bars unresolved).
     */
    private fun bonzoSolid(at: Vec3): Boolean {
        val s = state(floor(at.x).toInt(), floor(at.y).toInt(), floor(at.z).toInt())
        val b = s.block
        if (s.isAir || b is net.minecraft.world.level.block.LiquidBlock || b is net.minecraft.world.level.block.BaseFireBlock || b is LeverBlock || b is ButtonBlock ||
            b is SignBlock || b is net.minecraft.world.level.block.BaseTorchBlock || b is net.minecraft.world.level.block.TripWireBlock ||
            b is TripWireHookBlock || b is net.minecraft.world.level.block.BasePressurePlateBlock || b is net.minecraft.world.level.block.CarpetBlock ||
            b is net.minecraft.world.level.block.AbstractSkullBlock || b is net.minecraft.world.level.block.TallGrassBlock ||
            b is net.minecraft.world.level.block.LightBlock || b is LadderBlock || b is net.minecraft.world.level.block.VineBlock) return false
        if (b is net.minecraft.world.level.block.IronBarsBlock && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).path.endsWith("glass_pane")) return false
        return true
    }

    /** One of the 9 balloon heads, uniformly random. */
    private val BALLOON_HASHES = listOf(
        "f1f99e7c394e13d199d1d22cb082d209068cd7a4de6ca09edff3fc61660ae5cf", "52dd11da04252f76b6934bc26612f54f264f30eed74df89941209e191bebc0a2",
        "7c6cc5aed49056977d89348978c0aaaaa54c968a3b411bbaba5c9970b405297", "7399ff9c40fdfb7b115addddff59c344eaa11acb0c64c6839b5f8407e36d239d",
        "a2dd1389a97fd4c86b11a1a2abad61389404d837d2caf097c91d27768363dc5a", "ac96d34a3cced4e4bba25119f74ea31f50f43f90cca2fbcbc6bae8625d60dede",
        "28c544b288f899d494f953ace7743a15fdb22d24f58f9ca298f35429fdc27b63", "cc5ec2dfbf2cf4e21bd011c784d9fc4ad7a88182bed27afd91b53b4513ce0aba",
        "3491c8fb426979da2d6327b3705a4cb9c91c2d5536223233addde21d5f58c940")

    private fun balloonHead(): ItemStack {
        val hash = BALLOON_HASHES[Random.nextInt(BALLOON_HASHES.size)]
        val json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$hash\"}}}"
        val s = head(java.util.Base64.getEncoder().encodeToString(json.toByteArray()), "Player Head")
        s.remove(DataComponents.CUSTOM_NAME)
        return s
    }

    private var bonzoLast = -100
    /** Packet-only rockets not yet removed (a stop drops their cleanup: [reset] removes them). */
    private val liveRockets = it.unimi.dsi.fastutil.ints.IntArrayList()
    private const val BONZO_SPEED = 0.75
    /** ~170 lattice steps (127.5 blocks) with no block: no burst. */
    private const val BONZO_STEPS = 170

    /**
     * Jerry-chine Gun, as measured on Hypixel: an invisible bullet from F+1.1 along the last
     * move packet's look, B_k = O + (0.25 + 0.75k)u at tick k, bursts at the first B_k inside a block (or k = 30).
     * The burst plays villager.yes, then *replaces* your motion with vy 0.6 and 0.5 x the flat part of the unit
     * vector from it to your eye iff it is within 4.0 of feet+1.62, then a poof. Nothing is random but the pitch.
     */
    private fun jerry(p: ServerPlayer) {
        // At most 3 bullets a server tick (a 4-click tick still fires 3).
        if (jerryTick != Fight.serverTick) { jerryTick = Fight.serverTick; jerryShots = 0 }
        if (++jerryShots > 3) return
        // F = the last move packet's position (asClicked's click position), u = its look (asClicked prior on a
        // use_item, the player's own on a block click).
        val f = clickPos ?: p.position()
        val u = look(p)
        val o = f.add(0.0, 1.1, 0.0)
        fun bullet(k: Int) = o.add(u.scale(0.25 + 0.75 * k))
        // The bullet's visual: invisible silent marker stand, Villager head, head pose = pitch, at F + u - 0.5y = B_1 - 1.6y;
        // shared flags 33 (invisible + bit 0) and armor-stand flags 18, as Hypixel's.
        val stand = JerryStand(Sim.level, f.x + u.x, f.y + u.y - 0.5, f.z + u.z)
        stand.isInvisible = true
        stand.setSharedFlagOnFire(true)
        stand.isSilent = true
        stand.setNoGravity(true)
        Station.setMarker(stand)
        Station.setLeverFlags(stand)
        stand.yRot = p.yRot; stand.xRot = p.xRot
        stand.setHeadPose(net.minecraft.core.Rotations(p.xRot, 0f, 0f))
        stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, villagerHead())
        Sim.spawn(stand)
        // villager.trade NEUTRAL 0.5, pitch k/63 for k = 88..113, at your feet in the click tick, after the stand bundle.
        Sim.sound(SoundEvents.VILLAGER_TRADE, 0.5f, (88 + Random.nextInt(26)) / 63f, f, net.minecraft.sounds.SoundSource.NEUTRAL)
        rapidFire(p)
        fun fly(k: Int) {
            Fight.later(1, "jerry bullet") {
                if (p.isRemoved || Sim.player !== p) { stand.discard(); return@later }
                val b = bullet(k)
                // Check-then-move (max life 30).
                if (k < JERRY_LIFE && !jerrySolid(b)) {
                    val next = bullet(k + 1)
                    stand.setPos(next.x, next.y - 1.6, next.z)
                    fly(k + 1); return@later
                }
                jerryBurst(p, b)
                Fight.later(1, "jerry stand gone") { stand.discard() }
            }
        }
        fly(1)
    }

    /** A bullet bursts at [b]: yes, then the push iff in range, then one poof; the last burst of a tick wins. */
    private fun jerryBurst(p: ServerPlayer, b: Vec3) {
        // villager.yes MASTER 0.35 pitch 1 at floor(B)+0.5, out of range too: straight to you,
        // as a level sound at volume 0.35 reaches only 5.6 blocks and far bursts were silent.
        p.connection.send(net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.VILLAGER_YES),
            net.minecraft.sounds.SoundSource.MASTER, floor(b.x) + 0.5, floor(b.y) + 0.5, floor(b.z) + 0.5, 0.35f, 1f, Random.nextLong()))
        // d = (feet + 1.62) - B, the eye constant sneaking too; boost iff |d| <= 4.0.
        // Feet = the server's position of you at the burst.
        val d = Fight.seenPos(p).add(0.0, 1.62, 0.0).subtract(b)
        val len = d.length()
        if (len <= 4.0 && len > 0.0) push(p, Vec3(0.5 * d.x / len, 0.6, 0.5 * d.z / len))
        // One poof, count 1, at the exact burst point.
        Sim.level.sendParticles(ParticleTypes.POOF, true, true, b.x, b.y, b.z, 1, 0.0, 0.0, 0.0, 0.0)
    }

    /** A bullet point inside a block's collision shape (Hypixel's exact solidity rule is unverified). */
    private fun jerrySolid(b: Vec3): Boolean {
        val pos = BlockPos.containing(b)
        val shape = Sim.level.getBlockState(pos).getCollisionShape(Sim.level, pos, CollisionContext.empty())
        return !shape.isEmpty && shape.toAabbs().any { it.move(pos).contains(b) }
    }

    /** The Jerry stand's head: Hypixel's "Villager" profile skin. */
    private fun villagerHead(): ItemStack {
        val json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/41b830eb4082acec836bc835e40a11282bb51193315f91184337e8d3555583\"}}}"
        val s = head(java.util.Base64.getEncoder().encodeToString(json.toByteArray()), "Villager")
        s.remove(DataComponents.CUSTOM_NAME)
        return s
    }

    private var jerryTick = -1
    private var jerryShots = 0
    private const val JERRY_LIFE = 30

    private var rapidShots = 0
    private var rapidLast = -1000

    /** The Rapid-fire action bar: 14 x the shot in the burst, reset after 4 s idle. */
    private fun rapidFire(p: ServerPlayer) {
        val now = Fight.serverTick
        if (now - rapidLast > 80) rapidShots = 0
        rapidShots++; rapidLast = now
        // A system_chat overlay, as Hypixel sends it, not a set_action_bar_text.
        p.connection.send(net.minecraft.network.protocol.game.ClientboundSystemChatPacket(
            Component.literal("§b-${14 * rapidShots} Mana (§6Rapid-fire§b)"), true))
    }

    /** The Jerry bullet's stand: Hypixel's carries shared flag bit 0 (33 = invisible + 1) for its whole life; vanilla's baseTick would clear it. */
    class JerryStand(level: Level, x: Double, y: Double, z: Double) : ArmorStand(level, x, y, z) {
        override fun setSharedFlagOnFire(onFire: Boolean) = super.setSharedFlagOnFire(true)
    }

    /**
     * Creeper Veil as Hypixel runs it: it stays up until you right-click again, run out of vitality, or die
     * (no 10 s expiry, despite the lore). It does NOT stop death ticks (GoldorPhase.deathTick).
     * Vitality: 122, 30 a hit; a hit with under 30 left ends it ("Not enough vitality!"). Cooldown ~5 s from the end.
     */
    var cloakUntil = 0
        private set
    private var cloakReady = 0
    val cloaked get() = Fight.serverTick < cloakUntil
    private var vitality = 0
    private const val VEIL_VITALITY = 122
    private const val VEIL_HIT = 30
    private const val VEIL_COOLDOWN = 100

    /** The 6 invisible powered creepers: a hexagon, radius 1.5, ~3 degrees a tick, at your feet. */
    private val veil = ArrayList<net.minecraft.world.entity.monster.Creeper>()
    private var veilAngle = 0.0

    /** A creeper that stays in the peaceful sim world and can't be hit, pushed or aimed at. */
    class VeilCreeper(level: Level) : net.minecraft.world.entity.monster.Creeper(net.minecraft.world.entity.EntityTypes.CREEPER, level) {
        override fun checkDespawn() {}
        override fun isPickable() = false
        override fun isPushable() = false
        override fun canBeCollidedWith(other: net.minecraft.world.entity.Entity?) = false
    }

    private fun veilPos(p: ServerPlayer, i: Int): Vec3 {
        val a = Math.toRadians(veilAngle + 60.0 * i)
        return Vec3(p.x + 1.5 * Math.cos(a), p.y, p.z + 1.5 * Math.sin(a))
    }

    private fun spawnVeil(p: ServerPlayer) {
        discardVeil()
        veilAngle = 0.0
        for (i in 0 until 6) {
            val e = VeilCreeper(Sim.level)
            e.setNoAi(true); e.isSilent = true; e.isInvulnerable = true; e.setNoGravity(true); e.isInvisible = true
            // Vanilla clears a mob's invisible flag without the effect: a permanent effect keeps it (no particles).
            e.addEffect(net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, -1, 0, false, false))
            // Powered, as on Hypixel: the lightning hit sets the flag; its fire is put out again.
            e.thunderHit(Sim.level, net.minecraft.world.entity.LightningBolt(net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT, Sim.level))
            e.clearFire()
            val g = veilPos(p, i)
            e.snapTo(g.x, g.y, g.z, 0f, 0f)
            veil += Sim.spawn(e)
        }
    }

    private fun discardVeil() { veil.forEach { it.discard() }; veil.clear() }

    /** The veil's end: the hurt sound at you, the creepers gone 2 ticks after the chat. [chat] null = ended by death (no line). */
    private fun endVeil(p: ServerPlayer?, chat: String?, soundNow: Boolean = false) {
        val now = Fight.serverTick
        cloakUntil = now; cloakReady = now + VEIL_COOLDOWN; vitality = 0
        if (chat != null) Sim.chat(chat)
        val sound = { Sim.sound(SoundEvents.SKELETON_HURT, 4f, 1.1904762f, null, net.minecraft.sounds.SoundSource.HOSTILE) }
        if (soundNow) sound() else Fight.later(1, "veil end sound") { sound() }
        val gone = ArrayList(veil)
        veil.clear()
        Fight.later(2, "veil gone") { gone.forEach { it.discard() } }
    }

    /** A hit on you: true when the veil took it. 30 vitality a hit; too little ends the veil. */
    fun veilAbsorbs(): Boolean {
        if (!cloaked) return false
        if (vitality >= VEIL_HIT) { vitality -= VEIL_HIT; return true }
        endVeil(Sim.player, "§cNot enough vitality! §r§dCreeper Veil §r§cDe-activated!")
        return true
    }

    private fun tickVeil() {
        if (!cloaked) { if (veil.isNotEmpty() && veil.none { !it.isRemoved }) veil.clear(); return }
        val p = Sim.player ?: return
        // Dead (a death tick goes through the veil): it ends a tick later with the sound only.
        if (Masks.ghost) { endVeil(p, null, soundNow = true); return }
        veilAngle += 3.0
        veil.forEachIndexed { i, e -> val g = veilPos(p, i); e.snapTo(g.x, g.y, g.z, 0f, 0f) }
    }

    private fun cloak(p: ServerPlayer) {
        val now = Fight.serverTick
        if (cloaked) { endVeil(p, "§dCreeper Veil §r§cDe-activated!"); return }
        if (now < cloakReady) { Sim.chat("§cThis ability is on cooldown for ${(cloakReady - now + 19) / 20}s."); return }
        cloakUntil = Int.MAX_VALUE; vitality = VEIL_VITALITY
        Sim.chat("§dCreeper Veil §r§aActivated!")
        spawnVeil(p)
    }

    /**
     * A Pet Rod cast as main sends it: an invisible armour stand where the bobber starts, the silent splash (to you
     * only), player_abilities with the new pet's walking speed, the Autopet line ([Masks.swapPet]), then your fishing_bobber
     * thrown at 1.5 along your look (its motion sent with it), gone ~6 ticks on (2..11 measured).
     */
    private fun castRod(p: ServerPlayer) {
        val hook = net.minecraft.world.entity.projectile.FishingHook(p, Sim.level, 0, 0)
        hook.deltaMovement = p.lookAngle.scale(1.5)
        val stand = ArmorStand(Sim.level, hook.x, hook.y, hook.z)
        stand.isInvisible = true
        stand.isSilent = true
        stand.setNoGravity(true)
        Station.setMarker(stand)
        Sim.spawn(stand)
        p.connection.send(net.minecraft.network.protocol.game.ClientboundSoundPacket(SoundEvents.PLAYER_SPLASH.let { net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(it) },
            net.minecraft.sounds.SoundSource.PLAYERS, 200.0, 300.0, 400.0, 0f, 0f, Random.nextLong()))
        Masks.swapPet(p)
        Sim.spawn(hook)
        p.connection.send(net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(hook.id, hook.deltaMovement))
        Fight.later(6, "pet bobber gone") { hook.discard(); stand.discard() }
    }

    // ------------------------------------------------------------------ Superboom, Dungeonbreaker, bows

    /** Superboom TNT: blows the gate it's used on (or near where you look within 5), once that gate's section has started. */
    private fun superboom(p: ServerPlayer, on: BlockPos?, face: net.minecraft.core.Direction) {
        val phase = Fight.phase as? GoldorPhase ?: return
        // Infinite (never consumed), and no gate or explosion without a block target.
        val at = on?.let { Vec3.atCenterOf(it) } ?: return
        boomFx(Vec3.atCenterOf(on.relative(face)))
        val gate = phase.gateNear(at, 1.5)
        if (gate > 0) phase.blowGate(gate, Sim.me)
    }

    /**
     * A Superboom's (and Explosive Shot's) blast as main sends it, in the block before the face hit: explosion_emitter plus
     * 3 explosion (spread 1.0), and entity.generic.explode MASTER 1.0 at a pitch of floor((0.81 + r*0.174)*63)/63.
     */
    private fun boomFx(at: Vec3) {
        Sim.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
        Sim.level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 3, 1.0, 1.0, 1.0, 0.0)
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 1f, (floor((0.81 + Random.nextDouble() * 0.174) * 63) / 63).toFloat(), at)
    }

    // ------------------------------------------------------------------ Dungeonbreaker

    private const val THAT_BLOCK = "§cA mystical force prevents you from digging that block!"
    private const val THERE = "§cA mystical force prevents you digging there!"
    private const val NO_CHARGES = "§cYou don't have enough charges to break this block right now!"
    const val MAX_CHARGES = 20

    /** The core entrance's gold door (the only gold you can mine). */
    private val CORE_DOOR = AABB(52.0, 115.0, 54.0, 57.0, 122.0, 55.0)
    /** S4's device area: its lamps, levers and bedrock refuse with "digging there". */
    private val S4_DEVICE = AABB(55.0, 132.0, 142.0, 65.0, 137.0, 148.0)

    /** Something rests on [pos], hangs on one of its sides or under it: on top a carpet, plate, floor lever or button, torch, fire, head, sign, banner, rail, redstone, snow layer, door or plant; on a side a wall torch, wall lever or button, wall head, sign or banner, ladder, tripwire hook, or fire or vines on that face; under it a ceiling lever or button. */
    private fun holdsSomething(pos: BlockPos): Boolean {
        val level = Sim.level
        if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) return false
        val face = net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE
        val facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
        val up = level.getBlockState(pos.above())
        val ub = up.block
        val onTop = ub is net.minecraft.world.level.block.CarpetBlock || ub is net.minecraft.world.level.block.BasePressurePlateBlock ||
            (ub is net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock && up.getValue(face) == net.minecraft.world.level.block.state.properties.AttachFace.FLOOR) ||
            (ub is net.minecraft.world.level.block.BaseTorchBlock && ub !is net.minecraft.world.level.block.WallTorchBlock && ub !is net.minecraft.world.level.block.RedstoneWallTorchBlock) ||
            ub is net.minecraft.world.level.block.BaseFireBlock || ub is net.minecraft.world.level.block.SkullBlock || ub is net.minecraft.world.level.block.StandingSignBlock ||
            ub is net.minecraft.world.level.block.BannerBlock || ub is net.minecraft.world.level.block.BaseRailBlock || ub is net.minecraft.world.level.block.RedStoneWireBlock ||
            ub is net.minecraft.world.level.block.SnowLayerBlock || ub is net.minecraft.world.level.block.DoorBlock || ub is net.minecraft.world.level.block.TripWireBlock ||
            ub is net.minecraft.world.level.block.VegetationBlock || ub is net.minecraft.world.level.block.FlowerPotBlock
        if (onTop) return true
        val down = level.getBlockState(pos.below())
        if (down.block is net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock && down.getValue(face) == net.minecraft.world.level.block.state.properties.AttachFace.CEILING) return true
        for (d in net.minecraft.core.Direction.Plane.HORIZONTAL) {
            val n = level.getBlockState(pos.relative(d))
            val nb = n.block
            // Wall things face away from the block holding them.
            val wall = nb is net.minecraft.world.level.block.WallTorchBlock || nb is net.minecraft.world.level.block.RedstoneWallTorchBlock || nb is net.minecraft.world.level.block.WallSkullBlock ||
                nb is net.minecraft.world.level.block.WallSignBlock || nb is net.minecraft.world.level.block.WallBannerBlock || nb is net.minecraft.world.level.block.LadderBlock ||
                nb is net.minecraft.world.level.block.TripWireHookBlock ||
                (nb is net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock && n.getValue(face) == net.minecraft.world.level.block.state.properties.AttachFace.WALL)
            if (wall && n.hasProperty(facing) && n.getValue(facing) == d) return true
            // Fire and vines name the faces they're on.
            val side = net.minecraft.world.level.block.PipeBlock.PROPERTY_BY_DIRECTION[d.opposite]
            if ((nb is net.minecraft.world.level.block.FireBlock || nb is net.minecraft.world.level.block.VineBlock) && side != null && n.hasProperty(side) && n.getValue(side)) return true
        }
        return false
    }

    /** Why Hypixel refuses [pos]: a chat line, "" (silently), or null (it breaks). */
    private fun refusal(p: ServerPlayer, pos: BlockPos, s: BlockState): String? {
        val b = s.block
        val c = Vec3.atCenterOf(pos)
        // No mining rule for "leaving the inner chamber": it is a walking boundary on Hypixel (GoldorPhase.innerChamber).
        // S4's device area (x55-64, y132-136, z142-147) says "digging there" for its lamps, levers and bedrock; the same levers elsewhere say "that block".
        if (S4_DEVICE.contains(c) && (b is LeverBlock || b is ButtonBlock || b == net.minecraft.world.level.block.Blocks.REDSTONE_LAMP || b == net.minecraft.world.level.block.Blocks.BEDROCK)) return THERE
        if (s.getDestroySpeed(Sim.level, pos) < 0) return THAT_BLOCK
        if (b == net.minecraft.world.level.block.Blocks.BARRIER || b == net.minecraft.world.level.block.Blocks.BEDROCK) return THAT_BLOCK
        if (b is net.minecraft.world.level.block.CommandBlock) return THAT_BLOCK
        if (b == net.minecraft.world.level.block.Blocks.GOLD_BLOCK && !CORE_DOOR.contains(c)) return THAT_BLOCK
        if (b is LeverBlock || b is ButtonBlock) return THAT_BLOCK
        // Pistons and granite never break, nor does any block something is attached to (a carpet, fire, a button, a torch...).
        if (b is net.minecraft.world.level.block.piston.PistonBaseBlock || b is net.minecraft.world.level.block.piston.PistonHeadBlock || b is net.minecraft.world.level.block.piston.MovingPistonBlock) return THAT_BLOCK
        if (b == net.minecraft.world.level.block.Blocks.GRANITE || b == net.minecraft.world.level.block.Blocks.POLISHED_GRANITE) return THAT_BLOCK
        if (holdsSomething(pos)) return THAT_BLOCK
        // S4's redstone lamps are always refused with "digging there"; the emerald blocks behind the levers with "that block".
        if (b == net.minecraft.world.level.block.Blocks.REDSTONE_LAMP) return THERE
        if (b == net.minecraft.world.level.block.Blocks.EMERALD_BLOCK) return THAT_BLOCK
        // Out of reach (4.5 from the eyes): the server just puts it back.
        if (p.eyePosition.distanceTo(c) > 5.2) return ""
        return null
    }

    /** Charges (max 20), refilled in small irregular steps; blocks broken, oldest first, and when. */
    var charges = MAX_CHARGES; private set(v) { if (field != v) { field = v; refreshBreakerLore() } }
    private var refillStep = 0 // main: irregular +2 steps, not a batch per second
    private val refillRng = java.util.Random()
    private class Broken(val pos: BlockPos, val state: BlockState, val at: Int) { var restoreAt = Int.MAX_VALUE }
    private val broken = ArrayDeque<Broken>()
    /** The last 20 breaks: the 21st one schedules the oldest of them back. */
    private val window = ArrayDeque<Broken>()
    private var refusedSaidAt = -100 // one 20-tick throttle shared by the refusal and no-charges lines

    private fun resetBreaker() { charges = MAX_CHARGES; refillStep = 0; broken.clear(); window.clear(); refusedSaidAt = -100 }

    /**
     * A hit with the Dungeonbreaker reaching the server (after the ping): breaks that one block for
     * a charge, or refuses it and sends it back (the client already broke it, as on Hypixel).
     */
    private fun mine(p: ServerPlayer, pos: BlockPos) {
        val level = Sim.level
        val s = level.getBlockState(pos)
        if (s.isAir) return
        val now = Fight.serverTick
        val why = refusal(p, pos, s)
        if (why != null || charges <= 0) {
            p.connection.send(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(level, pos))
            if (why == null) { if (now - refusedSaidAt >= 20) { refusedSaidAt = now; Sim.chat(NO_CHARGES) } }
            else if (why.isNotEmpty() && now - refusedSaidAt >= 20) { refusedSaidAt = now; Sim.chat(why) }
            return
        }
        charges--
        Blocks.set(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
        broken.addLast(Broken(pos, s, now))
        // The 21st block broken brings the oldest back 41 ticks later, never past the regen timer.
        val b = broken.last()
        window.addLast(b)
        if (window.size > MAX_CHARGES) { val o = window.removeFirst(); o.restoreAt = minOf(o.restoreAt, now + 41) }
    }

    private fun restore(b: Broken) {
        Blocks.set(b.pos, b.state) // over whatever is there, the core-door barrier too
    }

    private fun tickBreaker() {
        val now = Fight.serverTick
        // Main refills ~6/s as +2 steps (sometimes +1) at irregular 1-20 tick gaps; the rate setting scales the gap.
        if (charges >= MAX_CHARGES) refillStep = 0
        else {
            if (refillStep == 0) refillStep = now + 1 + refillRng.nextInt(2 * Math.max(1, (36 / P3Sim.breakerRefill)) - 1)
            if (now >= refillStep) {
                charges = (charges + (if (refillRng.nextInt(5) == 0) 1 else 2)).coerceAtMost(MAX_CHARGES)
                refillStep = 0
            }
        }
        // The regen setting (221 ticks on main, as the client sees it); ping is added on top by the delayed block update.
        val regen = Math.round(P3Sim.breakerRegen * 20).toInt()
        broken.removeAll { if (now - it.at >= regen || now >= it.restoreAt) { restore(it); true } else false }
    }

    /**
     * The Archer's ability (Ctrl+Q): three arrows from your middle at the Terminator's +-5.5 deg, every 22 s
     * (Bows' arrows, so they count for i4 too); they blow up a gate they hit.
     */
    private var volleyReady = 0

    private fun volley(p: ServerPlayer) {
        val now = Fight.serverTick
        if (now < volleyReady) { Sim.chat("§cYour Regular Ability is currently on cooldown for ${(volleyReady - now + 19) / 20} more seconds."); return }
        volleyReady = now + 440
        val mid = Vec3(p.x, p.y + p.bbHeight / 2, p.z)
        for (dy in listOf(-5.5f, 0f, 5.5f)) {
            val yaw = Math.toRadians((p.yRot + dy).toDouble()); val pitch = Math.toRadians(p.xRot.toDouble())
            val v = Vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch)).scale(3.0)
            Bows.launch(mid, mid, v, owner = p) { h ->
                if (h is net.minecraft.world.phys.BlockHitResult) {
                    val at = h.location
                    boomFx(Vec3.atCenterOf(h.blockPos.relative(h.direction)))
                    (Fight.phase as? GoldorPhase)?.let { g -> g.gateNear(at, 2.0).takeIf { it > 0 }?.let { g.blowGate(it, Sim.me) } }
                }
            }?.isCritArrow = true
        }
        Sim.chat("§aUsed §6Explosive Shot§a!")
        Fight.later(1, "explosive shot summary") { Sim.chat("§7Your Explosive Shot hit §c0 §7enemies for §c0 §7damage.") }
        Sim.sound(SoundEvents.ARROW_SHOOT, 1f, 0.8f, p.position())
    }

    /** Arrows that aren't the sim's ([SimArrow]s handle their own hits): traced against the blocks for the i4 target. */
    private val arrows = ArrayList<AbstractArrow>()
    private val lastMotion = HashMap<AbstractArrow, Vec3>()

    fun tick() {
        val level = SimServer.level ?: return
        tickBreaker()
        tickVeil()
        EngineerClient.safely("p3sim slot 9") { tickSlot9() }
        Sim.player?.let { p -> EngineerClient.safely("p3sim mining effects") { miningEffects(p) } }
        // Vanilla bow arrows (anything shot that isn't one of Bows'). Each one's path since last tick (and a
        // little on), traced against the blocks: the first block on it is what it hit.
        level.getEntitiesOfClass(AbstractArrow::class.java, AABB(-20.0, 0.0, -20.0, 160.0, 256.0, 160.0)) { it.owner is Player && it !is SimArrow && it !in arrows }.forEach { arrows += it }
        val it = arrows.iterator()
        while (it.hasNext()) {
            val a = it.next()
            if (a.isRemoved) { it.remove(); lastMotion.remove(a); lastPos.remove(a); continue }
            val v = a.deltaMovement
            if (v.lengthSqr() > 1e-3) lastMotion[a] = v
            val dir = (lastMotion[a] ?: v).let { if (it.lengthSqr() < 1e-6) Vec3.ZERO else it.normalize() }
            val from = lastPos[a] ?: a.position().subtract(dir)
            val to = a.position().add(dir.scale(0.6))
            lastPos[a] = a.position()
            val hit = level.clip(net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, a))
            if (hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                (Fight.phase as? GoldorPhase)?.devices?.target?.hit(hit.blockPos)
                a.discard()
                it.remove(); lastMotion.remove(a); lastPos.remove(a)
                continue
            }
            if (a.tickCount > 100 || v.lengthSqr() < 1e-3) { a.discard(); it.remove(); lastMotion.remove(a); lastPos.remove(a) }
        }
    }

    private val lastPos = HashMap<AbstractArrow, Vec3>()

    // ------------------------------------------------------------------ Spirit Leap

    /** Spirit Leap's 2 s cooldown. */
    private var leapReady = 0

    private fun openLeap(p: ServerPlayer) {
        val now = Fight.serverTick
        if (now < leapReady) {
            // Cooldown: enderman.teleport HOSTILE vol 8.0 pitch 0.0 at you, then the chat line.
            Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 8f, 0f, p.position(), net.minecraft.sounds.SoundSource.HOSTILE)
            Sim.chat("§cThis ability is on cooldown for ${(leapReady - now + 19) / 20}s."); return
        }
        val bots = Party.bots().filter { it.entity != null }
        // The menu opens one RTT after the use.
        Fight.afterPing("leapOpen") { p.openMenu(SimpleMenuProvider({ id, inv, _ -> LeapMenu(id, inv, bots) }, Component.literal("Spirit Leap"))) }
    }

    /** The ghost's Haunt: the "Teleport to Player" menu, opened one RTT after the click. */
    private fun openHaunt(p: ServerPlayer) {
        val bots = Party.bots().filter { it.entity != null }
        Fight.afterPing("hauntOpen") { if (Masks.ghost) p.openMenu(SimpleMenuProvider({ id, inv, _ -> HauntMenu(id, inv, bots) }, Component.literal("Teleport to Player"))) }
    }

    /** Hypixel's ghost teleport window: teammates' heads in slots 11-15, a click teleports you to them (no cooldown). */
    class HauntMenu(id: Int, inv: Inventory, val bots: List<Party.Bot>) : ChestMenu(MenuType.GENERIC_9x4, id, inv, SimpleContainer(36), 4) {
        init {
            for (i in 0 until 36) container.setItem(i, ItemStack.EMPTY)
            bots.sortedBy { it.slot }.forEachIndexed { i, b ->
                val h = ItemStack(Items.PLAYER_HEAD)
                h.set(DataComponents.PROFILE, ResolvableProfile.createResolved(Party.profile(b)))
                h.set(DataComponents.CUSTOM_NAME, Component.literal("${rankColour(b.name)}${b.name}").withStyle { it.withItalic(false) })
                h.set(DataComponents.LORE, ItemLore(listOf("§7Click to teleport to this player!", "", "§7Health: §a100%", "", "§eClick to teleport!").map { Component.literal(it).withStyle { st -> st.withItalic(false) } }))
                container.setItem(listOf(11, 12, 14, 15).getOrElse(i) { 16 }, h)
            }
        }

        override fun clicked(slot: Int, button: Int, input: ContainerInput, p: Player) {
            if (slot !in 11..16) return
            val name = net.minecraft.ChatFormatting.stripFormatting(container.getItem(slot).hoverName.string)
            val bot = bots.firstOrNull { it.name == name } ?: return
            val sp = p as ServerPlayer
            Fight.afterPing("haunt") {
                sp.closeContainer()
                val e = bot.pos
                Sim.tp(sp, e.x, e.y, e.z, bot.yaw, bot.entity?.xRot ?: sp.xRot)
                Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1.095f, sp.position(), net.minecraft.sounds.SoundSource.HOSTILE)
                Sim.chatStyled("§aTeleported you to ${bot.name}!")
            }
        }

        override fun quickMoveStack(p: Player, slot: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(p: Player) = true
    }

    /** Hypixel's Spirit Leap window: teammates' heads in slots 11-15, a click leaps (8 ticks, as measured). */
    class LeapMenu(id: Int, inv: Inventory, val bots: List<Party.Bot>) : ChestMenu(MenuType.GENERIC_9x4, id, inv, SimpleContainer(36), 4) {
        private val owner = inv.player
        init {
            for (i in 0 until 36) container.setItem(i, Terminals.FILLER)
            // In the plan's leap slot order: slots 1-4 = chest slots 11, 12, 14, 15.
            bots.sortedBy { it.slot }.forEachIndexed { i, b ->
                val h = ItemStack(Items.PLAYER_HEAD)
                // The teammate's own head (the menu shows each one's skin): the bot's profile.
                h.set(DataComponents.PROFILE, ResolvableProfile.createResolved(Party.profile(b)))
                // The name in its rank colour, one yellow "Click to teleport!" line.
                val colour = net.minecraft.ChatFormatting.getByCode(rankColour(b.name)[1]) ?: net.minecraft.ChatFormatting.GREEN
                h.set(DataComponents.CUSTOM_NAME, Component.literal(b.name).withStyle { it.withItalic(false).withColor(colour) })
                h.set(DataComponents.LORE, ItemLore(listOf(Component.literal("Click to teleport!").withStyle { it.withItalic(false).withColor(net.minecraft.ChatFormatting.YELLOW) })))
                container.setItem(listOf(11, 12, 14, 15).getOrElse(i) { 16 }, h)
            }
        }

        /** The window's contents arrive as one container_set_slot per slot, as main sends them. */
        override fun setSynchronizer(synchronizer: net.minecraft.world.inventory.ContainerSynchronizer) =
            super.setSynchronizer((owner as? ServerPlayer)?.let { PerSlotSync(synchronizer, it) } ?: synchronizer)

        private var inClick = false

        override fun clicked(slot: Int, button: Int, input: ContainerInput, p: Player) {
            if (slot !in 11..16) return
            val name = net.minecraft.ChatFormatting.stripFormatting(container.getItem(slot).hoverName.string)
            val bot = bots.firstOrNull { it.name == name } ?: return
            val sp = p as ServerPlayer
            inClick = true
            try {
                Fight.afterPing("leap") {
                    // At no ping this runs inside the click's own packet handler, which then hands the click's cursor to
                    // whatever window is open: leaping there would close onto the inventory and draw a set_cursor_item.
                    // Run it at the head of the next server tick instead (the same tick for the client).
                    if (inClick) Fight.later(0, "leap") { leap(sp, bot) } else leap(sp, bot)
                }
            } finally { inClick = false }
        }

        /** Main's tail: container_close(N), player_position, the sound, the chat line, then container_close(0). */
        private fun leap(sp: ServerPlayer, bot: Party.Bot) {
            if (sp.isRemoved) return
            sp.closeContainer()
            val e = bot.pos
            leapReady = Fight.serverTick + 40
            // You land on them exactly, facing as they face.
            Sim.tp(sp, e.x, e.y, e.z, bot.yaw, bot.entity?.xRot ?: sp.xRot)
            Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, sp.position(), net.minecraft.sounds.SoundSource.HOSTILE)
            Sim.chatStyled("§aYou have teleported to §r${rankColour(bot.name)}${bot.name}§r§a!")
            sp.connection.send(net.minecraft.network.protocol.game.ClientboundContainerClosePacket(0))
            GhostCapture.event("leap", bot.clazz.name)
        }

        override fun quickMoveStack(p: Player, slot: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(p: Player) = true
    }

    /**
     * Sends a window's opening (and full re-send) contents as one container_set_slot per slot, stateId counting up, instead
     * of vanilla's single container_set_content: Hypixel's way for the Spirit Leap and Loadouts windows. The rest is vanilla's.
     */
    class PerSlotSync(private val base: net.minecraft.world.inventory.ContainerSynchronizer, private val sp: ServerPlayer) : net.minecraft.world.inventory.ContainerSynchronizer by base {
        override fun sendInitialData(container: net.minecraft.world.inventory.AbstractContainerMenu, slotItems: List<ItemStack>, carried: ItemStack, dataSlots: IntArray) {
            for ((i, s) in slotItems.withIndex()) sp.connection.send(net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(container.containerId, container.incrementStateId(), i, s))
            for ((i, v) in dataSlots.withIndex()) base.sendDataChange(container, i, v)
        }
    }
}
