package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Hypixel's Loadouts page, `(1/3) Loadouts` (menus-skyblock.md §10): the worn armour and equipment in
 * Stats & Equipment's columns, the summoned pet, the Power Stone and Stats Tuning with the profile's
 * numbers, and Go Back only when opened from the SkyBlock Menu. OPEN: Rustclear has no saved
 * presets, so the twelve preset slots stay filler and the sub-pages don't open.
 */
final class LoadoutsMenu extends ChestGui {
    static final String TITLE = "(1/3) Loadouts";
    private static final int[] ARMOR = {11, 20, 29, 38};
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final int[] EQUIPMENT = {10, 19, 28, 37};

    private final boolean fromMenu;

    static void open(ServerPlayer player, boolean fromMenu) {
        show(player, TITLE, (id, inventory, p) -> new LoadoutsMenu(id, inventory, player, fromMenu));
    }

    private LoadoutsMenu(int id, Inventory inventory, ServerPlayer player, boolean fromMenu) {
        super(id, inventory, player, new SimpleContainer(SIZE));
        this.fromMenu = fromMenu;
        fill();
    }

    @Override
    void fill() {
        frame(fromMenu ? SkyblockMenu.TITLE : null);
        Equipment.Slot[] slots = Equipment.Slot.values();
        for (int i = 0; i < 4; i++) {
            ItemStack worn = Equipment.get(player, slots[i]);
            if (!worn.isEmpty()) {
                set(EQUIPMENT[i], worn.copy());
            }
            ItemStack armor = player.getItemBySlot(ARMOR_SLOTS[i]);
            if (!armor.isEmpty()) {
                set(ARMOR[i], armor.copy());
            }
        }
        Pets.Pet pet = Pets.active(player);
        if (pet != null) {
            set(21, Pets.item(pet));
        }
        SkyblockProfile profile = PlayerStats.profile(player);
        DungeonClass c = PlayerStats.classOf(player);
        String power = profile.powerFor(c);
        List<String> stone = new ArrayList<>(List.of("§7Choose your selected Power Stone.", "",
            "§7Current: §a" + Character.toUpperCase(power.charAt(0)) + power.substring(1), "", "§7Stats:"));
        profile.powerStats(c, PlayerStats.inDungeon(player)).forEach((stat, v) -> stone.add(stat.color + "+" + MenuText.decimal2(v) + stat.symbol + " " + stat.label));
        Map<Stat, Double> unique = SkyblockProfile.uniqueBonus(power);
        if (!unique.isEmpty()) {
            stone.add("");
            stone.add("§7Unique Power Bonus:");
            unique.forEach((stat, v) -> stone.add(stat.color + "+" + MenuText.decimal2(v) + stat.symbol + " " + stat.label));
        }
        stone.addAll(List.of("", "§eClick to view!"));
        // OPEN: the stone's own icon (Hypixel shows the stone item); a plain stone here.
        set(27, EngineItems.named(Items.STONE, "§aPower Stone", stone.toArray(String[]::new)));
        boolean in = PlayerStats.inDungeon(player);
        int points = profile.accessoryPower(in) / 10;
        Map<Stat, Double> tuning = profile.tuningStats(in);
        double spent = profile.tuning == null ? 0 : profile.tuning.values().stream().mapToDouble(v -> v == null ? 0 : v).sum();
        List<String> lore = new ArrayList<>(List.of("§7Optimize your build to your liking by using", "§eTuning Points§7.", "",
            "§7Every §610 MP §7grants §e1 Tuning Point§7.", "",
            String.format(Locale.ROOT, "§7Magical Power: §6%,d", profile.accessoryPower(in)),
            String.format(Locale.ROOT, "§7Tuning Points: §e%,d", points)));
        int unassigned = (int) Math.max(0, points - spent);
        if (profile.tuning != null && unassigned > 0) {
            lore.add("§7Unassigned Points: §c" + unassigned + "!!!");
        }
        if (!tuning.isEmpty()) {
            lore.add("");
            lore.add("§7Your tuning:");
            tuning.forEach((stat, v) -> lore.add(stat.color + "+" + MenuText.decimal2(v) + stat.symbol + " " + stat.label));
        }
        lore.addAll(List.of("", "§eClick to view!"));
        set(36, EngineItems.named(Items.COMPARATOR, "§aStats Tuning", lore.toArray(String[]::new)));
        set(44, EngineItems.named(Items.ARROW, "§aNext Page", "§ePage 2"));
    }

    @Override
    void click(int slot) {
        if (isBack(slot)) {
            SkyblockMenu.open(player);
        }
    }
}
