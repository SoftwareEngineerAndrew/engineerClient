package com.engineerclient.p3sim.clear.puzzles

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.pack.rooms.WorldPos
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.api.PuzzleContext
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.level.block.Blocks
import java.util.UUID

/**
 * Higher Or Lower in the world, as the recorded Hypixel rooms build it. A few ticks after the first player steps in, ten blazes appear
 * in a spiral around the centre pillar (a block corner each, 4 blocks apart in height; [BlazeLayout]), each with an armor stand 2 blocks
 * above it named as Odin's blaze solver reads it (`[Lv11] <icon> Blaze N/N❤`), and a chest appears on the pillar. Killing the blazes in order
 * of health ("Lower": highest first, "Higher": lowest first) is the puzzle: the first correct kill sets the chest moving down (Lower) or up
 * (Higher) one block every 5 ticks ([BlazeRail]); the room turns green (or white until its chest is opened) 244 ticks after that kill, as the
 * chest arrives, and the puzzle counts as Completed at the last kill. A kill out of order fails the puzzle ("killed a Blaze in the wrong
 * order!"): the rest of the blazes vanish 2 ticks later and the chest is taken off the rail, until the Architect's First Draft brings
 * them back with the same health values.
 *
 * A blaze has one point of health and no AI: any damage (an arrow, a melee click handled here) kills it, vanilla plays its hurt and death
 * sounds, and the body and its name stand go 2 ticks after it died. The blazes are not silenced, so they crackle as real ones do. The
 * room's reward chest is the room's own secret chest (the secrets module places it); this puzzle leaves it alone and sends Odin's
 * chest-open signal and the harp jingle when a player opens it.
 *
 * UNMEASURED: the sim keeps the blazes' health data at 1 (any hit kills); Hypixel's shows 20. The ride is paced in server ticks, so
 * it runs on while nobody is in the room (the recorded rides that last longer than 244 ticks were probably frozen with nobody there).
 */
class BlazePuzzle(context: PuzzleContext) : WorldPuzzle(PuzzleKinds.BLAZE, context) {
    private class Foe(val hp: Int, val body: Blaze, val tag: ArmorStand, var dead: Boolean = false)

    private var logic: BlazeLogic? = null
    private var foes = ArrayList<Foe>()
    private var hps: List<Int> = emptyList()
    private var lastKiller: UUID? = null
    private var higher = false
    private var rail = BlazeRail(false)

    /** The server tick of the first correct kill, or -1 while the chest has not started to move. */
    private var rideStart = -1

    /** How many moves the chest has made. */
    private var railStep = 0

    override val wakeDelayTicks: Int get() = WAKE_DELAY_TICKS
    override val rewardSignalAtGreen: Boolean get() = false
    override val countsRewardAsSecret: Boolean get() = false

    /** The room's real chest (the pack's one secret) is where the reward is; null until the room is built. */
    private var rewardChest: WorldPos? = null

    override fun rewardSpots(): List<WorldPos> = listOfNotNull(rewardChest)

    /**
     * The room turns green when the chest stops (244 ticks after the first kill), so the puzzle counts as Completed at the last kill and the
     * green mark follows when the ride is over (at once if the ride has already ended).
     */
    override fun solveTiming(): SolveTiming {
        val green = if (rideStart >= 0) (rail.greenTick(rideStart) - now).coerceAtLeast(0) else 0
        return SolveTiming(chat = 0, green = green, complete = 0)
    }

    /** For tests: the chest set off on [tick], as if the first correct kill had just happened (no world is touched). */
    internal fun rideStartedAt(tick: Int) { rideStart = tick }

    override fun build() {
        val design = context.room.design ?: return
        higher = design.family == "Blaze_Upper"
        rail = BlazeRail(higher)
        rewardChest = design.secrets.firstOrNull { it.type == "chest" }?.let { world(it.pos.x, it.pos.y, it.pos.z) }
        val rnd = run.subRandom("blaze:${context.room.index}")
        hps = BlazeLogic.drawHps(rnd, BlazeLogic.COUNT)
        logic = BlazeLogic(hps, ascending = higher)
    }

    /** The room is entered: the blazes and the chest appear. A failed room (restored from a savepoint) waits for the draft. */
    override fun wake() {
        if (status == PuzzleStatus.FAILED) return
        railReset()
        spawnAll()
    }

    // ---- the blazes -----------------------------------------------------------------------------------------------------

    private fun spawnAll() {
        for (f in foes) { forget(f.tag); forget(f.body) }
        foes.clear()
        for (k in 0 until hps.size) {
            val c = BlazeLayout.cell(k, higher)
            val w = world(c.x, c.y, c.z) ?: continue
            val body = Blaze(EntityType.BLAZE, Sim.level)
            body.setNoAi(true); body.setNoGravity(true); body.setPersistenceRequired()
            body.getAttribute(Attributes.MAX_HEALTH)?.baseValue = 1.0
            body.health = 1f
            // Hypixel puts a blaze on the block corner, not the block centre.
            body.snapTo(w.x.toDouble(), w.y.toDouble(), w.z.toDouble(), 0f, 0f)
            track(body)
            Sim.spawn(body)
            val tag = spawnStand(BlazeLogic.nameOf(hps[k]), w.x.toDouble(), w.y + BlazeLayout.TAG_HEIGHT, w.z.toDouble())
            foes += Foe(hps[k], body, tag)
        }
    }

    private fun process(foe: Foe, by: UUID?) {
        if (foe.dead) return
        foe.dead = true
        // The body plays its death; it and its name stand go 2 ticks later.
        after(DEATH_TICKS, TAG_FOE) { forget(foe.tag); forget(foe.body) }
        val l = logic ?: return
        if (status != PuzzleStatus.UNSOLVED) return
        val ok = l.kill(foe.hp)
        val who = by ?: lastKiller ?: playersInRoom().firstOrNull()?.uuid
        if (!ok) { failRide(); fail(who, "killed a Blaze in the wrong order!") }
        else {
            if (rideStart < 0) startRide()
            if (l.solved) solve(who)
        }
    }

    override fun onTick() {
        for (f in foes.toList()) if (!f.dead && (!f.body.isAlive || f.body.isRemoved)) process(f, null)
    }

    override fun onAttack(player: UUID, entity: Entity): Boolean {
        val f = foes.firstOrNull { it.body === entity || it.tag === entity } ?: return false
        if (f.dead) return true
        lastKiller = player
        // Let the blaze take the hit so it plays its hurt and death; whatever happens, the kill counts.
        runCatching { f.body.hurtServer(Sim.level, Sim.level.damageSources().generic(), 1000f) }
        if (f.body.isAlive) runCatching { f.body.kill(Sim.level) }
        process(f, player)
        return true
    }

    // ---- the rail ---------------------------------------------------------------------------------------------------------

    private fun railState(c: BlazeRail.Cell) = when (c) {
        BlazeRail.Cell.CHEST -> PuzzleBlocks.parse(CHEST_STATE)!!
        BlazeRail.Cell.BARS -> PuzzleBlocks.parse(BARS_STATE)!!
        BlazeRail.Cell.AIR -> Blocks.AIR.defaultBlockState()
    }

    private fun setRailCell(y: Int, c: BlazeRail.Cell) { setAt(BlazeLayout.CENTRE, y, BlazeLayout.CENTRE, railState(c)) }

    /** The whole rail as it stands after [step] moves (the start of the room, or the end of a ride). */
    private fun railPaint(step: Int) { for (y in rail.cells) setRailCell(y, rail.cellAt(step, y)) }

    /** The rail as a fresh room has it: the chest at its first cell, the bars of a Higher rail above it, no trail. */
    private fun railReset() { cancelTimers(TAG_RAIL); rideStart = -1; railStep = 0; railPaint(0) }

    /** The first correct kill: the chest starts to move, one block every 5 ticks, the first move on this tick. */
    private fun startRide() {
        rideStart = now
        railStep = 0
        for (i in 0 until BlazeRail.STEPS) after(BlazeRail.STEP_TICKS * i, TAG_RAIL) { moveRail(i) }
    }

    /** Move [i]: the chest leaves its cell (a bar behind it for Lower, air for Higher) and enters the next. */
    private fun moveRail(i: Int) {
        setRailCell(rail.yAt(i), rail.cellAt(i + 1, rail.yAt(i)))
        setRailCell(rail.yAt(i + 1), BlazeRail.Cell.CHEST)
        railStep = i + 1
    }

    /** A wrong kill: the chest is taken off the rail where it stands and stops. */
    private fun failRide() {
        cancelTimers(TAG_RAIL)
        setRailCell(rail.yAt(railStep), BlazeRail.Cell.AIR)
        // Every blaze that is left vanishes in one tick, 2 ticks after the fail.
        after(WIPE_TICKS, TAG_FOE) {
            for (f in foes) { forget(f.tag); forget(f.body) }
            foes.clear()
        }
    }

    // ---- secrets -----------------------------------------------------------------------------------------------------------

    /** A player opened the room's chest: Odin's chest-open signal, then the five harp notes of the jingle. */
    override fun onSecretFound(x: Int, y: Int, z: Int, by: UUID?) {
        val chest = rewardChest ?: return
        if (x != chest.x || y != chest.y || z != chest.z) return
        // Odin takes the signal as the end of the puzzle, so it waits for the last kill (UNMEASURED: whether the chest can be opened sooner).
        if (status != PuzzleStatus.SOLVED) return
        lidSignal(chest)
        val at = net.minecraft.world.phys.Vec3(chest.x + 0.5, chest.y + 0.5, chest.z + 0.5)
        ChestJingle.PITCHES.forEachIndexed { i, pitch ->
            after(ChestJingle.FIRST_NOTE + ChestJingle.NOTE_GAP * i, TAG_JINGLE) { Sim.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HARP, 1.0f, pitch, at) }
        }
    }

    // ---- solved and reset -------------------------------------------------------------------------------------------------

    /** The room as it stands once solved (called when a savepoint restores a solved room): the chest at the end of the rail. */
    override fun applySolved() { railPaint(BlazeRail.STEPS) }

    override fun onReset() {
        cancelTimers(TAG_FOE)
        for (f in foes) { forget(f.tag); forget(f.body) }
        foes.clear()
        logic?.reset()
        // A fresh set of the same ten appears one tick after the item is used, and the rail goes back to its start in that same tick.
        after(RESET_TICKS, TAG_FOE) { railReset(); spawnAll() }
    }

    companion object {
        /** Measured: the blazes and the chest appear 7 ticks (median) after the first step into the room. */
        const val WAKE_DELAY_TICKS = 7

        /** A blaze and its name stand go this long after it dies. */
        const val DEATH_TICKS = 2

        /** The rest of the blazes go this long after a wrong kill. */
        const val WIPE_TICKS = 2

        /** A reset brings the blazes back this long after the item is used. */
        const val RESET_TICKS = 1

        /** The rail chest faces south in the world whichever way the room is turned (101 of 101 recorded placements). */
        private const val CHEST_STATE = "minecraft:chest[facing=south,type=single,waterlogged=false]"
        private const val BARS_STATE = "minecraft:iron_bars[east=true,north=true,south=true,waterlogged=false,west=true]"

        private const val TAG_FOE = "foe"
        private const val TAG_RAIL = "rail"
        private const val TAG_JINGLE = "jingle"
    }
}
