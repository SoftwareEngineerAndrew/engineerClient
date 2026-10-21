package net.rustclear.mod.engine.boss.goldor.terminal;

import java.util.Random;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.rustclear.mod.engine.BossMenu;

/**
 * `Change all to same color!` (9x5): 9 panes in 12-14 / 21-23 / 30-32, named Red, Orange, Yellow,
 * Green or Blue (green names). A left or middle click steps R->O->Y->G->B->R, a right click back
 * (162 back steps seen). Every pane is drawn uniformly, which gives Hypixel's fewest-clicks spread
 * (4-10, median 8, n 58). Done when all nine match.
 */
final class RubixTerminal extends TerminalMenu {
    static final String TITLE = "Change all to same color!";

    private final int[] board;

    static void open(ServerPlayer player, Object key, Random random, Runnable onSolved) {
        int[] board = TerminalLogic.rubixBoard(random);
        BossMenu.openNow(player, TITLE, (id, inventory, p) -> new RubixTerminal(id, inventory, player, key, random, onSolved, board));
    }

    private RubixTerminal(int id, Inventory inventory, ServerPlayer player, Object key, Random random, Runnable onSolved, int[] board) {
        super(id, inventory, player, 5, key, random, onSolved);
        this.board = board;
        fillMenu();
    }

    private int index(int slot) {
        for (int i = 0; i < TerminalLogic.RUBIX_SLOTS.length; i++) {
            if (TerminalLogic.RUBIX_SLOTS[i] == slot) {
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
        int c = board[i];
        return TerminalItems.named(TerminalItems.rubixPane(c), TerminalLogic.RUBIX_COLOURS[c], TerminalItems.GREEN);
    }

    @Override
    int[] press(int slot, boolean back) {
        int i = index(slot);
        if (i < 0) {
            return null;
        }
        board[i] = TerminalLogic.rubixStep(board[i], back);
        return new int[] {slot};
    }

    @Override
    boolean solved() {
        return TerminalLogic.rubixSolved(board);
    }

}
