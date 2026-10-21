package net.rustclear.mod.engine;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;

/**
 * The end of a won run (boss-p4-necron.md F8, score-secrets-puzzles.md "End screen"): at D the
 * end block (score, Defeated time, XP) and the teleport to the reward room at (28, 166, 118)
 * facing south, where the six reward chests stand; at D + 1 the stats block; at D + 40 the
 * re-queue line; at D + 1800 the 90-second warning. Lines are centred with spaces by their
 * width in Minecraft's font, as Hypixel's are.
 */
final class RunEnd {
    /** 64 green bold ▬. */
    private static final String RULE = "§a§l" + "▬".repeat(64);
    /** XP per score point, as measured (68,149.2 / 305 = 67,255.4 / 301 = 223.44; class 169.87; team bonus a quarter). OPEN: boosts. */
    private static final double CATA_XP_PER_POINT = 223.44;
    private static final double CLASS_XP_PER_POINT = 169.87;

    private RunEnd() {
    }

    static void defeated(DungeonRun run) {
        run.score.refresh();
        int score = run.score.total();
        String rank = Score.rank(score);
        String time = time(run.score.startNanos);
        String floor = run.floor.master() ? "Master Mode The Catacombs " : "The Catacombs ";
        List<ServerPlayer> players = run.level.players();
        SimLog.event("defeated", "score", score);
        for (ServerPlayer p : players) {
            DungeonClass own = PlayerStats.classOf(p);
            send(p, RULE);
            send(p, centred("§c" + floor + "§8- §eFloor VII"));
            send(p, "");
            send(p, centred(scoreLine(score, rank)));
            send(p, centred(defeatedLine(time)));
            p.sendSystemMessage(extraStats());
            send(p, centred("§8+§3" + number(CATA_XP_PER_POINT * score) + " Catacombs Experience"));
            double classXp = CLASS_XP_PER_POINT * score;
            if (own != null) {
                send(p, centred("§8+§3" + number(classXp) + " " + own.title + " Experience"));
            }
            for (ServerPlayer mate : players) {
                DungeonClass c = PlayerStats.classOf(mate);
                if (mate != p && c != null && c != own) {
                    send(p, centred("§8+§3" + number(classXp / 4) + " " + c.title + " Experience §b(Team Bonus)"));
                }
            }
            send(p, RULE);
            p.teleportTo(run.level, 28.0, 166.0, 118.0, Set.<Relative>of(), 0f, 0f, false);
            p.resetFallDistance();
        }
        RewardChests.spawn(run);
        int n = Math.max(1, players.size());
        run.schedule(1, () -> {
            for (ServerPlayer p : run.level.players()) {
                DungeonClass own = PlayerStats.classOf(p);
                send(p, RULE);
                send(p, centred("§c" + floor + "§8- §eFloor VII Stats"));
                send(p, "");
                send(p, centred(scoreLine(score, rank)));
                send(p, centred(defeatedLine(time)));
                send(p, "");
                // OPEN: Rustclear doesn't attribute damage, kills or secrets to players; the run's totals are shared out.
                send(p, centred("§fTotal Damage as " + (own == null ? "Mage" : own.title) + ": §a"
                    + String.format(Locale.ROOT, "%,d", run.mobs().damageDealt / n)));
                send(p, centred("§fAlly Healing: §a0"));
                send(p, centred("§fEnemies Killed: §a" + run.mobs().killed / n));
                send(p, centred("§fDeaths: §c" + run.combat().deathsOf.getOrDefault(p.getUUID(), 0)));
                send(p, centred("§fSecrets Found: §b" + run.score.secretsFound / n));
                send(p, "");
                send(p, RULE);
            }
        });
        run.schedule(40, () -> {
            for (ServerPlayer p : run.level.players()) {
                // Hypixel's re-queue line; here it starts a new dungeon (/rs).
                p.sendSystemMessage(EngineItems.styled("§eClick HERE to re-queue into The Catacombs!")
                    .withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/rs"))));
            }
        });
        run.schedule(1800, () -> run.broadcast("§cWarning! The instance will close in 90s."));
    }

    private static String scoreLine(int score, String rank) {
        return "§fTeam Score: §a" + score + " §f(" + rankColour(rank) + (rank.startsWith("S") ? "§l" : "") + rank + "§f)";
    }

    /** As recorded: S+ / S aqua bold, A green, C gold, D red (not bold; 261 A, 146 C, 50 D). OPEN: B's colour. */
    private static String rankColour(String rank) {
        return switch (rank) {
            case "S+", "S" -> "§b";
            case "A" -> "§a";
            case "B" -> "§e";
            case "C" -> "§6";
            default -> "§c";
        };
    }

    private static String defeatedLine(String time) {
        return "§c☠ §eDefeated §cMaxor, Storm, Goldor, and Necron §ein §a" + time;
    }

    /** "> EXTRA STATS <": a click runs /showextrastats (Odin's ExtraStats sends it), hover "Click to view extra stats!". */
    private static Component extraStats() {
        MutableComponent c = EngineItems.styled(" ".repeat(29) + "§6> §e§lEXTRA STATS §6<");
        return c.withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/showextrastats"))
            .withHoverEvent(new HoverEvent.ShowText(EngineItems.styled("§eClick to view extra stats!"))));
    }

    /** "03m 19s" from Mort's line (wall clock). */
    static String time(long startNanos) {
        long s = startNanos == 0 ? 0 : (System.nanoTime() - startNanos) / 1_000_000_000L;
        return String.format(Locale.ROOT, "%02dm %02ds", s / 60, s % 60);
    }

    /** "68,149.2": thousands commas, one decimal unless whole. */
    static String number(double v) {
        return net.rustclear.mod.engine.boss.Attacks.number(v);
    }

    private static void send(ServerPlayer p, String legacy) {
        p.sendSystemMessage(EngineItems.text(legacy));
    }

    /**
     * [legacy] padded with spaces to centre it in chat: pad = floor((164 - width / 2) / 4) with
     * Minecraft's glyph widths (bold +1 a glyph). Checked against all 16 recorded end-block lines.
     */
    static String centred(String legacy) {
        int width = 0;
        boolean bold = false;
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == '§' && i + 1 < legacy.length()) {
                char code = Character.toLowerCase(legacy.charAt(++i));
                if (code == 'l') {
                    bold = true;
                } else if ("0123456789abcdefr".indexOf(code) >= 0) {
                    bold = false;
                }
                continue;
            }
            width += glyph(c) + (bold && c != ' ' ? 1 : 0);
        }
        int pad = Math.max(0, (int) Math.floor((164 - width / 2.0) / 4));
        return "§f" + " ".repeat(pad) + legacy;
    }

    private static int glyph(char c) {
        return switch (c) {
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
            case 'l', '`' -> 3;
            case 't', 'I', '[', ']', ' ' -> 4;
            case 'f', 'k', '<', '>', '(', ')', '*', '{', '}', '"' -> 5;
            default -> 6;
        };
    }
}
