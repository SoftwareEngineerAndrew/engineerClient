package net.rustclear.mod;

import static net.rustclear.mod.ui.Theme.*;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.rustclear.mod.engine.Engine;
import net.rustclear.mod.engine.EngineItem;
import net.rustclear.mod.engine.ItemCategory;
import net.rustclear.mod.engine.ItemDefs;
import net.rustclear.mod.engine.Kit;
import net.rustclear.mod.engine.KitItems;
import net.rustclear.mod.engine.PetDefs;
import net.rustclear.mod.engine.Pets;
import net.rustclear.mod.engine.SkyblockProfile;
import net.rustclear.mod.ui.RustclearScreen;

/**
 * The Kit editor: the inventory every dungeon starts you with. On the left a picture of the
 * inventory (equipment, armour, bag, hotbar; slot 9 is the map's and locked) and the pet menu's
 * pets, on the right a searchable list of all the dungeon items, or, with a slot selected, that
 * item's stars, tier, quality and scrolls beside its tooltip, or, with a pet selected, its rarity,
 * level and whether it starts summoned, or, with the Power row clicked, the power stones. Saved to
 * config/rustclear/kit.json (KitItems); works from the title screen.
 *
 * Click a listed item, then a slot, to put it there at its best. Drag or right-click to move or
 * swap; shift-click empties a slot; the bar under a hotbar slot picks the slot you hold. The + in
 * the Pets row adds a pet; the Power row picks the power stone.
 */
public final class KitEditorScreen extends RustclearScreen {
    private static final int S = 18;
    private static final String MAP_KEY = Kit.key(Kit.MAP_SLOT);
    private static final String[] ARMOR_LETTERS = {"H", "C", "L", "B"};
    private static final String[] ARMOR_NAMES = {"Helmet", "Chestplate", "Leggings", "Boots"};
    private static final String[] EQUIPMENT_LETTERS = {"N", "C", "B", "G"};
    private static final String[] EQUIPMENT_NAMES = {"Necklace", "Cloak", "Belt", "Gloves / Bracelet"};
    /** Pets the editor shows: one row. */
    private static final int PET_ROW = 9;
    private static final String[] SCROLL_NAMES = {"Implosion", "Shadow Warp", "Wither Shield"};
    /** Every slot the picture shows, the map's included. */
    private static final List<String> SLOT_KEYS;

    static {
        List<String> keys = new ArrayList<>(Kit.ARMOR);
        keys.addAll(Kit.EQUIPMENT);
        for (int i = 0; i < Kit.SLOTS; i++) {
            keys.add(Kit.key(i));
        }
        SLOT_KEYS = List.copyOf(keys);
    }

    private Kit kit;
    /** The kit as last saved (or as opened), to tell whether there is anything to lose. */
    private Kit baseline;
    private boolean hasSaved;
    private final int cata;
    private final boolean masterMode;

    /** The slot being edited, or null (the item list shows). */
    private String selected;
    /** The kit pet being edited (an index), or -1. */
    private int petSelected = -1;
    /** The + in the Pets row was clicked: the right side lists the pets to add. */
    private boolean addingPet;
    /** The Power row was clicked: the right side lists the power stones. */
    private boolean choosingPower;
    private int powerScroll;
    private int powerMax;
    private final Map<String, ItemStack> petStacks = new HashMap<>();
    /** The listed item a click on a slot puts there, or null. */
    private ItemDefs.Def armed;
    /** The slot a right-click picked up to move, or null. */
    private String held;
    private String dragFrom;
    private double pressX;
    private double pressY;
    private boolean dragging;
    private boolean confirming;
    private String note;
    private int noteColor = DIM;

    private ItemCategory category = ItemCategory.ALL;
    private EditBox search;
    private String query = "";
    private final List<ItemDefs.Def> shown = new ArrayList<>();
    private int pickScroll;
    private int pickMax;
    private int previewScroll;
    private int previewMax;

    private final Map<String, ItemStack> slotStacks = new HashMap<>();
    private final Map<String, ItemStack> pickerStacks = new HashMap<>();

    // Geometry, from [layout].
    private int invX;
    private int armorX;
    private int invY;
    private int gridX;
    private int rightX;
    private int rightW;
    private int bodyY;
    private int footY;
    /** The item list's or the preview's area in the last frame, for the mouse wheel. */
    private int scrollX;
    private int scrollY;
    private int scrollW;
    private int scrollH;
    private boolean previewing;

    /**
     * Items get their data (components) from a world's registries when one loads, so on the title
     * screen no ItemStack can be made yet. Binds the vanilla defaults (data generation's own
     * lookup) when nothing has; a world loading later binds its own over them.
     */
    private static void bindItemComponents() {
        if (Items.STONE.builtInRegistryHolder().areComponentsBound()) {
            return;
        }
        long start = System.nanoTime();
        HolderLookup.Provider lookup = VanillaRegistries.createReloadableLookup(VanillaRegistries.createWorldLookup());
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup).forEach(DataComponentInitializers.PendingComponents::apply);
        RustclearMod.LOGGER.info("Kit editor: bound default item components in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    public KitEditorScreen(Screen parent) {
        super(Component.literal("Kit editor"), parent);
        bindItemComponents();
        DungeonSettings settings = RustclearMod.settings;
        cata = settings == null ? DungeonSettings.DEFAULT_CATA : settings.cataForTooltips();
        masterMode = settings == null || settings.floorOrDefault().master();
        Kit saved = KitItems.load();
        hasSaved = saved != null;
        kit = saved != null ? saved : KitItems.defaults();
        baseline = kit.copy();
        filter();
    }

    private void layout() {
        layoutPanel(Integer.MAX_VALUE, Integer.MAX_VALUE);
        bodyY = py + 30;
        footY = py + ph - 23;
        invX = px + 12;
        invY = bodyY + 13;
        armorX = invX + S + 2;
        gridX = armorX + S + 6;
        rightX = gridX + 9 * S + 14;
        rightW = px + pw - 12 - rightX;
    }

    @Override
    protected void init() {
        layout();
        search = input(rightX + 14, bodyY + 3, Math.max(20, rightW - 18), "Search items", 40);
        search.setValue(query);
        search.setResponder(text -> {
            query = text;
            pickScroll = 0;
            filter();
        });
        search.setVisible(!confirming && selected == null && petSelected < 0 && !addingPet && !choosingPower);
        addRenderableWidget(search);
    }

    private void filter() {
        shown.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (ItemDefs.Def def : ItemDefs.get().all()) {
            if (category.has(def) && (q.isEmpty() || def.name.toLowerCase(Locale.ROOT).contains(q)
                || def.id.toLowerCase(Locale.ROOT).contains(q.replace(' ', '_')))) {
                shown.add(def);
            }
        }
    }

    private boolean dirty() {
        return !kit.equals(baseline);
    }

    /** After any change to the kit: the slot pictures are drawn again from it. */
    private void changed() {
        slotStacks.clear();
        note = null;
    }

    private ItemStack petStack(Kit.Pet pet) {
        PetDefs.Type type = PetDefs.Type.of(pet.type);
        if (type == null) {
            return new ItemStack(Items.BARRIER);
        }
        PetDefs.Rarity rarity = PetDefs.fit(type, PetDefs.Rarity.of(pet.rarity));
        return petStacks.computeIfAbsent(type + ":" + rarity + ":" + pet.level, k -> Pets.stack(type, rarity, pet.level));
    }

    private void say(String text, int color) {
        note = text;
        noteColor = color;
    }

    // ---------------------------------------------------------------- items

    private ItemStack slotStack(String key) {
        Kit.Entry entry = kit.slots.get(key);
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        return slotStacks.computeIfAbsent(key, k -> build(entry));
    }

    private ItemStack pickerStack(ItemDefs.Def def) {
        return pickerStacks.computeIfAbsent(def.id, id -> build(KitItems.best(def)));
    }

    /** [entry]'s picture: a barrier for an id this game doesn't know, nothing if it can't be made at all. */
    private ItemStack build(Kit.Entry entry) {
        try {
            ItemStack stack = KitItems.stack(entry, cata, masterMode);
            return stack == null ? new ItemStack(Items.BARRIER) : stack;
        } catch (RuntimeException e) {
            RustclearMod.LOGGER.warn("Kit editor: couldn't show {}", entry.id, e);
            return ItemStack.EMPTY;
        }
    }

    /** What the item's tooltip says: its name, then its lore. */
    private static List<Component> tooltip(ItemStack stack) {
        List<Component> lines = new ArrayList<>();
        lines.add(stack.getHoverName());
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            lines.addAll(lore.lines());
        }
        return lines;
    }

    /** Whether [entry] may go in slot [key]: never the map's, and armour and equipment slots take only their own piece. */
    private static boolean fits(String key, Kit.Entry entry) {
        if (key.equals(MAP_KEY)) {
            return false;
        }
        return entry == null || !Kit.isWorn(key) || key.equals(KitItems.kind(entry.id));
    }

    private static String slotName(String key) {
        int armor = Kit.ARMOR.indexOf(key);
        if (armor >= 0) {
            return ARMOR_NAMES[armor] + " slot";
        }
        int equipment = Kit.EQUIPMENT.indexOf(key);
        if (equipment >= 0) {
            return EQUIPMENT_NAMES[equipment] + " slot";
        }
        int slot = Integer.parseInt(key);
        return slot < 9 ? "Hotbar slot " + (slot + 1) : "Inventory slot " + (slot + 1);
    }

    // ---------------------------------------------------------------- frame

    @Override
    protected void extractFrame(GuiGraphicsExtractor g, int mx, int my, float delta) {
        layout();
        panel(g);
        boolean dirty = dirty();
        header(g, "Kit editor", dirty ? "UNSAVED" : hasSaved ? "SAVED" : "DEFAULT ITEMS", dirty ? GOLD : hasSaved ? GOOD : DIM);
        boolean live = !confirming;
        int tmx = live ? mx : -1;
        int tmy = live ? my : -1;

        drawInventory(g, tmx, tmy);
        Kit.Entry editing = selected == null ? null : kit.slots.get(selected);
        if (editing == null) {
            selected = null;
        }
        if (petSelected >= kit.pets.size()) {
            petSelected = -1;
        }
        search.setVisible(live && selected == null && petSelected < 0 && !addingPet && !choosingPower);
        if (selected != null) {
            drawEditor(g, tmx, tmy, editing);
        } else if (petSelected >= 0) {
            drawPetEditor(g, tmx, tmy, kit.pets.get(petSelected));
        } else if (addingPet) {
            drawPetPicker(g, tmx, tmy);
        } else if (choosingPower) {
            drawPowerPicker(g, tmx, tmy);
        } else {
            drawPicker(g, tmx, tmy);
        }
        drawFooter(g, tmx, tmy, dirty);

        if (dragging && dragFrom != null && live) {
            g.nextStratum();
            g.item(slotStack(dragFrom), mx - 8, my - 8);
        }
        if (confirming) {
            drawConfirm(g, mx, my);
        }
    }

    private void drawInventory(GuiGraphicsExtractor g, int mx, int my) {
        text(g, "Inventory", invX, bodyY + 1, SILVER);
        for (String key : SLOT_KEYS) {
            drawSlot(g, mx, my, key);
        }
        // The bar under each hotbar slot: gold under the one held at the start.
        int barY = invY + 4 * S + 4 + 2;
        for (int i = 0; i < 9; i++) {
            int x = gridX + i * S;
            boolean on = kit.selected == i;
            boolean hover = over(mx, my, x, barY - 1, S, 6);
            rect(g, x + 2, barY, S - 4, 2, on ? GOLD : hover ? MUTED : alpha(DIM, 0x80));
            int slot = i;
            click(x, barY - 1, S, 6, () -> kit.selected = slot);
            if (hover) {
                g.setComponentTooltipForNextFrame(font, List.of(Component.literal(on ? "You start holding this slot."
                    : "Click to start holding this slot.")), mx, my);
            }
        }

        // The pet menu's pets, and a + to add one.
        int petsY = barY + 8;
        text(g, "Pets", invX, petsY, SILVER);
        int rowY = petsY + 11;
        for (int i = 0; i < PET_ROW; i++) {
            int x = gridX + i * S;
            boolean hover = over(mx, my, x, rowY, S, S);
            boolean on = i == petSelected;
            rect(g, x, rowY, S, S, PANEL_EDGE);
            rect(g, x + 1, rowY + 1, S - 2, S - 2, on ? alpha(GOLD, 0x40) : hover ? ROW_HOVER : RAIL);
            if (i < kit.pets.size()) {
                Kit.Pet pet = kit.pets.get(i);
                ItemStack stack = petStack(pet);
                g.item(stack, x + 1, rowY + 1);
                if (i == kit.activePet) {
                    outline(g, x, rowY, S, S, GOOD);
                } else if (on) {
                    outline(g, x, rowY, S, S, GOLD);
                }
                if (hover) {
                    List<Component> tip = new ArrayList<>(tooltip(stack));
                    tip.add(Component.literal(i == kit.activePet ? "§aSummoned at the start." : "§8In the pet menu."));
                    g.setComponentTooltipForNextFrame(font, tip, mx, my);
                }
                int index = i;
                click(x, rowY, S, S, () -> {
                    petSelected = petSelected == index ? -1 : index;
                    addingPet = false;
                    choosingPower = false;
                    selected = null;
                    armed = null;
                    held = null;
                });
            } else if (i == kit.pets.size()) {
                textCenter(g, "+", x + S / 2, rowY + 5, addingPet ? GOLD : hover ? TEXT : MUTED);
                if (hover) {
                    g.setComponentTooltipForNextFrame(font, List.of(Component.literal("Add a pet")), mx, my);
                }
                click(x, rowY, S, S, () -> {
                    addingPet = !addingPet;
                    choosingPower = false;
                    petSelected = -1;
                    selected = null;
                    armed = null;
                    held = null;
                });
            }
        }

        // The power stone: its icon and name, click to pick another.
        int powerY = rowY + S + 4;
        text(g, "Power", invX, powerY + 5, SILVER);
        String power = SkyblockProfile.powerId(kit.power);
        int boxW = 9 * S;
        boolean powerHover = over(mx, my, gridX, powerY, boxW, S);
        rect(g, gridX, powerY, boxW, S, PANEL_EDGE);
        rect(g, gridX + 1, powerY + 1, boxW - 2, S - 2, choosingPower ? alpha(GOLD, 0x40) : powerHover ? ROW_HOVER : RAIL);
        if (choosingPower) {
            outline(g, gridX, powerY, boxW, S, GOLD);
        }
        g.item(powerIcon(power), gridX + 1, powerY + 1);
        text(g, fit(power == null ? "Class default" : powerName(power), boxW - S - 6), gridX + S + 3, powerY + 5,
            power == null ? MUTED : TEXT);
        if (powerHover) {
            g.setComponentTooltipForNextFrame(font, power == null
                ? List.of(Component.literal("§aClass default"), Component.literal("§7Silky for Archer and Berserk,"),
                    Component.literal("§7Sighted for Mage, Bloody for the rest."), Component.literal(""),
                    Component.literal("§eClick to pick a power stone."))
                : powerTooltip(power, true), mx, my);
        }
        click(gridX, powerY, boxW, S, () -> {
            choosingPower = !choosingPower;
            powerScroll = 0;
            addingPet = false;
            petSelected = -1;
            selected = null;
            armed = null;
            held = null;
        });

        // What a click does now, under the picture.
        int ty = powerY + S + 6;
        int w = 9 * S + 2 * S + 8;
        String hint;
        int color = MUTED;
        if (armed != null) {
            hint = "Placing " + armed.name + ": click a slot. Click it in the list again to stop.";
            color = GOLD;
        } else if (held != null) {
            hint = "Moving " + slotName(held).toLowerCase(Locale.ROOT) + ": right-click the slot to move it to.";
            color = GOLD;
        } else {
            hint = "Pick an item on the right, then a slot. Click a filled slot to edit it. Drag or right-click "
                + "to move, shift-click to empty. The bar under a hotbar slot is the one you hold.";
        }
        for (String line : wrap(hint, w)) {
            if (ty + 10 > footY - 8) {
                break;
            }
            text(g, line, invX, ty, color);
            ty += 10;
        }
    }

    private int[] slotPos(String key) {
        int armor = Kit.ARMOR.indexOf(key);
        if (armor >= 0) {
            return new int[] {armorX, invY + armor * S};
        }
        int equipment = Kit.EQUIPMENT.indexOf(key);
        if (equipment >= 0) {
            return new int[] {invX, invY + equipment * S};
        }
        int slot = Integer.parseInt(key);
        if (slot < 9) {
            return new int[] {gridX + slot * S, invY + 3 * S + 4};
        }
        return new int[] {gridX + (slot - 9) % 9 * S, invY + (slot - 9) / 9 * S};
    }

    private String slotAt(double mx, double my) {
        for (String key : SLOT_KEYS) {
            int[] p = slotPos(key);
            if (mx >= p[0] && mx < p[0] + S && my >= p[1] && my < p[1] + S) {
                return key;
            }
        }
        return null;
    }

    private void drawSlot(GuiGraphicsExtractor g, int mx, int my, String key) {
        int[] p = slotPos(key);
        int x = p[0];
        int y = p[1];
        boolean hover = over(mx, my, x, y, S, S);
        boolean map = key.equals(MAP_KEY);
        Kit.Entry entry = kit.slots.get(key);
        int bg = key.equals(selected) ? alpha(GOLD, 0x40) : key.equals(held) ? alpha(TEAM_A, 0x50)
            : hover && !map ? ROW_HOVER : RAIL;
        rect(g, x, y, S, S, PANEL_EDGE);
        rect(g, x + 1, y + 1, S - 2, S - 2, bg);
        if (armed != null && hover) {
            outline(g, x, y, S, S, fits(key, KitItems.best(armed)) ? GOOD : BAD);
        } else if (key.equals(selected)) {
            outline(g, x, y, S, S, GOLD);
        }
        if (map) {
            g.item(new ItemStack(Items.FILLED_MAP), x + 1, y + 1);
            rect(g, x + 1, y + 1, S - 2, S - 2, 0x90101418);
            rect(g, x + S - 6, y + S - 6, 4, 3, MUTED);
            outline(g, x + S - 6, y + S - 9, 4, 4, MUTED);
            if (hover) {
                g.setComponentTooltipForNextFrame(font, List.of(Component.literal("§bMagical Map"),
                    Component.literal("§7Slot 9 always holds the dungeon map.")), mx, my);
            }
            return;
        }
        if (entry == null) {
            int armor = Kit.ARMOR.indexOf(key);
            int equipment = Kit.EQUIPMENT.indexOf(key);
            if (armor >= 0) {
                textCenter(g, ARMOR_LETTERS[armor], x + S / 2, y + 5, DIM);
            } else if (equipment >= 0) {
                textCenter(g, EQUIPMENT_LETTERS[equipment], x + S / 2, y + 5, alpha(TEAM_A, 0xA0));
            }
            if (hover && (armor >= 0 || equipment >= 0)) {
                g.setComponentTooltipForNextFrame(font, List.of(Component.literal(slotName(key))), mx, my);
            }
            return;
        }
        if (dragging && key.equals(dragFrom)) {
            return;
        }
        ItemStack stack = slotStack(key);
        g.item(stack, x + 1, y + 1);
        g.itemDecorations(font, stack, x + 1, y + 1);
        if (hover && !dragging) {
            g.setComponentTooltipForNextFrame(font, tooltip(stack), mx, my);
        }
    }

    private void drawPicker(GuiGraphicsExtractor g, int mx, int my) {
        inputFrame(g, rightX, bodyY, rightW, search.isFocused(), true);
        int cx = rightX;
        int cy = bodyY + 18;
        for (ItemCategory c : ItemCategory.values()) {
            int cw = font.width(c.label) + 8;
            if (cx > rightX && cx + cw > rightX + rightW) {
                cx = rightX;
                cy += 14;
            }
            cx += chip(g, mx, my, c.label, cx, cy, c == category, () -> {
                category = c;
                pickScroll = 0;
                filter();
            }) + 3;
        }
        int gy = cy + 16;
        int cols = Math.max(1, (rightW - 6) / S);
        int rows = Math.max(1, (footY - 8 - gy) / S);
        int total = (shown.size() + cols - 1) / cols;
        pickMax = Math.max(0, total - rows);
        pickScroll = Math.max(0, Math.min(pickMax, pickScroll));
        scrollX = rightX;
        scrollY = gy;
        scrollW = rightW;
        scrollH = rows * S;
        previewing = false;
        if (shown.isEmpty()) {
            text(g, "No items match.", rightX, gy + 4, DIM);
            return;
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int index = (pickScroll + r) * cols + c;
                if (index >= shown.size()) {
                    break;
                }
                ItemDefs.Def def = shown.get(index);
                int x = rightX + c * S;
                int y = gy + r * S;
                boolean hover = over(mx, my, x, y, S, S);
                boolean on = def == armed;
                rect(g, x, y, S, S, on ? alpha(GOLD, 0x50) : hover ? ROW_HOVER : RAIL);
                if (on) {
                    outline(g, x, y, S, S, GOLD);
                }
                ItemStack stack = pickerStack(def);
                g.item(stack, x + 1, y + 1);
                if (hover) {
                    g.setComponentTooltipForNextFrame(font, tooltip(stack), mx, my);
                }
                click(x, y, S, S, () -> {
                    armed = armed == def ? null : def;
                    held = null;
                });
            }
        }
        scrollbar(g, rightX + rightW - 2, gy, rows * S, rows, total, pickScroll, pickMax);
    }

    private void drawEditor(GuiGraphicsExtractor g, int mx, int my, Kit.Entry entry) {
        String key = selected;
        ItemStack stack = slotStack(key);
        ItemDefs.Def def = KitItems.def(entry.id);
        EngineItem engine = KitItems.engineItem(entry.id);
        int x = rightX;
        int y = bodyY;

        rect(g, x, y, S, S, PANEL_EDGE);
        rect(g, x + 1, y + 1, S - 2, S - 2, RAIL);
        g.item(stack, x + 1, y + 1);
        int nameW = rightW - S - 6 - font.width("Done") - 8;
        List<FormattedCharSequence> name = font.split(stack.getHoverName(), Math.max(20, nameW));
        if (!name.isEmpty()) {
            g.text(font, name.get(0), x + S + 6, y, TEXT, true);
        }
        text(g, fit(slotName(key) + (engine != null ? " · engine item" : ""), Math.max(20, nameW)), x + S + 6, y + 10, DIM);
        link(g, mx, my, "Done", x + rightW, y, () -> selected = null);

        boolean wide = rightW >= 300;
        int cw = wide ? 150 : rightW;
        int cy = y + S + 8;
        boolean any = false;
        if (def != null && KitItems.hasStars(def)) {
            any = true;
            stepper(g, mx, my, "Stars", entry.stars, 0, 5, false, x, cy, cw, v -> {
                entry.stars = v;
                if (v < 5) {
                    entry.masterStars = 0;
                }
            });
            cy += 16;
            stepper(g, mx, my, entry.stars == 5 ? "Master stars" : "Master stars (5 stars first)", entry.masterStars, 0, 5,
                false, x, cy, cw, entry.stars == 5 ? v -> entry.masterStars = v : null);
            cy += 16;
        }
        if (def != null && def.isTiered()) {
            any = true;
            stepper(g, mx, my, "Floor tier", entry.tier, 1, 10, false, x, cy, cw, v -> entry.tier = v);
            cy += 16;
            stepper(g, mx, my, "Item Quality", entry.quality, 0, 50, true, x, cy, cw, v -> entry.quality = v);
            cy += 16;
        }
        if (def != null && def.takesScrolls()) {
            any = true;
            text(g, "Necron scrolls", x, cy + 2, SILVER);
            cy += 13;
            for (int i = 0; i < ItemDefs.SCROLLS.size(); i++) {
                String scroll = ItemDefs.SCROLLS.get(i);
                boolean on = entry.scrolls.contains(scroll);
                boolean hover = over(mx, my, x, cy, cw, 12);
                if (hover) {
                    round(g, x - 2, cy - 1, cw + 2, 12, ROW_HOVER);
                }
                toggle(g, "scroll:" + i, x, cy + 1, on, GOLD);
                text(g, SCROLL_NAMES[i], x + 24, cy + 1, on ? TEXT : MUTED);
                click(x - 2, cy - 1, cw + 2, 12, () -> {
                    if (on) {
                        entry.scrolls.remove(scroll);
                    } else {
                        // Kept in the scrolls' own order, as Hypixel lists them.
                        List<String> next = new ArrayList<>();
                        for (String s : ItemDefs.SCROLLS) {
                            if (s.equals(scroll) || entry.scrolls.contains(s)) {
                                next.add(s);
                            }
                        }
                        entry.scrolls = next;
                    }
                    changed();
                });
                cy += 13;
            }
        }
        if (!any) {
            for (String line : wrap(engine != null ? "The engine's own item: it comes as it is, nothing to upgrade."
                : "Nothing to upgrade on this item.", cw)) {
                text(g, line, x, cy + 2, DIM);
                cy += 10;
            }
        }
        cy += 6;
        int bx = button(g, mx, my, "Remove", x + cw, cy, Style.DANGER, () -> {
            kit.slots.remove(key);
            selected = null;
            changed();
        });
        button(g, mx, my, "Max", bx - 4, cy, Style.SECONDARY, any ? () -> {
            Kit.Entry best = KitItems.best(def);
            best.id = entry.id;
            kit.slots.put(key, best);
            changed();
        } : null);
        cy += 17 + 8;

        // The tooltip as it will read, beside the controls or under them.
        int vx = wide ? x + cw + 12 : x;
        int vy = wide ? y + S + 8 : cy;
        int vw = wide ? rightW - cw - 12 : rightW;
        int vh = footY - 8 - vy;
        if (vh < 24) {
            previewing = false;
            return;
        }
        round(g, vx, vy, vw, vh, 0xF0100010);
        outline(g, vx, vy, vw, vh, 0xFF2D0A63);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : tooltip(stack)) {
            List<FormattedCharSequence> parts = font.split(line, vw - 10);
            lines.addAll(parts.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : parts);
        }
        int visible = (vh - 8) / 10;
        previewMax = Math.max(0, lines.size() - visible);
        previewScroll = Math.max(0, Math.min(previewMax, previewScroll));
        g.enableScissor(vx + 1, vy + 1, vx + vw - 1, vy + vh - 1);
        int ly = vy + 5;
        for (int i = previewScroll; i < lines.size() && i < previewScroll + visible + 1; i++) {
            g.text(font, lines.get(i), vx + 5, ly, 0xFFFFFFFF, true);
            ly += 10;
        }
        g.disableScissor();
        scrollbar(g, vx + vw - 4, vy + 3, vh - 6, visible, lines.size(), previewScroll, previewMax);
        scrollX = vx;
        scrollY = vy;
        scrollW = vw;
        scrollH = vh;
        previewing = true;
    }

    /** The pets that can be added: each kind at its best rarity and level, click to add. */
    private void drawPetPicker(GuiGraphicsExtractor g, int mx, int my) {
        int x = rightX;
        int y = bodyY;
        title(g, "Add a pet", x, y);
        link(g, mx, my, "Cancel", x + rightW, y, () -> addingPet = false);
        y += 18;
        if (kit.pets.size() >= PET_ROW) {
            text(g, "The kit has as many pets as it can hold.", x, y, DIM);
            return;
        }
        for (PetDefs.Type type : PetDefs.Type.values()) {
            PetDefs.Rarity rarity = type.best();
            ItemStack stack = petStack(new Kit.Pet(type.name(), rarity.name(), type.maxLevel));
            boolean hover = over(mx, my, x, y, rightW, S + 2);
            if (hover) {
                round(g, x - 2, y - 1, rightW + 2, S + 2, ROW_HOVER);
                g.setComponentTooltipForNextFrame(font, tooltip(stack), mx, my);
            }
            g.item(stack, x, y);
            text(g, type.title, x + S + 4, y + 1, TEXT);
            text(g, PetDefs.pretty(rarity) + " · level " + type.maxLevel, x + S + 4, y + 10, DIM);
            click(x - 2, y - 1, rightW + 2, S + 2, () -> {
                kit.pets.add(new Kit.Pet(type.name(), rarity.name(), type.maxLevel));
                if (kit.activePet < 0) {
                    kit.activePet = kit.pets.size() - 1;
                }
                petSelected = kit.pets.size() - 1;
                addingPet = false;
            });
            y += S + 6;
        }
        for (String line : wrap("Each dungeon starts with these pets in your pet menu (SkyBlock Menu > Pets), the "
            + "green one summoned. Pets you add in game stay until the next dungeon.", rightW)) {
            text(g, line, x, y + 2, DIM);
            y += 10;
        }
    }

    // ---------------------------------------------------------------- power stone

    /** An item standing for [power] (Hypixel's Select Power Stone icons where recorded); null: class default. */
    private static ItemStack powerIcon(String power) {
        return new ItemStack(power == null ? Items.STAINED_GLASS_PANE.lime() : switch (power) {
            case "silky" -> Items.STRING;
            case "sighted" -> Items.ENDER_EYE;
            case "bloody" -> Items.REDSTONE;
            case "shaded" -> Items.DYE.black();
            case "forceful" -> Items.BLAZE_POWDER;
            case "hurtful" -> Items.FLINT;
            case "strong" -> Items.IRON_INGOT;
            case "bizarre" -> Items.PAINTING;
            case "mythical" -> Items.NETHER_STAR;
            case "warrior" -> Items.DIAMOND;
            case "commando" -> Items.STICK;
            case "inspired" -> Items.LAPIS_LAZULI;
            default -> Items.STONE;
        });
    }

    private static String powerName(String power) {
        return Character.toUpperCase(power.charAt(0)) + power.substring(1);
    }

    /** [power]'s stats at the max build's in-run Accessory Power, and its Unique Power Bonus. */
    private static List<Component> powerTooltip(String power, boolean change) {
        int ap = SkyblockProfile.DEFAULT_ACCESSORY_POWER + SkyblockProfile.DEFAULT_DUNGEON_ACCESSORY_POWER;
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal("§a" + powerName(power)));
        tip.add(Component.literal(String.format(Locale.ROOT, "§7Stats at §6%,d§7 Accessory Power:", ap)));
        SkyblockProfile.powerStats(power, ap).forEach((stat, v) -> tip.add(Component.literal(
            String.format(Locale.ROOT, "%s%+,.2f %s", v < 0 ? "§c" : stat.color, v, stat.label))));
        Map<net.rustclear.mod.engine.Stat, Double> bonus = SkyblockProfile.uniqueBonus(power);
        if (!bonus.isEmpty()) {
            tip.add(Component.literal(""));
            tip.add(Component.literal("§7Unique Power Bonus:"));
            bonus.forEach((stat, v) -> tip.add(Component.literal(
                String.format(Locale.ROOT, "%s+%s %s", stat.color, v % 1 == 0 ? Integer.toString(v.intValue()) : v.toString(), stat.label))));
        }
        if (change) {
            tip.add(Component.literal(""));
            tip.add(Component.literal("§eClick to pick another."));
        }
        return tip;
    }

    /** The power stones: the class default, then every power; click one to select it. */
    private void drawPowerPicker(GuiGraphicsExtractor g, int mx, int my) {
        int x = rightX;
        int y = bodyY;
        title(g, "Select Power Stone", x, y);
        link(g, mx, my, "Done", x + rightW, y, () -> choosingPower = false);
        y += 18;
        List<String> rows = new ArrayList<>();
        rows.add(null);
        rows.addAll(SkyblockProfile.powers());
        int rowH = S + 4;
        int visible = Math.max(1, (footY - 8 - 22 - y) / rowH);
        powerMax = Math.max(0, rows.size() - visible);
        powerScroll = Math.max(0, Math.min(powerMax, powerScroll));
        scrollX = x;
        scrollY = y;
        scrollW = rightW;
        scrollH = visible * rowH;
        previewing = false;
        String current = SkyblockProfile.powerId(kit.power);
        int ap = SkyblockProfile.DEFAULT_ACCESSORY_POWER + SkyblockProfile.DEFAULT_DUNGEON_ACCESSORY_POWER;
        int listY = y;
        for (int i = powerScroll; i < rows.size() && i < powerScroll + visible; i++) {
            String power = rows.get(i);
            boolean on = java.util.Objects.equals(power, current);
            boolean hover = over(mx, my, x, y, rightW - 6, rowH);
            if (on || hover) {
                round(g, x - 2, y - 1, rightW - 4, rowH, on ? alpha(GOLD, 0x40) : ROW_HOVER);
            }
            g.item(powerIcon(power), x, y + 1);
            text(g, power == null ? "Class default" : powerName(power), x + S + 4, y + 1, on ? GOLD : TEXT);
            String stats;
            if (power == null) {
                stats = "Silky (Archer, Berserk), Sighted (Mage), else Bloody";
            } else {
                List<String> parts = new ArrayList<>();
                SkyblockProfile.powerStats(power, ap).forEach((stat, v) ->
                    parts.add(String.format(Locale.ROOT, "%+,.0f %s", v, stat.label)));
                stats = String.join(", ", parts);
            }
            text(g, fit(stats, rightW - S - 12), x + S + 4, y + 10, DIM);
            if (hover && power != null) {
                g.setComponentTooltipForNextFrame(font, powerTooltip(power, false), mx, my);
            }
            click(x - 2, y - 1, rightW - 4, rowH, () -> {
                kit.power = power;
                choosingPower = false;
                changed();
            });
            y += rowH;
        }
        scrollbar(g, x + rightW - 2, listY, visible * rowH, visible, rows.size(), powerScroll, powerMax);
        y = listY + visible * rowH + 2;
        for (String line : wrap(String.format(Locale.ROOT, "Stats shown at %,d Accessory Power (the max build in a run). "
            + "On each player's Hypixel stats, your Hypixel power stone is used.", ap), rightW)) {
            if (y + 10 > footY - 8) {
                break;
            }
            text(g, line, x, y, DIM);
            y += 10;
        }
    }

    /** One kit pet: its rarity, level, whether it starts summoned, and its tooltip. */
    private void drawPetEditor(GuiGraphicsExtractor g, int mx, int my, Kit.Pet pet) {
        PetDefs.Type type = PetDefs.Type.of(pet.type);
        int index = petSelected;
        int x = rightX;
        int y = bodyY;
        ItemStack stack = petStack(pet);
        rect(g, x, y, S, S, PANEL_EDGE);
        rect(g, x + 1, y + 1, S - 2, S - 2, RAIL);
        g.item(stack, x + 1, y + 1);
        g.text(font, stack.getHoverName(), x + S + 6, y, TEXT, true);
        text(g, "Pet " + (index + 1), x + S + 6, y + 10, DIM);
        link(g, mx, my, "Done", x + rightW, y, () -> petSelected = -1);
        if (type == null) {
            return;
        }
        boolean wide = rightW >= 300;
        int cw = wide ? 150 : rightW;
        int cy = y + S + 8;
        text(g, "Rarity", x, cy + 3, MUTED);
        int rx = x + 44;
        for (PetDefs.Rarity r : type.rarities) {
            rx += chip(g, mx, my, PetDefs.pretty(r), rx, cy + 1, r.name().equals(PetDefs.fit(type, PetDefs.Rarity.of(pet.rarity)).name()), () -> {
                pet.rarity = r.name();
            }) + 3;
        }
        cy += 16;
        stepper(g, mx, my, "Level", pet.level, 1, type.maxLevel, true, x, cy, cw, v -> pet.level = v);
        cy += 18;
        boolean on = kit.activePet == index;
        boolean hover = over(mx, my, x, cy, cw, 12);
        if (hover) {
            round(g, x - 2, cy - 1, cw + 2, 12, ROW_HOVER);
        }
        toggle(g, "pet-active", x, cy + 1, on, GOOD);
        text(g, "Summoned at the start", x + 24, cy + 1, on ? TEXT : MUTED);
        click(x - 2, cy - 1, cw + 2, 12, () -> kit.activePet = on ? -1 : index);
        cy += 18;
        button(g, mx, my, "Remove", x + cw, cy, Style.DANGER, () -> {
            kit.pets.remove(index);
            if (kit.activePet == index) {
                kit.activePet = -1;
            } else if (kit.activePet > index) {
                kit.activePet--;
            }
            petSelected = -1;
        });
        cy += 17 + 8;

        int vx = wide ? x + cw + 12 : x;
        int vy = wide ? y + S + 8 : cy;
        int vw = wide ? rightW - cw - 12 : rightW;
        int vh = footY - 8 - vy;
        if (vh < 24) {
            previewing = false;
            return;
        }
        round(g, vx, vy, vw, vh, 0xF0100010);
        outline(g, vx, vy, vw, vh, 0xFF2D0A63);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : tooltip(stack)) {
            List<FormattedCharSequence> parts = font.split(line, vw - 10);
            lines.addAll(parts.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : parts);
        }
        int visible = (vh - 8) / 10;
        previewMax = Math.max(0, lines.size() - visible);
        previewScroll = Math.max(0, Math.min(previewMax, previewScroll));
        g.enableScissor(vx + 1, vy + 1, vx + vw - 1, vy + vh - 1);
        int ly = vy + 5;
        for (int i = previewScroll; i < lines.size() && i < previewScroll + visible + 1; i++) {
            g.text(font, lines.get(i), vx + 5, ly, 0xFFFFFFFF, true);
            ly += 10;
        }
        g.disableScissor();
        scrollbar(g, vx + vw - 4, vy + 3, vh - 6, visible, lines.size(), previewScroll, previewMax);
        scrollX = vx;
        scrollY = vy;
        scrollW = vw;
        scrollH = vh;
        previewing = true;
    }

    /**
     * A number from [min] to [max] with - and + (and -10 / +10 when [big]) at the right of a [w]
     * wide row; [set] null shows it greyed out.
     */
    private void stepper(GuiGraphicsExtractor g, int mx, int my, String label, int value, int min, int max, boolean big,
                         int x, int y, int w, IntConsumer set) {
        text(g, label, x, y + 3, set == null ? DIM : MUTED);
        int right = x + w;
        String[] steps = big ? new String[] {"+10", "+", "-", "-10"} : new String[] {"+", "-"};
        int[] deltas = big ? new int[] {10, 1, -1, -10} : new int[] {1, -1};
        for (int i = 0; i < steps.length; i++) {
            int next = Math.max(min, Math.min(max, value + deltas[i]));
            int cw = choiceWidth(steps[i]);
            choice(g, mx, my, steps[i], right - cw, y, false, set != null && next != value ? () -> {
                set.accept(next);
                changed();
            } : null);
            right -= cw + 2;
            if (i == steps.length / 2 - 1) {
                // The value sits between the + and - buttons.
                String v = Integer.toString(value);
                textCenter(g, v, right - 10, y + 3, set == null ? DIM : GOLD);
                right -= 21;
            }
        }
    }

    private void drawFooter(GuiGraphicsExtractor g, int mx, int my, boolean dirty) {
        rect(g, px + 8, footY - 5, pw - 16, 1, PANEL_EDGE);
        boolean narrow = pw < 460;
        int right = px + pw - 8;
        right = button(g, mx, my, "Back", right, footY, Style.SECONDARY, this::onClose) - 4;
        right = button(g, mx, my, "Clear all", right, footY, Style.DANGER, kit.slots.isEmpty() && kit.pets.isEmpty() && kit.power == null ? null : () -> {
            kit.slots.clear();
            kit.pets.clear();
            kit.activePet = -1;
            kit.power = null;
            petSelected = -1;
            addingPet = false;
            choosingPower = false;
            selected = null;
            held = null;
            changed();
            say("Emptied. Save to start dungeons with nothing but the map.", MUTED);
        }) - 4;
        right = button(g, mx, my, narrow ? "Default" : "Reset to default kit", right, footY, Style.SECONDARY, () -> {
            kit = KitItems.defaults();
            selected = null;
            petSelected = -1;
            addingPet = false;
            choosingPower = false;
            held = null;
            changed();
            say("The default starting items. Save to keep them.", MUTED);
        }) - 4;
        right = button(g, mx, my, "Save", right, footY, Style.PRIMARY, this::save) - 8;
        String text = note != null ? note : dirty ? "Unsaved changes."
            : hasSaved ? "Every dungeon starts you with this kit." : "No kit saved: dungeons give the default items.";
        text(g, fit(text, right - px - 12), px + 12, footY + 5, note != null ? noteColor : dirty ? GOLD : DIM);
    }

    private void drawConfirm(GuiGraphicsExtractor g, int mx, int my) {
        g.nextStratum();
        // Nothing under the question can be clicked while it is open.
        click(0, 0, width, height, () -> {
        });
        g.fill(0, 0, width, height, 0xA0000000);
        int bw = Math.min(260, width - 16);
        int bh = 76;
        int bx = (width - bw) / 2;
        int by = (height - bh) / 2;
        round(g, bx, by, bw, bh, PANEL);
        outline(g, bx, by, bw, bh, PANEL_EDGE);
        rect(g, bx + 10, by + 10, 3, 9, GOLD);
        title(g, "Leave without saving?", bx + 18, by + 8);
        text(g, fit("Your kit has changes that aren't saved yet.", bw - 20), bx + 10, by + 26, MUTED);
        int right = bx + bw - 8;
        int y = by + bh - 23;
        right = button(g, mx, my, "Stay", right, y, Style.SECONDARY, () -> confirming = false) - 4;
        right = button(g, mx, my, "Leave", right, y, Style.DANGER, this::back) - 4;
        button(g, mx, my, bw < 220 ? "Save" : "Save and leave", right, y, Style.PRIMARY, () -> {
            if (save()) {
                back();
            } else {
                confirming = false;
            }
        });
    }

    // ---------------------------------------------------------------- actions

    private boolean save() {
        try {
            KitItems.save(kit);
        } catch (Exception e) {
            RustclearMod.LOGGER.warn("Couldn't save the kit", e);
            say("Couldn't save your kit: " + e.getMessage(), BAD);
            return false;
        }
        baseline = kit.copy();
        hasSaved = true;
        say(Engine.isEngineClient() ? "Saved, and you have it now. Every dungeon starts you with it."
            : "Saved. Every dungeon starts you with this kit.", GOOD);
        return true;
    }

    /** Moves [from]'s item to [to], swapping with what is there, if both fit where they'd go. */
    private void swap(String from, String to) {
        if (from.equals(to)) {
            return;
        }
        Kit.Entry a = kit.slots.get(from);
        Kit.Entry b = kit.slots.get(to);
        if (!fits(to, a) || !fits(from, b)) {
            say(to.equals(MAP_KEY) || from.equals(MAP_KEY) ? "Slot 9 always holds the map."
                : "Armour and equipment slots only take their own piece.", BAD);
            return;
        }
        if (a == null) {
            kit.slots.remove(to);
        } else {
            kit.slots.put(to, a);
        }
        if (b == null) {
            kit.slots.remove(from);
        } else {
            kit.slots.put(from, b);
        }
        if (from.equals(selected)) {
            selected = to;
        } else if (to.equals(selected)) {
            selected = from;
        }
        changed();
    }

    private void place(String key) {
        Kit.Entry entry = KitItems.best(armed);
        if (!fits(key, entry)) {
            say(key.equals(MAP_KEY) ? "Slot 9 always holds the map."
                : Kit.isArmor(key) ? "Only a " + ARMOR_NAMES[Kit.ARMOR.indexOf(key)].toLowerCase(Locale.ROOT) + " goes there."
                : Kit.isEquipment(key) ? "Only a " + EQUIPMENT_NAMES[Kit.EQUIPMENT.indexOf(key)].toLowerCase(Locale.ROOT) + " goes there."
                : "That doesn't go there.", BAD);
            return;
        }
        kit.slots.put(key, entry);
        armed = null;
        selected = key;
        petSelected = -1;
        addingPet = false;
        choosingPower = false;
        previewScroll = 0;
        changed();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!confirming) {
            String key = slotAt(event.x(), event.y());
            if (key != null) {
                search.setFocused(false);
                pressSlot(key, event);
                return true;
            }
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && (held != null || armed != null)) {
                // A right click off the slots puts down what was picked up.
                held = null;
                armed = null;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void pressSlot(String key, MouseButtonEvent event) {
        boolean map = key.equals(MAP_KEY);
        Kit.Entry entry = kit.slots.get(key);
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (held == null) {
                if (entry != null && !map) {
                    held = key;
                    armed = null;
                }
            } else {
                String from = held;
                held = null;
                swap(from, key);
            }
            return;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || map) {
            if (map) {
                say("Slot 9 always holds the map.", MUTED);
            }
            return;
        }
        if (event.hasShiftDown()) {
            if (kit.slots.remove(key) != null) {
                if (key.equals(selected)) {
                    selected = null;
                }
                changed();
            }
            return;
        }
        if (armed != null) {
            place(key);
            return;
        }
        if (held != null) {
            String from = held;
            held = null;
            swap(from, key);
            return;
        }
        if (entry == null) {
            selected = null;
            return;
        }
        petSelected = -1;
        addingPet = false;
        choosingPower = false;
        dragFrom = key;
        pressX = event.x();
        pressY = event.y();
        dragging = false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragFrom != null) {
            if (Math.abs(event.x() - pressX) > 3 || Math.abs(event.y() - pressY) > 3) {
                dragging = true;
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragFrom != null) {
            String from = dragFrom;
            dragFrom = null;
            if (dragging) {
                dragging = false;
                String to = slotAt(event.x(), event.y());
                if (to != null) {
                    swap(from, to);
                }
            } else {
                // A click, not a drag: edit this slot.
                selected = from.equals(selected) ? null : from;
                previewScroll = 0;
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollXAmount, double scrollYAmount) {
        if (!confirming && mx >= scrollX && mx < scrollX + scrollW && my >= scrollY && my < scrollY + scrollH) {
            int step = -(int) Math.signum(scrollYAmount);
            if (previewing) {
                previewScroll = Math.max(0, Math.min(previewMax, previewScroll + step * 3));
            } else if (choosingPower) {
                powerScroll = Math.max(0, Math.min(powerMax, powerScroll + step));
            } else {
                pickScroll = Math.max(0, Math.min(pickMax, pickScroll + step));
            }
            return true;
        }
        return super.mouseScrolled(mx, my, scrollXAmount, scrollYAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (confirming && event.isEscape()) {
            confirming = false;
            return true;
        }
        if (event.isEscape() && (armed != null || held != null)) {
            armed = null;
            held = null;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (dirty() && !confirming) {
            confirming = true;
            search.setFocused(false);
            return;
        }
        back();
    }
}
