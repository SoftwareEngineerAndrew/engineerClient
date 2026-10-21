package net.rustclear.mod.engine;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The dungeon item categories, as /rcitem's chest tabs and the Kit editor's picker show them. */
public enum ItemCategory {
    ALL("All", "All items", Items.NETHER_STAR),
    WEAPONS("Weapons", "Weapons", Items.DIAMOND_SWORD),
    BOWS("Bows", "Bows", Items.BOW),
    ARMOR("Armor", "Armor", Items.DIAMOND_CHESTPLATE),
    EQUIPMENT("Equipment", "Equipment", Items.LEAD),
    OTHER("Other", "Other items", Items.PAPER);

    /** The short name, for a chip. */
    public final String label;
    public final String title;
    final Item icon;

    ItemCategory(String label, String title, Item icon) {
        this.label = label;
        this.title = title;
        this.icon = icon;
    }

    public boolean has(ItemDefs.Def def) {
        return switch (this) {
            case ALL -> true;
            case WEAPONS -> def.type.equals("SWORD") || def.type.equals("FISHING ROD") || def.type.equals("PICKAXE");
            case BOWS -> def.type.equals("BOW") || def.type.equals("SHORTBOW");
            case ARMOR -> def.isArmor();
            case EQUIPMENT -> switch (def.type) {
                case "BELT", "NECKLACE", "CLOAK", "GLOVES", "ACCESSORY" -> true;
                default -> false;
            };
            case OTHER -> def.type.equals("ITEM") || def.type.equals("OTHER");
        };
    }
}
