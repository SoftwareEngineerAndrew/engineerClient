package com.engineerclient.p3sim.clear.bots

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.server.level.ServerEntity
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The profile ids of the bots of the run now, so the client side can tell a bot's player entity from a real player ([AdsBotsBodyMixin] makes them unpickable). */
object BotRegistry {
    private val ids: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @JvmStatic fun isBot(id: UUID): Boolean = id in ids
    fun add(id: UUID) { ids += id }
    fun remove(id: UUID) { ids -= id }
    fun clear() { ids.clear() }
    val size: Int get() = ids.size
}

/**
 * A teammate's server body: a light mob on the server (an [Avatar], a mannequin without the mannequin's profile and description) that the client is
 * told is a real `minecraft:player` with the bot's own profile id. That is how Hypixel's teammates reach the client, and what Odin's teammate
 * binding and class glow need (they only look at entities of type player whose name is on the tab). The player-info entry must reach the client
 * BEFORE the add (the client refuses a player add without one): [BotBodies] sends it, and the team that gives the name its prefix, before it spawns the body.
 *
 * An Avatar's synched data stops at id 16, exactly the ids a Hypixel player sends, so the client's `RemotePlayer` never meets a mannequin-only data
 * id (the same trick as the mobs module's disguised fake players). It touches nothing: it is not pushable, not pickable, takes no damage.
 */
class AdsBotBody(level: Level, profileId: UUID) : Avatar(EntityTypes.MANNEQUIN, level) {
    init {
        setUUID(profileId)
        // Show the second skin layers (jacket, sleeves, trousers, hat); an Avatar shows none by default.
        entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, 0x7f.toByte())
    }

    override fun getProfile(): ResolvableProfile = Mannequin.DEFAULT_PROFILE

    /** The add packet as the client wants it: a `PLAYER` with this body's position, look and profile id. */
    fun addPacket(): Packet<ClientGamePacketListener> =
        ClientboundAddEntityPacket(id, uuid, x, y, z, xRot, yRot, EntityTypes.PLAYER, 0, deltaMovement, yHeadRot.toDouble())

    override fun getAddEntityPacket(entity: ServerEntity): Packet<ClientGamePacketListener> = addPacket()

    override fun shouldBeSaved(): Boolean = false
    override fun fireImmune(): Boolean = true
    override fun isPushable(): Boolean = false
    override fun isPickable(): Boolean = false

    /** Teammates do not push (the server-side body is a ghost to the player; on the client the team's collision rule says never). */
    override fun pushEntities() {}
}
