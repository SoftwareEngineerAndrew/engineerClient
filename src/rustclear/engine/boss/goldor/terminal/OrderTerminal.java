package net.rustclear.mod.engine.boss.goldor.terminal;

import java.util.Random;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.rustclear.mod.engine.BossMenu;

/**
 * `Click in order!` (9x4): since the boss update 10 red panes in 11-15 / 20-24 (18 of 18 post
 * windows; 14 in 10-16 / 19-25 before), each with count = name = its number (green). Clicking the
 * next number turns it lime (same count and name); any other click does nothing.
 */
final class OrderTerminal extends TerminalMenu {
    static final String TITLE = "Click in order!";

    private final int[] numbers;
    private int next = 1;

    static void open(ServerPlayer player, Object key, Random random, Runnable onSolved) {
        int[] numbers = TerminalLogic.orderNumbers(random);
        BossMenu.openNow(player, TITLE, (id, inventory, p) -> new OrderTerminal(id, inventory, player, key, random, onSolved, numbers));
    }

    private OrderTerminal(int id, Inventory inventory, ServerPlayer player, Object key, Random random, Runnable onSolved, int[] numbers) {
        super(id, inventory, player, 4, key, random, onSolved);
        this.numbers = numbers;
        fillMenu();
    }

    private int index(int slot) {
        for (int i = 0; i < TerminalLogic.ORDER_SLOTS.length; i++) {
            if (TerminalLogic.ORDER_SLOTS[i] == slot) {
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
        int n = numbers[i];
        return TerminalItems.named(n < next ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.red(), n, String.valueOf(n),
            TerminalItems.GREEN);
    }

    @Override
    int[] press(int slot, boolean back) {
        int i = index(slot);
        if (i < 0 || numbers[i] != next) {
            return null;
        }
        next++;
        return new int[] {slot};
    }

    @Override
    boolean solved() {
        return next > numbers.length;
    }

}
