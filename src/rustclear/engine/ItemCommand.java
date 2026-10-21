package net.rustclear.mod.engine;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.rustclear.mod.Floor;
import net.rustclear.mod.RustclearMod;

/**
 * /rcitem opens the dungeon item browser; /rcitem give &lt;ID&gt; [stars] [masterStars] [tier] [quality]
 * hands one item over for a spot check. Unset numbers are a dungeon's best: five stars, five master
 * stars, this floor's tier (F7 7, M7 10) and 50/50 quality. Items the engine already had before
 * the item list ([EngineItem], such as the Hyperion and Terminator in the starting hotbar) come as
 * that engine item.
 */
final class ItemCommand {
    private ItemCommand() {
    }

    static void register(MinecraftServer s) {
        logSkipped();
        s.getCommands().getDispatcher().register(Commands.literal("rcitem")
            .executes(c -> {
                ItemBrowser.open(c.getSource().getPlayerOrException());
                return 1;
            })
            .then(Commands.literal("give")
                .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("id", StringArgumentType.word())
                    .suggests((c, b) -> {
                        String typed = b.getRemaining().toUpperCase(Locale.ROOT);
                        for (ItemDefs.Def def : ItemDefs.get().all()) {
                            if (def.id.startsWith(typed)) {
                                b.suggest(def.id);
                            }
                        }
                        return b.buildFuture();
                    })
                    .executes(c -> give(c, null, null, null, null))
                    .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("stars", IntegerArgumentType.integer(0, ItemStats.MAX_STARS))
                        .executes(c -> give(c, IntegerArgumentType.getInteger(c, "stars"), null, null, null))
                        .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("masterStars", IntegerArgumentType.integer(0, ItemStats.MAX_MASTER_STARS))
                            .executes(c -> give(c, IntegerArgumentType.getInteger(c, "stars"), IntegerArgumentType.getInteger(c, "masterStars"), null, null))
                            .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("tier", IntegerArgumentType.integer(1, ItemStats.MAX_TIER))
                                .executes(c -> give(c, IntegerArgumentType.getInteger(c, "stars"), IntegerArgumentType.getInteger(c, "masterStars"),
                                    IntegerArgumentType.getInteger(c, "tier"), null))
                                .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("quality", IntegerArgumentType.integer(0, ItemStats.MAX_QUALITY))
                                    .executes(c -> give(c, IntegerArgumentType.getInteger(c, "stars"), IntegerArgumentType.getInteger(c, "masterStars"),
                                        IntegerArgumentType.getInteger(c, "tier"), IntegerArgumentType.getInteger(c, "quality"))))))))));
    }

    private static int give(CommandContext<CommandSourceStack> c, Integer stars, Integer masterStars, Integer tier, Integer quality)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ItemDefs.Def def = ItemDefs.get().def(StringArgumentType.getString(c, "id"));
        if (def == null) {
            player.sendSystemMessage(EngineItems.text("§cNo dungeon item with that id. §7(/rcitem to browse them)"));
            return 0;
        }
        ItemStack stack = stack(player, def, stars, masterStars, tier, quality);
        if (!player.getInventory().add(stack)) {
            player.sendSystemMessage(EngineItems.text("§cYour inventory is full."));
            return 0;
        }
        player.inventoryMenu.broadcastChanges();
        player.sendSystemMessage(EngineItems.text("§aGave you ").copy().append(stack.getHoverName()));
        return 1;
    }

    /** [def] for [player]: the engine's own item where there is one, else the data item (nulls: a dungeon's best). */
    static ItemStack stack(ServerPlayer player, ItemDefs.Def def, Integer stars, Integer masterStars, Integer tier, Integer quality) {
        EngineItem engine = engineItem(def);
        if (engine != null) {
            return engine == EngineItem.DUNGEONBREAKER ? ItemSystem.dungeonbreakerStack(ItemSystem.MAX_CHARGES) : engine.stack();
        }
        Floor floor = EngineServer.settings().floorOrDefault();
        ItemDefs.Instance best = DataItems.best(def, floor.master() ? ItemStats.MAX_TIER : 7);
        ItemDefs.Instance item = new ItemDefs.Instance(def,
            stars != null ? stars : best.stars(),
            masterStars != null ? masterStars : best.masterStars(),
            tier != null ? tier : best.tier(),
            quality != null ? quality : best.quality(),
            best.scrolls());
        return DataItems.stack(item, PlayerStats.profile(player).cataLevel(), floor.master());
    }

    /** The engine item [def] already is (the Hyperion, Terminator...), or null. */
    static EngineItem engineItem(ItemDefs.Def def) {
        for (EngineItem item : EngineItem.values()) {
            if (item.hypixelId().equals(def.id)) {
                return item;
            }
        }
        return null;
    }

    /** The items the engine already had, which /rcitem hands out as those rather than again from the list. */
    private static void logSkipped() {
        List<String> skipped = new ArrayList<>();
        for (ItemDefs.Def def : ItemDefs.get().all()) {
            if (engineItem(def) != null) {
                skipped.add(def.id);
            }
        }
        RustclearMod.LOGGER.info("Dungeon items: {} from the item list; already engine items: {}", ItemDefs.get().all().size(), skipped);
    }
}
