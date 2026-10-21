package net.rustclear.mod.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.rustclear.mod.Protocol;
import net.rustclear.mod.RustclearMod;

/**
 * The host's copy of each player's real SkyBlock inventory (SkyblockImport, read by their own
 * game through the lobby): the kit a party on "each player's Hypixel stats" hands them instead
 * of their Kit editor's, and their Ender Chest pages and backpacks for the Storage menu (the
 * profile's Ender Chest pages are its unlocked ones: "Ender Chest (p/N)" and the Locked Pages). A
 * guest's game sends it as one {@code rustclear:skyblock} "kit" message and then one "page"
 * message per storage page; it lives in memory only and goes when they leave.
 *
 * Storage is a copy, like the kit: each dungeon's loadout puts it back as it was read, and what
 * was taken out goes with the rest of the starting items.
 */
public final class SkyblockData {
    /** Most storage pages a profile has: 9 Ender Chest pages and 18 backpacks. */
    static final int MAX_PAGES = 27;

    private static final class State {
        final Kit kit;
        final int expected;
        final List<SkyblockImport.Page> pages = new ArrayList<>();
        /** Each page's items as stacks once opened since the last loadout (null: not yet). */
        final List<ItemStack[]> stacks = new ArrayList<>();

        State(Kit kit, int expected) {
            this.kit = kit;
            this.expected = expected;
        }
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    /** One import (its "kit" message) per five seconds from each player. */
    private static final Throttle IMPORTS = new Throttle(100);

    private SkyblockData() {
    }

    /** [player]'s kit from their SkyBlock inventory, or null when their game hasn't sent one. */
    static Kit kit(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        return s == null ? null : s.kit;
    }

    /** One message from [player]'s game (Protocol.Skyblock). */
    static void receive(ServerPlayer player, String json) {
        JsonObject m;
        try {
            m = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String part = m.has("part") ? m.get("part").getAsString() : "";
        String name = player.getName().getString();
        if (part.equals("kit")) {
            if (!IMPORTS.allow(player)) {
                return;
            }
            Kit kit = m.has("kit") && m.get("kit").isJsonObject() ? Kit.parse(m.get("kit").toString()) : null;
            int pages = m.has("pages") ? Math.max(0, Math.min(MAX_PAGES, m.get("pages").getAsInt())) : 0;
            if (kit == null) {
                STATES.remove(player.getUUID());
                return;
            }
            STATES.put(player.getUUID(), new State(kit.sanitized(KitItems::kind,
                why -> RustclearMod.LOGGER.debug("{}'s SkyBlock kit: {}", name, why)), pages));
            if (EngineServer.settings().levelsFromHypixel()) {
                EngineServer.reequip(player);
            }
        } else if (part.equals("page")) {
            State s = STATES.get(player.getUUID());
            if (s == null || s.pages.size() >= s.expected || !m.has("page") || !m.get("page").isJsonObject()) {
                return;
            }
            SkyblockImport.Page page;
            try {
                page = Protocol.GSON.fromJson(m.get("page"), SkyblockImport.Page.class);
            } catch (RuntimeException e) {
                return;
            }
            s.pages.add(checked(page, name));
            s.stacks.add(null);
        }
    }

    private static SkyblockImport.Page checked(SkyblockImport.Page page, String name) {
        SkyblockImport.Page out = new SkyblockImport.Page();
        out.title = page.title == null ? "Storage" : page.title.substring(0, Math.min(32, page.title.length()));
        out.size = Math.max(9, Math.min(SkyblockImport.PAGE, page.size));
        if (page.slots != null) {
            page.slots.forEach((key, e) -> {
                int slot;
                try {
                    slot = Integer.parseInt(key);
                } catch (NumberFormatException ex) {
                    return;
                }
                Kit.Entry ok = slot >= 0 && slot < out.size && e != null
                    ? Kit.checked(e, null, KitItems::kind, why -> RustclearMod.LOGGER.debug("{}'s storage: {}", name, why)) : null;
                if (ok != null) {
                    out.slots.put(Integer.toString(slot), ok);
                }
            });
        }
        return out;
    }

    /** [player]'s storage pages as read (empty: none). */
    static List<SkyblockImport.Page> pages(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        return s == null ? List.of() : s.pages;
    }

    /** The index of [player]'s page titled "[prefix] [n]" ("Ender Chest Page 3", "Backpack Slot 1"), or -1. */
    static int find(ServerPlayer player, String prefix, int n) {
        List<SkyblockImport.Page> pages = pages(player);
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).title.equals(prefix + " " + n)) {
                return i;
            }
        }
        return -1;
    }

    /** The indexes of [player]'s pages titled "[prefix] <n>", in the order read (by n). */
    static List<Integer> book(ServerPlayer player, String prefix) {
        List<SkyblockImport.Page> pages = pages(player);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).title.startsWith(prefix + " ")) {
                out.add(i);
            }
        }
        return out;
    }

    /** Page [index]'s items, made into stacks the first time it's opened since the last loadout. */
    static ItemStack[] stacks(ServerPlayer player, int index) {
        State s = STATES.get(player.getUUID());
        if (s == null || index < 0 || index >= s.pages.size()) {
            return null;
        }
        ItemStack[] out = s.stacks.get(index);
        if (out == null) {
            SkyblockImport.Page page = s.pages.get(index);
            out = new ItemStack[page.size];
            int cata = PlayerStats.profile(player).cataLevel();
            boolean master = EngineServer.settings().floorOrDefault().master();
            for (int i = 0; i < out.length; i++) {
                Kit.Entry e = page.slots.get(Integer.toString(i));
                ItemStack stack = e == null ? null : KitItems.stack(e, cata, master);
                if (stack != null) {
                    KitItems.mark(stack);
                }
                out[i] = stack == null ? ItemStack.EMPTY : stack;
            }
            s.stacks.set(index, out);
        }
        return out;
    }

    /**
     * A new loadout: storage goes back to what was read. A page left open is closed first, so its
     * moves can't land in the old copy.
     */
    static void loadoutGiven(ServerPlayer player) {
        if (player.containerMenu instanceof StoragePage) {
            player.closeContainer();
        }
        State s = STATES.get(player.getUUID());
        if (s != null) {
            s.stacks.replaceAll(x -> null);
        }
    }

    static void forget(UUID id) {
        STATES.remove(id);
        IMPORTS.forget(id);
    }

    static void clear() {
        STATES.clear();
        IMPORTS.clear();
    }
}
