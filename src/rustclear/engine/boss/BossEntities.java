package net.rustclear.mod.engine.boss;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.world.entity.Entity;

/**
 * Every entity the Watcher's camp and the bosses spawn: the withers, adds, heads, crystals,
 * stands and debris. They live here, not in MobSystem, so its per-tick aggro loop (n²) and its
 * whole-level sweep never see them; the phase that spawned them ticks and removes them.
 */
public final class BossEntities {
    private final Set<Entity> entities = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Registers [entity] (already added to the level) and returns it. */
    public <T extends Entity> T add(T entity) {
        entities.add(entity);
        return entity;
    }

    public boolean contains(Entity entity) {
        return entities.contains(entity);
    }

    /** Removes [entity] from the level and the registry. */
    public void discard(Entity entity) {
        entities.remove(entity);
        if (!entity.isRemoved()) {
            entity.discard();
        }
    }

    /** How many are registered (for the profiling log). */
    public int size() {
        return entities.size();
    }

    /** Forgets entities something else already removed. */
    public void prune() {
        entities.removeIf(Entity::isRemoved);
    }

    /** Removes everything (the run ends, or a new one is built). */
    public void discardAll() {
        List<Entity> all = new ArrayList<>(entities);
        entities.clear();
        for (Entity e : all) {
            if (!e.isRemoved()) {
                e.discard();
            }
        }
    }
}
