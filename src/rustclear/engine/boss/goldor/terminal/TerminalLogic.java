package net.rustclear.mod.engine.boss.goldor.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The terminals' puzzle rules without any game types (unit-tested): slot layouts, how a fresh board
 * is dealt, and the counts measured on Hypixel (boss-p3-goldor.md §7, post-update windows).
 */
final class TerminalLogic {
    private TerminalLogic() {
    }

    /** Order: 10 panes in the 2x5 middle of the 9x4 window since the boss update (18 of 18 post windows). */
    static final int[] ORDER_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
    /** Panes: the 3x5 middle of the 9x5 window. */
    static final int[] PANES_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24, 29, 30, 31, 32, 33};
    /** Rubix: the 3x3 middle of the 9x5 window (58 of 58). */
    static final int[] RUBIX_SLOTS = {12, 13, 14, 21, 22, 23, 30, 31, 32};
    /** Starts with: 3x7 (21 items) in the 9x5 window. */
    static final int[] STARTS_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    /** Select: 4x7 (28 items) in the 9x6 window. */
    static final int[] SELECT_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43};

    /** Rubix's colour cycle: a left or middle click steps forward, a right click back. */
    static final String[] RUBIX_COLOURS = {"Red", "Orange", "Yellow", "Green", "Blue"};

    /**
     * Panes lit ("On") at open, 1 to 6 (median 3): the 36 measured windows as weights, index = count
     * (1:5, 2:9, 3:9, 4:5, 5:5, 6:3). 0 and 7+ were never seen.
     */
    private static final int[] PANES_ON_WEIGHTS = {0, 5, 9, 9, 5, 5, 3};

    /**
     * Starts with: items that match the letter, 4 to 11 (median 6): the 55 measured windows as
     * weights, index = count (4:6, 5:11, 6:12, 7:7, 8:7, 9:4, 10:6, 11:2).
     */
    private static final int[] STARTS_MATCH_WEIGHTS = {0, 0, 0, 0, 6, 11, 12, 7, 7, 4, 6, 2};

    /**
     * The letters seen in 55 Starts-with titles: A B C D E F G I M P R S W.
     * OPEN: J, L, N and O also start pool items but never came up (L and N have 6-7 items each,
     * ~1.6% to miss all of them by chance), so Hypixel likely draws from a fixed list; uniform over
     * the seen ones here.
     */
    static final String STARTS_LETTERS = "ABCDEFGIMPRSW";

    /**
     * The Starts-with pool as measured (171 distinct items in 55 windows, 2026-10-03 to 10-16):
     * vanilla id = Hypixel's 1.8 name. Items repeat within a window, matches included.
     */
    static final String[][] STARTS_POOL = pool(
        "acacia_door=Acacia Door|apple=Apple|armor_stand=Armor Stand|arrow=Arrow|baked_potato=Baked Potato|red_bed=Bed"
        + "|birch_door=Birch Door|blaze_powder=Blaze Powder|blaze_rod=Blaze Rod|oak_boat=Boat|bone=Bone|book=Book"
        + "|writable_book=Book and Quill|experience_bottle=Bottle o' Enchanting|bow=Bow|bowl=Bowl|bread=Bread"
        + "|brewing_stand=Brewing Stand|brick=Brick|bucket=Bucket|cake=Cake|carrot=Carrot|carrot_on_a_stick=Carrot on a Stick"
        + "|cauldron=Cauldron|chainmail_boots=Chainmail Boots|chainmail_chestplate=Chainmail Chestplate"
        + "|chainmail_helmet=Chainmail Helmet|chainmail_leggings=Chainmail Leggings|clay_ball=Clay|coal=Coal|compass=Compass"
        + "|cooked_cod=Cooked Fish|cooked_mutton=Cooked Mutton|cooked_porkchop=Cooked Porkchop|cooked_rabbit=Cooked Rabbit"
        + "|cookie=Cookie|dark_oak_door=Dark Oak Door|diamond=Diamond|diamond_axe=Diamond Axe|diamond_boots=Diamond Boots"
        + "|diamond_chestplate=Diamond Chestplate|diamond_helmet=Diamond Helmet|diamond_hoe=Diamond Hoe"
        + "|diamond_horse_armor=Diamond Horse Armor|diamond_leggings=Diamond Leggings|diamond_pickaxe=Diamond Pickaxe"
        + "|diamond_sword=Diamond Sword|egg=Egg|emerald=Emerald|map=Empty Map|enchanted_book=Enchanted Book"
        + "|ender_pearl=Ender Pearl|ender_eye=Eye of Ender|feather=Feather|fermented_spider_eye=Fermented Spider Eye"
        + "|fire_charge=Fire Charge|firework_rocket=Firework Rocket|firework_star=Firework Star|fishing_rod=Fishing Rod"
        + "|flint=Flint|flint_and_steel=Flint and Steel|flower_pot=Flower Pot|ghast_tear=Ghast Tear|glass_bottle=Glass Bottle"
        + "|glistering_melon_slice=Glistering Melon|glowstone_dust=Glowstone Dust|golden_axe=Gold Axe|golden_boots=Gold Boots"
        + "|golden_chestplate=Gold Chestplate|golden_helmet=Gold Helmet|golden_hoe=Gold Hoe|golden_horse_armor=Gold Horse Armor"
        + "|gold_ingot=Gold Ingot|golden_leggings=Gold Leggings|gold_nugget=Gold Nugget|golden_pickaxe=Gold Pickaxe"
        + "|golden_shovel=Gold Shovel|golden_sword=Gold Sword|golden_apple=Golden Apple|golden_carrot=Golden Carrot"
        + "|gunpowder=Gunpowder|ink_sac=Ink Sac|iron_axe=Iron Axe|iron_boots=Iron Boots|iron_chestplate=Iron Chestplate"
        + "|iron_door=Iron Door|iron_helmet=Iron Helmet|iron_hoe=Iron Hoe|iron_horse_armor=Iron Horse Armor|iron_ingot=Iron Ingot"
        + "|iron_leggings=Iron Leggings|iron_pickaxe=Iron Pickaxe|iron_shovel=Iron Shovel|iron_sword=Iron Sword"
        + "|item_frame=Item Frame|jungle_door=Jungle Door|lava_bucket=Lava Bucket|lead=Lead|leather=Leather"
        + "|leather_boots=Leather Boots|leather_chestplate=Leather Chestplate|leather_helmet=Leather Helmet"
        + "|leather_leggings=Leather Leggings|magma_cream=Magma Cream|filled_map=Map|melon_slice=Melon|melon_seeds=Melon Seeds"
        + "|milk_bucket=Milk Bucket|minecart=Minecart|command_block_minecart=Minecart with Command Block"
        + "|hopper_minecart=Minecart with Hopper|tnt_minecart=Minecart with TNT|mushroom_stew=Mushroom Stew|name_tag=Name Tag"
        + "|nether_brick=Nether Brick|quartz=Nether Quartz|nether_star=Nether Star|nether_wart=Nether Wart|oak_sign=Oak Sign"
        + "|painting=Painting|paper=Paper|poisonous_potato=Poisonous Potato|potato=Potato|furnace_minecart=Powered Minecart"
        + "|prismarine_crystals=Prismarine Crystals|prismarine_shard=Prismarine Shard|pumpkin_pie=Pumpkin Pie"
        + "|pumpkin_seeds=Pumpkin Seeds|rabbit_foot=Rabbit Foot|rabbit_hide=Rabbit Hide|rabbit_stew=Rabbit Stew|beef=Raw Beef"
        + "|chicken=Raw Chicken|cod=Raw Fish|mutton=Raw Mutton|porkchop=Raw Porkchop|rabbit=Raw Rabbit|redstone=Redstone"
        + "|comparator=Redstone Comparator|repeater=Redstone Repeater|cooked_chicken=Roast Chicken|rotten_flesh=Rotten Flesh"
        + "|saddle=Saddle|wheat_seeds=Seeds|shears=Shears|skeleton_skull=Skull Item|slime_ball=Slime Ball|snowball=Snowball"
        + "|spider_eye=Spider Eye|spruce_door=Spruce Door|cooked_beef=Steak|stick=Stick|stone_axe=Stone Axe|stone_hoe=Stone Hoe"
        + "|stone_pickaxe=Stone Pickaxe|stone_shovel=Stone Shovel|stone_sword=Stone Sword|chest_minecart=Storage Minecart"
        + "|string=String|sugar=Sugar|sugar_cane=Sugar Cane|clock=Watch|potion=Water Bottle|water_bucket=Water Bucket"
        + "|wheat=Wheat|wooden_axe=Wooden Axe|oak_door=Wooden Door|wooden_hoe=Wooden Hoe|wooden_pickaxe=Wooden Pickaxe"
        + "|wooden_shovel=Wooden Shovel|wooden_sword=Wooden Sword");

    /**
     * Select's 16 colour families, in DyeColor order: the title word, then the 1.8 names of its
     * stained glass, "Stained Clay" (terracotta), wool and dye (64 names measured in 50 windows).
     * Light gray is SILVER everywhere but its dye, "Light Gray Dye".
     */
    static final String[][] SELECT_FAMILIES = {
        {"WHITE", "White Stained Glass", "White Stained Clay", "Wool", "Bone Meal"},
        {"ORANGE", "Orange Stained Glass", "Orange Stained Clay", "Orange Wool", "Orange Dye"},
        {"MAGENTA", "Magenta Stained Glass", "Magenta Stained Clay", "Magenta Wool", "Magenta Dye"},
        {"LIGHT BLUE", "Light Blue Stained Glass", "Light Blue Stained Clay", "Light Blue Wool", "Light Blue Dye"},
        {"YELLOW", "Yellow Stained Glass", "Yellow Stained Clay", "Yellow Wool", "Dandelion Yellow"},
        {"LIME", "Lime Stained Glass", "Lime Stained Clay", "Lime Wool", "Lime Dye"},
        {"PINK", "Pink Stained Glass", "Pink Stained Clay", "Pink Wool", "Pink Dye"},
        {"GRAY", "Gray Stained Glass", "Gray Stained Clay", "Gray Wool", "Gray Dye"},
        {"SILVER", "Silver Stained Glass", "Silver Stained Clay", "Silver Wool", "Light Gray Dye"},
        {"CYAN", "Cyan Stained Glass", "Cyan Stained Clay", "Cyan Wool", "Cyan Dye"},
        {"PURPLE", "Purple Stained Glass", "Purple Stained Clay", "Purple Wool", "Purple Dye"},
        {"BLUE", "Blue Stained Glass", "Blue Stained Clay", "Blue Wool", "Lapis Lazuli"},
        {"BROWN", "Brown Stained Glass", "Brown Stained Clay", "Brown Wool", "Cocoa Bean"},
        {"GREEN", "Green Stained Glass", "Green Stained Clay", "Green Wool", "Cactus Green"},
        {"RED", "Red Stained Glass", "Red Stained Clay", "Red Wool", "Rose Red"},
        {"BLACK", "Black Stained Glass", "Black Stained Clay", "Black Wool", "Ink Sack"},
    };

    private static String[][] pool(String spec) {
        String[] parts = spec.split("\\|");
        String[][] out = new String[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            out[i] = new String[] {parts[i].substring(0, eq), parts[i].substring(eq + 1)};
        }
        return out;
    }

    private static int weighted(int[] weights, Random random) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int r = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    // ---------------------------------------------------------------- Order

    /** Order: the number (1-10) on each of [ORDER_SLOTS], a uniform shuffle. */
    static int[] orderNumbers(Random random) {
        int[] numbers = new int[ORDER_SLOTS.length];
        for (int i = 0; i < numbers.length; i++) {
            numbers[i] = i + 1;
        }
        shuffle(numbers, random);
        return numbers;
    }

    // ---------------------------------------------------------------- Panes

    /** Panes: which of [PANES_SLOTS] start On (1-6 of them, the measured spread). */
    static boolean[] panesOn(Random random) {
        int on = weighted(PANES_ON_WEIGHTS, random);
        int[] order = indices(PANES_SLOTS.length);
        shuffle(order, random);
        boolean[] lit = new boolean[PANES_SLOTS.length];
        for (int i = 0; i < on; i++) {
            lit[order[i]] = true;
        }
        return lit;
    }

    // ---------------------------------------------------------------- Rubix

    /**
     * Rubix: 9 colours (indexes into [RUBIX_COLOURS]), each uniform; a solved board is drawn again.
     * Plain uniform draws match Hypixel: fewest clicks over 58 windows 4:1 5:2 6:6 7:11 8:17 9:14
     * 10:7 against 0.6/2.1/5.2/10.2/15.0/15.8/9.0 expected (median 8 both).
     */
    static int[] rubixBoard(Random random) {
        int[] board = new int[RUBIX_SLOTS.length];
        do {
            for (int i = 0; i < board.length; i++) {
                board[i] = random.nextInt(RUBIX_COLOURS.length);
            }
        } while (rubixSolved(board));
        return board;
    }

    static boolean rubixSolved(int[] board) {
        for (int c : board) {
            if (c != board[0]) {
                return false;
            }
        }
        return true;
    }

    /** Clicks to turn colour [from] into [to] going the short way round (forward or back). */
    static int rubixDistance(int from, int to) {
        int forward = Math.floorMod(to - from, RUBIX_COLOURS.length);
        return Math.min(forward, RUBIX_COLOURS.length - forward);
    }

    /** The target colour that needs the fewest clicks (the lowest index on a tie). */
    static int rubixBestTarget(int[] board) {
        int best = 0;
        int bestCost = Integer.MAX_VALUE;
        for (int t = 0; t < RUBIX_COLOURS.length; t++) {
            int cost = 0;
            for (int c : board) {
                cost += rubixDistance(c, t);
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = t;
            }
        }
        return best;
    }

    static int rubixFewestClicks(int[] board) {
        int t = rubixBestTarget(board);
        int cost = 0;
        for (int c : board) {
            cost += rubixDistance(c, t);
        }
        return cost;
    }

    /** The colour after a click: forward (left/middle) or back (right). */
    static int rubixStep(int colour, boolean back) {
        return Math.floorMod(colour + (back ? -1 : 1), RUBIX_COLOURS.length);
    }

    /** The next click toward the best target: {index into the board, 1 if back else 0}, or null when solved. */
    static int[] rubixNextClick(int[] board) {
        if (rubixSolved(board)) {
            return null;
        }
        int t = rubixBestTarget(board);
        for (int i = 0; i < board.length; i++) {
            if (board[i] != t) {
                int forward = Math.floorMod(t - board[i], RUBIX_COLOURS.length);
                return new int[] {i, forward <= RUBIX_COLOURS.length - forward ? 0 : 1};
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Starts with

    /** How many of the 21 items match the letter (4-11, the measured spread). */
    static int startsMatches(Random random) {
        return weighted(STARTS_MATCH_WEIGHTS, random);
    }

    /**
     * A Starts-with board: [letter] and, per slot of [STARTS_SLOTS], an index into [STARTS_POOL].
     * Matches and the rest are each drawn with repeats (as measured: 8 matches of 2 distinct names
     * happen), then shuffled.
     */
    static int[] startsBoard(char letter, int matches, Random random) {
        List<Integer> right = new ArrayList<>();
        List<Integer> wrong = new ArrayList<>();
        for (int i = 0; i < STARTS_POOL.length; i++) {
            (STARTS_POOL[i][1].charAt(0) == letter ? right : wrong).add(i);
        }
        int[] board = new int[STARTS_SLOTS.length];
        for (int i = 0; i < board.length; i++) {
            List<Integer> from = i < matches ? right : wrong;
            board[i] = from.get(random.nextInt(from.size()));
        }
        shuffle(board, random);
        return board;
    }

    // ---------------------------------------------------------------- Select

    /**
     * A Select board: element 0 is the target family, then per slot of [SELECT_SLOTS] a family
     * index * 4 + kind (0 glass, 1 clay, 2 wool, 3 dye). Five families split 6-6-6-5-5, three of the
     * five drawn at random to get 6 (50 of 50 windows; the target had 6 in 29 of 50, 3/5 expected).
     */
    static int[] selectBoard(Random random) {
        int[] families = indices(SELECT_FAMILIES.length);
        shuffle(families, random);
        int[] sizes = {6, 6, 6, 5, 5};
        shuffle(sizes, random);
        int[] cells = new int[SELECT_SLOTS.length];
        int at = 0;
        for (int f = 0; f < 5; f++) {
            for (int k = 0; k < sizes[f]; k++) {
                cells[at++] = families[f] * 4 + random.nextInt(4);
            }
        }
        shuffle(cells, random);
        int[] out = new int[cells.length + 1];
        out[0] = families[0];
        System.arraycopy(cells, 0, out, 1, cells.length);
        return out;
    }

    // ---------------------------------------------------------------- Melody

    /**
     * Melody's state since the boss update: 3 lanes. The lime starts in column 1 moving right (21 of
     * 21 post windows) and bounces between columns 1 and 5, one column per step. A lock with the lime
     * under the magenta column holds the lane until the next step, when the next lane takes over (the
     * lime keeps moving, into the new lane) and the magenta moves to a different column (28 of 28
     * changes). The third lock finishes at once. A wrong lock makes the lime sit out its next 2 steps
     * (a 30-tick gap, 4 of 4 prior; more wrong locks in a row still give one delay).
     */
    static final class Melody {
        static final int LANES = 3;

        int lane;
        int lime = 1;
        int dir = 1;
        int target;
        boolean locked;
        int frozen;

        enum Lock { ACCEPTED, FINISHED, IGNORED, WRONG }

        // OPEN: the first target is uniform over 1-5 here (post-update 2/4/3/5/7 for columns 1-5, n 21; prior audit 17/6/12/21/11, p ~0.04).

        Melody(Random random) {
            target = 1 + random.nextInt(5);
        }

        /** A step (every 10 ticks): the next lane if this one is locked, then the lime moves unless frozen. */
        void step(Random random) {
            if (locked) {
                locked = false;
                lane++;
                int next = 1 + random.nextInt(4);
                target = next >= target ? next + 1 : next;
            }
            if (frozen > 0) {
                frozen--;
                return;
            }
            if (lime + dir < 1 || lime + dir > 5) {
                dir = -dir;
            }
            lime += dir;
        }

        /** A click on lane [clickedLane]'s lock button. */
        Lock lock(int clickedLane) {
            if (clickedLane == lane && locked) {
                return Lock.IGNORED;
            }
            if (clickedLane == lane && lime == target) {
                if (lane == LANES - 1) {
                    lane = LANES;
                    return Lock.FINISHED;
                }
                locked = true;
                return Lock.ACCEPTED;
            }
            frozen = 2;
            return Lock.WRONG;
        }

        boolean solved() {
            return lane >= LANES;
        }
    }

    // ---------------------------------------------------------------- helpers

    static int[] indices(int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = i;
        }
        return out;
    }

    static void shuffle(int[] a, Random random) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }
}
