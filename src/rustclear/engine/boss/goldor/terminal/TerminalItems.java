package net.rustclear.mod.engine.boss.goldor.terminal;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.ColorCollection;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.rustclear.mod.engine.EngineItems;

/**
 * The terminals' items as Hypixel sends them: every name is one plain component with a colour and
 * italic false (green for Order, Rubix, Starts with and Select; Panes "Off" red / "On" green; Melody
 * "Lock In Slot" green / "Row Not Active" red), and the filler is EngineItems.filler().
 */
final class TerminalItems {
    private TerminalItems() {
    }

    static final ChatFormatting GREEN = ChatFormatting.GREEN;
    static final ChatFormatting RED = ChatFormatting.RED;

    /** [item] x[count] named [name] in [colour] (null: no colour), not italic. */
    static ItemStack named(Item item, int count, String name, ChatFormatting colour) {
        ItemStack stack = new ItemStack(item, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name)
            .withStyle(style -> (colour == null ? style : style.withColor(colour)).withItalic(false)));
        return stack;
    }

    static ItemStack named(Item item, String name, ChatFormatting colour) {
        return named(item, 1, name, colour);
    }

    static ItemStack filler() {
        return EngineItems.filler();
    }

    /** Melody's black track panes (rows 0 and 4, columns 1-5): named "" but with their tooltip (not hidden). */
    static ItemStack trackPane() {
        return named(Items.STAINED_GLASS_PANE.black(), "", null);
    }

    static ItemStack pane(Item pane) {
        return named(pane, "", null);
    }

    /** Both Melody buttons carry the same two gray lines (184 of 184). */
    static ItemStack melodyButton(boolean active) {
        ItemStack stack = active
            ? named(Items.DYED_TERRACOTTA.lime(), "Lock In Slot", GREEN)
            : named(Items.DYED_TERRACOTTA.red(), "Row Not Active", RED);
        stack.set(DataComponents.LORE, new ItemLore(List.of(gray("Click this button when the Green"), gray("lines up with the Purple!"))));
        return stack;
    }

    private static Component gray(String line) {
        return Component.literal(line).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false));
    }

    /** Rubix's five panes, in [TerminalLogic.RUBIX_COLOURS] order. */
    static Item rubixPane(int colour) {
        return switch (colour) {
            case 0 -> Items.STAINED_GLASS_PANE.red();
            case 1 -> Items.STAINED_GLASS_PANE.orange();
            case 2 -> Items.STAINED_GLASS_PANE.yellow();
            case 3 -> Items.STAINED_GLASS_PANE.green();
            default -> Items.STAINED_GLASS_PANE.blue();
        };
    }

    /** A vanilla item by id ("minecraft:" implied). */
    static Item byId(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
    }

    /** A Starts-with item: [STARTS_POOL] entry [index], named in green. A filled map carries map id 0, as recorded. */
    static ItemStack startsItem(int index) {
        String[] entry = TerminalLogic.STARTS_POOL[index];
        ItemStack stack = named(byId(entry[0]), entry[1], GREEN);
        if (stack.is(Items.FILLED_MAP)) {
            stack.set(DataComponents.MAP_ID, new MapId(0));
        }
        return stack;
    }

    /** A Select item: family [family] (DyeColor order), kind 0 glass, 1 clay, 2 wool, 3 dye, named in green. */
    static ItemStack selectItem(int family, int kind) {
        Item item = switch (kind) {
            case 0 -> pick(Items.STAINED_GLASS, family);
            case 1 -> pick(Items.DYED_TERRACOTTA, family);
            case 2 -> pick(Items.WOOL, family);
            default -> dye(family);
        };
        return named(item, TerminalLogic.SELECT_FAMILIES[family][1 + kind], GREEN);
    }

    private static Item pick(ColorCollection<Item> items, int family) {
        return items.asList().get(family);
    }

    /** The 1.8 dye of each family: white, blue, brown and black are bone meal, lapis, cocoa beans and ink sac. */
    private static Item dye(int family) {
        return switch (family) {
            case 0 -> Items.BONE_MEAL;
            case 11 -> Items.LAPIS_LAZULI;
            case 12 -> Items.COCOA_BEANS;
            case 15 -> Items.INK_SAC;
            default -> pick(Items.DYE, family);
        };
    }

    /**
     * A picked Starts-with or Select item: it gains enchantment_glint_override. Items that glint on
     * their own (Enchanted Book, Bottle o' Enchanting, Nether Star) come back without one (TERM-18).
     */
    static ItemStack picked(ItemStack stack) {
        ItemStack out = stack.copy();
        if (!out.hasFoil()) {
            out.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return out;
    }
}
