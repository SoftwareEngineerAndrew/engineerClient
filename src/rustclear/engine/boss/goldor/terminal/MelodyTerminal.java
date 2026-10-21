package net.rustclear.mod.engine.boss.goldor.terminal;

import java.util.Random;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.rustclear.mod.engine.BossMenu;

/**
 * `Click the button on time!` (9x6), post-update (21 of 21 windows): 3 lanes in rows 1-3, each 5
 * panes in columns 1-5 (white while inactive; red with one lime in the active lane) and its button
 * in column 7 ("Lock In Slot" lime terracotta on the active lane, "Row Not Active" red terracotta
 * on the others). The magenta target sits in rows 0 and 4 (slots c and 36 + c) on black track panes
 * that keep their tooltip; everything else is the filler. The lime steps every 10 ticks from the
 * open (369 of 373 gaps; first step 10 after the open, 46 of 49).
 *
 * A lock with the lime on the magenta column gets the pling; the next lane takes over at the next
 * step (the whole redraw in that tick), the last lock finishes at once without a pling of its own.
 * A wrong lock gets nothing but stalls the lime 2 steps (+20 ticks); more locks in the same step
 * after a good one are ignored.
 */
final class MelodyTerminal extends TerminalMenu {
    static final String TITLE = "Click the button on time!";

    private final TerminalLogic.Melody melody;

    static void open(ServerPlayer player, Object key, Random random, Runnable onSolved) {
        BossMenu.openNow(player, TITLE, (id, inventory, p) -> new MelodyTerminal(id, inventory, player, key, random, onSolved));
    }

    private MelodyTerminal(int id, Inventory inventory, ServerPlayer player, Object key, Random random, Runnable onSolved) {
        super(id, inventory, player, 6, key, random, onSolved);
        this.melody = new TerminalLogic.Melody(random);
        fillMenu();
    }

    /** The lane (0-2) whose button is [slot], or -1. */
    private static int buttonLane(int slot) {
        int row = slot / 9;
        return slot % 9 == 7 && row >= 1 && row <= TerminalLogic.Melody.LANES ? row - 1 : -1;
    }

    @Override
    ItemStack render(int slot) {
        int row = slot / 9;
        int col = slot % 9;
        if ((row == 0 || row == TerminalLogic.Melody.LANES + 1) && col >= 1 && col <= 5) {
            return col == melody.target ? TerminalItems.pane(Items.STAINED_GLASS_PANE.magenta()) : TerminalItems.trackPane();
        }
        if (row >= 1 && row <= TerminalLogic.Melody.LANES) {
            int lane = row - 1;
            if (col >= 1 && col <= 5) {
                if (lane != melody.lane) {
                    return TerminalItems.pane(Items.STAINED_GLASS_PANE.white());
                }
                return TerminalItems.pane(col == melody.lime ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.red());
            }
            if (col == 7) {
                return TerminalItems.melodyButton(lane == melody.lane);
            }
        }
        return TerminalItems.filler();
    }

    @Override
    int[] step(int age) {
        if (age <= 0 || age % 10 != 0 || melody.solved()) {
            return null;
        }
        melody.step(random);
        // Redraw the track, lanes and buttons; only the slots that differ go out.
        int[] all = new int[menuSize()];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return all;
    }

    @Override
    int[] press(int slot, boolean back) {
        int lane = buttonLane(slot);
        if (lane < 0) {
            return null;
        }
        return switch (melody.lock(lane)) {
            case ACCEPTED, FINISHED -> new int[0];
            case IGNORED, WRONG -> null;
        };
    }

    @Override
    boolean solved() {
        return melody.solved();
    }

    @Override
    boolean plingOnFinish() {
        return false;
    }

}
