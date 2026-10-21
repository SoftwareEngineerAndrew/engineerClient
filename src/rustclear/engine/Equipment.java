package net.rustclear.mod.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.rustclear.mod.RustclearMod;

/**
 * Hypixel's four equipment slots (necklace, cloak, belt, gloves or bracelet), which aren't
 * vanilla slots: what each player wears is kept by the host, in config/rustclear/equipment/
 * &lt;uuid&gt;.json, and put on and taken off in the stats page ("Your Equipment and Stats",
 * StatsMenu), as on Hypixel. Only the item list's equipment can go in, each in its own slot.
 */
public final class Equipment {
    /** The slots in Hypixel's order and the item types each takes. */
    public enum Slot {
        NECKLACE("Necklace", "NECKLACE"),
        CLOAK("Cloak", "CLOAK"),
        BELT("Belt", "BELT"),
        GLOVES("Gloves / Bracelet", "GLOVES", "BRACELET");

        public final String title;
        final List<String> types;

        Slot(String title, String... types) {
            this.title = title;
            this.types = List.of(types);
        }

        /** The slot [def] goes in, or null when it isn't equipment. */
        public static Slot of(ItemDefs.Def def) {
            if (def == null || def.type == null) {
                return null;
            }
            for (Slot s : values()) {
                if (s.types.contains(def.type)) {
                    return s;
                }
            }
            return null;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Each player's worn equipment by slot name, as kit entries (id, stars...). */
    private static final Map<UUID, Map<String, Kit.Entry>> WORN = new ConcurrentHashMap<>();

    private Equipment() {
    }

    private static Path file(UUID id) {
        return FabricLoader.getInstance().getConfigDir().resolve("rustclear").resolve("equipment").resolve(id + ".json");
    }

    private static Map<String, Kit.Entry> of(UUID id) {
        return WORN.computeIfAbsent(id, k -> {
            Map<String, Kit.Entry> map = new LinkedHashMap<>();
            Path f = file(k);
            if (Files.exists(f)) {
                try {
                    Map<String, Kit.Entry> read = GSON.fromJson(Files.readString(f),
                        new com.google.gson.reflect.TypeToken<Map<String, Kit.Entry>>() { }.getType());
                    if (read != null) {
                        read.forEach((slot, e) -> {
                            if (e != null && e.id != null) {
                                map.put(slot, e);
                            }
                        });
                    }
                } catch (IOException | RuntimeException e) {
                    RustclearMod.LOGGER.warn("Couldn't read {}: {}", f, e.toString());
                }
            }
            return map;
        });
    }

    private static void save(UUID id) {
        Path f = file(id);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(of(id)));
        } catch (IOException e) {
            RustclearMod.LOGGER.warn("Couldn't save {}: {}", f, e.toString());
        }
    }

    /** What [player] wears in [slot], as an item (empty: nothing). */
    public static ItemStack get(ServerPlayer player, Slot slot) {
        Kit.Entry e = of(player.getUUID()).get(slot.name());
        if (e == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = KitItems.stack(e, PlayerStats.profile(player).cataLevel(), EngineServer.settings().floorOrDefault().master());
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /** What [player] wears as item list entries with their upgrades, for their stats (no ItemStacks built). */
    public static List<ItemDefs.Instance> instances(ServerPlayer player) {
        List<ItemDefs.Instance> out = new ArrayList<>();
        for (Kit.Entry e : of(player.getUUID()).values()) {
            ItemDefs.Def def = ItemDefs.get().def(e.id);
            if (def != null) {
                out.add(KitItems.instance(def, e));
            }
        }
        return out;
    }

    /** Everything [player] wears, slot by slot. */
    public static List<ItemStack> worn(ServerPlayer player) {
        List<ItemStack> out = new ArrayList<>();
        for (Slot s : Slot.values()) {
            ItemStack stack = get(player, s);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    /**
     * Puts [stack] on in its slot. Returns what was there (empty: nothing), or null when [stack]
     * isn't equipment.
     */
    public static ItemStack equip(ServerPlayer player, ItemStack stack) {
        ItemDefs.Instance item = DataItems.of(stack);
        Slot slot = item == null ? null : Slot.of(item.def());
        if (slot == null) {
            return null;
        }
        ItemStack was = get(player, slot);
        of(player.getUUID()).put(slot.name(), Kit.Entry.of(item));
        save(player.getUUID());
        return was;
    }

    /** Puts [entry] on in [slot] (null: takes off what's there), as a kit does. */
    public static void put(ServerPlayer player, Slot slot, Kit.Entry entry) {
        if (entry == null) {
            of(player.getUUID()).remove(slot.name());
        } else {
            of(player.getUUID()).put(slot.name(), entry);
        }
        save(player.getUUID());
    }

    /** Takes off what [player] wears in [slot] and returns it (empty: nothing). */
    public static ItemStack unequip(ServerPlayer player, Slot slot) {
        ItemStack was = get(player, slot);
        if (of(player.getUUID()).remove(slot.name()) != null) {
            save(player.getUUID());
        }
        return was;
    }

    static void forget(UUID id) {
        WORN.remove(id);
    }

    static void clear() {
        WORN.clear();
    }
}
