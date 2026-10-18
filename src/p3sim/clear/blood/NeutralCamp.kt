package com.engineerclient.p3sim.clear.blood

import com.engineerclient.p3sim.run.api.MobSpec
import com.engineerclient.p3sim.run.plugin.BloodCamp
import com.engineerclient.p3sim.run.plugin.BloodCampHost
import kotlin.math.cos
import kotlin.math.sin

/**
 * The stand-in camp, for a blood room that has too few wall heads for the Watcher's camp ([com.engineerclient.p3sim.clear.blood.camp.WatcherCamp])
 * to throw. It is not the real blood room: after a short wait it releases a fixed number of ordinary mobs into the room, one every
 * [intervalTicks], and is over when the last one is out and nothing it released is still alive. It encodes no behaviour of the real
 * Watcher.
 *
 * Every number is a setting of the blood module (`Blood Placeholder ...`); none is measured.
 */
class NeutralCamp(
    private val host: BloodCampHost,
    private val count: Int,
    private val firstDelayTicks: Int,
    private val intervalTicks: Int,
    /** Mob-pack archetype ids to draw from. */
    private val kinds: List<String>,
) : BloodCamp {
    private var released = 0

    override fun start() {
        host.note("This blood room has too few wall heads for the Watcher's camp, so a plain stand-in runs: $count mobs, one every ${intervalTicks / 20.0} seconds.")
    }

    override fun tick() {
        if (released < count && host.ticksSinceOpen >= firstDelayTicks + released * intervalTicks) {
            release()
            released++
        }
        reportKills()
    }

    /**
     * Tells the host how many of the Watcher's 15 counted kills the stand-in has earned (its dead mobs, scaled), so his health and boss
     * bar fall and the exit's clock starts when the last one dies. A host that ignores the report keeps the Watcher at full health
     * until the camp ends, and the exit starts at the pass line.
     */
    private fun reportKills() {
        val alive = host.mobsAlive().size
        val kills = if (released >= count && alive == 0) WatcherStats.KILLS_TO_DEFEAT
            else ((released - alive).coerceAtLeast(0) * (WatcherStats.KILLS_TO_DEFEAT - 1) / count.coerceAtLeast(1))
        host.watcherKills(kills)
    }

    private fun release() {
        val r = host.room
        val cells = r.mobCells
        val (x, y, z) = if (cells.isNotEmpty()) {
            val c = cells[host.random.nextInt(cells.size)]
            Triple(c[0], c[1], c[2])
        } else {
            // A ring around the Watcher, a different spot for each mob.
            val a = 2.0 * Math.PI * (released + host.random.nextDouble()) / count.coerceAtLeast(1)
            Triple(r.watcherX + RING_RADIUS * cos(a), r.watcherY, r.watcherZ + RING_RADIUS * sin(a))
        }
        val kind = kinds.getOrNull(host.random.nextInt(kinds.size.coerceAtLeast(1))) ?: return
        host.spawnMob(MobSpec(kind, x, y, z, roomIndex = r.roomIndex, starred = false, required = false))
    }

    override fun snapshot(): com.google.gson.JsonObject = com.google.gson.JsonObject().also { it.addProperty("released", released) }

    override fun restore(data: com.google.gson.JsonObject) { released = data.get("released")?.asInt ?: 0 }

    override val finished: Boolean get() = released >= count && host.mobsAlive().isEmpty()

    companion object {
        /** Blocks from the Watcher the stand-in mobs appear. */
        const val RING_RADIUS = 6.0

        /** Mob-pack archetypes the stand-in draws from (ordinary Floor 7 mobs). */
        val DEFAULT_KINDS = listOf("zombie_soldier", "skeleton_soldier", "zombie_knight", "skeleton_master")
    }
}
