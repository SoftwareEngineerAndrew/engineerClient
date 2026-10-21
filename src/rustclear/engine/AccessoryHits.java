package net.rustclear.mod.engine;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The max accessory bag's on-hit effects that need state (accessories.md §4.2), for players who
 * carry it (SkyblockProfile.maxAccessories):
 * - Legendary Ring of Love (Wedding Ring IX): a 1 in 100 chance of +100% damage on any hit;
 * - Tarantula Ring: every 10th melee hit on the same target +15%;
 * - Reaper Orb: +2 Strength for each mob the player killed in the last 5 seconds, applied to the
 *   hit as Strength would be (OPEN: Hypixel's stack cap; none here).
 */
final class AccessoryHits {
    private static final double LOVE_CHANCE = 0.01;
    private static final int TARANTULA_EVERY = 10;
    private static final double TARANTULA_BONUS = 1.15;
    private static final int REAPER_TICKS = 100;
    private static final double REAPER_STRENGTH = 2;

    private final Map<UUID, Map<Entity, Integer>> meleeHits = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> kills = new HashMap<>();

    /** The factor on [player]'s hit on [target] at run tick [now]. */
    double factor(ServerPlayer player, Entity target, StatSheet stats, boolean melee, boolean strength, long now, Random random) {
        if (!PlayerStats.profile(player).maxAccessories) {
            return 1;
        }
        double f = random.nextDouble() < LOVE_CHANCE ? 2 : 1;
        if (melee) {
            Map<Entity, Integer> hits = meleeHits.computeIfAbsent(player.getUUID(), k -> new java.util.WeakHashMap<>());
            int n = hits.merge(target, 1, Integer::sum);
            if (n % TARANTULA_EVERY == 0) {
                f *= TARANTULA_BONUS;
            }
        }
        if (strength) {
            int recent = recentKills(player, now);
            if (recent > 0) {
                double str = stats.get(Stat.STRENGTH);
                f *= (1 + (str + REAPER_STRENGTH * recent) / 100) / (1 + str / 100);
            }
        }
        return f;
    }

    void killed(ServerPlayer player, long now) {
        if (PlayerStats.profile(player).maxAccessories) {
            kills.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).addLast(now);
        }
    }

    private int recentKills(ServerPlayer player, long now) {
        ArrayDeque<Long> q = kills.get(player.getUUID());
        if (q == null) {
            return 0;
        }
        while (!q.isEmpty() && now - q.peekFirst() > REAPER_TICKS) {
            q.pollFirst();
        }
        return q.size();
    }
}
