package net.rustclear.mod.engine;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Hypixel's Storage (menus-skyblock.md C.1): the Ender Chest pages at 9-17 (purple panes counting
 * the page, red Locked Page past the profile's unlocked ones) and the backpack slots at 27-44, as
 * the player's Hypixel profile has them (SkyblockData). A page opens as its own window,
 * StoragePage, where items really move. Only items the Rustclear world has are there.
 */
final class StorageMenu extends ChestGui {
    static final String TITLE = "Storage";
    static final String ENDER = "Ender Chest Page";
    static final String BACKPACK = "Backpack Slot";
    private static final int ENDER_CHEST = 4;
    private static final int ENDER_ROW = 9;
    private static final int BACKPACKS_BUTTON = 22;
    private static final int BACKPACK_ROW = 27;
    private static final int ENDER_PAGES = 9;
    private static final int BACKPACKS = 18;

    /** Ticks until a full resync after a click that did nothing (Hypixel: set_content 4 ticks later); 0: none due. */
    private int resync;
    /** A page was clicked: this window is about to go, so it sends nothing more. */
    private boolean leaving;

    /** Opens Storage next tick, as a new window over the SkyBlock Menu (no close first). */
    static void open(ServerPlayer player) {
        ChestGui.show(player, TITLE, (id, inventory, p) -> new StorageMenu(id, inventory, player));
    }

    private StorageMenu(int id, Inventory inventory, ServerPlayer player) {
        super(id, inventory, player, new SimpleContainer(SIZE));
        // Made inside openMenu, so this goes out before open_screen, as Hypixel's does: chest.open
        // BLOCKS 1.0 at a random pitch k/63 (seen k 6..89). The lever click follows with the fill.
        sound(SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, (6 + player.getRandom().nextInt(84)) / 63f);
        fill();
    }

    @Override
    void fill() {
        frame(true);
        if (SkyblockData.pages(player).isEmpty()) {
            set(BACKPACKS_BUTTON, EngineItems.named(Items.BARRIER, "§cNo storage read", "§7Your Ender Chest and backpacks come",
                "§7from your Hypixel profile, in a party", "§7playing §eeach player's Hypixel stats§7.", "",
                "§7Your profile's §eInventory API §7must", "§7be on in Hypixel's settings."));
            return;
        }
        set(ENDER_CHEST, EngineItems.named(Items.ENDER_CHEST, "§aEnder Chest", "§7Store global items you can access",
            "§7anywhere in your ender chest."));
        for (int n = 1; n <= ENDER_PAGES; n++) {
            set(ENDER_ROW + n - 1, SkyblockData.find(player, ENDER, n) >= 0 ? page(n) : locked());
        }
        set(BACKPACKS_BUTTON, EngineItems.named(Items.CHEST, "§aBackpacks", "§7Place backpack items in these slots",
            "§7to use them as additional storage", "§7that can be accessed anywhere."));
        for (int n = 1; n <= BACKPACKS; n++) {
            int index = SkyblockData.find(player, BACKPACK, n);
            set(BACKPACK_ROW + n - 1, index >= 0 ? backpack(index, n) : emptyBackpack(n));
        }
    }

    private static ItemStack page(int n) {
        ItemStack stack = EngineItems.named(Items.STAINED_GLASS_PANE.purple(), "§aEnder Chest Page " + n, "",
            "§8Also accessible via /enderchest " + n, "", "§eLeft-click to open!", "§eRight-click to change icon!");
        stack.setCount(n);
        return stack;
    }

    private static ItemStack locked() {
        return EngineItems.named(Items.STAINED_GLASS_PANE.red(), "§cLocked Page", "§7Unlock more Ender Chest pages in",
            "§7the community shop!");
    }

    private static ItemStack emptyBackpack(int n) {
        ItemStack stack = EngineItems.named(Items.STAINED_GLASS_PANE.brown(), "§eEmpty Backpack Slot " + n, "",
            "§eLeft-click a backpack item on this", "§eslot to place it!");
        stack.setCount(n);
        return stack;
    }

    // OPEN: a filled backpack slot's icon and lore (never recorded: the account had no backpacks).
    // Rustclear's chest with the item count stands in.
    private ItemStack backpack(int index, int n) {
        ItemStack[] stacks = SkyblockData.stacks(player, index);
        int used = 0;
        for (ItemStack s : stacks) {
            if (!s.isEmpty()) {
                used++;
            }
        }
        return EngineItems.named(Items.CHEST, "§6Backpack Slot " + n, "§7Items: §a" + used + "§7/§a" + stacks.length, "",
            "§eClick to open!");
    }

    @Override
    void click(int slot) {
        click(slot, 0, false);
    }

    @Override
    void click(int slot, int button, boolean shift) {
        if (isBack(slot)) {
            SkyblockMenu.open(player);
            return;
        }
        // OPEN: a right-click on a page ("Right-click to change icon!"), a click on a Locked Page, on
        // the Ender Chest (4) or Backpacks (22) item, and on an empty backpack slot were never recorded;
        // they do nothing here but resync like filler.
        int index = -1;
        if (slot >= ENDER_ROW && slot < ENDER_ROW + ENDER_PAGES && button == 0 && !shift) {
            index = SkyblockData.find(player, ENDER, slot - ENDER_ROW + 1);
        } else if (slot >= BACKPACK_ROW && slot < BACKPACK_ROW + BACKPACKS) {
            index = SkyblockData.find(player, BACKPACK, slot - BACKPACK_ROW + 1);
        }
        if (index >= 0) {
            leaving = true;
            StoragePage.open(player, index);
            return;
        }
        // Hypixel answers a filler click with one full set_content 4 ticks later (C.1).
        resync = 4;
    }

    @Override
    public void broadcastChanges() {
        if (leaving) {
            // Hypixel closes Storage and opens the page without putting right the picked-up pane.
            return;
        }
        if (resync > 0) {
            if (--resync == 0) {
                sendAllDataToRemote();
            }
            return;
        }
        super.broadcastChanges();
    }
}
