package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.api.Puzzle
import com.engineerclient.p3sim.run.api.PuzzleContext
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonObject
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.monster.Silverfish
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * The Ice Path in the world: one silverfish with no mind of its own, moved a step each tick by [IcePathModel]. A left click on it
 * (the module routes `onAttackEntity` here) sends it sliding; nothing hurts it. It is spawned when somebody is in the room and put
 * back on its start cell after a fail.
 */
class IcePathPuzzle(override val context: PuzzleContext) : Puzzle {
    override val kind = "ICE_PATH"

    private var model: IcePathModel? = null
    private var floorY = IcePathLayout.DEFAULT_FLOOR_Y
    private var fish: Fish? = null
    private var respawnAt = Int.MIN_VALUE
    private var lastHitter: UUID? = null
    private var solved = false
    private val chest = ChestReward(context, ChestReward.ICE, this)

    /** Tick the fish starts to slide, after a hit (an arrow takes [SLIDE_DELAY_TICKS] to start it). */
    private var slideFrom = Int.MIN_VALUE

    private val placement get() = context.room.placement

    /** A silverfish that stays where it is put: no AI, no gravity, not saved with the world. */
    private class Fish(level: Level) : Silverfish(EntityTypes.SILVERFISH, level) {
        override fun shouldBeSaved() = false
    }

    override fun onRunStart() {
        solved = false
        chest.reset()
        slideFrom = Int.MIN_VALUE
        discardFish()
        val design = context.room.design
        val volume = design?.let { context.run.packs.rooms?.volumeFor(it, context.room.variantId) }
        val layout = volume?.let { IcePathLayout.read(it) } ?: return
        floorY = layout.floorY
        model = layout.model()
        placement?.let { HardBlocks.apply(it, listOf(chest.placeEdit())) }
    }

    override fun onInteract(player: UUID, x: Int, y: Int, z: Int): Boolean = chest.onInteract(player, x, y, z)

    fun ownsFish(e: Entity): Boolean = fish != null && fish === e

    fun ownsFishId(id: Int): Boolean = fish?.id == id

    /** A left click on the fish by [player] (not seen in the recordings: the real push is an arrow, see [onFishArrow]). */
    fun onFishHit(player: ServerPlayer) = push(player, 0)

    /**
     * An arrow of [player] hit the fish. The first arrow of a volley sends it sliding, [SLIDE_DELAY_TICKS] ticks later (measured); every
     * further arrow, while it is on its way, prints the "while it's moving" refusal once.
     */
    fun onFishArrow(player: ServerPlayer) = push(player, SLIDE_DELAY_TICKS)

    private fun push(player: ServerPlayer, delay: Int) {
        val m = model ?: return
        val pl = placement ?: return
        lastHitter = player.uuid
        val from = Frames.toCanonical(pl, player.x, player.z) ?: return
        val msg = when (m.hit(from.first, from.second)) {
            IcePathModel.Hit.STARTED -> { slideFrom = context.run.serverTick + delay; Sim.sound(net.minecraft.sounds.SoundEvents.SILVERFISH_HURT, 0.5f, 1f, fish?.position()); null }
            IcePathModel.Hit.WHILE_MOVING -> IcePathModel.MSG_MOVING
            IcePathModel.Hit.BLOCKED -> IcePathModel.MSG_BLOCKED
            IcePathModel.Hit.IGNORED -> null
        }
        if (msg != null) Sim.asPlayer(player) { Sim.chat("§c$msg") }
    }

    override fun onTick() {
        val m = model ?: return
        val pl = placement ?: return
        if (solved) {
            chest.onTick()
            if (fishGoneAt != Int.MIN_VALUE && context.run.t >= fishGoneAt) { discardFish(); fishGoneAt = Int.MIN_VALUE }
            return
        }
        val now = context.run.t
        var f = fish
        if (f == null || f.isRemoved) {
            if (now < respawnAt) return
            m.reset()
            f = Fish(Sim.level).also { it.setNoAi(true); it.setNoGravity(true); it.isInvulnerable = true; it.isSilent = true }
            val (wx, wz) = Frames.toWorld(pl, m.x, m.z)
            f.snapTo(wx, floorY.toDouble(), wz, Frames.yawToWorld(pl, 0f), 0f)
            fish = Sim.spawn(f)
        }
        val before = m.phase
        // The slide waits out the arrow's delay: the model is already MOVING, but stands still until then.
        val after = if (before == IcePathModel.Phase.MOVING && context.run.serverTick < slideFrom) before else m.tick()
        val (wx, wz) = Frames.toWorld(pl, m.x, m.z)
        val yaw = if (m.headingX != 0 || m.headingZ != 0) Frames.yawToWorld(pl, com.engineerclient.p3sim.clear.puzzles.hard.TpMazeModel.yawTo(m.headingX.toDouble(), m.headingZ.toDouble())) else f.yRot
        f.snapTo(wx, floorY.toDouble(), wz, yaw, 0f)
        f.yHeadRot = yaw
        if (before == IcePathModel.Phase.MOVING && after == IcePathModel.Phase.SOLVED) {
            // The goal opens the ice wall in front of the exit and the fish goes a tick later; the chest solves the puzzle.
            solved = true
            chest.objectiveDone = true
            placement?.let { HardBlocks.apply(it, wallEdits()) }
            fishGoneAt = now + 1
        } else if (before == IcePathModel.Phase.MOVING && after == IcePathModel.Phase.TRAPPED) {
            discardFish()
            respawnAt = now + RESPAWN_TICKS
            Services.puzzles.reportFailed(this, lastHitter, IcePathModel.MSG_FAIL)
        }
    }

    private var fishGoneAt = Int.MIN_VALUE

    /** The 12 ice blocks (3 columns, 4 high) that close the exit corridor, as air. */
    private fun wallEdits(): List<BlockEdit> = buildList {
        for (x in IcePathLayout.CORRIDOR_X) for (y in WALL_Y) add(BlockEdit(x, y, WALL_Z, "minecraft:air"))
    }

    private fun discardFish() { fish?.discard(); fish = null }

    override fun onReset() { discardFish(); model?.reset(); respawnAt = Int.MIN_VALUE; slideFrom = Int.MIN_VALUE }

    override fun snapshot(): JsonObject? = JsonObject().also { it.addProperty("solved", solved); chest.snapshot(it) }

    override fun restore(data: JsonObject) {
        solved = data.get("solved")?.asBoolean == true
        chest.restore(data)
        placement?.let { HardBlocks.apply(it, listOf(chest.placeEdit()) + if (solved) wallEdits() else emptyList()) }
        discardFish()
        model?.reset()
        respawnAt = Int.MIN_VALUE
    }

    internal val iceModel: IcePathModel? get() = model

    companion object {
        /** Ticks after a fail before a fresh fish stands on the start cell. Unmeasured. */
        const val RESPAWN_TICKS = 40

        /** Ticks from an arrow's hit to the fish moving (measured: 3). */
        const val SLIDE_DELAY_TICKS = 3

        /** The ice wall that opens at the goal: canonical z 25, y 68 to 71. */
        const val WALL_Z = 25
        val WALL_Y = 68..71
    }
}
