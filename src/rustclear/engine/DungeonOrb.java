package net.rustclear.mod.engine;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * Hypixel's Dungeon Orb (DUNGEON_STONE), from a kit or /rcitem: "When entering a Dungeon, this
 * stone adapts to its user." Here it takes its holder's class: tinted the class's color (drawn as
 * a firework star, whose middle takes the stack's colors) and named for it, with the class's
 * ability and ultimate, what they do and their cooldowns in its tooltip. Restyled whenever the
 * starting items are handed out and whenever the class changes.
 */
final class DungeonOrb {
    static final String ID = "DUNGEON_STONE";
    private static final Identifier MODEL = Identifier.withDefaultNamespace("firework_star");

    private DungeonOrb() {
    }

    static boolean is(ItemStack stack) {
        ItemDefs.Instance item = DataItems.of(stack);
        return item != null && ID.equals(item.def().id);
    }

    /** Every Dungeon Orb [player] carries, restyled for their class now. */
    static void refresh(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (is(stack)) {
                style(stack, player);
            }
        }
    }

    private static void style(ItemStack stack, ServerPlayer player) {
        StatSheet s = PlayerStats.of(player);
        DungeonClass c = s.dungeonClass != null ? s.dungeonClass : PlayerStats.classOf(player);
        int level = s.classLevel;
        double cdr = s.classBonus.cooldownReduction();
        stack.set(DataComponents.ITEM_MODEL, MODEL);
        stack.set(DataComponents.FIREWORK_EXPLOSION, new FireworkExplosion(FireworkExplosion.Shape.SMALL_BALL,
            IntList.of(c.rgb), IntList.of(), false, false));
        stack.set(DataComponents.CUSTOM_NAME, EngineItems.text(c.color + "Dungeon Orb §8(" + c.title + ")"));
        stack.set(DataComponents.LORE, new ItemLore(EngineItems.lines(lore(c, level, cdr).toArray(String[]::new))));
        TooltipDisplay shown = stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
        stack.set(DataComponents.TOOLTIP_DISPLAY, shown.withHidden(DataComponents.FIREWORK_EXPLOSION, true)
            .withHidden(DataComponents.PROFILE, true));
    }

    /** The orb's tooltip for [c] at [level] with [cdr] cooldown reduction. */
    static List<String> lore(DungeonClass c, int level, double cdr) {
        List<String> lines = new ArrayList<>();
        lines.add("§7When entering a Dungeon, this stone");
        lines.add("§7adapts to its user.");
        lines.add("");
        lines.add("§7Class: " + c.color + c.title + " " + level);
        lines.add("");
        lines.add("§6Ability: " + c.ability + " §e§lCTRL+DROP");
        for (String line : c.abilityText) {
            lines.add("§7" + line);
        }
        lines.add("§8Cooldown: §a" + seconds(ClassAbilities.cooldownTicks(c, false, level, cdr)));
        lines.add("");
        lines.add("§6Ultimate: " + c.ultimate + " §e§lDROP");
        for (String line : c.ultimateText) {
            lines.add("§7" + line);
        }
        lines.add("§8Cooldown: §a" + seconds(ClassAbilities.cooldownTicks(c, true, level, cdr)));
        lines.add("");
        lines.add("§6§lLEGENDARY DUNGEON ITEM");
        return lines;
    }

    private static String seconds(long ticks) {
        return Stat.number(ticks / 20.0) + "s";
    }
}
