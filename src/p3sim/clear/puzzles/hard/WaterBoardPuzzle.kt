package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.api.Puzzle
import com.engineerclient.p3sim.run.api.PuzzleContext
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonObject
import net.minecraft.sounds.SoundEvents
import java.util.UUID

/**
 * The Water Board in the world. A player pulls a lever (the six gate levers on the side walls, the water lever on the north wall);
 * the pistons in the maze wall push or pull their blocks, water runs, and a wool pit opens when its drain gets wet. Odin's solver reads
 * exactly these blocks: the wool at the pits (`x = 15, y = 56, z = 19 - k`) says which three pits are closed, the probe blocks near the
 * top say which layout this is, and the lever clicks are the ones it times. The game plays from [WaterBoardModel].
 *
 * Unmeasured here: the room pack holds one of the four layouts Odin knows (the quartz one), so every Water Board in a run is that
 * layout; what the chest holds. A second pull of the water lever puts the lapis plug back and the column drains. The chest is placed with the
 * room and opening it solves the puzzle.
 */
class WaterBoardPuzzle(override val context: PuzzleContext) : Puzzle {
    override val kind = "WATER_BOARD"

    private var model: WaterBoardModel? = null
    private var lastClicker: UUID? = null
    private val chest = ChestReward(context, ChestReward.WATER, this)

    private val placement get() = context.room.placement

    override fun onRunStart() {
        chest.reset()
        val design = context.room.design ?: return
        val volume: RoomVolume = context.run.packs.rooms?.volumeFor(design, context.room.variantId) ?: return
        val m = WaterBoardModel.create(volume) ?: return
        // Odin refuses a board without exactly three closed pits: a base room (none closed) or a state in the middle of play gets a fresh draw.
        if (m.closedPits.size != 3) {
            m.closedPits.clear()
            m.closedPits += WaterBoardModel.randomClosedPits(context.run.subRandom("waterboard:${context.room.index}"))
        }
        model = m
        val pl = placement ?: return
        // Every pit is written, closed or open, so a recorded room that was saved with other pits closed comes out as the draw says.
        HardBlocks.apply(pl, (0..4).flatMap { WaterBoardModel.pitEdits(it, closed = it in m.closedPits) } + chest.placeEdit())
    }

    override fun onInteract(player: UUID, x: Int, y: Int, z: Int): Boolean {
        val m = model ?: return false
        val pl = placement ?: return false
        if (chest.onInteract(player, x, y, z)) return true
        val (cx, cz) = pl.toCanonical(x, z) ?: return false
        val lever = WaterLever.at(cx, y, cz) ?: return false
        lastClicker = player
        HardBlocks.apply(pl, listOf(m.click(lever)))
        Sim.sound(SoundEvents.LEVER_CLICK, 0.3f, if (m.isLeverOn(lever)) LEVER_PITCH_ON else LEVER_PITCH_OFF, net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5, z + 0.5))
        return true
    }

    override fun onTick() {
        val m = model ?: return
        val pl = placement ?: return
        val before = m.openedPits.size
        HardBlocks.apply(pl, m.tick())
        // A pit that opens plays the experience orb pickup, as on Hypixel.
        if (m.openedPits.size > before) Sim.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, ORB_PITCH)
        // The pits are only the objective: the puzzle is solved by opening the reward chest (see [ChestReward]).
        if (m.solved) chest.objectiveDone = true
        chest.onTick()
    }

    override fun snapshot(): JsonObject? = model?.snapshot()?.also { chest.snapshot(it) }

    override fun restore(data: JsonObject) {
        val m = model ?: return
        val pl = placement ?: return
        m.restore(data)
        chest.restore(data)
        if (m.solved) chest.objectiveDone = true
        HardBlocks.apply(pl, m.fullEdits() + chest.placeEdit())
    }

    companion object {
        /** Lever click pitches on Hypixel (on / off). */
        const val LEVER_PITCH_ON = 0.5873016f
        const val LEVER_PITCH_OFF = 0.4920635f
        const val ORB_PITCH = 1.4920635f
    }

    /** For `/ads dev`: the model, or null when the room's volume could not be read. */
    internal val boardModel: WaterBoardModel? get() = model
}
