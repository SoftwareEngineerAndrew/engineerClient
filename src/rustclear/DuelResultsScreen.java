package net.rustclear.mod;

import static net.rustclear.mod.ui.Theme.*;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.rustclear.mod.ui.RustclearScreen;

/**
 * The end of a duel: who won and how, the winning time in the big pixel digits, and each team's
 * score, rooms, secrets and the time they reached the target score. The leader can start a rematch on a new dungeon (the same for
 * every team again) or take everyone back to the party; everyone else can look and close.
 */
public final class DuelResultsScreen extends RustclearScreen {
    private static final int ROW_H = 20;

    public DuelResultsScreen(Screen parent) {
        super(Component.literal("Duel results"), parent);
    }

    @Override
    protected void init() {
        layoutPanel(350, 196);
    }

    @Override
    protected void extractFrame(GuiGraphicsExtractor g, int mx, int my, float delta) {
        panel(g);
        Protocol.PartyState party = RustclearMod.party;
        String winner = party == null ? null : party.winner;
        String own = DuelHud.ownTeam();
        boolean won = winner != null && winner.equals(own);
        header(g, "Duel results", winner == null ? "RACING" : won ? "VICTORY" : "DEFEAT",
            winner == null ? GOLD : won ? GOOD : BAD);

        int x = px + 12;
        int w = pw - 24;
        int y = py + 32;
        if (party == null || winner == null) {
            text(g, "The race is still on.", x, y, MUTED);
            pulse(g, x + font.width("The race is still on.") + 6, y + 3, GOLD);
        } else {
            int c = DuelHud.teamColor(winner);
            rect(g, x, y + 1, 3, 20, c);
            textShadow(g, "TEAM " + winner + " WINS", x + 8, y + 1, c);
            text(g, fit(party.winReason == null ? "" : party.winReason, w - 120), x + 8, y + 13, MUTED);
            Protocol.TeamProgress best = party.teams.stream().filter(t -> t.team.equals(winner)).findFirst().orElse(null);
            if (best != null) {
                String time = SettingsScreen.formatTime(best.ticks);
                String digits = time.substring(0, time.indexOf('.'));
                bigNumber(g, digits, x + w - bigNumberWidth(digits, 3), y + 2, 3, GOLD);
            }
        }

        // One row per team.
        int ty = y + 30;
        text(g, "Team", x + 4, ty, DIM);
        int target = party == null ? 0 : party.target;
        textRight(g, "Score", x + w - 160, ty, DIM);
        textRight(g, "Rooms", x + w - 112, ty, DIM);
        textRight(g, "Secrets", x + w - 56, ty, DIM);
        textRight(g, "To " + target, x + w - 4, ty, MUTED);
        rect(g, x, ty + 10, w, 1, PANEL_EDGE);
        int ry = ty + 13;
        if (party != null) {
            for (Protocol.TeamProgress t : party.teams) {
                boolean mine = t.team.equals(own);
                boolean first = t.team.equals(winner);
                round(g, x, ry, w, ROW_H - 3, mine ? lerp(ROW_ON, GOLD, 0.08f) : RAIL);
                int c = DuelHud.teamColor(t.team);
                avatar(g, t.team, x + 4, ry + 3, c);
                text(g, fit(String.join(" & ", t.players), w - 220), x + 20, ry + 5, mine ? TEXT : MUTED);
                if (first) {
                    pill(g, "1ST", x + 20 + Math.min(font.width(String.join(" & ", t.players)), w - 220) + 5, ry + 2, GOLD);
                }
                textRight(g, String.valueOf(t.score), x + w - 160, ry + 5, t.finished ? GOOD : TEXT);
                textRight(g, t.roomsCleared + "/" + t.totalRooms, x + w - 112, ry + 5, lerp(NORMAL, TEXT, 0.45f));
                textRight(g, t.secretsFound + "/" + t.totalSecrets, x + w - 56, ry + 5, GOLD);
                textRight(g, t.finished ? SettingsScreen.formatTime(t.ticks) : "DNF", x + w - 4, ry + 5, t.finished ? GOOD : DIM);
                ry += ROW_H;
            }
        }

        int by = py + ph - 23;
        rect(g, px + 8, by - 5, pw - 16, 1, PANEL_EDGE);
        int right = px + pw - 8;
        boolean leader = party != null && party.you;
        if (leader && winner != null) {
            right = button(g, mx, my, "Rematch", right, by, Style.PRIMARY, () -> {
                RustclearMod.sendParty("rematch", null);
                onClose();
            }) - 4;
            right = button(g, mx, my, "Back to party", right, by, Style.SECONDARY, () -> {
                RustclearMod.sendParty("lobby", null);
                onClose();
            }) - 4;
        }
        button(g, mx, my, "Close", right, by, Style.SECONDARY, this::onClose);
        if (!leader && winner != null) {
            text(g, "The leader picks a rematch or the party.", x, by + 5, DIM);
        }
    }

    @Override
    public void onClose() {
        back();
    }
}
