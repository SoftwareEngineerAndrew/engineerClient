package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.rustclear.mod.DungeonSettings;

/**
 * Every player's stats in the Rustclear world, worked out from what they wear and hold
 * (StatBuilder) instead of fixed numbers: armour, the four equipment slots (Equipment), the held
 * item, the active pet, their class, and their Catacombs and class levels: either the same for
 * the whole party or each player's own from Hypixel, as the party leader picks (DungeonSettings
 * levels). Worked out at most once a tick per player.
 */
public final class PlayerStats {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Each player's Hypixel profile as their game read it (HypixelProfile), and the class they picked. */
    private static final Map<UUID, SkyblockProfile> HYPIXEL = new ConcurrentHashMap<>();
    private static final Map<UUID, DungeonClass> CLASSES = new ConcurrentHashMap<>();

    private record Cached(long tick, StatSheet sheet) {}

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    private PlayerStats() {
    }

    /** From a player's settings message: (when they could read it) their Hypixel profile. */
    static void receive(ServerPlayer player, DungeonSettings from) {
        if (from.hypixel != null) {
            HYPIXEL.put(player.getUUID(), sanitized(from.hypixel));
        }
        CACHE.remove(player.getUUID());
        DungeonOrb.refresh(player);
    }

    /** [player] picked [c] on the party screen (or Force start picked it for them). */
    static void pick(ServerPlayer player, DungeonClass c) {
        CLASSES.put(player.getUUID(), c);
        CACHE.remove(player.getUUID());
        DungeonOrb.refresh(player);
    }

    /** The class [player] picked, or null when they haven't (classOf then falls back). */
    public static DungeonClass picked(ServerPlayer player) {
        return CLASSES.get(player.getUUID());
    }

    /** Numbers from a guest's game stay within what a real profile can have. */
    private static SkyblockProfile sanitized(SkyblockProfile p) {
        SkyblockProfile out = p.copy();
        out.cata = Math.max(0, Math.min(SkyblockProfile.MAX_CATA, out.cata));
        out.accessoryPower = Math.max(0, Math.min(2500, out.accessoryPower));
        // A real profile's own Accessory Power; its accessories aren't read, so no max build and no
        // in-run doubling (OPEN: the recorder's dungeon accessories add 146).
        out.maxAccessories = false;
        out.dungeonAccessoryPower = 0;
        out.level = Math.max(0, Math.min(1000, out.level));
        out.classes.replaceAll((k, v) -> v == null ? null : Math.max(0, Math.min(DungeonClass.MAX_LEVEL, v)));
        out.skills.replaceAll((k, v) -> v == null ? null : Math.max(0, Math.min(60, v)));
        out.slayers.replaceAll((k, v) -> v == null ? null : Math.max(0, Math.min(9, v)));
        out.levelXp = Math.max(0, Math.min(99, out.levelXp));
        out.cataXp = Double.isFinite(out.cataXp) ? Math.max(0, Math.min(1e10, out.cataXp)) : 0;
        if (out.skillXp != null) {
            out.skillXp.replaceAll((k, v) -> v == null || !Double.isFinite(v) ? 0.0 : Math.max(0, Math.min(1e10, v)));
        }
        if (out.classXp != null) {
            out.classXp.replaceAll((k, v) -> v == null || !Double.isFinite(v) ? 0.0 : Math.max(0, Math.min(1e10, v)));
        }
        out.farmingCap = Math.max(0, Math.min(60, out.farmingCap));
        out.tamingCap = Math.max(0, Math.min(60, out.tamingCap));
        if (out.tuning != null) {
            out.tuning.replaceAll((k, v) -> v == null || !Double.isFinite(v) ? 0.0 : Math.max(0, Math.min(250, v)));
        }
        out.source = out.source == null ? "Hypixel" : out.source.substring(0, Math.min(64, out.source.length()));
        return out;
    }

    /** Drops [id]'s stats worked out this tick (their pet changed). */
    static void forgetCached(UUID id) {
        CACHE.remove(id);
    }

    static void forget(UUID id) {
        HYPIXEL.remove(id);
        CLASSES.remove(id);
        CACHE.remove(id);
    }

    static void clear() {
        HYPIXEL.clear();
        CLASSES.clear();
        CACHE.clear();
    }

    /** The levels [player] plays at: the party's fixed ones, or their own Hypixel profile (fixed 50s when there's none). */
    /** Whether [player] is inside a dungeon run (dungeon accessories count twice there). */
    static boolean inDungeon(ServerPlayer player) {
        return EngineServer.runOf(player) instanceof DungeonRun;
    }

    public static SkyblockProfile profile(ServerPlayer player) {
        DungeonSettings s = EngineServer.settings();
        if (s.levelsFromHypixel()) {
            SkyblockProfile own = HYPIXEL.get(player.getUUID());
            if (own != null) {
                return own;
            }
            SkyblockProfile none = SkyblockProfile.fixed(SkyblockProfile.MAX_CATA, DungeonClass.MAX_LEVEL);
            none.source = "Hypixel (not read yet: 50s for now)";
            return none;
        }
        SkyblockProfile fixed = SkyblockProfile.fixed(s.partyCataOrDefault(), s.partyClassLevelOrDefault());
        // The power stone picked in the Kit editor (on Hypixel stats, the profile's own is used).
        Kit kit = KitItems.forPlayer(player);
        fixed.power = kit == null ? null : kit.power;

        return fixed;
    }

    /** [player]'s class: the one they picked, else their Hypixel one, else Mage. */

    public static DungeonClass classOf(ServerPlayer player) {
        DungeonClass picked = CLASSES.get(player.getUUID());
        if (picked != null) {
            return picked;
        }

        SkyblockProfile own = HYPIXEL.get(player.getUUID());
        DungeonClass hypixel = own == null ? null : DungeonClass.of(own.selectedClass);
        return hypixel != null ? hypixel : DungeonClass.MAGE;
    }

    /** Whether nobody else in [player]'s world plays their class (its passives are doubled). */
    public static boolean solo(ServerPlayer player) {
        DungeonClass mine = classOf(player);
        for (var other : player.level().players()) {
            if (other != player && other instanceof ServerPlayer sp && classOf(sp) == mine) {
                return false;
            }
        }
        return true;
    }

    /** [player]'s stats right now. */
    public static StatSheet of(ServerPlayer player) {
        long tick = player.level().getGameTime();
        Cached c = CACHE.get(player.getUUID());
        if (c != null && c.tick == tick) {
            return c.sheet;
        }
        StatSheet sheet = build(player);
        CACHE.put(player.getUUID(), new Cached(tick, sheet));
        return sheet;
    }

    private static StatSheet build(ServerPlayer player) {
        List<ItemDefs.Instance> armor = new ArrayList<>();
        for (EquipmentSlot slot : ARMOR) {
            ItemDefs.Instance item = instance(player.getItemBySlot(slot));
            if (item != null) {
                armor.add(item);
            }
        }
        List<ItemDefs.Instance> equipment = Equipment.instances(player);
        ItemDefs.Instance held = instance(player.getMainHandItem());
        boolean master = EngineServer.settings().floorOrDefault().master();
        EngineRun run = EngineServer.runOf(player);
        StatBuilder.Run inRun = run instanceof DungeonRun d
            ? new StatBuilder.Run(true, master, d.blessings())
            : new StatBuilder.Run(false, master, Map.of());
        ClassAbilities abilities = run instanceof DungeonRun d ? d.abilities() : null;
        StatBuilder.Buffs buffs = abilities == null ? StatBuilder.Buffs.NONE : abilities.buffs(player);
        return StatBuilder.build(profile(player), classOf(player), solo(player), armor, equipment, held,
            Pets.stats(player), inRun, buffs);
    }

    /**
     * The dungeon item [stack] is, with its stars: a data item's own, or for one of the engine's
     * own items (the Hyperion, Terminator...) its entry in the item list, with the stars the
     * stack says or, without any, the dungeon's best the engine always hands out.
     */
    public static ItemDefs.Instance instance(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ItemDefs.Instance data = DataItems.of(stack);
        if (data != null) {
            return data;
        }
        EngineItem engine = EngineItem.of(stack);
        ItemDefs.Def def = engine == null ? null : ItemDefs.get().def(engine.hypixelId());
        if (def == null) {
            return null;
        }
        ItemDefs.Instance best = DataItems.best(def, EngineServer.settings().floorOrDefault().master() ? ItemStats.MAX_TIER : 7);
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return best;
        }
        net.minecraft.nbt.CompoundTag tag = custom.copyTag();
        int level = tag.getIntOr("upgrade_level", -1);
        ItemDefs.Instance item = level < 0 ? best
            : new ItemDefs.Instance(def, Math.min(level, ItemStats.MAX_STARS), Math.max(0, level - ItemStats.MAX_STARS),
                best.tier(), best.quality(), best.scrolls());
        return DataItems.withExtras(item, tag);
    }
}
