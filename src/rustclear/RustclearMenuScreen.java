package net.rustclear.mod;

import static net.rustclear.mod.ui.Theme.*;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.rustclear.mod.engine.EngineWorld;
import net.rustclear.mod.online.E4mcInstaller;
import net.rustclear.mod.online.E4mcPreLaunch;
import net.rustclear.mod.online.Relays;
import net.rustclear.mod.ui.RustclearScreen;

/**
 * What the title screen's Rustclear button opens, filling the window: on the left a dungeon map
 * in the map's own colours with who you are and what is online, on the right the ways to play as
 * large tiles. Make a party (the host's own world, private or public), join one, or queue for a
 * 1v1 or 2v2; scrolling down, the Kit editor and the (coming) Map editor. A
 * private party nobody is invited to is how you play solo, so that path never needs anything
 * online. The online options say why when they can't be used rather than disappearing.
 */
public final class RustclearMenuScreen extends RustclearScreen {
    private enum Page { MAIN, MAKE }

    /** The rail's dungeon: E entrance, N room, P puzzle, T trap, Y miniboss, F fairy, B blood. */
    private static final String[] MAP = {
        "NNPN.Y",
        "N.NFNN",
        "ENNTNP",
        "N.NNPN",
        "NNYN.N",
        ".NNNNB",
    };

    private Page page = Page.MAIN;
    /** How far the main page's tiles are scrolled, in pixels, and how far they can be. */
    private int mainScroll;
    private int mainMaxScroll;
    /** The main page's scrolled window in the last frame (x, y, w, h), for the mouse wheel. */
    private int[] mainView;

    public RustclearMenuScreen(Screen parent) {
        super(Component.literal("Rustclear"), parent);
    }

    @Override
    protected void init() {
        // The whole window, 8 px in from each edge.
        layoutPanel(Integer.MAX_VALUE, Integer.MAX_VALUE);
        Online.refresh();
    }

    private int railW() {
        return Math.max(130, Math.min(220, pw * 32 / 100));
    }

    @Override
    protected void extractFrame(GuiGraphicsExtractor g, int mx, int my, float delta) {
        panel(g);
        Online.Status online = Online.status();
        String badge = online.ready() ? "ONLINE" : online.checking() ? "CHECKING" : "OFFLINE";
        header(g, page == Page.MAIN ? "Play" : "Make a party", badge, online.ready() ? GOOD : online.checking() ? GOLD : DIM);
        int railW = railW();
        railBackground(g, railW);
        drawRail(g, mx, my, railW, online);

        int x = px + railW + 12;
        int w = pw - railW - 24;
        int y = py + 32;
        int by = py + ph - 23;
        int h = by - 8 - y;
        if (page == Page.MAIN) {
            drawMain(g, mx, my, x, y, w, h, online);
        } else {
            drawMake(g, mx, my, x, y, w, h, online);
        }

        rect(g, x, by - 5, w, 1, PANEL_EDGE);
        button(g, mx, my, page == Page.MAKE ? "Back" : "Done", px + pw - 8, by, Style.SECONDARY, () -> {
            if (page == Page.MAKE) {
                page = Page.MAIN;
            } else {
                onClose();
            }
        });
        String hint = page == Page.MAIN ? "Dungeons are built on your own game; nothing is needed online to play solo."
            : "You can open or close the party later from Party > Invite.";
        text(g, fit(hint, w - 80), x, by + 5, DIM);
    }

    // ---------------------------------------------------------------- rail

    private void drawRail(GuiGraphicsExtractor g, int mx, int my, int railW, Online.Status online) {
        int x = px + 12;
        int w = railW - 22;
        int y = py + 32;

        // A small dungeon, drawn the way the map item draws one.
        int cell = Math.max(8, Math.min(16, (w - 5 * 3) / 6));
        int gap = Math.max(2, cell / 5);
        int mapW = cell * 6 + gap * 5;
        int mx0 = x + (w - mapW) / 2;
        round(g, mx0 - 6, y - 4, mapW + 12, mapW + 12, alpha(PANEL_EDGE, 0x60));
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 6; c++) {
                char room = MAP[r].charAt(c);
                if (room == '.') {
                    continue;
                }
                int cx = mx0 + c * (cell + gap);
                int cy = y + 2 + r * (cell + gap);
                rect(g, cx, cy, cell, cell, roomColor(room));
                // Doors to the room on the right and below.
                if (c < 5 && MAP[r].charAt(c + 1) != '.') {
                    rect(g, cx + cell, cy + cell / 3, gap, Math.max(2, cell / 3), lerp(NORMAL, RAIL, 0.25f));
                }
                if (r < 5 && MAP[r + 1].charAt(c) != '.') {
                    rect(g, cx + cell / 3, cy + cell, Math.max(2, cell / 3), gap, lerp(NORMAL, RAIL, 0.25f));
                }
            }
        }
        // The player's arrow on the entrance, pulsing.
        int ex = mx0;
        int ey = y + 2 + 2 * (cell + gap);
        int pulse = (int) (System.currentTimeMillis() / 400 % 2);
        rect(g, ex + cell / 2 - 1, ey + cell / 2 - 1 - pulse, 3, 3, 0xFFFFFFFF);

        int ty = y + mapW + 16;
        g.text(font, "RUSTCLEAR", x + (w - font.width("RUSTCLEAR")) / 2, ty, GOLD, true);
        String tag = "Hypixel dungeons, anywhere.";
        textCenter(g, fit(tag, w), x + w / 2, ty + 12, MUTED);

        // Who and what: the player, the mod, the lobby, the relay.
        int iy = ty + 32;
        rect(g, x, iy - 7, w, 1, PANEL_EDGE);
        String lobby = online.ready() ? "Online" : online.checking() ? "Checking" : "Unavailable";
        info(g, x, iy, w, "Player", minecraft.getUser().getName(), TEXT);
        info(g, x, iy + 12, w, "Version", RustclearMod.version(), SILVER);
        info(g, x, iy + 24, w, "Lobby", lobby, online.ready() ? GOOD : online.checking() ? GOLD : DIM);
        info(g, x, iy + 36, w, "Relay", Relays.provider().name(), Relays.provider().name().startsWith("e4") ? GOOD : MUTED);
        drawInstaller(g, mx, my, x, iy + 52, w);
    }

    /**
     * Without e4mc, sharing a party needs a tunnel set up by hand; one click fetches e4mc from
     * Modrinth instead (E4mcInstaller), ready after a restart.
     */
    private void drawInstaller(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        E4mcInstaller.State state = E4mcInstaller.state();
        if (!E4mcInstaller.needed() && state != E4mcInstaller.State.INSTALLED) {
            return;
        }
        switch (state) {
            case INSTALLING -> {
                String installing = "Installing " + E4mcPreLaunch.relayProject();
                text(g, installing, x, y + 3, MUTED);
                pulse(g, x + font.width(installing) + 5, y + 6, GOLD);
            }
            case INSTALLED -> {
                for (String line : wrap(E4mcInstaller.message(), w)) {
                    text(g, line, x, y, GOOD);
                    y += 10;
                }
            }
            default -> {
                String install = "Install " + E4mcPreLaunch.relayProject();
                int bw = choiceWidth(install);
                choice(g, mx, my, install, x + (w - bw) / 2, y, false, E4mcInstaller::install);
                String why = state == E4mcInstaller.State.FAILED ? E4mcInstaller.message()
                    : "Shares parties with no setup. From Modrinth.";
                int ly = y + 17;
                for (String line : wrap(why, w)) {
                    textCenter(g, line, x + w / 2, ly, state == E4mcInstaller.State.FAILED ? BAD : DIM);
                    ly += 10;
                }
            }
        }
    }

    private void info(GuiGraphicsExtractor g, int x, int y, int w, String label, String value, int color) {
        text(g, label, x, y, DIM);
        textRight(g, fit(value, w - font.width(label) - 8), x + w, y, color);
    }

    private static int roomColor(char room) {
        return switch (room) {
            case 'E' -> ENTRANCE;
            case 'P' -> PUZZLE;
            case 'T' -> TRAP;
            case 'Y' -> MINIBOSS;
            case 'F' -> 0xFFE58BC4;
            case 'B' -> BLOOD;
            default -> NORMAL;
        };
    }

    // ---------------------------------------------------------------- pages

    private void drawMain(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, Online.Status online) {
        title(g, "How do you want to play?", x, y);
        String note = online.note();
        int gap = 8;
        int tw = (w - gap) / 2;
        // Two rows of tiles fill the window; the third (the editors) is scrolled to. Only the tiles scroll.
        int viewY = y + 18;
        int viewH = h - 18;
        int th = (viewH - gap) / 2;
        mainMaxScroll = th + gap;
        mainScroll = Math.max(0, Math.min(mainMaxScroll, mainScroll));
        mainView = new int[] {x, viewY, w, viewH};
        if (!over(mx, my, x, viewY, w, viewH)) {
            // Tiles scrolled out of the window don't light up under the title or the footer.
            mx = -1;
            my = -1;
        }
        int gy = viewY - mainScroll;
        beginClip(g, x, viewY, w, viewH);
        tile(g, mx, my, x, gy, tw, th, "Make a party", "Your own dungeon. Private to play solo, or public for anyone.",
            null, ENTRANCE, Glyph.PARTY, () -> page = Page.MAKE);
        // Joining by address needs no lobby, so this stays open even when the lobby can't be reached.
        tile(g, mx, my, x + tw + gap, gy, tw, th, "Join a party",
            online.ready() ? "Browse open parties and join one." : "Join a friend's party by its address.",
            online.ready() ? null : note, NORMAL, Glyph.JOIN, Online::openBrowser);
        tile(g, mx, my, x, gy + th + gap, tw, th, "1v1", "Race another player to 270 or 300 score.",
            note, PUZZLE, Glyph.DUEL, online.ready() ? () -> Online.openQueue("DUEL_1V1") : null);
        tile(g, mx, my, x + tw + gap, gy + th + gap, tw, th, "2v2", "Two teams of two race to 270 or 300. Alone or with a friend.",
            note, TRAP, Glyph.TEAMS, online.ready() ? () -> Online.openQueue("DUEL_2V2") : null);
        int ey = gy + 2 * (th + gap);
        tile(g, mx, my, x, ey, tw, th, "Kit editor", "The items you start every dungeon with.",
            null, GOLD, Glyph.KIT, () -> minecraft.gui.setScreen(new KitEditorScreen(this)));
        tile(g, mx, my, x + tw + gap, ey, tw, th, "Map editor", "Build your own dungeon maps.",
            "Coming soon", MINIBOSS, Glyph.MAP, () -> minecraft.gui.setScreen(new MapEditorScreen(this)));
        endClip(g);
        scrollbar(g, x + w + 5, viewY, viewH, viewH, viewH + mainMaxScroll, mainScroll, mainMaxScroll);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int[] v = mainView;
        if (page == Page.MAIN && v != null && mx >= v[0] && mx < v[0] + v[2] && my >= v[1] && my < v[1] + v[3]) {
            mainScroll = Math.max(0, Math.min(mainMaxScroll, mainScroll - (int) Math.signum(scrollY) * 24));
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    private void drawMake(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, Online.Status online) {
        title(g, "Who can join?", x, y);
        textRight(g, "5 players by default. Change it, or make it a duel, in the Mode tab.", x + w, y + 2, DIM);
        int gy = y + 18;
        int gap = 8;
        int tw = (w - gap) / 2;
        int th = h - 18;
        tile(g, mx, my, x, gy, tw, th, "Private", "Only players you invite can join. This is how you play solo.",
            null, GOLD, Glyph.LOCK, () -> make(false));
        tile(g, mx, my, x + tw + gap, gy, tw, th, "Public",
            online.ready() ? "Listed in the party browser for anyone to join." : "Open to anyone with your party's address.",
            null, GOOD, Glyph.GLOBE, () -> make(true));
    }

    /** Opens the host's world as a private or public party; sharing starts once it is open. */
    private void make(boolean open) {
        Online.hostParty(open);
        EngineWorld.open();
    }

    @Override
    public void onClose() {
        back();
    }
}
