package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Hypixel's Autopet window, from the Pets menu (menus-skyblock.md B.2): your rules as their
 * trigger's icon (a gray dye when off) packed from slot 10, and Create Rule in the next free slot.
 * A click on a rule opens Edit Autopet Rule (9x5): its pet (19), exceptions (21), on/off (24) and
 * Delete (25). Create Rule asks for a trigger, then the pet, then an exception, a window each.
 */
final class AutopetMenu extends ChestGui {
    static final String TITLE = "Autopet";
    static final String EDIT_TITLE = "Edit Autopet Rule";
    /** Rule slots: the pet-slot pattern, 16 rules. */
    private static final int[] RULE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29};
    private static final int[] PICK_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int EDIT_ICON = 4;
    private static final int EDIT_PET = 19;
    private static final int EDIT_EXCEPT = 21;
    private static final int EDIT_TOGGLE = 24;
    private static final int EDIT_DELETE = 25;

    // OPEN: the Create Rule windows (trigger, pet and exception pickers) were never recorded; titles and layout are Rustclear's.
    private enum Page {
        LIST(TITLE, 6), EDIT(EDIT_TITLE, 5), TRIGGER("Autopet ➜ Choose a Trigger", 6), PET("Autopet ➜ Choose a Pet", 6),
        EXCEPT("Autopet ➜ Choose an Exception", 6);

        final String title;
        final int rows;

        Page(String title, int rows) {
            this.title = title;
            this.rows = rows;
        }
    }

    /** One trigger a new rule can have, with its boss, island or floor. */
    private record When(Pets.Trigger trigger, String param) {}

    /** One pet a rule can name: a type in one of its rarities. */
    private record Choice(PetDefs.Type type, PetDefs.Rarity rarity) {}

    private static final List<When> WHENS = whens();

    private final Page page;
    /** The rule being made (TRIGGER, PET, EXCEPT while creating), or null. */
    private final Pets.Rule draft;
    /** The rule being edited (EDIT, and PET / EXCEPT from it), or -1. */
    private final int index;
    private final List<Choice> choices = new ArrayList<>();
    private final List<Pets.Condition> exceptions = new ArrayList<>();

    private static List<When> whens() {
        List<When> out = new ArrayList<>();
        for (String boss : Pets.BOSSES) {
            out.add(new When(Pets.Trigger.BOSS_SPAWN, boss));
        }
        out.add(new When(Pets.Trigger.ENTER_ISLAND, "Dungeon"));
        out.add(new When(Pets.Trigger.FISHING_HOOK, null));
        out.add(new When(Pets.Trigger.DUNGEON_START, "F7"));
        out.add(new When(Pets.Trigger.DUNGEON_START, "M7"));
        return out;
    }

    static void open(ServerPlayer player) {
        open(player, Page.LIST, null, -1);
    }

    private static void open(ServerPlayer player, Page page, Pets.Rule draft, int index) {
        ChestGui.show(player, page.title, (id, inventory, p) -> new AutopetMenu(id, inventory, player, page, draft, index));
    }

    private AutopetMenu(int id, Inventory inventory, ServerPlayer player, Page page, Pets.Rule draft, int index) {
        super(id, inventory, player, new SimpleContainer(page.rows * 9), page.rows);
        this.page = page;
        this.draft = draft;
        this.index = index;
        for (PetDefs.Type type : PetDefs.Type.values()) {
            for (PetDefs.Rarity rarity : type.rarities) {
                choices.add(new Choice(type, rarity));
            }
        }
        for (Pets.Except e : Pets.Except.values()) {
            if (e == Pets.Except.PET_EQUIPPED) {
                for (PetDefs.Type type : PetDefs.Type.values()) {
                    exceptions.add(new Pets.Condition(e, type.name()));
                }
            } else {
                exceptions.add(new Pets.Condition(e, null));
            }
        }
        fill();
    }

    /** The rule this window is about: the draft, or the one being edited (null if it's gone). */
    private Pets.Rule rule() {
        if (draft != null) {
            return draft;
        }
        List<Pets.Rule> rules = Pets.rules(player);
        return index >= 0 && index < rules.size() ? rules.get(index) : null;
    }

    @Override
    void fill() {
        frame(switch (page) {
            case LIST -> PetsMenu.TITLE;
            case EDIT, TRIGGER -> TITLE;
            case PET, EXCEPT -> draft == null ? EDIT_TITLE : page == Page.PET ? Page.TRIGGER.title : Page.PET.title;
        });
        switch (page) {
            case LIST -> fillList();
            case EDIT -> fillEdit();
            case TRIGGER -> {
                for (int i = 0; i < WHENS.size() && i < PICK_SLOTS.length; i++) {
                    When w = WHENS.get(i);
                    List<String> lore = new ArrayList<>(List.of("§aWhen:"));
                    lore.addAll(w.trigger().condition(w.param()));
                    lore.add("");
                    lore.add("§eClick to choose!");
                    set(PICK_SLOTS[i], EngineItems.named(w.trigger().icon, "§a" + w.trigger().title, lore.toArray(String[]::new)));
                }
            }
            case PET -> {
                List<Pets.Pet> owned = Pets.owned(player);
                for (int i = 0; i < choices.size() && i < PICK_SLOTS.length; i++) {
                    Choice c = choices.get(i);
                    boolean have = owned.stream().anyMatch(p -> p.type() == c.type() && p.rarity() == c.rarity());
                    ItemStack stack = Pets.stack(c.type(), c.rarity(), c.type().maxLevel,
                        have ? "§aYou own this pet." : "§cYou don't own this pet yet.", "§eClick to pick!");
                    stack.set(DataComponents.CUSTOM_NAME,
                        EngineItems.text(c.rarity().color + c.type().title + " §8(" + PetDefs.pretty(c.rarity()) + ")"));
                    set(PICK_SLOTS[i], stack);
                }
            }
            case EXCEPT -> {
                set(PICK_SLOTS[0], EngineItems.named(Items.BARRIER, "§aNo exception", "§7The rule always fires.", "",
                    "§eClick to choose!"));
                for (int i = 0; i < exceptions.size() && i + 1 < PICK_SLOTS.length; i++) {
                    Pets.Condition c = exceptions.get(i);
                    List<String> lore = new ArrayList<>(List.of("§eExcept if:"));
                    lore.addAll(c.lines());
                    lore.add("");
                    lore.add("§eClick to choose!");
                    set(PICK_SLOTS[i + 1], EngineItems.named(c.kind().icon, "§a" + c.kind().title, lore.toArray(String[]::new)));
                }
            }
        }
    }

    private void fillList() {
        List<Pets.Rule> rules = Pets.rules(player);
        // OPEN: the Autopet head's skin; a plain head until it's known.
        set(4, EngineItems.named(Items.PLAYER_HEAD, "§cAutopet", "§7Define custom §crules §7to automatically", "§7equip your pets.", "",
            "§7Rules used: §c" + rules.size() + "/" + Pets.MAX_RULES));
        for (int i = 0; i < rules.size() && i < RULE_SLOTS.length; i++) {
            set(RULE_SLOTS[i], icon(rules.get(i), i, true));
        }
        // OPEN: what the window shows instead of Create Rule with all 16 rules used.
        if (rules.size() < RULE_SLOTS.length) {
            set(RULE_SLOTS[rules.size()], EngineItems.named(Items.DYED_TERRACOTTA.green(), "§aCreate Rule",
                "§7Setup a new §cAutopet §7rule.", "", "§eClick to create!"));
        }
    }

    /** Rule [i] as its trigger's icon (a gray dye when off) named "§aOn Boss Spawn", with the rule lore. */
    private ItemStack icon(Pets.Rule rule, int i, boolean click) {
        Pets.Trigger trigger = rule.trigger();
        return EngineItems.named(rule.enabled ? trigger.icon : Items.DYE.gray(), "§a" + trigger.title,
            Pets.ruleLore(player, rule, i + 1, click).toArray(String[]::new));
    }

    private void fillEdit() {
        Pets.Rule rule = rule();
        if (rule == null) {
            return;
        }
        set(EDIT_ICON, icon(rule, index, false));
        List<String> pet = new ArrayList<>(List.of("§7Select a different pet for this rule.", ""));
        List<String> equip = Pets.equipLines(player, rule);
        pet.add(equip.get(0).replaceFirst("^§aEquip: ", "§7Current: ").replaceFirst("^§cMissing: ", "§7Current: "));
        pet.addAll(equip.subList(1, equip.size()));
        pet.add("");
        pet.add("§eClick to pick a pet!");
        set(EDIT_PET, EngineItems.named(Items.PLAYER_HEAD, "§aUse Another Pet", pet.toArray(String[]::new)));
        List<String> except = new ArrayList<>(List.of("§7Setup conditions under which this", "§7autopet rule will NOT trigger.", ""));
        List<Pets.Condition> conditions = rule.conditions();
        if (conditions.isEmpty()) {
            except.add("§8This rule has no exceptions!");
        } else {
            // OPEN: how Hypixel lists a rule's exceptions here (only a rule without any was opened).
            except.add("§eExcept if:");
            for (Pets.Condition c : conditions) {
                except.addAll(c.lines());
            }
        }
        except.add("");
        except.add("§eClick to add an exception!");
        set(EDIT_EXCEPT, EngineItems.named(Items.NETHER_QUARTZ_ORE, "§aAdd Rule Exception", except.toArray(String[]::new)));
        set(EDIT_TOGGLE, rule.enabled
            ? EngineItems.named(Items.DYE.lime(), "§aRule is enabled!", "§7You may temporarily disable this rule", "§7if you'd like.", "",
                "§eClick to disable it!")
            : EngineItems.named(Items.DYE.gray(), "§cRule is disabled!", "§7You've temporarily disabled this rule.", "",
                "§eClick to enable it!"));
        set(EDIT_DELETE, EngineItems.named(Items.CAULDRON, "§cDelete Rule", "§7Get rid of this rule now! It's trash!", "",
            "§eClick to delete!"));
    }

    @Override
    void click(int slot) {
        if (isBack(slot)) {
            switch (page) {
                case LIST -> PetsMenu.open(player);
                case EDIT, TRIGGER -> open(player, Page.LIST, null, -1);
                case PET -> open(player, draft != null ? Page.TRIGGER : Page.EDIT, draft, index);
                case EXCEPT -> open(player, draft != null ? Page.PET : Page.EDIT, draft, index);
            }
            return;
        }
        switch (page) {
            case LIST -> {
                List<Pets.Rule> rules = Pets.rules(player);
                int i = indexOf(RULE_SLOTS, slot);
                if (i >= 0 && i < rules.size()) {
                    open(player, Page.EDIT, null, i);
                } else if (i == rules.size() && i < Pets.MAX_RULES) {
                    open(player, Page.TRIGGER, new Pets.Rule(), -1);
                }
            }
            case EDIT -> {
                if (rule() == null) {
                    return;
                }
                if (slot == EDIT_PET) {
                    open(player, Page.PET, null, index);
                } else if (slot == EDIT_EXCEPT) {
                    open(player, Page.EXCEPT, null, index);
                } else if (slot == EDIT_TOGGLE) {
                    // Hypixel: a new window 1 tick later with the rule toggled, no chat.
                    Pets.toggleRule(player, index);
                    open(player, Page.EDIT, null, index);
                } else if (slot == EDIT_DELETE) {
                    // OPEN: Hypixel's answer to Delete Rule (chat, which window opens); never clicked in a recording.
                    Pets.removeRule(player, index);
                    open(player, Page.LIST, null, -1);
                }
            }
            case TRIGGER -> {
                int i = indexOf(PICK_SLOTS, slot);
                if (i >= 0 && i < WHENS.size()) {
                    draft.trigger = WHENS.get(i).trigger().name();
                    draft.param = WHENS.get(i).param();
                    open(player, Page.PET, draft, -1);
                }
            }
            case PET -> {
                int i = indexOf(PICK_SLOTS, slot);
                Pets.Rule rule = rule();
                if (i < 0 || i >= choices.size() || rule == null) {
                    return;
                }
                rule.type = choices.get(i).type().name();
                rule.rarity = choices.get(i).rarity().name();
                if (draft != null) {
                    open(player, Page.EXCEPT, draft, -1);
                } else {
                    Pets.changedRules(player);
                    open(player, Page.EDIT, null, index);
                }
            }
            case EXCEPT -> {
                int i = indexOf(PICK_SLOTS, slot);
                Pets.Rule rule = rule();
                if (i < 0 || i > exceptions.size() || rule == null) {
                    return;
                }
                if (i == 0) {
                    rule.exceptions = new ArrayList<>();
                } else {
                    rule.addException(exceptions.get(i - 1));
                }
                if (draft != null) {
                    Pets.addRule(player, draft);
                    open(player, Page.LIST, null, -1);
                } else {
                    Pets.changedRules(player);
                    open(player, Page.EDIT, null, index);
                }
            }
        }
    }

    private static int indexOf(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }
        return -1;
    }
}
