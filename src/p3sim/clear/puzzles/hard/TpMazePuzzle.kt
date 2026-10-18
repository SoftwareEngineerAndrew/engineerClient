package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.api.Puzzle
import com.engineerclient.p3sim.run.api.PuzzleContext
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonObject
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The Teleport Maze in the world. Standing on an end-portal frame sends you to the landing cell of another pad's patch, looking along
 * the clue; reaching pad 28's patch solves it. The maze is drawn from the run's seed, so a savepoint restore draws the same one.
 * Odin's solver recognises the landings by their position (y 69.5, x and z on half blocks) and reads the yaw.
 *
 * The frames are stood on, not clicked: a player counts when their feet are in the frame's cell, on top of it (the recordings show
 * the teleport at feet height 69.8, a frame is 0.8125 high). A player is not sent twice within [COOLDOWN_TICKS].
 */
class TpMazePuzzle(override val context: PuzzleContext) : Puzzle {
    override val kind = "TP_MAZE"

    private var model: TpMazeModel? = null
    private val cooldown = HashMap<UUID, Int>()
    private val chest = ChestReward(context, ChestReward.TP_MAZE, this)

    private val placement get() = context.room.placement

    override fun onRunStart() {
        chest.reset()
        cooldown.clear()
        val design = context.room.design
        val volume = design?.let { context.run.packs.rooms?.volumeFor(it, context.room.variantId) }
        val y = volume?.let { TpMazeModel.frameYOf(it) } ?: 69
        val routeGroups = HardPuzzlesModule.tpRouteGroups()
        model = TpMazeModel.generate(TpMazeModel.standardPads(), y, context.run.config.seed xor (0x7e11L * (context.room.index + 1)), routeGroups)
        placement?.let { HardBlocks.apply(it, listOf(chest.placeEdit())) }
    }

    override fun onInteract(player: UUID, x: Int, y: Int, z: Int): Boolean = chest.onInteract(player, x, y, z)

    override fun onTick() {
        val m = model ?: return
        val pl = placement ?: return
        val now = context.run.t
        for (rec in context.run.state.players.values) {
            if (rec.isBot || rec.ghost) continue
            if (now - (cooldown[rec.uuid] ?: Int.MIN_VALUE / 2) < COOLDOWN_TICKS) continue
            val (cx, cz) = pl.toCanonical(Math.floor(rec.x).toInt(), Math.floor(rec.z).toInt()) ?: continue
            val pad = m.padAtFrame(cx, cz) ?: continue
            val top = m.frameY + FRAME_HEIGHT
            if (rec.y < top - 0.15 || rec.y > top + 0.3) continue
            val land = m.step(pad.index) ?: continue
            cooldown[rec.uuid] = now
            val player = Sim.players().firstOrNull { it.uuid == rec.uuid } ?: continue
            val w = pl.toWorld(Math.floor(land.x).toInt(), m.frameY, Math.floor(land.z).toInt())
            // The teleport sound is heard at the landing, at full volume (measured).
            Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, Vec3(w.x + 0.5, land.y, w.z + 0.5))
            Sim.tp(player, w.x + 0.5, land.y, w.z + 0.5, land.yaw + 90f * pl.quarterTurns, land.pitch)
        }
        // The last pad is only the objective; the chest behind it solves the puzzle (see [ChestReward]).
        if (m.solved) chest.objectiveDone = true
        chest.onTick()
    }

    override fun snapshot(): JsonObject? = JsonObject().also { it.addProperty("solved", model?.solved == true); chest.snapshot(it) }

    override fun restore(data: JsonObject) {
        chest.restore(data)
        placement?.let { HardBlocks.apply(it, listOf(chest.placeEdit())) }
    }

    internal val mazeModel: TpMazeModel? get() = model

    companion object {
        /** The top of an end-portal frame above its block's floor. */
        const val FRAME_HEIGHT = 0.8125

        /** Ticks before the same player can be sent again. Unmeasured; long enough to walk off the landing. */
        const val COOLDOWN_TICKS = 20
    }
}
