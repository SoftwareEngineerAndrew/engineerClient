package net.rustclear.mod.engine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Every SkyBlock accessory (accessories.md, data/accessories.json, packed into accessories.json.gz
 * by tools/items/pack_accessories.py) and what the maximum build gives a player: every family's
 * top accessory at its highest rarity, recombobulated, 2,176 Accessory Power (2,326 in a run,
 * where the 8 dungeon accessories count twice, as the recordings show), their own stats, the
 * enrichments, and the effects a dungeon run feels. Free of Minecraft, so it can be tested.
 */
public final class Accessories {
    private static final String RESOURCE = "/assets/rustclear/engine/accessories.json.gz";

    /** One accessory as the data has it. */
    public record Def(String id, String name, String rarity, boolean dungeon, String family, boolean top,
                      Map<String, Double> stats, List<String> lore, JsonArray effects, String relevance,
                      String maxRarity, boolean maxRecombobulated, int maxPower) {
    }

    /** One accessory in the max build: its rarity there and the Accessory Power it gives. */
    public record Member(Def def, String rarity, int power) {
    }

    private static Data data;

    private record Data(Map<String, Def> byId, List<Member> max, int maxPower, int dungeonPower,
                        Map<Stat, Double> flatStats, int enrichments, int dungeonEnrichments) {
    }

    private Accessories() {
    }

    private static synchronized Data data() {
        if (data == null) {
            try (InputStream raw = Accessories.class.getResourceAsStream(RESOURCE)) {
                if (raw == null) {
                    throw new IllegalStateException("Missing " + RESOURCE);
                }
                JsonObject root = new Gson().fromJson(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8), JsonObject.class);
                Map<String, Def> byId = new LinkedHashMap<>();
                for (JsonElement e : root.getAsJsonArray("accessories")) {
                    JsonObject a = e.getAsJsonObject();
                    Map<String, Double> stats = new LinkedHashMap<>();
                    a.getAsJsonObject("stats").entrySet().forEach(s -> stats.put(s.getKey(), s.getValue().getAsDouble()));
                    List<String> lore = new ArrayList<>();
                    a.getAsJsonArray("lore").forEach(l -> lore.add(l.getAsString()));
                    JsonObject max = a.has("max") && a.get("max").isJsonObject() ? a.getAsJsonObject("max") : new JsonObject();
                    byId.put(a.get("id").getAsString(), new Def(a.get("id").getAsString(), a.get("name").getAsString(),
                        a.get("rarity").getAsString(), a.get("dungeon").getAsBoolean(), a.get("family").getAsString(),
                        a.get("top").getAsBoolean(), Collections.unmodifiableMap(stats), List.copyOf(lore),
                        a.getAsJsonArray("effects"), a.get("relevance").getAsString(),
                        str(max, "rarity", a.get("rarity").getAsString()), max.has("recombobulated") && max.get("recombobulated").getAsBoolean(),
                        max.has("accessory_power") ? max.get("accessory_power").getAsInt() : 0));
                }
                JsonObject m = root.getAsJsonObject("max");
                List<Member> members = new ArrayList<>();
                for (JsonElement e : m.getAsJsonArray("members")) {
                    JsonObject o = e.getAsJsonObject();
                    Def def = byId.get(o.get("id").getAsString());
                    if (def != null) {
                        members.add(new Member(def, o.get("rarity").getAsString(), o.get("ap").getAsInt()));
                    }
                }
                Map<Stat, Double> flat = new EnumMap<>(Stat.class);
                m.getAsJsonObject("flat_stats").entrySet().forEach(s -> {
                    Stat stat = Stat.of(s.getKey());
                    if (stat != null) {
                        flat.put(stat, s.getValue().getAsDouble());
                    }
                });
                data = new Data(byId, List.copyOf(members), m.get("accessory_power").getAsInt(),
                    m.get("dungeon_accessory_power").getAsInt(), flat, m.get("enrichment_slots").getAsInt(),
                    m.get("enrichment_slots_dungeon").getAsInt());
            } catch (IOException e) {
                throw new IllegalStateException("Can't read " + RESOURCE, e);
            }
        }
        return data;
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    /** Every accessory by Hypixel id. */
    public static Map<String, Def> all() {
        return data().byId();
    }

    /** The max build's members (one per family). */
    public static List<Member> maxBuild() {
        return data().max();
    }

    /** The max build's Accessory Power outside a run (2,176). */
    public static int maxPower() {
        return data().maxPower();
    }

    /** What the max build's dungeon accessories add again inside a run (150). */
    public static int maxDungeonPower() {
        return data().dungeonPower();
    }

    /**
     * Enrichment per slot, the recorder's choice on every Legendary/Mythic accessory: +0.5 Attack
     * Speed (accessories.md §1.3; "Attack Speed: +0.5% (+0.5%)" on the recorded Wither Relic).
     */
    static final double ENRICHMENT_ATTACK_SPEED = 0.5;

    /**
     * The max build's own stats and effects, for one player's sheet: the family tops' flat stats,
     * the enrichments (the dungeon accessories' counted again in a run), and what a run feels
     * (accessories.md §4.2). Reaper Orb, Tarantula Ring and the Ring of Love act on hits
     * (AccessoryHits); Bat Person's bats are left out (OPEN).
     */
    static void addMax(StatSheet s, SkyblockProfile profile, StatBuilder.Run run) {
        Data d = data();
        d.flatStats().forEach((stat, v) -> s.add(StatSheet.Source.ACCESSORY_BAG, "Accessories", stat, v));
        int enrichments = d.enrichments() + (run.inDungeon() ? d.dungeonEnrichments() : 0);
        s.add(StatSheet.Source.ACCESSORY_BAG, "Enrichments", Stat.ATTACK_SPEED, enrichments * ENRICHMENT_ATTACK_SPEED);
        // Moonlight and Sunshine Crystal are separate families: one of them is always on (OPEN: which clock).
        s.add(StatSheet.Source.ACCESSORY_BAG, "Moonlight / Sunshine Crystal", Stat.STRENGTH, 10);
        s.add(StatSheet.Source.ACCESSORY_BAG, "Moonlight / Sunshine Crystal", Stat.DEFENSE, 10);
        if (run.inDungeon()) {
            // "In combat" with their Soulflow toggle on: a run is combat throughout.
            s.add(StatSheet.Source.ACCESSORY_BAG, "Pocket Espresso Machine", Stat.INTELLIGENCE, 100);
            s.add(StatSheet.Source.ACCESSORY_BAG, "Handy Blood Chalice", Stat.FEROCITY, 20);
        }
        // 2 IQ Points: +2% of total Intelligence.
        s.multiply("2 IQ Points", Stat.INTELLIGENCE, 1.02);
        if (run.inDungeon() && run.masterMode()) {
            // Master Skull - Tier 7: +10% Health and Strength in Master Mode (OPEN: stacking order).
            s.multiply("Master Skull - Tier 7", Stat.HEALTH, 1.10);
            s.multiply("Master Skull - Tier 7", Stat.STRENGTH, 1.10);
        }
        // Book of Progression, Extra Infliction: +0.01% damage per SkyBlock level, at most 5%.
        int level = profile.level > 0 ? profile.level : 500;
        s.damageMultiplier *= 1 + Math.min(5.0, 0.01 * level) / 100;
        // Damage taken by mob type (Wither Relic, Zombie Artifact, Skeleton Talisman, Nether Artifact +
        // Blaze Talisman), and Burststopper on hits of half your max Health or more.
        s.takenByType.put("Wither", 0.75);
        s.takenByType.put("Undead", 0.85);
        s.takenByType.put("Skeletal", 0.95);
        s.takenByType.put("Infernal", 0.95 * 0.95);
        s.bigHitTaken = 0.9;
    }

    /**
     * Hypixel's mob types for a Rustclear attacker by name (OPEN: which F7/M7 mobs carry which tag
     * isn't recorded; accessories.md §4.2). Zombies, skeletons and withers are Undead; skeletons
     * and Wither Skeletons Skeletal; anything Wither-named and the Wither Lords and King Wither;
     * blazes Infernal.
     */
    static Set<String> mobTypes(String name) {
        if (name == null) {
            return Set.of();
        }
        String n = name.toLowerCase(Locale.ROOT);
        java.util.HashSet<String> out = new java.util.HashSet<>();
        boolean wither = n.contains("wither") || n.contains("maxor") || n.contains("storm") || n.contains("goldor")
            || n.contains("necron");
        boolean skeleton = n.contains("skelet") || n.contains("wither skeleton");
        if (wither) {
            out.add("Wither");
        }
        if (skeleton) {
            out.add("Skeletal");
        }
        if (wither || skeleton || n.contains("zombie") || n.contains("crypt") || n.contains("lurker") || n.contains("undead")) {
            out.add("Undead");
        }
        if (n.contains("blaze")) {
            out.add("Infernal");
        }
        return out;
    }
}
