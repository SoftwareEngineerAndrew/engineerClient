package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Spirit Leap menu (menus-ui.md §1): `Spirit Leap`, 9x4, the teammates' heads at 11/12/14/15 (12/14 for two),
 * black filler everywhere else. A head is named in the player's colour with `Click to teleport!`, or `This player is
 * currently dead!` for a ghost. A click puts you at the teammate's exact spot and rotation, with the enderman sound
 * and `You have teleported to X!`, in one tick.
 */
final class LeapMenu extends ChestGui {
    private final ServerLevel level;
    private final Runnable onLeap;
    private final List<UUID> targets = new ArrayList<>();
    private final List<Integer> headSlots = new ArrayList<>();

    /** Opens the menu; [onLeap] runs after a successful leap (a Spirit Leap is used up). */
    static void open(ServerPlayer player, Runnable onLeap) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new LeapMenu(id, inventory, player, onLeap),
            EngineItems.text("Spirit Leap")));
    }

    private LeapMenu(int id, Inventory inventory, ServerPlayer player, Runnable onLeap) {
        super(id, inventory, player, new SimpleContainer(36), 4);
        this.level = (ServerLevel) player.level();
        this.onLeap = onLeap;
        fill();
    }

    /** Where n heads go: 4 at 11/12/14/15 and 2 at 12/14 as recorded; 1 and 3 centred the same way (not recorded). */
    private static int[] layout(int n) {
        return switch (n) {
            case 0 -> new int[0];
            case 1 -> new int[] {13};
            case 2 -> new int[] {12, 14};
            case 3 -> new int[] {11, 13, 15};
            default -> new int[] {11, 12, 14, 15};
        };
    }

    @Override
    void fill() {
        targets.clear();
        headSlots.clear();
        for (int i = 0; i < size; i++) {
            box.setItem(i, EngineItems.filler());
        }
        // A fixed order for the dungeon: the order players joined it.
        List<ServerPlayer> mates = new ArrayList<>();
        for (ServerPlayer mate : level.players()) {
            if (mate != player && !mate.isSpectator() && mates.size() < 4) {
                mates.add(mate);
            }
        }
        int[] at = layout(mates.size());
        for (int i = 0; i < mates.size(); i++) {
            ServerPlayer mate = mates.get(i);
            String lore = Ghosts.isGhost(mate) ? "§cThis player is currently dead!" : "§eClick to teleport!";
            box.setItem(at[i], EngineItems.playerHead(mate.getGameProfile(), DungeonRun.nameColour(mate), lore));
            targets.add(mate.getUUID());
            headSlots.add(at[i]);
        }
    }

    @Override
    void click(int slot) {
        int index = headSlots.indexOf(slot);
        if (index < 0) {
            return;
        }
        ServerPlayer target = level.getServer().getPlayerList().getPlayer(targets.get(index));
        if (target == null || target.level() != level || Ghosts.absent(target)) {
            return;
        }
        player.closeContainer();
        player.teleportTo(level, target.getX(), target.getY(), target.getZ(), Set.<Relative>of(), target.getYRot(), target.getXRot(), false);
        player.resetFallDistance();
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f, 1f);
        player.sendSystemMessage(EngineItems.text("§aYou have teleported to " + DungeonRun.nameColour(target) + "§a!"));
        onLeap.run();
    }
}
