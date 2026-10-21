package net.rustclear.mod.engine;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.item.ItemStack;

/**
 * [ChestGui] for the boss packages (terminals, relics, reward chests), which live outside this
 * package: the same Hypixel window protocol, with public hooks. A subclass fills [box] in
 * [fillMenu] and answers clicks in [clickSlot].
 */
public abstract class BossMenu extends ChestGui {
    protected BossMenu(int id, Inventory inventory, ServerPlayer player, int rows) {
        super(id, inventory, player, new SimpleContainer(rows * 9), rows);
    }

    /** Opens [menu] titled [title] for [player] next tick (ChestGui.show). */
    public static void open(ServerPlayer player, String title, MenuConstructor menu) {
        ChestGui.show(player, title, menu);
    }

    /** Opens [menu] at once, in this tick (no +1 delay of its own). */
    public static void openNow(ServerPlayer player, String title, MenuConstructor menu) {
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(menu, EngineItems.text(title)));
    }

    /** Puts every item in the menu (called once by the subclass, and after inventory clicks). */
    public abstract void fillMenu();

    /** A click on the menu's slot [slot] with mouse [button] (0 left, 1 right, 2 middle), [shift] held. */
    public abstract void clickSlot(int slot, int button, boolean shift);

    @Override
    final void fill() {
        fillMenu();
    }

    @Override
    final void click(int slot) {
        clickSlot(slot, 0, false);
    }

    @Override
    final void click(int slot, int button, boolean shift) {
        clickSlot(slot, button, shift);
    }

    public ServerPlayer viewer() {
        return player;
    }

    public SimpleContainer box() {
        return box;
    }

    public int menuSize() {
        return size;
    }

    public void setSlot(int slot, ItemStack stack) {
        box.setItem(slot, stack);
    }

    public ItemStack slot(int slot) {
        return box.getItem(slot);
    }

    /** Whether the client gets every slot one by one in the open tick (true), as most Hypixel menus. */
    @Override
    protected boolean perSlotFill() {
        return true;
    }

    /** Ticks after opening that the full resync comes. */
    @Override
    protected int resyncDelay() {
        return 5;
    }

    /** The open tick's sound (ChestGui: lever click BLOCKS 0.5/1.0). */
    @Override
    protected void openSound() {
        super.openSound();
    }

    /** Plays [event] to the viewer only, at their position. */
    public void playToViewer(SoundEvent event, SoundSource source, float volume, float pitch) {
        sound(event, source, volume, pitch);
    }

    public void playToViewer(Holder<SoundEvent> event, SoundSource source, float volume, float pitch) {
        sound(event, source, volume, pitch);
    }

    /** Every server tick while open (broadcastChanges runs once a tick); after this tick's [tickMenu]. */
    @Override
    public void broadcastChanges() {
        tickMenu();
        super.broadcastChanges();
    }

    /** Called once a server tick while the menu is open (Melody's steps, click budgets). */
    protected void tickMenu() {
    }

    /** The menu closed (the player shut it, or it was replaced or closed by the server). */
    @Override
    public void removed(net.minecraft.world.entity.player.Player who) {
        super.removed(who);
        closed();
    }

    protected void closed() {
    }
}
