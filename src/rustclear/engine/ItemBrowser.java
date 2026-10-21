package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * /rcitem's chest: every dungeon item, 45 a page, with category tabs along the bottom. Clicking one
 * puts a copy (a dungeon's best: see ItemCommand) in your inventory; nothing in the chest moves.
 */
final class ItemBrowser extends ChestMenu {
    private static final int ROWS = 6;
    private static final int PER_PAGE = 45;
    private static final int PREVIOUS = 45;
    private static final int CLOSE = 52;
    private static final int NEXT = 53;

    private final ServerPlayer player;
    private final SimpleContainer box;
    private ItemCategory category = ItemCategory.ALL;
    private int page;
    private final List<ItemDefs.Def> shown = new ArrayList<>();

    static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ItemBrowser(id, inventory, player, new SimpleContainer(ROWS * 9)),
            EngineItems.text("Dungeon Items")));
    }

    private ItemBrowser(int id, Inventory inventory, ServerPlayer player, SimpleContainer box) {
        super(MenuType.GENERIC_9x6, id, inventory, box, ROWS);
        this.player = player;
        this.box = box;
        fill();
    }

    private void fill() {
        shown.clear();
        for (ItemDefs.Def def : ItemDefs.get().all()) {
            if (category.has(def)) {
                shown.add(def);
            }
        }
        int pages = Math.max(1, (shown.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < box.getContainerSize(); i++) {
            box.setItem(i, i < PER_PAGE ? ItemStack.EMPTY : EngineItems.filler());
        }
        for (int i = 0; i < PER_PAGE && page * PER_PAGE + i < shown.size(); i++) {
            box.setItem(i, ItemCommand.stack(player, shown.get(page * PER_PAGE + i), null, null, null, null));
        }
        if (page > 0) {
            box.setItem(PREVIOUS, EngineItems.named(Items.ARROW, "§aPrevious page", "§7Page " + page));
        }
        if (page < pages - 1) {
            box.setItem(NEXT, EngineItems.named(Items.ARROW, "§aNext page", "§7Page " + (page + 2)));
        }
        ItemCategory[] categories = ItemCategory.values();
        for (int i = 0; i < categories.length; i++) {
            ItemCategory c = categories[i];
            ItemStack tab = EngineItems.named(c.icon, (c == category ? "§a" : "§e") + c.title,
                c == category ? "§7Showing page " + (page + 1) + " of " + pages : "§eClick to show!");
            if (c == category) {
                tab.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            box.setItem(PREVIOUS + 1 + i, tab);
        }
        box.setItem(CLOSE, EngineItems.named(Items.BARRIER, "§cClose", "§7Click an item to take a copy.",
            "§7Stars, tier and quality: /rcitem give <id> ..."));
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player who) {
        if (slot >= 0 && slot < PER_PAGE) {
            int index = page * PER_PAGE + slot;
            if (index < shown.size()) {
                ItemStack copy = ItemCommand.stack(player, shown.get(index), null, null, null, null);
                if (!player.getInventory().add(copy)) {
                    player.sendSystemMessage(EngineItems.text("§cYour inventory is full."));
                }
            }
        } else if (slot == PREVIOUS && page > 0) {
            page--;
            fill();
        } else if (slot == NEXT) {
            page++;
            fill();
        } else if (slot > PREVIOUS && slot < CLOSE) {
            category = ItemCategory.values()[slot - PREVIOUS - 1];
            page = 0;
            fill();
        } else if (slot == CLOSE) {
            player.closeContainer();
            return;
        }
        // Nothing moves: whatever the client guessed, it gets the real slots (and an empty cursor) back.
        sendAllDataToRemote();
        player.inventoryMenu.sendAllDataToRemote();
    }

    @Override
    public ItemStack quickMoveStack(Player who, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player who) {
        return true;
    }
}
