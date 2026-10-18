package com.engineerclient.p3sim.clear.blood

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import com.engineerclient.p3sim.run.plugin.HeadSlot
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand

/**
 * The wall heads of the blood room, as Hypixel has them: one invisible armor stand per station of [BloodGeometry] with a player head
 * on it (no skull blocks). A camp chooses what each shows through `BloodCampHost.setHead`; [HeadTextures.NONE] takes the stand away.
 * A thrown head leaves its station on a straight [HeadFlight] ([fly]) and is gone on arrival. The stands are made with [Sim.spawn], so
 * `Sim.clearEntities` owns them too; a savepoint restore, which resets the room, finds them gone and [reapply] makes them again from
 * [keys]. Server thread only.
 */
class WallHeads(val slots: List<HeadSlot>, private val textures: List<SkullTexture>) {
    private val keys = arrayOfNulls<String>(slots.size)
    private val stands = arrayOfNulls<ArmorStand>(slots.size)

    private class Flying(val flight: HeadFlight, var age: Int = 0)
    private val flying = HashMap<Int, Flying>()

    /** The key each slot shows now (null = empty or thrown). */
    fun keys(): List<String?> = keys.toList()

    /** Puts a head with [key] (the plain head by default) on every station. */
    fun placeAll(key: String = HeadTextures.PLAIN) { for (i in slots.indices) set(i, key) }

    /** Takes every head off the wall. */
    fun clearAll() { for (i in slots.indices) set(i, HeadTextures.NONE) }

    fun set(slot: Int, key: String) {
        val s = slots.getOrNull(slot) ?: return
        if (key == HeadTextures.NONE) {
            keys[slot] = null
            flying.remove(slot)
            stands[slot]?.discard(); stands[slot] = null
            return
        }
        keys[slot] = key
        val stand = stands[slot]?.takeIf { !it.isRemoved }
        if (stand != null) stand.setItemSlot(EquipmentSlot.HEAD, HeadItems.stack(key, textures))
        else stands[slot] = spawn(s, key)
    }

    /** Remakes the stands from [saved] (a savepoint restore: the reset took them away). */
    fun reapply(saved: List<String?>) {
        removeAll()
        for (i in slots.indices) { val k = saved.getOrNull(i); if (k != null) set(i, k) }
    }

    /** The head at [slot] leaves its station for ([x], [y], [z]) over [ticks] ticks, spinning, and is gone on arrival. */
    fun fly(slot: Int, x: Double, y: Double, z: Double, ticks: Int) {
        val stand = stands.getOrNull(slot)?.takeIf { !it.isRemoved } ?: return
        flying[slot] = Flying(HeadFlight(stand.x, stand.y, stand.z, x, y, z, ticks, stand.yRot))
        keys[slot] = null
    }

    /** Moves every flying head one tick along its line; call once per server tick. */
    fun tick() {
        if (flying.isEmpty()) return
        val iter = flying.entries.iterator()
        while (iter.hasNext()) {
            val (slot, f) = iter.next()
            val stand = stands[slot]
            if (stand == null || stand.isRemoved) { iter.remove(); continue }
            f.age++
            val fl = f.flight
            stand.snapTo(fl.x(f.age), fl.y(f.age), fl.z(f.age), fl.yaw(f.age), 0f)
            if (fl.syncDue(f.age)) stand.needsSync = true
            if (fl.arrived(f.age)) { stand.discard(); stands[slot] = null; iter.remove() }
        }
    }

    /** Whether [e] is one of the wall's stands. */
    fun owns(e: Entity): Boolean = stands.any { it === e }

    /** The stand at [slot], or null (empty, thrown or not made). */
    fun stand(slot: Int): ArmorStand? = stands.getOrNull(slot)?.takeIf { !it.isRemoved }

    /** Discards every stand and forgets every flight; the keys stay. */
    fun removeAll() {
        flying.clear()
        for (i in stands.indices) { stands[i]?.discard(); stands[i] = null }
    }

    private fun spawn(s: HeadSlot, key: String): ArmorStand {
        val st = ArmorStand(EntityTypes.ARMOR_STAND, Sim.level)
        st.isInvisible = true; st.isSilent = true; st.setNoGravity(true); st.isPermanentlyInvulnerable = true
        st.setItemSlot(EquipmentSlot.HEAD, HeadItems.stack(key, textures))
        st.snapTo(s.x + 0.5, s.y + BloodGeometry.STAND_ABOVE_CELL, s.z + 0.5, 0f, 0f)
        return Sim.spawn(st)
    }
}
