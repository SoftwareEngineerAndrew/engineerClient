package net.rustclear.mod.ui;

import static net.rustclear.mod.ui.Theme.*;

import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A short message in Rustclear's panel, with one way on: shown when a party ends ("The host
 * ended the party."), when a guest is removed, or when something online can't be reached. Used
 * instead of Minecraft's disconnect screen so the end of a party doesn't read as an error.
 */
public final class NoticeScreen extends RustclearScreen {
    private final String heading;
    private final String message;
    private final int accent;
    private final String buttonLabel;
    private final Supplier<Screen> next;

    public NoticeScreen(String heading, String message, int accent, String buttonLabel, Supplier<Screen> next) {
        super(Component.literal(heading), null);
        this.heading = heading;
        this.message = message;
        this.accent = accent;
        this.buttonLabel = buttonLabel;
        this.next = next;
    }

    @Override
    protected void init() {
        layoutPanel(260, 120);
    }

    @Override
    protected void extractFrame(GuiGraphicsExtractor g, int mx, int my, float delta) {
        panel(g);
        header(g, heading, null, 0);
        int x = px + 14;
        int y = py + 34;
        rect(g, x, y + 1, 3, 9, accent);
        title(g, heading, x + 8, y - 1);
        int ly = y + 18;
        for (String line : wrap(message, pw - 28)) {
            text(g, line, x, ly, MUTED);
            ly += 11;
        }
        int by = py + ph - 23;
        rect(g, px + 8, by - 5, pw - 16, 1, PANEL_EDGE);
        button(g, mx, my, buttonLabel, px + pw - 8, by, Style.PRIMARY, this::onClose);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(next.get());
    }
}
