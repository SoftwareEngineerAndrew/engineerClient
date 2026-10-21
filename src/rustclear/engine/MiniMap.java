package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.rustclear.mod.ui.RustclearScreen;
import net.rustclear.mod.ui.Theme;

/**
 * A temporary minimap (the user, 2026-10-19, for watching scripted runs): the dungeon from above in the top left
 * corner, rooms coloured by type with found/total secrets once cleared, doors, and every player as a dot in their
 * class's colour with its letter (the local player outlined, ghosts gray). It reads the run straight from the
 * integrated server, so only the host sees it.
 */
public final class MiniMap {
    private MiniMap() {
    }

    /** Pixels per block: the 6 x 32-block grid in 108 pixels. */
    private static final float S = 108f / (Layout.GRID * Layout.CELL);
    private static final int X0 = 6;
    private static final int Y0 = 6;

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("rustclear", "minimap"), MiniMap::draw);
        // The Spirit Leap menu covers the HUD, and picking whom to leap to is when the map helps most (the user,
        // 2026-10-19): drawn on top of it.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                && screen.getTitle().getString().equals("Spirit Leap")) {
                net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen)
                    .register((s, g, mx, my, d) -> draw(g, true));
            }
        });
    }

    private static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
        draw(g, false);
    }

    private static void draw(GuiGraphicsExtractor g, boolean onScreen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden() || !onScreen && mc.gui.screen() != null) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        try {
            ServerPlayer me = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (me == null || !(EngineServer.runOf(me) instanceof DungeonRun run) || run.inBoss()) {
                return;
            }
            drawRun(g, mc, run, me);
        } catch (RuntimeException e) {
            // The server thread changed a list mid-draw: skip this frame.
        }
    }

    private static int px(double world, int origin) {
        return Math.round((float) (world - origin) * S);
    }

    private static void drawRun(GuiGraphicsExtractor g, Minecraft mc, DungeonRun run, ServerPlayer me) {
        int size = Math.round(Layout.GRID * Layout.CELL * S);
        RustclearScreen.round(g, X0 - 3, Y0 - 3, size + 6, size + 6, Theme.alpha(Theme.PANEL, 0xC8));
        RustclearScreen.outline(g, X0 - 3, Y0 - 3, size + 6, size + 6, Theme.PANEL_EDGE);
        int cell = Math.round(31 * S);
        int gap = Math.round(Layout.CELL * S) - cell;
        int[] at = new int[Layout.GRID * Layout.GRID];
        for (int gz = 0; gz < Layout.GRID; gz++) {
            for (int gx = 0; gx < Layout.GRID; gx++) {
                at[gx + gz * Layout.GRID] = run.roomAt(Layout.ORIGIN_X + gx * Layout.CELL + 15, Layout.ORIGIN_Z + gz * Layout.CELL + 15);
            }
        }
        for (int gz = 0; gz < Layout.GRID; gz++) {
            for (int gx = 0; gx < Layout.GRID; gx++) {
                int i = at[gx + gz * Layout.GRID];
                if (i < 0 || i >= run.rooms.size()) {
                    continue;
                }
                RoomState room = run.rooms.get(i);
                int c = colour(room);
                int x = X0 + px(Layout.ORIGIN_X + gx * Layout.CELL, Layout.ORIGIN_X);
                int y = Y0 + px(Layout.ORIGIN_Z + gz * Layout.CELL, Layout.ORIGIN_Z);
                RustclearScreen.rect(g, x, y, cell, cell, c);
                // One room over several cells: the gaps between its cells filled in.
                boolean right = gx + 1 < Layout.GRID && at[gx + 1 + gz * Layout.GRID] == i;
                boolean down = gz + 1 < Layout.GRID && at[gx + (gz + 1) * Layout.GRID] == i;
                if (right) {
                    RustclearScreen.rect(g, x + cell, y, gap, cell, c);
                }
                if (down) {
                    RustclearScreen.rect(g, x, y + cell, cell, gap, c);
                }
                if (right && down && at[gx + 1 + (gz + 1) * Layout.GRID] == i) {
                    RustclearScreen.rect(g, x + cell, y + cell, gap, gap, c);
                }
            }
        }
        // Once a room is cleared (its starred mobs dead, or the trap or puzzle done), found/total secrets in the
        // middle of its first cell in place of the checkmark dot: white, green once every secret is found (the user,
        // 2026-10-20). A cleared room with no secrets keeps a green dot.
        for (int gz = 0; gz < Layout.GRID; gz++) {
            for (int gx = 0; gx < Layout.GRID; gx++) {
                int i = at[gx + gz * Layout.GRID];
                if (i < 0 || i >= run.rooms.size() || firstCell(at, i) != gx + gz * Layout.GRID) {
                    continue;
                }
                RoomState room = run.rooms.get(i);
                if (!room.cleared()) {
                    continue;
                }
                int total = room.data().secrets();
                int x = X0 + px(Layout.ORIGIN_X + gx * Layout.CELL + 15, Layout.ORIGIN_X);
                int y = Y0 + px(Layout.ORIGIN_Z + gz * Layout.CELL + 15, Layout.ORIGIN_Z);
                if (total <= 0) {
                    RustclearScreen.rect(g, x - 1, y - 1, 3, 3, 0xFF4CAF50);
                    continue;
                }
                String s = Math.min(room.foundSecrets, total) + "/" + total;
                g.pose().pushMatrix();
                g.pose().translate(x + 0.5f, y - 2.5f);
                g.pose().scale(0.75f, 0.75f);
                g.text(mc.font, s, -mc.font.width(s) / 2, 0, room.foundSecrets >= total ? 0xFF4CAF50 : 0xFFFFFFFF, true);
                g.pose().popMatrix();
            }
        }
        for (DoorState d : new ArrayList<>(run.doors)) {
            int c = d.opened ? 0xFF4CAF50 : switch (d.type()) {
                case WITHER -> 0xFF101010;
                case BLOOD -> 0xFFD32F2F;
                default -> 0xFF8D6E63;
            };
            int x = X0 + px(d.door.x(), Layout.ORIGIN_X);
            int y = Y0 + px(d.door.z(), Layout.ORIGIN_Z);
            RustclearScreen.rect(g, x - 1, y - 1, 3, 3, c);
        }
        List<ServerPlayer> players = new ArrayList<>(run.level.players());
        for (ServerPlayer p : players) {
            int x = X0 + px(p.getX(), Layout.ORIGIN_X);
            int y = Y0 + px(p.getZ(), Layout.ORIGIN_Z);
            if (x < X0 - 2 || y < Y0 - 2 || x > X0 + size + 2 || y > Y0 + size + 2) {
                continue;
            }
            DungeonClass cls = PlayerStats.classOf(p);
            int c = Ghosts.isGhost(p) ? 0xFF9E9E9E : classColour(cls);
            if (p == me) {
                RustclearScreen.rect(g, x - 3, y - 3, 6, 6, 0xFFFFFFFF);
            }
            RustclearScreen.rect(g, x - 2, y - 2, 4, 4, c);
            g.text(mc.font, cls.name().substring(0, 1), x + 3, y - 4, c, true);
        }
    }

    private static int firstCell(int[] at, int room) {
        for (int k = 0; k < at.length; k++) {
            if (at[k] == room) {
                return k;
            }
        }
        return -1;
    }

    private static int colour(RoomState room) {
        return switch (room.type()) {
            case PUZZLE -> 0xFF7E57C2;
            case TRAP -> 0xFFEF6C00;
            case YELLOW -> 0xFFFBC02D;
            case BLOOD -> 0xFFC62828;
            case FAIRY -> 0xFFEC407A;
            case ENTRANCE -> 0xFF2E7D32;
            case RARE -> 0xFF26A69A;
            default -> 0xFF6D4C41;
        };
    }

    /** The class's own colour (its chat colour on Hypixel). */
    private static int classColour(DungeonClass c) {
        return 0xFF000000 | c.rgb;
    }
}
