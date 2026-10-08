package com.devgineerclient.recorder

import it.unimi.dsi.fastutil.ints.IntList
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.commands.synchronization.ArgumentUtils
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket
import net.minecraft.network.protocol.game.*
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.block.Block
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * What a packet means beyond its raw fields, worked out where the raw form is hard to read: packed
 * block changes unpacked to positions and states, map colour patches, light arrays keyed by section,
 * an entity's spawn data named (the falling block's state, a frame's facing), the game event's
 * name, tag ids resolved to names. These are added to the packet's "f" object next to its fields;
 * where a decoded member says the same as a raw field more plainly, it replaces that field.
 *
 * Also: which entities a packet is about ("e" on the packet line), for every packet class whose
 * entity-id fields were checked by name, and the client's command tree after it is applied.
 */
object PacketDecode {

    /** The local player's entity id; set from the login packet when known, else read from the client. */
    @Volatile var selfId = -1

    /** Fields of the packet's own object to leave out, and members to add, as (name, writer). */
    class Decoded(val skip: Set<String>, val members: List<Pair<String, (StringBuilder) -> Unit>>)

    /** A decode that threw: the packet is still written in full, with the error as a member. */
    fun failed(t: Throwable) = Decoded(emptySet(), listOf("@decodeError" to { sb: StringBuilder -> PacketJson.str(sb, t.toString()) }))

    /**
     * The decoded members of [p], or null for packets with none. Runs on the tapping thread: anything
     * the game could change once it applies the packet (the light arrays) is copied here; the writers
     * returned run later on the writer thread over immutable data.
     */
    fun capture(p: Any): Decoded? = when (p) {
        is ClientboundSectionBlocksUpdatePacket -> Decoded(setOf("positions", "states"), listOf("changes" to { sb -> sectionChanges(sb, p) }))
        is ClientboundMapItemDataPacket -> p.colorPatch().orElse(null)?.let { patch ->
            Decoded(setOf("colorPatch"), listOf("colorPatch" to { sb ->
                val c = patch.mapColors()
                sb.append("{\"x\":").append(patch.startX()).append(",\"y\":").append(patch.startY())
                    .append(",\"w\":").append(patch.width()).append(",\"h\":").append(patch.height())
                // Odin reads only whole maps (the dungeon map is always sent whole); say which these are.
                if (c.size == 128 * 128) sb.append(",\"full\":true")
                sb.append(",\"len\":").append(c.size).append(",\"b64\":\"").append(java.util.Base64.getEncoder().encodeToString(c)).append("\"}")
            }))
        }
        is ClientboundLightUpdatePacket -> light(p.lightData)
        is ClientboundLevelChunkWithLightPacket -> light(p.lightData)
        is ClientboundAddEntityPacket -> addEntity(p)
        is ClientboundLevelEventPacket -> if (p.type == 2001) Decoded(emptySet(), listOf("state" to { sb -> PacketJson.str(sb, BlockStateParser.serialize(Block.stateById(p.data))) })) else null
        is ClientboundGameEventPacket -> Decoded(emptySet(), listOf("eventName" to { sb -> val n = gameEventNames()[p.event]; if (n == null) sb.append("null") else PacketJson.str(sb, n) }))
        // The tag's SNBT in place of the {"snbt"} wrapper; the type is already its id.
        is ClientboundBlockEntityDataPacket -> Decoded(setOf("tag"), listOf("snbt" to { sb -> PacketJson.str(sb, p.tag.toString()) }))
        is ClientboundUpdateTagsPacket -> Decoded(emptySet(), listOf("names" to { sb -> tagNames(sb, p) }))
        else -> null
    }

    private fun light(d: ClientboundLightUpdatePacketData): Decoded {
        val copy = RichJson.copyLight(d)
        val minSy = runCatching { (Minecraft.getInstance() as Minecraft?)?.level?.minSectionY }.getOrNull()
        return Decoded(setOf("lightData"), listOf("light" to { sb -> RichJson.writeLight(sb, copy, minSy) }))
    }

    /** `[[x,y,z,"state"],...]`: runUpdates hands out one mutable position, so its coordinates are read at once. */
    private fun sectionChanges(sb: StringBuilder, p: ClientboundSectionBlocksUpdatePacket) {
        sb.append('[')
        var first = true
        p.runUpdates { pos, state ->
            if (!first) sb.append(',')
            first = false
            sb.append('[').append(pos.x).append(',').append(pos.y).append(',').append(pos.z).append(',')
            PacketJson.str(sb, BlockStateParser.serialize(state)); sb.append(']')
        }
        sb.append(']')
    }

    private fun addEntity(p: ClientboundAddEntityPacket): Decoded {
        val members = ArrayList<Pair<String, (StringBuilder) -> Unit>>()
        val type = p.type
        members += "etype" to { sb -> PacketJson.str(sb, BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()) }
        members += "deg" to { sb ->
            sb.append('['); PacketJson.num(sb, p.xRot); sb.append(','); PacketJson.num(sb, p.yRot); sb.append(','); PacketJson.num(sb, p.yHeadRot); sb.append(']')
        }
        when {
            type === EntityTypes.FALLING_BLOCK -> members += "dataState" to { sb -> PacketJson.str(sb, BlockStateParser.serialize(Block.stateById(p.data))) }
            type === EntityTypes.ITEM_FRAME || type === EntityTypes.GLOW_ITEM_FRAME || type === EntityTypes.PAINTING ->
                members += "facing" to { sb -> PacketJson.str(sb, Direction.from3DDataValue(p.data).serializedName) }
        }
        return Decoded(emptySet(), members)
    }

    @Volatile private var gameEvents: Map<Any, String>? = null

    /** ClientboundGameEventPacket.Type has only an int id; its name is the static field holding it. */
    private fun gameEventNames(): Map<Any, String> = gameEvents ?: synchronized(this) {
        gameEvents ?: IdentityHashMap<Any, String>().also { m ->
            for (f in ClientboundGameEventPacket::class.java.declaredFields) {
                if (!Modifier.isStatic(f.modifiers) || f.type != ClientboundGameEventPacket.Type::class.java) continue
                runCatching { f.isAccessible = true; f.get(null) }.getOrNull()?.let { m[it] = f.name }
            }
            gameEvents = m
        }
    }

    /**
     * `{registry:{tag:[names]}}` for the built-in registries (blocks, items, entity types...): the raw
     * int ids stay in the tags field beside it. Dynamic registries (biomes, enchantments) are not
     * resolved here; their ids are only in the raw field.
     */
    private fun tagNames(sb: StringBuilder, p: ClientboundUpdateTagsPacket) {
        sb.append('{')
        var firstReg = true
        for ((regKey, payload) in p.tags) {
            @Suppress("UNCHECKED_CAST")
            val reg = (BuiltInRegistries.REGISTRY as net.minecraft.core.Registry<net.minecraft.core.Registry<Any>>).getValue(regKey.identifier()) ?: continue
            @Suppress("UNCHECKED_CAST")
            val tags = runCatching { payloadTags.get(payload) as Map<Identifier, IntList> }.getOrNull() ?: continue
            if (!firstReg) sb.append(',')
            firstReg = false
            PacketJson.str(sb, regKey.identifier().toString()); sb.append(":{")
            var firstTag = true
            for ((tag, ids) in tags) {
                if (!firstTag) sb.append(',')
                firstTag = false
                PacketJson.str(sb, tag.toString()); sb.append(":[")
                for (i in 0 until ids.size) {
                    if (i > 0) sb.append(',')
                    val v = reg.byId(ids.getInt(i))
                    val key = if (v == null) null else reg.getKey(v)
                    if (key == null) sb.append(ids.getInt(i)) else PacketJson.str(sb, key.toString())
                }
                sb.append(']')
            }
            sb.append('}')
        }
        sb.append('}')
    }

    private val payloadTags: Field by lazy {
        Class.forName("net.minecraft.tags.TagNetworkSerialization\$NetworkPayload").getDeclaredField("tags").also { it.isAccessible = true }
    }

    // ------------------------------------------------------------------ entity ids

    /**
     * The entity-id fields of each packet class, checked by name against Minecraft 26.2. A subclass
     * (the move_entity variants) uses its parent's entry.
     */
    private val ENTITY_FIELDS: Map<Class<*>, List<String>> = mapOf(
        ClientboundRemoveEntitiesPacket::class.java to listOf("entityIds"),
        ClientboundEntityEventPacket::class.java to listOf("entityId"),
        ClientboundAnimatePacket::class.java to listOf("id"),
        ClientboundHurtAnimationPacket::class.java to listOf("id"),
        ClientboundDamageEventPacket::class.java to listOf("entityId", "sourceCauseId", "sourceDirectId"),
        ClientboundTakeItemEntityPacket::class.java to listOf("itemId", "playerId"),
        ClientboundSetPassengersPacket::class.java to listOf("vehicle", "passengers"),
        ClientboundSetEntityLinkPacket::class.java to listOf("sourceId", "destId"),
        ClientboundUpdateAttributesPacket::class.java to listOf("entityId"),
        ClientboundUpdateMobEffectPacket::class.java to listOf("entityId"),
        ClientboundRemoveMobEffectPacket::class.java to listOf("entityId"),
        ClientboundSoundEntityPacket::class.java to listOf("id"),
        ClientboundSetEquipmentPacket::class.java to listOf("entity"),
        ClientboundSetEntityDataPacket::class.java to listOf("id"),
        ClientboundEntityPositionSyncPacket::class.java to listOf("id"),
        ClientboundTeleportEntityPacket::class.java to listOf("id"),
        ClientboundMoveEntityPacket::class.java to listOf("entityId"),
        ClientboundRotateHeadPacket::class.java to listOf("entityId"),
        ClientboundSetEntityMotionPacket::class.java to listOf("id"),
        ClientboundAddEntityPacket::class.java to listOf("id"),
        ClientboundProjectilePowerPacket::class.java to listOf("id"),
        ClientboundMoveMinecartPacket::class.java to listOf("entityId"),
        ClientboundSetCameraPacket::class.java to listOf("cameraId"),
        ClientboundPlayerCombatKillPacket::class.java to listOf("playerId"),
        ClientboundPlayerLookAtPacket::class.java to listOf("entity"),
        ClientboundDebugEntityValuePacket::class.java to listOf("entityId"),
        ClientboundMountScreenOpenPacket::class.java to listOf("entityId"),
        ClientboundBlockDestructionPacket::class.java to listOf("id"),
        ClientboundLoginPacket::class.java to listOf("playerId"),
        ServerboundInteractPacket::class.java to listOf("entityId"),
        ServerboundAttackPacket::class.java to listOf("entityId"),
        ServerboundPickItemFromEntityPacket::class.java to listOf("id"),
        ServerboundPlayerCommandPacket::class.java to listOf("id"),
        ServerboundSpectatorActionPacket::class.java to listOf("spectateEntityId"),
        ServerboundEntityTagQueryPacket::class.java to listOf("entityId"),
    )

    private val idFields = ConcurrentHashMap<Class<*>, List<Field>>()

    private fun idFieldsOf(c: Class<*>): List<Field> = idFields.getOrPut(c) {
        var k: Class<*>? = c
        var names: List<String>? = null
        while (k != null && names == null) { names = ENTITY_FIELDS[k]; k = k.superclass }
        // Unknown classes: only a field literally named entityId. The looser "id / *Id" rule would take
        // container, state, teleport and transaction ids for entities.
        val wanted = names ?: listOf("entityId")
        wanted.mapNotNull { n -> findField(c, n)?.takeIf { f -> names != null || f.type == Int::class.javaPrimitiveType } }
    }

    private fun findField(c: Class<*>, name: String): Field? {
        var k: Class<*>? = c
        while (k != null && k != Any::class.java) {
            runCatching { k.getDeclaredField(name) }.getOrNull()?.let { f ->
                if (Modifier.isStatic(f.modifiers)) return null
                return if (runCatching { f.trySetAccessible() }.getOrDefault(false)) f else null
            }
            k = k.superclass
        }
        return null
    }

    /** The entity ids [p] is about, in field order (none: null). Negative ids (no entity) are left out. */
    fun entityIds(p: Any): IntArray? {
        if (p is ClientboundPlayerLookAtPacket && !lookAtEntity(p)) return null
        val fields = idFieldsOf(p.javaClass)
        if (fields.isEmpty()) return null
        val out = it.unimi.dsi.fastutil.ints.IntArrayList(2)
        for (f in fields) {
            when (val v = runCatching { f.get(p) }.getOrNull()) {
                is Int -> if (v >= 0) out.add(v)
                is IntArray -> v.forEach { if (it >= 0) out.add(it) }
                is IntList -> for (i in 0 until v.size) v.getInt(i).let { if (it >= 0) out.add(it) }
                is java.util.OptionalInt -> if (v.isPresent && v.asInt >= 0) out.add(v.asInt)
            }
        }
        return if (out.isEmpty) null else out.toIntArray()
    }

    private val lookAtField: Field? by lazy { findField(ClientboundPlayerLookAtPacket::class.java, "atEntity") }
    private fun lookAtEntity(p: ClientboundPlayerLookAtPacket) = runCatching { lookAtField?.getBoolean(p) }.getOrNull() ?: true

    /** The local player's id: [selfId] once something set it, else the client's player right now. */
    fun self(): Int = selfId.takeIf { it >= 0 } ?: (runCatching { (Minecraft.getInstance() as Minecraft?)?.player?.id }.getOrNull() ?: -1)

    /** `,"e":[ids]` and `,"self":true` when the local player is among them; "" for packets about no entity. */
    fun entityMembers(p: Any): String {
        val ids = try { entityIds(p) } catch (_: Throwable) { null } ?: return ""
        val sb = StringBuilder(16 + ids.size * 6).append(",\"e\":[")
        ids.forEachIndexed { i, id -> if (i > 0) sb.append(','); sb.append(id) }
        sb.append(']')
        val me = self()
        if (me >= 0 && ids.contains(me)) sb.append(",\"self\":true")
        return sb.toString()
    }

    // ------------------------------------------------------------------ command tree

    /**
     * After the client applied a commands packet (CommandsAppliedMixin, game thread): the tree as the
     * game now holds it, in vanilla's own JSON form. The packet line has the raw node list; this is
     * the resolved tree a mod sees when it reads the dispatcher.
     */
    @JvmStatic
    fun commandsApplied(listener: ClientPacketListener) {
        if (!Rec.active) return
        try {
            val d = listener.commands
            val tree = ArgumentUtils.serializeNodeToJson(d, d.root)
            Rec.emit("commands", "\"tree\":" + RichJson.jsonNow(tree))
        } catch (t: Throwable) {
            Rec.emit("error", "\"p\":\"commands\",\"err\":${RecorderFiles.q(t.toString())}")
        }
    }
}
