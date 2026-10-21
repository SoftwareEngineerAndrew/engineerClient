package net.rustclear.mod.engine.boss;

import net.rustclear.mod.engine.SimLog;

/**
 * The boss fight inside a run (OVERHAUL_PLAN.md §1): one phase at a time, Maxor -> Storm ->
 * Goldor -> Necron (-> the Wither King on M7), on a clock that restarts at each phase. It lives in
 * DungeonRun, so the map, score, tab list and party carry over from the clear.
 */
public final class BossFight {
    public final BossContext ctx;
    /** The current phase's clock; phases schedule their scripts on it. */
    public final Scheduler clock = new Scheduler();
    private BossPhase phase;
    private boolean ended;
    /** Effects that outlive their phase (Storm's corpse into P3), ticked until they return false. */
    private final java.util.List<java.util.function.BooleanSupplier> lingering = new java.util.ArrayList<>();

    public BossFight(BossContext ctx) {
        this.ctx = ctx;
    }

    /** Whether a phase is running. */
    public boolean active() {
        return phase != null;
    }

    public BossPhase phase() {
        return phase;
    }

    public boolean ended() {
        return ended;
    }

    /** Ends the current phase (if any) and starts [next] at clock 0. */
    public void begin(BossPhase next) {
        if (phase != null) {
            phase.dispose();
        }
        clock.reset();
        phase = next;
        if (next != null) {
            SimLog.event("phase", "name", next.name());
            next.start(this);
        }
    }

    /** Ticks [effect] every server tick from the next one on, across phase changes, until it returns false. */
    public void linger(java.util.function.BooleanSupplier effect) {
        lingering.add(effect);
    }

    /** Every server tick, after the run's own systems. */
    public void tick() {
        if (!lingering.isEmpty()) {
            lingering.removeIf(e -> !e.getAsBoolean());
        }
        if (phase == null) {
            return;
        }
        phase.tick();
        clock.tick();
        if (phase.ended()) {
            ended = true;
            phase.dispose();
            phase = null;
            return;
        }
        BossPhase next = phase.next();
        if (next != null) {
            begin(next);
            // This tick is the new phase's clock 0: what it scheduled there runs now.
            clock.tick();
        }
    }

    /** A right click on [entity]: the running phase's, if it takes it. */
    public boolean use(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.entity.Entity entity) {
        return phase != null && phase.use(player, entity);
    }

    public boolean useBlock(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos pos) {
        return phase != null && phase.useBlock(player, pos);
    }

    public boolean hitBlock(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos pos) {
        return phase != null && phase.hitBlock(player, pos);
    }

    public boolean attack(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.entity.Entity entity) {
        return phase != null && phase.attack(player, entity);
    }

    public boolean boom(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos pos) {
        return phase != null && phase.boom(player, pos);
    }

    /** The run is torn down. */
    public void dispose() {
        if (phase != null) {
            phase.dispose();
            phase = null;
        }
        lingering.clear();
        clock.reset();
        ctx.entities().discardAll();
        ctx.blocks().restore();
    }
}
