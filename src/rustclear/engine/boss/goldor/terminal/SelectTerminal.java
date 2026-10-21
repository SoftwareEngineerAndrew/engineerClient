package net.rustclear.mod.engine.boss.goldor.terminal;

import java.util.Random;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.rustclear.mod.engine.BossMenu;

/**
 * `Select all the <COLOUR> items!` (9x6): 28 items in 10-16 / 19-25 / 28-34 / 37-43 from 5 colour
 * families (stained glass, "Stained Clay", wool, dye; green names), split 6-6-6-5-5 (50 of 50). The
 * title uses the 1.8 colour words (SILVER, LIGHT BLUE). Clicking a target-colour item glints it; a
 * wrong or repeated click does nothing. Done when every target item is picked.
 */
final class SelectTerminal extends TerminalMenu {
    private final int target;
    /** Per slot of SELECT_SLOTS: family * 4 + kind. */
    private final int[] cells;
    private final boolean[] picked;

    static void open(ServerPlayer player, Object key, Random random, Runnable onSolved) {
        int[] board = TerminalLogic.selectBoard(random);
        int target = board[0];
        int[] cells = java.util.Arrays.copyOfRange(board, 1, board.length);
        BossMenu.openNow(player, "Select all the " + TerminalLogic.SELECT_FAMILIES[target][0] + " items!",
            (id, inventory, p) -> new SelectTerminal(id, inventory, player, key, random, onSolved, target, cells));
    }

    private SelectTerminal(int id, Inventory inventory, ServerPlayer player, Object key, Random random, Runnable onSolved,
        int target, int[] cells) {
        super(id, inventory, player, 6, key, random, onSolved);
        this.target = target;
        this.cells = cells;
        this.picked = new boolean[cells.length];
        fillMenu();
    }

    private int index(int slot) {
        for (int i = 0; i < TerminalLogic.SELECT_SLOTS.length; i++) {
            if (TerminalLogic.SELECT_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private boolean wanted(int i) {
        return cells[i] / 4 == target;
    }

    @Override
    ItemStack render(int slot) {
        int i = index(slot);
        if (i < 0) {
            return TerminalItems.filler();
        }
        ItemStack item = TerminalItems.selectItem(cells[i] / 4, cells[i] % 4);
        return picked[i] ? TerminalItems.picked(item) : item;
    }

    @Override
    int[] press(int slot, boolean back) {
        int i = index(slot);
        // OPEN: a wrong item does nothing (no wrong click got through Odin's GUI in the recordings).
        if (i < 0 || picked[i] || !wanted(i)) {
            return null;
        }
        picked[i] = true;
        return new int[] {slot};
    }

    @Override
    boolean solved() {
        for (int i = 0; i < cells.length; i++) {
            if (wanted(i) && !picked[i]) {
                return false;
            }
        }
        return true;
    }

}
