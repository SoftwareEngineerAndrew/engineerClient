package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * ItemStacks for [ItemDefs]' items, and back. The stack's custom data holds the item's id under
 * "rustclear_def" plus Hypixel's own ExtraAttributes fields at the root (id, upgrade_level,
 * baseStatBoostPercentage, item_tier, ability_scroll), which client mods read.
 */
public final class DataItems {
    static final String TAG = "rustclear_def";
    private static final String BONUS = "rustclear_bonus";
    private static final String REFORGE = "rustclear_reforge";
    private static final String GEMS = "rustclear_gems";

    private DataItems() {
    }

    /** [item] as a stack, its tooltip worked out for a player at Catacombs [cata] on a [masterMode] floor. */
    public static ItemStack stack(ItemDefs.Instance item, int cata, boolean masterMode) {
        ItemDefs.Def def = item.def();
        Item material = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(def.material));
        if (material == Items.AIR) {
            material = Items.PAPER;
        }
        if (def.id.equals("BAT_WAND") || def.id.equals("STARRED_BAT_WAND")) {
            // The kit's Heroic Spirit Sceptre look (EngineItem.SPIRIT_SCEPTRE), not the data's paper.
            material = Items.ALLIUM;
        }
        ItemStack stack;
        if (material == Items.PLAYER_HEAD && def.skin != null) {
            stack = new ItemStack(Items.PLAYER_HEAD);
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(
                EngineItems.profile(def.id.length() > 16 ? def.id.substring(0, 16) : def.id, def.skin, def.signature)));
        } else {
            stack = new ItemStack(material);
        }
        stack.set(DataComponents.CUSTOM_NAME, EngineItems.text(ItemDefs.displayName(item)));
        stack.set(DataComponents.LORE, new ItemLore(EngineItems.lines(ItemDefs.lore(item, cata, masterMode).toArray(String[]::new))));
        if (def.color != null) {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(def.color));
        }
        if (def.glowing || item.witherImpact()) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        // Our lore says it all: no vanilla attack damage, dye or "When in main hand" lines under it.
        stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT
            .withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true)
            .withHidden(DataComponents.DYED_COLOR, true)
            .withHidden(DataComponents.UNBREAKABLE, true));
        stack.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG, def.id);
        tag.putString("id", def.id);
        if (item.stars() > 0) {
            tag.putInt("upgrade_level", item.stars() + item.masterStars());
        }
        if (def.isTiered()) {
            tag.putInt("item_tier", item.tier());
            tag.putInt("baseStatBoostPercentage", item.quality());
        }
        if (!item.scrolls().isEmpty()) {
            ListTag scrolls = new ListTag();
            item.scrolls().forEach(s -> scrolls.add(StringTag.valueOf(s)));
            tag.put("ability_scroll", scrolls);
        }
        putExtras(tag, item);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** [item]'s Hypixel-profile extras (bonus stats, reforge, recombobulator) into a stack's custom data. */
    static void putExtras(CompoundTag tag, ItemDefs.Instance item) {
        if (!item.bonus().isEmpty()) {
            CompoundTag bonus = new CompoundTag();
            item.bonus().forEach(bonus::putDouble);
            tag.put(BONUS, bonus);
        }
        if (item.reforge() != null) {
            tag.putString(REFORGE, item.reforge());
        }
        if (item.recomb()) {
            tag.putInt("rarity_upgrades", 1);
        }
        putMods(tag, item.mods());
    }

    /** [m] as Hypixel's custom_data keeps them (modifier, hot_potato_count, enchantments, gems...). */
    static void putMods(CompoundTag tag, ItemMods m) {
        if (m.reforge() != null) {
            tag.putString("modifier", m.reforge());
        }
        if (m.potatoBooks() > 0) {
            tag.putInt("hot_potato_count", m.potatoBooks());
        }
        if (m.artOfPeace()) {
            tag.putInt("artOfPeaceApplied", 1);
        }
        if (m.artOfWar()) {
            tag.putInt("art_of_war_count", 1);
        }
        if (!m.enchants().isEmpty()) {
            CompoundTag ench = new CompoundTag();
            m.enchants().forEach(ench::putInt);
            tag.put("enchantments", ench);
        }
        if (!m.gems().isEmpty()) {
            net.minecraft.nbt.ListTag gems = new net.minecraft.nbt.ListTag();
            m.gems().forEach(g -> gems.add(net.minecraft.nbt.StringTag.valueOf(g)));
            tag.put(GEMS, gems);
        }
    }

    /** The mods [tag] holds (putExtras). */
    static ItemMods mods(CompoundTag tag) {
        java.util.Map<String, Integer> ench = new java.util.LinkedHashMap<>();
        CompoundTag e = tag.getCompoundOrEmpty("enchantments");
        for (String k : e.keySet()) {
            ench.put(k, e.getIntOr(k, 0));
        }
        List<String> gems = new ArrayList<>();
        for (Tag t : tag.getListOrEmpty(GEMS)) {
            t.asString().ifPresent(gems::add);
        }
        return new ItemMods(tag.getStringOr("modifier", null), tag.getIntOr("hot_potato_count", 0),
            tag.getIntOr("artOfPeaceApplied", 0) > 0, tag.getIntOr("art_of_war_count", 0) > 0, ench, gems);
    }

    /** [item] with the extras [tag] holds (putExtras), if any. */
    static ItemDefs.Instance withExtras(ItemDefs.Instance item, CompoundTag tag) {
        CompoundTag bonusTag = tag.getCompoundOrEmpty(BONUS);
        String reforge = tag.getStringOr(REFORGE, "");
        boolean recomb = tag.getIntOr("rarity_upgrades", 0) > 0;
        ItemMods mods = mods(tag);
        if (bonusTag.isEmpty() && reforge.isEmpty() && !recomb && mods.isEmpty()) {
            return item;
        }
        java.util.Map<String, Double> bonus = new java.util.LinkedHashMap<>();
        for (String stat : bonusTag.keySet()) {
            bonus.put(stat, bonusTag.getDoubleOr(stat, 0));
        }
        return new ItemDefs.Instance(item.def(), item.stars(), item.masterStars(), item.tier(), item.quality(), item.scrolls(),
            bonus, reforge.isEmpty() ? null : reforge, recomb, mods);
    }

    /** The item [stack] is, or null when it isn't one of these. */
    public static ItemDefs.Instance of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        ItemDefs.Def def = ItemDefs.get().def(tag.getStringOr(TAG, ""));
        if (def == null) {
            return null;
        }
        int level = tag.getIntOr("upgrade_level", 0);
        List<String> scrolls = new ArrayList<>();
        for (Tag t : tag.getListOrEmpty("ability_scroll")) {
            t.asString().ifPresent(scrolls::add);
        }
        return withExtras(new ItemDefs.Instance(def, Math.min(level, ItemStats.MAX_STARS), Math.max(0, level - ItemStats.MAX_STARS),
            tag.getIntOr("item_tier", 7), tag.getIntOr("baseStatBoostPercentage", 0), scrolls), tag);
    }

    /** [def] as a dungeon would hand it out: five stars, five master stars, [tier], 50/50 quality and every scroll. */
    public static ItemDefs.Instance best(ItemDefs.Def def, int tier) {
        boolean starrable = def.starCosts != null && def.starCosts.length > 0;
        return new ItemDefs.Instance(def, starrable ? ItemStats.MAX_STARS : 0, starrable ? ItemStats.MAX_MASTER_STARS : 0,
            tier, ItemStats.MAX_QUALITY, def.takesScrolls() ? ItemDefs.SCROLLS : List.of());
    }
}
