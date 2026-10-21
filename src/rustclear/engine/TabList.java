package net.rustclear.mod.engine;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.rustclear.mod.RustclearMod;

/**
 * The tab list (Smalldungeons server/utils/player_list): Hypixel's header and footer, and 80 fake
 * entries named "!A-a" .. "!D-b" (so they sort into four columns of 20) whose display names carry
 * the dungeon's stats, filled in each tick like main.rs does.
 *
 * The fake entries need ClientboundPlayerInfoUpdatePacket built from entries rather than from real
 * players, which Minecraft doesn't offer publicly, so that packet is put together by reflection.
 * If that ever fails, only the header and footer are shown.
 */
final class TabList {
    static final int LINES = 80;

    /** player_profile.rs GRAY / GRAY_SIG: the gray skin every fake entry wears. */
    private static final String GRAY = "eyJ0aW1lc3RhbXAiOjE0NjU2NTg1NjkzNDQsInByb2ZpbGVJZCI6ImIwZDRiMjhiYzFkNzQ4ODlhZjBlODY2MWNlZTk2YWFiIiwicHJvZmlsZU5hbWUiOiJJbnZlbnRpdmVHYW1lcyIsInNpZ25hdHVyZVJlcXVpcmVkIjp0cnVlLCJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTdmNWZkMjNhYWZiYjlhYjRmZDE0ZmQwODIzYWVhM2E5ZmJkZmU2ZmIyZGUwZjdlZjMzZDBmNGI0YWI3YSJ9fX0=";
    private static final String GRAY_SIG = "Ntyez/Ut7Ht7cmrgPK3dJ/MvIEyQt3jy2c6ACx9sfxB4AKNzyE6fsjffRWwXuObEiPGwYm6iIl2eWuMSvlWljXBg5XJL/ql2Xse1yXImfGfUmedltUhoQuAxCPjogubcapAdGsIr4W5tVrYoTnMgjz7zyc0gHafCClEQBm8A+C5FIfFsAkWUdmzqrEPjbzlqt6qUl0m1sAdskgR0DgCg2pZk5djL+CzPiUgZv1rqXO4Mg5zXr5DSpeEgk5AMInEuxCRemL096cp0fKwf99FEUOiJ2Rq6J9azZVnyjoQy5sZv1RD3+24ib9uwZoJFWakjtTJnWEcUrFsFrr3p5q1Q1prCvbOKS+xD7pMWUbCVz4WuS14e0PtjF5IpDFCehwveREofru7uxAeD4lP13XTdAuWvBvPox1gKvnax8OsnQO64F4nadGm3WmWas6JYzGgOkIRwiKGTxJDIQcAmRXgn8qDx8if5EVnI3/RLIayo06FPXQGmU1p6CCoAy7prGkVeHZsoKuvRGVj3PN70aoJialHzKKpW1OYgSKQXuTOaaWjQN3xbXVrOKaEEAxglWR7drfVPM1szBpcwnYL7hIKejTiHbfZj6mPwFDf0iy1YVcY0iDeCJogf50Z5dWJrwNw6IsQCLuig7BTWAcP7iCCl73UuJRrYndeN7tUIQbtRqfM=";

    /** Skytils' Catlas reads real players off these rows (CLASS_TAB_SLOTS). */
    private static final int[] CLASS_SLOTS = {1, 5, 9, 13, 17};
    private static final int[] PUZZLE_SLOTS = {48, 49, 50, 51, 52, 53};

    private static Wire wire;
    private static boolean wireLoaded;

    private final DungeonRun run;
    private final GameProfile[] profiles = new GameProfile[LINES];
    private final UUID[] ids = new UUID[LINES];
    private final String[] lines = new String[LINES];
    private final BitSet updated = new BitSet(LINES);
    /**
     * Rows each viewer sees for themselves (VIEWER_FIRST..VIEWER_LAST): "Your Milestone", its progress
     * row and the Discoveries block it pushes down. Hypixel's tab is per player; the rest is shared.
     */
    static final int VIEWER_FIRST = 28;
    static final int VIEWER_LAST = 33;
    private final java.util.Map<UUID, String[]> viewerLines = new java.util.HashMap<>();
    private final java.util.Map<UUID, BitSet> viewerUpdated = new java.util.HashMap<>();
    private final Set<UUID> initialized = new HashSet<>();
    /** Whether to re-send every line next tick, as an update (see [tick]). */
    private boolean resendAll;
    private int ticks;

    TabList(DungeonRun run) {
        this.run = run;
        for (int i = 0; i < LINES; i++) {
            // generate_default_lines: uppercase first letter, lowercase second ("!A-b").
            String name = "!" + Character.toUpperCase((char) ('a' + (i / 26) % 26)) + "-" + (char) ('a' + i % 26);
            ids[i] = UUID.nameUUIDFromBytes(("rustclear:tab:" + name).getBytes(StandardCharsets.UTF_8));
            profiles[i] = grayProfile(ids[i], name);
            lines[i] = "";
        }
    }

    private static GameProfile grayProfile(UUID id, String name) {
        Property textures = new Property("textures", GRAY, GRAY_SIG);
        return new GameProfile(id, name, new PropertyMap(ImmutableMultimap.of("textures", textures)));
    }

    // ---------------------------------------------------------------- content

    /** [n] in Roman numerals, as Hypixel writes class levels ("Mage L"); 0 is "0". */
    private static String roman(int n) {
        if (n <= 0) {
            return "0";
        }
        int[] values = {50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (n >= values[i]) {
                out.append(numerals[i]);
                n -= values[i];
            }
        }
        return out.toString();
    }

    private void setLine(int index, String text) {
        if (!lines[index].equals(text)) {
            lines[index] = text;
            updated.set(index);
        }
    }

    /**
     * Hypixel's dungeon tab, line for line: Skyblocker reads several lines by their position (the
     * layout in its DungeonsTabPlaceholder), and the other mods match the text.
     */
    /**
     * Hypixel's " Ultimate:" row: red N/A before the run starts, green Ready, else the time left in
     * yellow, whole seconds rounded down, as "04m 14s" from a minute up and "59s".."00s" below.
     */
    private String ultimate(ServerPlayer player) {
        DungeonRun.State state = run.state();
        if (state != DungeonRun.State.STARTED && state != DungeonRun.State.FINISHED) {
            return "§e§cN/A";
        }
        long ticks = run.abilities().ticksLeft(player, true);
        if (ticks <= 0) {
            return "§e§aReady";
        }
        long seconds = ticks / 20;
        return seconds >= 60 ? String.format(java.util.Locale.ROOT, "§e%02dm %02ds", seconds / 60, seconds % 60)
            : String.format(java.util.Locale.ROOT, "§e%02ds", seconds);
    }

    private void refresh() {
        List<ServerPlayer> players = run.level.players();
        int party = Math.min(players.size(), CLASS_SLOTS.length);
        setLine(0, "         §b§lParty §f(" + party + ")");
        for (int i = 0; i < CLASS_SLOTS.length && i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            String name = player.getName().getString();
            StatSheet s = PlayerStats.of(player);
            DungeonClass c = s.dungeonClass != null ? s.dungeonClass : PlayerStats.classOf(player);
            // "[SkyBlock level] name (Class level)", which the mods read the team and classes from.
            setLine(CLASS_SLOTS[i], "§8[§9420§8] §b" + name + " §f(§d" + c.title + " " + roman(s.classLevel) + "§f)");
            setLine(CLASS_SLOTS[i] + 1, " Ultimate: " + ultimate(player));
            setLine(CLASS_SLOTS[i] + 2, " Revive Stones: §c0");
        }

        Score score = run.score;
        boolean started = run.state() == DungeonRun.State.STARTED;
        String time = clock(score.elapsedTicks / 20);

        double secretsPercent = score.totalSecrets == 0 ? 0.0 : score.secretsFound * 100.0 / score.totalSecrets;
        int opened = 0;
        List<RoomState> puzzles = new ArrayList<>();
        for (RoomState room : run.rooms) {
            if (room.entered) {
                opened++;
            }
            if (room.type() == RoomType.PUZZLE) {
                puzzles.add(room);
            }
        }

        setLine(20, "       §2§lPlayer Stats");
        // Hypixel's downed rows: the ghost, seconds since the death counting up ("00s", "07s"), and
        // " Revive: 00s" (the only value recorded while someone is down; OPEN), back on the revive.
        java.util.UUID downed = run.combat().ghosts().firstDowned();
        ServerPlayer ghost = downed == null ? null : run.level.getServer().getPlayerList().getPlayer(downed);
        if (ghost != null) {
            setLine(21, "§a§lDowned: §e" + ghost.getName().getString());
            setLine(22, " Time: §e" + clock(run.combat().ghosts().downedSeconds(downed)));
            setLine(23, " Revive: §e00s");
        } else {
            setLine(21, "§a§lDowned: §7NONE");
            setLine(22, " Time: §eN/A");
            setLine(23, " Revive: §cN/A");
        }
        setLine(25, "§a§lTeam Deaths: §f" + score.deaths);
        setLine(26, " Team Damage Dealt: §c" + shortNumber(run.mobs().damageDealt) + "❤");
        setLine(27, " Team Healing Done: §c" + shortNumber(run.combat().healingDone) + "❤");
        for (ServerPlayer viewer : players) {
            String[] rows = viewerRows(viewer, started, score);
            String[] had = viewerLines.computeIfAbsent(viewer.getUUID(), k -> new String[rows.length]);
            BitSet changed = viewerUpdated.computeIfAbsent(viewer.getUUID(), k -> new BitSet(LINES));
            for (int i = 0; i < rows.length; i++) {
                if (!rows[i].equals(had[i])) {
                    had[i] = rows[i];
                    changed.set(VIEWER_FIRST + i);
                }
            }
        }

        setLine(40, "       §3§lDungeon Stats");
        setLine(41, "§b§lDungeon: §7Catacombs");
        setLine(42, " Opened Rooms: §5" + opened);
        setLine(43, " Completed Rooms: §d" + score.clearedRooms);
        setLine(44, " Secrets Found: §e" + trimmed(secretsPercent) + "%");
        setLine(45, " Time: §6" + (started ? time : "Soon!"));

        setLine(47, "§b§lPuzzles: §f(" + puzzles.size() + ")");
        for (int i = 0; i < PUZZLE_SLOTS.length && i < puzzles.size(); i++) {
            RoomState room = puzzles.get(i);
            String name = odinName(room.data().name());
            if (!room.entered || name == null) {
                // Hypixel lists a puzzle nobody has found yet as "???".
                setLine(PUZZLE_SLOTS[i], " ???: §7[§6§l✦§7]");
                continue;
            }
            // ✔ done, ✖ failed, ✦ found but not done yet.
            String glyph = room.puzzleFailed ? "§c§l✖" : room.puzzleCompleted ? "§a§l✔" : "§6§l✦";
            // A failed puzzle names who failed it: " Higher Or Lower: [✖] (player)".
            String who = room.puzzleFailed && room.failedBy != null ? " §f(§7" + room.failedBy + "§f)" : "";
            setLine(PUZZLE_SLOTS[i], " " + name + ": §7[" + glyph + "§7]" + who);
        }

        setLine(60, "       §6§lAccount Info");
        setLine(61, "§e§lProfile: §a" + HypixelSignals.PROFILE_NAME);
    }

    /**
     * [viewer]'s own rows 28-33: " Your Milestone", the progress row under it, then Discoveries, which
     * Hypixel puts one row lower when the progress row is shown (31-33, else 30-32).
     */
    private String[] viewerRows(ServerPlayer viewer, boolean started, Score score) {
        String[] rows = new String[VIEWER_LAST - VIEWER_FIRST + 1];
        java.util.Arrays.fill(rows, "");
        DungeonClass yours = PlayerStats.classOf(viewer);
        String progress = null;
        if (!started || yours == null) {
            rows[0] = " Your Milestone: §e?";
        } else {
            rows[0] = " Your Milestone: §e" + Milestones.symbol(yours) + Milestones.GLYPHS[run.milestones().reached(viewer)];
            progress = run.milestones().progressLine(viewer, yours);
        }
        if (progress != null) {
            rows[1] = " §7" + progress;
        }
        int d = (progress == null ? 30 : 31) - VIEWER_FIRST;
        rows[d] = "§a§lDiscoveries: §f" + (score.secretsFound + score.crypts);
        rows[d + 1] = " Secrets Found: §b" + score.secretsFound;
        rows[d + 2] = " Crypts: §6" + score.crypts;
        return rows;
    }

    /** Row [index] as [viewer] sees it. */
    private String lineFor(ServerPlayer viewer, int index) {
        if (index >= VIEWER_FIRST && index <= VIEWER_LAST) {
            String[] rows = viewerLines.get(viewer.getUUID());
            return rows == null || rows[index - VIEWER_FIRST] == null ? "" : rows[index - VIEWER_FIRST];
        }
        return lines[index];
    }

    /** 65.4 rather than 65.40, and 50 rather than 50.0, as Hypixel writes the tab's percentages (one decimal). */
    static String trimmed(double value) {
        String s = String.format(Locale.ROOT, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** The tab's clock: "01m 01s", "45s" (two digits each; hours as "01h 02m 03s", OPEN: never recorded). */
    static String clock(long seconds) {
        long h = seconds / 3600;
        long m = seconds % 3600 / 60;
        long sec = seconds % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%02dh %02dm %02ds", h, m, sec);
        }
        return m > 0 ? String.format(Locale.ROOT, "%02dm %02ds", m, sec) : String.format(Locale.ROOT, "%02ds", sec);
    }

    /** Hypixel's short numbers on the tab: 469.2M, 1.5B, 2.4k, 999 (one decimal, ".0" dropped). */
    static String shortNumber(double value) {
        double v = Math.max(0, value);
        String[] suffix = {"", "k", "M", "B", "T"};
        int i = 0;
        while (v >= 1000 && i < suffix.length - 1) {
            v /= 1000;
            i++;
        }
        if (i == 0) {
            return Long.toString((long) Math.floor(v));
        }
        // Rounded down to a tenth, so 999.96k never reads 1000k.
        return trimmed(Math.floor(v * 10) / 10) + suffix[i];
    }

    /** puzzle_odin_display_name. */
    private static String odinName(String room) {
        return switch (room) {
            case "Blaze" -> "Higher Or Lower";
            case "Boulder", "Creeper Beams", "Ice Fill", "Ice Path", "Quiz", "Teleport Maze", "Three Weirdos",
                "Tic Tac Toe", "Water Board" -> room;
            default -> null;
        };
    }

    // ---------------------------------------------------------------- sending

    /** Tab rows change on a 20-tick grid (menus-ui.md: row gaps 20 x165, 21 x38, 19 x36 of 258). */
    private static final int REFRESH_TICKS = 20;

    void tick() {
        ticks++;
        if (ticks % REFRESH_TICKS == 1 || resendAll) {
            refresh();
            Component footer = footer();
            if (!footer.getString().equals(sentFooter)) {
                sentFooter = footer.getString();
                for (ServerPlayer player : run.level.players()) {
                    if (initialized.contains(player.getUUID())) {
                        player.connection.send(new ClientboundTabListPacket(header(), footer));
                    }
                }
            }
            if (SimLog.enabled()) {
                // The log keeps the first player's view.
                java.util.Map<Integer, String> changed = new java.util.TreeMap<>();
                for (int i = updated.nextSetBit(0); i >= 0; i = updated.nextSetBit(i + 1)) {
                    changed.put(i, lines[i]);
                }
                List<ServerPlayer> all = run.level.players();
                BitSet own = all.isEmpty() ? null : viewerUpdated.get(all.get(0).getUUID());
                if (own != null) {
                    for (int i = own.nextSetBit(0); i >= 0; i = own.nextSetBit(i + 1)) {
                        changed.put(i, lineFor(all.get(0), i));
                    }
                }
                if (!changed.isEmpty()) {
                    SimLog.tab(run.level, changed);
                }
            }
        }
        List<ServerPlayer> players = run.level.players();
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer player : players) {
            present.add(player.getUUID());
        }
        initialized.retainAll(present);
        viewerLines.keySet().retainAll(present);
        viewerUpdated.keySet().retainAll(present);

        // Hypixel re-sends its tab lines now and then as display-name updates. Devonian only reads
        // lines from such updates (not from the join snapshot), so do the same: the tick after
        // someone joins, then every 3 seconds.
        boolean resendRows = resendAll || ticks % 60 == 0;
        if (resendRows) {
            updated.set(0, LINES);
            resendAll = false;
        }
        updated.clear(VIEWER_FIRST, VIEWER_LAST + 1);
        Wire w = wire();
        Packet<?> delta = null;
        List<Object> shared = new ArrayList<>();
        if (w != null) {
            for (int i = updated.nextSetBit(0); i >= 0; i = updated.nextSetBit(i + 1)) {
                shared.add(w.entry(ids[i], profiles[i], lines[i]));
            }
            if (!shared.isEmpty()) {
                delta = w.packet(w.actions("UPDATE_DISPLAY_NAME"), shared);
            }
        }
        updated.clear();

        for (ServerPlayer player : players) {
            if (initialized.add(player.getUUID())) {
                resendAll = true;
                // The join snapshot (server.rs: new_packet() plus PlayerListHeaderFooter), led by
                // the brand and Mod API hello/location server.rs sends, so client mods see a dungeon.
                HypixelSignals.join(player);
                player.connection.send(new ClientboundTabListPacket(header(), footer()));
                if (w != null) {
                    List<Object> entries = new ArrayList<>();
                    for (int i = 0; i < LINES; i++) {
                        entries.add(w.entry(ids[i], profiles[i], lineFor(player, i)));
                    }
                    Packet<?> all = w.packet(w.actions("ADD_PLAYER", "UPDATE_GAME_MODE", "UPDATE_LISTED",
                        "UPDATE_LATENCY", "UPDATE_DISPLAY_NAME", "UPDATE_HAT"), entries);
                    if (all != null) {
                        player.connection.send(all);
                    }
                }
                viewerUpdated.remove(player.getUUID());
            } else if (w != null) {
                BitSet own = viewerUpdated.remove(player.getUUID());
                if (resendRows) {
                    own = new BitSet(LINES);
                    own.set(VIEWER_FIRST, VIEWER_LAST + 1);
                }
                if (own == null || own.isEmpty()) {
                    if (delta != null) {
                        player.connection.send(delta);
                    }
                    continue;
                }
                List<Object> entries = new ArrayList<>(shared);
                for (int i = own.nextSetBit(0); i >= 0; i = own.nextSetBit(i + 1)) {
                    entries.add(w.entry(ids[i], profiles[i], lineFor(player, i)));
                }
                Packet<?> mine = w.packet(w.actions("UPDATE_DISPLAY_NAME"), entries);
                if (mine != null) {
                    player.connection.send(mine);
                }
            }
        }
    }

    /** Re-sends every line next tick. */
    void resend() {
        resendAll = true;
    }

    /** Takes the fake entries back out and blanks the header and footer. */
    void dispose() {
        for (ServerPlayer player : run.level.players()) {
            if (!initialized.contains(player.getUUID())) {
                continue;
            }
            player.connection.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
            if (wire() != null) {
                player.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(ids)));
            }
        }
        initialized.clear();
    }

    /** header.rs (the hover on the address is left out: nothing can hover the tab list). */
    private static Component header() {
        return Component.literal("§bYou are playing on §e§lMC.HYPIXEL.NET\n§s");
    }

    /** Hypixel's footer: Kinds in the order its Dungeon Buffs block lists them (Time's place: OPEN). */
    private static final String[][] BUFFS = {{"power", "Power"}, {"wisdom", "Wisdom"}, {"life", "Life"}, {"stone", "Stone"},
        {"time", "Time"}};

    /**
     * footer.rs, with the Dungeon Buffs block Hypixel adds once the run starts (recorded: Mort's
     * line + 6, then on the tab's 20-tick grid): "No Buffs active..." until the team finds one,
     * then a line a kind with the levels found so far added up (Life V, VII, VIII, XIII...).
     */
    private Component footer() {
        StringBuilder buffs = new StringBuilder();
        if (run.state() == DungeonRun.State.STARTED || run.state() == DungeonRun.State.FINISHED) {
            buffs.append("\n§6§lDungeon Buffs");
            java.util.Map<String, Integer> found = run.blessings();
            boolean any = false;
            for (String[] kind : BUFFS) {
                int level = found.getOrDefault(kind[0], 0);
                if (level > 0) {
                    buffs.append("\n§fBlessing of ").append(kind[1]).append(' ').append(Blessings.roman(level));
                    any = true;
                }
            }
            if (!any) {
                buffs.append("\n§7No Buffs active. Find them by exploring the Dungeon!");
            }
            buffs.append("\n§s");
        }
        return Component.literal("\n§a§lActive Effects"
            + "\n§7You have §e2 §7active effects. Use \"§6/effects§7\" to see them!"
            + "\n§eHaste III"
            + "\n§s"
            + "\n§d§lCookie Buff"
            + "\n§7Not Active! Obtain booster cookies from the community"
            + "\n§7shop in the hub."
            + "\n§s"
            + buffs
            + "\n§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET");
    }

    /** The footer last sent, to send it again only when it changes. */
    private String sentFooter;

    // ---------------------------------------------------------------- the update packet, by reflection

    private static synchronized Wire wire() {
        if (!wireLoaded) {
            wireLoaded = true;
            try {
                wire = new Wire();
            } catch (Throwable t) {
                RustclearMod.LOGGER.warn("Tab list entries unavailable, showing header and footer only", t);
                wire = null;
            }
        }
        return wire;
    }

    /** ClientboundPlayerInfoUpdatePacket(EnumSet<Action>, List<Entry>) and its Entry record. */
    private static final class Wire {
        private final Class<?> actionClass;
        private final Constructor<?> packet;
        /** The packet's entry list, set after construction when [packet] takes players rather than entries. */
        private final Field entriesField;
        private final Constructor<?> entry;
        private final RecordComponent[] components;

        Wire() throws ReflectiveOperationException {
            String name = "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket";
            Class<?> packetClass = Class.forName(name);
            actionClass = Class.forName(name + "$Action");
            Class<?> entryClass = Class.forName(name + "$Entry");
            Constructor<?> found = null;
            Constructor<?> fromPlayers = null;
            for (Constructor<?> c : packetClass.getDeclaredConstructors()) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length == 2 && params[0] == EnumSet.class && params[1] == List.class) {
                    found = c;
                } else if (params.length == 2 && params[0] == EnumSet.class && params[1] == Collection.class) {
                    fromPlayers = c;
                }
            }
            Field list = null;
            if (found == null) {
                // 26.1+ only has the public (EnumSet, Collection<ServerPlayer>) constructor: build the packet
                // with no players, then put the entries into its list field.
                if (fromPlayers == null) {
                    throw new NoSuchMethodException(name + "(EnumSet, List or Collection)");
                }
                for (Field f : packetClass.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && f.getType() == List.class) {
                        if (list != null) {
                            throw new NoSuchFieldException(name + " has more than one List field");
                        }
                        list = f;
                    }
                }
                if (list == null) {
                    throw new NoSuchFieldException(name + " entries");
                }
                list.setAccessible(true);
                found = fromPlayers;
            }
            found.setAccessible(true);
            packet = found;
            entriesField = list;
            components = entryClass.getRecordComponents();
            if (components == null) {
                throw new NoSuchMethodException(name + "$Entry is not a record");
            }
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
            }
            entry = entryClass.getDeclaredConstructor(types);
            entry.setAccessible(true);
            // Fail now rather than every tick if the pieces don't fit.
            UUID probe = UUID.randomUUID();
            Object probeEntry = entry(probe, grayProfile(probe, "probe"), "");
            if (packet(actions("UPDATE_DISPLAY_NAME"), List.of(probeEntry)) == null) {
                throw new IllegalStateException("Couldn't build a tab list packet");
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        EnumSet<?> actions(String... names) {
            EnumSet set = EnumSet.noneOf((Class) actionClass);
            for (String n : names) {
                try {
                    set.add(Enum.valueOf((Class) actionClass, n));
                } catch (IllegalArgumentException ignored) {
                    // Not in this version.
                }
            }
            return set;
        }

        /** PlayerData::new: ping 1, creative, listed, the given display name. */
        Object entry(UUID id, GameProfile profile, String text) {
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                Class<?> type = components[i].getType();
                String n = components[i].getName();
                if (type == UUID.class) {
                    args[i] = id;
                } else if (type == GameProfile.class) {
                    args[i] = profile;
                } else if (type == boolean.class) {
                    args[i] = n.equals("listed") || n.equals("showHat");
                } else if (type == int.class) {
                    args[i] = n.equals("latency") ? 1 : 0;
                } else if (type == GameType.class) {
                    args[i] = GameType.CREATIVE;
                } else if (type == Component.class) {
                    args[i] = EngineItems.styled(text);
                } else if (type.isPrimitive()) {
                    args[i] = type == long.class ? (Object) 0L : type == float.class ? (Object) 0f
                        : type == double.class ? (Object) 0d : type == byte.class ? (Object) (byte) 0
                        : type == short.class ? (Object) (short) 0 : type == char.class ? (Object) '\0' : (Object) 0;
                } else {
                    args[i] = null;
                }
            }
            try {
                return entry.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        Packet<?> packet(EnumSet<?> actions, List<Object> entries) {
            try {
                if (entriesField == null) {
                    return (Packet<?>) packet.newInstance(actions, entries);
                }
                Packet<?> built = (Packet<?>) packet.newInstance(actions, List.of());
                entriesField.set(built, List.copyOf(entries));
                return built;
            } catch (ReflectiveOperationException | ClassCastException e) {
                RustclearMod.LOGGER.warn("Couldn't build tab list packet", e);
                return null;
            }
        }
    }
}
