package net.rustclear.mod.engine.boss.goldor.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.server.level.ServerPlayer;

/**
 * P3's six terminal puzzles (boss-p3-goldor.md §7, menus-ui.md): what GoldorPhase calls. Each open
 * is a fresh puzzle of the station's type; [onSolved] runs once, in the tick the finishing click
 * lands, after that window closed (and before the close of window 0 that ends Hypixel's sequence).
 * Several players can have the same station open at once (Hypixel has no terminal lock).
 */
public final class Terminals {
    public enum Type { ORDER, PANES, RUBIX, STARTS_WITH, SELECT, MELODY }

    /** Every terminal window open now. */
    private static final List<TerminalMenu> OPEN = new ArrayList<>();

    private Terminals() {
    }

    /** A station's type: uniform over the six (§7: consistent with the open counts). */
    public static Type random(Random random) {
        Type[] all = Type.values();
        return all[random.nextInt(all.length)];
    }

    /**
     * Opens a new [type] puzzle for [player] (in this tick: GoldorPhase already waits the +1 after
     * the click). [key] is the station, so a teammate's finish can close it ([closeFor]).
     */
    public static void open(ServerPlayer player, Object key, Type type, Random random, Runnable onSolved) {
        Runnable once = new Runnable() {
            private boolean ran;

            @Override
            public void run() {
                if (!ran) {
                    ran = true;
                    onSolved.run();
                }
            }
        };
        switch (type) {
            case ORDER -> OrderTerminal.open(player, key, random, once);
            case PANES -> PanesTerminal.open(player, key, random, once);
            case RUBIX -> RubixTerminal.open(player, key, random, once);
            case STARTS_WITH -> StartsWithTerminal.open(player, key, random, once);
            case SELECT -> SelectTerminal.open(player, key, random, once);
            case MELODY -> MelodyTerminal.open(player, key, random, once);
        }
    }

    /** Closes every open window on [key]'s terminal but [except]'s (someone else finished it). */
    public static void closeFor(Object key, ServerPlayer except) {
        for (TerminalMenu menu : List.copyOf(OPEN)) {
            if (menu.key.equals(key) && menu.viewer() != except && menu.viewer().containerMenu == menu) {
                menu.viewer().closeContainer();
            }
        }
    }

    /** Closes every terminal window (the phase ends). */
    public static void closeAll() {
        for (TerminalMenu menu : List.copyOf(OPEN)) {
            if (menu.viewer().containerMenu == menu) {
                menu.viewer().closeContainer();
            }
        }
        OPEN.clear();
    }

    /**
     * The LAN check: [player]'s next correct click as {window id, slot, button}, button 1 a right
     * click and anything else a middle click; null when no terminal is
     * open or the next click isn't due (Melody off target).
     */

    static void opened(TerminalMenu menu) {
        OPEN.add(menu);
    }

    static void closed(TerminalMenu menu) {
        OPEN.remove(menu);
    }
}
