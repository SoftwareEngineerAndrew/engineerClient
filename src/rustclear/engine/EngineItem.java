package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * The items every player carries (Smalldungeons items/mod.rs Item and get_item_stack), tagged
 * with a custom data id so the engine knows them whatever their vanilla item does.
 */
public enum EngineItem {
    HYPERION(Items.IRON_SWORD, 1, true, "§dHeroic Hyperion §6✪✪✪✪✪",
        "§7Damage: §c+369 §e(+30)", "§7Strength: §c+245 §e(+30) §9(+50)", "§7Intelligence: §a+670 §9(+125) §d(+60)", "",
        "§aScroll Abilities:", "§c§l⦾ §6Ability: Wither Impact  §e§lRIGHT CLICK", "§7Teleport §a10 blocks§7 ahead of you.",
        "§7Then implode dealing §c20,208.4 §7damage", "§7to nearby enemies.", "§8Mana Cost: §3270", "",
        "§8§l* §8Co-op Soulbound §8§l*", "§d§l§ka§r §d§lMYTHIC DUNGEON SWORD §d§l§ka"),
    ASPECT_OF_THE_VOID(Items.DIAMOND_SHOVEL, 1, true, "§6Aspect of the Void",
        "§6Ability: Instant Transmission §e§lRIGHT CLICK", "§7Teleport §a12 blocks§7 ahead of you.", "§8Mana Cost: §318", "",
        "§6Ability: Ether Transmission §e§lSNEAK RIGHT CLICK", "§7Teleport to your targeted block up", "§7to §a61 blocks §7away",
        "§8Mana Cost: §373", "", "§6§l§kU§r§6§l LEGENDARY SWORD §kU"),
    DIAMOND_PICKAXE(Items.DIAMOND_PICKAXE, 1, true, "§9Diamond Pickaxe",
        "§8Breaking Power 4", "", "§9Efficiency X", "§7Increases how quickly your tool", "§7breaks blocks.", "",
        "§9§l§kE§r§9§l RARE PICKAXE §kE"),
    SPIRIT_SCEPTRE(Items.ALLIUM, 1, true, "§d⚚ Heroic Spirit Sceptre §6✪✪✪✪✪",
        "§b§l⦾ §6Ability: Guided Bat  §e§lRIGHT CLICK", "§7Shoots a guided spirit bat, following your aim",
        "§7and exploding for §c6,253.4 §7damage.", "§8Mana Cost: §3180", "",
        "§d§l§ka§r §d§lMYTHIC DUNGEON SWORD §d§l§ka"),
    SUPERBOOM_TNT(Items.TNT, 64, true, "§9Superboom TNT",
        "§7Breaks weak walls. Can be used to", "§7blow up Crypts in §cThe Catacombs §7and", "§7§5Crystal Hollows§7.", "",
        "§9§lRARE"),
    TACTICAL_INSERTION(Items.BLAZE_ROD, 16, false, "§6Tactical Insertion",
        "§6Ability: Gorilla Tactics  §e§lRIGHT CLICK", "§7Marks your location and teleport back there", "§7after §a3s§7.",
        "", "§8Mana Cost: §3180", "§8Cooldown: §a20s", "", "§6§l§ka§r §6§lLEGENDARY §6§l§ka"),
    TERMINATOR(Items.BOW, 1, true, "§dPrecise Terminator §6✪✪✪✪✪§c➎",
        "§7Shoots §b3 §7arrows at once.", "§7Can damage endermen.", "", "§dShortbow: Instantly shoots!", "",
        "§d§l§ka§r §d§lMYTHIC DUNGEON BOW §d§l§ka"),
    ENDER_PEARL(Items.ENDER_PEARL, 16, false, "§fEnder Pearl", "§8Collection Item", "", "§f§lCOMMON"),
    GOLDEN_AXE(Items.GOLDEN_AXE, 1, true, "§fGolden Axe", "§7Damage: §c+20", "", "§9Efficiency V", "", "§f§lCOMMON AXE"),
    BONZO_STAFF(Items.BLAZE_ROD, 1, false, "§9⚚ Heroic Bonzo's Staff §6✪✪✪✪✪",
        "§6Ability: Showtime  §e§lRIGHT CLICK", "§7Shoots balloons that create a large explosion",
        "§7on impact, dealing up to §c22,999.9 §7damage.", "§8Mana Cost: §341", "", "§9§lRARE DUNGEON SWORD"),
    JERRY_CHINE_GUN(Items.GOLDEN_HORSE_ARMOR, 1, false, "§5Jerry-Chine Gun"),
    DUNGEONBREAKER(Items.DIAMOND_PICKAXE, 1, true, "§cDungeonbreaker"),
    DEPTH_STRIDER_BOOTS(Items.DIAMOND_BOOTS, 1, false, "§bDiamond Boots", "§7Depth Strider III");

    private static final String TAG = "rustclear_item";

    final Item item;
    final int count;
    final boolean glint;
    final String name;
    final String[] lore;

    EngineItem(Item item, int count, boolean glint, String name, String... lore) {
        this.item = item;
        this.count = count;
        this.glint = glint;
        this.name = name;
        this.lore = lore;
    }

    public ItemStack stack() {
        ItemStack stack = EngineItems.named(item, name, lore);
        stack.setCount(count);
        if (glint) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG, name());
        // Hypixel's ExtraAttributes, which client mods read from the root of custom_data.
        tag.putString("id", hypixelId());
        if (this == ASPECT_OF_THE_VOID) {
            // Odin's isEtherwarpItem (and other mods') look for this, the Etherwarp transmission.
            tag.putInt("ethermerge", 1);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** The item's id on Hypixel (smalldungeons' items/mod.rs). */
    String hypixelId() {
        return switch (this) {
            case SPIRIT_SCEPTRE -> "STARRED_BAT_WAND";
            case GOLDEN_AXE -> "GOLD_AXE";
            case BONZO_STAFF -> "STARRED_BONZO_STAFF";
            case JERRY_CHINE_GUN -> "JERRY_STAFF";
            case DEPTH_STRIDER_BOOTS -> "DIAMOND_BOOTS";
            default -> name();
        };
    }

    /** Which engine item [stack] is, or null. */
    public static EngineItem of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        String id = data.copyTag().getStringOr(TAG, "");
        for (EngineItem item : values()) {
            if (item.name().equals(id)) {
                return item;
            }
        }
        return null;
    }

    /**
     * server.rs's starting inventory (1.8 window slots 36-44 are hotbar 0-8; slot 8 is the run's map),
     * laid out as HotbarLayout says; or, when [player] saved a kit in the Kit editor, exactly that.
     */
    public static void giveLoadout(ServerPlayer player) {
        Kit kit = KitItems.forPlayer(player);
        SkyblockData.loadoutGiven(player);
        if (kit != null) {
            KitItems.give(player, kit);
            DungeonOrb.refresh(player);
            return;
        }
        List<Map.Entry<Integer, ItemStack>> items = new ArrayList<>();
        items.add(Map.entry(0, DefaultGear.withMods(HYPERION, HYPERION.stack())));
        items.add(Map.entry(1, DefaultGear.withMods(ASPECT_OF_THE_VOID, ASPECT_OF_THE_VOID.stack())));
        items.add(Map.entry(2, DIAMOND_PICKAXE.stack()));
        items.add(Map.entry(3, SPIRIT_SCEPTRE.stack()));
        items.add(Map.entry(4, SUPERBOOM_TNT.stack()));
        items.add(Map.entry(5, TACTICAL_INSERTION.stack()));
        items.add(Map.entry(6, DefaultGear.terminator(player)));
        items.add(Map.entry(7, ENDER_PEARL.stack()));
        items.add(Map.entry(13, GOLDEN_AXE.stack()));
        items.add(Map.entry(14, DefaultGear.withMods(BONZO_STAFF, BONZO_STAFF.stack())));
        items.add(Map.entry(15, DefaultGear.withMods(JERRY_CHINE_GUN, JERRY_CHINE_GUN.stack())));
        items.add(Map.entry(16, new ItemStack(Items.CHEST, 64)));
        items.add(Map.entry(17, ItemSystem.dungeonbreakerStack(ItemSystem.MAX_CHARGES)));
        HotbarLayout.give(player, items);
        // Armour and equipment as top players wear them for the player's class (DefaultGear); a
        // dungeon item someone put on themselves stays on, and the old Depth Strider boots make way.
        ItemStack worn = player.getItemBySlot(EquipmentSlot.FEET);
        if (of(worn) == DEPTH_STRIDER_BOOTS) {
            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        }
        DefaultGear.wear(player, PlayerStats.classOf(player));
        DungeonOrb.refresh(player);
    }

    /** Each starting item's default slot, by HotbarLayout's id. */
    public static Map<String, Integer> defaultSlots() {
        Map<String, Integer> slots = new LinkedHashMap<>();
        slots.put(HYPERION.name(), 0);
        slots.put(ASPECT_OF_THE_VOID.name(), 1);
        slots.put(DIAMOND_PICKAXE.name(), 2);
        slots.put(SPIRIT_SCEPTRE.name(), 3);
        slots.put(SUPERBOOM_TNT.name(), 4);
        slots.put(TACTICAL_INSERTION.name(), 5);
        slots.put(TERMINATOR.name(), 6);
        slots.put(ENDER_PEARL.name(), 7);
        slots.put(GOLDEN_AXE.name(), 13);
        slots.put(BONZO_STAFF.name(), 14);
        slots.put(JERRY_CHINE_GUN.name(), 15);
        slots.put("CHEST", 16);
        slots.put(DUNGEONBREAKER.name(), 17);
        return slots;
    }
}
