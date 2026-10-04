package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3

/**
 * One thing that counts toward a P3 section: a terminal, a lever or the section's device. Its
 * status stands are Hypixel's: a terminal has "Inactive Terminal" over "CLICK HERE", a device
 * "Inactive" over "Device", a lever a single "Not Activated" (positions from the recordings).
 */
class Station(
    val kind: Kind,
    val section: Int,
    /** The top stand's position (Hypixel's, from the recordings). */
    val at: Vec3,
    /** Short name for menus and bots: "T1", "east lever", "SS"... */
    val label: String,
    /** The lever block (levers only). */
    val lever: BlockPos? = null,
) {
    enum class Kind { TERMINAL, LEVER, DEVICE }

    var done = false
    var doneBy: String? = null
    var doneAt = -1
    /** The terminal's current puzzle (kept when you close it unsolved). */
    var term: Terminals.Term? = null
    var top: ArmorStand? = null
    var bottom: ArmorStand? = null

    val id: String get() = "S$section $label"

    fun nextType(): Terminals.Type = Fight.forcedTerminal ?: Terminals.randomType()

    /** Done by [by] (you, or a bot's name): counts it, says so, opens what it opens. */
    fun complete(by: String) {
        (Fight.phase as? GoldorPhase)?.complete(this, by)
    }

    fun spawnStands() {
        val level = Sim.level
        fun stand(y: Double, name: String, marker: Boolean): ArmorStand {
            val s = SimStand(level)
            s.snapTo(at.x, y, at.z, 0f, 0f)
            s.isInvisible = true
            // STANDS-15: gravity is on in Hypixel's packets (noGravity false); SimStand never moves by itself.
            s.isInvulnerable = true
            s.isSilent = true
            // Styled from the start, as Hypixel's names are (TERM-19).
            s.setCustomName(if (name.isEmpty()) null else Sim.legacy(name))
            s.isCustomNameVisible = name.isNotEmpty()
            if (marker) { setMarker(s); setLeverFlags(s) }
            return Sim.spawn(s)
        }
        when (kind) {
            Kind.TERMINAL -> { top = stand(at.y, INACTIVE, false); bottom = stand(at.y - 0.375, CLICK_HERE, false) }
            Kind.DEVICE -> { top = stand(at.y, "§cInactive", false); bottom = stand(at.y - 0.375, "§cDevice", false) }
            Kind.LEVER -> { top = stand(at.y, "§cNot Activated", true) }
        }
        refreshStands()
    }

    /**
     * The stands' names as they should read now (Hypixel refreshes them on a 20-tick grid). A
     * terminal's or device's pair reads "" while you are 25+ blocks away: of ~4000 renames, the
     * status names came within 26 blocks only, the blanks from 23 out (`terminals.md`). Levers keep theirs.
     */
    fun refreshStands() {
        fun name(s: ArmorStand?, n: String) {
            s ?: return
            val c = if (n.isEmpty()) null else Sim.legacy(n)
            if (s.customName?.string != c?.string || (s.customName == null) != (c == null)) {
                s.setCustomName(c); s.isCustomNameVisible = c != null
            }
        }
        val far = kind != Kind.LEVER && (Sim.player?.position()?.distanceTo(at) ?: 0.0) >= 25.0
        if (far) { name(top, ""); name(bottom, ""); return }
        when (kind) {
            Kind.TERMINAL -> if (done) {
                // Done: the top stand moves down 0.375, onto the bottom one's height (TERM-11).
                top?.let { if (it.y > at.y - 0.375 + 1e-3) it.snapTo(at.x, at.y - 0.375, at.z, 0f, 0f) }
                name(top, "§aTerminal Active"); name(bottom, "")
            } else { top?.let { if (it.y < at.y - 1e-3) it.snapTo(at.x, at.y, at.z, 0f, 0f) }; name(top, INACTIVE); name(bottom, CLICK_HERE) }
            Kind.DEVICE -> if (done) { name(top, "§aDevice"); name(bottom, "§aActive") } else { name(top, "§cInactive"); name(bottom, "§cDevice") }
            Kind.LEVER -> name(top, if (done) "§aActivated" else "§cNot Activated")
        }
    }

    fun clearStands() {
        top?.discard(); bottom?.discard(); top = null; bottom = null
    }

    fun owns(e: net.minecraft.world.entity.Entity) = e === top || e === bottom

    companion object {
        const val INACTIVE = "§cInactive Terminal"
        const val CLICK_HERE = "§e§lCLICK HERE"

        private val markerMethod by lazy { ArmorStand::class.java.getDeclaredMethod("setMarker", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
        fun setMarker(s: ArmorStand) { runCatching { markerMethod.invoke(s, true) } }

        /** STANDS-12: Hypixel's lever stands carry armor-stand flags 18 (marker 16 + the unused bit 2) on 288 of 288; terminal and device stands 0. */
        fun setLeverFlags(s: ArmorStand) { runCatching { s.entityData.set(ArmorStand.DATA_CLIENT_FLAGS, 18.toByte()) } }

        private fun t(section: Int, n: Int, x: Double, y: Double, z: Double) = Station(Kind.TERMINAL, section, Vec3(x, y, z), "T$n")
        private fun l(section: Int, label: String, x: Int, y: Int, z: Int) = Station(Kind.LEVER, section, Vec3(x + 0.5, y + 0.688, z + 0.5), label, BlockPos(x, y, z))
        private fun d(section: Int, label: String, x: Double, y: Double, z: Double) = Station(Kind.DEVICE, section, Vec3(x, y, z), label)

        /**
         * All 30, positions from the recordings (stands), numbered as in terminal-roles.md: the
         * n-th terminal a player reaches walking in from the section's start. Except, named as players do:
         * S1's by how close they are to the levers (4 nearest, 1 furthest), and S2's 4 and 5,
         * named as players do: 5 is the high one (by the low lever), 4 the low one by ll (the high lever).
         */
        fun all(): List<Station> = listOf(
            t(1, 1, 110.5, 112.0, 73.5), t(1, 2, 110.5, 118.0, 79.5), t(1, 3, 90.5, 111.0, 92.5), t(1, 4, 90.5, 121.0, 101.5),
            l(1, "east lever", 106, 124, 113), l(1, "west lever", 94, 124, 113),
            d(1, "SS", 110.5, 119.0, 91.5),
            t(2, 1, 68.5, 108.0, 122.5), t(2, 2, 59.5, 119.0, 123.5), t(2, 3, 47.5, 108.0, 122.5), t(2, 4, 39.5, 107.0, 142.5), t(2, 5, 40.5, 123.0, 123.5),
            l(2, "low lever", 27, 124, 127), l(2, "high lever", 23, 132, 138),
            d(2, "Lights", 60.5, 131.0, 142.5),
            t(3, 1, -1.5, 108.0, 112.5), t(3, 2, -1.5, 118.0, 93.5), t(3, 3, 18.5, 122.0, 93.5), t(3, 4, -1.5, 108.0, 77.5),
            l(3, "west lever", 2, 122, 55), l(3, "east lever", 14, 122, 55),
            d(3, "Arrows", -1.5, 119.0, 74.5),
            t(4, 1, 41.5, 108.0, 30.5), t(4, 2, 44.5, 120.0, 30.5), t(4, 3, 67.5, 108.0, 30.5), t(4, 4, 72.5, 114.0, 47.5),
            l(4, "low lever", 84, 121, 34), l(4, "high lever", 86, 128, 46),
            d(4, "Target", 63.5, 126.0, 34.5),
        )

        /** Each section's count: terminals + levers + device (7, S2 8). */
        fun total(section: Int) = if (section == 2) 8 else 7
    }
}

/**
 * A status stand as Hypixel's: gravity on (STANDS-15: noGravity false in every recorded stand's data) yet
 * it stays where it is put, as there. Vanilla would let it fall, so its own movement is dropped.
 */
class SimStand(level: net.minecraft.world.level.Level) : ArmorStand(EntityType.ARMOR_STAND, level) {
    override fun travel(travelVector: Vec3) {}
}

/**
 * STANDS-06: where you die as a ghost Hypixel leaves your body for about as long as you wait to be revived
 * (life 119-141 ticks, median 121): a fake player lying down in your armour, a name stand and a red "DEAD"
 * stand just under it, and a stand with your head on it a block to the side.
 */
object Corpse {
    fun spawn(p: net.minecraft.server.level.ServerPlayer, ticks: Int) {
        val made = ArrayList<net.minecraft.world.entity.Entity>()
        EngineerClient.safely("p3sim corpse") {
            val m = net.minecraft.world.entity.decoration.Mannequin(EntityType.MANNEQUIN, Sim.level)
            m.setComponent(net.minecraft.core.component.DataComponents.PROFILE, net.minecraft.world.item.component.ResolvableProfile.createResolved(p.gameProfile))
            m.isInvulnerable = true
            m.setNoGravity(true)
            m.isSilent = true
            for (slot in listOf(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET))
                m.setItemSlot(slot, p.getItemBySlot(slot).copy())
            m.pose = net.minecraft.world.entity.Pose.SLEEPING
            m.snapTo(p.x, p.y, p.z, p.yRot, 0f)
            made += Sim.spawn(m)
            fun stand(x: Double, y: Double, name: String?, head: net.minecraft.world.item.ItemStack? = null, invisible: Boolean = true) {
                val s = SimStand(Sim.level)
                s.snapTo(x, y, p.z, 0f, 0f)
                s.isInvisible = invisible
                s.isInvulnerable = true
                s.isSilent = true
                Station.setMarker(s)
                if (name != null) { s.setCustomName(Sim.legacy(name)); s.isCustomNameVisible = true }
                if (head != null) s.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, head)
                made += Sim.spawn(s)
            }
            stand(p.x, p.y - 1.28, "§a${Sim.me}")
            stand(p.x, p.y - 1.66, "§c§lDEAD")
            stand(p.x - 1.0, p.y, null, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD), invisible = false)
        }
        Fight.later(ticks, "corpse gone") { made.forEach { it.discard() } }
    }
}
