package net.rustclear.mod.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A chest menu the way Hypixel's work (the SkyBlock Menu and what it opens): nothing in it can be
 * taken or moved, a click on one of its items is a button ([click]), and a click on the player's
 * own inventory below goes to [inventoryClick]. A click gets no reply of its own: vanilla's
 * change tracking puts right only the slots the client guessed wrong (menus-skyblock.md §0: a
 * left-click on filler gets nothing, a right-click a set_slot of that slot).
 *
 * It opens with Hypixel's packets (menus-ui.md §0): the open sound (lever click BLOCKS 0.5/1.0 for
 * most menus) in the open tick, every slot sent one by one in ascending order with rising state
 * ids, then a full resync 5 ticks later. Menus open through [show], 1 tick after the click or use
 * that asked for them (Hypixel: +1 in 180 of 248 navigations), as a new window that replaces the
 * old one without a close packet; bag windows send the close first ([closeFirst]).
 */
abstract class ChestGui extends ChestMenu {
    static final int ROWS = 6;
    static final int SIZE = ROWS * 9;
    static final int BACK = 48;
    static final int CLOSE = 49;

    /** Menus waiting for the next tick to open, by player. */
    private static final Map<ServerPlayer, Runnable> PENDING = new LinkedHashMap<>();

    final ServerPlayer player;
    final SimpleContainer box;
    /** This menu's slot count, rows, and Close / Go Back buttons (the bottom row's middle and the slot before it). */
    final int size;
    final int rows;
    final int closeSlot;
    final int backSlot;
    /** Ticks since the menu opened (broadcastChanges runs every tick), or -1 before it has. */
    private int age = -1;

    ChestGui(int id, Inventory inventory, ServerPlayer player, SimpleContainer box) {
        this(id, inventory, player, box, ROWS);
    }

    /**
     * A [rows]-row menu (1 to 6). Close sits in the middle of the bottom row and Go Back just left
     * of it: 48/49 in 9x6, 39/40 in 9x5, 30/31 in 9x4, 12/13 in 9x2 (menus-skyblock.md §0).
     */
    ChestGui(int id, Inventory inventory, ServerPlayer player, SimpleContainer box, int rows) {
        super(type(rows), id, inventory, box, rows);
        this.player = player;
        this.box = box;
        this.rows = rows;
        this.size = rows * 9;
        this.closeSlot = size - 5;
        this.backSlot = size - 6;
    }

    private static MenuType<ChestMenu> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /**
     * Opens a menu titled [title] for [player] next tick. A menu of ours already open is replaced
     * without a close packet (Hypixel's sub-menus); with [closeFirst] the server closes it first,
     * as Hypixel does for bag windows (menus-skyblock.md §0).
     */
    static void show(ServerPlayer player, String title, MenuConstructor menu, boolean closeFirst) {
        PENDING.put(player, () -> {
            if (player.isRemoved()) {
                return;
            }
            if (player.containerMenu != player.inventoryMenu) {
                if (closeFirst || !(player.containerMenu instanceof ChestGui)) {
                    player.closeContainer();
                } else {
                    player.doCloseContainer();
                }
            }
            player.openMenu(new SimpleMenuProvider(menu, EngineItems.text(title)));
        });
    }

    static void show(ServerPlayer player, String title, MenuConstructor menu) {
        show(player, title, menu, false);
    }

    /** Opens the menus asked for last tick (EngineServer's END_SERVER_TICK). */
    static void tick() {
        if (PENDING.isEmpty()) {
            return;
        }
        Runnable[] due = PENDING.values().toArray(Runnable[]::new);
        PENDING.clear();
        for (Runnable open : due) {
            open.run();
        }
    }

    /** Puts every item in the chest. */
    abstract void fill();

    /** A click on the chest's slot [slot]. */
    abstract void click(int slot);

    /** A click on the chest's slot [slot] with mouse [button] (0 left, 1 right), [shift] held. */
    void click(int slot, int button, boolean shift) {
        click(slot);
    }

    /** A click on the player's inventory slot [index] (0-8 the hotbar, 9-35 the bag). */
    void inventoryClick(int index) {
    }

    /** Black panes everywhere, then Close, and Go Back "To [parent]" unless [parent] is null. */
    void frame(String parent) {
        for (int i = 0; i < size; i++) {
            box.setItem(i, EngineItems.filler());
        }
        box.setItem(closeSlot, close());
        if (parent != null) {
            box.setItem(backSlot, back(parent));
        }
    }

    /** [frame] with Go Back to the SkyBlock Menu when [back]. */
    void frame(boolean back) {
        frame(back ? SkyblockMenu.TITLE : null);
    }

    static ItemStack close() {
        return EngineItems.named(Items.BARRIER, "§cClose");
    }

    static ItemStack back(String parent) {
        return EngineItems.named(Items.ARROW, "§aGo Back", "§7To " + parent);
    }

    void set(int slot, ItemStack stack) {
        box.setItem(slot, stack);
    }

    /** Whether [slot] holds this menu's Go Back arrow. */
    boolean isBack(int slot) {
        return slot == backSlot && box.getItem(slot).is(Items.ARROW);
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player who) {
        if (slot >= 0 && slot < size) {
            if (slot == closeSlot && box.getItem(slot).is(Items.BARRIER)) {
                player.closeContainer();
                return;
            }
            click(slot, button, input == ContainerInput.QUICK_MOVE);
        } else if (slot >= size && slot < slots.size()) {
            Slot s = slots.get(slot);
            if (s.container == player.getInventory() && s.getContainerSlot() == Kit.MAP_SLOT && !(this instanceof SkyblockMenu)) {
                // The 9th slot's item opens the SkyBlock Menu from anywhere.
                SkyblockMenu.open(player);
                return;
            }
            if (s.container == player.getInventory()) {
                inventoryClick(s.getContainerSlot());
                fill();
            }
        }
    }

    /** Hypixel sends a menu slot by slot when it opens; Haunt's "Teleport to Player" opens empty instead. */
    boolean perSlotFill() {
        return true;
    }

    /** Whether the per-slot fill skips [slot] (Hypixel leaves some empty until the resync). */
    boolean lateSlot(int slot) {
        return false;
    }

    /** Ticks after opening that the full resync comes (5; Haunt's menu 2). */
    int resyncDelay() {
        return 5;
    }

    /** The sound in the open tick: the lever click on most menus (menus-skyblock.md §0 lists the others). */
    void openSound() {
        sound(SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.5f, 1f);
    }

    void sound(SoundEvent event, SoundSource source, float volume, float pitch) {
        sound(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event), source, volume, pitch);
    }

    void sound(Holder<SoundEvent> event, SoundSource source, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(event, source, player.getX(), player.getY(), player.getZ(),
            volume, pitch, player.getRandom().nextLong()));
    }

    /** [event] sent inline rather than by registry id, as Hypixel sends page_turn and lodestone_compass. */
    void inlineSound(SoundEvent event, SoundSource source, float volume, float pitch) {
        sound(Holder.direct(SoundEvent.createVariableRangeEvent(event.location())), source, volume, pitch);
    }

    /** The bag and sack windows' saddle sound: NEUTRAL 1.0, a random pitch from 0 to about 1.92 (Hypixel's k/63 steps). */
    void saddleSound() {
        sound(SoundEvents.HORSE_SADDLE, SoundSource.NEUTRAL, 1f, player.getRandom().nextInt(64) / 63f * 1.92f);
    }

    @Override
    public void sendAllDataToRemote() {
        if (age >= 0) {
            super.sendAllDataToRemote();
            return;
        }
        // The open: one set_slot per slot (the menu's, then the inventory's), each with the next state id.
        age = 0;
        openSound();
        for (int i = 0; i < slots.size(); i++) {
            ItemStack item = slots.get(i).getItem();
            boolean late = i < size && lateSlot(i);
            setRemoteSlot(i, late ? ItemStack.EMPTY : item.copy());
            if (perSlotFill() && !late) {
                player.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), i, item.copy()));
            }
        }
    }

    @Override
    public void broadcastChanges() {
        if (age >= 0 && ++age == resyncDelay()) {
            super.sendAllDataToRemote();
        }
        if (age >= 0 && age < resyncDelay()) {
            // Slots held back from the fill stay as the client has them until the resync.
            for (int i = 0; i < size; i++) {
                if (lateSlot(i)) {
                    setRemoteSlot(i, slots.get(i).getItem().copy());
                }
            }
        }
        super.broadcastChanges();
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
