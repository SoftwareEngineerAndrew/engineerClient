package com.engineerclient.p3sim.clear.mobs

/**
 * Everything the module does to the actual world, behind one small interface, so the rules (counters, kills, keys, sleepers,
 * which mobs are built when) run headless in tests with a stand-in. [RealStage] is the Minecraft side. Server thread only.
 */
internal interface MobStage {
    /** A replay: puts the body where the recording has the mob this tick (no AI), facing the way it moved. */
    fun place(e: MobEntry, x: Double, y: Double, z: Double) {}
    /** Whether a sim world is running at all. */
    fun available(): Boolean
    /** Whether the chunk of column ([x], [z]) is loaded. */
    fun isLoaded(x: Int, z: Int): Boolean
    /** Builds the mob's entities at its current position; false when it could not. Sets [MobEntry.live] and the record's entity id. */
    fun materialise(e: MobEntry): Boolean
    /** True when the mob's body has gone from the world (its chunk unloaded, or the void took it). */
    fun isGone(e: MobEntry): Boolean
    /** Takes the mob's entities away without a death (no event). The record stays; [MobEntry.live] goes false. */
    fun release(e: MobEntry)
    /** A death on the wire (hurt animation and entity event 3), then body, fillers and tag removed together a couple of ticks later. */
    fun die(e: MobEntry)
    /** Takes a fake player's info and team off the clients. */
    fun retract(e: MobEntry)
    /** Announces every live fake player's info to a player who joined late. */
    fun announceTo(playerId: java.util.UUID, entries: Collection<MobEntry>)
    /** The Fels wakes: visible again. */
    fun wake(e: MobEntry)
    /** The Shadow Assassin shows itself. */
    fun reveal(e: MobEntry)
    /** The tag text and body health data follow the record's HP. */
    fun refresh(e: MobEntry)
    /** The hurt flash on the body. */
    fun hurtFlash(e: MobEntry)
    /** Where the body is now (and moves the tag with it), or null when it has none. */
    fun position(e: MobEntry): DoubleArray?
    /** Carries out what the AI decided: walk, swing, shoot, teleport. [targetX]/[targetZ] are the nearest target's, or NaN. */
    fun act(e: MobEntry, action: AiAction, targetY: Double, targetX: Double, targetZ: Double)
    /** Once a tick: removes the bodies whose death delay is over. */
    fun tick() {}
    /** Removes every body still waiting out its death delay (the run ended). */
    fun flush() {}
    /** Whether the mob's eyes have a clear line to the point (a player's eyes). Stand-ins see everything. */
    fun canSee(e: MobEntry, x: Double, y: Double, z: Double): Boolean = true
    /** Puts a body that fell out of the world back where it was rolled. */
    fun rescue(e: MobEntry)
}
