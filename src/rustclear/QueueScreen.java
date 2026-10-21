package net.rustclear.mod;

import static net.rustclear.mod.ui.Theme.*;

import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.rustclear.mod.ui.RustclearScreen;

/**
 * The 1v1 / 2v2 queue: first the race (first to 270 or first to 300 score, each its own queue,
 * with how many are searching in each) and, for a 2v2, an optional friend to queue with as a duo;
 * then a live timer with Cancel; then the match. The chosen host's game opens and shares the
 * dungeon by itself (Online.onMatch), and everyone else's connects as soon as the host's address
 * arrives.
 */
public final class QueueScreen extends RustclearScreen {
    private static final String[] TARGET_DETAIL = {"S rank. The shorter race.", "S+ rank. Every room and nearly every secret."};

    private final String mode;
    private EditBox partner;
    /** The race picked: first to this score. */
    private int target = Online.queueTarget();

    public QueueScreen(Screen parent, String mode) {
        super(Component.literal("Queue"), parent);
        this.mode = mode;
        Online.refreshCounts();
    }

    private boolean isDuo() {
        return "DUEL_2V2".equals(mode);
    }

    private String label() {
        return isDuo() ? "2v2" : "1v1";
    }

    @Override
    protected void init() {
        layoutPanel(280, isDuo() ? 214 : 186);
        if (isDuo() && Online.phase() == Online.QueuePhase.IDLE) {
            String previous = partner == null ? "" : partner.getValue();
            partner = input(px + 18, py + 151, 140, "Friend's name (optional)", 16);
            partner.setValue(previous);
            partner.setResponder(text -> {
                String clean = text.replaceAll("[^A-Za-z0-9_]", "");
                if (!clean.equals(text)) {
                    partner.setValue(clean);
                }
            });
            addRenderableWidget(partner);
        } else {
            partner = null;
        }
    }

    @Override
    protected void extractFrame(GuiGraphicsExtractor g, int mx, int my, float delta) {
        panel(g);
        Online.QueuePhase phase = Online.phase();
        header(g, label() + " queue", badge(phase), phase == Online.QueuePhase.FAILED ? BAD : GOOD);
        int x = px + 12;
        int w = pw - 24;
        int y = py + 32;
        int by = py + ph - 23;
        rect(g, px + 8, by - 5, pw - 16, 1, PANEL_EDGE);

        if (phase == Online.QueuePhase.IDLE || phase == Online.QueuePhase.QUEUED) {
            Online.refreshCounts();
        }
        switch (phase) {
            case IDLE -> {
                title(g, "Pick your race", x, y);
                text(g, fit("The first team to reach the score wins.", w), x, y + 16, MUTED);
                int cy = y + 30;
                for (int i = 0; i < Online.TARGETS.length; i++) {
                    int t = Online.TARGETS[i];
                    int n = Online.searching(mode, t);
                    String count = n < 0 ? "…" : n + " searching";
                    card(g, mx, my, x, cy, w, 30, "First to " + t, TARGET_DETAIL[i], count, t == target ? GOLD : PUZZLE,
                        Glyph.DUEL, () -> target = t);
                    if (t == target) {
                        outline(g, x, cy, w, 30, GOLD);
                    }
                    cy += 34;
                }
                if (isDuo()) {
                    text(g, "Queue alone, or name a friend who queues for you too.", x, cy + 2, MUTED);
                    inputFrame(g, x, cy + 16, 152, partner != null && partner.isFocused(), false);
                    text(g, fit("You'll be teamed together; strangers fill the rest.", w), x, cy + 35, DIM);
                }
                button(g, mx, my, "Queue", px + pw - 8, by, Style.PRIMARY, () -> {
                    Online.queue(mode, target, partner == null ? null : partner.getValue());
                    rebuildWidgets();
                });
            }
            case CONNECTING, QUEUED -> {
                long seconds = Math.max(0, (System.currentTimeMillis() - Online.queueSince()) / 1000);
                String time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
                int tw = bigNumberWidth(time, 5);
                bigNumber(g, time, px + (pw - tw) / 2, y + 6, 5, phase == Online.QueuePhase.QUEUED ? GOLD : MUTED);
                String status = phase == Online.QueuePhase.CONNECTING ? "Signing in to the lobby" : "Looking for players";
                int sw = font.width(status);
                text(g, status, px + (pw - sw) / 2 - 8, y + 40, MUTED);
                pulse(g, px + (pw + sw) / 2 - 4, y + 43, GOLD);
                int n = Online.searching(mode, Online.queueTarget());
                String race = "First to " + Online.queueTarget() + (n < 0 ? "" : " · " + n + " searching");
                textCenter(g, fit(race, w), px + pw / 2, y + 56, DIM);
                if (Online.queueMessage() != null) {
                    textCenter(g, fit(Online.queueMessage(), w), px + pw / 2, y + 70, BAD);
                }
                button(g, mx, my, "Cancel", px + pw - 8, by, Style.DANGER, () -> {
                    Online.cancelQueue();
                    rebuildWidgets();
                });
            }
            case HOSTING, WAITING_FOR_HOST, JOINING -> drawMatch(g, x, y, w, phase);
            case FAILED -> {
                title(g, "Not this time", x, y);
                text(g, fit(Online.queueMessage() == null ? "Something went wrong." : Online.queueMessage(), w), x, y + 16, BAD);
                button(g, mx, my, "Queue again", px + pw - 8, by, Style.PRIMARY, () -> {
                    Online.cancelQueue();
                    rebuildWidgets();
                });
            }
        }
        if (phase == Online.QueuePhase.IDLE || phase == Online.QueuePhase.FAILED) {
            int bx = px + pw - 8 - font.width(phase == Online.QueuePhase.IDLE ? "Queue" : "Queue again") - 16 - 4;
            button(g, mx, my, "Back", bx, by, Style.SECONDARY, this::onClose);
        }
    }

    private static String badge(Online.QueuePhase phase) {
        return switch (phase) {
            case CONNECTING, QUEUED -> "SEARCHING";
            case HOSTING, WAITING_FOR_HOST, JOINING -> "MATCH FOUND";
            case FAILED -> "STOPPED";
            default -> null;
        };
    }

    private void drawMatch(GuiGraphicsExtractor g, int x, int y, int w, Online.QueuePhase phase) {
        title(g, "Match found", x, y);
        textRight(g, "First to " + Online.queueTarget(), x + w, y + 2, GOLD);
        String what = switch (phase) {
            case HOSTING -> "You host: opening the dungeon";
            case WAITING_FOR_HOST -> "Waiting for " + Online.matchHost() + " to open the dungeon";
            default -> "Joining";
        };
        text(g, fit(what, w - 24), x, y + 16, MUTED);
        pulse(g, x + Math.min(font.width(what), w - 24) + 6, y + 19, GOLD);
        int ry = y + 32;
        for (String team : new String[] {"A", "B"}) {
            int c = "A".equals(team) ? TEAM_A : TEAM_B;
            text(g, "Team " + team, x, ry, c);
            int nx = x + 44;
            for (Map.Entry<String, String> e : Online.matchTeams().entrySet()) {
                if (e.getValue().equals(team)) {
                    avatar(g, e.getKey(), nx, ry - 2, c);
                    text(g, e.getKey(), nx + 14, ry, TEXT);
                    nx += font.width(e.getKey()) + 24;
                }
            }
            ry += 16;
        }
    }

    @Override
    public void onClose() {
        // Leaving the screen while searching cancels; a found match carries on.
        if (Online.phase() == Online.QueuePhase.QUEUED || Online.phase() == Online.QueuePhase.CONNECTING
            || Online.phase() == Online.QueuePhase.FAILED) {
            Online.cancelQueue();
        }
        back();
    }
}
