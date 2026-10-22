package net.rustclear.mod.engine;

import net.minecraft.world.item.ItemStack;

/**
 * The engine's stack for a SkyBlock item id, for code outside the engine (the P3 Sim) that wants the item to look as
 * the engine builds it: its vanilla base item, player-head skin, dye and glint, all without Hypixel's resource pack.
 * The engine's own items come first (EngineItem: the Superboom is TNT, the Bonzo's Staff a blaze rod), then the
 * ghost kit, the SkyBlock Menu star, then the item list (items.json.gz and items_extra.json) at its best.
 */
public final class SkyblockItems {
    private SkyblockItems() {
    }

    /** A fresh stack for SkyBlock id [id] as the engine makes it, or null when the engine doesn't know the id. */
    public static ItemStack stack(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (id.equals("SKYBLOCK_MENU")) {
            return SkyblockMenu.star();
        }
        ItemStack ghost = Ghosts.kitStack(id);
        if (ghost != null) {
            return ghost;
        }
        if (KitItems.engineItem(id) == null && KitItems.def(id) == null) {
            return null;
        }
        return KitItems.stack(KitItems.bestRecorded(id), ItemStats.MAX_CATA, false);
    }
}
