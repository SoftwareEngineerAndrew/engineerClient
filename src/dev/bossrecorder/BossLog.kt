package com.devgineerclient.bossrecorder

import com.devgineerclient.DevgineerClient
import com.devgineerclient.mixin.EntityEventPacketAccessor
import com.devgineerclient.mixin.MoveEntityPacketAccessor
import com.devgineerclient.mixin.RotateHeadPacketAccessor
import com.google.gson.JsonPrimitive
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundAnimatePacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.level.block.state.BlockState
import java.util.Locale
import java.util.Optional
import java.util.UUID

/**
 * The Boss Recorder's packet log: the server's own packets about the boss fights, each stamped with
 * the server tick it arrived on, as `net` entries.
 *
 * A recording of the client's view (once a client tick) shows mobs slid over 3 ticks toward where
 * the server put them, chat and blocks a tick late, and other players only as they are drawn. That
 * is enough for the fights' scripts but not for bosses' health and who hit them, a boss skipping
 * one move, who he targets and how soon he switches, projectiles' aim, or exact spawn and death
 * ticks. Those need the packets themselves, which this keeps:
 *
 *  - always: every boss wither's movement, head, spawn, removal, health data and damage, the boss
 *    bar, and the server's clock (`time`, its game time every second);
 *  - in focus (in the boss, and from the blood door opening until the Watcher is done): every
 *    entity's movement, spawns with their velocity and owner, removals, deaths, damage, hurt and
 *    swing animations, synced data (health, names, flags), passengers; explosions, sounds, block
 *    changes, your own health, and each ping the server tick count comes from.
 *
 * [tap] runs on the network thread for every packet, ahead of any mod that could cancel it. It
 * reads the tick count there and hands the rest to the game thread, where the entity a relative
 * move is for can be looked up; that task is queued before the game's own handling of the packet,
 * so the entity is still where the previous packet put it.
 */
object BossLog {

    /** Called by ConnectionTapMixin with every packet the server sends, on the network thread. */
    fun tap(packet: Packet<*>, s: BossRecording) {
        val tasks = ArrayList<(BossRecording) -> Unit>(1)
        collect(packet, s, tasks)
        if (tasks.isEmpty()) return
        DevgineerClient.mc.execute {
            DevgineerClient.safely("boss recorder") { if (BossRecorder.current === s) tasks.forEach { it(s) } }
        }
    }

    private fun collect(p: Packet<*>, s: BossRecording, out: MutableList<(BossRecording) -> Unit>, inBundle: Boolean = false) {
        // Server ticks are counted here, as Odin counts them (one a top-level ping with a non-zero
        // id), so a packet's tick is exactly the pings that came before it on the wire. Pings inside a
        // bundle are not ticks: Hypixel's anticheat sends a pair around every change to your own
        // entity flags, which counted ran `n` 9-18% ahead of the server.
        if (p is ClientboundPingPacket && p.id != 0 && !inBundle) s.serverTicks++
        val n = s.serverTicks
        val focus = s.focus
        fun watched(id: Int) = focus || id in s.bossIds
        fun add(entry: String) { out += { it.net(n, entry) } }

        when (p) {
            is ClientboundBundlePacket -> p.subPackets().forEach { collect(it, s, out, inBundle = true) }

            is ClientboundSetTimePacket -> add("\"time\",${p.gameTime()}")
            is ClientboundPingPacket -> if (focus) add(if (inBundle) "\"pg\",${p.id},1" else "\"pg\",${p.id}")

            is ClientboundMoveEntityPacket -> {
                val id = (p as MoveEntityPacketAccessor).ec_getEntityId()
                if (!watched(id)) return
                val pos = p.hasPosition()
                val dx = p.getXa().toLong(); val dy = p.getYa().toLong(); val dz = p.getZa().toLong()
                val rot = if (p.hasRotation()) "${a(p.getYRot())},${a(p.getXRot())}" else "null,null"
                val g = if (p.isOnGround()) 1 else 0
                out += { r ->
                    val e = DevgineerClient.mc.level?.getEntity(id)
                    when {
                        !pos -> r.net(n, "\"m\",$id,null,null,null,$rot,$g")
                        e != null -> { val v = e.positionCodec.decode(dx, dy, dz); r.net(n, "\"m\",$id,${b(v.x)},${b(v.y)},${b(v.z)},$rot,$g") }
                        // Not in the world yet (added by a packet still queued): the raw delta, in 1/4096 blocks.
                        else -> r.net(n, "\"md\",$id,$dx,$dy,$dz,$rot,$g")
                    }
                }
            }
            is ClientboundTeleportEntityPacket -> {
                val id = p.id()
                if (!watched(id)) return
                val change = p.change(); val rel = p.relatives(); val g = if (p.onGround()) 1 else 0
                out += { r ->
                    val e = DevgineerClient.mc.level?.getEntity(id)
                    val abs = if (e != null) PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(e), change, rel) else change
                    val flag = if (e == null && rel.isNotEmpty()) ",1" else ""
                    r.net(n, "\"tp\",$id,${pmr(abs)},$g$flag")
                }
            }
            is ClientboundEntityPositionSyncPacket -> if (watched(p.id())) add("\"sy\",${p.id()},${pmr(p.values())},${if (p.onGround()) 1 else 0}")
            is ClientboundRotateHeadPacket -> {
                val id = (p as RotateHeadPacketAccessor).ec_getEntityId()
                if (watched(id)) add("\"h\",$id,${a(p.getYHeadRot())}")
            }
            is ClientboundSetEntityMotionPacket -> if (watched(p.id())) add("\"v\",${p.id()},${v(p.movement().x)},${v(p.movement().y)},${v(p.movement().z)}")

            is ClientboundAddEntityPacket -> {
                if (p.type == EntityTypes.WITHER) s.bossIds += p.id
                if (!(focus || p.type == EntityTypes.WITHER)) return
                val m = p.movement
                val entry = "\"a\",${p.id},${js(BuiltInRegistries.ENTITY_TYPE.getKey(p.type).toString())},${b(p.x)},${b(p.y)},${b(p.z)}," +
                    "${v(m.x)},${v(m.y)},${v(m.z)},${a(p.yRot)},${a(p.xRot)},${a(p.yHeadRot)},${p.data}"
                if (p.type != EntityTypes.PLAYER) { add(entry); return }
                // Players (and Hypixel's player-shaped NPCs): their name, from the tab list the server
                // fills before it adds them.
                val uuid = p.uuid
                out += { r -> r.net(n, entry + "," + js(DevgineerClient.mc.connection?.getPlayerInfo(uuid)?.profile?.name ?: "")) }
            }
            is ClientboundRemoveEntitiesPacket -> {
                val ids = p.entityIds.filter { watched(it) }
                p.entityIds.forEach { s.bossIds.remove(it) }
                if (ids.isNotEmpty()) add("\"r\",[${ids.joinToString(",")}]")
            }
            is ClientboundEntityEventPacket -> {
                val id = (p as EntityEventPacketAccessor).ec_getEntityId()
                if (watched(id)) add("\"ev\",$id,${p.eventId}")
            }
            is ClientboundDamageEventPacket -> if (watched(p.entityId())) {
                val at = p.sourcePosition().map { ",${b(it.x)},${b(it.y)},${b(it.z)}" }.orElse("")
                add("\"dmg\",${p.entityId()},${js(p.sourceType().registeredName)},${p.sourceCauseId()},${p.sourceDirectId()}$at")
            }
            is ClientboundHurtAnimationPacket -> if (watched(p.id())) add("\"hurt\",${p.id()},${a(p.yaw())}")
            is ClientboundAnimatePacket -> if (watched(p.id)) add("\"an\",${p.id},${p.action}")
            is ClientboundSetEntityDataPacket -> if (watched(p.id())) {
                val vals = p.packedItems().mapNotNull { d -> dataValue(d.value())?.let { "[${d.id()},$it]" } }
                if (vals.isNotEmpty()) add("\"d\",${p.id()},[${vals.joinToString(",")}]")
            }
            is ClientboundSetPassengersPacket -> if (focus) add("\"pas\",${p.vehicle},[${p.passengers.joinToString(",")}]")

            is ClientboundBossEventPacket -> p.dispatch(object : ClientboundBossEventPacket.Handler {
                override fun add(id: UUID, name: Component, progress: Float, color: BossEvent.BossBarColor, overlay: BossEvent.BossBarOverlay, darken: Boolean, music: Boolean, fog: Boolean) =
                    add("\"bb\",\"add\",${js(id.toString())},${js(name.string)},${f(progress)},${js(color.name)}")
                override fun remove(id: UUID) = add("\"bb\",\"remove\",${js(id.toString())}")
                override fun updateProgress(id: UUID, progress: Float) = add("\"bb\",\"progress\",${js(id.toString())},${f(progress)}")
                override fun updateName(id: UUID, name: Component) = add("\"bb\",\"name\",${js(id.toString())},${js(name.string)}")
            })

            is ClientboundExplodePacket -> if (focus) {
                val c = p.center()
                val kb = p.playerKnockback().map { ",${v(it.x)},${v(it.y)},${v(it.z)}" }.orElse("")
                add("\"ex\",${b(c.x)},${b(c.y)},${b(c.z)},${f(p.radius())},${p.blockCount()}$kb")
            }
            is ClientboundSoundPacket -> if (focus && BossRecorder.sounds)
                add("\"snd\",${js(sound(p.sound))},${js(p.source.getName())},${b(p.x)},${b(p.y)},${b(p.z)},${f(p.volume)},${f(p.pitch)}")
            is ClientboundSoundEntityPacket -> if (focus && BossRecorder.sounds)
                add("\"sde\",${js(sound(p.sound))},${p.id},${f(p.volume)},${f(p.pitch)}")
            is ClientboundSetHealthPacket -> if (focus) add("\"hp\",${f(p.health)},${p.food},${f(p.saturation)}")

            is ClientboundBlockUpdatePacket -> if (focus && BossRecorder.blocks) {
                val pos = p.pos; val state = p.blockState
                out += { it.netBlock(n, pos, state) }
            }
            is ClientboundSectionBlocksUpdatePacket -> if (focus && BossRecorder.blocks) {
                val changes = ArrayList<Pair<BlockPos, BlockState>>()
                p.runUpdates { pos, state -> changes += pos.immutable() to state }
                out += { r -> changes.forEach { (pos, state) -> r.netBlock(n, pos, state) } }
            }
        }
    }

    /** A synced data value worth keeping: numbers, flags and names (health lives in these), not items or poses. */
    private fun dataValue(v: Any?): String? = when (v) {
        is Boolean -> v.toString()
        is Byte, is Short, is Int, is Long -> v.toString()
        is Float -> if (v.isFinite()) v.toString() else null
        is Double -> if (v.isFinite()) v.toString() else null
        is String -> js(v)
        is Component -> js(v.string)
        is Optional<*> -> if (v.isEmpty) null else (v.get() as? Component)?.let { js(it.string) }
        else -> null
    }

    private fun sound(h: Holder<SoundEvent>): String = h.unwrapKey().map { it.identifier().toString() }.orElseGet { h.value().location().toString() }

    private fun pmr(p: PositionMoveRotation) = "${b(p.position().x)},${b(p.position().y)},${b(p.position().z)},${a(p.yRot())},${a(p.xRot())}"

    private fun js(s: String) = JsonPrimitive(s).toString()
    private fun b(v: Double) = String.format(Locale.ROOT, "%.5f", v)
    private fun v(v: Double) = String.format(Locale.ROOT, "%.4f", v)
    private fun a(v: Float) = String.format(Locale.ROOT, "%.1f", v)
    private fun f(v: Float) = if (v.isFinite()) v.toString() else "null"
}
