package net.rustclear.mod.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The gear a player gets when they have no kit of their own: what top F7 players wear and how they
 * build it, measured from the recordings (items-stats.md; the recorders' Stats & Equipment and
 * Loadouts windows). Mage: a Spirit Mask and Storm's armour (Ancient, recombobulated, 15 potato
 * books, five master stars, two Perfect Sapphires, Growth V and Protection V); Berserk and Archer: a
 * Diamond Necron Head and Necron's armour (Jaspers); Tank: Goldor's; Healer: Storm's, as a Mage.
 * Everyone: a Bone Necklace, Shadow Assassin Cloak and Soulweaver Gloves. The hotbar's weapons get
 * their measured mods too (the Hyperion Heroic, as the starter is named, with 15 books, Art of War, two Perfect Sapphires).
 * OPEN: Terror armour, the Implosion Belt, the Racing Helmet and Spring Boots (often worn) aren't in
 * Rustclear's item data; Tank and Healer gear was never recorded (the recorder never played them).
 */
final class DefaultGear {
    private DefaultGear() {
    }

    private static final Map<String, Integer> ARMOUR_ENCHANTS = Map.of("growth", 5, "protection", 5, "rejuvenate", 5);
    private static final List<String> SAPPHIRES = List.of("SAPPHIRE:PERFECT", "SAPPHIRE:PERFECT");
    private static final List<String> JASPERS = List.of("JASPER:PERFECT", "JASPER:PERFECT");

    /** One piece: its item id, where it's worn, and its mods. */
    private record Piece(String id, EquipmentSlot slot, Equipment.Slot equipment, int level, String reforge, int books,
                         Map<String, Integer> enchants, List<String> gems) {}

    private static Piece armour(String id, EquipmentSlot slot, String reforge, Map<String, Integer> extra, List<String> gems) {
        Map<String, Integer> enchants = new LinkedHashMap<>(ARMOUR_ENCHANTS);
        enchants.putAll(extra);
        return new Piece(id, slot, null, 10, reforge, 15, enchants, gems);
    }

    private static Piece equipment(String id, Equipment.Slot slot, String reforge) {
        return new Piece(id, null, slot, 5, reforge, 0, Map.of(), List.of());
    }

    /** [c]'s pieces, worn and equipped. */
    private static List<Piece> pieces(DungeonClass c) {
        List<Piece> worn = switch (c) {
            case BERSERK, ARCHER -> List.of(
                new Piece("DIAMOND_NECRON_HEAD", EquipmentSlot.HEAD, null, 10, "ancient", 15, ARMOUR_ENCHANTS, List.of()),
                armour("POWER_WITHER_CHESTPLATE", EquipmentSlot.CHEST, "ancient", Map.of("true_protection", 1), JASPERS),
                armour("POWER_WITHER_LEGGINGS", EquipmentSlot.LEGS, "ancient", Map.of(), JASPERS),
                armour("POWER_WITHER_BOOTS", EquipmentSlot.FEET, "ancient", Map.of("sugar_rush", 3), JASPERS));
            case TANK -> List.of(
                armour("TANK_WITHER_HELMET", EquipmentSlot.HEAD, "necrotic", Map.of(), List.of()),
                armour("TANK_WITHER_CHESTPLATE", EquipmentSlot.CHEST, "necrotic", Map.of("true_protection", 1), List.of()),
                armour("TANK_WITHER_LEGGINGS", EquipmentSlot.LEGS, "necrotic", Map.of(), List.of()),
                armour("TANK_WITHER_BOOTS", EquipmentSlot.FEET, "necrotic", Map.of("sugar_rush", 3), List.of()));
            case MAGE, HEALER -> List.of(
                new Piece("STARRED_SPIRIT_MASK", EquipmentSlot.HEAD, null, 5, "necrotic", 0,
                    Map.of("respite", 5, "ultimate_wisdom", 5), List.of()),
                armour("WISE_WITHER_CHESTPLATE", EquipmentSlot.CHEST, "ancient",
                    Map.of("reflection", 5, "true_protection", 1, "ultimate_wisdom", 5), SAPPHIRES),
                armour("WISE_WITHER_LEGGINGS", EquipmentSlot.LEGS, "ancient", Map.of("smarty_pants", 5, "ultimate_wisdom", 5), SAPPHIRES),
                armour("WISE_WITHER_BOOTS", EquipmentSlot.FEET, "ancient",
                    Map.of("sugar_rush", 3, "depth_strider", 3, "feather_falling", 10, "ultimate_wisdom", 5), SAPPHIRES));
        };
        List<Piece> all = new java.util.ArrayList<>(worn);
        all.add(equipment("STARRED_BONE_NECKLACE", Equipment.Slot.NECKLACE, null));
        all.add(equipment("STARRED_SHADOW_ASSASSIN_CLOAK", Equipment.Slot.CLOAK, null));
        all.add(equipment("SOULWEAVER_GLOVES", Equipment.Slot.GLOVES, null));
        return all;
    }

    /** Marks the pieces this hands out, so the next loadout swaps them (a class change) but leaves the player's own. */
    private static final String MARK = "rustclear_default_gear";

    static boolean isDefault(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(MARK, false);
    }

    /** Puts [c]'s default armour on [player] and their equipment in its slots (empty or default ones only). */
    static void wear(ServerPlayer player, DungeonClass c) {
        for (Piece p : pieces(c)) {
            ItemDefs.Def def = ItemDefs.get().def(p.id());
            if (def == null) {
                continue;
            }
            ItemDefs.Instance item = new ItemDefs.Instance(def, Math.min(p.level(), ItemStats.MAX_STARS),
                Math.max(0, p.level() - ItemStats.MAX_STARS), ItemStats.MAX_TIER, ItemStats.MAX_QUALITY, List.of(), null, null,
                true, new ItemMods(p.reforge(), p.books(), false, false, p.enchants(), p.gems()));
            ItemStack stack = DataItems.stack(item, PlayerStats.profile(player).cataLevel(),
                EngineServer.settings().floorOrDefault().master());
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
            tag.putBoolean(MARK, true);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            if (p.slot() != null) {
                ItemStack now = player.getItemBySlot(p.slot());
                if (now.isEmpty() || isDefault(now)) {
                    player.setItemSlot(p.slot(), stack);
                }
            } else {
                ItemStack now = Equipment.get(player, p.equipment());
                if (now.isEmpty() || isDefault(now)) {
                    Equipment.equip(player, stack);
                }
            }
        }
    }

    /** The measured mods on the hotbar's engine weapons (none: the item's own). */
    static ItemMods weapon(EngineItem item) {
        return switch (item) {
            case HYPERION -> new ItemMods("heroic", 15, false, true, Map.ofEntries(Map.entry("critical", 7),
                Map.entry("sharpness", 7), Map.entry("ender_slayer", 6), Map.entry("giant_killer", 6), Map.entry("thunderlord", 7),
                Map.entry("PROSECUTE", 6), Map.entry("cubism", 5), Map.entry("impaling", 5), Map.entry("smite", 7),
                Map.entry("lethality", 6), Map.entry("vampirism", 6), Map.entry("titan_killer", 7)), SAPPHIRES);
            case BONZO_STAFF -> new ItemMods("heroic", 0, false, false, Map.of("ultimate_wise", 5), SAPPHIRES);
            case JERRY_CHINE_GUN -> new ItemMods("heroic", 0, false, false, Map.of("ultimate_wise", 5), List.of());
            case ASPECT_OF_THE_VOID -> new ItemMods("heroic", 0, false, false, Map.of("ultimate_wise", 5), List.of());
            default -> ItemMods.NONE;
        };
    }

    /**
     * The Terminator as top players build it, the same one a kit gives: five stars and five master stars, recombobulated,
     * with the recorded mods (kit_mods.json: Precise, 15 potato books, Art of War and the full enchantment set).
     */
    static ItemStack terminator(ServerPlayer player) {
        Kit.Entry entry = KitItems.bestRecorded(EngineItem.TERMINATOR.name());
        entry.recomb = true;
        return KitItems.stack(entry, PlayerStats.profile(player).cataLevel(), EngineServer.settings().floorOrDefault().master());
    }

    /** [stack] (an engine item's) with [item]'s measured mods in its custom data, recombobulated with them. */
    static ItemStack withMods(EngineItem item, ItemStack stack) {
        ItemMods mods = weapon(item);
        if (mods.isEmpty()) {
            return stack;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
        ItemDefs.Def def = ItemDefs.get().def(item.hypixelId());
        if (def != null) {
            DataItems.putExtras(tag, new ItemDefs.Instance(def, 0, 0, ItemStats.MAX_TIER, ItemStats.MAX_QUALITY, List.of(), null,
                null, true, mods));
        } else {
            DataItems.putMods(tag, mods);
            tag.putInt("rarity_upgrades", 1);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }
}
