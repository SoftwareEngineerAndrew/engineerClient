package net.rustclear.mod.engine.boss;

/**
 * One phase of the boss fight (Maxor, Storm, Goldor, Necron, the Wither King). Each keeps its
 * timing constants in its own data class, so a Hypixel change is a data edit.
 */
public interface BossPhase {
    /** Its name, for logs and the SimLog's "phase" events. */
    String name();

    /** It begins: the fight's clock was just reset to 0. Schedule the script here. */
    void start(BossFight fight);

    /** Every server tick while it runs, before the fight's scheduled tasks. */
    void tick();

    /** The next phase, once this one is over (null: not over yet, or the last). */
    BossPhase next();

    /** Whether the fight is over (the last phase is done). */
    default boolean ended() {
        return false;
    }

    /** [player] right-clicked [entity] (a crystal, a pylon, a lever...): true when the phase took it. */
    default boolean use(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.entity.Entity entity) {
        return false;
    }

    /** [player] right-clicked the block at [pos] (a lever, a button): true when the phase took it. */
    default boolean useBlock(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos pos) {
        return false;
    }

    /** [player] left-clicked the block at [pos]: true when the phase took it. */
    default boolean hitBlock(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos pos) {
        return false;
    }

    /** [player] left-clicked [entity] (an item frame, a stand): true when the phase took it. */
    default boolean attack(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.entity.Entity entity) {
        return false;
    }

    /** [player] used a Superboom TNT at [pos] (a gate): true when the phase took it. */
    default boolean boom(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos pos) {
        return false;
    }

    /** Removes what it spawned that the next phase doesn't take over. */
    void dispose();
}
