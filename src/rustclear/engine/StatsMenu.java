package net.rustclear.mod.engine;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * Hypixel's /stats, page by page (stats.md §2, menus-skyblock.md A §9):
 * - "Stats & Equipment": your held item at 2, the equipment down column 1 (10/19/28/37) and the
 *   armour down column 2 (11/20/29/38), the stat categories on the right (Combat 14, Mining 15,
 *   Farming 16, Foraging 23, Fishing 24, Miscellaneous 25, Hunting 32, Wisdom 34), each listing
 *   every one of its stats (0 for the ones the Rustclear world has no use for), the pet at 47,
 *   Go Back at 48 only when opened from the SkyBlock Menu, Close, Active Effects and Achievements
 *   at 49-51. Clicking armour or equipment in your inventory below puts it on, clicking a worn
 *   piece takes it off; either way a sound and a new window next tick, as Hypixel answers.
 * - "Your Stats Breakdown": a category's stats, 10-16 and 19-25.
 * - "Stats ➜ Defense": where a stat comes from, as Flat Bonuses, Additive Buffs and Multiplicative
 *   Buffs (21-23), each listing its parts.
 * - "Defense ➜ Additive Buffs": each part on its own.
 * The three breakdown pages are Rustclear's own: Hypixel's were never recorded.
 */
final class StatsMenu extends ChestGui {
    static final String TITLE = "Stats & Equipment";
    private static final String BREAKDOWN_TITLE = "Your Stats Breakdown";
    private static final int HELD = 2;
    private static final int[] EQUIPMENT = {10, 19, 28, 37};
    private static final int[] ARMOR = {11, 20, 29, 38};
    private static final int COMBAT = 14;
    private static final int MISC = 25;
    private static final int PET = 47;
    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43};
    private static final int FLAT = 21;
    private static final int ADDITIVE = 22;
    private static final int MULTIPLIED = 23;
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] ARMOR_NAMES = {"Helmet", "Chestplate", "Leggings", "Boots"};

    /**
     * One line of a category's lore: ` <colour><glyph> <label> §f<value>`. [key] is the [Stat] it
     * reads (by Stat.of, so a stat Stat.java gains later shows up by itself); without one it shows 0.
     * [struck]: Hypixel draws it gray struck through when it doesn't apply (Sweep, 1,388 of 1,413).
     */
    private record Row(String label, String color, String glyph, boolean percent, boolean struck) {
        Row(String label, String color, String glyph, boolean percent) {
            this(label, color, glyph, percent, false);
        }

        Stat stat() {
            return Stat.of(label);
        }
    }

    /** Hypixel's eight categories, in their slots, with every stat in its order (stats.md §2). */
    private record Category(int slot, Item icon, String name, List<String> about, List<Row> rows) {}

    // OPEN: 69 — Hypixel draws these with its resource pack's U+E0xx glyphs; Rustclear keeps the classic symbols
    // (Stat.java's where Stat has the stat). Stats with no classic symbol (Gemstone Spread, Overbloom, Timber,
    // Tracking, Pull, Hunting Fortune) get a stand-in chosen here.
    private static final String FORTUNE = "☘";
    private static final List<Category> CATEGORIES = List.of(
        new Category(COMBAT, Items.STONE_SWORD, "§cCombat Stats",
            List.of("§7Stats that influence how much", "§7damage you take and deal when in", "§7combat."),
            List.of(new Row("Health", "§c", "❤", false), new Row("Defense", "§a", "❈", false),
                new Row("True Defense", "§f", "❂", false), new Row("Strength", "§c", "❁", false),
                new Row("Crit Chance", "§9", "☣", true), new Row("Crit Damage", "§9", "☠", true),
                new Row("Attack Speed", "§e", "⚔", true), new Row("Ferocity", "§c", "⫽", false),
                new Row("Swing Range", "§e", "Ⓢ", false), new Row("Intelligence", "§b", "✎", false),
                new Row("Ability Damage", "§c", "๑", true), new Row("Health Regen", "§c", "❣", false),
                new Row("Vitality", "§4", "♨", false), new Row("Mending", "§a", "☄", false))),
        new Category(15, Items.STONE_PICKAXE, "§6Mining Stats",
            List.of("§7Stats that influence what you can", "§7break, how quickly you can break it,",
                "§7and how many drops you receive", "§7when mining."),
            List.of(new Row("Breaking Power", "§2", "Ⓟ", false), new Row("Mining Speed", "§6", "⸕", false),
                new Row("Mining Spread", "§e", "▚", false), new Row("Gemstone Spread", "§e", "▚", false),
                new Row("Pristine", "§5", "✧", false), new Row("Mining Fortune", "§6", FORTUNE, false),
                new Row("Ore Fortune", "§6", FORTUNE, false), new Row("Block Fortune", "§6", FORTUNE, false),
                new Row("Dwarven Metal Fortune", "§6", FORTUNE, false), new Row("Gemstone Fortune", "§6", FORTUNE, false))),
        new Category(16, Items.GOLDEN_HOE, "§aFarming Stats",
            List.of("§7Stats that influence how many drops", "§7you receive and how many pests", "§7spawn when farming."),
            farmingRows()),
        new Category(23, Items.JUNGLE_SAPLING, "§2Foraging Stats",
            List.of("§7Stat that includes how many drops", "§7you receive when foraging."),
            List.of(new Row("Sweep", "§2", "∮", false, true), new Row("Foraging Fortune", "§6", FORTUNE, false),
                new Row("Fig Fortune", "§6", FORTUNE, false), new Row("Mangrove Fortune", "§6", FORTUNE, false),
                new Row("Helix Fortune", "§6", FORTUNE, false), new Row("Timber", "§4", "⸸", true))),
        new Category(24, Items.FISHING_ROD, "§bFishing Stats",
            List.of("§7Stats that influence what you catch", "§7and how quickly you catch it while", "§7fishing."),
            List.of(new Row("Fishing Speed", "§b", "☂", false), new Row("Sea Creature Chance", "§3", "α", true),
                new Row("Double Hook Chance", "§9", "⚓", true), new Row("Trophy Chance", "§6", "♔", true),
                new Row("Treasure Chance", "§6", "⛃", true))),
        new Category(MISC, Items.CLOCK, "§dMiscellaneous Stats",
            List.of("§7Stats that augment various aspects", "§7of your gameplay."),
            List.of(new Row("Speed", "§f", "✦", false), new Row("Magic Find", "§b", "✯", false),
                new Row("Pet Luck", "§d", "♣", false), new Row("Heat Resistance", "§c", "♨", false),
                new Row("Cold Resistance", "§b", "❄", false), new Row("Respiration", "§3", "⚶", false),
                new Row("Pressure Resistance", "§9", "❍", false), new Row("Fear", "§5", "☠", false),
                new Row("Tracking", "§d", "⌖", false))),
        new Category(32, Items.LEAD, "§eHunting Stats",
            List.of("§7Stats that influence how quickly you", "§7hunt mobs and how many shards you", "§7get for doing so."),
            List.of(new Row("Pull", "§b", "⇲", false), new Row("Hunting Fortune", "§d", FORTUNE, false),
                new Row("Charm Chance", "§b", "❣", true))),
        new Category(34, Items.BOOK, "§3Wisdom Stats",
            List.of("§7Stats that influence how much §3Skill", "§3XP §7you gain."),
            wisdomRows()));

    /** The SkyBlock Menu head's seven stats, in Hypixel's order (menus-skyblock.md A §1). */
    private static final List<String> HEAD_STATS = List.of("Speed", "Strength", "Defense", "Crit Damage", "Crit Chance",
        "Health", "Intelligence");

    private static List<Row> farmingRows() {
        List<Row> rows = new ArrayList<>(List.of(new Row("Bonus Pest Chance", "§2", "ൠ", true),
            new Row("Overbloom", "§e", "✿", false), new Row("Farming Fortune", "§6", FORTUNE, false)));
        for (String crop : new String[] {"Wheat", "Carrot", "Potato", "Pumpkin", "Sugar Cane", "Melon Slice", "Cactus",
            "Cocoa Beans", "Mushroom", "Nether Wart", "Sunflower", "Moonflower", "Wild Rose"}) {
            rows.add(new Row(crop + " Fortune", "§6", FORTUNE, false));
        }
        return List.copyOf(rows);
    }

    private static List<Row> wisdomRows() {
        List<Row> rows = new ArrayList<>();
        for (String skill : new String[] {"Combat", "Farming", "Fishing", "Mining", "Foraging", "Enchanting", "Alchemy",
            "Carpentry", "Runecrafting", "Taming", "Social", "Hunting"}) {
            rows.add(new Row(skill + " Wisdom", "§3", "☯", false));
        }
        return List.copyOf(rows);
    }

    private enum Page { MAIN, BREAKDOWN, STAT, PARTS }

    /** What the window answers: Hypixel's reply to a swap is a sound and this new window (stats.md §2, slots 11/28/54-89). */
    private enum Answer { OPEN, EQUIP, ARMOR_OFF, EQUIPMENT_OFF }

    /** A stat's three kinds of bonus. */
    private enum Kind {
        FLAT("Flat Bonuses"), ADDITIVE("Additive Buffs"), MULTIPLIED("Multiplicative Buffs");

        final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    private final Page page;
    private final boolean fromMenu;
    /** BREAKDOWN: the Combat (true) or Miscellaneous page. */
    private final boolean combat;
    private final Stat stat;
    private final Kind kind;
    private final Answer answer;
    /** A swap was made here and the new window is on its way: this one takes no more clicks. */
    private boolean replaced;

    static void open(ServerPlayer player) {
        open(player, false);
    }

    /** The first page, next tick; [fromMenu]: it has a Go Back to the SkyBlock Menu (filler at 48 from /stats). */
    static void open(ServerPlayer player, boolean fromMenu) {
        open(player, Page.MAIN, fromMenu, true, null, null, TITLE, Answer.OPEN);
    }

    private static void open(ServerPlayer player, Page page, boolean fromMenu, boolean combat, Stat stat, Kind kind, String title) {
        open(player, page, fromMenu, combat, stat, kind, title, Answer.OPEN);
    }

    private static void open(ServerPlayer player, Page page, boolean fromMenu, boolean combat, Stat stat, Kind kind, String title,
                             Answer answer) {
        ChestGui.show(player, title, (id, inventory, p) -> new StatsMenu(id, inventory, player, page, fromMenu, combat, stat, kind, answer));
    }

    private StatsMenu(int id, Inventory inventory, ServerPlayer player, Page page, boolean fromMenu, boolean combat, Stat stat, Kind kind,
                      Answer answer) {
        super(id, inventory, player, new SimpleContainer(SIZE));
        this.page = page;
        this.fromMenu = fromMenu;
        this.combat = combat;
        this.stat = stat;
        this.kind = kind;
        this.answer = answer;
        fill();
    }

    @Override
    void fill() {
        if (replaced) {
            return;
        }
        StatSheet s = PlayerStats.of(player);
        switch (page) {
            case MAIN -> fillMain(s);
            case BREAKDOWN -> fillBreakdown(s);
            case STAT -> fillStat(s);
            case PARTS -> fillParts(s);
        }
    }

    /**
     * The open tick's sound: a swap's answer instead of the menu's own when this window answers one.
     * Hypixel: putting on, `block.lever.click` BLOCKS 0.55/2.0; taking off armour `entity.horse.armor`
     * 1.0/1.0, equipment `item.armor.equip_chain` 1.0/1.0 (stats.md §2), sent with the new window
     * (0-2 ticks after the click; here +1 with ChestGui.show).
     */
    @Override
    void openSound() {
        switch (answer) {
            // OPEN: whether Hypixel's re-opened window also plays the menus' lever 0.5/1.0 open sound on top of the answer.
            case OPEN -> super.openSound();
            case EQUIP -> sound(SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.55f, 2f);
            // OPEN: the take-off sounds' source (not decoded), and whether every armour piece plays horse.armor and every
            // equipment piece equip_chain (one recording each: a helmet, a belt).
            case ARMOR_OFF -> sound(SoundEvents.HORSE_ARMOR, SoundSource.PLAYERS, 1f, 1f);
            case EQUIPMENT_OFF -> sound(SoundEvents.ARMOR_EQUIP_CHAIN, SoundSource.PLAYERS, 1f, 1f);
        }
    }

    // ---------------------------------------------------------------- Stats & Equipment

    private void fillMain(StatSheet s) {
        frame(fromMenu ? SkyblockMenu.TITLE : null);
        ItemStack held = player.getMainHandItem();
        set(HELD, held.isEmpty() || SkyblockMenu.opensMenu(player) ? empty("§7Empty Hand Slot") : held.copy());
        Equipment.Slot[] slots = Equipment.Slot.values();
        for (int i = 0; i < 4; i++) {
            ItemStack worn = Equipment.get(player, slots[i]);
            // OPEN: only the belt's empty slot was recorded (` §8> Belt`); the others use Equipment.Slot's title.
            set(EQUIPMENT[i], worn.isEmpty() ? empty("§7Empty Equipment Slot", " §8> " + slots[i].title) : worn.copy());
            ItemStack armor = player.getItemBySlot(ARMOR_SLOTS[i]);
            set(ARMOR[i], armor.isEmpty() ? empty("§7Empty " + ARMOR_NAMES[i] + " Slot") : armor.copy());
        }
        for (Category category : CATEGORIES) {
            set(category.slot(), category(s, category));
        }
        Pets.Pet pet = Pets.active(player);
        // OPEN: the empty pet slot was never recorded (47 was always a pet); this one is Rustclear's.
        set(PET, pet == null
            ? EngineItems.named(Items.STAINED_GLASS_PANE.lightGray(), "§7Empty Pet Slot", "§7You don't have a pet summoned.", "",
                "§eClick to view your pets!")
            : Pets.stack(pet.type(), pet.rarity(), pet.level()));
        set(50, plain(Items.POTION, "§aActive Effects", "§7View and manage all of your active", "§7potion effects and buffs.", "",
            "§7Drink Potions or splash them on the", "§7ground to buff yourself!", "",
            "§7Currently Active: §e" + (EngineServer.runOf(player) instanceof DungeonRun ? 1 : 0), "",
            "§8Also accessible via /effects.", "", "§eClick to view!"));
        // OPEN: Rustclear has no achievement data; Hypixel's totals (336 achievements, 3,120 points) with nothing unlocked.
        set(51, plain(Items.DIAMOND, "§aSkyBlock Achievements", "§7View the available §eHypixel", "§7achievements for SkyBlock.", "",
            "§7These achievements reward", "§eAchievement Points§7, which let you", "§7unlock rewards on the Hypixel", "§7Network.", "",
            "§7Unlocked: §b0§7/§b336 §8(0%)", "§7Points: §e0§7/§e3,120 §8(0%)", "", "§7Legacy Unlocked: §b0", "§7Legacy Points: §e0", "",
            "§eClick to view achievements!"));
    }

    /** Hypixel's empty slot: a light gray pane with the slot's name (and the equipment's kind). */
    private static ItemStack empty(String name, String... lore) {
        return EngineItems.named(Items.STAINED_GLASS_PANE.lightGray(), name, lore);
    }

    /** A category button: what it is about, then every stat in it with its value. */
    private static ItemStack category(StatSheet s, Category category) {
        List<String> lore = new ArrayList<>(category.about());
        lore.add("");
        for (Row row : category.rows()) {
            lore.add(line(s, row));
        }
        lore.add("");
        lore.add("§eClick for details!");
        return plain(category.icon(), category.name(), lore.toArray(String[]::new));
    }

    private static Category category(int slot) {
        for (Category category : CATEGORIES) {
            if (category.slot() == slot) {
                return category;
            }
        }
        return null;
    }

    private static Row row(String label) {
        for (Category category : CATEGORIES) {
            for (Row row : category.rows()) {
                if (row.label().equals(label)) {
                    return row;
                }
            }
        }
        throw new IllegalArgumentException(label);
    }

    /**
     * ` §c❤ Health §f13,576.54`: no `§f` before a white stat's value (Speed, True Defense), and
     * ` §7§m∮ Sweep 0` for a stat that doesn't apply.
     */
    private static String line(StatSheet s, Row row) {
        Stat stat = row.stat();
        String color = stat == null ? row.color() : stat.color;
        String glyph = stat == null ? row.glyph() : stat.symbol;
        String value = value(stat == null ? 0 : s.get(stat)) + (row.percent() ? "%" : "");
        if (row.struck()) {
            return " §7§m" + glyph + " " + row.label() + " " + value;
        }
        return " " + color + glyph + " " + row.label() + " " + (color.equals("§f") ? "" : "§f") + value;
    }

    /** 13,576.54 or 111.1 or 0: thousands separated, at most two decimals. */
    static String value(double value) {
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(RoundingMode.HALF_UP);
        String text = format.format(value);
        return text.equals("-0") ? "0" : text;
    }

    /** The stats a category's breakdown page lists: those of its rows that the Rustclear world has. */
    private static List<Stat> stats(boolean combat) {
        List<Stat> out = new ArrayList<>();
        for (Row row : category(combat ? COMBAT : MISC).rows()) {
            if (row.stat() != null) {
                out.add(row.stat());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Your Stats Breakdown

    private void fillBreakdown(StatSheet s) {
        frame(TITLE);
        set(4, category(s, category(combat ? COMBAT : MISC)));
        List<Stat> stats = stats(combat);
        for (int i = 0; i < stats.size() && i < GRID.length; i++) {
            set(GRID[i], statItem(s, stats.get(i), true));
        }
    }

    /** A stat with its value, what it does, its flat, additive and multiplied parts, and what it adds up to. */
    private static ItemStack statItem(StatSheet s, Stat stat, boolean click) {
        List<String> lore = new ArrayList<>();
        for (String line : wrap(describe(stat), 38)) {
            lore.add("§7" + line);
        }
        lore.add("");
        lore.add("§7Flat: " + stat.color + "+" + stat.format(s.flat(stat)) + (stat.percent ? "" : stat.symbol));
        if (s.additive(stat) != 0) {
            lore.add("§7Additive: " + stat.color + "+" + Stat.number(s.additive(stat) * 100) + "%");
        }
        if (s.multiplier(stat) != 1) {
            lore.add("§7Multiplicative: " + stat.color + "x" + String.format(Locale.ROOT, "%.2f", s.multiplier(stat)));
        }
        List<String> extra = extra(s, stat);
        if (!extra.isEmpty()) {
            lore.add("");
            lore.addAll(extra);
        }
        if (click) {
            lore.add("");
            lore.add("§eClick to view!");
        }
        return plain(icon(stat), stat.color + stat.symbol + " " + stat.label + " §f" + stat.format(s.get(stat)), lore.toArray(String[]::new));
    }

    /** What a stat comes to: Defense's damage reduction and Effective Health... */
    private static List<String> extra(StatSheet s, Stat stat) {
        return switch (stat) {
            case DEFENSE -> List.of("§7Damage Reduction: §a" + Stat.number((1 - CombatMath.taken(s.get(Stat.DEFENSE))) * 100) + "%",
                "§7Effective Health: §c" + Stat.number(CombatMath.ehp(s)) + "❤");
            case TRUE_DEFENSE -> List.of("§7True Damage Reduction: §a" + Stat.number((1 - CombatMath.taken(s.get(Stat.TRUE_DEFENSE))) * 100) + "%",
                "§7True Effective Health: §c" + Stat.number(CombatMath.trueEhp(s)) + "❤");
            case INTELLIGENCE -> List.of("§7Mana: §b" + Stat.number(CombatMath.maxMana(s)) + "✎",
                "§7Regenerates §b" + Stat.number(CombatMath.manaRegenPerSecond(s)) + " §7a second.");
            case SPEED -> List.of("§7Cap: §f" + Stat.number(s.speedCap) + (s.uncappedSpeed() > s.speedCap
                ? " §8(" + Stat.number(s.uncappedSpeed()) + " before it)" : ""));
            case ATTACK_SPEED -> List.of("§7Terminator: §ea volley every " + CombatMath.shotTicks(s, 10) + " ticks");
            case FEROCITY -> List.of("§7Extra hits: §c" + Stat.number(s.get(Stat.FEROCITY) / 100));
            case HEALTH_REGEN -> List.of("§7Out of combat: §c+" + Stat.number(CombatMath.regenPerSecond(s)) + "❤ §7a second");
            default -> List.of();
        };
    }

    // ---------------------------------------------------------------- Stats ➜ Defense

    private void fillStat(StatSheet s) {
        frame(BREAKDOWN_TITLE);
        set(4, statItem(s, stat, false));
        set(FLAT, kindItem(s, Kind.FLAT));
        set(ADDITIVE, kindItem(s, Kind.ADDITIVE));
        set(MULTIPLIED, kindItem(s, Kind.MULTIPLIED));
    }

    /** A part of a stat: its label and its value, ready to list. */
    private record Line(String label, String value, String what) {}

    private List<Line> lines(StatSheet s, Kind kind) {
        List<Line> out = new ArrayList<>();
        switch (kind) {
            case FLAT -> s.parts(stat).forEach(p -> out.add(new Line(p.label(),
                (p.value() > 0 ? "+" : "") + stat.format(p.value()) + (stat.percent ? "" : stat.symbol), p.source().label)));
            case ADDITIVE -> s.percents(stat).forEach(p -> out.add(new Line(p.label(),
                (p.fraction() > 0 ? "+" : "") + Stat.number(p.fraction() * 100) + "%", "Buff")));
            case MULTIPLIED -> s.multipliers(stat).forEach(m -> out.add(new Line(m.label(),
                "x" + String.format(Locale.ROOT, "%.2f", m.factor()), "Multiplier")));
        }
        return out;
    }

    private ItemStack kindItem(StatSheet s, Kind kind) {
        List<String> lore = new ArrayList<>();
        lore.add("§8" + stat.label + " Stat");
        switch (kind) {
            case FLAT -> lore.addAll(List.of("§7All of these are summed together", "§7into a base amount."));
            case ADDITIVE -> lore.addAll(List.of("§7These stat bonuses are added up", "§7and then converted into the", "§7(additive) multiplier."));
            case MULTIPLIED -> lore.addAll(List.of("§7Each of these multiplies the stat", "§7on its own, one after the other."));
        }
        lore.add("");
        List<Line> lines = lines(s, kind);
        if (lines.isEmpty()) {
            lore.add("§8None.");
        }
        for (int i = 0; i < lines.size() && i < 7; i++) {
            lore.add(" " + stat.color + lines.get(i).value() + " §f" + lines.get(i).label());
        }
        if (lines.size() > 7) {
            lore.add(" §8And " + (lines.size() - 7) + " more...");
        }
        lore.add("");
        switch (kind) {
            case FLAT -> lore.add("§7Adds up to: " + stat.color + "+" + stat.format(s.flat(stat)) + (stat.percent ? "" : stat.symbol) + " " + stat.label);
            case ADDITIVE -> {
                lore.add("§7Adds up to: " + stat.color + "+" + Stat.number(s.additive(stat) * 100) + "%");
                lore.add("§7As multiplier: " + stat.color + String.format(Locale.ROOT, "%.2f", 1 + s.additive(stat)) + "x");
                lore.add("§8Multiplied with flat!");
            }
            case MULTIPLIED -> lore.add("§7Total: " + stat.color + "x" + String.format(Locale.ROOT, "%.2f", s.multiplier(stat)));
        }
        if (!lines.isEmpty()) {
            lore.add("");
            lore.add("§eClick to dig deeper!");
        }
        Item icon = switch (kind) {
            case FLAT -> Items.PAPER;
            case ADDITIVE -> Items.DYE.pick(net.minecraft.world.item.DyeColor.LIME);
            case MULTIPLIED -> Items.ENCHANTED_BOOK;
        };
        return plain(icon, stat.color + stat.symbol + " " + kind.title, lore.toArray(String[]::new));
    }

    // ---------------------------------------------------------------- Defense ➜ Additive Buffs

    private void fillParts(StatSheet s) {
        frame("Stats ➜ " + stat.label);
        List<Line> lines = lines(s, kind);
        for (int i = 0; i < lines.size() && i < GRID.length; i++) {
            Line line = lines.get(i);
            set(GRID[i], plain(partIcon(line.what()), stat.color + stat.symbol + " " + line.label(), "§8" + line.what(), "",
                "§7Value: " + stat.color + line.value()));
        }
    }

    private static Item partIcon(String what) {
        for (StatSheet.Source source : StatSheet.Source.values()) {
            if (source.label.equals(what)) {
                return switch (source) {
                    case BASE -> Items.PLAYER_HEAD;
                    case SKILLS -> Items.DIAMOND_SWORD;
                    case SLAYERS -> Items.ROTTEN_FLESH;
                    case ACCESSORIES -> Items.EMERALD;
                    case CLASS -> Items.BLAZE_ROD;
                    case ARMOR -> Items.IRON_CHESTPLATE;
                    case EQUIPMENT -> Items.LEATHER;
                    case HELD -> Items.IRON_SWORD;
                    case PET -> Items.BONE;
                    case BLESSINGS -> Items.BEACON;
                    case POTION -> Items.POTION;
                    case BUFFS -> Items.BLAZE_POWDER;
                    default -> Items.PAPER;
                };
            }
        }
        return what.equals("Multiplier") ? Items.ENCHANTED_BOOK : Items.DYE.pick(net.minecraft.world.item.DyeColor.LIME);
    }

    // ---------------------------------------------------------------- clicks

    @Override
    void click(int slot) {
        if (replaced) {
            slowDown();
            return;
        }
        switch (page) {
            case MAIN -> clickMain(slot);
            case BREAKDOWN -> {
                if (isBack(slot)) {
                    open(player, Page.MAIN, fromMenu, true, null, null, TITLE);
                    return;
                }
                List<Stat> stats = stats(combat);
                for (int i = 0; i < stats.size() && i < GRID.length; i++) {
                    if (slot == GRID[i]) {
                        open(player, Page.STAT, fromMenu, combat, stats.get(i), null, "Stats ➜ " + stats.get(i).label);
                    }
                }
            }
            case STAT -> {
                if (isBack(slot)) {
                    open(player, Page.BREAKDOWN, fromMenu, combat, null, null, BREAKDOWN_TITLE);
                    return;
                }
                Kind k = slot == FLAT ? Kind.FLAT : slot == ADDITIVE ? Kind.ADDITIVE : slot == MULTIPLIED ? Kind.MULTIPLIED : null;
                if (k != null && !lines(PlayerStats.of(player), k).isEmpty()) {
                    open(player, Page.PARTS, fromMenu, combat, stat, k, stat.label + " ➜ " + k.title);
                }
            }
            case PARTS -> {
                if (isBack(slot)) {
                    open(player, Page.STAT, fromMenu, combat, stat, null, "Stats ➜ " + stat.label);
                }
            }
        }
    }

    private void clickMain(int slot) {
        if (isBack(slot)) {
            SkyblockMenu.open(player);
            return;
        }
        if (slot == PET) {
            // OPEN: the pet slot's click answer was never recorded (stats.md Not recorded 7); Rustclear opens Pets.
            PetsMenu.open(player);
            return;
        }
        if (slot == COMBAT || slot == MISC) {
            // OPEN: Hypixel's category pages were never recorded; these are Rustclear's, and the other six categories
            // answer nothing.
            open(player, Page.BREAKDOWN, fromMenu, slot == COMBAT, null, null, BREAKDOWN_TITLE);
            return;
        }
        // OPEN: Active Effects (50) and SkyBlock Achievements (51) clicks were never recorded; they answer nothing.
        for (int i = 0; i < 4; i++) {
            if (slot == ARMOR[i]) {
                ItemStack worn = player.getItemBySlot(ARMOR_SLOTS[i]);
                if (worn.isEmpty()) {
                    return;
                }
                if (!player.getInventory().add(worn.copy())) {
                    inventoryFull();
                    return;
                }
                player.setItemSlot(ARMOR_SLOTS[i], ItemStack.EMPTY);
                reopen(Answer.ARMOR_OFF);
                return;
            }
            if (slot == EQUIPMENT[i]) {
                Equipment.Slot which = Equipment.Slot.values()[i];
                if (Equipment.get(player, which).isEmpty()) {
                    return;
                }
                if (player.getInventory().getFreeSlot() < 0) {
                    inventoryFull();
                    return;
                }
                player.getInventory().add(Equipment.unequip(player, which));
                reopen(Answer.EQUIPMENT_OFF);
                return;
            }
        }
    }

    /** Armour or equipment clicked in the inventory goes on, and what was worn there comes back in its place. */
    @Override
    void inventoryClick(int index) {
        if (page != Page.MAIN) {
            return;
        }
        if (replaced) {
            slowDown();
            return;
        }
        var inv = player.getInventory();
        ItemStack stack = inv.getItem(index);
        if (stack.isEmpty() || index == Kit.MAP_SLOT) {
            return;
        }
        ItemDefs.Instance item = PlayerStats.instance(stack);
        if (item != null && item.def().isArmor()) {
            EquipmentSlot slot = ARMOR_SLOTS[Kit.ARMOR.indexOf(KitItems.armorKey(item.def().type))];
            ItemStack was = player.getItemBySlot(slot);
            player.setItemSlot(slot, stack.copy());
            inv.setItem(index, was.copy());
            reopen(Answer.EQUIP);
            return;
        }
        ItemStack was = Equipment.equip(player, stack);
        if (was == null) {
            // OPEN: Hypixel's answer to a click on something that isn't armour or equipment was not decoded.
            player.sendSystemMessage(EngineItems.text("§cThat isn't armor or equipment."));
            return;
        }
        inv.setItem(index, was);
        reopen(Answer.EQUIP);
    }

    /** A swap's answer: a new Stats & Equipment window next tick with [answer]'s sound; this one is done. */
    private void reopen(Answer answer) {
        replaced = true;
        open(player, Page.MAIN, fromMenu, true, null, null, TITLE, answer);
    }

    /** Hypixel's answer to a click before the last swap's window came: no swap (5 windows in the recordings). */
    private void slowDown() {
        // OPEN: the message's colour, and Hypixel's real rate limit (here: any click on a window already answered).
        player.sendSystemMessage(EngineItems.text("§cWhow! Slow down there!"));
    }

    private void inventoryFull() {
        // OPEN: taking a piece off with a full inventory was never recorded; Rustclear's message.
        player.sendSystemMessage(EngineItems.text("§cYour inventory is full."));
    }

    // ---------------------------------------------------------------- items

    /** An icon without vanilla's attribute lines (a stone sword's "+5 Attack Damage"). */
    private static ItemStack plain(Item item, String name, String... lore) {
        ItemStack stack = EngineItems.named(item, name, lore);
        stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true)
            .withHidden(DataComponents.POTION_CONTENTS, true));
        return stack;
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            line.append(line.isEmpty() ? "" : " ").append(word);
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    /**
     * The SkyBlock Menu's slot 13 (menus-skyblock.md A §1): the player's head, `§aStats & Equipment`,
     * Speed, Strength, Defense, Crit Damage, Crit Chance, Health and Intelligence as the category
     * lore writes them, `§8and more...`, `§8Also accessible via /stats` and `§eClick to view!`.
     */
    static ItemStack menuHead(ServerPlayer player, StatSheet s) {
        List<String> lore = new ArrayList<>(List.of("§7View your equipment, stats,", "§7achievements, and more!", ""));
        for (String label : HEAD_STATS) {
            lore.add(line(s, row(label)));
        }
        lore.addAll(List.of(" §8and more...", "", "§8Also accessible via /stats", "", "§eClick to view!"));
        return EngineItems.playerHead(player.getGameProfile(), "§aStats & Equipment", lore.toArray(String[]::new));
    }

    /** The player's head with their main stats, class and level, named [name] with [after] at the end (Rustclear's own). */
    static ItemStack profileHead(ServerPlayer player, StatSheet s, String name, String... after) {
        List<String> lore = new ArrayList<>();
        for (Stat stat : new Stat[] {Stat.HEALTH, Stat.DEFENSE, Stat.STRENGTH, Stat.INTELLIGENCE, Stat.CRIT_CHANCE,
            Stat.CRIT_DAMAGE, Stat.ATTACK_SPEED, Stat.ABILITY_DAMAGE, Stat.FEROCITY, Stat.SPEED}) {
            lore.add(" " + stat.title() + " §f" + stat.format(s.get(stat)));
        }
        if (s.dungeonClass != null) {
            lore.add("");
            lore.add("§7Class: " + s.dungeonClass.color + s.dungeonClass.title + " " + s.classLevel + (s.soloClass ? " §6(solo: doubled)" : ""));
            lore.add("§7Catacombs: §c" + s.cata);
        }
        int level = PlayerStats.profile(player).level;
        if (level > 0) {
            lore.add("§7SkyBlock Level: §b" + level);
        }
        if (after.length > 0) {
            lore.add("");
            lore.addAll(List.of(after));
        }
        return EngineItems.playerHead(player.getGameProfile(), name, lore.toArray(String[]::new));
    }

    private static Item icon(Stat stat) {
        return switch (stat) {
            case HEALTH -> Items.GOLDEN_APPLE;
            case DEFENSE -> Items.IRON_CHESTPLATE;
            case TRUE_DEFENSE -> Items.BONE_MEAL;
            case STRENGTH -> Items.BLAZE_POWDER;
            case INTELLIGENCE -> Items.ENCHANTED_BOOK;
            case CRIT_CHANCE -> Items.SUGAR_CANE;
            case CRIT_DAMAGE -> Items.WITHER_SKELETON_SKULL;
            case ATTACK_SPEED -> Items.GOLDEN_AXE;
            case ABILITY_DAMAGE -> Items.BEACON;
            case FEROCITY -> Items.DYE.pick(net.minecraft.world.item.DyeColor.RED);
            case SPEED -> Items.SUGAR;
            case VITALITY -> Items.GLISTERING_MELON_SLICE;
            case MENDING -> Items.GHAST_TEAR;
            case HEALTH_REGEN -> Items.APPLE;
            case MAGIC_FIND -> Items.STICK;
            case SWING_RANGE -> Items.STONE_SWORD;
            // Stats Stat.java gains later (Pet Luck...).
            default -> Items.PAPER;
        };
    }

    private static String describe(Stat stat) {
        return switch (stat) {
            case HEALTH -> "Your Health stat increases your maximum health.";
            case DEFENSE -> "Your Defense stat reduces the damage that you take from enemies.";
            case TRUE_DEFENSE -> "Your True Defense stat reduces the true damage that you take from enemies.";
            case STRENGTH -> "Strength increases your base melee damage, including punching and weapons.";
            case INTELLIGENCE -> "Intelligence increases both your Mana Pool and the damage of your magical items.";
            case CRIT_CHANCE -> "Crit Chance is your chance to deal extra damage on enemies.";
            case CRIT_DAMAGE -> "Crit Damage is the amount of extra damage you deal when landing a critical hit.";
            case ATTACK_SPEED -> "Attack Speed decreases the time between hits on your weapon.";
            case ABILITY_DAMAGE -> "Ability Damage increases the damage of your abilities.";
            case FEROCITY -> "Ferocity grants percent chance to double-strike enemies. Each 100 is one more strike.";
            case SPEED -> "Your Speed stat increases how fast you can walk.";
            case VITALITY -> "Vitality increases the healing you receive.";
            case MENDING -> "Mending increases the healing you give to others.";
            case HEALTH_REGEN -> "Health Regen increases the amount of health you naturally regenerate.";
            case MAGIC_FIND -> "Magic Find increases how many rare items you find.";
            case SWING_RANGE -> "Swing Range increases how far away you can hit enemies with melee.";
            default -> stat.label + ".";
        };
    }
}
