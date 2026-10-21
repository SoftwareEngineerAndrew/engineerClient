package net.rustclear.mod.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Lava bounces you up instead of burning you (pearls-teleports.md §9, main): straight up at 2.25 b/t, or 3.0375 when
 * looking up past -46 degrees, on the first tick in lava; about 300 ticks of (harmless) fire; no health lost; a hurt
 * animation and sound on 84% of bounces; at most one bounce every 12 ticks. The lava itself stays static
 * (EngineFluidMixin).
 */
final class Lava {
    private static final double LOW = 2.2499542;
    private static final double HIGH = 3.0375389;
    /** Looking up past this pitch gives the high bounce (-90..-46 high, -27..+70 low; between unrecorded). */
    private static final float HIGH_PITCH = -46f;
    private static final int GAP_TICKS = 12;
    private static final int FIRE_TICKS = 300;
    private static final double HURT_SHARE = 0.84;

    private final DungeonRun run;
    private final Map<UUID, Long> lastBounce = new HashMap<>();
    private long ticks;

    Lava(DungeonRun run) {
        this.run = run;
    }

    void tick() {
        ticks++;
        for (ServerPlayer player : run.level.players()) {
            if (player.isSpectator() || !inLava(player)) {
                continue;
            }
            long last = lastBounce.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2);
            if (ticks - last < GAP_TICKS) {
                continue;
            }
            lastBounce.put(player.getUUID(), ticks);
            Vec3 v = new Vec3(0, player.getXRot() <= HIGH_PITCH ? HIGH : LOW, 0);
            player.setDeltaMovement(v);
            player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), v));
            player.setRemainingFireTicks(FIRE_TICKS);
            if (player.getRandom().nextDouble() < HURT_SHARE) {
                run.level.getChunkSource().sendToTrackingPlayersAndSelf(player, new ClientboundHurtAnimationPacket(player));
                run.level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1f, 1f);
            }
        }
    }

    private boolean inLava(ServerPlayer player) {
        BlockPos feet = player.blockPosition();
        return run.level.getBlockState(feet).is(Blocks.LAVA) || run.level.getBlockState(feet.below()).is(Blocks.LAVA)
            && player.getY() - feet.getY() < 0.2;
    }
}
