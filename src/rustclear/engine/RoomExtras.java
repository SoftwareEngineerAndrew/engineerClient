package net.rustclear.mod.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-room extras the jar carries next to the room blocks, by room name, all in the room data's
 * own coordinates (y as recorded): secrets (secrets_loader.rs), lever-locked chests
 * (locked_chests.rs) and levers that open walls (levers.rs).
 */
public final class RoomExtras {
    /** The secrets.json categories, in the order secrets_loader.rs loads them. */
    public enum SecretKind {
        CHEST("rchest", 8.0, false),
        ESSENCE("ress", 8.0, false),
        BAT_SPAWN("batsp", 8.0, false),
        BAT_DIE("batdie", 8.0, false),
        ITEM("itemsp", 8.0, false),
        SECRET_CHEST("schest", 4.0, true),
        SECRET_ESSENCE("sess", 4.0, true);

        final String key;
        final double radius;
        /** Appears as soon as the room is entered, instead of when a player comes near. */
        public final boolean onEntry;

        SecretKind(String key, double radius, boolean onEntry) {
            this.key = key;
            this.radius = radius;
            this.onEntry = onEntry;
        }

        public boolean isChest() {
            return this == CHEST || this == SECRET_CHEST;
        }

        public boolean isChestOrEssence() {
            return this == CHEST || this == SECRET_CHEST || this == ESSENCE || this == SECRET_ESSENCE;
        }

        public boolean isEssence() {
            return this == ESSENCE || this == SECRET_ESSENCE;
        }
    }

    public record SecretEntry(SecretKind kind, String id, int x, int y, int z, Facing facing, double radius) {}

    public record LockedChest(int x, int y, int z, Facing facing, int leverX, int leverY, int leverZ) {}

    public record Lever(int x, int y, int z, List<int[]> blocks) {}

    private static final String ROOT = "/assets/rustclear/engine/";
    private static RoomExtras instance;

    private final Map<String, List<SecretEntry>> secrets = new HashMap<>();
    private final Map<String, List<LockedChest>> locked = new HashMap<>();
    private final Map<String, List<Lever>> levers = new HashMap<>();

    public static synchronized RoomExtras get() {
        if (instance == null) {
            instance = new RoomExtras();
        }
        return instance;
    }

    private RoomExtras() {
        for (Map.Entry<String, JsonElement> room : read("secrets.json").getAsJsonObject("rooms").entrySet()) {
            List<SecretEntry> list = new ArrayList<>();
            JsonObject kinds = room.getValue().getAsJsonObject();
            for (SecretKind kind : SecretKind.values()) {
                JsonArray entries = kinds.getAsJsonArray(kind.key);
                if (entries == null) {
                    continue;
                }
                for (JsonElement e : entries) {
                    JsonObject o = e.getAsJsonObject();
                    String id = o.has("id") ? o.get("id").getAsString() : "";
                    list.add(new SecretEntry(kind, id, o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                        facing(o.has("facing") ? o.get("facing").getAsString() : null),
                        radius(room.getKey(), id, kind.radius)));
                }
            }
            secrets.put(room.getKey(), list);
        }
        for (Map.Entry<String, JsonElement> room : read("locked.json").getAsJsonObject("rooms").entrySet()) {
            List<LockedChest> list = new ArrayList<>();
            for (JsonElement e : room.getValue().getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                JsonArray lever = o.getAsJsonArray("lever");
                list.add(new LockedChest(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                    facing(o.get("facing").getAsString()), lever.get(0).getAsInt(), lever.get(1).getAsInt(), lever.get(2).getAsInt()));
            }
            locked.put(room.getKey(), list);
        }
        for (Map.Entry<String, JsonElement> room : read("levers.json").getAsJsonObject("rooms").entrySet()) {
            JsonArray array = room.getValue().getAsJsonObject().getAsJsonArray("levers");
            if (array == null) {
                continue;
            }
            List<Lever> list = new ArrayList<>();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                JsonArray lever = o.getAsJsonArray("lever");
                List<int[]> blocks = new ArrayList<>();
                for (JsonElement b : o.getAsJsonArray("blocks")) {
                    JsonArray xyz = b.getAsJsonArray();
                    blocks.add(new int[] {xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()});
                }
                list.add(new Lever(lever.get(0).getAsInt(), lever.get(1).getAsInt(), lever.get(2).getAsInt(), blocks));
            }
            levers.put(room.getKey(), list);
        }
    }

    public List<SecretEntry> secrets(String room) {
        return secrets.getOrDefault(room, List.of());
    }

    public List<LockedChest> lockedChests(String room) {
        return locked.getOrDefault(room, List.of());
    }

    public List<Lever> levers(String room) {
        return levers.getOrDefault(room, List.of());
    }

    /** facing_string_to_direction: unknown or missing is north. */
    private static Facing facing(String name) {
        if (name == null) {
            return Facing.NORTH;
        }
        return switch (name.toLowerCase()) {
            case "east" -> Facing.EAST;
            case "south" -> Facing.SOUTH;
            case "west" -> Facing.WEST;
            default -> Facing.NORTH;
        };
    }

    /** secret_spawn_boxes.rs: the few secrets that appear from further away than their kind's default. */
    private static double radius(String room, String id, double fallback) {
        if (room.equals("Red Green") && id.equals("s2")) {
            return 18.0;
        }
        if (room.equals("Supertall") && id.equals("s1")) {
            return 11.0;
        }
        return fallback;
    }

    private static JsonObject read(String file) {
        try (InputStream in = RoomExtras.class.getResourceAsStream(ROOT + file)) {
            if (in == null) {
                throw new IOException("No " + ROOT + file);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Rustclear's dungeon data is missing from the jar", e);
        }
    }
}
