package net.rustclear.mod.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.rustclear.mod.RustclearMod;

/**
 * Each player's pets, as on Hypixel: the pet menu (PetsMenu) holds them, one is summoned at a time
 * and adds its stats (PetDefs), a pet can be turned into an item (right-click it in the menu) and an
 * item put back in the menu (right-click it, or click it in your inventory with the menu open), and
 * Autopet rules summon a pet when something happens in the run. Kept by the host per player in
 * config/rustclear/pets/&lt;uuid&gt;.json; a kit with pets fills the menu with them each dungeon
 * (KitItems.give).
 */
public final class Pets {
    /** Pets in a menu: Hypixel's first page. */
    public static final int MAX = Kit.MAX_PETS;
    /** Autopet rules a player can have (Hypixel's "Rules used: 10/16"). */
    public static final int MAX_RULES = 16;
    private static final String ITEM_ID = "PET";
    private static final String LEVEL_TAG = "rustclear_pet_level";
    /** The bosses an On Boss Spawn rule can name. */
    // OPEN: Hypixel's full boss list for On Boss Spawn (only Necron was recorded).
    public static final List<String> BOSSES = List.of("Maxor", "Storm", "Goldor", "Necron", "Wither King");

    /** One pet in a menu: its exp (Hypixel's petInfo exp), the held item's Hypixel id and the skin's id (each null for none). */
    public record Pet(String uuid, PetDefs.Type type, PetDefs.Rarity rarity, int level, double exp, String heldItem, String skin) {
        /** "§7[Lvl 100] §6Black Cat§5 ✦". */
        public String name() {
            return PetDefs.name(type, rarity, level, skin);
        }

        /** "§6Golden Dragon", as the summon lines say it ("§6Black Cat§5 ✦" when skinned). */
        public String shortName() {
            return rarity.color + type.title + (skin == null ? "" : PetDefs.SKIN_MARK);
        }

        public PetDefs.HeldItem held() {
            return PetDefs.heldItem(heldItem);
        }
    }

    /** What sets off an Autopet rule, with its icon in the Autopet window (menus-skyblock.md B.2). */
    public enum Trigger {
        BOSS_SPAWN("On Boss Spawn", Items.DRAGON_EGG),
        ENTER_ISLAND("On Enter Island", Items.GRASS_BLOCK),
        FISHING_HOOK("On Throw Fishing Hook", Items.FISHING_ROD),
        // OPEN: the On Start Dungeon Floor icon is a player_head whose skin wasn't kept.
        DUNGEON_START("On Start Dungeon Floor", Items.PLAYER_HEAD),
        /** Rustclear's own, never seen on Hypixel: kept so older rules still work, not offered for new ones. */
        BLOOD_OPEN("On Open Blood Door", Items.REDSTONE_BLOCK);

        public final String title;
        public final Item icon;

        Trigger(String title, Item icon) {
            this.title = title;
            this.icon = icon;
        }

        /** The rule's "When:" lines for [param] (the boss, island or floor; null: the usual one). */
        public List<String> condition(String param) {
            return switch (this) {
                case BOSS_SPAWN -> List.of("§c" + (param == null ? "Necron" : param) + " §7spawns.");
                case ENTER_ISLAND -> List.of("§7You enter §a" + (param == null ? "Dungeon" : param) + "§7.");
                case FISHING_HOOK -> List.of("§7You throw a fishing hook.");
                case DUNGEON_START -> "M7".equalsIgnoreCase(param)
                    ? List.of("§7You begin Catacombs Master", "§7Mode Floor VII§7.")
                    : List.of("§7You begin Catacombs Floor VII§7.");
                case BLOOD_OPEN -> List.of("§7You open the §cBlood Door§7.");
            };
        }
    }

    /** When a rule doesn't fire: Hypixel's two recorded ones, then Rustclear's own. */
    public enum Except {
        PET_EQUIPPED("You have a pet equipped", Items.BONE, null),
        NOT_IN_DUNGEON("You're NOT in Dungeon", Items.GRASS_BLOCK, null),
        // OPEN: Hypixel's full exception list (only the two above were recorded); these are Rustclear's.
        HOLDING_BOW("You're holding a Bow", Items.BOW, null),
        MAGE("You're playing Mage", Items.BLAZE_ROD, DungeonClass.MAGE),
        ARCHER("You're playing Archer", Items.ARROW, DungeonClass.ARCHER),
        BERSERK("You're playing Berserk", Items.IRON_SWORD, DungeonClass.BERSERK),
        TANK("You're playing Tank", Items.LEATHER_CHESTPLATE, DungeonClass.TANK),
        HEALER("You're playing Healer", Items.SPLASH_POTION, DungeonClass.HEALER);

        public final String title;
        public final Item icon;
        final DungeonClass dungeonClass;

        Except(String title, Item icon, DungeonClass dungeonClass) {
            this.title = title;
            this.icon = icon;
            this.dungeonClass = dungeonClass;
        }
    }

    /** One exception of a rule: its kind and, for PET_EQUIPPED, the pet type. */
    public record Condition(Except kind, String param) {
        static Condition of(String saved) {
            if (saved == null) {
                return null;
            }
            String[] parts = saved.split(":", 2);
            try {
                return new Condition(Except.valueOf(parts[0]), parts.length > 1 ? parts[1] : null);
            } catch (RuntimeException e) {
                return null;
            }
        }

        String saved() {
            return param == null ? kind.name() : kind.name() + ":" + param;
        }

        /** The "Except if:" lines: "§7You have a §aBlack Cat §7pet" / "§7equipped.", "§7You're NOT in §aDungeon§7.". */
        public List<String> lines() {
            return switch (kind) {
                case PET_EQUIPPED -> {
                    PetDefs.Type t = PetDefs.Type.of(param);
                    yield List.of("§7You have a §a" + (t == null ? "?" : t.title) + " §7pet", "§7equipped.");
                }
                case NOT_IN_DUNGEON -> List.of("§7You're NOT in §aDungeon§7.");
                case HOLDING_BOW -> List.of("§7You're holding a §aBow§7.");
                default -> List.of("§7You're playing §a" + kind.dungeonClass.title + "§7.");
            };
        }
    }

    /** "When [trigger] ([param]), equip [type] ([rarity]), except if [exceptions]". */
    public static final class Rule {
        public String trigger;
        /** The boss (BOSS_SPAWN), island (ENTER_ISLAND) or floor code F7 / M7 (DUNGEON_START); null: any. */
        public String param;
        public String type;
        public String rarity;
        public List<String> exceptions = new ArrayList<>();
        /** The one exception rules saved by older versions had; moved into [exceptions] on load. */
        public String except;
        public boolean enabled = true;

        public Rule() {
        }

        public Trigger trigger() {
            try {
                return Trigger.valueOf(trigger);
            } catch (RuntimeException e) {
                return null;
            }
        }

        public List<Condition> conditions() {
            List<Condition> out = new ArrayList<>();
            if (exceptions != null) {
                for (String s : exceptions) {
                    Condition c = Condition.of(s);
                    if (c != null) {
                        out.add(c);
                    }
                }
            }
            return out;
        }

        public void addException(Condition c) {
            if (exceptions == null) {
                exceptions = new ArrayList<>();
            }
            if (!exceptions.contains(c.saved())) {
                exceptions.add(c.saved());
            }
        }

        public PetDefs.Type type() {
            return PetDefs.Type.of(type);
        }

        public PetDefs.Rarity rarity() {
            return PetDefs.Rarity.of(rarity);
        }

        public List<String> condition() {
            Trigger t = trigger();
            return t == null ? List.of() : t.condition(param);
        }
    }

    /** How the menu lists pets (Hypixel's Sort button, slot 52). */
    public enum Sort {
        RARITY("Rarity"), A_TO_Z("A to Z"), Z_TO_A("Z to A"), PET_EXP("Pet Exp"), SKILL("Skill");

        public final String title;

        Sort(String title) {
            this.title = title;
        }
    }

    /** A player's pets as the file has them. */
    private static final class State {
        List<Saved> pets = new ArrayList<>();
        String active;
        List<Rule> rules = new ArrayList<>();
        String sort = Sort.RARITY.name();
    }

    private static final class Saved {
        String uuid;
        String type;
        String rarity;
        int level;
        double exp;
        String heldItem;
        String skin;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private Pets() {
    }

    // ---------------------------------------------------------------- storage

    private static Path file(UUID id) {
        return FabricLoader.getInstance().getConfigDir().resolve("rustclear").resolve("pets").resolve(id + ".json");
    }

    private static State state(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), id -> {
            Path f = file(id);
            if (Files.exists(f)) {
                try {
                    State read = GSON.fromJson(Files.readString(f), State.class);
                    if (read != null) {
                        return checked(read);
                    }
                } catch (IOException | RuntimeException e) {
                    RustclearMod.LOGGER.warn("Couldn't read {}: {}", f, e.toString());
                }
            }
            return new State();
        });
    }

    /** [s] without pets or rules PetDefs doesn't know. */
    private static State checked(State s) {
        State out = new State();
        if (s.pets != null) {
            for (Saved p : s.pets) {
                PetDefs.Type type = p == null ? null : PetDefs.Type.of(p.type);
                if (type != null && out.pets.size() < MAX) {
                    out.pets.add(saved(new Pet(p.uuid == null ? UUID.randomUUID().toString() : p.uuid, type,
                        PetDefs.fit(type, PetDefs.Rarity.of(p.rarity)), p.level, p.exp, p.heldItem, p.skin)));
                }
            }
        }
        out.active = s.active != null && out.pets.stream().anyMatch(p -> p.uuid.equals(s.active)) ? s.active : null;
        if (s.rules != null) {
            for (Rule r : s.rules) {
                if (r != null && r.trigger() != null && r.type() != null && out.rules.size() < MAX_RULES) {
                    if (r.exceptions == null) {
                        r.exceptions = new ArrayList<>();
                    }
                    Condition old = Condition.of(r.except);
                    if (old != null) {
                        r.addException(old);
                    }
                    r.except = null;
                    out.rules.add(r);
                }
            }
        }
        out.sort = s.sort == null ? Sort.RARITY.name() : s.sort;
        return out;
    }

    /** [p] as the file keeps it: the level within its type's, and exp at least what that level takes. */
    private static Saved saved(Pet p) {
        Saved s = new Saved();
        s.uuid = p.uuid();
        s.type = p.type().name();
        s.rarity = p.rarity().name();
        s.level = p.level() > 0 ? PetDefs.clampLevel(p.type(), p.level()) : PetDefs.levelFor(p.type(), p.rarity(), p.exp());
        s.exp = Math.max(p.exp(), PetDefs.expFor(p.type(), p.rarity(), s.level));
        s.heldItem = p.heldItem();
        s.skin = p.skin();
        return s;
    }

    private static void save(ServerPlayer player) {
        Path f = file(player.getUUID());
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(state(player)));
        } catch (IOException e) {
            RustclearMod.LOGGER.warn("Couldn't save {}: {}", f, e.toString());
        }
    }

    private static Pet pet(Saved s) {
        PetDefs.Type type = PetDefs.Type.of(s.type);
        return new Pet(s.uuid, type, PetDefs.fit(type, PetDefs.Rarity.of(s.rarity)), s.level, s.exp, s.heldItem, s.skin);
    }

    // ---------------------------------------------------------------- the menu

    /**
     * [player]'s pets, in their menu's order. Rarity (the default): rarest first, then the higher
     * level, then more exp (Hypixel's order on one profile: Golden Dragon 200 before Black Cat 100
     * before Spirit 100 with less exp).
     */
    public static List<Pet> owned(ServerPlayer player) {
        State s = state(player);
        List<Pet> out = new ArrayList<>(s.pets.stream().map(Pets::pet).toList());
        Comparator<Pet> byRarity = Comparator.comparingInt((Pet p) -> p.rarity().ordinal()).reversed()
            .thenComparing(Comparator.comparingInt(Pet::level).reversed())
            .thenComparing(Comparator.comparingDouble(Pet::exp).reversed());
        Comparator<Pet> byName = Comparator.comparing((Pet p) -> p.type().title);
        // OPEN: the order of A to Z / Z to A ties, Pet Exp and Skill (never clicked in a recording).
        switch (sort(player)) {
            case RARITY, SKILL -> out.sort(byRarity);
            case A_TO_Z -> out.sort(byName.thenComparing(byRarity));
            case Z_TO_A -> out.sort(byName.reversed().thenComparing(byRarity));
            case PET_EXP -> out.sort(Comparator.comparingDouble(Pet::exp).reversed().thenComparing(byRarity));
        }
        return out;
    }

    /** [player]'s summoned pet, or null. */
    public static Pet active(ServerPlayer player) {
        State s = state(player);
        for (Saved p : s.pets) {
            if (p.uuid.equals(s.active)) {
                return pet(p);
            }
        }
        return null;
    }

    /** What the summoned pet adds to [player]'s stats. */
    public static PetDefs.Bonus stats(ServerPlayer player) {
        Pet pet = active(player);
        return pet == null ? PetDefs.Bonus.NONE : PetDefs.bonus(pet.type(), pet.rarity(), pet.level(), pet.held());
    }

    /** [player]'s Pet Score (the Pets menu's slot 47). */
    public static int score(ServerPlayer player) {
        List<Pet> pets = owned(player);
        return PetDefs.score(pets.stream().map(Pet::type).toList(), pets.stream().map(Pet::rarity).toList(),
            pets.stream().map(Pet::level).toList());
    }

    /**
     * Summons [pet] (or, already out, puts it away), with Hypixel's line and, on a summon, the egg
     * sound (NEUTRAL 1.0/1.0). The Pets menu closes itself.
     */
    public static void toggle(ServerPlayer player, Pet pet) {
        State s = state(player);
        if (pet.uuid().equals(s.active)) {
            // OPEN: Hypixel's despawn (chat, sound, close or re-open); never recorded.
            s.active = null;
            player.sendSystemMessage(EngineItems.text("§aYou despawned your " + pet.shortName() + "§a!"));
        } else {
            s.active = pet.uuid();
            player.sendSystemMessage(EngineItems.text("§aYou summoned your " + pet.shortName() + "§a!"));
            sound(player, SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 1f, 1f);
        }
        save(player);
        PlayerStats.forgetCached(player.getUUID());
    }

    public static Sort sort(ServerPlayer player) {
        try {
            return Sort.valueOf(state(player).sort);
        } catch (RuntimeException e) {
            return Sort.RARITY;
        }
    }

    /** The next sort, or with [back] the one before (the Sort button's right-click). */
    public static void nextSort(ServerPlayer player, boolean back) {
        int n = Sort.values().length;
        state(player).sort = Sort.values()[(sort(player).ordinal() + (back ? n - 1 : 1)) % n].name();
        save(player);
    }

    /** Whether [player] has room for a pet item. */
    static boolean hasRoom(ServerPlayer player) {
        return player.getInventory().getFreeSlot() >= 0;
    }

    /** Takes [pet] out of the menu as an item in [player]'s first free slot, silently. False: no room. */
    public static boolean toItem(ServerPlayer player, Pet pet) {
        int free = player.getInventory().getFreeSlot();
        if (free < 0) {
            // OPEN: what Hypixel says when converting with a full inventory.
            player.sendSystemMessage(EngineItems.text("§cYour inventory is full!"));
            return false;
        }
        State s = state(player);
        if (!s.pets.removeIf(p -> p.uuid.equals(pet.uuid()))) {
            return false;
        }
        if (pet.uuid().equals(s.active)) {
            s.active = null;
        }
        save(player);
        PlayerStats.forgetCached(player.getUUID());
        player.getInventory().setItem(free, item(new Pet(pet.uuid(), pet.type(), pet.rarity(), pet.level(), pet.exp(),
            pet.heldItem(), pet.skin())));
        return true;
    }

    /**
     * Puts the pet item in [player]'s inventory slot [index] back in their menu, with Hypixel's line
     * and sounds (3x experience_orb.pickup PLAYERS 1.0/1.492, then arrow.hit_player MASTER 1.0/1.0).
     * False: it isn't one.
     */
    public static boolean fromItem(ServerPlayer player, int index) {
        ItemStack stack = player.getInventory().getItem(index);
        Pet pet = ofItem(stack);
        if (pet == null) {
            return false;
        }
        State s = state(player);
        if (s.pets.size() >= MAX) {
            // OPEN: Hypixel's line for a full pet menu.
            player.sendSystemMessage(EngineItems.text("§cYour pet menu is full!"));
            return true;
        }
        String uuid = s.pets.stream().anyMatch(p -> p.uuid.equals(pet.uuid())) ? UUID.randomUUID().toString() : pet.uuid();
        s.pets.add(saved(new Pet(uuid, pet.type(), pet.rarity(), pet.level(), pet.exp(), pet.heldItem(), pet.skin())));
        save(player);
        player.getInventory().setItem(index, ItemStack.EMPTY);
        player.sendSystemMessage(EngineItems.text("§aSuccessfully added " + pet.shortName() + " §ato your pet menu!"));
        for (int i = 0; i < 3; i++) {
            sound(player, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1.492f);
        }
        sound(player, SoundEvents.ARROW_HIT_PLAYER, SoundSource.MASTER, 1f, 1f);
        return true;
    }

    /** A kit's pets: the menu holds exactly them, with [active] (an index, -1 none) summoned. */
    static void fromKit(ServerPlayer player, List<Kit.Pet> pets, int active) {
        State s = state(player);
        s.pets.clear();
        s.active = null;
        for (int i = 0; i < pets.size() && s.pets.size() < MAX; i++) {
            Kit.Pet p = pets.get(i);
            PetDefs.Type type = PetDefs.Type.of(p.type);
            if (type == null) {
                continue;
            }
            PetDefs.Rarity rarity = PetDefs.fit(type, PetDefs.Rarity.of(p.rarity));
            int level = PetDefs.clampLevel(type, p.level);
            Saved saved = saved(new Pet(UUID.randomUUID().toString(), type, rarity, level,
                Math.max(p.exp, PetDefs.expFor(type, rarity, level)), p.heldItem, p.skin));
            s.pets.add(saved);
            if (i == active) {
                s.active = saved.uuid;
            }
        }
        save(player);
        PlayerStats.forgetCached(player.getUUID());
    }

    // ---------------------------------------------------------------- items

    /** [pet]'s lore down to the exp line, and Kat's line when it comes in a better rarity. */
    private static List<String> description(Pet pet) {
        List<String> lore = new ArrayList<>(PetDefs.lore(pet.type(), pet.rarity(), pet.level(), pet.exp(), pet.held(), pet.skin()));
        if (PetDefs.upgradable(pet.type(), pet.rarity())) {
            lore.add("");
            lore.add("§8Can be upgraded at Kat in The Hub!");
        }
        return lore;
    }

    /**
     * [pet] as the Pets menu shows it: no rarity line, no glint even when summoned, and Hypixel's
     * three click lines (the first "§cClick to despawn!" for the summoned one).
     */
    static ItemStack menuStack(Pet pet, boolean active) {
        List<String> lore = description(pet);
        lore.add("");
        lore.add(active ? "§cClick to despawn!" : "§eLeft-click to summon!");
        lore.add("§eShift Left-click to toggle as favorite!");
        lore.add("§eRight-click to convert to an item!");
        ItemStack stack = head(pet.type(), pet.name(), lore);
        tag(stack, pet, active);
        return stack;
    }

    /** [type] as a pet item would show, with [after] at the end of its lore. */
    public static ItemStack stack(PetDefs.Type type, PetDefs.Rarity rarity, int level, String... after) {
        Pet pet = new Pet("", type, rarity, level, PetDefs.expFor(type, rarity, level), null, null);
        List<String> lore = description(pet);
        if (after.length > 0) {
            lore.add("");
            lore.addAll(List.of(after));
        }
        lore.add("");
        lore.add(PetDefs.rarityLine(rarity));
        return head(type, pet.name(), lore);
    }

    /** [pet] as Hypixel's pet item (id PET, its petInfo): the menu's lore, then a blank line and the rarity line. */
    public static ItemStack item(Pet pet) {
        // OPEN: whether a converted pet keeps "Can be upgraded at Kat" (only a Golden Dragon, which has none, was recorded).
        List<String> lore = description(pet);
        lore.add("");
        lore.add(PetDefs.rarityLine(pet.rarity()));
        ItemStack stack = head(pet.type(), pet.name(), lore);
        tag(stack, pet, false);
        return stack;
    }

    private static ItemStack head(PetDefs.Type type, String name, List<String> lore) {
        String[] lines = lore.toArray(String[]::new);
        if (type.skin == null) {
            return EngineItems.named(Items.PLAYER_HEAD, name, lines);
        }
        // A profile name is at most 16 characters ("pet_golden_dragon" got players kicked).
        return EngineItems.playerHead(EngineItems.profile("pet" + type.ordinal(), type.skin, null), name, lines);
    }

    /** Hypixel's custom_data {id:"PET", petInfo:"{...}"} and tooltip_style hypixel_skyblock:&lt;rarity&gt;, which client mods read. */
    private static void tag(ItemStack stack, Pet pet, boolean active) {
        JsonObject info = new JsonObject();
        info.addProperty("type", pet.type().name());
        info.addProperty("active", active);
        info.addProperty("exp", pet.exp());
        info.addProperty("tier", pet.rarity().name());
        info.addProperty("hideInfo", false);
        if (pet.heldItem() != null) {
            info.addProperty("heldItem", pet.heldItem());
        }
        info.addProperty("candyUsed", 0);
        if (pet.skin() != null) {
            info.addProperty("skin", pet.skin());
        }
        info.addProperty("uuid", pet.uuid());
        info.addProperty("hideRightClick", false);
        info.addProperty("noMove", false);
        info.add("extraData", new JsonObject());
        info.addProperty("petSoulbound", false);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", ITEM_ID);
        tag.putString("petInfo", info.toString());
        tag.putString("uuid", pet.uuid());
        tag.putInt(LEVEL_TAG, pet.level());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.TOOLTIP_STYLE,
            Identifier.fromNamespaceAndPath("hypixel_skyblock", pet.rarity().name().toLowerCase(Locale.ROOT)));
    }

    /** The pet [stack] is, or null. */
    public static Pet ofItem(ItemStack stack) {
        if (stack == null || !stack.is(Items.PLAYER_HEAD)) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (!ITEM_ID.equals(tag.getStringOr("id", ""))) {
            return null;
        }
        try {
            JsonObject info = GSON.fromJson(tag.getStringOr("petInfo", "{}"), JsonObject.class);
            PetDefs.Type type = PetDefs.Type.of(info.has("type") ? info.get("type").getAsString() : "");
            if (type == null) {
                return null;
            }
            PetDefs.Rarity rarity = PetDefs.fit(type, PetDefs.Rarity.of(info.has("tier") ? info.get("tier").getAsString() : ""));
            String uuid = info.has("uuid") ? info.get("uuid").getAsString() : UUID.randomUUID().toString();
            double exp = info.has("exp") ? info.get("exp").getAsDouble() : -1;
            int level = tag.getIntOr(LEVEL_TAG, exp >= 0 ? PetDefs.levelFor(type, rarity, exp) : type.maxLevel);
            level = PetDefs.clampLevel(type, level);
            return new Pet(uuid, type, rarity, level, Math.max(exp, PetDefs.expFor(type, rarity, level)),
                info.has("heldItem") ? info.get("heldItem").getAsString() : null,
                info.has("skin") ? info.get("skin").getAsString() : null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A right-click with a pet item in the main hand puts it in the menu. True: it was one. */
    static boolean useItem(ServerPlayer player) {
        int slot = player.getInventory().getSelectedSlot();
        return ofItem(player.getInventory().getItem(slot)) != null && fromItem(player, slot);
    }

    static void sound(ServerPlayer player, SoundEvent event, SoundSource source, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event), source,
            player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
    }

    // ---------------------------------------------------------------- Autopet

    public static List<Rule> rules(ServerPlayer player) {
        return state(player).rules;
    }

    /** Adds [rule]; false when there are already [MAX_RULES]. */
    public static boolean addRule(ServerPlayer player, Rule rule) {
        State s = state(player);
        if (s.rules.size() >= MAX_RULES) {
            return false;
        }
        s.rules.add(rule);
        save(player);
        return true;
    }

    public static void removeRule(ServerPlayer player, int index) {
        State s = state(player);
        if (index >= 0 && index < s.rules.size()) {
            s.rules.remove(index);
            save(player);
        }
    }

    public static void toggleRule(ServerPlayer player, int index) {
        State s = state(player);
        if (index >= 0 && index < s.rules.size()) {
            s.rules.get(index).enabled = !s.rules.get(index).enabled;
            save(player);
        }
    }

    /** Saves a change made to one of [player]'s rules. */
    public static void changedRules(ServerPlayer player) {
        save(player);
    }

    /** The pet [rule] would equip: [player]'s best of its type (and rarity, if it names one), or null. */
    public static Pet petFor(ServerPlayer player, Rule rule) {
        Pet best = null;
        for (Pet p : owned(player)) {
            if (p.type() == rule.type() && (rule.rarity() == null || p.rarity() == rule.rarity())
                && (best == null || p.level() > best.level() || p.level() == best.level() && p.exp() > best.exp())) {
                best = p;
            }
        }
        return best;
    }

    /**
     * Rule number [number]'s lore, Hypixel's template (menus-skyblock.md B.2): "Autopet Rule #n", the
     * disabled line, When, Equip (or Missing) and Held Item, Except if, and with [click] "Click to edit rule!".
     */
    public static List<String> ruleLore(ServerPlayer player, Rule rule, int number, boolean click) {
        List<String> out = new ArrayList<>();
        out.add("§8Autopet Rule #" + number);
        out.add("");
        if (!rule.enabled) {
            out.add("§cRULE IS DISABLED!");
            out.add("");
        }
        out.add("§aWhen:");
        out.addAll(rule.condition());
        out.add("");
        out.addAll(equipLines(player, rule));
        out.add("");
        List<Condition> conditions = rule.conditions();
        if (!conditions.isEmpty()) {
            out.add("§eExcept if:");
            for (Condition c : conditions) {
                out.addAll(c.lines());
            }
            out.add("");
        }
        if (click) {
            out.add("§eClick to edit rule!");
        }
        return out;
    }

    /** "§aEquip: §7[Lvl 100] §6Black Cat§5 ✦" and "§aHeld Item: ...", or "§cMissing: §7[Lvl 100] §6Jellyfish". */
    public static List<String> equipLines(ServerPlayer player, Rule rule) {
        Pet pet = petFor(player, rule);
        PetDefs.Type type = rule.type();
        if (pet == null) {
            PetDefs.Rarity rarity = PetDefs.fit(type, rule.rarity());
            return List.of("§cMissing: " + PetDefs.name(type, rarity, type.maxLevel));
        }
        PetDefs.HeldItem held = pet.held();
        return held == null ? List.of("§aEquip: " + pet.name()) : List.of("§aEquip: " + pet.name(), "§aHeld Item: " + held.name());
    }

    /** [trigger] happened to [player] (the boss, island or floor unknown). */
    static void trigger(ServerPlayer player, Trigger trigger) {
        trigger(player, trigger, null);
    }

    /**
     * [trigger] happened to [player], [param] being the boss (BOSS_SPAWN), island (ENTER_ISLAND) or
     * floor code (DUNGEON_START; null: the run's own floor). Every enabled rule for it fires in order,
     * as on Hypixel, where one rod throw equipped Phoenix and then Black Cat in the same tick (19 of 240).
     * Exceptions are judged on the pet that was out before any rule fired, and a rule whose pet is
     * already out says nothing (I: that is what makes a "Phoenix" rule and a "Black Cat unless Black
     * Cat is out" rule alternate, as the recordings' 132 Phoenix / 108 Black Cat lines do).
     */
    static void trigger(ServerPlayer player, Trigger trigger, String param) {
        State s = state(player);
        if (trigger == Trigger.DUNGEON_START && param == null && EngineServer.runOf(player) instanceof DungeonRun run) {
            param = run.floor.code;
        }
        Pet before = active(player);
        for (int i = 0; i < s.rules.size(); i++) {
            Rule rule = s.rules.get(i);
            if (!rule.enabled || rule.trigger() != trigger || param != null && rule.param != null && !rule.param.equalsIgnoreCase(param)) {
                continue;
            }
            if (rule.conditions().stream().anyMatch(c -> excepted(player, c, before))) {
                continue;
            }
            Pet pet = petFor(player, rule);
            if (pet == null) {
                player.sendSystemMessage(EngineItems.text("§cAutopet rule triggered but couldn't find your pet!"));
                continue;
            }
            if (!pet.uuid().equals(s.active)) {
                s.active = pet.uuid();
                save(player);
                PlayerStats.forgetCached(player.getUUID());
                // One legacy § text with the rule on hover, as Hypixel sends it.
                player.sendSystemMessage(Component.literal("§cAutopet §eequipped your " + pet.name() + "§e! §a§lVIEW RULE")
                    .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover(rule, pet)))));
            }
        }
    }

    /** The VIEW RULE hover: "Autopet Rule", When, Equip and Held Item (71b575's decoded hover). */
    private static Component hover(Rule rule, Pet pet) {
        StringBuilder text = new StringBuilder("Autopet Rule\n\n§aWhen:\n§7");
        text.append(String.join(" ", rule.condition()).replaceAll("§.", "")).append("\n\n");
        text.append("§aEquip: §7[Lvl ").append(pet.level()).append("] ").append(pet.shortName());
        PetDefs.HeldItem held = pet.held();
        if (held != null) {
            text.append("\n§aHeld Item: ").append(held.name());
        }
        return EngineItems.styled(text.toString());
    }

    private static boolean excepted(ServerPlayer player, Condition c, Pet before) {
        return switch (c.kind()) {
            case PET_EQUIPPED -> before != null && before.type().name().equalsIgnoreCase(c.param());
            case NOT_IN_DUNGEON -> !(EngineServer.runOf(player) instanceof DungeonRun);
            case HOLDING_BOW -> {
                ItemStack held = player.getMainHandItem();
                ItemDefs.Instance item = PlayerStats.instance(held);
                yield held.is(Items.BOW) || item != null && ("BOW".equals(item.def().type) || "SHORTBOW".equals(item.def().type));
            }
            default -> PlayerStats.classOf(player) == c.kind().dungeonClass;
        };
    }

    static void forget(UUID id) {
        STATES.remove(id);
    }

    static void clear() {
        STATES.clear();
    }
}
