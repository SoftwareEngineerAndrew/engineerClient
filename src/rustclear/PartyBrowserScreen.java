package net.rustclear.mod;

import static net.rustclear.mod.ui.Theme.*;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.rustclear.mod.online.LobbyClient;
import net.rustclear.mod.online.Relays;
import net.rustclear.mod.ui.RustclearScreen;

/**
 * "Join a party": the lobby's open parties on this mod version, with their members, size and
 * mode, each with Join. Joining connects to the party's relay address; this game opens no world
 * of its own. Joining by address (a friend's invite) works here too, even with the lobby down.
 */
public final class PartyBrowserScreen extends RustclearScreen {
    private static final int ROW_H = 24;

    private List<LobbyClient.Listing> parties = List.of();
    private boolean loading;
    private String problem;
    private int scroll;
    private int maxScroll;
    private EditBox address;
    private String note;

    public PartyBrowserScreen(Screen parent) {
        super(Component.literal("Join a party"), parent);
        refresh();
    }

    private void refresh() {
        if (!LobbyClient.configured()) {
            problem = "Online features unavailable. You can still join by address below.";
            return;
        }
        loading = true;
        problem = null;
        LobbyClient.parties().whenComplete((list, error) -> Minecraft.getInstance().execute(() -> {
            loading = false;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                problem = cause.getMessage() != null ? cause.getMessage() : "Online features unavailable.";
                parties = List.of();
            } else {
                parties = list;
            }
        }));
    }

    @Override
    protected void init() {
        layoutPanel(340, 230);
        String previous = address == null ? "" : address.getValue();
        address = input(px + 16, py + ph - 41, 160, "friend.e4mc.link", 253);
        address.setValue(previous);
        addRenderableWidget(address);
    }

    @Override
    protected void extractFrame(GuiGraphicsExtractor g, int mx, int my, float delta) {
        panel(g);
        header(g, "Join a party", loading ? null : parties.size() + " OPEN", GOOD);
        int x = px + 10;
        int w = pw - 20;
        int y = py + 30;
        int tx = x + title(g, "Open parties", x, y) + 6;
        pill(g, "v" + RustclearMod.version(), tx, y, SILVER);
        link(g, mx, my, "Refresh", x + w, y, this::refresh);

        int listY = y + 17;
        int listBottom = py + ph - 52;
        int visible = Math.max(1, (listBottom - listY) / ROW_H);
        maxScroll = Math.max(0, parties.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        if (loading) {
            text(g, "Loading parties", x + 4, listY + 6, MUTED);
            pulse(g, x + 4 + font.width("Loading parties") + 6, listY + 9, GOLD);
        } else if (problem != null) {
            text(g, fit(problem, w - 8), x + 4, listY + 6, BAD);
        } else if (parties.isEmpty()) {
            text(g, "No open parties right now. Make one from the menu.", x + 4, listY + 6, DIM);
        }
        for (int i = 0; i < visible && scroll + i < parties.size(); i++) {
            row(g, mx, my, parties.get(scroll + i), x, listY + i * ROW_H, w);
        }
        scrollbar(g, x + w + 3, listY, visible * ROW_H, visible, parties.size(), scroll, maxScroll);

        // Join by address: works without the lobby.
        int ay = py + ph - 44;
        rect(g, x, ay - 6, w, 1, PANEL_EDGE);
        inputFrame(g, x, ay, 172, address != null && address.isFocused(), false);
        text(g, "Join by address", x + 2, ay - 14 + 1, SILVER);
        choice(g, mx, my, "Join", x + 176, ay, false, () -> {
            String value = address == null ? "" : address.getValue();
            if (Relays.cleanAddress(value) == null) {
                note = "Paste the party's address (a host name; IP addresses aren't allowed).";
            } else {
                Online.join(value);
            }
        });

        int by = py + ph - 23;
        button(g, mx, my, "Back", px + pw - 8, by, Style.SECONDARY, this::onClose);
        if (note != null) {
            text(g, fit(note, pw - 80), x, by + 5, BAD);
        }
    }

    private void row(GuiGraphicsExtractor g, int mx, int my, LobbyClient.Listing party, int x, int y, int w) {
        boolean full = party.count >= party.max;
        boolean hover = over(mx, my, x, y, w, ROW_H - 3);
        round(g, x, y, w, ROW_H - 3, hover ? ROW_HOVER : RAIL);
        boolean duel = !"PARTY".equals(party.mode);
        avatar(g, party.leader, x + 5, y + 5, duel ? PUZZLE : ENTRANCE);
        int nx = x + 22;
        text(g, party.leader + "'s party", nx, y + 3, TEXT);
        String mode = modeLabel(party.mode).toUpperCase(Locale.ROOT);
        pill(g, mode, nx + font.width(party.leader + "'s party") + 5, y + 1, duel ? PUZZLE : ENTRANCE);
        int joinW = choiceWidth("Join");
        int countX = x + w - joinW - 10;
        text(g, fit(String.join(", ", party.members), countX - nx - 30), nx, y + 12, MUTED);
        String count = party.count + "/" + party.max;
        textRight(g, count, countX, y + 7, full ? BAD : GOOD);
        choice(g, mx, my, full ? "Full" : "Join", x + w - joinW - 4, y + 4, false, full ? null : () -> Online.join(party.address));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public void onClose() {
        back();
    }
}
