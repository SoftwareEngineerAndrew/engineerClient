package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Your Skills and the Dungeoneering pages under it (menus-skyblock.md §2): Your Skills, Dungeoneering,
 * Catacombs Rewards, Catacombs RNG Meters and Catacombs Profile, laid out as Hypixel's with the
 * player's levels and XP (their Hypixel profile, or the party's levels). What Rustclear can't know
 * (a level's coin and SkyBlock XP rewards, RNG meter scores, floor records) is left out or 0.
 */
final class SkillMenus {
    private SkillMenus() {
    }

    static final String SKILLS = "Your Skills";
    static final String DUNGEONEERING = "Dungeoneering";
    static final String REWARDS = "Catacombs Rewards";
    static final String RNG = "Catacombs RNG Meters";
    static final String PROFILE = "Catacombs Profile";

    /** One skill on Your Skills: its slot, icon, name, profile key, max level and description (recorded text). */
    private record Skill(int slot, Item icon, String name, String key, int max, String... about) {}

    private static final Skill[] ALL = {
        new Skill(19, Items.STONE_SWORD, "Combat", "combat", 60, "§7Fight mobs and special bosses to", "§7earn Combat EXP!"),
        new Skill(20, Items.GOLDEN_HOE, "Farming", "farming", 60, "§7Harvest crops and shear sheep to", "§7earn Farming EXP!"),
        new Skill(21, Items.FISHING_ROD, "Fishing", "fishing", 50, "§7Visit your local pond to fish and", "§7earn Fishing EXP!"),
        new Skill(22, Items.STONE_PICKAXE, "Mining", "mining", 60, "§7Dive into deep caves and find rare",
            "§7ores and valuable materials to earn", "§7Mining EXP!"),
        new Skill(23, Items.JUNGLE_SAPLING, "Foraging", "foraging", 54, "§7Cut trees and forage for other",
            "§7plants to earn Foraging EXP!"),
        new Skill(24, Items.ENCHANTING_TABLE, "Enchanting", "enchanting", 60, "§7Enchant items to earn Enchanting EXP!"),
        new Skill(25, Items.BREWING_STAND, "Alchemy", "alchemy", 50, "§7Brew potions to earn Alchemy EXP!"),
        new Skill(28, Items.CRAFTING_TABLE, "Carpentry", "carpentry", 50, "§7Craft items to earn Carpentry EXP!"),
        new Skill(29, Items.MAGMA_CREAM, "Runecrafting", "runecrafting", 25, "§7Slay bosses and runic mobs, and",
            "§7fuse runes to earn Runecrafting EXP!"),
        new Skill(30, Items.POLAR_BEAR_SPAWN_EGG, "Taming", "taming", 60, "§7Level up pets to earn Taming EXP!"),
        new Skill(32, Items.EMERALD, "Social", "social", 25, "§7Gain Social EXP for every new unique",
            "§7guest, hosting guests, and visiting", "§7islands!"),
        // OPEN: Hunting's max level (26 recorded, using the skill XP table).
        new Skill(33, Items.LEAD, "Hunting", "hunting", 60, "§7Hunt various monsters to earn", "§7Hunting EXP!"),
    };

    /** The ten skills Hypixel's "Skill Avg. (non-cosmetic)" averages (44.1 recorded = these ten's mean). */
    private static final List<String> AVERAGED = List.of("combat", "farming", "fishing", "mining", "foraging", "enchanting",
        "alchemy", "carpentry", "taming", "hunting");

    static double average(SkyblockProfile profile) {
        double sum = 0;
        for (Skill s : ALL) {
            if (AVERAGED.contains(s.key)) {
                sum += profile.skillLevel(s.key, s.max);
            }
        }
        return Math.floor(sum / AVERAGED.size() * 10) / 10;
    }

    static void openSkills(ServerPlayer player) {
        ChestGui.show(player, SKILLS, (id, inventory, p) -> new YourSkills(id, inventory, player));
    }

    /** "§7Progress to Level <next>: §e<pct>%" and the bar, from [xp] (0: not read) at [level] in [table]. */
    private static List<String> progress(String label, double xp, long[] table, int level) {
        double[] p = SkyblockProfile.progress(xp, table, level);
        double pct = p[1] <= 0 ? 0 : p[0] / p[1] * 100;
        return List.of("§7" + label + ": §e" + MenuText.percent(pct) + "%", MenuText.bar(p[0], p[1], "§2"));
    }

    private static double total(long[] table, int levels) {
        double sum = 0;
        for (int i = 0; i < Math.min(levels, table.length); i++) {
            sum += table[i];
        }
        return sum;
    }

    // ---------------------------------------------------------------- Your Skills

    static final class YourSkills extends ChestGui {
        YourSkills(int id, Inventory inventory, ServerPlayer player) {
            super(id, inventory, player, new SimpleContainer(SIZE));
            fill();
        }

        @Override
        void fill() {
            frame(SkyblockMenu.TITLE);
            SkyblockProfile profile = PlayerStats.profile(player);
            set(4, EngineItems.named(Items.DIAMOND_SWORD, "§aYour Skills", "§7View your Skill progression and", "§7rewards.", "",
                "§6" + MenuText.decimal(average(profile)) + " Skill Avg. §8(non-cosmetic)", "", "§8Also accessible via /skills."));
            for (Skill s : ALL) {
                set(s.slot, skill(profile, s));
            }
            set(34, MenuText.head(MenuText.DUNGEONEERING, "§aDungeoneering", "§7Complete Dungeons to level up your",
                "§7classes! Unlock new gear and class", "§7upgrades by completing higher tier", "§7dungeons!", "",
                "§7Requires §bCombat Level 15 §7to enter a", "§7Dungeon.", "", "§eClick to view!"));
            set(53, EngineItems.named(Items.OAK_SIGN, "§aShow Skill Rankings", "§7Show the rankings display for your",
                "§7Skills.", "", "§eClick to show!"));
        }

        private static ItemStack skill(SkyblockProfile profile, Skill s) {
            int level = profile.skillLevel(s.key, s.max);
            Double xp = profile.skillXp == null ? null : profile.skillXp.get(s.key);
            boolean ownTable = !s.key.equals("runecrafting") && !s.key.equals("social");
            List<String> lore = new ArrayList<>(List.of(s.about));
            lore.add("");
            if (level >= s.max) {
                lore.add("§8Max Skill level reached!");
                if (ownTable || xp != null) {
                    double shown = xp != null ? xp : total(SkyblockProfile.SKILL_XP, s.max);
                    lore.add("§e§l§m" + " ".repeat(20) + "§r §6" + MenuText.decimal(shown));
                }
                lore.add("");
            } else {
                // OPEN: a level's rewards (perk, stat, coins, SkyBlock XP) are only recorded for a few levels; left out.
                lore.addAll(progress("Progress to Level " + MenuText.roman(level + 1), xp == null ? 0 : xp,
                    SkyblockProfile.SKILL_XP, level));
                lore.add("");
                int cap = s.key.equals("farming") ? profile.farmingCap : s.key.equals("taming") ? profile.tamingCap : 0;
                if (cap > 0 && level >= cap) {
                    if (s.key.equals("farming")) {
                        lore.addAll(List.of("§6Increase your Farming level cap by", "§6visiting Jacob and Anita in the Hub or",
                            "§6Garden!", ""));
                    } else {
                        lore.addAll(List.of("§6Increase your Taming level cap by", "§6visiting George at §bPet Care§6!", ""));
                    }
                }
            }
            if (s.key.equals("runecrafting")) {
                lore.addAll(List.of("§dLevel up Runecrafting to activate", "§dthe effects of rune-bearing items!", ""));
            }
            lore.add("§eClick to view!");
            return EngineItems.named(s.icon, "§a" + s.name + " " + MenuText.roman(level), lore.toArray(String[]::new));
        }

        @Override
        void click(int slot) {
            if (isBack(slot)) {
                SkyblockMenu.open(player);
            } else if (slot == 34) {
                ChestGui.show(player, DUNGEONEERING, (id, inventory, p) -> new Dungeoneering(id, inventory, player));
            }
            // OPEN: the skill pages (Combat Skill, Hunting Skill...) aren't in Rustclear.
        }
    }

    // ---------------------------------------------------------------- Dungeoneering

    static final class Dungeoneering extends ChestGui {
        Dungeoneering(int id, Inventory inventory, ServerPlayer player) {
            super(id, inventory, player, new SimpleContainer(SIZE));
            fill();
        }

        @Override
        void fill() {
            frame(SKILLS);
            SkyblockProfile profile = PlayerStats.profile(player);
            set(12, catacombs(profile, true));
            set(13, EngineItems.named(Items.STAINED_GLASS_PANE.red(), "§cNot Coming Soon"));
            set(14, EngineItems.named(Items.STAINED_GLASS_PANE.red(), "§cNot Coming Soon"));
            set(29, dungeonClass(profile, DungeonClass.HEALER, Items.SPLASH_POTION));
            set(30, dungeonClass(profile, DungeonClass.MAGE, Items.BLAZE_ROD));
            set(31, dungeonClass(profile, DungeonClass.BERSERK, Items.IRON_SWORD));
            set(32, dungeonClass(profile, DungeonClass.ARCHER, Items.BOW));
            set(33, dungeonClass(profile, DungeonClass.TANK, Items.LEATHER_CHESTPLATE));
            set(50, MenuText.head(MenuText.ESSENCE, "§aYour Essence", "§7Collect Essence in Dungeons which",
                "§7can be used to upgrade gear and", "§7purchase perks and upgrades!", "", "§eClick to view!"));
        }

        @Override
        void click(int slot) {
            if (isBack(slot)) {
                openSkills(player);
            } else if (slot == 12) {
                ChestGui.show(player, REWARDS, (id, inventory, p) -> new Rewards(id, inventory, player));
            }
        }
    }

    /** The Catacombs head: level (with ✯ from 50), progress to the next mastery, the stat bonus. */
    static ItemStack catacombs(SkyblockProfile profile, boolean click) {
        int level = SkyblockProfile.cataDisplayLevel(profile.cataXp, profile.cataLevel());
        List<String> lore = new ArrayList<>(List.of("§7Complete this dungeon to", "§7demonstrate your mastery, earning",
            "§7experience and unlocking new", "§7rewards!", ""));
        long[] table = cataTable(level);
        lore.addAll(progress("Progress to Mastery " + (level + 1), profile.cataXp, table, level));
        lore.add("");
        lore.addAll(List.of("§7Current Mastery Reward:", "§7Your Dungeon Items' stats are", "§7increased by §c"
            + ItemStats.cataBonus(level) + "%§7 while in §cThe", "§cCatacombs§7."));
        if (level >= 50) {
            lore.addAll(List.of("", "§8§oLevels above 50 are cosmetic and", "§8§odo not provide any bonus stats."));
        }
        if (click) {
            lore.addAll(List.of("", "§eClick to view!"));
        }
        return MenuText.head(MenuText.CATACOMBS, "§cThe Catacombs " + level + (level >= 50 ? " §6✯" : ""), lore.toArray(String[]::new));
    }

    /** The Catacombs XP table, long enough to reach past [level] (200M a level after 50). */
    private static long[] cataTable(int level) {
        long[] t = new long[Math.max(SkyblockProfile.CATA_XP.length, level + 1)];
        for (int i = 0; i < t.length; i++) {
            t[i] = i < SkyblockProfile.CATA_XP.length ? SkyblockProfile.CATA_XP[i] : SkyblockProfile.CATA_XP_PAST_50;
        }
        return t;
    }

    /** A class on Dungeoneering: its stat lines at the player's level, passives and abilities, progress. */
    private static ItemStack dungeonClass(SkyblockProfile profile, DungeonClass c, Item icon) {
        int level = profile.classLevel(c);
        DungeonClass.Bonus b = c.bonus(level, false);
        List<String> lore = new ArrayList<>();
        switch (c) {
            case HEALER -> lore.addAll(List.of("§7Vitality: §a+" + Stat.number(b.stat(Stat.VITALITY)),
                "§7Mending: §a+" + Stat.number(b.stat(Stat.MENDING))));
            case MAGE -> lore.addAll(List.of("§7Intelligence: §a+" + Stat.number(b.stat(Stat.INTELLIGENCE)),
                "§7Ability Damage: §a+" + Stat.number(b.stat(Stat.ABILITY_DAMAGE)) + "%"));
            case BERSERK -> lore.addAll(List.of("§7Melee Damage: §c+§c" + Stat.number(b.meleeAdditive()) + "%",
                "§7Walk Speed: §a+" + Stat.number(b.stat(Stat.SPEED))));
            case ARCHER -> lore.addAll(List.of("§7Arrow Damage: §c+§c" + Stat.number(b.arrowAdditive()) + "%",
                "§7Melee Damage: §c-§c" + Stat.number(-b.meleeAdditive()) + "%"));
            case TANK -> lore.addAll(List.of("§7Health: §a+" + Stat.number(b.stat(Stat.HEALTH)),
                "§7Defense: §a+" + Stat.number(b.stat(Stat.DEFENSE)), "§7Vitality: §a+" + Stat.number(b.stat(Stat.VITALITY))));
        }
        lore.add("");
        lore.add("§f§lClass Passives");
        for (String p : passives(c)) {
            lore.add("§8∙ §a" + p);
        }
        lore.add("");
        lore.add("§f§lDungeon Orb Abilities");
        lore.add("§8∙ §6" + c.ability);
        lore.add("§8∙ §6" + c.ultimate);
        lore.add("");
        lore.add("§f§lGhost Abilities");
        for (String g : ghost(c)) {
            lore.add("§8∙ §f" + g);
        }
        lore.add("");
        if (level >= DungeonClass.MAX_LEVEL) {
            lore.addAll(List.of("§b§oYou have reached the max level for", "§b§othis Class!"));
        } else {
            Double xp = profile.classXp == null ? null : profile.classXp.get(c.name().toLowerCase(java.util.Locale.ROOT));
            lore.addAll(progress("Progress to Level " + (level + 1), xp == null ? 0 : xp, SkyblockProfile.CATA_XP, level));
            lore.add("");
            lore.addAll(List.of("§8§oUse this class in dungeons to level it", "§8§oup!"));
        }
        lore.add("");
        lore.add("§eClick to view!");
        return EngineItems.named(icon, "§a" + c.title + " " + level + (level >= DungeonClass.MAX_LEVEL ? " §6✯" : ""),
            lore.toArray(String[]::new));
    }

    /** Each class's passives, as Dungeoneering lists them (recorded). */
    private static List<String> passives(DungeonClass c) {
        return switch (c) {
            case HEALER -> List.of("Renew", "Healing Aura", "Revive", "Orbies", "Soul Tether", "Overheal");
            case MAGE -> List.of("Mage Staff", "Efficient Spells");
            case BERSERK -> List.of("Bloodlust", "Lust for Blood", "Indomitable", "Weapon Master");
            case ARCHER -> List.of("Doubleshot", "Bone Plating", "Bouncy Arrows");
            case TANK -> List.of("Protective Barrier", "Taunt", "Diversion", "Defensive Stance");
        };
    }

    private static List<String> ghost(DungeonClass c) {
        return switch (c) {
            case HEALER -> List.of("Healing Potion", "Revive Self");
            case MAGE -> List.of("Pop-up Wall", "Fireball");
            case BERSERK -> List.of("Strength Potion", "Ghost Axe");
            case ARCHER -> List.of("Stun Bow", "Healing Bow");
            case TANK -> List.of("Stun Potion", "Absorption Potion");
        };
    }

    // ---------------------------------------------------------------- Catacombs Rewards

    /** The 24-cell track shared by the skill pages and Catacombs Rewards: down, up, down, up, down. */
    static final int[] TRACK = {0, 9, 18, 27, 28, 29, 20, 11, 2, 3, 4, 13, 22, 31, 32, 33, 24, 15, 6, 7, 8, 17, 26, 35};

    static final class Rewards extends ChestGui {
        private static final int MAX = 50;
        private final int level;
        /** The first level on the track; the default puts the level in progress 11th (Hypixel XXVI opened on 17-40). */
        private final int home;
        private int first;

        Rewards(int id, Inventory inventory, ServerPlayer player) {
            super(id, inventory, player, new SimpleContainer(SIZE));
            SkyblockProfile profile = PlayerStats.profile(player);
            level = SkyblockProfile.cataDisplayLevel(profile.cataXp, profile.cataLevel());
            home = clampFirst(level + 1 - 10);
            first = home;
            fill();
        }

        private static int clampFirst(int first) {
            return Math.max(1, Math.min(MAX - TRACK.length + 1, first));
        }

        @Override
        void fill() {
            frame(DUNGEONEERING);
            SkyblockProfile profile = PlayerStats.profile(player);
            for (int i = 0; i < TRACK.length; i++) {
                set(TRACK[i], cell(first + i, profile));
            }
            set(39, MenuText.head(MenuText.RNG_METERS, "§dCatacombs RNG Meters", "§7Your §dCatacombs RNG Meters §7fill with",
                "§bDungeon Score §7each time you", "§7complete an §bS §7or §b§lS+ §7run.", "", "§7Your selected §dRNG drop §7will be",
                "§7guaranteed to appear in a chest", "§7once your RNG Meter for a floor is", "§7full!", "", "§eClick to view!"));
            set(41, EngineItems.named(Items.OAK_SIGN, "§aCatacombs Profile", "§7View your statistics, best",
                "§7performances, and more for §cThe", "§cCatacombs§7!", "", "§eClick to view!"));
            String[] scroll = {"§eLeft-click to scroll!", "§eRight-click to scroll fast!"};
            if (first > 1) {
                set(45, EngineItems.named(Items.ARROW, "§aScroll Left", scroll));
            }
            if (first + TRACK.length - 1 < MAX) {
                set(53, EngineItems.named(Items.ARROW, "§aScroll Right", scroll));
            }
            if (first != home) {
                set(50, EngineItems.named(Items.NETHER_STAR, "§aRe-Center"));
            }
        }

        private ItemStack cell(int n, SkyblockProfile profile) {
            List<String> lore = new ArrayList<>(List.of("§7Rewards:", " §8+§a2 §c❤ Health", " §8+§b" + (n < 40 ? 20 : 40) + " SkyBlock XP",
                " §7Increases the base stats of your", " §7dungeon items and healing in",
                " §7dungeons by §8" + (n == 1 ? 0 : ItemStats.cataBonus(n - 1)) + "➜§c" + ItemStats.cataBonus(n) + "%§7 while in",
                " §cThe Catacombs§7.", ""));
            boolean milestone = n % 5 == 0;
            String name = "Catacombs Mastery " + n;
            ItemStack stack;
            if (n <= level) {
                lore.add("§a§lUNLOCKED");
                stack = milestone ? MenuText.head(MenuText.CATACOMBS, "§a" + name, lore.toArray(String[]::new))
                    : EngineItems.named(Items.STAINED_GLASS_PANE.lime(), "§a" + name, lore.toArray(String[]::new));
            } else if (n == level + 1) {
                double[] p = SkyblockProfile.progress(profile.cataXp, SkyblockProfile.CATA_XP, level);
                lore.add("§7Progress: §e" + MenuText.percent(p[1] <= 0 ? 0 : p[0] / p[1] * 100) + "%");
                lore.add(MenuText.bar(p[0], p[1], "§2"));
                stack = milestone ? MenuText.head(MenuText.CATACOMBS, "§e" + name, lore.toArray(String[]::new))
                    : EngineItems.named(Items.STAINED_GLASS_PANE.yellow(), "§e" + name, lore.toArray(String[]::new));
            } else {
                lore.removeLast();
                stack = milestone ? MenuText.head(MenuText.CATACOMBS, "§c" + name, lore.toArray(String[]::new))
                    : EngineItems.named(Items.STAINED_GLASS_PANE.red(), "§c" + name, lore.toArray(String[]::new));
            }
            stack.setCount(Math.min(99, n));
            return stack;
        }

        @Override
        void click(int slot, int button, boolean shift) {
            if (isBack(slot)) {
                ChestGui.show(player, DUNGEONEERING, (id, inventory, p) -> new Dungeoneering(id, inventory, player));
            } else if (slot == 39) {
                ChestGui.show(player, RNG, (id, inventory, p) -> new RngMeters(id, inventory, player));
            } else if (slot == 41) {
                ChestGui.show(player, PROFILE, (id, inventory, p) -> new CatacombsProfile(id, inventory, player));
            } else if (slot == 45 || slot == 53 || slot == 50 && first != home) {
                // Scrolling is answered in place. A right-click moves 10 (recorded); OPEN: a left-click's step (1 here).
                int step = button == 1 ? 10 : 1;
                first = slot == 50 ? home : clampFirst(first + (slot == 45 ? -step : step));
                fill();
            }
        }

        @Override
        void click(int slot) {
            click(slot, 0, false);
        }
    }

    // ---------------------------------------------------------------- RNG meters

    static final class RngMeters extends ChestGui {
        RngMeters(int id, Inventory inventory, ServerPlayer player) {
            super(id, inventory, player, new SimpleContainer(SIZE));
            fill();
        }

        @Override
        void fill() {
            frame(REWARDS);
            set(4, MenuText.head(MenuText.RNG_METERS, "§dCatacombs RNG Meters", "§7Your §dCatacombs RNG Meters §7fill with",
                "§bDungeon Score §7each time you", "§7complete an §bS §7or §b§lS+ §7run.", "", "§7Your selected §dRNG drop §7will be",
                "§7guaranteed to appear in a chest", "§7once your RNG Meter for a floor is", "§7full!"));
            for (int f = 1; f <= 7; f++) {
                set(18 + f, meter("F" + f, f == 7 ? null : MenuText.RNG[f - 1]));
                set(27 + f, meter("M" + f, MenuText.RNG[5 + f]));
            }
        }

        /** A floor's meter. OPEN: Rustclear keeps no meter, so every one shows no drop selected and no score. */
        private static ItemStack meter(String floor, String texture) {
            String[] lore = {"§7Your §dRNG Meter §7fills each time you", "§7complete an §b§lS+ §7run on §cCatacombs",
                "§c(" + floor + ") §7by your §bscore §7for that run.", "§7Completing §bS §7runs fills your meter",
                "§7with §a70% §7of your score.", "", "§7When your meter is full, your", "§7selected drop will be guaranteed to",
                "§7drop in a chest in your next eligible", "§7run!", "", "§cYou don't have an RNG drop", "§cselected. Choose one to start",
                "§cprogressing towards it!", "", "§7Stored Dungeon Score: §d0", "", "§eClick to view!"};
            String name = "§dCatacombs (" + floor + ") RNG Meter";
            return texture == null ? EngineItems.named(Items.WITHER_SKELETON_SKULL, name, lore) : MenuText.head(texture, name, lore);
        }

        @Override
        void click(int slot) {
            if (isBack(slot)) {
                ChestGui.show(player, REWARDS, (id, inventory, p) -> new Rewards(id, inventory, player));
            }
        }
    }

    // ---------------------------------------------------------------- Catacombs Profile

    /** Each floor's slot and boss (Entrance's first stat line is The Watcher's alone). */
    private static final int[] FLOOR_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22};
    private static final int[] MASTER_SLOTS = {23, 24, 29, 30, 31, 32, 33};
    private static final String[] BOSSES = {null, "Bonzo", "Scarf", "The Professor", "Thorn", "Livid", "Sadan", "Necron"};

    static final class CatacombsProfile extends ChestGui {
        private int ticks;
        private boolean loaded;

        CatacombsProfile(int id, Inventory inventory, ServerPlayer player) {
            super(id, inventory, player, new SimpleContainer(SIZE));
            fill();
        }

        @Override
        void fill() {
            frame(REWARDS);
            for (int f = 0; f < FLOOR_SLOTS.length; f++) {
                set(FLOOR_SLOTS[f], floor("§cThe Catacombs §8- §e" + (f == 0 ? "Entrance" : "Floor " + MenuText.roman(f)),
                    MenuText.FLOORS[f], BOSSES[f]));
            }
            for (int f = 1; f <= 7; f++) {
                set(MASTER_SLOTS[f - 1], floor("§cMaster Mode The Catacombs §8- §eFloor " + MenuText.roman(f),
                    MenuText.FLOORS[7 + f], BOSSES[f]));
            }
        }

        /**
         * A floor's head: "Loading stats..." at the open, its stats a tick later (Hypixel, 6 of 6). OPEN:
         * Rustclear keeps no floor records, so the stats are 0 and N/A.
         */
        private ItemStack floor(String name, String texture, String boss) {
            if (!loaded) {
                return MenuText.head(texture, name, "", "§7Loading stats...", "", "§eClick to view!");
            }
            List<String> lore = new ArrayList<>(List.of(""));
            if (boss != null) {
                lore.add("§7" + boss + " Kills: §a0");
            }
            lore.addAll(List.of("§7The Watcher Kills: §a0", "§7Fastest Time: §cN/A", "§7Fastest Time (S): §cN/A",
                "§7Fastest Time (S+): §cN/A", "§7Best Score: §cN/A", "§7Most Archer Damage: §cN/A", "§7Most Berserk Damage: §cN/A",
                "§7Most Healer Damage: §cN/A", "§7Most Mage Damage: §cN/A", "§7Most Tank Damage: §cN/A", "§7Most Ally Healing: §cN/A",
                "§7Total Enemies Killed: §a0", "§7Most Enemies Killed: §cN/A", "", "§8Rankings may take some time to refresh.", "",
                "§eClick to view!"));
            return MenuText.head(texture, name, lore.toArray(String[]::new));
        }

        @Override
        public void broadcastChanges() {
            if (!loaded && ++ticks >= 1) {
                loaded = true;
                fill();
            }
            super.broadcastChanges();
        }

        @Override
        void click(int slot) {
            if (isBack(slot)) {
                ChestGui.show(player, REWARDS, (id, inventory, p) -> new Rewards(id, inventory, player));
            }
        }
    }
}
