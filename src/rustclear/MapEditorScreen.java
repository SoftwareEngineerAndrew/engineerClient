package net.rustclear.mod;

import static net.rustclear.mod.ui.Theme.*;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.rustclear.mod.ui.RustclearScreen;

/** The Rustclear menu's Map editor: not built yet, so it only says so. */
public final class MapEditorScreen extends RustclearScreen {
    public MapEditorScreen(Screen parent) {
        super(Component.literal("Map editor"), parent);
    }

    @Override
    protected void init() {
        layoutPanel(280, 120);
    }

    @Override
    protected void extractFrame(GuiGraphicsExtractor g, int mx, int my, float delta) {
        panel(g);
        header(g, "Map editor", "SOON", GOLD);
        int x = px + 14;
        int y = py + 34;
        rect(g, x, y + 1, 3, 9, GOLD);
        title(g, "Map editor — work in progress", x + 8, y - 1);
        int ly = y + 18;
        for (String line : wrap("Coming soon: build and edit your own dungeon maps here.", pw - 28)) {
            text(g, line, x, ly, MUTED);
            ly += 11;
        }
        int by = py + ph - 23;
        rect(g, px + 8, by - 5, pw - 16, 1, PANEL_EDGE);
        button(g, mx, my, "Back", px + pw - 8, by, Style.SECONDARY, this::onClose);
    }

    @Override
    public void onClose() {
        back();
    }
}
