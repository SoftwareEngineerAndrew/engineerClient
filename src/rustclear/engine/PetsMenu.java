package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Hypixel's Pets menu, slot for slot (menus-skyblock.md B.1): Pets at 4, Exp Sharing at 7, +28 Pet
 * Slots at 8, your pets packed from 10, and along the bottom Autopet (46), Pet Score Rewards (47), Go
 * Back, Close, Search (50), Pet visibility (51) and Sort (52). Left-click a pet to summon it (the menu
 * closes), right-click it to take it out as an item (the menu re-opens without it); click a pet item
 * in your inventory below to put it back in (a right-click with it does the same anywhere).
 */
final class PetsMenu extends ChestGui {
    static final String TITLE = "Pets";
    static final int[] PET_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43};
    private static final int INFO = 4;
    private static final int EXP_SHARING = 7;
    private static final int PET_SLOTS_UPGRADE = 8;
    private static final int AUTOPET = 46;
    private static final int SCORE = 47;
    private static final int SEARCH = 50;
    private static final int VISIBILITY = 51;
    private static final int SORT = 52;
    /** Pet Score Rewards' tiers: the score, and the Magic Find it gives. */
    private static final int[] SCORE_TIERS = {10, 25, 50, 75, 100, 130, 175, 225, 275, 325, 375, 450, 500};

    /** The window Go Back returns to. */
    private final String parent;
    private List<Pets.Pet> shown = List.of();
    /** A right-clicked pet on its way out as an item, and how many ticks since the click. */
    private Pets.Pet converting;
    private int convertTicks;

    static void open(ServerPlayer player) {
        open(player, SkyblockMenu.TITLE);
    }

    static void open(ServerPlayer player, String parent) {
        ChestGui.show(player, TITLE, (id, inventory, p) -> new PetsMenu(id, inventory, player, parent));
    }

    private PetsMenu(int id, Inventory inventory, ServerPlayer player, String parent) {
        super(id, inventory, player, new SimpleContainer(SIZE));
        this.parent = parent;
        fill();
    }

    @Override
    void fill() {
        frame(parent);
        Pets.Pet active = Pets.active(player);
        shown = Pets.owned(player);
        set(INFO, EngineItems.named(Items.BONE, "§aPets", infoLore(active)));
        set(EXP_SHARING, EngineItems.named(Items.DYE.yellow(), "§6Exp Sharing", "§7Let your pets gain §aPet Exp §7without",
            "§7them being actively deployed!", "", "§eClick to view!"));
        // Rustclear has no Bits, so the button reads as it does on a profile without enough.
        ItemStack slots = EngineItems.named(Items.DIAMOND, "§a+28 Pet Slots", "§7Unlock a new page of pets.", "",
            "§7Current Max Pets: §a" + Pets.MAX, "", "§7Cost", "§b5,000 Bits", "", "§cYou don't have enough Bits!");
        slots.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        set(PET_SLOTS_UPGRADE, slots);
        // OPEN: what an empty Pets menu shows in the middle (every recorded profile had pets).
        for (int i = 0; i < shown.size() && i < PET_SLOTS.length; i++) {
            Pets.Pet pet = shown.get(i);
            set(PET_SLOTS[i], Pets.menuStack(pet, pet.uuid().equals(active == null ? null : active.uuid())));
        }
        // OPEN: the Autopet head's skin; a plain head until it's known.
        set(AUTOPET, EngineItems.named(Items.PLAYER_HEAD, "§cAutopet", "§7Define custom §crules §7to automatically",
            "§7equip your pets.", "", "§7Rules used: §c" + Pets.rules(player).size() + "/" + Pets.MAX_RULES, "",
            "§eClick to setup autopet!"));
        set(SCORE, EngineItems.named(Items.DIAMOND, "§dPet Score Rewards", scoreLore(Pets.score(player))));
        set(SEARCH, EngineItems.named(Items.OAK_SIGN, "§aSearch", "§7Find pets by name!", "", "§eClick to search!"));
        // Rustclear's pets don't follow you, which is Hypixel's "Hide all pets".
        set(VISIBILITY, EngineItems.named(Items.STONE_BUTTON, "§aPet visibility", "", "§7  Show all pets", "§3▶ Hide all pets",
            "§7  Only your pet", "", "§7Pet name tags: §cDisabled", "", "§bRight-click to toggle name tags!",
            "§eLeft-click to switch visibility!"));
        List<String> sort = new ArrayList<>();
        sort.add("");
        for (Pets.Sort s : Pets.Sort.values()) {
            sort.add((s == Pets.sort(player) ? "§b▶ " : "§7  ") + s.title);
        }
        sort.add("");
        sort.add("§eClick to switch sort!");
        sort.add("§bRight-Click to go backwards!");
        set(SORT, EngineItems.named(Items.HOPPER, "§aSort", sort.toArray(String[]::new)));
    }

    /** Slot 4: "Selected pet: §6Black Cat§5 ✦", its level block and exp, and the /pets line. */
    private static String[] infoLore(Pets.Pet active) {
        List<String> lore = new ArrayList<>(List.of("§7View and manage all of your Pets.", "",
            "§7Level up your pets faster by gaining", "§7XP in their favorite skill!", ""));
        if (active == null) {
            // OPEN: the Selected pet line with no pet out.
            lore.add("§7Selected pet: §cNone");
        } else {
            lore.add("§7Selected pet: " + active.shortName());
            lore.add("");
            if (active.level() >= active.type().maxLevel) {
                lore.add("§b§lMAX LEVEL");
                lore.add("§8▸ " + String.format(Locale.ROOT, "%,d", (long) Math.floor(active.exp())) + " XP");
            } else {
                // OPEN: the progress lines of a pet below max level.
                lore.add("§7Level §a" + active.level() + "§7/§a" + active.type().maxLevel);
            }
        }
        lore.add("");
        lore.add("§8Also accessible via /pets.");
        return lore.toArray(String[]::new);
    }

    /** Slot 47: the score tiers, the reached one marked §5«, and "Your Pet Score". */
    private static String[] scoreLore(int score) {
        List<String> lore = new ArrayList<>(List.of("§dPet Score §7is calculated based on", "§7how many §aunique §7pets you have and",
            "§7their §6rarity§7.", "", "§8SPECIAL and VERY SPECIAL Pets do", "§8not contribute towards your Pet", "§8Score.", "",
            "§7You gain an additional score for", "§7each max level pet you have!", ""));
        int reached = -1;
        for (int i = 0; i < SCORE_TIERS.length; i++) {
            if (score >= SCORE_TIERS[i]) {
                reached = i;
            }
        }
        for (int i = 0; i < SCORE_TIERS.length; i++) {
            lore.add("§6" + SCORE_TIERS[i] + " Score§8: +§b" + (i + 1) + " Magic Find" + (i == reached ? " §5«" : ""));
        }
        lore.add("");
        lore.add("§9Your Pet Score: §f" + score);
        return lore.toArray(String[]::new);
    }

    @Override
    void click(int slot) {
        click(slot, 0, false);
    }

    @Override
    void click(int slot, int button, boolean shift) {
        if (isBack(slot)) {
            SkyblockMenu.open(player);
            return;
        }
        if (slot == AUTOPET) {
            AutopetMenu.open(player);
            return;
        }
        if (slot == SORT) {
            Pets.nextSort(player, button == 1);
            fill();
            return;
        }
        if (converting != null) {
            return;
        }
        for (int i = 0; i < shown.size() && i < PET_SLOTS.length; i++) {
            if (slot != PET_SLOTS[i]) {
                continue;
            }
            Pets.Pet pet = shown.get(i);
            if (button == 1) {
                // Convert to an item: the pet stays shown until the menu re-opens without it.
                if (Pets.hasRoom(player)) {
                    converting = pet;
                    convertTicks = 0;
                } else {
                    Pets.toItem(player, pet);
                }
            } else if (shift) {
                // OPEN: Shift Left-click toggles a favourite on Hypixel; how a favourite is drawn wasn't recorded.
            } else {
                Pets.toggle(player, pet);
                player.closeContainer();
            }
            return;
        }
    }

    /**
     * A conversion's steps, as Hypixel times them after the click (menus-skyblock.md B.1.3): +1 the
     * arrow.hit_player sound (NEUTRAL 1.0/1.0), +2 the item in the first free slot, +4 the menu again
     * as a new window. This runs every tick from the click's own (the first call, +0), before the menu
     * sends its changes, so the item goes out in the tick it's given.
     */
    @Override
    public void broadcastChanges() {
        if (converting != null) {
            convertTicks++;
            if (convertTicks == 2) {
                Pets.sound(player, SoundEvents.ARROW_HIT_PLAYER, SoundSource.NEUTRAL, 1f, 1f);
            } else if (convertTicks == 3) {
                Pets.toItem(player, converting);
            } else if (convertTicks == 5) {
                // ChestGui.show opens it at the end of this tick: +4.
                converting = null;
                open(player, parent);
            }
        }
        super.broadcastChanges();
    }

    /** Closed before a conversion gave its item: give it now. */
    @Override
    public void removed(Player who) {
        super.removed(who);
        if (converting != null && convertTicks < 3) {
            Pets.toItem(player, converting);
        }
        converting = null;
    }

    /** A pet item clicked in the inventory below goes back in the menu. */
    @Override
    void inventoryClick(int index) {
        Pets.fromItem(player, index);
    }
}
