package com.engineerclient.p3sim.clear.puzzles

import com.engineerclient.p3sim.pack.rooms.WorldPos
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.api.PuzzleContext
import net.minecraft.world.level.block.Blocks
import java.util.UUID

/**
 * Boulder in the world. The room is built with one of Odin's boulder patterns (the grid at y = 66 is what Odin reads when you enter);
 * the sim reads the same grid, takes Odin's list of clicks for it and finishes when every one has been made on its wall sign or
 * stone button (y = 65). The reward chest stands at the room's chest anchor and opens with the cherry-log block event Odin listens for.
 *
 * NOT simulated (open issue): the boulders sliding when a sign or button is pressed, and what a wrong press does to the room.
 */
class BoulderPuzzle(context: PuzzleContext) : WorldPuzzle(PuzzleKinds.BOULDER, context) {
    private var logic: BoulderLogic? = null
    private var chest: WorldPos? = null

    override fun rewardSpots(): List<WorldPos> = listOfNotNull(chest)

    override fun build() {
        chest = context.anchor("chest").firstOrNull()
        chest?.let { setWorld(it, Blocks.CHEST.defaultBlockState()) }
        val key = BoulderLogic.key { x, z -> isAirAt(x, 66, z) }
        val solution = BoulderLogic.solutionFor(key)
        if (solution == null) org.slf4j.LoggerFactory.getLogger("engineerclient").warn("[ads] Boulder in ${context.room.designId}${context.room.variantId?.let { "/$it" } ?: ""}: pattern $key is not in Odin's table, so Odin cannot solve it")
        logic = BoulderLogic(solution.orEmpty())
    }

    override fun onInteract(player: UUID, x: Int, y: Int, z: Int): Boolean {
        val l = logic ?: return false
        if (y != 65) return false
        val loc = local(x, z) ?: return false
        if (status != PuzzleStatus.UNSOLVED) return true
        l.click(loc.first, loc.second)
        if (l.solved) solve(player)
        return true
    }

    override fun onReset() { logic?.reset() }
}
