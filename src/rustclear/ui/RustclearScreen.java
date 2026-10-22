package net.rustclear.mod.ui;

import static net.rustclear.mod.ui.Theme.*;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.rustclear.mod.Online;
import net.rustclear.mod.Protocol;
import net.rustclear.mod.RustclearMod;
import net.rustclear.mod.engine.DungeonClass;

/**
 * The base of every Rustclear screen: a hand-drawn slate panel with the dungeon-map emblem, and
 * the small set of components the screens are made of (rail tabs, buttons, chips, pills, switches,
 * option cards, a scrollbar, pixel digits). Nothing here uses Minecraft's own widgets except
 * [EditBox] for typing, framed to match.
 *
 * Components are immediate mode: each frame draws them and registers its click areas; a click is
 * matched against the areas of the last whole frame, topmost (last drawn) first.
 */
public abstract class RustclearScreen extends Screen {
    public enum Style { PRIMARY, SECONDARY, DANGER }

    protected final Screen parent;

    /** Panel geometry, set by [layoutPanel]. */
    protected int px;
    protected int py;
    protected int pw;
    protected int ph;

    /** Click areas of the last whole frame; [building] collects the next one while it's drawn. */
    private volatile List<Hit> hits = List.of();
    private List<Hit> building = new ArrayList<>();
    /** Animation progress per key (switches, selections), eased toward its target each frame. */
    private final Map<String, Float> anim = new HashMap<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    protected RustclearScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** Centres a panel of at most [maxW] x [maxH] in the window, 8 px from each edge at least. */
    protected void layoutPanel(int maxW, int maxH) {
        pw = Math.min(maxW, width - 16);
        ph = Math.min(maxH, height - 16);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    // ---------------------------------------------------------------- primitives

    /** The ADS logo (art/logo.svg, rendered at 128 px and filtered smooth by its .mcmeta). */
    public static final Identifier LOGO = Identifier.fromNamespaceAndPath("engineerclient", "textures/gui/logo.png");

    /** The ADS logo, [size] px square at (x, y). */
    public static void logo(GuiGraphicsExtractor g, int x, int y, int size) {
        g.blit(RenderPipelines.GUI_TEXTURED, LOGO, x, y, 0f, 0f, size, size, 128, 128, 128, 128);
    }

    public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + h, c);
    }

    /** A rectangle with its corner pixels cut, which reads as rounded at GUI scale. */
    public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    public static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + 1, c);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    protected void text(GuiGraphicsExtractor g, String s, int x, int y, int c) {
        g.text(font, s, x, y, c, false);
    }

    protected void textShadow(GuiGraphicsExtractor g, String s, int x, int y, int c) {
        g.text(font, s, x, y, c, true);
    }

    protected void textRight(GuiGraphicsExtractor g, String s, int right, int y, int c) {
        g.text(font, s, right - font.width(s), y, c, false);
    }

    protected void textCenter(GuiGraphicsExtractor g, String s, int cx, int y, int c) {
        g.text(font, s, cx - font.width(s) / 2, y, c, false);
    }

    /** [s] cut with an ellipsis to fit [width] pixels. */
    protected String fit(String s, int width) {
        if (font.width(s) <= width) {
            return s;
        }
        while (!s.isEmpty() && font.width(s + "…") > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    /** Eases the animation [key] toward on (1) or off (0) and returns where it is now. */
    protected float ease(String key, boolean on) {
        float target = on ? 1f : 0f;
        float current = anim.getOrDefault(key, target);
        current += (target - current) * 0.35f;
        if (Math.abs(target - current) < 0.02f) {
            current = target;
        }
        anim.put(key, current);
        return current;
    }

    /** Registers a click area for this frame. Later areas sit on top of earlier ones. */
    protected void click(int x, int y, int w, int h, Runnable action) {
        Hit hit = clipped(x, y, w, h, action);
        if (hit != null) {
            building.add(hit);
        }
    }

    /** Registers a right-click area for this frame, as [click] does for the left button. */
    protected void rightClick(int x, int y, int w, int h, Runnable action) {
        Hit hit = clipped(x, y, w, h, action);
        if (hit != null) {
            rightBuilding.add(hit);
        }
    }

    /** The area cut to a scrolled area's window, or null when none of it shows. */
    private Hit clipped(int x, int y, int w, int h, Runnable action) {
        if (clip != null) {
            // Only the part inside a scrolled area's window can be clicked.
            int x0 = Math.max(x, clip[0]);
            int y0 = Math.max(y, clip[1]);
            int x1 = Math.min(x + w, clip[0] + clip[2]);
            int y1 = Math.min(y + h, clip[1] + clip[3]);
            if (x1 <= x0 || y1 <= y0) {
                return null;
            }
            x = x0;
            y = y0;
            w = x1 - x0;
            h = y1 - y0;
        }
        return new Hit(x, y, w, h, action);
    }

    /** Right-click areas, kept as [hits] are; most screens register none. */
    private volatile List<Hit> rightHits = List.of();
    private List<Hit> rightBuilding = new ArrayList<>();

    /** The window click areas are cut to while a scrolled area is drawn, or null. */
    private int[] clip;

    /** Starts a scrolled area: drawing and click areas are cut to this window until [endClip]. */
    protected void beginClip(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        clip = new int[] {x, y, w, h};
        g.enableScissor(x, y, x + w, y + h);
    }

    protected void endClip(GuiGraphicsExtractor g) {
        clip = null;
        g.disableScissor();
    }

    protected static boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ---------------------------------------------------------------- components

    /** The darkened backdrop and the panel, with a faint light along its top. */
    protected void panel(GuiGraphicsExtractor g) {
        g.fillGradient(0, 0, width, height, SHADE_TOP, SHADE_BOTTOM);
        round(g, px, py, pw, ph, PANEL);
        outline(g, px, py, pw, ph, PANEL_EDGE);
        g.fillGradient(px + 1, py + 1, px + pw - 1, py + 22, 0x14FFFFFF, 0x00FFFFFF);
    }

    /**
     * The header: the ADS logo, ADS in gold, [subtitle], and a badge on the
     * right ([badge] in [badgeColor]; none when null). Ends with the rule under it at py + 22.
     */
    protected void header(GuiGraphicsExtractor g, String subtitle, String badge, int badgeColor) {
        logo(g, px + 7, py + 5, 12);
        g.text(font, "ADS", px + 22, py + 8, GOLD, true);
        text(g, subtitle, px + 22 + font.width("ADS") + 6, py + 8, MUTED);
        if (badge != null) {
            badge(g, badge, px + pw - 8, py + 5, badgeColor);
        }
        rect(g, px + 1, py + 22, pw - 2, 1, PANEL_EDGE);
    }

    /** A status badge ending at [right]: a tinted pill with a square and a word, as RANKED. Returns its left edge. */
    protected int badge(GuiGraphicsExtractor g, String label, int right, int y, int color) {
        int bw = font.width(label) + 16;
        int bx = right - bw;
        round(g, bx, y, bw, 13, alpha(color, 0x26));
        rect(g, bx + 5, y + 5, 3, 3, color);
        text(g, label, bx + 11, y + 3, color);
        return bx;
    }

    /** The rail's background down the left of the panel, [w] wide, under the header. */
    protected void railBackground(GuiGraphicsExtractor g, int w) {
        rect(g, px + 1, py + 23, w - 1, ph - 24, RAIL);
        rect(g, px + w, py + 23, 1, ph - 24, PANEL_EDGE);
    }

    /**
     * A rail tab: a colour square, [name], and a one-line [summary] under it. Highlighted when [on]
     * or hovered. [summaryColor] 0 picks the default (dim for "Random"-like summaries).
     */
    protected void railItem(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, boolean on,
                            String name, String summary, int color, int summaryColor, Runnable action) {
        boolean hover = over(mx, my, x, y, w, 26);
        if (on || hover) {
            round(g, x, y, w, 26, on ? ROW_ON : ROW_HOVER);
        }
        rect(g, x + 6, y + 6, 6, 6, on ? color : lerp(color, RAIL, 0.45f));
        text(g, name, x + 17, y + 5, on ? TEXT : lerp(TEXT, MUTED, 0.4f));
        text(g, fit(summary, w - 20), x + 17, y + 15, summaryColor);
        click(x, y, w, 26, action);
    }

    /** A tinted word in a rounded box, as the RANDOM / CUSTOM pills. Returns its width. */
    protected int pill(GuiGraphicsExtractor g, String label, int x, int y, int color) {
        int w = font.width(label) + 8;
        round(g, x, y, w, 12, alpha(color, 0x24));
        text(g, label, x + 4, y + 2, color);
        return w;
    }

    /** A section title in white with a shadow, as each tab's heading. Returns its width. */
    protected int title(GuiGraphicsExtractor g, String title, int x, int y) {
        g.text(font, title, x, y + 2, TEXT, true);
        return font.width(title);
    }

    /** An underlined-on-hover text link ending at [right]. */
    protected void link(GuiGraphicsExtractor g, int mx, int my, String label, int right, int y, Runnable action) {
        int lw = font.width(label);
        int lx = right - lw;
        boolean hover = over(mx, my, lx, y, lw, 11);
        text(g, label, lx, y + 2, hover ? TEXT : MUTED);
        if (hover) {
            rect(g, lx, y + 11, lw, 1, TEXT);
        }
        click(lx, y, lw, 11, action);
    }

    /** A filter or preset chip at [x]: gold when [on]. Returns its width. */
    protected int chip(GuiGraphicsExtractor g, int mx, int my, String label, int x, int y, boolean on, Runnable action) {
        int cw = font.width(label) + 8;
        boolean hover = over(mx, my, x, y, cw, 12);
        if (on || hover) {
            round(g, x, y, cw, 12, on ? alpha(GOLD, 0x30) : ROW_HOVER);
        }
        text(g, label, x + 4, y + 2, on ? GOLD : hover ? TEXT : MUTED);
        if (action != null) {
            click(x, y, cw, 12, action);
        }
        return cw;
    }

    /** A boxed choice 13 px tall at [x], as the speed presets: gold when [on]. Returns its width. */
    protected int choice(GuiGraphicsExtractor g, int mx, int my, String label, int x, int y, boolean on, Runnable action) {
        int cw = font.width(label) + 10;
        boolean hover = action != null && over(mx, my, x, y, cw, 13);
        round(g, x, y, cw, 13, on ? alpha(GOLD, 0x30) : hover ? ROW_HOVER : RAIL);
        text(g, label, x + 5, y + 3, on ? GOLD : hover ? TEXT : action == null ? DIM : MUTED);
        if (action != null) {
            click(x, y, cw, 13, action);
        }
        return cw;
    }

    /** A dungeon class's color, opaque, for screens. */
    protected static int classColor(DungeonClass c) {
        return 0xFF000000 | c.rgb;
    }

    /**
     * The five classes as choices in a row at [x], [picked] lit in its own color; hovering one shows
     * its ability and ultimate. Clicking calls [pick] with it, or nothing when [pick] is null.
     */
    protected void classPicker(GuiGraphicsExtractor g, int mx, int my, int x, int y, DungeonClass picked,
                               java.util.function.Consumer<DungeonClass> pick) {
        for (DungeonClass c : DungeonClass.values()) {
            int color = classColor(c);
            boolean on = c == picked;
            int cw = font.width(c.title) + 10;
            boolean hover = over(mx, my, x, y, cw, 13);
            round(g, x, y, cw, 13, on ? alpha(color, 0x40) : hover ? ROW_HOVER : RAIL);
            if (on) {
                outline(g, x, y, cw, 13, alpha(color, 0xA0));
            }
            text(g, c.title, x + 5, y + 3, on || hover ? color : lerp(color, MUTED, 0.45f));
            if (pick != null) {
                click(x, y, cw, 13, () -> pick.accept(c));
            }
            if (hover) {
                g.setComponentTooltipForNextFrame(font, classTooltip(c), mx, my);
            }
            x += cw + 3;
        }
    }

    /** A class's name, ability and ultimate with what each does, for a tooltip. */
    protected static List<Component> classTooltip(DungeonClass c) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(c.color + c.title));
        lines.add(Component.literal("§6Ability: " + c.ability + " §8(Ctrl+Q)"));
        for (String line : c.abilityText) {
            lines.add(Component.literal("§7" + line));
        }
        lines.add(Component.literal(""));
        lines.add(Component.literal("§6Ultimate: " + c.ultimate + " §8(Q)"));
        for (String line : c.ultimateText) {
            lines.add(Component.literal("§7" + line));
        }
        return lines;
    }

    /** Width a [choice] with this label takes. */
    protected int choiceWidth(String label) {
        return font.width(label) + 10;
    }

    /**
     * A footer button 17 px tall ending at [right]; gold when primary, red-tinted when danger,
     * greyed and unclickable when [action] is null. Returns its left edge.
     */
    protected int button(GuiGraphicsExtractor g, int mx, int my, String label, int right, int y, Style style, Runnable action) {
        int w = font.width(label) + 16;
        int x = right - w;
        boolean enabled = action != null;
        boolean hover = enabled && over(mx, my, x, y, w, 17);
        switch (style) {
            case PRIMARY -> {
                int bg = !enabled ? GOLD_DEEP : hover ? lerp(GOLD, 0xFFFFFFFF, 0.2f) : GOLD;
                round(g, x, y, w, 17, bg);
                text(g, label, x + 8, y + 5, enabled ? ON_GOLD : lerp(ON_GOLD, GOLD, 0.4f));
            }
            case DANGER -> {
                round(g, x, y, w, 17, hover ? alpha(BAD, 0x50) : alpha(BAD, 0x22));
                outline(g, x, y, w, 17, hover ? BAD : alpha(BAD, 0x70));
                text(g, label, x + 8, y + 5, enabled ? BAD : DIM);
            }
            default -> {
                round(g, x, y, w, 17, hover ? ROW_HOVER : RAIL);
                outline(g, x, y, w, 17, hover ? DIM : PANEL_EDGE);
                text(g, label, x + 8, y + 5, !enabled ? DIM : hover ? TEXT : MUTED);
            }
        }
        if (enabled) {
            click(x, y, w, 17, action);
        }
        return x;
    }

    /** A sliding on/off switch, 18 x 8, eased under [key]. */
    protected void toggle(GuiGraphicsExtractor g, String key, int x, int y, boolean on, int color) {
        float t = ease(key, on);
        round(g, x, y, 18, 8, lerp(DIM, color, t));
        int knob = x + 1 + Math.round(t * 10);
        rect(g, knob, y + 1, 6, 6, lerp(MUTED, TEXT, t));
    }

    /**
     * A large option card, as on the Rustclear menu: an icon tile in [color], a [name], a [detail]
     * line, and a right-hand [note] (gold, or dim when disabled). [action] null greys it out.
     */
    protected void card(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, String name, String detail,
                        String note, int color, Glyph glyph, Runnable action) {
        boolean enabled = action != null;
        boolean hover = enabled && over(mx, my, x, y, w, h);
        float t = ease("card:" + name, hover);
        round(g, x, y, w, h, enabled ? lerp(RAIL, ROW_HOVER, t) : RAIL);
        outline(g, x, y, w, h, enabled ? lerp(PANEL_EDGE, lerp(PANEL_EDGE, color, 0.6f), t) : PANEL_EDGE);
        if (enabled && t > 0) {
            rect(g, x, y + 3, 1, h - 6, alpha(color, Math.round(255 * t)));
        }
        int tile = h - 10;
        int tx = x + 6;
        int ty = y + 5;
        round(g, tx, ty, tile, tile, alpha(enabled ? color : DIM, enabled ? 0x30 : 0x20));
        glyph.draw(g, tx + tile / 2, ty + tile / 2, 1, enabled ? color : DIM);
        int textX = tx + tile + 8;
        int nameY = y + h / 2 - 9;
        int noteW = note == null ? 0 : font.width(note) + 12;
        g.text(font, name, textX, nameY, enabled ? TEXT : MUTED, enabled);
        text(g, fit(detail, x + w - textX - noteW - 6), textX, nameY + 11, enabled ? MUTED : DIM);
        if (note != null) {
            int nx = x + w - noteW - 4;
            int nc = enabled ? GOLD : DIM;
            round(g, nx, y + h / 2 - 6, noteW, 12, alpha(nc, 0x24));
            text(g, note, nx + 6, y + h / 2 - 4, nc);
        }
        if (enabled) {
            click(x, y, w, h, action);
        }
    }

    /** A framed text field to hold an [EditBox] placed at (x + 14, y + 3), with a magnifier if [search]. */
    protected void inputFrame(GuiGraphicsExtractor g, int x, int y, int w, boolean focused, boolean search) {
        round(g, x, y, w, 14, RAIL);
        outline(g, x, y, w, 14, focused ? GOLD_DEEP : PANEL_EDGE);
        if (search) {
            g.fill(x + 5, y + 4, x + 8, y + 5, MUTED);
            g.fill(x + 4, y + 5, x + 5, y + 8, MUTED);
            g.fill(x + 8, y + 5, x + 9, y + 8, MUTED);
            g.fill(x + 5, y + 8, x + 8, y + 9, MUTED);
            g.fill(x + 9, y + 9, x + 11, y + 11, MUTED);
        }
    }

    /** An [EditBox] with no border of its own, for an [inputFrame]. */
    protected EditBox input(int x, int y, int w, String hint, int maxLength) {
        EditBox box = new EditBox(font, x, y, w, 10, Component.literal(hint));
        box.setBordered(false);
        box.setMaxLength(maxLength);
        box.setTextColor(TEXT);
        box.setHint(Component.literal(hint));
        return box;
    }

    /** A thin scrollbar at [x] for a list of [rows], [visible] at once, scrolled to [scroll] of [maxScroll]. */
    protected static void scrollbar(GuiGraphicsExtractor g, int x, int y, int trackH, int visible, int rows, int scroll, int maxScroll) {
        if (maxScroll <= 0) {
            return;
        }
        int thumbH = Math.max(10, trackH * visible / Math.max(1, rows));
        int thumbY = y + (trackH - thumbH) * scroll / maxScroll;
        rect(g, x, y, 2, trackH, RAIL);
        rect(g, x, thumbY, 2, thumbH, DIM);
    }

    /** Three squares that pulse in turn, for something in progress. */
    protected static void pulse(GuiGraphicsExtractor g, int x, int y, int color) {
        long step = (System.currentTimeMillis() / 220) % 3;
        for (int i = 0; i < 3; i++) {
            rect(g, x + i * 5, y, 3, 3, i == step ? color : alpha(color, 0x50));
        }
    }

    /** A player's initial in a tile tinted [color], as the party and browser lists show members. */
    protected void avatar(GuiGraphicsExtractor g, String name, int x, int y, int color) {
        round(g, x, y, 11, 11, alpha(color, 0x40));
        String initial = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        textCenter(g, initial, x + 6, y + 2, color);
    }

    /** 3x5 pixel digits, one row per int (bit 2 is the left column). */
    private static final int[][] DIGITS = {
        {7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 7, 1, 7}, {5, 5, 7, 1, 1},
        {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 1, 1, 1}, {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7},
    };

    /** How wide [bigNumber] draws [digits] at [scale]. */
    public static int bigNumberWidth(String digits, int scale) {
        int w = 0;
        for (int i = 0; i < digits.length(); i++) {
            w += digits.charAt(i) == ':' ? 2 * scale : 4 * scale;
        }
        return Math.max(0, w - scale);
    }

    /** Draws [digits] (0-9 and ':') in the pixel font at [scale] px per pixel; returns the width drawn. */
    public static int bigNumber(GuiGraphicsExtractor g, String digits, int x, int y, int scale, int color) {
        int cx = x;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c == ':') {
                rect(g, cx, y + scale, scale, scale, color);
                rect(g, cx, y + 3 * scale, scale, scale, color);
                cx += 2 * scale;
                continue;
            }
            int[] rows = DIGITS[c - '0'];
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 3; col++) {
                    if ((rows[row] >> (2 - col) & 1) != 0) {
                        rect(g, cx + col * scale, y + row * scale, scale, scale, color);
                    }
                }
            }
            cx += 4 * scale;
        }
        return cx - x - scale;
    }

    /** One glyph pixel block: [w] x [h] cells at ([x], [y]) from the centre, each cell [s] px. */
    private static void cell(GuiGraphicsExtractor g, int cx, int cy, int s, int x, int y, int w, int h, int c) {
        rect(g, cx + x * s, cy + y * s, w * s, h * s, c);
    }

    /** A hollow box of glyph cells. */
    private static void box(GuiGraphicsExtractor g, int cx, int cy, int s, int x, int y, int w, int h, int c) {
        cell(g, cx, cy, s, x, y, w, 1, c);
        cell(g, cx, cy, s, x, y + h - 1, w, 1, c);
        cell(g, cx, cy, s, x, y, 1, h, c);
        cell(g, cx, cy, s, x + w - 1, y, 1, h, c);
    }

    /** Small pixel icons for cards and tiles, drawn around their centre at any whole scale. */
    // --- The party's mode (Party > Mode and the lobby) ---

    private static final int DEFAULT_PARTY_SIZE = 5;
    private static final int MAX_PARTY_SIZE = 50;
    private static final int MAX_TEAM_SIZE = 20;

    /** The team size the duel card picks: the party's when it is a duel, else the last one shown. */
    private int teamSize = 1;

    /** Players per team in a duel mode ("DUEL_3V3" is 3), or 0 for a party. */
    public static int teamSize(String mode) {
        if (mode == null || !mode.startsWith("DUEL_")) {
            return 0;
        }
        try {
            return Integer.parseInt(mode.substring(5, mode.indexOf('V')));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static String modeLabel(String mode) {
        int n = teamSize(mode);
        return n > 0 ? n + "v" + n : "Party";
    }

    /**
     * A party's mode, as on Party > Mode and the lobby's Mode tab: a party of the default size or
     * any size up to 50, or an NvN duel (1v1 up to 20v20) raced to 270 or 300 score. Only the
     * leader can change it, and the mode only at Mort.
     */
    protected void partyMode(GuiGraphicsExtractor g, int mx, int my, Protocol.PartyState party, int x, int y, int w) {
        title(g, "Mode", x, y);
        boolean lobby = "lobby".equals(party.phase);
        boolean edit = party.you && lobby;
        if (!party.you) {
            textRight(g, "Leader's pick", x + w, y + 2, MUTED);
        }
        int current = teamSize(party.mode);
        if (current > 0) {
            teamSize = current;
        }
        int n = teamSize;

        int cy = y + 18;
        String size = party.size == DEFAULT_PARTY_SIZE ? "Up to 5 players" : "Up to " + party.size + " players";
        card(g, mx, my, x, cy, w, 30, "Party", size + " clear one dungeon together.", current == 0 ? "Selected" : null,
            ENTRANCE, Glyph.PARTY, edit && current != 0 ? () -> RustclearMod.sendParty("mode", null, "PARTY") : null);
        cy += 34;
        card(g, mx, my, x, cy, w, 30, "Duel: " + n + "v" + n, "Two teams race the same dungeon to " + party.target + " score.",
            current > 0 ? "Selected" : null, PUZZLE, n == 1 ? Glyph.DUEL : Glyph.TEAMS,
            edit && current == 0 ? () -> RustclearMod.sendParty("mode", null, duelMode(n)) : null);
        cy += 38;

        // Team size: picking a size in a duel switches to it at once; in a party it sets the duel card.
        int lx = x + 70;
        text(g, "Teams", x, cy + 3, SILVER);
        int cx = lx;
        cx += choice(g, mx, my, "-", cx, cy, false, edit && n > 1 ? () -> pickTeamSize(n - 1, current) : null) + 3;
        String teams = n + "v" + n;
        text(g, teams, cx + 4, cy + 3, current > 0 ? GOLD : TEXT);
        cx += Math.max(font.width("20v20"), font.width(teams)) + 10;
        choice(g, mx, my, "+", cx, cy, false, edit && n < MAX_TEAM_SIZE ? () -> pickTeamSize(n + 1, current) : null);
        cy += 18;

        text(g, "Race to", x, cy + 3, SILVER);
        cx = lx;
        for (int t : Online.TARGETS) {
            int target = t;
            cx += choice(g, mx, my, t + " score", cx, cy, party.target == t,
                edit && party.target != t ? () -> RustclearMod.sendParty("target", target) : null) + 3;
        }
        cy += 18;

        text(g, "Party size", x, cy + 3, SILVER);
        cx = lx;
        boolean custom = party.size != DEFAULT_PARTY_SIZE;
        cx += choice(g, mx, my, "Default (5)", cx, cy, !custom,
            party.you && custom ? () -> RustclearMod.sendParty("size", DEFAULT_PARTY_SIZE) : null) + 3;
        int min = Math.max(1, party.members.size());
        cx += choice(g, mx, my, "-", cx, cy, false,
            party.you && party.size > min ? () -> RustclearMod.sendParty("size", party.size - 1) : null) + 3;
        String count = String.valueOf(party.size);
        text(g, count, cx + 4, cy + 3, custom ? GOLD : TEXT);
        cx += font.width("50") + 10;
        choice(g, mx, my, "+", cx, cy, false,
            party.you && party.size < MAX_PARTY_SIZE ? () -> RustclearMod.sendParty("size", party.size + 1) : null);
        cy += 20;

        String hint = !lobby ? "The mode can change at Mort, before the run starts."
            : current > 0 ? "A " + n + "v" + n + " needs exactly " + 2 * n + " players. First team to " + party.target + " score wins."
            : "Party size is how many can join (at most " + MAX_PARTY_SIZE + ").";
        text(g, fit(hint, w), x, cy, DIM);
    }

    private static String duelMode(int n) {
        return "DUEL_" + n + "V" + n;
    }

    private void pickTeamSize(int n, int current) {
        teamSize = n;
        if (current > 0) {
            RustclearMod.sendParty("mode", null, duelMode(n));
        }
    }

    public enum Glyph {
        /** Three heads: a party. */
        PARTY {
            @Override
            void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c) {
                cell(g, cx, cy, s, -2, -5, 4, 4, c);
                cell(g, cx, cy, s, -4, 0, 8, 4, c);
                cell(g, cx, cy, s, -9, -3, 3, 3, alpha(c, 0xB0));
                cell(g, cx, cy, s, -10, 1, 5, 3, alpha(c, 0xB0));
                cell(g, cx, cy, s, 6, -3, 3, 3, alpha(c, 0xB0));
                cell(g, cx, cy, s, 5, 1, 5, 3, alpha(c, 0xB0));
            }
        },
        /** A door with an arrow into it: joining. */
        JOIN {
            @Override
            void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c) {
                box(g, cx, cy, s, 0, -6, 7, 12, c);
                cell(g, cx, cy, s, -8, -1, 8, 2, c);
                cell(g, cx, cy, s, -3, -3, 2, 2, c);
                cell(g, cx, cy, s, -3, 1, 2, 2, c);
            }
        },
        /** Two blades crossed: a 1v1. */
        DUEL {
            @Override
            void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c) {
                for (int i = -5; i <= 5; i++) {
                    cell(g, cx, cy, s, i, i, 2, 2, c);
                    cell(g, cx, cy, s, -i, i, 2, 2, c);
                }
            }
        },
        /** Two pairs facing off: a 2v2. */
        TEAMS {
            @Override
            void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c) {
                cell(g, cx, cy, s, -9, -4, 3, 3, c);
                cell(g, cx, cy, s, -5, -4, 3, 3, c);
                cell(g, cx, cy, s, -10, 0, 9, 3, c);
                cell(g, cx, cy, s, 2, -4, 3, 3, alpha(c, 0xB0));
                cell(g, cx, cy, s, 6, -4, 3, 3, alpha(c, 0xB0));
                cell(g, cx, cy, s, 1, 0, 9, 3, alpha(c, 0xB0));
            }
        },
        /** An arrow left: back. */
        BACK {
            @Override
            void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c) {
                cell(g, cx, cy, s, -5, -1, 11, 2, c);
                cell(g, cx, cy, s, -3, -3, 2, 2, c);
                cell(g, cx, cy, s, -3, 1, 2, 2, c);
            }
        },
        /** A padlock: private. */
        LOCK {
            @Override
            void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c) {
                box(g, cx, cy, s, -3, -6, 7, 6, c);
                cell(g, cx, cy, s, -5, -1, 11, 7, c);
            }
        },
        /** A chestplate: the kit you start with. */
        KIT {
            @Override
            void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c) {
                cell(g, cx, cy, s, -7, -6, 4, 4, c);
                cell(g, cx, cy, s, 4, -6, 4, 4, c);
                cell(g, cx, cy, s, -3, -5, 7, 2, alpha(c, 0xB0));
                cell(g, cx, cy, s, -4, -3, 9, 10, c);
                cell(g, cx, cy, s, 0, -2, 1, 8, alpha(c, 0x70));
            }
        },
        /** A folded map with a route: the map editor. */
        MAP {
            @Override
            void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c) {
                box(g, cx, cy, s, -8, -6, 17, 13, c);
                cell(g, cx, cy, s, -3, -6, 1, 13, alpha(c, 0x70));
                cell(g, cx, cy, s, 3, -6, 1, 13, alpha(c, 0x70));
                cell(g, cx, cy, s, -6, 2, 3, 2, c);
                cell(g, cx, cy, s, -1, -1, 3, 2, c);
                cell(g, cx, cy, s, 4, -4, 3, 2, c);
            }
        },
        /** A ringed globe: public. */
        GLOBE {
            @Override
            void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c) {
                box(g, cx, cy, s, -6, -6, 13, 13, c);
                cell(g, cx, cy, s, 0, -6, 1, 13, c);
                cell(g, cx, cy, s, -6, 0, 13, 1, c);
            }
        },
        /** A target, ringed with a dot in the middle: practice. */
        TARGET {
            @Override
            void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c) {
                box(g, cx, cy, s, -7, -7, 15, 15, c);
                box(g, cx, cy, s, -4, -4, 9, 9, alpha(c, 0xB0));
                cell(g, cx, cy, s, -1, -1, 3, 3, c);
            }
        },
        /** A skull: a boss phase. */
        SKULL {
            @Override
            void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c) {
                cell(g, cx, cy, s, -6, -7, 13, 9, c);
                cell(g, cx, cy, s, -4, 2, 9, 4, c);
                cell(g, cx, cy, s, -4, -4, 3, 3, RAIL);
                cell(g, cx, cy, s, 2, -4, 3, 3, RAIL);
                cell(g, cx, cy, s, -2, 3, 1, 3, RAIL);
                cell(g, cx, cy, s, 2, 3, 1, 3, RAIL);
            }
        };

        abstract void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int c);
    }

    /**
     * A large option tile, for full-window menus: a coloured bar along the top, the [glyph] big in
     * a tinted square, the [name], a wrapped [detail], and a [note] pill at the bottom (gold, or dim
     * when disabled). [action] null greys it out.
     */
    protected void tile(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, String name, String detail,
                        String note, int color, Glyph glyph, Runnable action) {
        boolean enabled = action != null;
        boolean hover = enabled && over(mx, my, x, y, w, h);
        float t = ease("tile:" + name, hover);
        int accent = enabled ? color : DIM;
        round(g, x, y, w, h, enabled ? lerp(RAIL, ROW_HOVER, t) : RAIL);
        outline(g, x, y, w, h, enabled ? lerp(PANEL_EDGE, lerp(PANEL_EDGE, color, 0.7f), t) : PANEL_EDGE);
        rect(g, x + 1, y + 1, w - 2, 2, alpha(accent, enabled ? 0xC0 + Math.round(0x3F * t) : 0x60));

        // The glyph, as big as the tile allows (3x, 2x, or 1x in a short tile), in its colour's
        // square, and the text under it, centred together in the space above the note. A tile too
        // short for all of it drops detail lines, then the glyph.
        int room = h - (note != null ? 22 : 4);
        int scale = Math.min(w, h) >= 150 ? 3 : 2;
        int square = 22 * scale + 6;
        while (scale > 1 && 8 + square + 8 + 13 + 10 > room) {
            scale--;
            square = 22 * scale + 6;
        }
        boolean showGlyph = 8 + square + 8 + 13 <= room;
        int head = showGlyph ? square + 8 : 0;
        List<String> lines = wrap(detail, w - 20);
        int maxLines = Math.max(0, Math.min(3, (room - (showGlyph ? 8 : 4) - head - 13) / 10));
        if (lines.size() > maxLines) {
            lines = lines.subList(0, maxLines);
        }
        int block = head + 13 + lines.size() * 10;
        int sx = x + (w - square) / 2;
        int sy = y + Math.max(showGlyph ? 8 : 4, (room - block) / 2);
        if (showGlyph) {
            round(g, sx, sy - Math.round(2 * t), square, square, alpha(accent, enabled ? 0x2C + Math.round(0x18 * t) : 0x18));
            glyph.draw(g, sx + square / 2, sy + square / 2 - Math.round(2 * t), scale, accent);
        }

        int ty = sy + head;
        g.text(font, name, x + (w - font.width(name)) / 2, ty, enabled ? TEXT : MUTED, enabled);
        int ly = ty + 13;
        for (String line : lines) {
            textCenter(g, line, x + w / 2, ly, enabled ? MUTED : DIM);
            ly += 10;
        }
        if (note != null) {
            int nw = font.width(note) + 12;
            int ny = y + h - 18;
            round(g, x + (w - nw) / 2, ny, nw, 12, alpha(enabled ? GOLD : DIM, 0x24));
            textCenter(g, note, x + w / 2, ny + 2, enabled ? GOLD : MUTED);
        }
        if (enabled) {
            click(x, y, w, h, action);
        }
    }

    /** [s] broken into lines no wider than [width], at spaces and explicit newlines. */
    protected List<String> wrap(String s, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.width(candidate) > width && !line.isEmpty()) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    // ---------------------------------------------------------------- frame and input

    @Override
    public final void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        building = new ArrayList<>();
        rightBuilding = new ArrayList<>();
        clip = null;
        try {
            extractFrame(g, mx, my, delta);
            // Minecraft's own widgets (text fields) on top of the panel.
            super.extractRenderState(g, mx, my, delta);
        } finally {
            // Published whole, so a click never sees a half-drawn (or just cleared) frame.
            hits = List.copyOf(building);
            rightHits = List.copyOf(rightBuilding);
        }
    }

    /** Draws the screen and registers its click areas. */
    protected abstract void extractFrame(GuiGraphicsExtractor g, int mx, int my, float delta);

    /** A left press before the click areas get it (drags start here); true when taken. */
    protected boolean pressed(double mx, double my) {
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 26.3 reads the mouse through SDL: the left button is 1 there (MOUSE_BUTTON_LEFT), not GLFW's 0.
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (pressed(event.x(), event.y())) {
                return true;
            }
            List<Hit> frame = hits;
            for (int i = frame.size() - 1; i >= 0; i--) {
                Hit hit = frame.get(i);
                if (hit.contains(event.x(), event.y())) {
                    // A click elsewhere takes the focus off a text field, as Minecraft's own screens do,
                    // unless it landed on the field itself.
                    boolean onField = false;
                    for (var child : children()) {
                        if (child instanceof EditBox box && box.isMouseOver(event.x(), event.y())) {
                            onField = true;
                        }
                    }
                    if (onField) {
                        break;
                    }
                    for (var child : children()) {
                        if (child instanceof EditBox box) {
                            box.setFocused(false);
                        }
                    }
                    hit.action().run();
                    return true;
                }
            }
        }
        // And SDL's right button is 3 (MOUSE_BUTTON_RIGHT), not GLFW's 1.
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            List<Hit> frame = rightHits;
            for (int i = frame.size() - 1; i >= 0; i--) {
                Hit hit = frame.get(i);
                if (hit.contains(event.x(), event.y())) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Back to the screen this one was opened from. */
    protected void back() {
        minecraft.gui.setScreen(parent);
    }
}
