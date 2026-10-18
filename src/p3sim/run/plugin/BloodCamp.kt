package com.engineerclient.p3sim.run.plugin

import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.api.MobSpec
import com.google.gson.JsonObject
import java.util.UUID

/*
 * The blood-camp seam. The blood module builds the blood room (its builds, the wall heads, the Watcher entity, the blood door) and
 * hands the room to a camp implementation through [BloodCampHost]. Without a plug-in the camp is `WatcherCamp` (`clear/blood/camp/`), the
 * Watcher's own camp as the real server plays it; a plug-in's factory, when it supports the room, takes its place. A room with too few
 * wall heads for that gets a plain stand-in: mobs released on a fixed timer and a chat note saying so.
 */

/** A plug-in's way to make a camp. Implemented by the plug-in. */
interface BloodCampFactory {
    /** Whether this implementation can run this room build; false falls through to the next plug-in, then the placeholder. Default: yes. */
    fun supports(room: BloodRoomSpec): Boolean = true

    /** Makes the camp for one run. Called when the blood door opens; the host is valid until the camp [BloodCamp.finished]. */
    fun create(host: BloodCampHost): BloodCamp
}

/** One camp. Implemented by the plug-in. Ticks on the server thread. */
interface BloodCamp {
    /** The blood door has just opened and the Watcher is in the room. */
    fun start()

    /** Once per server tick while the camp runs (including the tick of [start]). */
    fun tick()

    /** Every run event, so the camp can follow kills, deaths and entries. Default: ignore. */
    fun onEvent(event: RunEvent) {}

    /** True when the camp is over: the host then ends the Watcher stage ("You may pass."). Read after every tick. */
    val finished: Boolean

    /** The camp's own state for a savepoint, or null if it has none worth saving. */
    fun snapshot(): JsonObject? = null
    fun restore(data: JsonObject) {}
}

/** The blood room as the public side built it, in WORLD coordinates. May gain members. */
interface BloodRoomSpec {
    /** The layout's room index of the blood room. */
    val roomIndex: Int
    /** The rooms-pack design id and build key of the blood room that was placed (it has several builds). */
    val designId: String
    val buildKey: String?
    /** World centre (x, y, z) of the blood door. */
    val doorX: Double
    val doorY: Double
    val doorZ: Double
    /** Where the Watcher stands: [watcherY] is the floor height; he hangs 4 blocks higher. */
    val watcherX: Double
    val watcherY: Double
    val watcherZ: Double
    /**
     * The wall heads of the room, in world coordinates: `[x, y, z, facing]` in room order. A head is an invisible stand wearing the skull, at
     * (x + 0.5, y + 0.75, z + 0.5). The camp decides what each one shows (see [BloodCampHost.setHead]).
     */
    val heads: List<HeadSlot>
    /** Cells (feet blocks, world coordinates) the pack lists as mob spawn points of this room build, if any. */
    val mobCells: List<DoubleArray>
}

/** A wall head slot. [facing] is `north east south west`. */
data class HeadSlot(val index: Int, val x: Int, val y: Int, val z: Int, val facing: String)

/**
 * What a camp can ask of the public side. Implemented by the blood module. May gain members.
 * All calls are server thread only.
 */
interface BloodCampHost {
    val run: Run
    val room: BloodRoomSpec
    /** A random source seeded from the run's seed: the same seed gives the same camp. */
    val random: kotlin.random.Random
    /** Server ticks since the blood door opened (0 on the tick it opened). */
    val ticksSinceOpen: Int

    /** Spawns a mob in the room through the mob module; null if it could not. [spec.kind] is a mob-pack archetype id or a module special. */
    fun spawnMob(spec: MobSpec): UUID?

    /** Removes a mob without a kill (no key, no score). */
    fun removeMob(id: UUID)

    /** The room's mobs that are alive now. */
    fun mobsAlive(): List<UUID>

    /** The party members (players and bots) currently inside the blood room. */
    fun partyInRoom(): List<UUID>

    /**
     * Sets what a wall head shows. [textureKey] is a skull texture id (64 hex characters, the id in textures.minecraft.net URLs; the host builds
     * the textures-only profile value from it, using the rooms pack's own entry when it has one) or one of the table's `role` words.
     */
    fun setHead(slot: Int, textureKey: String)

    /** Takes the head at [slot] off the wall (a head that has been thrown). Default: nothing. */
    fun clearHead(slot: Int) {}
    /**
     * The head at [slot] is thrown: its stand flies in a straight line from the wall to ([x], [y], [z]) (the stand's feet, world
     * coordinates) over [ticks] ticks, spinning, and is gone on arrival. Default: take it off the wall now.
     */
    fun flyHead(slot: Int, x: Double, y: Double, z: Double, ticks: Int) { clearHead(slot) }

    /** Moves the Watcher toward a spot in the room (world coordinates). Default: he stays where he stands. */
    fun moveWatcher(x: Double, y: Double, z: Double) {}

    /** Moves the Watcher facing [yaw] degrees (Minecraft's yaw: 0 south, 90 west). Default: ignore the yaw. */
    fun moveWatcher(x: Double, y: Double, z: Double, yaw: Float) { moveWatcher(x, y, z) }

    /**
     * The camp's counted kills of the Watcher's mobs are now [kills] (call it whenever the number changes; repeats are harmless). His
     * health falls by 200 a kill, his boss bar follows, and the fifteenth kill is his defeat: it starts the exit's clock (the portal
     * 80 or 90 ticks later, the Watcher gone at 81). A camp that never calls this is taken to be defeated when it says it is finished.
     * Default: nothing.
     */
    fun watcherKills(kills: Int) {}
    /** The Watcher says a line (chat + his name stand), in his generic `[BOSS] The Watcher: ...` form. */
    fun watcherSays(line: String)

    /** A note for the player in chat ("sim" voice), for debugging a camp. */
    fun note(text: String)
}
