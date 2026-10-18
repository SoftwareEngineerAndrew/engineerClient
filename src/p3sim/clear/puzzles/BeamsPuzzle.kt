package com.engineerclient.p3sim.clear.puzzles

import com.engineerclient.p3sim.pack.rooms.WorldPos
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.api.PuzzleContext
import net.minecraft.world.level.block.Blocks
import java.util.UUID

/**
 * Creeper Beams in the world. The room's sea lanterns come in pairs; an arrow that lands near either end of a pair turns both
 * lanterns to prismarine on the same tick. Odin's solver re-reads the pairs whenever a block swaps between prismarine and sea
 * lantern, and notes the end when a chest appears where the room's chest anchor is (air before): the sim makes exactly those
 * block changes. After the last pair the chest appears [CHEST_DELAY_TICKS] ticks later and the room turns green just after it.
 * The damage module reports arrow landings (see [PuzzlesModule.onEvent]); a right click does nothing.
 *
 * Not simulated: the guardian beam, the creeper and the centre pedestal that dissolves with the chest.
 */
class BeamsPuzzle(context: PuzzleContext) : WorldPuzzle(PuzzleKinds.BEAMS, context) {
    private var logic: BeamsLogic? = null
    private var chest: WorldPos? = null
    private var chestAt = -1
    private var solver: UUID? = null

    /** The room turns green this long after the chest appears (measured 1 to 4). */
    private val greenAfterChest = 2

    override fun build() {
        chest = context.anchor("chest").firstOrNull()
        val pairs = BeamsLogic.pairsIn { c -> stateAt(c.x, c.y, c.z)?.block == Blocks.SEA_LANTERN }
        logic = BeamsLogic(pairs)
        chestAt = -1
        if (pairs.isEmpty()) org.slf4j.LoggerFactory.getLogger("engineerclient").warn("[ads] Creeper Beams in ${context.room.designId}: none of Odin's lantern pairs is in the room, so Odin cannot solve it")
    }

    private fun apply(c: BeamsLogic.Change) {
        c.toPrismarine.forEach { setAt(it.x, it.y, it.z, Blocks.PRISMARINE.defaultBlockState()) }
        c.toLantern.forEach { setAt(it.x, it.y, it.z, Blocks.SEA_LANTERN.defaultBlockState()) }
    }

    override fun onArrowHit(x: Int, y: Int, z: Int, by: UUID?) {
        val l = logic ?: return
        if (status != PuzzleStatus.UNSOLVED || chestAt >= 0) return
        val loc = local(x, z) ?: return
        val cell = l.lanternNear(loc.first + 0.5, y + 0.5, loc.second + 0.5) ?: return
        apply(l.hit(cell))
        if (l.solved) { chestAt = now + CHEST_DELAY_TICKS; solver = by }
    }

    override fun onTick() {
        if (chestAt in 0..now) {
            chestAt = -1
            applySolved()
            solve(solver)
        }
    }

    override val greenLeadTicks: Int get() = -greenAfterChest

    override fun applySolved() {
        val l = logic
        l?.lanterns?.forEach { setAt(it.x, it.y, it.z, Blocks.PRISMARINE.defaultBlockState()) }
        chest?.let { setWorld(it, Blocks.CHEST.defaultBlockState()) }
    }

    override fun onReset() {
        logic?.let { l ->
            l.lanterns.forEach { setAt(it.x, it.y, it.z, Blocks.SEA_LANTERN.defaultBlockState()) }
            chest?.let { setWorld(it, Blocks.AIR.defaultBlockState()) }
            l.reset()
        }
        chestAt = -1
    }

    companion object {
        /** The chest appears this long after the last pair (measured 28 to 32). */
        const val CHEST_DELAY_TICKS = 30
    }
}
