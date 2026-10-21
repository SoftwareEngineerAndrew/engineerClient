package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * Hypixel's SkyBlock Menu (menus-skyblock.md §1), opened by using the item in the 9th hotbar slot
 * (the Magical Map in a run, or the SkyBlock Menu star) or /sbmenu, one tick later. Every button
 * has Hypixel's icon, name and lore, with the player's own numbers where Rustclear has them (their
 * stats, skill levels, SkyBlock level, pet, the SkyBlock date); lines whose numbers Rustclear
 * can't know (collections, recipes, coins) are left out. Stats & Equipment, Your Skills (and the
 * Dungeoneering pages under it), Storage, Your Bags, Pets, Loadouts and Personal Bank open their
 * menus; the rest were never opened in the recordings and do nothing.
 */
final class SkyblockMenu extends ChestGui {
    static final String TITLE = "SkyBlock Menu";
    private static final String ID = "SKYBLOCK_MENU";
    private static final int PROFILE = 13;
    private static final int SKILLS = 19;
    private static final int COLLECTIONS = 20;
    private static final int RECIPES = 21;
    private static final int LEVELING = 22;
    private static final int QUESTS = 23;
    private static final int CALENDAR = 24;
    private static final int STORAGE = 25;
    private static final int BAGS = 29;
    private static final int PETS = 30;
    private static final int CRAFTING = 31;
    private static final int LOADOUTS = 32;
    private static final int BANK = 33;
    private static final int FAST_TRAVEL = 47;
    private static final int PROFILES = 48;
    private static final int SETTINGS = 50;
    private static final int COOKIE = 51;

    /** The SkyBlock calendar: year 1 began 2019-06-11 17:55 UTC; a day is 20 minutes, a month 31 days. */
    private static final long CALENDAR_EPOCH = 1_560_275_700L;
    private static final String[] MONTHS = {"Early Spring", "Spring", "Late Spring", "Early Summer", "Summer", "Late Summer",
        "Early Autumn", "Autumn", "Late Autumn", "Early Winter", "Winter", "Late Winter"};

    static void open(ServerPlayer player) {
        show(player, TITLE, (id, inventory, p) -> new SkyblockMenu(id, inventory, player));
    }

    private SkyblockMenu(int id, Inventory inventory, ServerPlayer player) {
        super(id, inventory, player, new SimpleContainer(SIZE));
        fill();
    }

    /** Whether [player] using what they hold opens this: the item in the 9th hotbar slot, held. */
    static boolean opensMenu(ServerPlayer player) {
        if (player.getInventory().getSelectedSlot() != Kit.MAP_SLOT) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        return held.is(Items.FILLED_MAP) || isStar(held);
    }

    /** The SkyBlock Menu star, as Hypixel's (id SKYBLOCK_MENU, which mods look for). */
    static ItemStack star() {
        ItemStack stack = EngineItems.named(Items.NETHER_STAR, "§aSkyBlock Menu §7(Click)",
            "§7View all of your SkyBlock progress,", "§7including your Skills, Collections,", "§7Recipes, and more!", "", "§eClick to open!");
        CompoundTag tag = new CompoundTag();
        tag.putString("id", ID);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    static boolean isStar(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return stack.is(Items.NETHER_STAR) && data != null && ID.equals(data.copyTag().getStringOr("id", ""));
    }

    @Override
    void fill() {
        frame((String) null);
        SkyblockProfile profile = PlayerStats.profile(player);
        set(PROFILE, StatsMenu.menuHead(player, PlayerStats.of(player)));
        set(SKILLS, EngineItems.named(Items.DIAMOND_SWORD, "§aYour Skills", "§7View your Skill progression and", "§7rewards.", "",
            "§6" + MenuText.decimal(SkillMenus.average(profile)) + " Skill Avg. §8(non-cosmetic)", "",
            "§8Also accessible via /skills.", "", "§eClick to view!"));
        set(COLLECTIONS, EngineItems.named(Items.PAINTING, "§aCollections", "§7View all of the items available in",
            "§7SkyBlock. Collect more of an item to", "§7unlock rewards on your way to", "§7becoming a master of SkyBlock!", "",
            "§8Also accessible via /collection.", "", "§eClick to view!"));
        set(RECIPES, EngineItems.named(Items.BOOK, "§aRecipe Book", "§7Through your adventure, you will",
            "§7unlock recipes for all kinds of", "§7special items! You can view how to", "§7craft these items here.", "",
            "§8Also accessible via /recipes.", "", "§eClick to view!"));
        set(LEVELING, leveling(profile));
        set(QUESTS, EngineItems.named(Items.WRITABLE_BOOK, "§aQuests & Chapters", "§7Each island has its own series of",
            "§bChapters §7for you to complete!", "", "§7Complete tasks within a Chapter to", "§7earn small §6rewards§7, or complete",
            "§7entire Chapters to earn big ones!", "", "§7Some islands also have §aQuests §7for",
            "§7you to complete! Some items can only", "§7be obtained through Quests.", "", "§eClick to view!"));
        set(CALENDAR, EngineItems.named(Items.CLOCK, "§aCalendar and Events", "§7View the SkyBlock Calendar, upcoming",
            "§7events, and event rewards!", "", "§7Date: §a" + date(System.currentTimeMillis() / 1000), "",
            "§8Also accessible via /calendar", "", "§eClick to view!"));
        set(STORAGE, EngineItems.named(Items.CHEST, "§aStorage", "§7Store global items that you want to",
            "§7access at any time from anywhere", "§7here.", "", "§8Also accessible via /storage", "", "§eClick to view!"));
        set(BAGS, MenuText.head(MenuText.BAGS, "§aYour Bags", "§7Different bags allow you to store",
            "§7many different items inside!", "", "§8Also accessible via /bags", "", "§eClick to open!"));
        set(PETS, pets());
        set(CRAFTING, EngineItems.named(Items.CRAFTING_TABLE, "§aCrafting Table", "§7Opens the crafting grid.", "",
            "§8Also accessible via /craft", "", "§eClick to open!"));
        set(LOADOUTS, EngineItems.named(Items.BARREL, "§aLoadouts", "§7View and edit preset armor and",
            "§7equipment sets with other settings to", "§7make switching activities easy.", "", "§8Also accessible via /loadouts",
            "", "§eClick to view!"));
        set(BANK, MenuText.head(MenuText.BANK, "§aPersonal Bank", "§7Contact your Banker from anywhere.", "", "§6No cooldown!",
            "", "§8Also accessible via /bank", "", "§eClick to open!"));
        set(FAST_TRAVEL, MenuText.head(MenuText.FAST_TRAVEL, "§bFast Travel", "§7Teleport to islands you've already",
            "§7visited.", "", "§8Also accessible via /warp", "", "§8Right-click for hub warp!", "§eClick to pick location!"));
        // The Rustclear world is always a dungeon: Profile Management says it's disabled.
        set(PROFILES, EngineItems.named(Items.NAME_TAG, "§aProfile Management", "§7You can have multiple SkyBlock",
            "§7profiles at the same time.", "", "§7Each profile has its own island,", "§7inventory, quest log...", "",
            "§bPlay with friends using /coopadd <name>!", "", "§8Also accessible via /profiles", "",
            "§cProfile Management is disabled in", "§cDungeons!"));
        set(SETTINGS, EngineItems.named(Items.REDSTONE_TORCH, "§aSettings", "§7View and edit your SkyBlock settings.", "",
            "§8Also accessible via /viewsettings.", "", "§eClick to view!"));
        ItemStack cookie = EngineItems.named(Items.COOKIE, "§6Booster Cookie", "§7Obtain the §dCookie Buff §7from Booster",
            "§7Cookies in the hub's Community Shop.", "", "§8Also accessible via /boostercookie", "", "§eClick to view!");
        cookie.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        set(COOKIE, cookie);
    }

    /** Slot 22: the SkyBlock level with its colour bracket and the XP into the next level. */
    private static ItemStack leveling(SkyblockProfile profile) {
        List<String> lore = new ArrayList<>();
        if (profile.level > 0) {
            lore.add("§7Your SkyBlock Level: §8[" + levelColor(profile.level) + profile.level + "§8]");
            lore.add("");
        }
        lore.addAll(List.of("§7Determine how far you've", "§7progressed in SkyBlock and earn", "§7rewards from completing unique",
            "§7tasks."));
        if (profile.level > 0) {
            lore.add("");
            lore.add("§7Progress to Level " + (profile.level + 1) + ":");
            lore.add(MenuText.bar(profile.levelXp, 100, "§3", "§b" + profile.levelXp + "§3/§b100 XP"));
        }
        lore.addAll(List.of("", "§8Also accessible via /levels", "", "§eClick to view!"));
        return MenuText.head(MenuText.LEVELING, "§aSkyBlock Leveling", lore.toArray(String[]::new));
    }

    /** The colour of a SkyBlock level, by its bracket of 40 (277 dark aqua, 280-319 blue: menus-skyblock.md §1, §5). */
    static String levelColor(int level) {
        String[] colors = {"§7", "§f", "§e", "§a", "§2", "§b", "§3", "§9", "§d", "§5", "§6", "§c", "§4"};
        return colors[Math.max(0, Math.min(colors.length - 1, level / 40))];
    }

    /** Slot 30: the selected pet, and its MAX LEVEL / XP lines. */
    private ItemStack pets() {
        Pets.Pet pet = Pets.active(player);
        List<String> lore = new ArrayList<>(List.of("§7View and manage all of your Pets.", "",
            "§7Level up your pets faster by gaining", "§7XP in their favorite skill!", ""));
        if (pet == null) {
            lore.add("§7Selected pet: §cNone");
        } else {
            lore.add("§7Selected pet: " + pet.shortName() + "§5 ✦");
            if (pet.level() >= pet.type().maxLevel) {
                lore.add("");
                lore.add("§b§lMAX LEVEL");
                lore.add(String.format(java.util.Locale.ROOT, "§8▸ %,d XP", (long) pet.exp()));
            }
        }
        lore.addAll(List.of("", "§8Also accessible via /pets.", "", "§eClick to view!"));
        return EngineItems.named(Items.BONE, "§aPets", lore.toArray(String[]::new));
    }

    /** The SkyBlock date at [epochSeconds], "12th Autumn 518" (checked against the recordings). */
    static String date(long epochSeconds) {
        long since = Math.max(0, epochSeconds - CALENDAR_EPOCH);
        long year = since / 446_400 + 1;
        long inYear = since % 446_400;
        int month = (int) (inYear / 37_200);
        int day = (int) (inYear % 37_200 / 1_200) + 1;
        return day + ordinal(day) + " " + MONTHS[month] + " " + year;
    }

    private static String ordinal(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) {
            return "th";
        }
        return switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    @Override
    void click(int slot) {
        switch (slot) {
            case PROFILE -> StatsMenu.open(player, true);
            case SKILLS -> SkillMenus.openSkills(player);
            case STORAGE -> StorageMenu.open(player);
            case BAGS -> BagMenus.openBags(player);
            case PETS -> PetsMenu.open(player);
            case LOADOUTS -> LoadoutsMenu.open(player, true);
            case BANK -> BagMenus.openBank(player);
            default -> {
                // OPEN: Collections, Recipe Book, SkyBlock Leveling, Quests, Calendar, Crafting Table,
                // Fast Travel, Profile Management, Settings and Booster Cookie open menus Rustclear doesn't have.
            }
        }
    }
}
