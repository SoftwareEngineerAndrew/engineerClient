package com.engineerclient.p3sim.clear.blood

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.api.MobSpec
import com.engineerclient.p3sim.run.api.Services
import com.engineerclient.p3sim.run.plugin.BloodCampHost
import com.engineerclient.p3sim.run.plugin.BloodRoomSpec
import java.util.UUID
import kotlin.random.Random

/**
 * What a camp may ask of the public side (`BloodCampHost`): mobs through the mob service, the wall heads and
 * their flights, the Watcher (his voice, his flight and his health), a debugging note. Every call is server thread only. [mobsAlive]
 * covers only mobs spawned through this host.
 */
class BloodHost(
    override val run: Run,
    override val room: BloodRoomSpec,
    private val flow: BloodFlow,
    private val heads: WallHeads,
    private val watcher: WatcherEntity,
) : BloodCampHost {
    override val random: Random = run.subRandom("blood")
    override val ticksSinceOpen: Int get() = flow.ticksSinceOpen(run.serverTick)

    private val spawned = LinkedHashSet<UUID>()

    override fun spawnMob(spec: MobSpec): UUID? {
        val rec = Services.mobs.spawn(run, spec) ?: return null
        spawned += rec.id
        return rec.id
    }

    /** Counts an already existing mob (one a savepoint brought back) among the mobs this host spawned. */
    fun adopt(id: UUID) { spawned += id }

    override fun removeMob(id: UUID) {
        spawned -= id
        Services.mobs.remove(run, id)
    }

    override fun mobsAlive(): List<UUID> = spawned.filter { Services.mobs.mob(it)?.alive == true }

    override fun partyInRoom(): List<UUID> = run.state.players.values.filter { inRoom(it.x, it.z) }.map { it.uuid }

    /** Whether the blood room stands over the column ([x], [z]). */
    fun inRoom(x: Double, z: Double): Boolean = run.plan.roomAtBlock(x, z)?.index == room.roomIndex

    override fun setHead(slot: Int, textureKey: String) = heads.set(slot, textureKey)

    override fun clearHead(slot: Int) = heads.set(slot, HeadTextures.NONE)

    override fun flyHead(slot: Int, x: Double, y: Double, z: Double, ticks: Int) = heads.fly(slot, x, y, z, ticks)

    override fun moveWatcher(x: Double, y: Double, z: Double) = watcher.moveTo(x, y, z)

    override fun moveWatcher(x: Double, y: Double, z: Double, yaw: Float) = watcher.moveTo(x, y, z, yaw)

    override fun watcherKills(kills: Int) {
        val before = flow.kills
        flow.noteKills(kills, run.serverTick)
        if (flow.kills != before) watcher.setKills(flow.kills)
    }

    override fun watcherSays(line: String) = watcher.say(line)

    override fun note(text: String) = Sim.note(text)

    /** Once per server tick: moves the thrown heads along their flights. */
    fun tick() = heads.tick()

    /** Removes everything this host spawned that is still around (the stage is over). */
    fun cleanUp() {
        for (id in spawned.toList()) Services.mobs.remove(run, id)
        spawned.clear()
    }
}
