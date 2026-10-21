package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The six reward chests in the reward room after the boss (boss-p4-necron.md F8, menus-ui.md §4):
 * armour-stand chests at x 23.5 / 28.3 / 33.5, z 126-136, spawned at D; a right click opens a 9x6
 * window titled by tier a tick later: the loot at 11-15 (with 4 items a gray pane at 13), "Open
 * Reward Chest" at 31, Close at 49, "Reroll Chest" at 50. Opening prints "<TIER> CHEST REWARDS"
 * with two experience-orb sounds and closes the window.
 * OPEN: which chest stands where; the loot only looks like the recorded chests.
 */
final class RewardChests {
    enum Tier {
        WOOD("Wood", "§f", "FREE"), GOLD("Gold", "§6", "100,000 Coins"), DIAMOND("Diamond", "§b", "250,000 Coins"),
        EMERALD("Emerald", "§2", "500,000 Coins"), OBSIDIAN("Obsidian", "§5", "1,000,000 Coins"),
        BEDROCK("Bedrock", "§8", "2,000,000 Coins");

        final String title;
        final String colour;
        /** The lowest cost seen per tier (menus-ui.md §4; costs vary with the contents). */
        final String cost;

        Tier(String title, String colour, String cost) {
            this.title = title;
            this.colour = colour;
            this.cost = cost;
        }
    }

    private static final double[] XS = {23.5, 28.3, 33.5};
    private static final double[] ZS = {127.5, 134.5};

    /** The stands of the current run's chests, and their tier. */
    private static final Map<Entity, Tier> STANDS = new IdentityHashMap<>();

    private RewardChests() {
    }

    static void spawn(DungeonRun run) {
        Tier[] tiers = Tier.values();
        for (int i = 0; i < tiers.length; i++) {
            ArmorStand s = new ArmorStand(EntityTypes.ARMOR_STAND, run.level);
            s.setInvisible(true);
            s.setNoGravity(true);
            s.setPermanentlyInvulnerable(true);
            s.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHEST));
            s.setCustomName(EngineItems.styled(tiers[i].colour + tiers[i].title + " Chest"));
            s.setCustomNameVisible(true);
            Vec3 at = new Vec3(XS[i % 3], 160.6, ZS[i / 3]);
            s.snapTo(at.x, at.y, at.z, 180f, 0f);
            run.level.addFreshEntity(s);
            run.bossEntities.add(s);
            STANDS.put(s, tiers[i]);
        }
    }

    /** A right click on a chest's stand: its window a tick later. */
    static boolean use(ServerPlayer player, Entity entity) {
        Tier tier = STANDS.get(entity);
        if (tier == null) {
            return false;
        }
        ChestGui.show(player, tier.title, (id, inv, p) -> new Menu(id, inv, player, tier));
        return true;
    }

    static void clear() {
        STANDS.keySet().removeIf(Entity::isRemoved);
    }

    private static final class Menu extends ChestGui {
        private final Tier tier;
        private final List<String> loot;

        Menu(int id, Inventory inventory, ServerPlayer player, Tier tier) {
            super(id, inventory, player, new SimpleContainer(54), 6);
            this.tier = tier;
            this.loot = loot(tier, player.getRandom().nextLong());
            fill();
        }

        @Override
        void fill() {
            for (int i = 0; i < size; i++) {
                box.setItem(i, EngineItems.filler());
            }
            int[] at = loot.size() >= 5 ? new int[] {11, 12, 13, 14, 15} : new int[] {11, 12, 14, 15};
            if (loot.size() < 5) {
                box.setItem(13, EngineItems.named(Items.STAINED_GLASS_PANE.gray(), " "));
            }
            for (int i = 0; i < Math.min(loot.size(), at.length); i++) {
                String name = loot.get(i);
                box.setItem(at[i], EngineItems.named(name.contains("Essence") ? Items.PLAYER_HEAD : Items.PAPER, name,
                    "", "§eClick the chest below to purchase", "§ethese rewards!"));
            }
            List<String> lore = new ArrayList<>();
            lore.add("§7Contents");
            lore.addAll(loot);
            lore.add("");
            lore.add("§7Cost");
            lore.add(tier == Tier.WOOD ? "§aFREE" : "§6" + tier.cost);
            lore.add("§7§cNOTE: Coins are withdrawn from your bank if you don't have enough in your purse.");
            lore.add("");
            lore.add("§eClick to open!");
            box.setItem(31, EngineItems.named(Items.CHEST, "§aOpen Reward Chest", lore.toArray(String[]::new)));
            box.setItem(CLOSE, close());
            box.setItem(50, EngineItems.named(Items.FEATHER, "§aReroll Chest",
                "§7Consume a §dKismet Feather §7to reroll", "§7the loot in this chest.", "", "§cBring a Kismet Feather"));
        }

        @Override
        void click(int slot) {
            if (slot != 31) {
                return;
            }
            sound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1.492f);
            sound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1.492f);
            player.sendSystemMessage(EngineItems.text(""));
            player.sendSystemMessage(EngineItems.text("  " + tier.colour + "§l" + tier.title.toUpperCase(java.util.Locale.ROOT)
                + " CHEST REWARDS"));
            for (String item : loot) {
                player.sendSystemMessage(EngineItems.text("    " + item));
            }
            player.sendSystemMessage(EngineItems.text(""));
            player.closeContainer();
        }
    }

    /**
     * A chest that looks like the recorded ones (117 F7 chests, mostly Obsidian and Bedrock): Wither
     * and Undead Essence at the tier's counts, one or two enchanted books, sometimes a drop from the
     * tier's pool. Not a loot table (the user: chests only need to look right).
     */
    static List<String> loot(Tier tier, long seed) {
        Random r = new Random(seed);
        List<String> out = new ArrayList<>();
        int t = tier.ordinal();
        String[][] pool = {
            {},
            {},
            {"§9Precursor Gear", "§5Wither Catalyst"},
            {"§9Precursor Gear", "§5Wither Catalyst"},
            {"§6Wither Boots", "§6Wither Helmet", "§5Fuming Potato Book", "§5Hot Potato Book", "§5Wither Blood",
                "§9Precursor Gear", "§5Wither Catalyst"},
            {"§6Recombobulator 3000", "§6Wither Helmet", "§6Wither Boots", "§5Fuming Potato Book", "§5Hot Potato Book",
                "§9Precursor Gear", "§aStorm the Fish", "§aMaxor the Fish", "§aGoldor the Fish", "§5Wither Blood"},
        };
        String[] books = {"§9Rejuvenate II", "§d§lLast Stand I", "§d§lCombo II", "§9Feather Falling VI",
            "§d§lNo Pain No Gain I", "§9Infinite Quiver VI", "§d§lWisdom I", "§d§lBank I"};
        if (pool[t].length > 0 && r.nextDouble() < 0.6) {
            out.add(pool[t][r.nextInt(pool[t].length)]);
        }
        int n = 1 + (r.nextBoolean() ? 1 : 0);
        for (int i = 0; i < n; i++) {
            out.add("§fEnchanted Book §8(" + books[r.nextInt(books.length)] + "§8)");
        }
        // Recorded counts: Wither 15/22/~30/29-35/35-44/50-55, Undead 16/37/~42/46/58/79.
        int[] wither = {15, 22, 26, 29, 35, 50};
        int[] undead = {16, 37, 42, 46, 58, 79};
        out.add("§dWither Essence §8x" + (wither[t] + r.nextInt(t >= 4 ? 6 : 3)));
        out.add("§dUndead Essence §8x" + undead[t]);
        return out;
    }
}
