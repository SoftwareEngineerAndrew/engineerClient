package net.rustclear.mod;

import static net.rustclear.mod.ui.Theme.*;

import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.rustclear.mod.ui.RustclearScreen;

/**
 * During a duel, every team's progress at the top of the screen: score (against the target the
 * duel races to), rooms cleared, secrets and time, the player's own team marked in gold. Drawn in the Rustclear panel's style from the host's
 * rustclear:party, which carries every team's progress twice a second.
 */
public final class DuelHud {
    private DuelHud() {
    }

    static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("rustclear", "duel"), DuelHud::draw);
    }

    /** The duel teams to show, or an empty list outside a duel. */
    static List<Protocol.TeamProgress> teams() {
        Protocol.PartyState party = RustclearMod.party;
        if (party == null || party.teams.isEmpty() || "lobby".equals(party.phase)) {
            return List.of();
        }
        return party.teams;
    }

    /** Which team the local player races on, or null. */
    static String ownTeam() {
        Protocol.PartyState party = RustclearMod.party;
        if (party == null) {
            return null;
        }
        String me = Minecraft.getInstance().getUser().getName();
        return party.members.stream().filter(m -> m.name.equalsIgnoreCase(me)).map(m -> m.team).findFirst().orElse(null);
    }

    static int teamColor(String team) {
        return "B".equals(team) ? TEAM_B : TEAM_A;
    }

    private static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        List<Protocol.TeamProgress> teams = teams();
        if (teams.isEmpty() || mc.gui.hud.isHidden() || mc.gui.screen() != null) {
            return;
        }
        Font font = mc.font;
        String own = ownTeam();
        int w = 230;
        int rowH = 12;
        int h = 14 + teams.size() * rowH + 3;
        int x = (mc.getWindow().getGuiScaledWidth() - w) / 2;
        int y = 22;
        RustclearScreen.round(g, x, y, w, h, alpha(PANEL, 0xD8));
        RustclearScreen.outline(g, x, y, w, h, PANEL_EDGE);

        String winner = RustclearMod.party == null ? null : RustclearMod.party.winner;
        int target = RustclearMod.party == null ? 0 : RustclearMod.party.target;
        String head = winner != null ? "TEAM " + winner + " WINS" : "FIRST TO " + target;
        g.text(font, head, x + 6, y + 3, winner != null ? teamColor(winner) : GOLD, true);
        String legend = "Score  Rooms  Secrets  Time";
        g.text(font, legend, x + w - 6 - font.width(legend), y + 3, DIM, false);

        int ry = y + 14;
        for (Protocol.TeamProgress t : teams) {
            boolean mine = t.team.equals(own);
            if (mine) {
                RustclearScreen.rect(g, x + 2, ry, w - 4, rowH - 1, alpha(GOLD, 0x1C));
                RustclearScreen.rect(g, x + 2, ry + 2, 1, rowH - 5, GOLD);
            }
            RustclearScreen.rect(g, x + 6, ry + 3, 5, 5, teamColor(t.team));
            String name = String.join(" & ", t.players);
            int nameW = w - 6 - 16 - 144;
            g.text(font, fit(font, name, nameW), x + 15, ry + 2, mine ? TEXT : MUTED, false);
            int right = x + w - 6;
            String time = SettingsScreen.formatTime(t.ticks);
            g.text(font, time, right - font.width(time), ry + 2, t.finished ? GOOD : TEXT, false);
            String secrets = t.secretsFound + "/" + t.totalSecrets;
            g.text(font, secrets, right - 50 - font.width(secrets), ry + 2, GOLD, false);
            String rooms = t.roomsCleared + "/" + t.totalRooms;
            g.text(font, rooms, right - 86 - font.width(rooms), ry + 2, lerp(NORMAL, TEXT, 0.45f), false);
            String score = String.valueOf(t.score);
            g.text(font, score, right - 122 - font.width(score), ry + 2, t.finished ? GOOD : t.score >= target - 20 ? GOLD : TEXT, false);
            ry += rowH;
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
