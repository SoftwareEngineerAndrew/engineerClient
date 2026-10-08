package com.devgineerclient.recorder

import com.devgineerclient.mixin.MoveEntityPacketAccessor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.network.protocol.game.VecDeltaCodec
import net.minecraft.world.entity.Relative
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The client's position codecs, mirrored on the network thread in packet order, so that every
 * relative move line can say where the entity actually is.
 *
 * A move_entity packet carries only a delta in 1/4096 blocks against the entity's codec base, which
 * the game keeps per entity and moves with every add_entity, position sync and positioned move.
 * Reading the base from the entity itself is not possible here (the game thread applies the packet
 * later, and may already be ahead or behind), so this keeps its own copy, updated exactly the way
 * ClientPacketListener updates the entity's (checked against Minecraft 26.1: teleport_entity leaves the base
 * alone, so a relative teleport only says the absolute is pending; EntityCapture's emove line holds
 * where it landed).
 *
 * Also remembers each entity's type, so set_entity_data lines (whose indices mean different things
 * per type) say what they are for.
 *
 * The maps are touched only by the network thread ([annotate]); the game thread reaches in only
 * through [seed] and [packetSpawned], which are concurrent.
 */
object EntityMirror {

    private val codecs = HashMap<Int, VecDeltaCodec>()
    private val types = HashMap<Int, String>()

    /** Ids added by an add_entity packet and not yet seen loaded by the game: EntityCapture's "src". */
    val packetSpawned: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** A base and type from the game thread (keyframes), for entities spawned before the recording. */
    class Seed(val id: Int, val type: String, val x: Double, val y: Double, val z: Double)

    private val seeds = ConcurrentLinkedQueue<Seed>()

    /** Queues [s] for the network thread; it only fills ids the mirror does not know yet. */
    fun seed(s: Seed) { seeds.add(s) }

    internal fun reset() {
        codecs.clear(); types.clear(); packetSpawned.clear(); seeds.clear()
    }

    /**
     * Members to append to [p]'s packet line (without a leading comma), or null when there is
     * nothing to say. Network thread, in packet order; also called for packets that are filtered out
     * of the file, so the mirror never misses a step.
     */
    fun annotate(p: Packet<*>): String? {
        drainSeeds()
        return when (p) {
            is ClientboundLoginPacket, is ClientboundRespawnPacket -> { reset(); null }
            is ClientboundAddEntityPacket -> { added(p.id, BuiltInRegistries.ENTITY_TYPE.getKey(p.type).toString(), p.x, p.y, p.z); null }
            is ClientboundEntityPositionSyncPacket -> sync(p.id(), p.position().endPosition())
            is ClientboundMoveEntityPacket -> move((p as MoveEntityPacketAccessor).ec_getEntityId(), p)
            is ClientboundRotateHeadPacket -> rotateHead(p)
            is ClientboundRemoveEntitiesPacket -> { val ids = p.entityIds; for (i in 0 until ids.size) removed(ids.getInt(i)); null }
            is ClientboundSetEntityDataPacket -> "\"etype\":" + RecorderFiles.q(types[p.id()])
            is ClientboundTeleportEntityPacket -> teleport(p)
            else -> null
        }
    }

    internal fun added(id: Int, type: String, x: Double, y: Double, z: Double) {
        codecs[id] = VecDeltaCodec().also { it.setBase(Vec3(x, y, z)) }
        types[id] = type
        packetSpawned.add(id)
    }

    internal fun removed(id: Int) {
        codecs.remove(id); types.remove(id)
    }

    internal fun sync(id: Int, pos: Vec3): String {
        codecs.getOrPut(id) { VecDeltaCodec() }.setBase(pos)
        return abs(pos)
    }

    /** As ClientPacketListener.handleMoveEntity: decode against the base, which then becomes the result. */
    internal fun move(id: Int, p: ClientboundMoveEntityPacket): String? {
        val sb = StringBuilder(64)
        if (p.hasPosition()) {
            val c = codecs[id]
            if (c == null) sb.append("\"abs\":null,\"why\":\"no base\"")
            else {
                val v = p.positionDelta.decode(c).endPosition()
                c.setBase(v)
                sb.append(abs(v))
            }
        }
        if (p.hasRotation()) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append("\"deg\":["); PacketJson.num(sb, p.yRot); sb.append(','); PacketJson.num(sb, p.xRot); sb.append(']')
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /** The head yaw in degrees (the packet's byte times 360/256, as the game applies it). */
    internal fun rotateHead(p: ClientboundRotateHeadPacket): String =
        StringBuilder(24).append("\"deg\":[").also { PacketJson.num(it, p.yHeadRot) }.append(']').toString()

    /** The base stays put (26.1); an absolute teleport still says where to, a relative one is resolved by emove. */
    private fun teleport(p: ClientboundTeleportEntityPacket): String {
        val r = p.relatives()
        return if (Relative.X in r || Relative.Y in r || Relative.Z in r) "\"absPending\":true" else abs(p.change().position())
    }

    private fun drainSeeds() {
        while (true) {
            val s = seeds.poll() ?: return
            // The network thread may already be past the keyframe's tick for this entity: never overwrite.
            if (s.id !in codecs) codecs[s.id] = VecDeltaCodec().also { it.setBase(Vec3(s.x, s.y, s.z)) }
            types.putIfAbsent(s.id, s.type)
        }
    }

    private fun abs(v: Vec3): String {
        val sb = StringBuilder(64).append("\"abs\":[")
        PacketJson.num(sb, v.x); sb.append(','); PacketJson.num(sb, v.y); sb.append(','); PacketJson.num(sb, v.z)
        return sb.append(']').toString()
    }
}
