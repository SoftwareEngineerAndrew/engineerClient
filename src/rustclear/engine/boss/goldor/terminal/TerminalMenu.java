package net.rustclear.mod.engine.boss.goldor.terminal;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.rustclear.mod.engine.BossMenu;

/**
 * One open terminal window (boss-p3-goldor.md §7, menus-ui.md §2). The open is BossMenu's Hypixel
 * protocol unchanged: the lever click (BLOCKS 0.5/1.0) and every slot one by one in the open tick,
 * the full resync 5 ticks later (all 292 recorded terminal windows had their puzzle in the open tick).
 *
 * A click is "counted" only while fewer than 5 have counted in the last 10 ticks (the best fit of
 * the tested limits: exact pling count in 81 of 214 windows); past that it gets no answer at all.
 * A counted click that does something gets the pling (BLOCKS 8 at 4.047619) at once and its slot
 * update the tick after (1250 of 1347 plings 0-1 tick after the click, the set_slot a tick later).
 * A click that does nothing gets nothing. The finishing click plays its pling (173 of 175 own
 * finishes; Melody's last lock does not, 34 of 35), closes the window, runs the station's
 * completion (chat, title, pling), then closes window 0, all in one tick (TERM-15, 294 of 294).
 */
abstract class TerminalMenu extends BossMenu {
    /** Counted clicks allowed per [WINDOW] ticks. */
    static final int LIMIT = 5;
    static final int WINDOW = 10;

    final Object key;
    final Random random;
    private final Runnable onSolved;
    private final int openTick;
    /** Server ticks of the clicks that counted, oldest first. */
    private final ArrayDeque<Integer> counted = new ArrayDeque<>();
    /** Slots whose change waits for the tick after the click that made it. */
    private final Set<Integer> held = new LinkedHashSet<>();
    private int answeredTick = Integer.MIN_VALUE;
    private int lastTick = Integer.MIN_VALUE;
    private boolean finished;

    TerminalMenu(int id, Inventory inventory, ServerPlayer player, int rows, Object key, Random random, Runnable onSolved) {
        super(id, inventory, player, rows);
        this.key = key;
        this.random = random;
        this.onSolved = onSolved;
        this.openTick = now();
        Terminals.opened(this);
    }

    // ---------------------------------------------------------------- what each terminal defines

    /** What slot [slot] shows now (the filler unless the puzzle draws there). */
    abstract ItemStack render(int slot);

    /**
     * A counted click on slot [slot]: the slots it changed (maybe none, as a Melody lock), or null if
     * it does nothing (no pling). [back] is a right click (Rubix steps back).
     */
    abstract int[] press(int slot, boolean back);

    abstract boolean solved();

    /** Called once a tick with the ticks since the open; returns the slots it changed (Melody's steps). */
    int[] step(int age) {
        return null;
    }

    /** Whether the finishing click plays its own pling (all but Melody). */
    boolean plingOnFinish() {
        return true;
    }

    // ---------------------------------------------------------------- the window

    int now() {
        return ((ServerLevel) viewer().level()).getServer().getTickCount();
    }

    @Override
    public void fillMenu() {
        for (int i = 0; i < menuSize(); i++) {
            if (!held.contains(i)) {
                setSlot(i, render(i));
            }
        }
    }

    @Override
    protected void tickMenu() {
        int tick = now();
        if (tick > answeredTick && !held.isEmpty()) {
            for (int slot : held) {
                setSlot(slot, render(slot));
            }
            held.clear();
        }
        if (tick == lastTick || finished) {
            return;
        }
        lastTick = tick;
        int[] changed = step(tick - openTick);
        if (changed != null) {
            for (int slot : changed) {
                if (!held.contains(slot)) {
                    setSlot(slot, render(slot));
                }
            }
        }
    }

    /** Clicks on the window go to [handle] with the click type; clicks on the inventory below stay ChestGui's. */
    @Override
    public void clicked(int slot, int button, ContainerInput input, Player who) {
        if (slot >= 0 && slot < menuSize()) {
            // OPEN: a right click steps Rubix back as a PICKUP or shift click; number keys, Q and
            // double clicks are taken as plain (forward) clicks.
            handle(slot, button == 1 && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE));
            return;
        }
        super.clicked(slot, button, input, who);
    }

    @Override
    public void clickSlot(int slot, int button, boolean shift) {
        handle(slot, button == 1);
    }

    /** Whether a click now would count (fewer than [LIMIT] counted in the last [WINDOW] ticks). */
    boolean canCount() {
        int tick = now();
        while (!counted.isEmpty() && counted.peekFirst() <= tick - WINDOW) {
            counted.pollFirst();
        }
        return counted.size() < LIMIT;
    }

    /** A click on the window's slot [slot]. */
    void handle(int slot, boolean back) {
        if (finished || viewer().containerMenu != this) {
            return;
        }
        // OPEN: every click on the window uses up the limit, wrong ones too (Odin's GUI blocks wrong
        // clicks, so the recordings can't tell).
        if (!canCount()) {
            return;
        }
        int tick = now();
        counted.addLast(tick);
        int[] changed = press(slot, back);
        if (changed == null) {
            return;
        }
        if (solved()) {
            finish();
            return;
        }
        pling();
        answeredTick = tick;
        for (int s : changed) {
            held.add(s);
        }
    }

    private void pling() {
        playToViewer(SoundEvents.NOTE_BLOCK_PLING, SoundSource.BLOCKS, 8f, 4.047619f);
    }

    private void finish() {
        finished = true;
        ServerPlayer player = viewer();
        if (plingOnFinish()) {
            pling();
        }
        player.closeContainer();
        onSolved.run();
        player.connection.send(new ClientboundContainerClosePacket(0));
    }

    @Override
    protected void closed() {
        Terminals.closed(this);
    }

}
