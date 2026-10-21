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
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * Recorded mob spawns per room (Smalldungeons spawn_data.rs, packed by tools/pack_engine_data.py
 * into mobs.json.gz): for each room, every recorded visit's mobs. A run picks one visit per room.
 */
public final class MobStore {
    /** One equipment piece: item id, its coloured name (or null), leather dye (or null), head texture (or null). */
    public record Piece(String id, String name, Integer color, String skull) {}

    /** One recorded mob (MobSpawnJson). */
    public record Spawn(String type, boolean starred, String fullName, double x, double y, double z, float yaw,
                        Map<String, Piece> equipment, String skin, String signature) {}

    /**
     * Where Hypixel spawns a room's starred mobs (mob_spots.json, from tools/routes/mob_spots.py): its miniboss spots
     * and every block a starred mob spawned on, in the room-data frame with world y. Then every block the recordings
     * saw any of the room's mobs spawn on (mob_spawns.json, tools/mobs/spawn_spots.py; y above the room's bottom) and
     * how far from them a recorded mob may stand ([spawnRadius]). Any list may be empty.
     */
    public record Spots(List<int[]> minis, List<int[]> points, List<int[]> spawns, double spawnRadius) {
        static final Spots NONE = new Spots(List.of(), List.of(), List.of(), 0);
    }

    private static final String ROOT = "/assets/rustclear/engine/";
    private static MobStore instance;

    private final Map<String, List<List<Spawn>>> rooms = new HashMap<>();
    private final Map<String, String> skins = new HashMap<>();
    private final Map<String, Spots> spots = new HashMap<>();

    public static synchronized MobStore get() {
        if (instance == null) {
            instance = new MobStore();
        }
        return instance;
    }

    private MobStore() {
        addRooms(read("mobs.json.gz", true));
        // Rooms with no recorded visit above, measured from Hypixel recordings (tools/mobs/hypixel_visits.py).
        addRooms(read("mobs_hypixel.json", false));
        for (Map.Entry<String, JsonElement> skin : read("mob_skins.json", false).entrySet()) {
            skins.put(skin.getKey(), skin.getValue().getAsString());
        }
        JsonObject site = read("mob_spots.json", false).getAsJsonObject("rooms");
        JsonObject recorded = read("mob_spawns.json", false).getAsJsonObject("rooms");
        Set<String> keys = new TreeSet<>(site.keySet());
        keys.addAll(recorded.keySet());
        for (String key : keys) {
            JsonObject o = site.has(key) ? site.getAsJsonObject(key) : new JsonObject();
            JsonObject r = recorded.has(key) ? recorded.getAsJsonObject(key) : new JsonObject();
            spots.put(key, new Spots(blocks(o.getAsJsonArray("minis")), blocks(o.getAsJsonArray("points")),
                blocks(r.getAsJsonArray("points")), r.has("r") ? r.get("r").getAsDouble() : 0));
        }
    }

    /** Every room in [all] that has no visits yet. */
    private void addRooms(JsonObject all) {
        for (Map.Entry<String, JsonElement> room : all.entrySet()) {
            if (rooms.containsKey(room.getKey())) {
                continue;
            }
            List<List<Spawn>> runs = new ArrayList<>();
            for (JsonElement runElement : room.getValue().getAsJsonArray()) {
                List<Spawn> run = new ArrayList<>();
                for (JsonElement e : runElement.getAsJsonArray()) {
                    JsonArray a = e.getAsJsonArray();
                    Map<String, Piece> equipment = new HashMap<>();
                    for (Map.Entry<String, JsonElement> piece : a.get(7).getAsJsonObject().entrySet()) {
                        JsonArray p = piece.getValue().getAsJsonArray();
                        equipment.put(piece.getKey(), new Piece(p.get(0).getAsString(), string(p.get(1)),
                            p.get(2).isJsonNull() ? null : p.get(2).getAsInt(), string(p.get(3))));
                    }
                    run.add(new Spawn(a.get(0).getAsString(), a.get(1).getAsInt() != 0, a.get(2).getAsString(),
                        a.get(3).getAsDouble(), a.get(4).getAsDouble(), a.get(5).getAsDouble(), a.get(6).getAsFloat(),
                        equipment, string(a.get(8)), string(a.get(9))));
                }
                runs.add(run);
            }
            rooms.put(room.getKey(), runs);
        }
    }

    private static List<int[]> blocks(JsonArray array) {
        List<int[]> out = new ArrayList<>();
        if (array != null) {
            for (JsonElement e : array) {
                JsonArray a = e.getAsJsonArray();
                out.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
            }
        }
        return List.copyOf(out);
    }

    /** Hypixel's spawn spots for [roomName] (empty lists when it has none). */
    public Spots spots(String roomName) {
        return spots.getOrDefault(normalize(roomName), Spots.NONE);
    }

    /** Every recorded visit's mobs for [roomName] (empty when it has none). */
    public List<List<Spawn>> runs(String roomName) {
        return rooms.getOrDefault(normalize(roomName), List.of());
    }

    /** A skin constant from mob_type.rs by name (e.g. "KING_MIDAS_SKIN"), or null. */
    public String skin(String constant) {
        return skins.get(constant);
    }

    /**
     * normalize_room_name: no whitespace, underscores or hyphens ("Criss-Cross"), lower case. The room library spells
     * one room "Silvers Sword" where the mob data and Hypixel say "Silver Sword".
     */
    private static String normalize(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (!Character.isWhitespace(c) && c != '_' && c != '-') {
                out.append(Character.toLowerCase(c));
            }
        }
        String key = out.toString();
        return key.equals("silverssword") ? "silversword" : key;
    }

    private static String string(JsonElement e) {
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static JsonObject read(String file, boolean gzip) {
        try (InputStream raw = MobStore.class.getResourceAsStream(ROOT + file)) {
            if (raw == null) {
                throw new IOException("No " + ROOT + file);
            }
            InputStream in = gzip ? new GZIPInputStream(raw) : raw;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Rustclear's dungeon data is missing from the jar", e);
        }
    }
}
