package net.rustclear.mod;

import static net.rustclear.mod.ui.Theme.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.rustclear.mod.engine.Engine;
import net.rustclear.mod.engine.DungeonClass;
import net.rustclear.mod.online.HypixelProfile;
import net.rustclear.mod.ui.RustclearScreen;

/**
 * The dungeon settings screen, drawn by hand in the dungeon map's own colours: brown normal rooms,
 * purple puzzles, orange traps, yellow minibosses, and gold for secrets. A rail on the left picks
 * the tab (rooms, puzzles, secrets); each tab is fully random until the player picks something.
 * The secrets tab draws how often each total comes up in random dungeons (sent by the server)
 * behind a two-handled range slider. The speed tab sets the player's speed stat, Hypixel SkyBlock
 * style, on a one-handled slider. The leaderboard tab lists the server's ranked runs.
 *
 * In another player's Rustclear world only the party leader picks the dungeon: everyone else sees
 * the leader's rooms, puzzles and secrets read-only, and only their own speed and hotbar change.
 */
public final class SettingsScreen extends RustclearScreen {
    private enum Tab { PARTY, MODE, ROOMS, PUZZLES, SECRETS, SPEED, STATS, LEADERBOARD }

    private static final String[] FILTERS = {"All", "1x1", "1x2", "1x3", "1x4", "2x2", "L", "Trap", "Mini"};
    private static final int ROW_H = 14;

    /**
     * The party lobby: the whole window, the world hidden behind it, open while the party waits to
     * start (RustclearMod opens and closes it). Esc doesn't close it; Leave party does.
     */
    private final boolean lobby;
    /** Whether this player may change the dungeon (not a guest in someone else's party). */
    private boolean editable = RustclearMod.canEditDungeon();
    /** Stats tab fields, and the party tab's invite box. */
    /** The party's fixed Catacombs and class level (the leader's, in "same for everyone"). */
    private EditBox partyCata;
    private EditBox partyClass;
    private EditBox inviteName;
    /** The last thing a party button did, shown in the footer. */
    private String partyNote;
    /** The header badge's text this frame, so the floor switch sits just left of it. */
    private String badgeText = "";
    private DungeonSettings settings = editable ? RustclearMod.settings.copy() : leaderView(RustclearMod.lastStatus);
    private Tab tab = Tab.ROOMS;
    private String filter = "All";
    private int scroll = 0;
    private int maxScroll = 0;
    private EditBox search;
    private Protocol.StatusMessage status = RustclearMod.lastStatus;
    /** The leaderboard run under the mouse, shown in the footer. */
    private Protocol.Entry hoveredRun;
    /** What the last hotbar button did, shown under them. */
    private String hotbarNote;

    /** Which slider handle is being dragged: -1 none, 0 min, 1 max, 2 the speed handle. */
    private int dragging = -1;
    private int sliderX;
    private int sliderW = 1;
    private int sliderLo;
    private int sliderHi = 1;
    private int chartY;
    private int speedX;
    private int speedW = 1;
    private int speedY;

    public SettingsScreen(Screen parent) {
        this(parent, false);
    }

    /** Opens on the leaderboard tab when {@code leaderboard} is true. */
    public SettingsScreen(Screen parent, boolean leaderboard) {
        this(parent, leaderboard, false);
    }

    private SettingsScreen(Screen parent, boolean leaderboard, boolean lobby) {
        super(Component.literal(lobby ? "ADS party lobby" : "ADS dungeon settings"), parent);
        this.lobby = lobby;
        if (lobby) {
            tab = Tab.PARTY;
        } else if (leaderboard) {
            tab = Tab.LEADERBOARD;
            requestBoard();
        }
    }

    /** The party lobby screen. */
    public static SettingsScreen lobby() {
        return new SettingsScreen(null, false, true);
    }

    public boolean isLobby() {
        return lobby;
    }

    /** The leader's dungeon as the host last sent it, with this player's own speed and toggles. */
    private static DungeonSettings leaderView(Protocol.StatusMessage status) {
        DungeonSettings view = status != null && status.settings != null ? status.settings.copy() : new DungeonSettings();
        keepPersonal(view, RustclearMod.settings);
        return view;
    }

    /** Puts [from]'s own fields (speed, toggles, stats) on [view], which holds someone else's dungeon. */
    private static void keepPersonal(DungeonSettings view, DungeonSettings from) {
        view.speed = from.speed;
        view.practiceMobs = from.practiceMobs;
        view.hypixel = from.hypixel;
        view.simLog = from.simLog;
    }

    /** Asks for the board being shown, or the player's own party size the first time. */
    private static void requestBoard() {
        RustclearMod.requestBoard(RustclearMod.board == null ? null : RustclearMod.board.category);
    }

    @Override
    protected void init() {
        if (lobby) {
            layoutPanel(Integer.MAX_VALUE, Integer.MAX_VALUE);
        } else {
            layoutPanel(372, 236);
        }
        partyCata = partyClass = inviteName = null;
        if (tab == Tab.STATS && editable && !settings.levelsFromHypixel()) {
            partyCata = levelInput(contentX() + 70, levelsY() + 30, settings.partyCata, DungeonSettings.DEFAULT_CATA,
                v -> settings.partyCata = v);
            partyClass = levelInput(contentX() + 70 + 120, levelsY() + 30, settings.partyClassLevel, DungeonSettings.DEFAULT_CLASS_LEVEL,
                v -> settings.partyClassLevel = v);
        }
        if (tab == Tab.PARTY && RustclearMod.party != null && RustclearMod.party.you) {
            inviteName = input(contentX() + 6, partyInviteY() + 3, 110, "Player name", 16);
            inviteName.setResponder(text -> {
                String clean = text.replaceAll("[^A-Za-z0-9_]", "");
                if (!clean.equals(text)) {
                    inviteName.setValue(clean);
                }
            });
            addRenderableWidget(inviteName);
        }
        if (tab == Tab.ROOMS) {
            String previous = search == null ? "" : search.getValue();
            search = input(contentX() + 14, contentY() + 21, 104, "Search rooms", 32);
            search.setValue(previous);
            search.setResponder(text -> scroll = 0);
            addRenderableWidget(search);
        } else {
            search = null;
        }
    }

    private int contentX() {
        return px + 108;
    }

    private int contentY() {
        return py + 30;
    }

    private int contentW() {
        return pw - 108 - 12;
    }

    /** The dungeon map's colour for a room of this type. */
    static int typeColor(String type) {
        return switch (type) {
            case "puzzle" -> PUZZLE;
            case "trap" -> TRAP;
            case "yellow" -> MINIBOSS;
            default -> NORMAL;
        };
    }

    /** Draws a room's shape as map tiles: 3 px squares with 1 px gaps. */
    static void shapeGlyph(GuiGraphicsExtractor g, String shape, int x, int y, int color) {
        int[][] cells = switch (shape) {
            case "OneByTwo" -> new int[][] {{0, 1}, {1, 1}};
            case "OneByThree" -> new int[][] {{0, 1}, {1, 1}, {2, 1}};
            case "OneByFour" -> new int[][] {{0, 1}, {1, 1}, {2, 1}, {3, 1}};
            case "TwoByTwo" -> new int[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
            case "L" -> new int[][] {{0, 0}, {0, 1}, {1, 1}};
            default -> new int[][] {{0, 1}};
        };
        for (int[] cell : cells) {
            g.fill(x + cell[0] * 4, y + cell[1] * 4, x + cell[0] * 4 + 3, y + cell[1] * 4 + 3, color);
        }
    }

    static String shapeLabel(Protocol.Room room) {
        if ("trap".equals(room.type)) {
            return "Trap";
        }
        if ("yellow".equals(room.type)) {
            return "Mini";
        }
        return switch (room.shape) {
            case "OneByTwo" -> "1x2";
            case "OneByThree" -> "1x3";
            case "OneByFour" -> "1x4";
            case "TwoByTwo" -> "2x2";
            case "L" -> "L";
            default -> "1x1";
        };
    }

    // --- Frame ---

    @Override
    protected void extractFrame(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (lobby) {
            // The world stays hidden until the run starts.
            g.fill(0, 0, width, height, 0xFF0A0D10);
        }
        panel(g);
        if (lobby) {
            Protocol.PartyState party = RustclearMod.party;
            int ready = party == null ? 0 : (int) party.members.stream().filter(m -> m.ready).count();
            int count = party == null ? 0 : party.members.size();
            badgeText = ready + "/" + count + " READY";
            header(g, "Party lobby", badgeText, count > 0 && ready == count ? GOOD : GOLD);
        } else {
            boolean ranked = settings.isFullyRandom();
            badgeText = ranked ? "RANKED" : "UNRANKED";
            header(g, tab == Tab.LEADERBOARD ? "Leaderboard" : "Dungeon settings", badgeText, ranked ? GOOD : BAD);
        }
        if (Engine.isEngineClient() && tab != Tab.LEADERBOARD) {
            floorSwitch(g, mx, my);
        }
        drawRail(g, mx, my);
        switch (tab) {
            case PARTY -> drawParty(g, mx, my);
            case MODE -> {
                if (RustclearMod.party != null) {
                    partyMode(g, mx, my, RustclearMod.party, contentX(), contentY(), contentW());
                }
            }
            case STATS -> drawStats(g, mx, my);
            case ROOMS -> drawRooms(g, mx, my);
            case PUZZLES -> drawPuzzles(g, mx, my);
            case SECRETS -> drawSecrets(g, mx, my);
            case SPEED -> drawSpeed(g, mx, my);
            case LEADERBOARD -> drawLeaderboard(g, mx, my);
        }
        drawFooter(g, mx, my);
    }

    private void drawRail(GuiGraphicsExtractor g, int mx, int my) {
        railBackground(g, 100);
        int y = py + 29;
        boolean engine = Engine.isEngineClient();
        if (lobby) {
            Protocol.PartyState party = RustclearMod.party;
            String members = party == null ? "…" : party.members.size() + " of " + party.max;
            railTab(g, mx, my, Tab.PARTY, "Party", members, ENTRANCE, y);
            railTab(g, mx, my, Tab.MODE, "Mode", party == null ? "…" : party.mode.startsWith("DUEL_")
                ? modeLabel(party.mode) + " to " + party.target : party.size == 5 ? "Party" : "Party of " + party.size, PUZZLE, y + 28);
            y += 56;
        }
        // In the lobby a guest only sees the party and their own stats; the dungeon is the leader's.
        if (!lobby || editable) {
            railTab(g, mx, my, Tab.ROOMS, "Rooms", settings.rooms.isEmpty() ? "Random" : settings.rooms.size() + " guaranteed", NORMAL, y);
            railTab(g, mx, my, Tab.PUZZLES, "Puzzles", settings.puzzles == null ? "Random" : settings.puzzles.size() + " of " + puzzleNames().size() + " on", PUZZLE, y + 28);
            railTab(g, mx, my, Tab.SECRETS, "Secrets", settings.secrets == null ? "Random" : settings.secrets[0] + "–" + settings.secrets[1], GOLD, y + 56);
            y += 84;
        }
        railTab(g, mx, my, Tab.SPEED, "Speed", settings.speed == null ? "Default " + DungeonSettings.DEFAULT_SPEED : Integer.toString(settings.speedOrDefault()), SILVER, y);
        if (engine) {
            railTab(g, mx, my, Tab.STATS, "Levels", settings.levelsFromHypixel() ? "Hypixel"
                : "Catacombs " + settings.partyCataOrDefault(), BLOOD, y + 28);
        }

        if (!lobby) {
            // The leaderboard sits apart at the bottom, with whether these settings would count.
            int by = py + ph - 38;
            rect(g, px + 9, by - 6, 83, 1, PANEL_EDGE);
            railTab(g, mx, my, Tab.LEADERBOARD, "Leaderboard", settings.isFullyRandom() ? "Counts" : "Doesn't count", GOOD, by);
        }
    }

    /**
     * F7 / M7 in the header, left of the badge: the leader's to switch (it changes the mobs'
     * health and damage, so it takes a new dungeon), everyone else's to see.
     */
    private void floorSwitch(GuiGraphicsExtractor g, int mx, int my) {
        Floor current = settings.floorOrDefault();
        int right = px + pw - 8 - (font.width(badgeText) + 16) - 8;
        int x = right - (choiceWidth("F7") + choiceWidth("M7") + 3);
        // M7 rests on alpha recordings only (decision 6): say so next to the switch.
        String label = current.master() ? "alpha-measured · Floor" : "Floor";
        text(g, label, x - font.width(label) - 5, py + 8, DIM);
        for (Floor f : Floor.values()) {
            Runnable pick = editable && f != current ? () -> settings.floor = f.code : null;
            int w = choice(g, mx, my, f.code, x, py + 5, f == current, pick);
            if (f == current && f.master()) {
                // Master Mode in the map's blood red.
                outline(g, x, py + 5, w, 13, BLOOD);
            }
            x += w + 3;
        }
    }

    private void railTab(GuiGraphicsExtractor g, int mx, int my, Tab which, String name, String summary, int color, int y) {
        int summaryColor = summary.equals("Random") || summary.equals("Doesn't count") || summary.startsWith("Default") || summary.equals("…") ? DIM
            : color == NORMAL ? lerp(NORMAL, TEXT, 0.45f) : color;
        railItem(g, mx, my, px + 5, y, 91, tab == which, name, summary, color, summaryColor, () -> {
            if (tab != which) {
                tab = which;
                scroll = 0;
                if (which == Tab.LEADERBOARD) {
                    requestBoard();
                }
                rebuildWidgets();
            }
        });
    }

    // --- Party (the lobby) ---

    private static final int MEMBER_H = 17;
    /** Where the members start on the party tab, under the class picker. */
    private static final int MEMBERS_Y = 32;

    /** Where the leader's invite box sits on the party tab. */
    private int partyInviteY() {
        return contentY() + MEMBERS_Y + memberRows() * MEMBER_H + 22;
    }

    /**
     * How many member rows the party tab shows: every member and open slot when they fit above the
     * invite box, else as many as fit (at least five) and the rest scroll.
     */
    private int memberRows() {
        Protocol.PartyState party = RustclearMod.party;
        int rows = party == null ? 5 : Math.max(1, Math.max(party.members.size(), party.max));
        // Under the rows: the invite box and the address (about 56 px), then the footer.
        int room = (py + ph - 30 - 56) - (contentY() + MEMBERS_Y + 22);
        return Math.min(rows, Math.max(5, room / MEMBER_H));
    }

    /** This player's own row in the party, or null. */
    private Protocol.PartyMember myMember() {
        Protocol.PartyState party = RustclearMod.party;
        if (party == null) {
            return null;
        }
        String me = minecraft.getUser().getName();
        return party.members.stream().filter(m -> m.name.equalsIgnoreCase(me)).findFirst().orElse(null);
    }

    private void drawParty(GuiGraphicsExtractor g, int mx, int my) {
        Protocol.PartyState party = RustclearMod.party;
        int x = contentX();
        int y = contentY();
        int w = contentW();
        if (party == null) {
            text(g, "Waiting for the host", x, y + 4, DIM);
            pulse(g, x + font.width("Waiting for the host") + 6, y + 7, GOLD);
            return;
        }
        int tx = x + title(g, "Party", x, y) + 6;
        tx += pill(g, party.members.size() + "/" + party.max, tx, y, GOOD) + 3;
        tx += pill(g, party.open ? "OPEN" : "PRIVATE", tx, y, party.open ? GOOD : GOLD) + 3;
        String me = minecraft.getUser().getName();
        // Your class: picking one readies you (the same one again un-readies you).
        Protocol.PartyMember own = myMember();
        classPicker(g, mx, my, x, y + 15, own == null ? null : DungeonClass.of(own.dungeonClass),
            "lobby".equals(party.phase) ? c -> {
                saveSettings();
                RustclearMod.pickClass(c.name());
            } : null);
        int ry = y + MEMBERS_Y;
        // Five rows show; a bigger party scrolls.
        int rows = Math.max(party.members.size(), party.max);
        int shown = memberRows();
        maxScroll = Math.max(0, rows - shown);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        if (maxScroll > 0) {
            text(g, scroll < maxScroll ? "+" + (rows - scroll - shown) + " below, scroll" : "scroll up", tx, y + 2, DIM);
        }
        for (Protocol.PartyMember member : party.members.subList(Math.min(scroll, party.members.size()),
                Math.min(scroll + shown, party.members.size()))) {
            boolean mine = member.name.equalsIgnoreCase(me);
            boolean hover = over(mx, my, x, ry, w, MEMBER_H - 3);
            round(g, x, ry, w, MEMBER_H - 3, mine ? lerp(ROW_ON, GOLD, 0.08f) : hover ? ROW_HOVER : RAIL);
            if (mine) {
                rect(g, x, ry + 3, 1, MEMBER_H - 9, GOLD);
            }
            avatar(g, member.name, x + 5, ry + 3, member.ready ? GOOD : SILVER);
            int nx = x + 22;
            text(g, member.name, nx, ry + 3, TEXT);
            if (member.leader) {
                pill(g, "LEADER", nx + font.width(member.name) + 5, ry + 1, GOLD);
            }
            int right = x + w - 6;
            if (party.you && hover && !member.leader) {
                int kx = right - font.width("Kick") - 8;
                memberChip(g, mx, my, "Kick", kx, ry + 2, BAD, () -> RustclearMod.sendParty("kick", member.name));
                memberChip(g, mx, my, "Make leader", kx - font.width("Make leader") - 11, ry + 2, GOLD,
                    () -> RustclearMod.sendParty("leader", member.name));
            } else {
                String label = member.ready ? "Ready" : "Not ready";
                int c = member.ready ? GOOD : BAD;
                textRight(g, label, right, ry + 3, c);
                rect(g, right - font.width(label) - 7, ry + 5, 3, 3, c);
                DungeonClass dc = DungeonClass.of(member.dungeonClass);
                if (dc != null) {
                    int pw = font.width(dc.title) + 8;
                    int cx = right - font.width(label) - 13 - pw;
                    pill(g, dc.title, cx, ry + 1, classColor(dc));
                    if (over(mx, my, cx, ry + 1, pw, 12)) {
                        g.setComponentTooltipForNextFrame(font, classTooltip(dc), mx, my);
                    }
                }
            }
            ry += MEMBER_H;
        }
        for (int i = Math.max(party.members.size(), scroll); i < party.max && i < scroll + shown; i++) {
            outline(g, x, ry, w, MEMBER_H - 3, alpha(PANEL_EDGE, 0xA0));
            text(g, "Open slot", x + 22, ry + 3, DIM);
            ry += MEMBER_H;
        }

        // Who can join, the address to give them, and (leader) inviting by name.
        int iy = partyInviteY();
        text(g, "Invite", x, iy - 11, SILVER);
        if (party.you) {
            inputFrame(g, x, iy, 122, inviteName != null && inviteName.isFocused(), false);
            int bx = x + 126;
            bx += choice(g, mx, my, "Invite", bx, iy, false, () -> {
                String name = inviteName == null ? "" : inviteName.getValue().trim();
                if (name.length() >= 3) {
                    RustclearMod.sendParty("invite", name);
                    partyNote = "Invited " + name + ".";
                    inviteName.setValue("");
                }
            }) + 8;
            bx += choice(g, mx, my, "Private", bx, iy, !party.open, () -> RustclearMod.sendParty("close", null)) + 3;
            choice(g, mx, my, "Open", bx, iy, party.open, () -> RustclearMod.sendParty("open", null));
        }
        int ay = iy + 20;
        String address = RustclearMod.shareAddress;
        if (address != null) {
            int cw = choiceWidth("Copy");
            text(g, "Address", x, ay + 3, DIM);
            text(g, fit(address, w - cw - 60), x + 46, ay + 3, GOLD);
            choice(g, mx, my, "Copy", x + w - cw, ay, false, () -> {
                minecraft.keyboardHandler.setClipboard(address);
                partyNote = "Copied. Friends use Join a party > Join by address.";
            });
        } else {
            text(g, fit(Engine.isEngineHost() ? (Online.sharing ? "Sharing the party…"
                : Online.shareProblem != null ? Online.shareProblem : "Not shared yet (Esc > Party > Invite).")
                : "Ask the host for the party's address.", w), x, ay + 3, DIM);
        }
    }

    private void memberChip(GuiGraphicsExtractor g, int mx, int my, String label, int x, int y, int color, Runnable action) {
        int w = font.width(label) + 8;
        boolean hover = over(mx, my, x, y, w, 11);
        round(g, x, y, w, 11, hover ? color : alpha(color, 0x30));
        text(g, label, x + 4, y + 2, hover ? ON_GOLD : color);
        click(x, y, w, 11, action);
    }

    // --- Class & levels ---

    private int levelsY() {
        return contentY() + 36;
    }

    /** A 0..50 level box; empty means [fallback] (shown as the hint). */
    private EditBox levelInput(int x, int y, Integer current, int fallback, java.util.function.Consumer<Integer> set) {
        EditBox box = input(x + 6, y + 3, 30, Integer.toString(fallback), 2);
        box.setValue(current == null ? "" : Integer.toString(current));
        box.setResponder(text -> {
            String digits = text.replaceAll("[^0-9]", "");
            if (!digits.equals(text)) {
                box.setValue(digits);
                return;
            }
            set.accept(digits.isEmpty() ? null : Math.min(50, Integer.parseInt(digits)));
        });
        addRenderableWidget(box);
        return box;
    }

    /**
     * The party's Catacombs and class levels: the same for everyone (the leader sets them) or
     * each player's own from Hypixel. Classes are picked on the party tab. The stats themselves
     * come from gear (engine.PlayerStats) and show in the SkyBlock Menu.
     */
    private void drawStats(GuiGraphicsExtractor g, int mx, int my) {
        int x = contentX();
        int w = contentW();
        int y = contentY();
        title(g, "Levels", x, y);
        Protocol.PartyMember own = myMember();
        DungeonClass picked = own == null ? null : DungeonClass.of(own.dungeonClass);
        text(g, fit(picked == null ? "Pick your class on the Party tab (Esc > Party)."
            : "Your class: " + picked.title + ". Change it on the Party tab.", w), x, y + 18, picked == null ? DIM : classColor(picked));

        // The party's levels: the leader's pick.
        int ly = levelsY();
        text(g, "Catacombs and class levels", x, ly, SILVER);
        if (!editable) {
            lockNote(g, x + w, ly);
        }
        boolean hypixel = settings.levelsFromHypixel();
        int bx = x;
        bx += choice(g, mx, my, "Same for everyone", bx, ly + 12, !hypixel, editable && hypixel ? () -> {
            settings.levels = null;
            rebuildWidgets();
        } : null) + 3;
        choice(g, mx, my, "Each player's Hypixel stats", bx, ly + 12, hypixel, editable && !hypixel ? () -> {
            settings.levels = "hypixel";
            HypixelProfile.fetch(false);
            rebuildWidgets();
        } : null);

        int ry = ly + 30;
        if (!hypixel) {
            text(g, "Catacombs", x, ry + 3, BLOOD);
            text(g, "Class level", x + 120, ry + 3, TEXT);
            if (editable) {
                inputFrame(g, x + 70, ry, 40, partyCata != null && partyCata.isFocused(), false);
                inputFrame(g, x + 190, ry, 40, partyClass != null && partyClass.isFocused(), false);
            } else {
                text(g, Integer.toString(settings.partyCataOrDefault()), x + 76, ry + 3, GOLD);
                text(g, Integer.toString(settings.partyClassLevelOrDefault()), x + 196, ry + 3, GOLD);
            }
            text(g, fit("Everyone plays at these, with maxed skills, slayers and 1,400 Accessory Power.", w), x, ry + 20, DIM);
        } else {
            net.rustclear.mod.engine.SkyblockProfile mine = settings.hypixel;
            String status = HypixelProfile.status();
            if (mine != null) {
                DungeonClass c = picked != null ? picked : DungeonClass.of(mine.selectedClass);
                text(g, fit(String.format(Locale.ROOT, "Yours: Catacombs %d%s, %,d Accessory Power", mine.cataLevel(),
                    c == null ? "" : ", " + c.title + " " + mine.classLevel(c), mine.accessoryPower), w - 70), x, ry + 3, GOOD);
            } else {
                text(g, fit(status != null ? status : "Your Hypixel profile hasn't been read yet.", w - 70), x, ry + 3, status != null ? BAD : MUTED);
            }
            int rw = choiceWidth("Read again");
            choice(g, mx, my, HypixelProfile.busy() ? "Reading…" : "Read again", x + w - rw, ry, false,
                HypixelProfile.busy() ? null : () -> HypixelProfile.fetch(true));
            String inventory = HypixelProfile.inventorySummary();
            text(g, fit(inventory != null ? inventory : "Skills, slayers, Accessory Power, pets and your inventory come from Hypixel too.", w), x, ry + 20, DIM);
        }

        int ny = ry + 40;
        rect(g, x, ny - 4, w, 1, PANEL_EDGE);
        text(g, fit("Health, Defense, Strength, damage, Mana and Speed come from your gear, class and levels.", w), x, ny, MUTED);
        text(g, fit("See them: use the 9th hotbar slot (SkyBlock Menu) > Your SkyBlock Profile, or /sbmenu.", w), x, ny + 11, MUTED);
        text(g, fit("Ctrl+Q: your class ability. Q: your ultimate.", w), x, ny + 22, DIM);
    }

    /** The tab's title, a RANDOM or CUSTOM pill, and a reset link when custom (or the leader's lock for guests). */
    private void tabHeader(GuiGraphicsExtractor g, int mx, int my, String title, boolean random, Runnable reset) {
        tabHeader(g, mx, my, title, random, reset, "RANDOM", "Reset to random", false);
    }

    /** As above, with the pill's word for the untouched state, the reset link's text, and whether guests may change it. */
    private void tabHeader(GuiGraphicsExtractor g, int mx, int my, String title, boolean random, Runnable reset,
                           String untouched, String resetLabel, boolean personal) {
        int x = contentX();
        int y = contentY();
        int tx = x + title(g, title, x, y) + 6;
        pill(g, random ? untouched : "CUSTOM", tx, y, random ? GOOD : GOLD);
        if (!editable && !personal) {
            lockNote(g, x + contentW(), y);
        } else if (!random) {
            link(g, mx, my, resetLabel, x + contentW(), y, reset);
        }
    }

    /** "Leader's pick" with a padlock, right-aligned: this tab is the party leader's to set. */
    private void lockNote(GuiGraphicsExtractor g, int right, int y) {
        String note = "Leader's pick";
        int nx = right - font.width(note);
        text(g, note, nx, y + 2, MUTED);
        outline(g, nx - 9, y + 1, 5, 4, MUTED);
        rect(g, nx - 10, y + 4, 7, 5, MUTED);
    }

    // --- Rooms ---

    private List<Protocol.Room> filteredRooms() {
        List<Protocol.Room> out = new ArrayList<>();
        if (RustclearMod.catalog == null) {
            return out;
        }
        String query = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        for (Protocol.Room room : RustclearMod.catalog.rooms) {
            if (!filter.equals("All") && !filter.equals(shapeLabel(room))) {
                continue;
            }
            if (!query.isEmpty() && !room.name.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            out.add(room);
        }
        return out;
    }

    private boolean guaranteed(String name) {
        return settings.rooms.stream().anyMatch(r -> r.equalsIgnoreCase(name));
    }

    private void drawRooms(GuiGraphicsExtractor g, int mx, int my) {
        tabHeader(g, mx, my, "Guaranteed rooms", settings.rooms.isEmpty(), settings.rooms::clear);
        int x = contentX();
        int y = contentY() + 17;
        int w = contentW();

        inputFrame(g, x, y, 122, search != null && search.isFocused(), true);
        if (!settings.rooms.isEmpty()) {
            textRight(g, settings.rooms.size() + " picked", x + w, y + 3, GOLD);
        }

        // Shape filters as chips on their own row (browsing works read-only too).
        int cx = x;
        int cy = y + 17;
        for (String f : FILTERS) {
            String which = f;
            cx += chip(g, mx, my, f, cx, cy, filter.equals(f), () -> {
                filter = which;
                scroll = 0;
            }) + 2;
        }

        // Two columns of rooms.
        List<Protocol.Room> rooms = filteredRooms();
        int listY = cy + 16;
        int listH = py + ph - 30 - listY;
        int rowsVisible = Math.max(1, listH / ROW_H);
        int colW = (w - 6) / 2;
        int rows = (rooms.size() + 1) / 2;
        maxScroll = Math.max(0, rows - rowsVisible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        if (rooms.isEmpty()) {
            text(g, RustclearMod.catalog == null ? "Join a Rustclear server to see its rooms." : "No rooms match.", x + 4, listY + 4, DIM);
        }
        for (int r = 0; r < rowsVisible; r++) {
            for (int c = 0; c < 2; c++) {
                int index = (scroll + r) * 2 + c;
                if (index >= rooms.size()) {
                    continue;
                }
                roomRow(g, mx, my, rooms.get(index), x + c * (colW + 4), listY + r * ROW_H, colW);
            }
        }
        scrollbar(g, x + w + 4, listY, rowsVisible * ROW_H, rowsVisible, rows, scroll, maxScroll);
    }

    private void roomRow(GuiGraphicsExtractor g, int mx, int my, Protocol.Room room, int rx, int ry, int colW) {
        boolean on = guaranteed(room.name);
        boolean hover = editable && over(mx, my, rx, ry, colW, ROW_H - 1);
        float t = ease("room:" + room.name, on);
        int color = typeColor(room.type);
        int onBg = lerp(ROW_ON, color, 0.16f);
        if (t > 0 || hover) {
            round(g, rx, ry, colW, ROW_H - 1, t > 0 ? lerp(ROW_HOVER, onBg, t) : ROW_HOVER);
            if (t > 0) {
                rect(g, rx, ry + 2, 1, ROW_H - 5, alpha(color, Math.round(255 * t)));
            }
        }
        shapeGlyph(g, room.shape, rx + 4, ry + 2, on ? color : lerp(color, PANEL, 0.35f));

        String secrets = Integer.toString(room.secrets);
        boolean none = room.secrets == 0;
        int sx = rx + colW - font.width(secrets) - 4;
        boolean practice = hover && Engine.canPractice(room.name);
        int nameRight = practice ? practicePill(g, mx, my, room.name, sx - 10, ry + 1) - 2 : sx - 10;
        text(g, fit(room.name, nameRight - rx - 22), rx + 22, ry + 3, on ? TEXT : lerp(TEXT, MUTED, 0.35f));
        // Secret count beside a little chest.
        rect(g, sx - 7, ry + 4, 5, 5, none ? DIM : GOLD_DEEP);
        rect(g, sx - 7, ry + 6, 5, 1, none ? PANEL : GOLD);
        text(g, secrets, sx, ry + 3, none ? DIM : GOLD);

        if (editable) {
            click(rx, ry, colW, ROW_H - 1, () -> {
                if (!settings.rooms.removeIf(n -> n.equalsIgnoreCase(room.name))) {
                    settings.rooms.add(room.name);
                }
            });
        }
        if (practice) {
            // Drawn again so its click sits above the row's.
            practicePill(g, mx, my, room.name, sx - 10, ry + 1);
        }
    }

    /**
     * A small "Practice" pill ending at [right], shown on a hovered room in the Rustclear world:
     * it builds that room alone (as /practice does) and closes the screen. Returns its left edge.
     */
    private int practicePill(GuiGraphicsExtractor g, int mx, int my, String room, int right, int y) {
        String label = "Practice";
        int w = font.width(label) + 8;
        int x = right - w;
        boolean hover = over(mx, my, x, y, w, 10);
        round(g, x, y, w, 10, hover ? GOLD : alpha(GOLD, 0x30));
        text(g, label, x + 4, y + 1, hover ? ON_GOLD : GOLD);
        click(x, y, w, 10, () -> {
            RustclearMod.settings = settings.copy();
            Engine.practice(room, settings.practiceMobs());
            minecraft.gui.setScreen(null);
        });
        return x;
    }

    // --- Puzzles ---

    private List<String> puzzleNames() {
        List<String> names = new ArrayList<>();
        if (RustclearMod.catalog != null) {
            for (Protocol.Room room : RustclearMod.catalog.puzzles) {
                names.add(room.name);
            }
        }
        return names;
    }

    private void drawPuzzles(GuiGraphicsExtractor g, int mx, int my) {
        tabHeader(g, mx, my, "Puzzles", settings.puzzles == null, () -> settings.puzzles = null);
        int x = contentX();
        int y = contentY() + 18;
        int w = contentW();
        int colW = (w - 6) / 2;

        List<String> names = puzzleNames();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            boolean on = settings.puzzles == null || settings.puzzles.contains(name);
            int rx = x + (i % 2) * (colW + 6);
            int ry = y + (i / 2) * 21;
            boolean hover = editable && over(mx, my, rx, ry, colW, 18);
            float t = ease("puzzle:" + name, on);

            round(g, rx, ry, colW, 18, hover ? ROW_HOVER : RAIL);
            rect(g, rx + 5, ry + 6, 6, 6, lerp(DIM, PUZZLE, t));
            int sx = rx + colW - 18 - 5;
            boolean practice = hover && Engine.canPractice(name);
            int nameRight = practice ? practicePill(g, mx, my, name, sx - 4, ry + 4) - 2 : sx - 4;
            text(g, fit(name, nameRight - rx - 16), rx + 16, ry + 5, lerp(MUTED, TEXT, t));
            toggle(g, "puzzle:" + name, sx, ry + 5, on, PUZZLE);

            if (editable) {
                click(rx, ry, colW, 18, () -> {
                    if (settings.puzzles == null) {
                        settings.puzzles = new ArrayList<>(puzzleNames());
                    }
                    if (!settings.puzzles.remove(name)) {
                        settings.puzzles.add(name);
                    }
                    if (settings.puzzles.size() == puzzleNames().size()) {
                        settings.puzzles = null;
                    }
                });
            }
            if (practice) {
                // Drawn again so its click sits above the row's.
                practicePill(g, mx, my, name, sx - 4, ry + 4);
            }
        }

        int note = y + ((names.size() + 1) / 2) * 21 + 4;
        int on = settings.puzzles == null ? names.size() : settings.puzzles.size();
        if (on < 2) {
            text(g, "Turn on at least 2: every layout has 2 to 5 puzzles.", x, note, BAD);
        } else {
            text(g, "Every layout has 2 to 5 puzzle rooms.", x, note, DIM);
        }
    }

    // --- Secrets ---

    private float[] histogram() {
        Protocol.Catalog catalog = RustclearMod.catalog;
        if (catalog == null || catalog.secretHistogram == null) {
            return new float[0];
        }
        return catalog.secretHistogram;
    }

    /** The secret total at which a fraction {@code q} of random dungeons is reached. */
    private static int quantile(float[] h, float q) {
        float total = 0;
        for (float v : h) {
            total += v;
        }
        float run = 0;
        for (int i = 0; i < h.length; i++) {
            run += h[i];
            if (run >= total * q) {
                return i;
            }
        }
        return Math.max(0, h.length - 1);
    }

    private static int peak(float[] h) {
        int best = 0;
        for (int i = 1; i < h.length; i++) {
            if (h[i] > h[best]) {
                best = i;
            }
        }
        return best;
    }

    private void drawSecrets(GuiGraphicsExtractor g, int mx, int my) {
        tabHeader(g, mx, my, "Total secrets", settings.secrets == null, () -> settings.secrets = null);
        int x = contentX();
        int y = contentY() + 19;
        int w = contentW();
        float[] h = histogram();

        // Domain: a little past where random dungeons start and end.
        int first = 0;
        while (first < h.length && h[first] < 0.01f) {
            first++;
        }
        int last = h.length - 1;
        while (last > 0 && h[last] < 0.01f) {
            last--;
        }
        if (h.length == 0) {
            first = 30;
            last = 70;
        }
        sliderLo = Math.max(0, (first - 8) / 5 * 5);
        sliderHi = (last + 12) / 5 * 5;

        int[] range = settings.secrets;
        String readout = range == null ? "Any total" : range[0] + " – " + range[1] + " secrets";
        textShadow(g, readout, x, y, range == null ? MUTED : GOLD);
        if (h.length > 0) {
            float total = 0;
            float inside = 0;
            for (int i = 0; i < h.length; i++) {
                total += h[i];
                if (range != null && i >= range[0] && i <= range[1]) {
                    inside += h[i];
                }
            }
            String share = range == null
                ? "Random runs: " + first + "–" + last + ", most often " + peak(h)
                : Math.round(100 * inside / Math.max(total, 1e-6f)) + "% of random runs land here";
            textRight(g, share, x + w, y, MUTED);
        }

        // One bar per secret total.
        chartY = y + 16;
        int chartH = 62;
        sliderX = x + 4;
        sliderW = w - 8;
        int span = sliderHi - sliderLo + 1;
        round(g, x, chartY - 3, w, chartH + 6, RAIL);
        for (int s = sliderLo; s <= sliderHi; s++) {
            int bx0 = valueToX(s);
            int bx1 = Math.max(bx0 + 1, valueToX(s + 1) - 1);
            float v = s < h.length ? h[s] : 0f;
            int bh = Math.round(v * (chartH - 4));
            boolean in = range != null && s >= range[0] && s <= range[1];
            int c = range == null ? lerp(DIM, MUTED, 0.25f) : in ? GOLD : DIM;
            if (bh > 0) {
                g.fill(bx0, chartY + chartH - bh, bx1, chartY + chartH, c);
            }
        }
        // Axis labels every 10.
        int axisY = chartY + chartH + 4;
        for (int s = (sliderLo + 9) / 10 * 10; s <= sliderHi; s += 10) {
            int tx = valueToX(s) + sliderW / span / 2;
            textCenter(g, Integer.toString(s), tx, axisY + 3, DIM);
        }

        if (range != null) {
            int ax = valueToX(range[0]);
            int bx = valueToX(range[1] + 1) - 1;
            rect(g, ax, chartY - 3, bx - ax + 1, 1, GOLD);
            for (int i = 0; i < 2; i++) {
                int hx = i == 0 ? ax : bx;
                boolean hot = editable && (dragging == i || (dragging < 0 && over(mx, my, hx - 4, chartY - 4, 9, chartH + 10)));
                rect(g, hx, chartY - 3, 1, chartH + 6, hot ? TEXT : GOLD);
                round(g, hx - 3, chartY + chartH + 1, 7, 6, hot ? TEXT : GOLD);
            }
        } else if (editable) {
            click(x, chartY - 4, w, chartH + 10, () -> settings.secrets = new int[] {quantile(h, 0.3f), quantile(h, 0.7f)});
            text(g, "Click to set a range", x + 6, chartY + 3, MUTED);
        }

        // Presets from the random distribution.
        int presetY = axisY + 16;
        int cx = x;
        String[] names = {"Light", "Typical", "Heavy"};
        float[][] qs = {{0.02f, 0.3f}, {0.3f, 0.7f}, {0.7f, 0.98f}};
        for (int i = 0; i < names.length && h.length > 0; i++) {
            int[] preset = {quantile(h, qs[i][0]), quantile(h, qs[i][1])};
            String label = names[i] + " " + preset[0] + "–" + preset[1];
            cx += choice(g, mx, my, label, cx, presetY, Arrays.equals(preset, range),
                editable ? () -> settings.secrets = preset.clone() : null) + 3;
        }
    }

    private int valueToX(int value) {
        return sliderX + (value - sliderLo) * sliderW / (sliderHi - sliderLo + 1);
    }

    private int xToValue(double mx) {
        int v = sliderLo + (int) Math.floor((mx - sliderX) * (sliderHi - sliderLo + 1) / (double) sliderW);
        return Math.max(sliderLo, Math.min(sliderHi, v));
    }

    // --- Speed ---

    /**
     * In the Rustclear world speed is a stat like the rest (engine.PlayerStats: base 100, gear,
     * class, the Dungeon potion, Ragnarok; capped at 500), so this shows what it is now, as the
     * host set it on this player.
     */
    private int drawStatSpeed(GuiGraphicsExtractor g, int x, int w) {
        int y = contentY();
        int tx = x + title(g, "Speed", x, y) + 6;
        pill(g, "FROM STATS", tx, y, GOOD);
        y += 20;
        var attribute = minecraft.player == null ? null
            : minecraft.player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        int value = attribute == null ? 100 : (int) Math.round(attribute.getBaseValue() * 1000);
        int bw = bigNumber(g, Integer.toString(value), x, y, 3, TEXT);
        text(g, "✦ Speed", x + bw + 8, y, SILVER);
        text(g, "SkyBlock stat", x + bw + 8, y + 9, DIM);
        double walk = 4.317 * value / 100.0;
        textRight(g, String.format(Locale.ROOT, "Walk %.1f b/s", walk), x + w, y, MUTED);
        textRight(g, String.format(Locale.ROOT, "Sprint %.1f b/s", walk * 1.3), x + w, y + 9, MUTED);
        int noteY = y + 34;
        text(g, fit("Your Speed comes from your stats: base 100, your gear, class and the", w), x, noteY, DIM);
        text(g, fit("Dungeon potion, up to the 500 cap. Swap gear to change it.", w), x, noteY + 11, DIM);
        text(g, fit("Where it comes from: SkyBlock Menu (9th slot) > Your SkyBlock Profile.", w), x, noteY + 22, MUTED);
        return noteY + 11;
    }

    /** The speed setting's slider, for the Rust server. Returns where the notes below it end. */
    private int drawSpeedSlider(GuiGraphicsExtractor g, int mx, int my, int x, int w) {
        tabHeader(g, mx, my, "Speed", settings.speed == null, () -> settings.speed = null,
            "DEFAULT", "Reset to " + DungeonSettings.DEFAULT_SPEED, true);
        int y = contentY() + 20;
        int value = settings.speedOrDefault();
        boolean custom = settings.speed != null;

        // The value large, with what it means in blocks per second (vanilla walks 4.317 at 100).
        int bw = bigNumber(g, Integer.toString(value), x, y, 3, custom ? GOLD : TEXT);
        text(g, "✦ Speed", x + bw + 8, y, SILVER);
        text(g, "SkyBlock stat", x + bw + 8, y + 9, DIM);
        double walk = 4.317 * value / 100.0;
        textRight(g, String.format(Locale.ROOT, "Walk %.1f b/s", walk), x + w, y, MUTED);
        textRight(g, String.format(Locale.ROOT, "Sprint %.1f b/s", walk * 1.3), x + w, y + 9, MUTED);

        // Slider: a track filled up to the handle, the default marked.
        speedX = x + 4;
        speedW = w - 8;
        speedY = y + 30;
        round(g, x, speedY - 6, w, 18, RAIL);
        int hx = speedToX(value);
        rect(g, speedX, speedY + 2, speedW, 2, DIM);
        rect(g, speedX, speedY + 2, hx - speedX, 2, custom ? GOLD : lerp(DIM, MUTED, 0.5f));
        int dx = speedToX(DungeonSettings.DEFAULT_SPEED);
        rect(g, dx, speedY, 1, 6, custom ? MUTED : TEXT);
        boolean hot = dragging == 2 || (dragging < 0 && over(mx, my, speedX - 4, speedY - 6, speedW + 8, 18));
        rect(g, hx, speedY - 3, 1, 12, hot ? TEXT : custom ? GOLD : MUTED);
        round(g, hx - 3, speedY + 9, 7, 6, hot ? TEXT : custom ? GOLD : MUTED);

        // Axis labels every 50.
        int axisY = speedY + 17;
        for (int v = DungeonSettings.MIN_SPEED; v <= DungeonSettings.MAX_SPEED; v += 50) {
            String label = Integer.toString(v);
            int lx = Math.max(x, Math.min(x + w - font.width(label), speedToX(v) - font.width(label) / 2));
            text(g, label, lx, axisY, v == value ? (custom ? GOLD : TEXT) : DIM);
        }

        // Presets.
        int presetY = axisY + 14;
        int cx = x;
        for (int preset : new int[] {400, DungeonSettings.DEFAULT_SPEED, 600, 700}) {
            String label = preset == DungeonSettings.DEFAULT_SPEED ? preset + " Default" : Integer.toString(preset);
            cx += choice(g, mx, my, label, cx, presetY, value == preset, () -> setSpeed(preset)) + 3;
        }

        boolean engine = Engine.isEngineClient();
        int noteY = presetY + 20;
        text(g, fit("Hypixel's scale: 100 is vanilla walking, sprinting adds 30%.", w), x, noteY, DIM);
        String where = engine
            ? "Your own speed. Applies on Done or New dungeon. Doesn't affect ranking."
            : "Applies in the Rustclear world. Servers use their own speed.";
        text(g, fit(where, w), x, noteY + 11, DIM);

        return noteY;
    }

    private void drawSpeed(GuiGraphicsExtractor g, int mx, int my) {
        boolean engine = Engine.isEngineClient();
        int x = contentX();
        int w = contentW();
        int noteY;
        if (engine) {
            noteY = drawStatSpeed(g, x, w);
        } else {
            noteY = drawSpeedSlider(g, mx, my, x, w);
        }

        // Save Hotbar / Default Hotbar (engineerClient's): only in a Rustclear world, each player's own.
        if (!engine) {
            return;
        }
        int hotbarY = noteY + 32;
        text(g, "Hotbar", x, hotbarY, SILVER);
        int bx = x;
        int by = hotbarY + 13;
        String[] labels = {"Save Hotbar", "Default Hotbar"};
        for (int i = 0; i < labels.length; i++) {
            boolean save = i == 0;
            bx += choice(g, mx, my, labels[i], bx, by, false, () -> {
                Engine.hotbar(save);
                hotbarNote = save ? "Saved where your items are now." : "Back to the default layout.";
            }) + 3;
        }
        // The Kit editor sets the items themselves; saving it here hands the kit out right away.
        choice(g, mx, my, "Kit editor", bx, by, false, () -> minecraft.gui.setScreen(new KitEditorScreen(this)));
        text(g, fit(hotbarNote != null ? hotbarNote : "Save keeps where your items are for every new dungeon.", w),
            x, by + 20, hotbarNote != null ? GOLD : DIM);

        // Practice rooms with or without their mobs (off by default, as on the server), at the
        // right of the hotbar row. Practice rebuilds everyone's dungeon, so it is the leader's.
        if (!editable) {
            return;
        }
        String[] modes = {"No mobs", "Mobs"};
        int right = x + w;
        for (int i = modes.length - 1; i >= 0; i--) {
            boolean mobs = i == 1;
            int cw = choiceWidth(modes[i]);
            choice(g, mx, my, modes[i], right - cw, by, settings.practiceMobs() == mobs,
                () -> settings.practiceMobs = mobs ? Boolean.TRUE : null);
            right -= cw + 3;
        }
        textRight(g, "Practice rooms", x + w, hotbarY, SILVER);

        // Debug: the SimLog (the host's run as JSON lines for tools/f7check), under the hotbar row.
        int logY = by + 40;
        text(g, "SimLog (debug)", x, logY, SILVER);
        String[] logModes = {"Off", "On"};
        int lx = x;
        for (int i = 0; i < logModes.length; i++) {
            boolean on = i == 1;
            lx += choice(g, mx, my, logModes[i], lx, logY + 13, settings.simLog() == on,
                () -> settings.simLog = on ? Boolean.TRUE : null) + 3;
        }
        text(g, fit("Writes rustclear-simlog/*.jsonl in the game folder, for tools/f7check.", w - (lx - x) - 6), lx + 6, logY + 16, DIM);

        // Room-entry HP scaling (Hypixel's x(1 + 0.05k)); the rule for k is still open, so it can be turned off.
        int scaleY = logY + 40;
        text(g, "Mob HP rises on room entry", x, scaleY, SILVER);
        int sx = x;
        for (int i = 0; i < logModes.length; i++) {
            boolean on = i == 1;
            sx += choice(g, mx, my, logModes[i], sx, scaleY + 13, settings.roomHpScalingOrDefault() == on,
                () -> settings.roomHpScaling = on ? null : Boolean.FALSE) + 3;
        }
    }

    private int speedToX(int value) {
        int span = DungeonSettings.MAX_SPEED - DungeonSettings.MIN_SPEED;
        return speedX + (value - DungeonSettings.MIN_SPEED) * speedW / span;
    }

    /** The speed under the mouse, snapped to 5. */
    private int xToSpeed(double mx) {
        int span = DungeonSettings.MAX_SPEED - DungeonSettings.MIN_SPEED;
        double raw = DungeonSettings.MIN_SPEED + (mx - speedX) * span / (double) Math.max(1, speedW);
        return DungeonSettings.clampSpeed((int) Math.round(raw / 5.0) * 5);
    }

    /** Sets the speed; the default is stored as unset. */
    private void setSpeed(int value) {
        int v = DungeonSettings.clampSpeed(value);
        settings.speed = v == DungeonSettings.DEFAULT_SPEED ? null : v;
    }

    // --- Leaderboard ---

    /** Short chip labels: "solo" is Solo, "3 players" is 3 (after a "Party" label). */
    private static String categoryLabel(String category) {
        if (category.endsWith(" players")) {
            return category.substring(0, category.length() - " players".length());
        }
        return category.isEmpty() ? category : Character.toUpperCase(category.charAt(0)) + category.substring(1);
    }

    /** m:ss.cc from game ticks (50 ms each). */
    public static String formatTime(long ticks) {
        long centis = ticks * 5;
        return String.format(Locale.ROOT, "%d:%02d.%02d", centis / 6000, centis / 100 % 60, centis % 100);
    }

    private static String ownName() {
        try {
            return Minecraft.getInstance().getUser().getName();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean isOwn(Protocol.Entry run, String me) {
        return !me.isEmpty() && run.players.stream().anyMatch(p -> p.equalsIgnoreCase(me));
    }

    private void drawLeaderboard(GuiGraphicsExtractor g, int mx, int my) {
        Protocol.BoardMessage board = RustclearMod.board;
        int x = contentX();
        int y = contentY();
        int w = contentW();
        title(g, "Leaderboard", x, y);

        // Party-size chips on the right.
        List<String> categories = board == null ? List.of() : board.categories;
        String current = board == null ? "" : board.category;
        int cx = x + w;
        for (int i = categories.size() - 1; i >= 0; i--) {
            String which = categories.get(i);
            String label = categoryLabel(which);
            cx -= font.width(label) + 8;
            chip(g, mx, my, label, cx, y, which.equals(current), () -> {
                if (!which.equals(current)) {
                    scroll = 0;
                    RustclearMod.requestBoard(which);
                }
            });
            cx -= 2;
        }
        if (!categories.isEmpty()) {
            textRight(g, "Party", cx - 2, y + 2, DIM);
        }

        // Column layout: rank, players, then time, score and secrets right-aligned.
        int colRank = x + 4;
        int colSecrets = x + w - 4;
        int colScore = colSecrets - 40;
        int colTime = colScore - 34;
        int namesW = colTime - 44 - (x + 24);
        int headY = y + 18;
        text(g, "#", colRank, headY, DIM);
        text(g, "Players", x + 24, headY, DIM);
        textRight(g, "Time", colTime, headY, MUTED);
        textRight(g, "Score", colScore, headY, DIM);
        textRight(g, "Secrets", colSecrets, headY, DIM);
        rect(g, x, headY + 10, w, 1, PANEL_EDGE);

        String me = ownName();
        Protocol.Entry you = board == null ? null : board.personalBest;
        boolean pinYou = you != null;
        int listY = headY + 13;
        int listBottom = py + ph - 30 - (pinYou ? ROW_H + 3 : 0);
        int rowsVisible = Math.max(1, (listBottom - listY) / ROW_H);
        List<Protocol.Entry> entries = board == null ? List.of() : board.entries;
        maxScroll = Math.max(0, entries.size() - rowsVisible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        hoveredRun = null;

        if (entries.isEmpty()) {
            String empty = RustclearMod.catalog == null ? "Join a Rustclear server to see its leaderboard."
                : board == null ? "Loading…" : "No runs yet. Open blood with 300+ on a fully random dungeon.";
            text(g, empty, x + 4, listY + 4, DIM);
        }
        for (int r = 0; r < rowsVisible && scroll + r < entries.size(); r++) {
            Protocol.Entry run = entries.get(scroll + r);
            runRow(g, mx, my, run, x, listY + r * ROW_H, w, namesW, colTime, colScore, colSecrets, isOwn(run, me));
        }
        scrollbar(g, x + w + 4, listY, rowsVisible * ROW_H, rowsVisible, entries.size(), scroll, maxScroll);

        // The player's own best, pinned under the list.
        if (pinYou) {
            int yy = py + ph - 30 - ROW_H;
            rect(g, x, yy - 2, w, 1, PANEL_EDGE);
            runRow(g, mx, my, you, x, yy, w, namesW, colTime, colScore, colSecrets, true);
        }
    }

    private void runRow(GuiGraphicsExtractor g, int mx, int my, Protocol.Entry run, int rx, int ry, int w,
                        int namesW, int colTime, int colScore, int colSecrets, boolean own) {
        boolean hover = over(mx, my, rx, ry, w, ROW_H - 1);
        if (hover) {
            hoveredRun = run;
        }
        if (own || hover) {
            round(g, rx, ry, w, ROW_H - 1, own ? lerp(ROW_ON, GOLD, hover ? 0.16f : 0.1f) : ROW_HOVER);
        }
        if (own) {
            rect(g, rx, ry + 2, 1, ROW_H - 5, GOLD);
        }

        // Top three get a medal square in gold, silver and bronze.
        int medal = run.rank == 1 ? GOLD : run.rank == 2 ? SILVER : run.rank == 3 ? BRONZE : 0;
        String rank = Integer.toString(run.rank);
        if (medal != 0) {
            round(g, rx + 2, ry + 1, 15, 11, alpha(medal, 0x38));
        }
        text(g, rank, rx + 10 - font.width(rank) / 2, ry + 3, medal != 0 ? medal : MUTED);

        String names = String.join(", ", run.players);
        text(g, fit(names, namesW), rx + 24, ry + 3, own ? TEXT : lerp(TEXT, MUTED, 0.2f));
        textRight(g, formatTime(run.ticks), colTime, ry + 3, TEXT);
        textRight(g, Integer.toString(run.score), colScore, ry + 3, run.score >= 300 ? GOOD : TEXT);
        textRight(g, run.secretsFound + "/" + run.totalSecrets, colSecrets, ry + 3, GOLD);
    }

    // --- Footer ---

    private void drawFooter(GuiGraphicsExtractor g, int mx, int my) {
        int y = py + ph - 23;
        int x = contentX();
        rect(g, x, y - 5, contentW(), 1, PANEL_EDGE);

        int right = px + pw - 8;
        if (lobby) {
            drawLobbyFooter(g, mx, my, x, y, right);
            return;
        }
        if (tab == Tab.LEADERBOARD) {
            // Nothing to apply here, so just a way out.
            right = button(g, mx, my, "Done", right, y, Style.SECONDARY, this::onClose);
        } else {
            if (editable) {
                right = button(g, mx, my, "New dungeon", right, y, Style.PRIMARY, () -> {
                    RustclearMod.settings = settings.copy();
                    RustclearMod.send("generate");
                }) - 4;
            }
            right = button(g, mx, my, "Done", right, y, Style.SECONDARY, this::onClose);
            right = button(g, mx, my, "Cancel", right - 4, y, Style.SECONDARY, this::back);
        }

        int room = right - x - 8;
        if (tab == Tab.LEADERBOARD && hoveredRun != null) {
            String when = Instant.ofEpochSecond(hoveredRun.finishedAt).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT));
            text(g, fit("Played " + when, room), x, y + 5, MUTED);
        } else if (tab == Tab.LEADERBOARD && RustclearMod.board != null) {
            Protocol.BoardMessage board = RustclearMod.board;
            if (board.justFinished != null) {
                text(g, fit("Your run is #" + board.justFinished.rank + " in " + formatTime(board.justFinished.ticks) + "!", room), x, y + 5, GOLD);
            } else if (board.currentRunRanked) {
                text(g, fit("This run can make the board.", room), x, y + 5, GOOD);
            } else if (board.currentRunReason != null) {
                text(g, fit("This run won't count: " + board.currentRunReason, room), x, y + 5, DIM);
            }
        } else if (status != null && status.message != null && !status.message.isEmpty()) {
            text(g, fit(status.message, room), x, y + 5, status.ok ? GOOD : BAD);
        } else if (!editable) {
            text(g, fit("The party leader picks the dungeon.", room), x, y + 5, DIM);
        }
    }

    // --- Input ---

    @Override
    protected boolean pressed(double ex, double ey) {
        int mx = (int) ex;
        int my = (int) ey;
        if (tab == Tab.SPEED && over(mx, my, speedX - 4, speedY - 6, speedW + 8, 22)) {
            dragging = 2;
            setSpeed(xToSpeed(ex));
            return true;
        }
        if (editable && tab == Tab.SECRETS && settings.secrets != null && over(mx, my, sliderX - 4, chartY - 4, sliderW + 8, 76)) {
            int value = xToValue(ex);
            int[] r = settings.secrets;
            dragging = Math.abs(value - r[0]) <= Math.abs(value - r[1]) ? 0 : 1;
            moveHandle(value);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging == 2) {
            setSpeed(xToSpeed(event.x()));
            return true;
        }
        if (dragging >= 0 && settings.secrets != null) {
            moveHandle(xToValue(event.x()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = -1;
        return super.mouseReleased(event);
    }

    private void moveHandle(int value) {
        int[] r = settings.secrets;
        if (dragging == 0) {
            r[0] = Math.min(value, r[1]);
        } else if (dragging == 1) {
            r[1] = Math.max(value, r[0]);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (tab == Tab.ROOMS || tab == Tab.LEADERBOARD || tab == Tab.PARTY) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    void onStatus(Protocol.StatusMessage status) {
        this.status = status;
        boolean nowEditable = RustclearMod.canEditDungeon();
        if (nowEditable != editable) {
            // Leadership moved: the dungeon tabs open up or lock.
            editable = nowEditable;
            if (!editable && (tab == Tab.ROOMS || tab == Tab.PUZZLES || tab == Tab.SECRETS) && lobby) {
                tab = Tab.PARTY;
            }
            rebuildWidgets();
        }
        if (!editable) {
            // Follow the leader's changes, keeping what this player has set for themselves.
            DungeonSettings view = leaderView(status);
            keepPersonal(view, settings);
            settings = view;
        }
    }

    /** A new party state: the party tab's leader-only parts may have come or gone. */
    void onParty() {
        if (tab == Tab.PARTY) {
            boolean leader = RustclearMod.party != null && RustclearMod.party.you;
            // The invite box sits under the member rows, which follow the party's size.
            if (leader != (inviteName != null) || (inviteName != null && inviteName.getY() != partyInviteY() + 3)) {
                rebuildWidgets();
            }
        }
    }

    // --- Lobby footer ---

    /** Whether the leader has changed the dungeon (or floor) since it was built. */
    private boolean dungeonChanged() {
        Protocol.StatusMessage s = RustclearMod.lastStatus;
        if (!editable || s == null || s.settings == null) {
            return false;
        }
        DungeonSettings mine = settings.copy();
        DungeonSettings built = s.settings.copy();
        for (DungeonSettings d : new DungeonSettings[] {mine, built}) {
            d.speed = null;
            d.practiceMobs = null;
            d.simLog = null;
            d.hypixel = null;
            d.floor = d.floorOrDefault().code;
        }
        return !Protocol.GSON.toJson(mine).equals(Protocol.GSON.toJson(built));
    }

    private void drawLobbyFooter(GuiGraphicsExtractor g, int mx, int my, int x, int y, int right) {
        Protocol.PartyState party = RustclearMod.party;
        Protocol.PartyMember own = myMember();
        boolean ready = own != null && own.ready;
        boolean changed = dungeonChanged();
        if (party != null && party.you) {
            right = button(g, mx, my, "Force start", right, y, Style.PRIMARY, changed ? null : () -> {
                saveSettings();
                RustclearMod.sendParty("start", null);
            }) - 4;
            if (changed) {
                right = button(g, mx, my, "Apply changes", right, y, Style.SECONDARY, () -> {
                    RustclearMod.settings = settings.copy();
                    RustclearMod.send("generate");
                }) - 4;
            }
        }
        right = button(g, mx, my, "Leave party", right, y, Style.DANGER, this::leaveParty);

        String line;
        int color = DIM;
        if (changed) {
            line = "Apply your changes first: they build a new dungeon.";
            color = GOLD;
        } else if (partyNote != null) {
            line = partyNote;
            color = GOLD;
        } else if (status != null && status.message != null && !status.message.isEmpty()) {
            line = status.message;
            color = status.ok ? GOOD : BAD;
        } else if (ready) {
            line = "Ready. Pick your class again to unready.";
        } else if (party != null && party.you) {
            line = "Pick a class to ready up. Force start gives the rest one.";
        } else {
            line = party == null || party.leader == null ? "Waiting for the party." : party.leader + " picks the dungeon. Pick a class to ready up.";
        }
        text(g, fit(line, right - x - 8), x, y + 5, color);
    }

    /** Sends this player's own settings (speed, stats; the leader's dungeon picks too) if they changed. */
    private void saveSettings() {
        DungeonSettings next;
        if (editable) {
            next = settings.copy();
        } else {
            next = RustclearMod.settings.copy();
            keepPersonal(next, settings);
        }
        boolean changed = !Protocol.GSON.toJson(next).equals(Protocol.GSON.toJson(RustclearMod.settings));
        RustclearMod.settings = next;
        if (changed) {
            RustclearMod.send("apply");
        }
    }

    /** Leaves the party for the Rustclear menu; for the host this closes the party for everyone. */
    private void leaveParty() {
        saveSettings();
        minecraft.disconnectFromWorld(Component.literal("Left the party"));
        minecraft.gui.setScreen(new RustclearMenuScreen(new net.minecraft.client.gui.screens.TitleScreen()));
    }

    /** The run is starting: keep this player's changes and get out of the way. */
    public void closeForRun() {
        saveSettings();
        minecraft.gui.setScreen(null);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !lobby;
    }

    /**
     * Done (and Escape) keeps the settings for the next dungeon. A guest's only change is their own
     * speed: their saved dungeon picks (for their own world) stay as they were.
     */
    @Override
    public void onClose() {
        if (lobby) {
            return;
        }
        saveSettings();
        back();
    }
}
