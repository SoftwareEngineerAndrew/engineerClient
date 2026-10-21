package net.rustclear.mod.engine.boss.goldor.terminal;

import java.util.Random;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.rustclear.mod.engine.BossMenu;

/**
 * `What starts with: 'X'?` (9x5): 21 vanilla items with 1.8 names (green) in 10-16 / 19-25 / 28-34,
 * 4-11 of them starting with X (median 6, n 55), repeats allowed. Clicking a match glints it (none
 * glint at open); a wrong or repeated click does nothing. Done when every match is picked.
 */
final class StartsWithTerminal extends TerminalMenu {
    private final char letter;
    private final ItemStack[] items;
    private final boolean[] picked;

    static void open(ServerPlayer player, Object key, Random random, Runnable onSolved) {
        char letter = TerminalLogic.STARTS_LETTERS.charAt(random.nextInt(TerminalLogic.STARTS_LETTERS.length()));
        int[] board = TerminalLogic.startsBoard(letter, TerminalLogic.startsMatches(random), random);
        BossMenu.openNow(player, "What starts with: '" + letter + "'?",
            (id, inventory, p) -> new StartsWithTerminal(id, inventory, player, key, random, onSolved, letter, board));
    }

    private StartsWithTerminal(int id, Inventory inventory, ServerPlayer player, Object key, Random random, Runnable onSolved,
        char letter, int[] board) {
        super(id, inventory, player, 5, key, random, onSolved);
        this.letter = letter;
        this.items = new ItemStack[board.length];
        this.picked = new boolean[board.length];
        for (int i = 0; i < board.length; i++) {
            items[i] = TerminalItems.startsItem(board[i]);
        }
        fillMenu();
    }

    private int index(int slot) {
        for (int i = 0; i < TerminalLogic.STARTS_SLOTS.length; i++) {
            if (TerminalLogic.STARTS_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private boolean matches(int i) {
        return items[i].getHoverName().getString().charAt(0) == letter;
    }

    @Override
    ItemStack render(int slot) {
        int i = index(slot);
        if (i < 0) {
            return TerminalItems.filler();
        }
        return picked[i] ? TerminalItems.picked(items[i]) : items[i].copy();
    }

    @Override
    int[] press(int slot, boolean back) {
        int i = index(slot);
        // OPEN: a wrong item does nothing (Odin's GUI never let a wrong click through in the recordings).
        if (i < 0 || picked[i] || !matches(i)) {
            return null;
        }
        picked[i] = true;
        return new int[] {slot};
    }

    @Override
    boolean solved() {
        for (int i = 0; i < items.length; i++) {
            if (matches(i) && !picked[i]) {
                return false;
            }
        }
        return true;
    }

}
