package com.engineerclient.p3sim.clear.state

import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.KeyKind
import com.engineerclient.p3sim.run.PlacedDoor
import java.util.UUID

/**
 * Everything the state module does to the world, behind one small interface: chat, sounds, a door's blocks, key stands. The
 * controller is pure logic over [com.engineerclient.p3sim.run.RunState] and the bus; the real implementation
 * ([McStateEffects]) talks to Minecraft, the tests record. Server thread only.
 */
interface StateEffects {
    /** A chat line to everyone, or to one player. */
    fun chat(text: String, to: UUID? = null)

    /** A sound for everyone (each hears it at their own position), or for one player. */
    fun sound(cue: Cue, to: UUID? = null)

    /** Writes a door's body: closed (its own block), barrier, or air. [onlyFirst] writes just the first block (a staggered fall). */
    fun setBody(door: PlacedDoor, blocks: BodyBlocks, onlyFirst: Boolean = false)

    /** The falling-block animation of a door that just opened (36 bats carrying the door's blocks, sinking, gone at +23). Real effects only. */
    fun spawnDoorDebris(door: PlacedDoor) {}

    /**
     * Makes a key stand (the named stand and the spinning model) standing at the floor under ([x], [z]) and returns the y it got.
     * [id] names it for [removeStand].
     */
    fun spawnStand(id: Int, kind: KeyKind, x: Double, z: Double, floorGuessY: Double): Double

    /** Takes a picked-up key's stands away: the spinning model at once, the name a couple of ticks later. */
    fun removeStand(id: Int)

    /** Removes every stand (end of run, restore). */
    fun clearStands()

    /** Once a tick: the real effects write what had to wait for a far chunk to load (a door several rooms away, a key stand there). */
    fun tick() {}

    /** The world was put back (a restore): whatever was waiting for a chunk is stale. */
    fun reset() {}
}

/** Which door kinds have a body of blocks to write. */
val DoorKind.hasBody: Boolean get() = this == DoorKind.WITHER || this == DoorKind.BLOOD || this == DoorKind.FAIRY || this == DoorKind.ENTRANCE
