package net.rustclear.mod.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * At most one message per [ticks] server ticks from each player, for the serverbound messages
 * guests can send (a guest's game is not trusted to pace itself). Server thread only.
 */
final class Throttle {
    private final int ticks;
    private final Map<UUID, Integer> last = new HashMap<>();

    Throttle(int ticks) {
        this.ticks = ticks;
    }

    /** True (and counted) when [player] may send another one now. */
    boolean allow(ServerPlayer player) {
        int now = player.level().getServer().getTickCount();
        Integer previous = last.get(player.getUUID());
        if (previous != null && now - previous < ticks) {
            return false;
        }
        last.put(player.getUUID(), now);
        return true;
    }

    void forget(UUID id) {
        last.remove(id);
    }

    void clear() {
        last.clear();
    }
}
