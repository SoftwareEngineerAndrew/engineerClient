package net.rustclear.mod.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.Enchantments;
import net.rustclear.mod.Floor;
import net.rustclear.mod.Protocol;
import net.rustclear.mod.RustclearMod;

/**
 * [Kit]s as items. The kit file lives on each player's own computer; a guest's is sent to the host
 * on joining and on every save, and the host keeps it in memory per player, as HotbarLayout does.
 * A dungeon with a kit for a player hands out exactly that kit in place of the default starting
 * items and saved hotbar.
 *
 * Items the engine already has ([EngineItem]: the Hyperion, Terminator, Dungeonbreaker...) come as
 * that engine item, as /rcitem gives them, so their engine abilities keep working; their name and
 * tooltip show the kit's stars and scrolls.
 */
public final class KitItems {
    private static final String CHEST = "CHEST";
    /** Custom data flag on everything a kit hands out, so the next dungeon can take it back. */
    private static final String TAG = "rustclear_kit";
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Each player's kit as last received (or, for the host, read), by player; empty: none. */
    private static final Map<UUID, Optional<Kit>> CACHE = new ConcurrentHashMap<>();

    private KitItems() {
    }

    // ---------------------------------------------------------------- ids

    private static EngineItem engineByName(String id) {
        for (EngineItem item : EngineItem.values()) {
            if (item.name().equals(id)) {
                return item;
            }
        }
        return null;
    }

    /** The engine item [id] gives, or null for a data item (or CHEST, or an unknown id). */
    public static EngineItem engineItem(String id) {
        EngineItem engine = engineByName(id);
        if (engine != null) {
            return engine;
        }
        ItemDefs.Def def = ItemDefs.get().def(id);
        return def == null ? null : ItemCommand.engineItem(def);
    }

    /** The item list's entry for [id] (an engine item's own, when it has one), or null. */
    public static ItemDefs.Def def(String id) {
        EngineItem engine = engineByName(id);
        return ItemDefs.get().def(engine != null ? engine.hypixelId() : id);
    }

    /** Whether [def] has stars, a tier and quality, or scrolls to set. */
    public static boolean upgradable(ItemDefs.Def def) {
        return def != null && (hasStars(def) || def.isTiered() || def.takesScrolls());
    }

    public static boolean hasStars(ItemDefs.Def def) {
        return def.starCosts != null && def.starCosts.length > 0;
    }

    /** The armour slot key an item of [type] is worn in, or "" for anything else. */
    public static String armorKey(String type) {
        return switch (type) {
            case "HELMET" -> "head";
            case "CHESTPLATE" -> "chest";
            case "LEGGINGS" -> "legs";
            case "BOOTS" -> "feet";
            default -> "";
        };
    }

    /** The equipment slot key an item of [type] is worn in, or "" for anything else. */
    public static String equipmentKey(String type) {
        return switch (type == null ? "" : type) {
            case "NECKLACE" -> "necklace";
            case "CLOAK" -> "cloak";
            case "BELT" -> "belt";
            case "GLOVES", "BRACELET" -> "gloves";
            default -> "";
        };
    }

    /** For [Kit.sanitized]: null for an unknown id, the armour or equipment key for those, "" for anything else. */
    public static String kind(String id) {
        if (CHEST.equals(id)) {
            return "";
        }
        EngineItem engine = engineByName(id);
        if (engine == EngineItem.DEPTH_STRIDER_BOOTS) {
            return "feet";
        }
        ItemDefs.Def def = def(id);
        if (def == null) {
            return engine != null ? "" : null;
        }
        String armor = armorKey(def.type);
        return armor.isEmpty() ? equipmentKey(def.type) : armor;
    }

    /** [def] at its best, the way the picker hands it out. */
    public static Kit.Entry best(ItemDefs.Def def) {
        ItemDefs.Instance best = DataItems.best(def, ItemStats.MAX_TIER);
        return new Kit.Entry(def.id, best.stars(), best.masterStars(), best.tier(), best.quality(), best.scrolls());
    }

    /** [entry]'s upgrades on [def], keeping only what [def] can take. */
    public static ItemDefs.Instance instance(ItemDefs.Def def, Kit.Entry entry) {
        boolean stars = hasStars(def);
        return new ItemDefs.Instance(def, stars ? entry.stars : 0, stars ? entry.masterStars : 0, entry.tier, entry.quality,
            def.takesScrolls() ? entry.scrolls : List.of(), entry.bonus, entry.reforge, entry.recomb, entry.mods());
    }

    /** The starting items as a kit: EngineItem.defaultSlots, the Depth Strider boots on, holding slot 1. */
    public static Kit defaults() {
        Kit kit = new Kit();
        for (Map.Entry<String, Integer> e : EngineItem.defaultSlots().entrySet()) {
            kit.slots.put(Kit.key(e.getValue()), bestRecorded(e.getKey()));
        }
        kit.slots.put("feet", plain(EngineItem.DEPTH_STRIDER_BOOTS.name()));
        return kit;
    }

    /** Item [id] at its best (stars, master stars, scrolls) with the recorded top players' mods on it. */
    public static Kit.Entry bestRecorded(String id) {
        ItemDefs.Def def = def(id);
        Kit.Entry entry = def != null && upgradable(def) ? withId(best(def), id) : plain(id);
        recordedMods(entry);
        return entry;
    }

    /** kit_mods.json: each kit item's mods as the recorded top players have them. */
    private static com.google.gson.JsonObject recorded;

    /** [entry] gets its item's recorded enchantments, reforge, potato books and Art of War / Peace. */
    private static void recordedMods(Kit.Entry entry) {
        if (recorded == null) {
            try (var in = KitItems.class.getResourceAsStream("/assets/rustclear/engine/kit_mods.json")) {
                recorded = in == null ? new com.google.gson.JsonObject()
                    : com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (java.io.IOException | RuntimeException e) {
                recorded = new com.google.gson.JsonObject();
            }
        }
        if (!recorded.has(entry.id)) {
            return;
        }
        var m = recorded.getAsJsonObject(entry.id);
        if (m.has("modifier")) {
            entry.modifier = m.get("modifier").getAsString();
        }
        entry.potatoBooks = m.has("hot_potato_count") ? m.get("hot_potato_count").getAsInt() : 0;
        entry.artOfWar = m.has("art_of_war_count") && m.get("art_of_war_count").getAsInt() > 0;
        entry.artOfPeace = m.has("artOfPeaceApplied") && m.get("artOfPeaceApplied").getAsInt() > 0;
        if (m.has("enchantments")) {
            entry.enchants = new java.util.LinkedHashMap<>();
            for (var en : m.getAsJsonObject("enchantments").entrySet()) {
                entry.enchants.put(en.getKey(), en.getValue().getAsInt());
            }
        }
    }

    private static Kit.Entry withId(Kit.Entry entry, String id) {
        entry.id = id;
        return entry;
    }

    private static Kit.Entry plain(String id) {
        return new Kit.Entry(id, 0, 0, ItemStats.MAX_TIER, ItemStats.MAX_QUALITY, List.of());
    }

    // ---------------------------------------------------------------- stacks

    /** [entry] as an ItemStack for a player at Catacombs [cata] on a [masterMode] floor, or null for an unknown id. */
    public static ItemStack stack(Kit.Entry entry, int cata, boolean masterMode) {
        if (CHEST.equals(entry.id)) {
            return new ItemStack(Items.CHEST, 64);
        }
        ItemDefs.Def def = def(entry.id);
        EngineItem engine = engineItem(entry.id);
        if (engine == null) {
            return def == null ? null : DataItems.stack(instance(def, entry), cata, masterMode);
        }
        ItemStack stack = engine == EngineItem.DUNGEONBREAKER ? ItemSystem.dungeonbreakerStack(ItemSystem.MAX_CHARGES) : engine.stack();
        if (upgradable(def)) {
            // The engine's own item, named and described as the kit has it.
            ItemDefs.Instance item = instance(def, entry);
            stack.set(DataComponents.CUSTOM_NAME, EngineItems.text(ItemDefs.displayName(item)));
            stack.set(DataComponents.LORE, new ItemLore(EngineItems.lines(ItemDefs.lore(item, cata, masterMode).toArray(String[]::new))));
            stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true));
            if (def.glowing || item.witherImpact()) {
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (item.stars() > 0) {
                tag.putInt("upgrade_level", item.stars() + item.masterStars());
            }
            if (!item.scrolls().isEmpty()) {
                ListTag scrolls = new ListTag();
                item.scrolls().forEach(s -> scrolls.add(StringTag.valueOf(s)));
                tag.put("ability_scroll", scrolls);
            }
            DataItems.putExtras(tag, item);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        return stack;
    }

    static void mark(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putBoolean(TAG, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** A starting item from a kit or the default loadout, which the next dungeon's hands out again. */
    private static boolean starting(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (EngineItem.of(stack) != null || stack.is(Items.CHEST)) {
            return true;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(TAG, false);
    }

    // ---------------------------------------------------------------- this player's file (client)

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("rustclear").resolve("kit.json");
    }

    /** This player's saved kit, or null when they have none. */
    public static Kit load() {
        return Kit.read(file(), KitItems::kind, RustclearMod.LOGGER::warn);
    }

    /** Saves [kit] and, inside a Rustclear world, sends it to the host, who hands it out right away. */
    public static void save(Kit kit) throws java.io.IOException {
        kit.sanitized(KitItems::kind, RustclearMod.LOGGER::warn).write(file());
        if (Engine.isEngineClient()) {
            send();
        }
    }

    /** Sends the saved kit (if any) to the Rustclear world this client is in. */
    public static void send() {
        Kit kit = load();
        if (kit != null && ClientPlayNetworking.canSend(Protocol.Kit.TYPE)) {
            ClientPlayNetworking.send(new Protocol.Kit(kit.toJson(false)));
        }
    }

    // ---------------------------------------------------------------- the host

    /**
     * [player]'s kit: in a party on "each player's Hypixel stats", what their SkyBlock inventory
     * holds (SkyblockData) once their game has read it; else what their game sent, or for the host
     * before that, their own file. Null: none.
     */
    static Kit forPlayer(ServerPlayer player) {

        Kit skyblock = EngineServer.settings().levelsFromHypixel() ? SkyblockData.kit(player) : null;
        if (skyblock != null) {
            return skyblock;
        }
        return CACHE.computeIfAbsent(player.getUUID(),
            id -> Optional.ofNullable(EngineServer.isHost(player) ? load() : null)).orElse(null);
    }

    /** A kit from [player]'s game, checked against the host's item list; handed out right away. */
    static void receive(ServerPlayer player, String json) {
        Kit kit = Kit.parse(json);
        if (kit == null) {
            RustclearMod.LOGGER.debug("Bad Rustclear kit from {}", player.getName().getString());
            return;
        }
        CACHE.put(player.getUUID(), Optional.of(kit.sanitized(KitItems::kind,
            why -> RustclearMod.LOGGER.debug("{}'s kit: {}", player.getName().getString(), why))));
        EngineServer.reequip(player);
    }

    static void forget(UUID id) {
        CACHE.remove(id);
    }

    static void clear() {
        CACHE.clear();
    }

    /**
     * Hands out [kit]: last dungeon's starting items go (wherever they were moved), then each kit
     * item goes in its exact slot and the armour is put on. Anything else in those slots (a key, a
     * /rcitem item) moves to a free slot rather than away. A kit with equipment sets all four
     * equipment slots (empty where it has none), and one with pets fills the pet menu with them.
     */
    static void give(ServerPlayer player, Kit kit) {
        Floor floor = EngineServer.settings().floorOrDefault();
        int cata = PlayerStats.profile(player).cataLevel();
        var inv = player.getInventory();
        for (int i = 0; i < Kit.SLOTS; i++) {
            if (starting(inv.getItem(i))) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (starting(player.getItemBySlot(slot))) {
                player.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
        boolean equipment = kit.slots.keySet().stream().anyMatch(Kit::isEquipment);
        if (equipment) {
            for (Equipment.Slot slot : Equipment.Slot.values()) {
                Kit.Entry e = kit.slots.get(Kit.EQUIPMENT.get(slot.ordinal()));
                Equipment.put(player, slot, e == null ? null : e.copy());
            }
        }
        if (!kit.pets.isEmpty()) {
            Pets.fromKit(player, kit.pets, kit.activePet);
        }
        List<ItemStack> displaced = new ArrayList<>();
        for (Map.Entry<String, Kit.Entry> e : kit.slots.entrySet()) {
            if (Kit.isEquipment(e.getKey())) {
                continue;
            }
            ItemStack stack = stack(e.getValue(), cata, floor.master());
            if (stack == null) {
                continue;
            }
            mark(stack);
            if (EngineItem.of(stack) == EngineItem.DEPTH_STRIDER_BOOTS) {
                stack.enchant(player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.DEPTH_STRIDER), 3);
            }
            int armor = Kit.ARMOR.indexOf(e.getKey());
            ItemStack there;
            if (armor >= 0) {
                there = player.getItemBySlot(ARMOR_SLOTS[armor]);
                player.setItemSlot(ARMOR_SLOTS[armor], stack);
            } else {
                int slot = Integer.parseInt(e.getKey());
                there = inv.getItem(slot);
                inv.setItem(slot, stack);
            }
            if (!there.isEmpty()) {
                displaced.add(there);
            }
        }
        for (ItemStack stack : displaced) {
            int free = -1;
            for (int i = 0; i < Kit.SLOTS && free < 0; i++) {
                if (i != Kit.MAP_SLOT && inv.getItem(i).isEmpty() && !kit.slots.containsKey(Kit.key(i))) {
                    free = i;
                }
            }
            if (free >= 0) {
                inv.setItem(free, stack);
            } else {
                player.drop(stack, false, Prediction.SERVER_ONLY);
            }
        }
        HotbarLayout.select(player, kit.selected);
        player.inventoryMenu.broadcastChanges();
    }
}
