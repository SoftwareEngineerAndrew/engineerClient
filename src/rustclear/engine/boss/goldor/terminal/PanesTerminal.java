package net.rustclear.mod.engine.boss.goldor.terminal;

import java.util.Random;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.rustclear.mod.engine.BossMenu;

/**
 * `Correct all the panes!` (9x5): 15 panes in 11-15 / 20-24 / 29-33, red "Off" (red name) or lime
 * "On" (green name), 1-6 On at open (median 3, n 36). A click toggles a pane either way; done when
 * all are On.
 */
final class PanesTerminal extends TerminalMenu {
    static final String TITLE = "Correct all the panes!";

    private final boolean[] on;

    static void open(ServerPlayer player, Object key, Random random, Runnable onSolved) {
        boolean[] on = TerminalLogic.panesOn(random);
        BossMenu.openNow(player, TITLE, (id, inventory, p) -> new PanesTerminal(id, inventory, player, key, random, onSolved, on));
    }

    private PanesTerminal(int id, Inventory inventory, ServerPlayer player, Object key, Random random, Runnable onSolved, boolean[] on) {
        super(id, inventory, player, 5, key, random, onSolved);
        this.on = on;
        fillMenu();
    }

    private int index(int slot) {
        for (int i = 0; i < TerminalLogic.PANES_SLOTS.length; i++) {
            if (TerminalLogic.PANES_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    @Override
    ItemStack render(int slot) {
        int i = index(slot);
        if (i < 0) {
            return TerminalItems.filler();
        }
        return on[i]
            ? TerminalItems.named(Items.STAINED_GLASS_PANE.lime(), "On", TerminalItems.GREEN)
            : TerminalItems.named(Items.STAINED_GLASS_PANE.red(), "Off", TerminalItems.RED);
    }

    @Override
    int[] press(int slot, boolean back) {
        int i = index(slot);
        if (i < 0) {
            return null;
        }
        on[i] = !on[i];
        return new int[] {slot};
    }

    @Override
    boolean solved() {
        for (boolean b : on) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

}
