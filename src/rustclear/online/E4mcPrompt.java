package net.rustclear.mod.online;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * The start-up question about e4mc, as its own small process (E4mcPreLaunch starts it, with
 * Minecraft's version, the mods folder, Rustclear's version, the mod to install ("e4mc" or "e4all")
 * and, when switching from e4mc to e4all, e4mc's jar as arguments). Drawn by hand in
 * Rustclear's colours: the dungeon-map emblem, gold RUSTCLEAR, a slate panel, flat buttons.
 * Answers with its exit code: [INSTALLED], [NOT_NOW] or [DONT_ASK]. Plain Java and Swing only.
 */
public final class E4mcPrompt extends JPanel {
    public static final int INSTALLED = 0;
    public static final int NOT_NOW = 1;
    public static final int DONT_ASK = 2;

    // Rustclear's palette (ui.Theme), without its Minecraft-side helpers.
    private static final Color SHADE = new Color(0x0A0D10);
    private static final Color PANEL = new Color(0x161B21);
    private static final Color EDGE = new Color(0x2C343E);
    private static final Color RAIL = new Color(0x101418);
    private static final Color HOVER = new Color(0x1E252D);
    private static final Color TEXT = new Color(0xE7EAEE);
    private static final Color MUTED = new Color(0x8C96A1);
    private static final Color DIM = new Color(0x4D5661);
    private static final Color GOLD = new Color(0xF2B33D);
    private static final Color ON_GOLD = new Color(0x1A1206);
    private static final Color GOOD = new Color(0x6BCB77);
    private static final Color BAD = new Color(0xE0675A);
    private static final int[] EMBLEM = {0x5DBB4D, 0x8A5A2E, 0x8A5A2E, 0x8A5A2E, 0x8E4CC2, 0x8A5A2E, 0x8A5A2E, 0x8A5A2E, 0xC94A3F};

    private enum Stage { ASK, INSTALLING, DONE, FAILED }

    private record Button(String label, boolean primary, Runnable action, Rectangle box) {}

    private final String minecraft;
    private final Path mods;
    private final String rustclear;
    private final String project;
    /** e4mc's jar when this is the switch to e4all (e4mc and Krypton don't work together), else null. */
    private final Path replace;
    private Stage stage = Stage.ASK;
    private String detail = "";
    private final List<Button> buttons = new ArrayList<>();
    private Button hovered;
    private long started = System.currentTimeMillis();

    private E4mcPrompt(String minecraft, Path mods, String rustclear, String project, Path replace) {
        this.minecraft = minecraft;
        this.mods = mods;
        this.rustclear = rustclear;
        this.project = project;
        this.replace = replace;
        setPreferredSize(new Dimension(520, 270));
        setBackground(SHADE);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Button over = at(e.getX(), e.getY());
                if (over != hovered) {
                    hovered = over;
                    setCursor(Cursor.getPredefinedCursor(over != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    repaint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                Button over = at(e.getX(), e.getY());
                if (over != null) {
                    over.action.run();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        // The pulse while installing.
        new javax.swing.Timer(120, e -> {
            if (stage == Stage.INSTALLING) {
                repaint();
            }
        }).start();
    }

    public static void main(String[] args) {
        String minecraft = args.length > 0 ? args[0] : "26.3";
        Path mods = Path.of(args.length > 1 ? args[1] : "mods");
        String rustclear = args.length > 2 ? args[2] : "dev";
        String project = args.length > 3 ? args[3] : "e4mc";
        Path replace = args.length > 4 ? Path.of(args[4]) : null;
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("ADS");
            E4mcPrompt prompt = new E4mcPrompt(minecraft, mods, rustclear, project, replace);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    // Closing the window is "Not now" (or, once installed, the same as OK).
                    System.exit(prompt.stage == Stage.DONE ? INSTALLED : NOT_NOW);
                }
            });
            frame.setContentPane(prompt);
            frame.setResizable(false);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
            frame.toFront();
        });
    }

    private Button at(int x, int y) {
        for (Button b : buttons) {
            if (b.box.contains(x, y)) {
                return b;
            }
        }
        return null;
    }

    private void install() {
        stage = Stage.INSTALLING;
        started = System.currentTimeMillis();
        repaint();
        Thread.ofPlatform().daemon().start(() -> {
            try {
                String file = E4mcDownload.install(project, minecraft, mods, rustclear, replace);
                SwingUtilities.invokeLater(() -> {
                    stage = Stage.DONE;
                    detail = file;
                    repaint();
                });
            } catch (RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    stage = Stage.FAILED;
                    detail = e.getMessage() == null ? "Couldn't install " + project + "." : e.getMessage();
                    repaint();
                });
            }
        });
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        int px = 14;
        int py = 14;
        int pw = w - 28;
        int ph = h - 28;
        g.setColor(PANEL);
        g.fillRoundRect(px, py, pw, ph, 6, 6);
        g.setColor(EDGE);
        g.drawRoundRect(px, py, pw - 1, ph - 1, 6, 6);

        // Header: emblem, RUSTCLEAR, and what this is.
        for (int i = 0; i < 9; i++) {
            g.setColor(new Color(EMBLEM[i]));
            g.fillRect(px + 16 + (i % 3) * 6, py + 15 + (i / 3) * 6, 4, 4);
        }
        Font bold = new Font(Font.SANS_SERIF, Font.BOLD, 15);
        Font body = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        g.setFont(bold);
        g.setColor(GOLD);
        g.drawString("ADS", px + 44, py + 30);
        int tw = g.getFontMetrics().stringWidth("ADS");
        g.setFont(body);
        g.setColor(MUTED);
        g.drawString("Party sharing", px + 44 + tw + 10, py + 30);
        g.setColor(EDGE);
        g.fillRect(px + 1, py + 46, pw - 2, 1);

        int x = px + 22;
        int y = py + 76;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        g.setColor(TEXT);
        String title = switch (stage) {
            case ASK -> replace != null ? "Switch to e4all?" : "Install " + project + "?";
            case INSTALLING -> "Installing " + project;
            case DONE -> project + " is installed";
            case FAILED -> "Couldn't install " + project;
        };
        g.drawString(title, x, y);
        g.setFont(body);
        String text = switch (stage) {
            case ASK -> replace != null
                ? "e4mc and Krypton together stop you joining any world, singleplayer included. e4all is a "
                    + "fork of e4mc that works with Krypton. Replace e4mc with e4all from Modrinth now? "
                    + "Minecraft closes afterwards; press Play again to start with it."
                : "ADS shares your parties through " + project + ", so friends can join from anywhere and "
                    + "nobody sees anyone's IP. It isn't installed. Install it from Modrinth now? Minecraft "
                    + "closes afterwards; press Play again to start with it."
                    + (project.equals("e4all") ? " (e4all is the e4mc fork that works with Krypton.)" : "");
            case INSTALLING -> "Downloading from Modrinth and checking it";
            case DONE -> "Installed " + detail + " in your mods folder. Minecraft will close now; press Play "
                + "again in your launcher.";
            case FAILED -> detail + " You can try again, or start without it and use a tunnel or LAN instead.";
        };
        g.setColor(stage == Stage.FAILED ? BAD : stage == Stage.DONE ? GOOD : MUTED);
        int ly = y + 26;
        for (String line : wrap(g.getFontMetrics(), text, pw - 44)) {
            g.drawString(line, x, ly);
            ly += 19;
        }
        if (stage == Stage.INSTALLING) {
            int step = (int) ((System.currentTimeMillis() - started) / 220 % 3);
            int dx = x + g.getFontMetrics().stringWidth(text) + 10;
            for (int i = 0; i < 3; i++) {
                g.setColor(i == step ? GOLD : new Color(0x7A5718));
                g.fillRect(dx + i * 9, ly - 30, 5, 5);
            }
        }

        // Buttons along the bottom, right to left.
        buttons.clear();
        int by = py + ph - 46;
        g.setColor(EDGE);
        g.fillRect(px + 16, by - 12, pw - 32, 1);
        int right = px + pw - 16;
        switch (stage) {
            case ASK -> {
                right = button(g, replace != null ? "Switch to e4all" : "Install " + project, true, right, by, this::install) - 8;
                right = button(g, "Not now", false, right, by, () -> System.exit(NOT_NOW)) - 8;
                button(g, "Don't ask again", false, right, by, () -> System.exit(DONT_ASK));
            }
            case DONE -> button(g, "OK", true, right, by, () -> System.exit(INSTALLED));
            case FAILED -> {
                right = button(g, "Try again", true, right, by, this::install) - 8;
                button(g, "Start without it", false, right, by, () -> System.exit(NOT_NOW));
            }
            default -> {
            }
        }
        g.setColor(DIM);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.drawString(project.equals("e4all") ? "e4all is a fork of skyevg's e4mc; Rustclear fetches it from Modrinth."
            : "e4mc is made by skyevg; Rustclear fetches it from Modrinth.", px + 16, py + ph - 12);
    }

    /** A flat button ending at [right]: gold when primary. Returns its left edge. */
    private int button(Graphics2D g, String label, boolean primary, int right, int y, Runnable action) {
        g.setFont(new Font(Font.SANS_SERIF, primary ? Font.BOLD : Font.PLAIN, 13));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(label) + 28;
        int h = 30;
        int x = right - w;
        Button button = new Button(label, primary, action, new Rectangle(x, y, w, h));
        buttons.add(button);
        boolean hover = hovered != null && hovered.label.equals(label);
        if (primary) {
            g.setColor(hover ? GOLD.brighter() : GOLD);
            g.fillRoundRect(x, y, w, h, 5, 5);
            g.setColor(ON_GOLD);
        } else {
            g.setColor(hover ? HOVER : RAIL);
            g.fillRoundRect(x, y, w, h, 5, 5);
            g.setColor(hover ? DIM : EDGE);
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(x, y, w - 1, h - 1, 5, 5);
            g.setColor(hover ? TEXT : MUTED);
        }
        g.drawString(label, x + 14, y + 20);
        return x;
    }

    private static List<String> wrap(FontMetrics fm, String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(next) > width && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        lines.add(line.toString());
        return lines;
    }
}
