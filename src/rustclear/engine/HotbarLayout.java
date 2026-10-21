package net.rustclear.mod.engine;

import net.minecraft.util.Prediction;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.rustclear.mod.RustclearMod;

/**
 * Where the starting items go (like engineerClient's P3 Sim hotbar): "Save Hotbar" records which
 * slot each item is in right now and the slot you hold, in config/rustclear/hotbar.json, and every
 * new dungeon lays them out that way. Each player has their own. "Default Hotbar" forgets it and puts the items back where
 * server.rs has them. Slot 9 always holds the run's map.
 */
public final class HotbarLayout {
    private static final int MAP_SLOT = 8;
    private static final int DEFAULT_SELECTED = 0;
    private static final String CHEST = "CHEST";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Item id (an EngineItem name, or CHEST) to inventory slot, and the hotbar slot held. */
    private static final class Saved {
        Map<String, Integer> slots = new LinkedHashMap<>();
        int selected = DEFAULT_SELECTED;
    }

    /** Each player's saved layout as last read or written (null: none saved), by player. */
    private static final Map<UUID, Optional<Saved>> CACHE = new ConcurrentHashMap<>();

    private HotbarLayout() {
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("rustclear");
    }

    /**
     * Where [player]'s layout lives: the host's own in hotbar.json as before, each guest's in
     * hotbars/<uuid>.json on the host, so a guest's Save never moves anyone else's items.
     */
    private static Path file(ServerPlayer player) {
        if (player.level().getServer().isSingleplayerOwner(player.nameAndId())) {
            return dir().resolve("hotbar.json");
        }
        return dir().resolve("hotbars").resolve(player.getUUID() + ".json");
    }

    private static Saved saved(ServerPlayer player) {
        return CACHE.computeIfAbsent(player.getUUID(), id -> {
            try {
                Path f = file(player);
                return Optional.ofNullable(Files.exists(f) ? GSON.fromJson(Files.readString(f), Saved.class) : null);
            } catch (Exception e) {
                RustclearMod.LOGGER.warn("Couldn't read a saved hotbar", e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Drops [id]'s cached layout (they left; it is read again from disk if they come back). */
    static void forget(UUID id) {
        CACHE.remove(id);
    }

    /** The id the layout knows [stack] by, or null for anything that isn't a starting item. */
    private static String idOf(ItemStack stack) {
        EngineItem item = EngineItem.of(stack);
        if (item != null) {
            return item.name();
        }
        return !stack.isEmpty() && stack.is(Items.CHEST) ? CHEST : null;
    }

    /**
     * Places [defaults] (each starting item at its default slot) into [player]'s inventory, where the
     * saved layout has them, and selects the saved slot.
     */
    static void give(ServerPlayer player, List<Map.Entry<Integer, ItemStack>> defaults) {
        // Fresh copies replace the old ones wherever they were moved to, so nothing is carried twice.
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (idOf(inv.getItem(i)) != null) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        place(player, defaults, saved(player));
    }

    private static void place(ServerPlayer player, List<Map.Entry<Integer, ItemStack>> items, Saved s) {
        var inv = player.getInventory();
        Set<Integer> taken = new HashSet<>();
        taken.add(MAP_SLOT);
        List<Map.Entry<Integer, ItemStack>> left = new ArrayList<>();
        List<Map.Entry<Integer, ItemStack>> placed = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> e : items) {
            Integer want = s == null ? null : s.slots.get(idOf(e.getValue()));
            if (want != null && want >= 0 && want < 36 && taken.add(want)) {
                placed.add(Map.entry(want, e.getValue()));
            } else {
                left.add(e);
            }
        }
        for (Map.Entry<Integer, ItemStack> e : left) {
            int at = e.getKey();
            if (taken.contains(at)) {
                at = 0;
                while (taken.contains(at)) {
                    at++;
                }
            }
            taken.add(at);
            placed.add(Map.entry(at, e.getValue()));
        }
        // Anything else in those slots (a dungeon item from /rcitem, a key) moves to a free slot, not away.
        List<ItemStack> displaced = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> e : placed) {
            ItemStack there = inv.getItem(e.getKey());
            if (!there.isEmpty() && there != e.getValue()) {
                displaced.add(there);
            }
            inv.setItem(e.getKey(), e.getValue());
        }
        for (ItemStack stack : displaced) {
            int free = -1;
            for (int i = 0; i < 36 && free < 0; i++) {
                if (i != MAP_SLOT && inv.getItem(i).isEmpty()) {
                    free = i;
                }
            }
            if (free >= 0) {
                inv.setItem(free, stack);
            } else {
                player.drop(stack, false, Prediction.SERVER_ONLY);
            }
        }
        select(player, s == null ? DEFAULT_SELECTED : s.selected);
    }

    static void select(ServerPlayer player, int slot) {
        int held = Math.max(0, Math.min(8, slot));
        player.getInventory().setSelectedSlot(held);
        player.connection.send(new ClientboundSetHeldSlotPacket(held));
    }

    /** Save Hotbar: where [player]'s starting items are now. Returns what to tell them. */
    public static String save(ServerPlayer player) {
        var inv = player.getInventory();
        Saved s = new Saved();
        for (int i = 0; i < 36; i++) {
            String id = idOf(inv.getItem(i));
            if (id != null) {
                s.slots.putIfAbsent(id, i);
            }
        }
        if (s.slots.isEmpty()) {
            return "§cNothing to save: none of the dungeon items are in your inventory.";
        }
        s.selected = inv.getSelectedSlot();
        try {
            Path f = file(player);
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(s));
        } catch (Exception e) {
            RustclearMod.LOGGER.warn("Couldn't save the hotbar", e);
            return "§cCouldn't save your hotbar: " + e.getMessage();
        }
        CACHE.put(player.getUUID(), Optional.of(s));
        return "§aSaved your hotbar §7(" + s.slots.size() + " items, holding slot " + (s.selected + 1) + "). Every new dungeon lays it out this way.";
    }

    /** Default Hotbar: forgets the saved layout and moves [player]'s starting items back to their default slots now. */
    public static String reset(ServerPlayer player) {
        try {
            Files.deleteIfExists(file(player));
        } catch (Exception e) {
            RustclearMod.LOGGER.warn("Couldn't delete the saved hotbar", e);
        }
        CACHE.put(player.getUUID(), Optional.empty());
        Map<String, Integer> defaults = EngineItem.defaultSlots();
        var inv = player.getInventory();
        List<Map.Entry<Integer, ItemStack>> items = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            String id = idOf(stack);
            Integer slot = id == null ? null : defaults.get(id);
            if (slot != null) {
                items.add(Map.entry(slot, stack));
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        // Anything else sitting in a default slot (a key, say) moves out of the way first.
        List<ItemStack> displaced = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> e : items) {
            ItemStack there = inv.getItem(e.getKey());
            if (!there.isEmpty()) {
                displaced.add(there);
                inv.setItem(e.getKey(), ItemStack.EMPTY);
            }
        }
        place(player, items, null);
        for (ItemStack stack : displaced) {
            if (!inv.add(stack)) {
                player.drop(stack, false, Prediction.SERVER_ONLY);
            }
        }
        player.inventoryMenu.broadcastChanges();
        return "§aBack to the default hotbar.";
    }
}
