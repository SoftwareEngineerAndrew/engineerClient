package net.rustclear.mod.engine;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One Ender Chest page (or backpack) opened from Storage, as Hypixel's `Ender Chest (p/N)`
 * (menus-skyblock.md C.2 and C.4): its own window, the controls along the top row (0 Close,
 * 1 Back to Storage, 5-8 First / Previous / Next / Last Page) and the page's items at 9 on.
 *
 * Unlike the button menus (ChestGui) items really move here: the item slots and the player's
 * inventory take vanilla clicks, shift-clicks and drags, and the server accepts what the client
 * predicted without a word. What moves is written straight into the player's storage copy
 * (SkyblockData), so it stays until the next loadout puts storage back. The top row is
 * protected: a click there that isn't a button gets one full resync 3 ticks later. On close the
 * player-inventory slots that changed are sent again on containerId 0, 1 tick later.
 */
final class StoragePage extends ChestMenu {
    private static final int TOP = 9;
    private static final int CLOSE = 0;
    private static final int BACK = 1;
    private static final int FIRST = 5;
    private static final int PREVIOUS = 6;
    private static final int NEXT = 7;
    private static final int LAST = 8;
    /** Ticks after opening that the full resync comes (Hypixel: 3-4 on alpha, 5-6 on main). */
    private static final int RESYNC = 5;
    /** The page arrows' skins (Hypixel's player_head textures, as their texture ids). */
    private static final String FIRST_SKIN = "8af22a97292de001079a5d98a0ae3a82c427172eabc370ed6d4a31c7e3a0024f";
    private static final String PREVIOUS_SKIN = "f247603e42ad5c9387d6df2ee48cd2effccd38e485dc9549be31196a57584217";
    private static final String NEXT_SKIN = "92d61121e2de01af7b42c75f0652da2c0e64ae18ed4ac1856e1c8677c18805ab";
    private static final String LAST_SKIN = "1ceb50d0d79b9fb790a7392660bc296b7ad2f856c5cbe1c566d99cfec191e668";

    private final ServerPlayer player;
    private final SimpleContainer box;
    private final int size;
    /** The storage copy's array this page shows (SkyblockData.stacks), written through on every change. */
    private final ItemStack[] stacks;
    /** The pages this one pages through (indexes into SkyblockData.pages) and where it is in them. */
    private final List<Integer> book;
    private final int position;
    private final boolean ender;
    /** The player's inventory as the open sent it, by window slot, for the resend on close. */
    private final ItemStack[] sent;
    /** Ticks since the open (-1 before it), a protected click's resync countdown, and whether a button is replacing this window. */
    private int age = -1;
    private int resync;
    private boolean leaving;

    /**
     * Opens SkyblockData page [index] for [player] next tick, closing what is open first (Hypixel
     * sends container_close and then open_screen, from Storage and for every page arrow).
     */
    static void open(ServerPlayer player, int index) {
        List<SkyblockImport.Page> pages = SkyblockData.pages(player);
        if (index < 0 || index >= pages.size()) {
            return;
        }
        boolean ender = pages.get(index).title.startsWith(StorageMenu.ENDER + " ");
        List<Integer> book = SkyblockData.book(player, ender ? StorageMenu.ENDER : StorageMenu.BACKPACK);
        int position = Math.max(0, book.indexOf(index));
        // OPEN: a backpack window's title (never recorded); Rustclear's page title stands in.
        String title = ender ? "Ender Chest (" + (position + 1) + "/" + book.size() + ")" : pages.get(index).title;
        ChestGui.show(player, title, (id, inventory, p) -> new StoragePage(id, inventory, player, index, book, position, ender),
            true);
    }

    private StoragePage(int id, Inventory inventory, ServerPlayer player, int index, List<Integer> book, int position,
                        boolean ender) {
        this(id, inventory, player, stacksOf(player, index), book, position, ender);
    }

    /** Page [index]'s items, or none when the storage went (a new import) between the click and the open. */
    private static ItemStack[] stacksOf(ServerPlayer player, int index) {
        ItemStack[] stacks = SkyblockData.stacks(player, index);
        return stacks == null ? new ItemStack[0] : stacks;
    }

    private StoragePage(int id, Inventory inventory, ServerPlayer player, ItemStack[] stacks, List<Integer> book, int position,
                        boolean ender) {
        this(id, inventory, player, stacks, book, position, ender, rows(stacks.length));
    }

    private StoragePage(int id, Inventory inventory, ServerPlayer player, ItemStack[] stacks, List<Integer> book, int position,
                        boolean ender, int rows) {
        super(type(rows), id, inventory, new Cells(rows * 9), rows);
        this.player = player;
        this.box = (SimpleContainer) getContainer();
        this.size = rows * 9;
        this.stacks = stacks;
        this.book = book;
        this.position = position;
        this.ender = ender;
        top();
        for (int i = TOP; i < size; i++) {
            int item = i - TOP;
            box.setItem(i, item < stacks.length ? stacks[item] : EngineItems.filler());
        }
        // The top row, the cells past a short page's end and the 9th hotbar slot (the SkyBlock
        // Menu's item) can't be taken or filled.
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            boolean mapSlot = s.container == inventory && s.getContainerSlot() == Kit.MAP_SLOT;
            if (i < TOP || (i < size && i - TOP >= stacks.length) || mapSlot) {
                Slot fixed = new Fixed(s.container, s.getContainerSlot(), s.x, s.y);
                fixed.index = i;
                slots.set(i, fixed);
            }
        }
        ((Cells) box).changed = this::writeBack;
        sent = new ItemStack[slots.size()];
    }

    /**
     * Rows for a page of [items] slots: the top row and as many rows as the items fill. A full
     * 45-slot page is 9x6.
     * OPEN: why Hypixel's page 7/7 was 9x2 (c7cec1 n10914): sized by its unlocked slots, as here, or by its content?
     */
    private static int rows(int items) {
        return Math.max(2, Math.min(6, 1 + (items + 8) / 9));
    }

    private static MenuType<ChestMenu> type(int rows) {
        return switch (rows) {
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /** The top row with all four arrows; the ones that go nowhere turn to filler 1 tick after the open. */
    private void top() {
        for (int i = 0; i < TOP; i++) {
            box.setItem(i, EngineItems.filler());
        }
        box.setItem(CLOSE, ChestGui.close());
        // OPEN: what Back (1) does is unrecorded; it reopens Storage here.
        box.setItem(BACK, EngineItems.named(Items.ARROW, "§eBack"));
        box.setItem(FIRST, arrow(FIRST_SKIN, "§e« First Page"));
        box.setItem(PREVIOUS, arrow(PREVIOUS_SKIN, "§a← Previous Page"));
        box.setItem(NEXT, arrow(NEXT_SKIN, "§aNext Page →"));
        box.setItem(LAST, arrow(LAST_SKIN, "§eLast Page »"));
    }

    private static ItemStack arrow(String skin, String name) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + skin + "\"}}}";
        return EngineItems.head(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)), name);
    }

    private boolean hasPrevious() {
        return position > 0;
    }

    private boolean hasNext() {
        return position + 1 < book.size();
    }

    /** The disabled arrows become filler (Hypixel: 5-6 on the first page, 7-8 on the last, 1 tick after the open). */
    private void disableArrows() {
        if (!hasPrevious()) {
            box.setItem(FIRST, EngineItems.filler());
            box.setItem(PREVIOUS, EngineItems.filler());
        }
        if (!hasNext()) {
            box.setItem(NEXT, EngineItems.filler());
            box.setItem(LAST, EngineItems.filler());
        }
    }

    /** The item slots into the storage copy (the stacks themselves, so a count change is there already). */
    private void writeBack() {
        for (int i = 0; i < stacks.length && TOP + i < size; i++) {
            stacks[i] = box.getItem(TOP + i);
        }
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player who) {
        if (slot >= 0 && slot < TOP && input != ContainerInput.QUICK_CRAFT) {
            if (input == ContainerInput.PICKUP && button(slot)) {
                return;
            }
            resync = 3;
            return;
        }
        if (slot >= 0 && slot < slots.size() && slots.get(slot) instanceof Fixed && input != ContainerInput.QUICK_CRAFT) {
            Slot s = slots.get(slot);
            if (s.container == player.getInventory() && s.getContainerSlot() == Kit.MAP_SLOT) {
                // The 9th slot's item opens the SkyBlock Menu from anywhere (as in ChestGui).
                leaving = true;
                SkyblockMenu.open(player);
                return;
            }
            resync = 3;
            return;
        }
        if (input == ContainerInput.SWAP && button == Kit.MAP_SLOT) {
            // A number key would swap the SkyBlock Menu's item into the page.
            resync = 3;
            return;
        }
        super.clicked(slot, button, input, who);
    }

    /** A top-row button: true when it did something. */
    private boolean button(int slot) {
        if (slot == CLOSE) {
            player.closeContainer();
            return true;
        }
        int to = switch (slot) {
            case FIRST -> hasPrevious() ? 0 : -1;
            case PREVIOUS -> hasPrevious() ? position - 1 : -1;
            case NEXT -> hasNext() ? position + 1 : -1;
            case LAST -> hasNext() ? book.size() - 1 : -1;
            default -> -1;
        };
        if (slot == BACK) {
            leaving = true;
            StorageMenu.open(player);
            return true;
        }
        if (to < 0) {
            return false;
        }
        leaving = true;
        open(player, book.get(to));
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player who, int index) {
        Slot slot = slots.get(index);
        if (slot instanceof Fixed || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index < size) {
            // Page to inventory, from the end as vanilla's chests do.
            if (!moveItemStackTo(stack, size, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, TOP, TOP + Math.min(stacks.length, size - TOP), false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return before;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack carried, Slot slot) {
        return !(slot instanceof Fixed);
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return !(slot instanceof Fixed);
    }

    @Override
    public void sendAllDataToRemote() {
        if (age >= 0) {
            super.sendAllDataToRemote();
            return;
        }
        // The open (C.2): the top row, then only the non-empty slots one by one in ascending order
        // with rising state ids (empty ones are skipped, not sent), then the open sound.
        age = 0;
        for (int i = 0; i < slots.size(); i++) {
            ItemStack item = slots.get(i).getItem();
            setRemoteSlot(i, item.copy());
            if (i >= size) {
                sent[i] = item.copy();
            }
            if (i < TOP || !item.isEmpty()) {
                player.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), i, item.copy()));
            }
        }
        if (ender) {
            // block.ender_chest.open BLOCKS 0.5 at pitch k/63, k 3..62, at the centre of the player's block.
            player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.ENDER_CHEST_OPEN),
                SoundSource.BLOCKS, player.getBlockX() + 0.5, player.getBlockY() + 0.5, player.getBlockZ() + 0.5, 0.5f,
                (3 + player.getRandom().nextInt(60)) / 63f, player.getRandom().nextLong()));
        }
        // OPEN: a backpack's open sound (never recorded); Rustclear's pages had none.
    }

    @Override
    public void broadcastChanges() {
        if (leaving) {
            // A button opens the next window next tick; Hypixel doesn't put the clicked slot right first.
            return;
        }
        if (age >= 0 && ++age == 1) {
            disableArrows();
        }
        if (age == RESYNC) {
            super.sendAllDataToRemote();
            return;
        }
        if (resync > 0) {
            if (--resync == 0) {
                super.sendAllDataToRemote();
            }
            return;
        }
        super.broadcastChanges();
    }

    @Override
    public void removed(Player who) {
        super.removed(who);
        writeBack();
        // The inventory menu takes this window's remote slots on close (InventoryMenu.transferState);
        // giving it back what the open sent makes its next broadcast send every inventory slot that
        // changed while the page was open, on containerId 0, as Hypixel does after a close.
        for (int i = size; i < slots.size(); i++) {
            if (sent[i] != null) {
                setRemoteSlot(i, sent[i]);
            }
        }
    }

    @Override
    public boolean stillValid(Player who) {
        return true;
    }

    /** The window's slots; [changed] runs on every change, to write the items back. */
    private static final class Cells extends SimpleContainer {
        Runnable changed;

        Cells(int size) {
            super(size);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (changed != null) {
                changed.run();
            }
        }
    }

    /** A slot nothing can be taken from or put in. */
    private static final class Fixed extends Slot {
        Fixed(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}
