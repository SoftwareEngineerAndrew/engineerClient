package com.engineerclient.p3sim.clear.blood

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.Station
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import com.engineerclient.p3sim.run.api.Services
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.zombie.Zombie
import kotlin.math.atan2
import kotlin.random.Random

/**
 * The Watcher as the player sees him on Hypixel: an invisible zombie wearing a head, hanging 4 blocks over the middle of the blood
 * room facing the door, with the grey-blue potion swirl around him, 3000 health, a name tag over him, and two Watchful Eyes (1000
 * health, their own head and tag) hovering a couple of blocks from him. The camp moves him ([moveTo]) and the host counts his kills
 * down ([setKills]); the exit removes all of it ([remove]). He says his lines in chat only (no speech stand). Server thread only.
 *
 * His head and the Eyes' head are textures of the rooms pack under the roles [ROLE_WATCHER] and [ROLE_EYE]; a pack without them
 * leaves a plain head. UNMEASURED: nothing of the figure itself; the swirl is an infinite Invisibility effect with particles on.
 */
class WatcherEntity(
    /** Where his feet are when the room opens: the middle of the room, [WatcherStats.RISE] over the floor. */
    private val x: Double, private val y: Double, private val z: Double,
    /** The way he faces (Minecraft degrees), toward the blood door. */
    private val yaw: Float,
    private val textures: List<SkullTexture>,
    private val random: Random,
) {
    /** A body, the tag stand over it, and where it hangs relative to the Watcher. */
    private class Part(val body: Zombie, val tag: ArmorStand, val dx: Double, val dy: Double, val dz: Double)

    private var main: Part? = null
    private var eyes: List<Part> = emptyList()

    private var curYaw = yaw

    val alive: Boolean get() = main?.body?.isRemoved == false

    fun spawn() {
        if (alive) return
        main = part(0.0, 0.0, 0.0, WatcherStats.MAX_HEALTH, ROLE_WATCHER, WATCHER_TAG)
        eyes = List(EYES) {
            val o = WatcherStats.eyeOffset(random)
            part(o[0], o[1], o[2], WatcherStats.EYE_HEALTH, ROLE_EYE, EYE_TAG)
        }
    }

    /** Sets his health for [kills] counted kills (3000 minus 200 each, 0.1 at the fifteenth). */
    fun setKills(kills: Int) {
        main?.body?.let { if (!it.isRemoved) it.health = WatcherStats.health(kills) }
    }

    /** He says [line]: the chat line only. */
    fun say(line: String) {
        Services.channels.chat(WatcherLines.chat(line))
    }

    /**
     * Moves him to ([nx], [ny], [nz]) facing [nyaw] degrees (null: along the way he moved, or unchanged if he did not). The Eyes keep
     * their place around him and the tags stay over the bodies, so the entity tracker sends the usual move packets.
     */
    fun moveTo(nx: Double, ny: Double, nz: Double, nyaw: Float? = null) {
        val m = main?.body ?: return
        if (m.isRemoved) return
        val newYaw = nyaw ?: run {
            val dx = nx - m.x; val dz = nz - m.z
            if (dx * dx + dz * dz > 1e-4) Math.toDegrees(atan2(-dx, dz)).toFloat() else curYaw
        }
        curYaw = newYaw
        for (p in listOfNotNull(main) + eyes) place(p, nx + p.dx, ny + p.dy, nz + p.dz, newYaw)
    }

    /** Whether [e] is the Watcher, an Eye or one of their tags. */
    fun owns(e: Entity): Boolean = (listOfNotNull(main) + eyes).any { it.body === e || it.tag === e }

    /** Takes the Watcher, his Eyes and their tags away (no death animation). */
    fun remove() {
        for (p in listOfNotNull(main) + eyes) { p.tag.discard(); p.body.discard() }
        main = null; eyes = emptyList()
    }

    private fun part(dx: Double, dy: Double, dz: Double, health: Float, role: String, tagText: String): Part {
        val body = Zombie(EntityTypes.ZOMBIE, Sim.level).also { m ->
            m.setNoAi(true); m.isSilent = true; m.isInvulnerable = true; m.setPersistenceRequired(); m.setNoGravity(true)
            m.getAttribute(Attributes.MAX_HEALTH)?.baseValue = health.toDouble()
            m.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = WatcherStats.MOVEMENT_SPEED
            m.health = health
            m.setItemSlot(EquipmentSlot.HEAD, HeadItems.stack(role, textures))
            // The grey-blue swirl: an endless Invisibility with the particles shown and the ambient (faint) look.
            m.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, true, true, false))
            m.isInvisible = true
        }
        val tag = ArmorStand(EntityTypes.ARMOR_STAND, Sim.level).also { s ->
            s.isInvisible = true; s.setNoGravity(true); s.isInvulnerable = true; s.isSilent = true
            Station.setMarker(s)
            s.setCustomName(Sim.legacy(tagText))
            s.isCustomNameVisible = true
        }
        val p = Part(body, tag, dx, dy, dz)
        place(p, x + dx, y + dy, z + dz, yaw)
        Sim.spawn(body)
        Sim.spawn(tag)
        return p
    }

    private fun place(p: Part, px: Double, py: Double, pz: Double, pyaw: Float) {
        p.body.snapTo(px, py, pz, pyaw, 0f)
        p.body.setYHeadRot(pyaw); p.body.setYBodyRot(pyaw)
        p.tag.snapTo(px, py + WatcherStats.TAG_DY, pz, 0f, 0f)
    }

    companion object {
        /** Rooms-pack texture roles of the two heads. */
        const val ROLE_WATCHER = "watcher"
        const val ROLE_EYE = "watchful_eye"

        /** Name tags: a purple (Watcher) or blue (Eye) icon glyph, then the name; the Watcher's is not bold, the Eye's is. */
        const val WATCHER_TAG = "§5 §r§cThe Watcher"
        const val EYE_TAG = "§9 §r§3§lWatchful Eye"

        /** The Watchful Eyes that hover near him. */
        const val EYES = 2
    }
}
