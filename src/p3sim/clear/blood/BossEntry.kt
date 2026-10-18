package com.engineerclient.p3sim.clear.blood

import com.engineerclient.p3sim.BossBar
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.Spots
import com.engineerclient.p3sim.run.BossEntry as BossEntryEvent
import com.engineerclient.p3sim.run.BossEntryOptions
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunBlocks
import com.engineerclient.p3sim.run.RunStage
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.NetherPortalBlock
import net.minecraft.world.phys.Vec3
import kotlin.math.floor
import kotlin.random.Random

/**
 * The way from the blood room to Maxor, as Hypixel does it (recorded in 36 runs with the full kill log), in two parts:
 *
 * 1. [strike] and [openPortal]. A fixed time after the Watcher's last kill (see [BloodExit]) a lightning bolt is added at the block
 *    centre of the room's back alcove and, in the next tick, thunder and an explosion sound play there and 12 nether portal blocks
 *    (3 wide, 4 tall, inside the alcove's obsidian frame) appear in one tick. Nobody has to be near.
 * 2. [enter]. One tick after a player's body first touches the portal, in ONE server tick Maxor's boss bar replaces the Watcher's, the
 *    whole party is on the P1 spot, wherever they were, and nothing is re-given and no dungeon block is removed. The run goes to
 *    BOSS_ENTRY and the engine's boss stage takes over through `Run.beginBoss`.
 *
 * Sounds (recorded): thunder on the weather channel at volume 10000 with a random pitch of 0.79 to 0.98, and an explosion on the
 * blocks channel at volume 2 with a random pitch of 0.5 to 0.7, both at the strike. No lever click plays at the arrival point.
 */
object BossEntry {
    const val THUNDER_VOLUME = 10000f
    const val EXPLODE_VOLUME = 2f

    /** A thunder pitch for one entry (recorded 0.79 to 0.98). */
    fun thunderPitch(random: Random): Float = 0.79f + random.nextFloat() * 0.19f

    /** An explosion pitch for one entry (recorded 0.49 to 0.80, mean 0.59; vanilla's 0.5 + 0.2 * random). */
    fun explodePitch(random: Random): Float = 0.5f + random.nextFloat() * 0.2f

    /** Where the party lands: the P1 spot, a half block up (recorded arrival y 221.3 to 221.5, settling to 221.0 as the party falls). */
    val ARRIVAL = Vec3(Spots.P1.x, Spots.P1.y + 0.5, Spots.P1.z)

    /** The centre of the block a position is in. */
    fun blockCentre(x: Double, y: Double, z: Double) = Vec3(floor(x) + 0.5, floor(y) + 0.5, floor(z) + 0.5)

    /** Where the bolt strikes: the room's alcove, or (a room without a placement) the centre of the block the host stands in. */
    fun strikePoint(site: BloodGeometry.ExitSite?): Vec3 {
        if (site != null) return Vec3(site.strikeX, site.strikeY, site.strikeZ)
        val from = Sim.host ?: Sim.players().firstOrNull() ?: return ARRIVAL
        return blockCentre(from.x, from.y, from.z)
    }

    /** The lightning bolt is added: the tick before the portal appears. */
    fun strike(site: BloodGeometry.ExitSite?) {
        val at = strikePoint(site)
        val bolt = LightningBolt(EntityType.LIGHTNING_BOLT, Sim.level)
        bolt.setVisualOnly(true)
        bolt.snapTo(at.x, at.y, at.z, 0f, 0f)
        Sim.spawn(bolt)
    }

    /** The portal opens: thunder and the explosion play at the strike and the portal blocks appear, all in this tick. */
    fun openPortal(run: Run, site: BloodGeometry.ExitSite?) {
        val random = run.subRandom("blood-entry")
        val at = strikePoint(site)
        // A visual-only bolt makes no sound of its own, so both sounds are sent at the strike.
        Sim.sound(SoundEvents.LIGHTNING_BOLT_THUNDER, THUNDER_VOLUME, thunderPitch(random), at, SoundSource.WEATHER)
        Sim.sound(SoundEvents.GENERIC_EXPLODE, EXPLODE_VOLUME, explodePitch(random), at, SoundSource.BLOCKS)
        if (site != null) setPortal(site)
    }

    /** Writes the 12 portal blocks (also after a savepoint restore, which resets the world to the room as built). */
    fun setPortal(site: BloodGeometry.ExitSite) {
        val axis = if (site.axisX) Direction.Axis.X else Direction.Axis.Z
        val state = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis)
        for (c in site.cells) RunBlocks.set(c.x, c.y, c.z, state)
    }

    /** Makes the alcove an open doorway: a build recorded after the fight has the portal standing in it already. */
    fun clearPortal(site: BloodGeometry.ExitSite) {
        for (c in site.cells) RunBlocks.set(c.x, c.y, c.z, Blocks.AIR.defaultBlockState())
    }

    /** The jump: Maxor's bar, the whole party to the P1 spot, then the boss stage. */
    fun enter(run: Run) {
        // The Watcher's bar, when it was up, is renamed in place; otherwise Maxor's appears.
        BossBar.show("§c§lMaxor", 1f)
        for (p in Sim.players()) Sim.tp(p, ARRIVAL.x, ARRIVAL.y, ARRIVAL.z)
        run.state.milestones.bossEntryT = run.t
        run.bus.post(BossEntryEvent(run.t))
        run.requestStage(RunStage.BOSS_ENTRY)
        run.beginBoss(BossEntryOptions(giveHotbar = false, firstLineDelayTicks = 2))
    }
}
