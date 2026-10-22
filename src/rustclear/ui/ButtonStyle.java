package net.rustclear.mod.ui;

import static net.rustclear.mod.ui.Theme.*;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * How Rustclear draws a button outside its own screens (the title screen's): the slate panel of
 * the mod's GUIs, a gold edge and lift on hover, greyed out when inactive. [brand] buttons get the
 * ADS logo and gold lettering, as the ADS title button does.
 */
public final class ButtonStyle {
    /** Hover progress per button, eased each frame. */
    private static final Map<AbstractWidget, Float> HOVER = new WeakHashMap<>();

    private ButtonStyle() {
    }

    /** Draws [button] with [label] (the button's own text when null) in Rustclear's style. */
    public static void draw(GuiGraphicsExtractor g, AbstractWidget button, boolean brand) {
        Font font = Minecraft.getInstance().font;
        boolean active = button.active;
        float target = active && button.isHoveredOrFocused() ? 1f : 0f;
        float t = HOVER.getOrDefault(button, target);
        t += (target - t) * 0.35f;
        if (Math.abs(target - t) < 0.02f) {
            t = target;
        }
        HOVER.put(button, t);

        int x = button.getX();
        int y = button.getY() - Math.round(t);
        int w = button.getWidth();
        int h = button.getHeight();
        int edge = brand ? lerp(GOLD_DEEP, GOLD, t) : lerp(PANEL_EDGE, GOLD_DEEP, t);
        RustclearScreen.round(g, x, y, w, h, active ? lerp(brand ? lerp(PANEL, GOLD_DEEP, 0.18f) : PANEL, ROW_HOVER, t) : alpha(RAIL, 0xE0));
        RustclearScreen.outline(g, x, y, w, h, active ? edge : PANEL_EDGE);
        if (active) {
            g.fillGradient(x + 1, y + 1, x + w - 1, y + h / 2, 0x14FFFFFF, 0x00FFFFFF);
        }
        if (t > 0) {
            RustclearScreen.rect(g, x + 3, y + h - 1, w - 6, 1, alpha(GOLD, Math.round(0xFF * t)));
        }
        String label = button.getMessage().getString();
        if (label.isEmpty()) {
            return;
        }
        int textY = y + (h - 8) / 2;
        if (brand) {
            // The ADS logo, then the label (ADS) centred.
            int size = Math.max(8, h - 6);
            RustclearScreen.logo(g, x + 4, y + (h - size) / 2, size);
            String text = label.toUpperCase(java.util.Locale.ROOT);
            g.text(font, text, x + (w - font.width(text)) / 2, textY, lerp(GOLD, 0xFFFFFFFF, t * 0.25f), true);
            return;
        }
        String text = fit(font, label, w - 8);
        int color = !active ? DIM : lerp(lerp(TEXT, MUTED, 0.15f), TEXT, t);
        g.text(font, text, x + (w - font.width(text)) / 2, textY, color, active);
    }

    /** Just the panel, for an icon button whose icon is drawn on top. */
    public static void drawFrame(GuiGraphicsExtractor g, AbstractWidget button) {
        boolean active = button.active;
        float target = active && button.isHoveredOrFocused() ? 1f : 0f;
        float t = HOVER.getOrDefault(button, target);
        t += (target - t) * 0.35f;
        if (Math.abs(target - t) < 0.02f) {
            t = target;
        }
        HOVER.put(button, t);
        int x = button.getX();
        int y = button.getY();
        int w = button.getWidth();
        int h = button.getHeight();
        RustclearScreen.round(g, x, y, w, h, active ? lerp(PANEL, ROW_HOVER, t) : alpha(RAIL, 0xE0));
        RustclearScreen.outline(g, x, y, w, h, active ? lerp(PANEL_EDGE, GOLD_DEEP, t) : PANEL_EDGE);
        if (t > 0) {
            RustclearScreen.rect(g, x + 3, y + h - 1, w - 6, 1, alpha(GOLD, Math.round(0xFF * t)));
        }
    }

    private static String fit(Font font, String s, int width) {
        if (font.width(s) <= width) {
            return s;
        }
        while (!s.isEmpty() && font.width(s + "…") > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }
}
