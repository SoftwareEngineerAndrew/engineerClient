package net.rustclear.mod;

import static net.rustclear.mod.ui.Theme.*;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.rustclear.mod.engine.DungeonClass;
import net.rustclear.mod.engine.Engine;
import net.rustclear.mod.online.Relays;
import net.rustclear.mod.ui.RustclearScreen;

/**
 * The party screen from the pause menu, drawn like Dungeon settings: a rail of tabs (members,
 * invites, mode) and the members' classes and ready state. Picking a class readies you (the
 * same class again un-readies you); the leader also gets Force start (which gives everyone who
 * hasn't picked a class one), Lead and Kick on each member, the invite list, open/private, and
 * the mode: a party of the default size or any size up to 50, or an NvN duel (1v1, 2v2, 3v3, ...)
 * raced to 270 or 300 score.
 * What it shows comes from the host's rustclear:party ([RustclearMod#party]).
 */
public final class PartyScreen extends RustclearScreen {
    private enum Tab { MEMBERS, INVITE, MODE }

    private static final int ROW_H = 20;

    private Tab tab = Tab.MEMBERS;
    private EditBox inviteName;
    /** The host's tunnel address box, when no relay mod shares the world. */
    private EditBox tunnel;
    /** The last thing a button here did, shown in the footer. */
    private String note;
    /** Members scrolled past, when there are more than fit. */
    private int scroll;
    private int maxScroll;

    public PartyScreen(Screen parent) {
        super(Component.literal("Rustclear party"), parent);
        RustclearMod.sendParty("state", null);
    }

    @Override
    protected void init() {
        layoutPanel(372, 236);
        Protocol.PartyState party = RustclearMod.party;
        if (tab == Tab.INVITE && party != null && party.you) {
            String previous = inviteName == null ? "" : inviteName.getValue();
            inviteName = input(contentX() + 6, contentY() + 66, 110, "Player name", 16);
            inviteName.setValue(previous);
            // Minecraft names only: letters, digits and underscores.
            inviteName.setResponder(text -> {
                String clean = text.replaceAll("[^A-Za-z0-9_]", "");
                if (!clean.equals(text)) {
                    inviteName.setValue(clean);
                }
            });
            addRenderableWidget(inviteName);
        } else {
            inviteName = null;
        }
        if (tab == Tab.INVITE && Engine.isEngineHost() && RustclearMod.shareAddress == null && !Online.sharing) {
            String previous = tunnel == null ? "" : tunnel.getValue();
            tunnel = input(contentX() + 6, contentY() + 115, 150, "Tunnel address (host name)", 253);
            tunnel.setValue(previous);
            addRenderableWidget(tunnel);
        } else {
            tunnel = null;
        }
    }

    private int contentX() {
        return px + 108;
    }

    private int contentY() {
        return py + 30;
    }

    private int contentW() {
        return pw - 108 - 12;
    }

    private static int modeColor(String mode) {
        return "PARTY".equals(mode) || mode == null ? ENTRANCE : PUZZLE;
    }

    private static String phaseLabel(String phase) {
        return switch (phase == null ? "" : phase) {
            case "countdown" -> "Starting";
            case "running" -> "In the dungeon";
            case "finished" -> "Finished";
            default -> "At Mort";
        };
    }

    // --- Frame ---

    @Override
    protected void extractFrame(GuiGraphicsExtractor g, int mx, int my, float delta) {
        panel(g);
        Protocol.PartyState party = RustclearMod.party;
        String mode = party == null ? "PARTY" : party.mode;
        header(g, "Party", modeLabel(mode).toUpperCase(Locale.ROOT), modeColor(mode));
        drawRail(g, mx, my, party);
        if (party == null) {
            text(g, "Waiting for the host…", contentX(), contentY() + 4, DIM);
            pulse(g, contentX() + font.width("Waiting for the host…") + 6, contentY() + 7, GOLD);
        } else {
            switch (tab) {
                case MEMBERS -> drawMembers(g, mx, my, party);
                case INVITE -> drawInvite(g, mx, my, party);
                case MODE -> drawMode(g, mx, my, party);
            }
        }
        drawFooter(g, mx, my, party);
    }

    private void drawRail(GuiGraphicsExtractor g, int mx, int my, Protocol.PartyState party) {
        railBackground(g, 100);
        int y = py + 29;
        int count = party == null ? 0 : party.members.size();
        int max = party == null ? 5 : party.max;
        railTab(g, mx, my, Tab.MEMBERS, "Members", count + " of " + max, ENTRANCE, y);
        railTab(g, mx, my, Tab.INVITE, "Invite", party == null ? "…" : party.open ? "Open" : "Private", GOLD, y + 30);
        railTab(g, mx, my, Tab.MODE, "Mode", party == null ? "…" : modeLabel(party.mode), PUZZLE, y + 60);

        // Where the party is, at the bottom like the leaderboard's slot in Dungeon settings.
        int by = py + ph - 38;
        rect(g, px + 9, by - 6, 83, 1, PANEL_EDGE);
        String phase = party == null ? "Connecting" : phaseLabel(party.phase);
        boolean live = party != null && ("countdown".equals(party.phase) || "running".equals(party.phase));
        railItem(g, mx, my, px + 5, by, 91, false, "Dungeon", phase, live ? GOOD : SILVER, live ? GOOD : DIM, () -> {});
    }

    private void railTab(GuiGraphicsExtractor g, int mx, int my, Tab which, String name, String summary, int color, int y) {
        railItem(g, mx, my, px + 5, y, 91, tab == which, name, summary, color, color == ENTRANCE ? lerp(ENTRANCE, TEXT, 0.3f) : color, () -> {
            if (tab != which) {
                tab = which;
                note = null;
                rebuildWidgets();
            }
        });
    }

    // --- Members ---

    private void drawMembers(GuiGraphicsExtractor g, int mx, int my, Protocol.PartyState party) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int tx = x + title(g, "Members", x, y) + 6;
        tx += pill(g, party.members.size() + "/" + party.max, tx, y, party.members.size() >= party.max ? GOLD : GOOD) + 4;
        long ready = party.members.stream().filter(m -> m.ready).count();
        textRight(g, ready + " of " + party.members.size() + " ready", x + w, y + 2, ready == party.members.size() ? GOOD : MUTED);

        // Your class: picking one readies you.
        Protocol.PartyMember mine = mine(party);
        DungeonClass picked = mine == null ? null : DungeonClass.of(mine.dungeonClass);
        classPicker(g, mx, my, x, y + 16, picked, "lobby".equals(party.phase) ? c -> RustclearMod.pickClass(c.name()) : null);

        int ry = y + 34;
        int visible = Math.max(1, (py + ph - 30 - ry) / ROW_H);
        int rows = Math.max(party.members.size(), party.max);
        maxScroll = Math.max(0, rows - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        for (int i = scroll; i < scroll + visible && i < rows; i++) {
            if (i < party.members.size()) {
                memberRow(g, mx, my, party, party.members.get(i), x, ry, w);
            } else {
                // Free places, so it's clear how many more can join.
                outline(g, x, ry, w, ROW_H - 3, alpha(PANEL_EDGE, 0xA0));
                text(g, "Open slot", x + 22, ry + 5, DIM);
            }
            ry += ROW_H;
        }
        if (maxScroll > 0) {
            // Rows past the panel's bottom are scrolled to.
            text(g, scroll < maxScroll ? "+" + (rows - scroll - visible) + " below" : "scroll up", tx, y + 2, DIM);
        }
    }

    private void memberRow(GuiGraphicsExtractor g, int mx, int my, Protocol.PartyState party, Protocol.PartyMember member,
                           int x, int y, int w) {
        boolean me = member.name.equalsIgnoreCase(minecraft.getUser().getName());
        boolean hover = over(mx, my, x, y, w, ROW_H - 3);
        round(g, x, y, w, ROW_H - 3, me ? lerp(ROW_ON, GOLD, 0.08f) : hover ? ROW_HOVER : RAIL);
        if (me) {
            rect(g, x, y + 3, 1, ROW_H - 9, GOLD);
        }
        int teamColor = "A".equals(member.team) ? TEAM_A : "B".equals(member.team) ? TEAM_B : SILVER;
        avatar(g, member.name, x + 5, y + 3, teamColor);
        int nx = x + 22;
        text(g, member.name, nx, y + 5, TEXT);
        nx += font.width(member.name) + 5;
        if (member.leader) {
            nx += pill(g, "LEADER", nx, y + 2, GOLD) + 3;
        }
        if (member.team != null) {
            pill(g, "TEAM " + member.team, nx, y + 2, teamColor);
        }

        // Ready on the right; the leader's controls appear over it on hover.
        int right = x + w - 6;
        if (party.you && hover && !member.leader) {
            int kx = right - font.width("Kick") - 8;
            memberAction(g, mx, my, "Kick", kx, y + 3, BAD, () -> RustclearMod.sendParty("kick", member.name));
            int lx = kx - font.width("Make leader") - 11;
            memberAction(g, mx, my, "Make leader", lx, y + 3, GOLD, () -> RustclearMod.sendParty("leader", member.name));
        } else {
            String label = member.ready ? "Ready" : "Not ready";
            int c = member.ready ? GOOD : BAD;
            textRight(g, label, right, y + 5, c);
            rect(g, right - font.width(label) - 7, y + 7, 3, 3, c);
            DungeonClass dc = DungeonClass.of(member.dungeonClass);
            if (dc != null) {
                int cx = right - font.width(label) - 13 - (font.width(dc.title) + 8);
                pill(g, dc.title, cx, y + 2, classColor(dc));
                if (over(mx, my, cx, y + 2, font.width(dc.title) + 8, 12)) {
                    g.setComponentTooltipForNextFrame(font, classTooltip(dc), mx, my);
                }
            }
        }
    }

    /** This player's own row, or null. */
    private Protocol.PartyMember mine(Protocol.PartyState party) {
        String me = minecraft.getUser().getName();
        return party.members.stream().filter(m -> m.name.equalsIgnoreCase(me)).findFirst().orElse(null);
    }

    private void memberAction(GuiGraphicsExtractor g, int mx, int my, String label, int x, int y, int color, Runnable action) {
        int w = font.width(label) + 8;
        boolean hover = over(mx, my, x, y, w, 11);
        round(g, x, y, w, 11, hover ? color : alpha(color, 0x30));
        text(g, label, x + 4, y + 2, hover ? ON_GOLD : color);
        click(x, y, w, 11, action);
    }

    // --- Invite ---

    private void drawInvite(GuiGraphicsExtractor g, int mx, int my, Protocol.PartyState party) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int tx = x + title(g, "Who can join", x, y) + 6;
        pill(g, party.open ? "OPEN" : "PRIVATE", tx, y, party.open ? GOOD : GOLD);

        int cy = y + 18;
        int cx = x;
        Runnable open = party.you ? () -> RustclearMod.sendParty("open", null) : null;
        Runnable close = party.you ? () -> RustclearMod.sendParty("close", null) : null;
        cx += choice(g, mx, my, "Private", cx, cy, !party.open, close) + 3;
        choice(g, mx, my, "Open", cx, cy, party.open, open);
        text(g, fit(party.open ? "Anyone with the party's address can join, up to " + party.max + "."
            : "Only players you invite can join. Alone, this is how you play solo.", w), x, cy + 18, DIM);

        int iy = y + 52;
        text(g, "Invite by name", x, iy, SILVER);
        if (!party.you) {
            text(g, "Only the party leader can invite.", x, iy + 14, DIM);
            return;
        }
        inputFrame(g, x, iy + 11, 122, inviteName != null && inviteName.isFocused(), false);
        int bx = x + 126;
        choice(g, mx, my, "Invite", bx, iy + 11, false, () -> {
            String name = inviteName == null ? "" : inviteName.getValue().trim();
            if (name.length() >= 3) {
                RustclearMod.sendParty("invite", name);
                note = "Invited " + name + ".";
                inviteName.setValue("");
            } else {
                note = "Type the player's Minecraft name.";
            }
        });

        drawShare(g, mx, my, x, iy + 34, w);
    }

    /**
     * Where friends join: the relay address with Copy, sharing in progress, or (on the host,
     * without a relay mod) why not and a box for a tunnel's address. Never an IP.
     */
    private void drawShare(GuiGraphicsExtractor g, int mx, int my, int x, int ay, int w) {
        text(g, "Party address", x, ay, SILVER);
        String address = RustclearMod.shareAddress;
        if (address != null) {
            int cw = choiceWidth("Copy");
            text(g, fit(address, w - cw - 6), x, ay + 14, GOLD);
            choice(g, mx, my, "Copy", x + w - cw, ay + 11, false, () -> {
                minecraft.keyboardHandler.setClipboard(address);
                note = "Copied. Friends paste it in Multiplayer > Direct Connection.";
            });
            text(g, fit("Friends join through the relay; nobody sees anyone's IP.", w), x, ay + 27, DIM);
            return;
        }
        if (!Engine.isEngineHost()) {
            text(g, fit("Ask the host for the party's address.", w), x, ay + 14, DIM);
            return;
        }
        if (Online.sharing) {
            text(g, "Sharing through " + Relays.provider().name(), x, ay + 14, MUTED);
            pulse(g, x + font.width("Sharing through " + Relays.provider().name()) + 6, ay + 17, GOLD);
            return;
        }
        String problem = Online.shareProblem == null ? "Not shared yet." : Online.shareProblem;
        int rw = choiceWidth("Share");
        text(g, fit(problem, w - rw - 6), x, ay + 14, Online.shareProblem == null ? DIM : BAD);
        choice(g, mx, my, "Share", x + w - rw, ay + 11, false, () -> {
            Online.share();
            rebuildWidgets();
        });
        inputFrame(g, x, ay + 26, 162, tunnel != null && tunnel.isFocused(), false);
        choice(g, mx, my, "Use", x + 166, ay + 26, false, () -> {
            if (tunnel != null && Relays.useTunnelAddress(tunnel.getValue())) {
                note = "Friends can join at " + RustclearMod.shareAddress + ".";
                Online.onAddressReady();
                rebuildWidgets();
            } else {
                note = "Paste the tunnel's host name (an IP address isn't allowed).";
            }
        });
    }

    // --- Mode ---

    private void drawMode(GuiGraphicsExtractor g, int mx, int my, Protocol.PartyState party) {
        partyMode(g, mx, my, party, contentX(), contentY(), contentW());
    }

    // --- Footer ---

    private void drawFooter(GuiGraphicsExtractor g, int mx, int my, Protocol.PartyState party) {
        int y = py + ph - 23;
        int x = contentX();
        rect(g, x, y - 5, contentW(), 1, PANEL_EDGE);
        int right = px + pw - 8;
        if (party != null && party.you) {
            right = button(g, mx, my, "Force start", right, y, Style.PRIMARY,
                "lobby".equals(party.phase) ? () -> RustclearMod.sendParty("start", null) : null) - 4;
        }
        right = button(g, mx, my, "Done", right, y, Style.SECONDARY, this::onClose);

        String line = note;
        int color = GOLD;
        if (line == null && party != null && tab == Tab.MEMBERS && "lobby".equals(party.phase)) {
            Protocol.PartyMember mine = mine(party);
            line = mine != null && mine.ready ? "Ready. Pick your class again to unready."
                : party.you ? "Pick a class to ready up. Force start fills in the rest." : "Pick a class to ready up.";
            color = DIM;
        } else if (line == null && party != null && !party.you) {
            line = party.leader == null ? "" : party.leader + " leads the party.";
            color = DIM;
        }
        if (line != null) {
            text(g, fit(line, right - x - 8), x, y + 5, color);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (tab == Tab.MEMBERS) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        back();
    }

    /** Called when a new party state arrives, so a tab that depends on leadership is rebuilt. */
    void onParty() {
        rebuildWidgets();
    }

    /** The members list as shown, for tests and other screens. */
    static List<Protocol.PartyMember> members() {
        Protocol.PartyState party = RustclearMod.party;
        return party == null ? List.of() : party.members;
    }
}
