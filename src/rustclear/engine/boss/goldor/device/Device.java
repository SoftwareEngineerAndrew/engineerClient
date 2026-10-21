package net.rustclear.mod.engine.boss.goldor.device;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * One of P3's four devices (boss-p3-goldor.md §6): Simon Says (S1), Lights (S2), Arrow Align (S3),
 * the target / i4 (S4). It runs all through P3, whatever section is in progress (a later
 * section's device can be done early: GoldorPhase credits it then). Inputs reach it through the
 * phase; it reports a completion through [Host.completed].
 */
public abstract class Device {
    /** What GoldorPhase gives a device. */
    public interface Host {
        net.rustclear.mod.engine.boss.BossContext ctx();

        /** Ticks since "Who dares trespass into my domain?" (Goldor's n). */
        long n();

        java.util.Random random();

        /** Runs [action] [delay] ticks from now on the phase clock (dropped if P3 ends first). */
        void later(long delay, Runnable action);

        /** [device] was completed by [by] ([twice]: Hypixel announced it twice in the tick). */
        void completed(Device device, ServerPlayer by, boolean twice);

        /** Whether P3's inputs are live (false before n = -1 and after the core opens). */
        boolean live();
    }

    protected final Host host;
    private boolean done;

    protected Device(Host host) {
        this.host = host;
    }

    /** "SS", "Lights", "Arrows", "Target" (Station labels). */
    public abstract String label();

    /** Its section (1-4). */
    public abstract int section();

    /** P3 starts: put the device in its starting state (blocks, frames, lamps). */
    public abstract void start();

    /** Every server tick of P3. */
    public void tick() {
    }

    public boolean useBlock(ServerPlayer player, BlockPos pos) {
        return false;
    }

    public boolean hitBlock(ServerPlayer player, BlockPos pos) {
        return false;
    }

    public boolean useEntity(ServerPlayer player, Entity entity) {
        return false;
    }

    public boolean attackEntity(ServerPlayer player, Entity entity) {
        return false;
    }

    public final boolean done() {
        return done;
    }

    /** Whether a player has it running (Simon Says: started); for the LAN check. */
    public boolean busy() {
        return false;
    }

    /** Marks it done and tells the phase (once). */
    protected final void complete(ServerPlayer by, boolean twice) {
        if (done) {
            return;
        }
        done = true;
        host.completed(this, by, twice);
    }

    /** Removes what it spawned (blocks go back through BossBlocks with the rest of the arena). */
    public void dispose() {
    }
}
