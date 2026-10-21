package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Your Bags and the bags it opens, and the Personal Bank (menus-skyblock.md §7, §8), laid out as
 * Hypixel's. Bag windows open the Hypixel way: the server closes the old window first, then the
 * bag opens with the saddle sound at a random pitch; a sack opened from Sack of Sacks doesn't close
 * it first. OPEN: Rustclear has no sack, potion bag, accessory bag, time pocket or bank contents,
 * so they open empty (a sack's items show "Empty sack!").
 */
final class BagMenus {
    private BagMenus() {
    }

    static final String BAGS = "Your Bags";
    static final String SACKS = "Sack of Sacks";
    static final String DUNGEON_SACK = "Dungeon Sack";
    static final String BANK = "Bank";

    static void openBags(ServerPlayer player) {
        ChestGui.show(player, BAGS, (id, inventory, p) -> new YourBags(id, inventory, player));
    }

    static void openBank(ServerPlayer player) {
        ChestGui.show(player, BANK, (id, inventory, p) -> new Bank(id, inventory, player));
    }

    /** A bag window: 1 tick later, the old window closed first, the saddle sound. */
    private static void openBag(ServerPlayer player, String title, net.minecraft.world.inventory.MenuConstructor menu) {
        ChestGui.show(player, title, menu, true);
    }

    // ---------------------------------------------------------------- Your Bags

    /** Your Bags as main has it since 10-06: five bags at 20-24 (the Fishing Bag is gone). */
    static final class YourBags extends ChestGui {
        YourBags(int id, Inventory inventory, ServerPlayer player) {
            super(id, inventory, player, new SimpleContainer(SIZE));
            fill();
        }

        @Override
        void fill() {
            frame(SkyblockMenu.TITLE);
            set(20, MenuText.head(MenuText.SACK_OF_SACKS, "§aSack of Sacks", "§7A sack which contains other sacks.",
                "§7Sackception!", "", "§8Also accessible via /sacks", "", "§eClick to open!"));
            set(21, MenuText.head(MenuText.POTION_BAG, "§aPotion Bag", "§7A handy bag for holding your", "§7Potions in.", "",
                "§8Also accessible via /potionbag", "", "§eClick to open!"));
            set(22, MenuText.head(MenuText.QUIVER, "§aQuiver", "§7A masterfully crafted Quiver which",
                "§7holds any kind of projectile you can", "§7think of!", "", "§8Also accessible via /quiver", "", "§eClick to open!"));
            set(23, accessoryBag());
            set(24, MenuText.head(MenuText.TIME_POCKET, "§aTime Pocket", "§7A bag which holds items that evolve",
                "§7over time. Time flows twice as fast in", "§7here!", "", "§8Also accessible via /timepocket", "", "§eClick to open!"));
        }

        /** The Accessory Bag button: Accessory Power, the selected power's stats, and the tuning. */
        private ItemStack accessoryBag() {
            SkyblockProfile profile = PlayerStats.profile(player);
            DungeonClass c = PlayerStats.classOf(player);
            // In a run the dungeon accessories count twice, and the lore shows it (a5fcd1: 1,743 for 1,597).
            boolean in = PlayerStats.inDungeon(player);
            List<String> lore = new ArrayList<>(List.of("§7A special bag which can hold", "§7Talismans, Rings, Artifacts, and Orbs",
                "§7within it. All will still work while in this", "§7bag!", "",
                String.format(Locale.ROOT, "§7Accessory Power: §6%,d", profile.accessoryPower(in)), ""));
            String power = profile.powerFor(c);
            lore.add("§7Selected Power: §a" + Character.toUpperCase(power.charAt(0)) + power.substring(1));
            for (Map.Entry<Stat, Double> e : profile.powerStats(c, in).entrySet()) {
                lore.add(e.getKey().color + "+" + MenuText.decimal2(e.getValue()) + e.getKey().symbol + " " + e.getKey().label);
            }
            Map<Stat, Double> unique = SkyblockProfile.uniqueBonus(power);
            if (!unique.isEmpty()) {
                lore.add("");
                lore.add("§7Unique Power Bonus:");
                unique.forEach((stat, v) -> lore.add(stat.color + "+" + MenuText.decimal2(v) + stat.symbol + " " + stat.label));
            }
            Map<Stat, Double> tuning = profile.tuningStats(in);
            if (!tuning.isEmpty()) {
                lore.add("");
                lore.add("§7Tuning:");
                tuning.forEach((stat, v) -> lore.add(stat.color + "+" + MenuText.decimal2(v) + stat.symbol + " " + stat.label));
            }
            lore.addAll(List.of("", "§8Also accessible via /accessorybag", "", "§eClick to open!"));
            return MenuText.head(MenuText.ACCESSORY_BAG, "§aAccessory Bag", lore.toArray(String[]::new));
        }

        @Override
        void click(int slot) {
            if (isBack(slot)) {
                SkyblockMenu.open(player);
            } else if (slot == 20) {
                openBag(player, SACKS, (id, inventory, p) -> new SackOfSacks(id, inventory, player));
            } else if (slot == 21) {
                openBag(player, "Potion Bag", (id, inventory, p) -> new PotionBag(id, inventory, player));
            } else if (slot == 23) {
                AccessoryBag.open(player, 1);
            } else if (slot == 24) {
                openBag(player, "Time Pocket", (id, inventory, p) -> new TimePocket(id, inventory, player));
            }
            // OPEN: the Quiver was never opened in the recordings.
        }
    }

    /** A bag or sack window: the saddle sound on open (bag and sack windows only). */
    abstract static class Bag extends ChestGui {
        private final boolean saddle;

        Bag(int id, Inventory inventory, ServerPlayer player, int rows, boolean saddle) {
            super(id, inventory, player, new SimpleContainer(rows * 9), rows);
            this.saddle = saddle;
        }

        @Override
        void openSound() {
            if (saddle) {
                saddleSound();
            }
        }
    }

    // ---------------------------------------------------------------- sacks

    static final class SackOfSacks extends Bag {
        SackOfSacks(int id, Inventory inventory, ServerPlayer player) {
            this(id, inventory, player, true);
        }

        SackOfSacks(int id, Inventory inventory, ServerPlayer player, boolean saddle) {
            super(id, inventory, player, 5, saddle);
            fill();
        }

        @Override
        void fill() {
            frame(BAGS);
            for (int i = 0; i < 35; i++) {
                set(i, ItemStack.EMPTY);
            }
            set(0, MenuText.head(MenuText.DUNGEON_SACK, "§6Dungeon Sack", "§7Holds items commonly found in §cThe",
                "§cCatacombs§7! Items you pickup go", "§7directly into your sacks.", "", "§7Capacity: §e20,160 of each item",
                "§8Multiple sacks sum their capacity!", "", "§eRight Click to open!", "", "§6§lLEGENDARY SACK"));
            set(41, EngineItems.named(Items.CHEST, "§aInsert inventory", "§7Inserts your inventory items into", "§7your sacks.", "",
                "§eClick to put items in!"));
        }

        /** Unused sack slots aren't sent when it opens; the resync sends them empty (recorded). */
        @Override
        boolean lateSlot(int slot) {
            return slot > 0 && slot < 35;
        }

        @Override
        void click(int slot) {
            if (isBack(slot)) {
                openBags(player);
            } else if (slot == 0) {
                ChestGui.show(player, DUNGEON_SACK, (id, inventory, p) -> new DungeonSack(id, inventory, player));
            }
        }
    }

    /** The Dungeon Sack's items in Hypixel's (alphabetical) order, with the item ids it shows them as. */
    private record SackItem(Item icon, String name) {}

    private static final SackItem[] DUNGEON_ITEMS = {
        new SackItem(Items.PAPER, "§9Ancient Rose"), new SackItem(Items.PAPER, "§5Architect's First Draft"),
        new SackItem(Items.LEAD, "§5Bigfoot's Bola"), new SackItem(Items.RED_MUSHROOM, "§9Bonzo Fragment"),
        new SackItem(Items.POLAR_BEAR_SPAWN_EGG, "§aDecoy"), new SackItem(Items.PAPER, "§9Dungeon Chest Key"),
        new SackItem(Items.PAPER, "§9Fel Pearl"), new SackItem(Items.ROTTEN_FLESH, "§9Healing Tissue"),
        new SackItem(Items.POLAR_BEAR_SPAWN_EGG, "§fInflatable Jerry"), new SackItem(Items.PAPER, "§5Jolly Pink Rock"),
        new SackItem(Items.FEATHER, "§9Kismet Feather"), new SackItem(Items.PAPER, "§5L.A.S.R.'s Eye"),
        new SackItem(Items.NETHER_BRICK, "§9Livid Fragment"), new SackItem(Items.PAPER, "§9Mimic Fragment"),
        new SackItem(Items.POPPY, "§9Scarf Fragment"), new SackItem(Items.ENDER_PEARL, "§9Spirit Leap"),
        // Hypixel draws Superboom TNT on paper with its own model; Rustclear's is TNT, as the kit's.
        new SackItem(Items.TNT, "§9Superboom TNT"), new SackItem(Items.GHAST_TEAR, "§9Thorn Fragment"),
        new SackItem(Items.HEAVY_WEIGHTED_PRESSURE_PLATE, "§aTrap"), new SackItem(Items.PAPER, "§5Wither Catalyst")};
    private static final int[] SACK_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33};

    static final class DungeonSack extends Bag {
        DungeonSack(int id, Inventory inventory, ServerPlayer player) {
            super(id, inventory, player, 5, true);
            fill();
        }

        @Override
        void fill() {
            frame(SACKS);
            for (int i = 0; i < DUNGEON_ITEMS.length; i++) {
                set(SACK_SLOTS[i], EngineItems.named(DUNGEON_ITEMS[i].icon(), DUNGEON_ITEMS[i].name(), "§8Dungeon Sack", "",
                    "§7Stored: §80§7/20.1k", "", "§8Empty sack!"));
            }
            set(41, EngineItems.named(Items.CAULDRON, "§aPickup All", "§7Attempts to pickup all items in this", "§7sack.", "",
                "§7Total items: §e0", "", "§eClick to pickup!"));
            set(42, EngineItems.named(Items.CHEST, "§aInsert inventory", "§7Inserts your inventory items into", "§7your sacks.", "",
                "§7Items: §e0", "", "§eClick to put items in!"));
        }

        @Override
        void click(int slot) {
            if (isBack(slot)) {
                // Back to Sack of Sacks: the sack is closed first and no sound plays (recorded 3 of 3).
                ChestGui.show(player, SACKS, (id, inventory, p) -> new SackOfSacks(id, inventory, player, false), true);
            }
        }
    }

    // ---------------------------------------------------------------- bags

    static final class PotionBag extends Bag {
        PotionBag(int id, Inventory inventory, ServerPlayer player) {
            super(id, inventory, player, 6, true);
            fill();
        }

        @Override
        void fill() {
            frame(BAGS);
            for (int i = 0; i < 45; i++) {
                set(i, ItemStack.EMPTY);
            }
        }

        @Override
        void click(int slot) {
            if (isBack(slot)) {
                openBags(player);
            }
        }
    }

    /**
     * The Accessory Bag: the max build's accessories (Accessories.maxBuild), 45 a page, as
     * "Accessory Bag (p/N)" (menus-skyblock.md §7.5): Previous Page 45 and Next Page 53, the last
     * page's unused slots not sent. A Hypixel profile's own accessories aren't read: its bag is empty.
     */
    static final class AccessoryBag extends Bag {
        private static final int PER_PAGE = 45;
        private final int page;
        private final List<Accessories.Member> shown;

        AccessoryBag(int id, Inventory inventory, ServerPlayer player, int page) {
            super(id, inventory, player, 6, true);
            this.shown = contents(player);
            this.page = page;
            fill();
        }

        static List<Accessories.Member> contents(ServerPlayer player) {
            if (!PlayerStats.profile(player).maxAccessories) {
                return List.of();
            }
            List<Accessories.Member> all = new ArrayList<>(Accessories.maxBuild());
            // Highest rarity first, then by name (OPEN: Hypixel keeps the player's own order).
            all.sort(java.util.Comparator.comparingInt((Accessories.Member m) -> -rarityRank(m.rarity()))
                .thenComparing(m -> m.def().name()));
            return all;
        }

        static int pages(ServerPlayer player) {
            return Math.max(1, (contents(player).size() + PER_PAGE - 1) / PER_PAGE);
        }

        static void open(ServerPlayer player, int page) {
            int n = pages(player);
            int p = Math.max(1, Math.min(n, page));
            openBag(player, "Accessory Bag (" + p + "/" + n + ")", (id, inventory, pl) -> new AccessoryBag(id, inventory, player, p));
        }

        private int pageCount() {
            return Math.max(1, (shown.size() + PER_PAGE - 1) / PER_PAGE);
        }

        @Override
        void fill() {
            frame(BAGS);
            for (int i = 0; i < PER_PAGE; i++) {
                int k = (page - 1) * PER_PAGE + i;
                set(i, k < shown.size() ? item(shown.get(k)) : ItemStack.EMPTY);
            }
            if (page > 1) {
                set(45, EngineItems.named(Items.ARROW, "§aPrevious Page", "§ePage " + (page - 1)));
            }
            if (page < pageCount()) {
                set(53, EngineItems.named(Items.ARROW, "§aNext Page", "§ePage " + (page + 1)));
            }
            set(50, EngineItems.named(Items.REDSTONE_TORCH, "§aNeed more room?", "§7You can expand your Accessory Bag",
                "§7by doing any of the following:", "", "§7Increasing your §aRedstone Collection§7.", "",
                "§7Purchasing slots for §6Coins §7from", "§6Jacobus §7in the §cCombat Settlement §7in", "§7the §bHub§7.", "",
                "§7Unlocking slots from §dElizabeth §7in the", "§bCommunity Center §7in the §bHub§7."));
        }

        /** The last page's unused slots aren't sent (recorded on page 3/3). */
        @Override
        boolean lateSlot(int slot) {
            return slot < PER_PAGE && (page - 1) * PER_PAGE + slot >= shown.size();
        }

        @Override
        void click(int slot) {
            if (isBack(slot)) {
                openBags(player);
            } else if (slot == 45 && page > 1) {
                open(player, page - 1);
            } else if (slot == 53 && page < pageCount()) {
                open(player, page + 1);
            }
        }

        /**
         * An accessory as the bag shows it: the rarity-coloured name, its lore (Accessory Power at the
         * build's rarity), "Works while in Accessory Bag!" and the rarity line, with the obfuscated
         * "a" on both sides when recombobulated. OPEN: skull textures aren't in the data.
         */
        static ItemStack item(Accessories.Member m) {
            String colour = rarityColour(m.rarity());
            List<String> lore = new ArrayList<>();
            boolean works = false;
            for (String line : m.def().lore()) {
                String clean = line.replaceAll("[\\uE000-\\uF8FF]", "").replace("  ", " ");
                if (clean.startsWith("Accessory Power:")) {
                    lore.add("§7Accessory Power: §6+" + m.power());
                } else if (clean.equals("Works while in Accessory Bag!")) {
                    works = true;
                    lore.add("§8" + clean);
                } else if (clean.matches("[A-Za-z ]+: [+-][0-9.,]+%?( \\(.*\\))?")) {
                    int c = clean.indexOf(':');
                    lore.add("§7" + clean.substring(0, c + 1) + " §a" + clean.substring(c + 2));
                } else {
                    lore.add(clean.isEmpty() ? "" : "§7" + clean);
                }
            }
            if (!works) {
                lore.add("");
                lore.add("§8Works while in Accessory Bag!");
            }
            boolean recomb = m.def().maxRecombobulated() && !m.rarity().equals(m.def().rarity());
            String kind = (m.def().dungeon() ? "DUNGEON " : "") + "ACCESSORY";
            String rarity = colour + "§l" + m.rarity().replace('_', ' ') + " " + kind;
            lore.add(recomb ? colour + "§k§la§r " + rarity + " " + colour + "§k§la" : rarity);
            return EngineItems.named(Items.PLAYER_HEAD, colour + m.def().name(), lore.toArray(String[]::new));
        }
    }

    static String rarityColour(String rarity) {
        return switch (rarity) {
            case "UNCOMMON" -> "§a";
            case "RARE" -> "§9";
            case "EPIC" -> "§5";
            case "LEGENDARY" -> "§6";
            case "MYTHIC" -> "§d";
            case "DIVINE" -> "§b";
            case "SPECIAL", "VERY_SPECIAL" -> "§c";
            default -> "§f";
        };
    }

    static int rarityRank(String rarity) {
        return switch (rarity) {
            case "UNCOMMON" -> 1;
            case "RARE" -> 2;
            case "EPIC" -> 3;
            case "LEGENDARY" -> 4;
            case "MYTHIC" -> 5;
            case "DIVINE" -> 6;
            case "SPECIAL" -> 7;
            case "VERY_SPECIAL" -> 8;
            default -> 0;
        };
    }

    static final class TimePocket extends Bag {
        TimePocket(int id, Inventory inventory, ServerPlayer player) {
            super(id, inventory, player, 2, true);
            fill();
        }

        @Override
        void fill() {
            frame(BAGS);
            for (int i = 0; i < 6; i++) {
                set(i, ItemStack.EMPTY);
            }
            set(14, EngineItems.named(Items.FLINT, "§aUpgrade Time Pocket", "§7Upgrade your §aTime Pocket §7to store",
                "§7more items §evulnerable to the", "§epassage of time§7.", "", "§7Current Size: §a6 Slots", "§7Next Upgrade: §a9 Slots",
                "", "§7Cost: §9Discrite", "", "§eClick to upgrade!"));
        }

        @Override
        boolean lateSlot(int slot) {
            return slot < 6;
        }

        @Override
        void click(int slot) {
            if (isBack(slot)) {
                openBags(player);
            }
        }
    }

    // ---------------------------------------------------------------- bank

    /** The Personal Bank (9x4). OPEN: Rustclear has no coins, so the balance lines are left out and nothing opens. */
    static final class Bank extends ChestGui {
        Bank(int id, Inventory inventory, ServerPlayer player) {
            super(id, inventory, player, new SimpleContainer(36), 4);
            fill();
        }

        @Override
        void fill() {
            frame(SkyblockMenu.TITLE);
            set(11, EngineItems.named(Items.ENDER_CHEST, "§aCo-op Bank Account", "§7This account is shared with all the",
                "§7members of your §bco-op§7!", "", "§7Account: §cPremier", "", "§eClick to open account!"));
            set(15, EngineItems.named(Items.CHEST, "§aPersonal Bank Account", "§7Your very own account!", "",
                "§7Stores up to §620% §7of your §bco-op", "§7account.", "", "§7Both accounts accrue interest,",
                "§7but you will only earn §620% §7of it", "§7on your personal account.", "", "§eClick to open account!"));
            set(32, EngineItems.named(Items.IRON_DOOR, "§aPersonal Vault", "§7Store your valuable items in a safe",
                "§7place that only you can access!", "", "§eClick to view!"));
            set(35, EngineItems.named(Items.GOLD_BLOCK, "§6Bank Upgrades", "§7Are you so rich that you can't even",
                "§7store your coins?", "", "§7Current account: §cPremier", "", "§eClick to view upgrades!"));
        }

        @Override
        void click(int slot) {
            if (isBack(slot)) {
                SkyblockMenu.open(player);
            }
        }
    }
}
