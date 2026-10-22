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
 * What the title screen's ADS button opens, filling the window: on the left who you are and
 * what is online, on the right the ways to play as
 * large tiles. Make a party (the host's own world, private or public), join one, or queue for a
 * 1v1 or 2v2; scrolling down, the Kit editor and the (coming) Map editor. A
 * private party nobody is invited to is how you play solo, so that path never needs anything
 * online. The online options say why when they can't be used rather than disappearing.
 */
public final class RustclearMenuScreen extends RustclearScreen {
    private enum Page { MAIN, MAKE }

    private Page page = Page.MAIN;
    /** How far the main page's tiles are scrolled, in pixels, and how far they can be. */
    private int mainScroll;
    private int mainMaxScroll;
    /** The main page's scrolled window in the last frame (x, y, w, h), for the mouse wheel. */
    private int[] mainView;

    public RustclearMenuScreen(Screen parent) {
        super(Component.literal("ADS"), parent);
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

        int ty = y;
        String tag = "Advanced Dungeon Simulator";
        textCenter(g, fit(tag, w), x + w / 2, ty, MUTED);

        // Who and what: the player, the mod, the lobby, the relay.
        int iy = ty + 20;
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

    // ---------------------------------------------------------------- pages

    private void drawMain(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, Online.Status online) {
        title(g, "How do you want to play?", x, y);
        String note = online.note();
        int gap = 8;
        int tw = (w - gap) / 2;
        // Practice across the top, then two rows of tiles fill the window; the third (the editors) is
        // scrolled to. Only the tiles scroll.
        int viewY = y + 18;
        int viewH = h - 18;
        int ph = Math.max(34, Math.min(56, viewH / 5));
        int th = (viewH - ph - 2 * gap) / 2;
        mainMaxScroll = th + gap;
        mainScroll = Math.max(0, Math.min(mainMaxScroll, mainScroll));
        mainView = new int[] {x, viewY, w, viewH};
        if (!over(mx, my, x, viewY, w, viewH)) {
            // Tiles scrolled out of the window don't light up under the title or the footer.
            mx = -1;
            my = -1;
        }
        int top = viewY - mainScroll;
        beginClip(g, x, viewY, w, viewH);
        card(g, mx, my, x, top, w, ph, "Practice", "Full clears, a fixed seed, single rooms, blood, and every boss phase. Solo.",
            null, GOLD, Glyph.TARGET, () -> minecraft.gui.setScreen(new com.engineerclient.ads.PracticeScreen(this)));
        int gy = top + ph + gap;
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
