package net.rustclear.mod.engine.blood;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The camp's skins and heads (blood_camp.json, measured from the recordings): each named NPC's
 * skin (textures + signature), the Watcher's, the Eye's and the giant's head textures, and the
 * wall's other heads (stands that are never thrown).
 */
final class BloodSkins {
    record Skin(String value, String signature) {}

    private static BloodSkins instance;

    final Map<String, Skin> npcs = new HashMap<>();
    String watcher;
    String eye;
    String giant;
    final List<String> decoys = new ArrayList<>();

    static synchronized BloodSkins get() {
        if (instance == null) {
            instance = new BloodSkins();
        }
        return instance;
    }

    private BloodSkins() {
        try (InputStream in = BloodSkins.class.getResourceAsStream("/assets/rustclear/engine/blood_camp.json")) {
            if (in == null) {
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("npcs").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                npcs.put(e.getKey(), new Skin(o.get("value").getAsString(),
                    o.get("signature").isJsonNull() ? null : o.get("signature").getAsString()));
            }
            JsonObject heads = root.getAsJsonObject("heads");
            watcher = heads.get("watcher").getAsString();
            eye = heads.get("eye").getAsString();
            giant = heads.get("giant").getAsString();
            heads.getAsJsonArray("decoys").forEach(d -> decoys.add(d.getAsString()));
        } catch (Exception e) {
            net.rustclear.mod.RustclearMod.LOGGER.warn("Couldn't read blood_camp.json: {}", e.toString());
        }
    }

    Skin npc(String name) {
        return npcs.get(name);
    }
}
