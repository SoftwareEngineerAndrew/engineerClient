package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.api.Puzzle
import com.engineerclient.p3sim.run.api.PuzzleContext
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Where a hard puzzle's reward chest stands in the room's canonical frame, and which way it faces. */
class ChestSpot(val x: Int, val y: Int, val z: Int, val facing: String) {
    fun edit() = BlockEdit(x, y, z, "minecraft:chest[facing=$facing,type=single,waterlogged=false]")
}

/**
 * What happens in the ticks after a player right-clicks the reward chest, as a plain schedule (ticks after the click). Measured on
 * Hypixel: the click, then the chest-open sound 2 ticks later, then a second open sound and the lid block event one tick after that;
 * the room turns green with the block event, and five harp notes follow, the first three ticks after it and then every five ticks.
 */
object ChestFinale {
    enum class Step { OPEN_SOUND, OPEN_SOUND_CENTRE, LID_EVENT, HARP }

    class Item(val at: Int, val step: Step, val pitch: Float = 1f)

    /** The harp pitches of the jingle, in order. */
    val HARP_PITCHES = floatArrayOf(0.7936508f, 0.8888889f, 1.0f, 1.0952381f, 1.1904762f)

    const val CLICK_TO_LID = 3

    fun schedule(): List<Item> = buildList {
        add(Item(CLICK_TO_LID - 1, Step.OPEN_SOUND, 0.984f))
        add(Item(CLICK_TO_LID, Step.OPEN_SOUND_CENTRE, 0.91f))
        add(Item(CLICK_TO_LID, Step.LID_EVENT))
        HARP_PITCHES.forEachIndexed { i, p -> add(Item(CLICK_TO_LID + 3 + 5 * i, Step.HARP, p)) }
    }
}

/**
 * The reward chest of a hard puzzle. Hypixel places it when the room wakes up and solves the puzzle when a player opens it, not when
 * the objective is reached (the last pad, the pits, the fish at the goal); so the puzzle only marks its objective done and this solves
 * the puzzle on the click. Odin's finish signal is the cherry-log block event the chest sends.
 */
class ChestReward(private val context: PuzzleContext, private val spot: ChestSpot, private val puzzle: Puzzle) {
    /** The puzzle's objective is met; the chest now solves it. */
    var objectiveDone = false

    /** The chest has been opened (kept in savepoints). */
    var opened = false
        private set

    private var clickedAt = Int.MIN_VALUE
    private var clicker: UUID? = null
    private val done = HashSet<Int>()

    private val placement get() = context.room.placement

    /** The chest block, to be written with the room's other blocks. */
    fun placeEdit() = spot.edit()

    fun reset() { objectiveDone = false; opened = false; clickedAt = Int.MIN_VALUE; done.clear() }

    /** A right click on the world block (x, y, z). True when it was the chest (consumed either way). */
    fun onInteract(player: UUID, x: Int, y: Int, z: Int): Boolean {
        val pl = placement ?: return false
        val (cx, cz) = pl.toCanonical(x, z) ?: return false
        if (cx != spot.x || y != spot.y || cz != spot.z) return false
        if (objectiveDone && !opened && clickedAt == Int.MIN_VALUE) { clickedAt = context.run.serverTick; clicker = player; done.clear() }
        return true
    }

    fun onTick() {
        if (clickedAt == Int.MIN_VALUE || done.size >= ChestFinale.schedule().size) return
        val pl = placement ?: return
        val w = pl.toWorld(spot.x, spot.y, spot.z)
        val at = Vec3(w.x + 0.5, w.y + 0.5, w.z + 0.5)
        val since = context.run.serverTick - clickedAt
        for ((i, item) in ChestFinale.schedule().withIndex()) {
            if (since < item.at || !done.add(i)) continue
            when (item.step) {
                ChestFinale.Step.OPEN_SOUND -> Sim.sound(SoundEvents.CHEST_OPEN, 0.5f, item.pitch, Vec3(w.x.toDouble(), w.y.toDouble(), w.z.toDouble()))
                ChestFinale.Step.OPEN_SOUND_CENTRE -> Sim.sound(SoundEvents.CHEST_OPEN, 0.5f, item.pitch, at)
                ChestFinale.Step.LID_EVENT -> {
                    opened = true
                    val packet = ClientboundBlockEventPacket(BlockPos(w.x, w.y, w.z), Blocks.CHERRY_LOG, 1, 1)
                    for (p in Sim.players()) p.connection.send(packet)
                    Services.puzzles.reportSolved(puzzle, clicker)
                }
                ChestFinale.Step.HARP -> Sim.sound(SoundEvents.NOTE_BLOCK_HARP, 1.0f, item.pitch, at)
            }
        }
    }

    fun snapshot(o: JsonObject) { o.addProperty("chestOpened", opened); o.addProperty("objective", objectiveDone) }

    fun restore(o: JsonObject) {
        opened = o.get("chestOpened")?.asBoolean == true
        objectiveDone = opened || o.get("objective")?.asBoolean == true
        clickedAt = Int.MIN_VALUE
        done.clear()
    }

    companion object {
        /** The canonical chest cells measured in the recordings. */
        val ICE = ChestSpot(15, 67, 28, "north")
        val TP_MAZE = ChestSpot(15, 70, 20, "south")
        val WATER = ChestSpot(15, 56, 22, "south")
    }
}
