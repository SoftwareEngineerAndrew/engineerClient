package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.TransientStand
import com.engineerclient.p3sim.clear.secrets.FloorDrops
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunBlocks
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import kotlin.random.Random

/**
 * The Fairy room's four fairies: small invisible stands wearing the fairy head, each with a `§8[§r§7Lv0§r§8] §r§dName §r§a4§r§c❤` tag
 * stand just over it, drifting round the room. Pure rules ([pickNames], [nextTarget]) and the server-side bodies.
 *
 * Measured (ADR, 77 F7 clears with the room): four names drawn from fifteen, always level 0 with 4 hearts (one 3), one head skin for all; the
 * tag 0.94 over the body; they fly at 0.06/0.14/0.25 blocks a tick (p10/50/90), 3.1/5.1/6.8 blocks from the tile centre and at y 69.4/71.5/73.8.
 * UNMEASURED: their path rule (here: straight flights to random points in that band) and whether they react to players.
 */
object FairyRoom {
    val NAMES = listOf("Akia", "Aliya", "Desini", "Genevieve", "Ilene", "Lelani", "Mari", "Nymira", "Steve", "Synestra", "Trisha", "Tyene", "Ussaea", "Yve", "Zana")
    const val TEXTURE = "2eedcffc6a11a3834a28849cc316af7a2752a376d536cf84039cf79108b167ae"
    const val TAG_ABOVE = 0.94
    const val SPEED = 0.14
    const val MIN_R = 2.5
    const val MAX_R = 7.0
    const val MIN_Y = 69.3
    const val MAX_Y = 74.0

    fun tag(name: String): String = "§8[§r§7Lv0§r§8] §r§d$name §r§a4§r§c❤"

    /** Four different names. */
    fun pickNames(rnd: Random): List<String> = NAMES.shuffled(rnd).take(4)

    /** A point in the band the fairies keep to round ([cx], [cz]). */
    fun nextTarget(cx: Double, cz: Double, rnd: Random): DoubleArray {
        val a = rnd.nextDouble() * Math.PI * 2
        val r = MIN_R + (MAX_R - MIN_R) * rnd.nextDouble()
        return doubleArrayOf(cx + Math.cos(a) * r, MIN_Y + (MAX_Y - MIN_Y) * rnd.nextDouble(), cz + Math.sin(a) * r)
    }

    private class Fairy(val name: String, var x: Double, var y: Double, var z: Double, var target: DoubleArray) {
        var body: ArmorStand? = null
        var tag: ArmorStand? = null
    }

    private val fairies = ArrayList<Fairy>()
    private var planned = false
    private var cx = 0.0
    private var cz = 0.0
    private var rnd = Random(0)

    /** Plans the fairies of [run] (once), builds them once their chunk is loaded, and moves them. Server thread. */
    fun tick(run: Run) {
        if (!planned) {
            planned = true
            val room = run.plan.rooms.firstOrNull { it.type == RoomType.FAIRY } ?: return
            val tile = room.tiles.firstOrNull() ?: return
            cx = DungeonFrame.tileCentre(tile.x) + 0.5; cz = DungeonFrame.tileCentre(tile.z) + 0.5
            rnd = run.subRandom("fairies")
            for (n in pickNames(rnd)) { val at = nextTarget(cx, cz, rnd); fairies += Fairy(n, at[0], at[1], at[2], nextTarget(cx, cz, rnd)) }
        }
        if (fairies.isEmpty()) return
        if (!RunBlocks.isLoaded(Math.floor(cx).toInt(), Math.floor(cz).toInt())) return
        for (f in fairies) {
            if (f.body?.isRemoved != false) build(f)
            val dx = f.target[0] - f.x; val dy = f.target[1] - f.y; val dz = f.target[2] - f.z
            val d = Math.sqrt(dx * dx + dy * dy + dz * dz)
            if (d <= SPEED) f.target = nextTarget(cx, cz, rnd)
            else { f.x += dx / d * SPEED; f.y += dy / d * SPEED; f.z += dz / d * SPEED }
            val yaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
            f.body?.snapTo(f.x, f.y, f.z, yaw, 0f)
            f.tag?.snapTo(f.x, f.y + TAG_ABOVE, f.z, yaw, 0f)
        }
    }

    private fun build(f: Fairy) {
        f.body?.discard(); f.tag?.discard()
        EngineerClient.safely("ads fairy") {
            val b = TransientStand(Sim.level)
            b.snapTo(f.x, f.y, f.z, 0f, 0f)
            b.isInvisible = true; b.isPermanentlyInvulnerable = true; b.isSilent = true; b.setNoGravity(true)
            // Armor-stand flags as recorded: the body 3 (small + the unused bit 2), the tag 18 (marker + bit 2).
            runCatching { b.entityData.set(ArmorStand.DATA_CLIENT_FLAGS, 3.toByte()) }
            b.setItemSlot(EquipmentSlot.HEAD, FloorDrops.head(TEXTURE, null))
            val t = TransientStand(Sim.level)
            t.snapTo(f.x, f.y + TAG_ABOVE, f.z, 0f, 0f)
            t.isInvisible = true; t.isPermanentlyInvulnerable = true; t.isSilent = true; t.setNoGravity(true)
            com.engineerclient.p3sim.Station.setLeverFlags(t)
            t.setCustomName(Sim.legacy(tag(f.name))); t.isCustomNameVisible = true
            f.body = Sim.spawn(b); f.tag = Sim.spawn(t)
        }
    }


    /** Takes the fairies away and forgets the plan (the run ended or went to the boss). */
    fun clear() {
        for (f in fairies) { f.body?.discard(); f.tag?.discard() }
        fairies.clear(); planned = false
    }
}
