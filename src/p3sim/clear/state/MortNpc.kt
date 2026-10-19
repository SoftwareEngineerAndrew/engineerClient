package com.engineerclient.p3sim.clear.state

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimStand
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.run.DungeonPlan
import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Base64
import java.util.UUID
import kotlin.math.atan2

/**
 * Mort, the NPC in the entrance room who hands out the map. As recorded (429 runs): a fake player standing on the floor (y 69) 11 blocks
 * from the entrance tile's centre towards the back of the room (12 in the entrance whose Odin core is [FAR_CORE]), the side the room's
 * rotation puts behind it, with a "§bMort" name stand on him and a "§e§lCLICK" one a quarter block lower. He turns his head to the nearest
 * player. Spawned at the run's start and again after a savepoint restore (which clears every sim entity). Server thread.
 *
 * Both name stands go blank just after his second line (ADR, 226 runs: a median 42 ticks after his first, p10 40, his second comes at 40) and stay
 * blank; he is still there and still answers a click.
 */
object MortNpc {
    /** Mort's skin (texture id; slim). */
    private const val SKIN = "9b56895b9659896ad647f58599238af532d46db9c1b0389b8bbeb70999dab33d"

    /** The one entrance build that has Mort a block further back. */
    private const val FAR_CORE = 77375583

    /** How far he looks for someone to face (blocks). Unmeasured. */
    private const val LOOK_RANGE = 12.0

    /** Ticks after Mort's second line that his name stands go blank. */
    const val BLANK_AFTER_SECOND_LINE = 2

    private val made = ArrayList<Entity>()
    private val names = ArrayList<Entity>()
    private var blankIn = -1
    private var blanked = false
    private var body: Mannequin? = null
    private var home: DoubleArray? = null

    /** Where Mort stands for [plan]'s entrance: x, y, z and the yaw facing into the room; null without an entrance. Pure. */
    fun spot(plan: DungeonPlan): DoubleArray? {
        val room = plan.entrance ?: return null
        val tile = room.tiles.firstOrNull() ?: return null
        val back = if (room.layoutRoom?.core == FAR_CORE) 12 else 11
        val cx = DungeonFrame.tileCentre(tile.x); val cz = DungeonFrame.tileCentre(tile.z)
        // The back of the room, by the rotation Odin reads: SOUTH has it to the north, and so on; he faces the centre.
        val (bx, bz, yaw) = when (room.rotation) {
            Rot.SOUTH -> Triple(cx, cz - back, 0f)
            Rot.NORTH -> Triple(cx, cz + back, 180f)
            Rot.EAST -> Triple(cx - back, cz, -90f)
            Rot.WEST -> Triple(cx + back, cz, 90f)
        }
        return doubleArrayOf(bx + 0.5, 69.0, bz + 0.5, yaw.toDouble())
    }

    /** Whether [e] is Mort or one of his name stands (a click on any of them is a click on him). */
    fun owns(e: Entity): Boolean = made.any { it === e }

    fun spawn(plan: DungeonPlan) {
        val wasBlank = blanked
        remove()
        ReadyUpMenu.reset()
        val at = spot(plan) ?: return
        home = at
        EngineerClient.safely("ads mort") {
            val m = Mannequin(EntityTypes.MANNEQUIN, Sim.level)
            val skin = Base64.getEncoder().encodeToString(
                "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$SKIN\",\"metadata\":{\"model\":\"slim\"}}}}".toByteArray(Charsets.UTF_8))
            val profile = GameProfile(UUID.nameUUIDFromBytes("ads:mort".toByteArray()), "Mort", PropertyMap(ImmutableMultimap.of("textures", Property("textures", skin))))
            m.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
            m.isPermanentlyInvulnerable = true
            m.isSilent = true
            m.setNoGravity(true)
            hideDescription(m)
            m.snapTo(at[0], at[1], at[2], at[3].toFloat(), 0f)
            m.yHeadRot = at[3].toFloat(); m.yBodyRot = at[3].toFloat()
            body = Sim.spawn(m)
            made += m
            names += stand(at[0], at[1], at[2], "§bMort")
            names += stand(at[0], at[1] - 0.25, at[2], "§e§lCLICK")
            made += names
        }
        // A savepoint restore after the start brings him back as he was then.
        if (wasBlank) blankNow()
    }

    /** Hypixel's name stands: full-size, invisible, flags 2. */
    private fun stand(x: Double, y: Double, z: Double, name: String): Entity {
        val s = SimStand(Sim.level)
        s.snapTo(x, y, z, 0f, 0f)
        s.isInvisible = true
        s.isPermanentlyInvulnerable = true
        s.isSilent = true
        s.setNoGravity(true)
        runCatching { s.entityData.set(ArmorStand.DATA_CLIENT_FLAGS, 2.toByte()) }
        s.setCustomName(Sim.legacy(name))
        s.isCustomNameVisible = true
        return Sim.spawn(s)
    }

    /** His name stands go blank [ticks] from now. */
    fun blankNames(ticks: Int) { if (ticks <= 0) blankNow() else blankIn = ticks }

    private fun blankNow() {
        blankIn = -1; blanked = true
        for (s in names) if (!s.isRemoved) s.setCustomName(Sim.legacy(""))
    }

    /** Turns his head to the nearest player in range, else back to facing the room. */
    fun tick() {
        if (blankIn > 0 && --blankIn == 0) blankNow()
        val m = body ?: return
        if (m.isRemoved) { body = null; return }
        val h = home ?: return
        val near = Sim.players().filter { it.distanceToSqr(m) < LOOK_RANGE * LOOK_RANGE }.minByOrNull { it.distanceToSqr(m) }
        val yaw = if (near == null) h[3].toFloat() else (Math.toDegrees(atan2(near.z - m.z, near.x - m.x)) - 90.0).toFloat()
        if (yaw != m.yHeadRot) { m.yRot = yaw; m.yHeadRot = yaw; m.yBodyRot = yaw }
    }

    fun remove() {
        for (e in made) if (!e.isRemoved) e.discard()
        made.clear(); names.clear(); body = null; blankIn = -1; blanked = false
    }

    private val hideMethod by lazy { Mannequin::class.java.getDeclaredMethod("setHideDescription", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
    private fun hideDescription(m: Mannequin) { runCatching { hideMethod.invoke(m, true) } }

}
