package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.Masks
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimItems
import com.engineerclient.p3sim.SimServer
import com.engineerclient.p3sim.clear.hud.HudPackets
import com.engineerclient.p3sim.run.RunBlocks
import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Pose
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.Team
import net.minecraft.world.scores.TeamColor
import java.util.Optional
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The visible half of a bot: a body dressed like a teammate of its class, standing where the plan puts it. The plan is the truth; the body is only
 * shown while a human is near (the world only keeps the chunks near a player, and a body in an unloaded chunk would not tick anyway), and is put
 * back wherever the plan says each tick. Server thread only. The dressing follows the boss sim's own bots (`Party`), so a bot looks the same in the
 * clear and in the fight.
 *
 * By default the body is an [AdsBotBody]: a light server mob that clients receive as a real player (Hypixel's teammates are players, and Odin's
 * class glow and teammate binding only see players), with its name and prefix from a scoreboard team. With [asPlayers] false it is the mannequin
 * the first version used (a custom name over the head, no team), for a setup where the player-entity route misbehaves.
 */
class BotBodies(private val asPlayers: () -> Boolean = { true }) {
    private val log = LoggerFactory.getLogger("engineerclient")

    private class Body(val seat: Int, val entity: Avatar, val player: Boolean, val look: BotLook, var y: Double, var yaw: Float, var held: String) {
        /** The block column the height was last worked out for. */
        var col: Long = Long.MIN_VALUE
        /** Where the body was put last tick. */
        var x = 0.0
        var z = 0.0
        /** The humans whose client has been sent this body's tab entry and name team. */
        val announced = HashSet<UUID>()
        /** Ticks in a row the body has been held back by a wall from where the plan has it. */
        var behind = 0
    }

    private val bodies = HashMap<Int, Body>()

    /** Whether a body exists for [seat] (and is alive). */
    fun has(seat: Int): Boolean = bodies[seat]?.entity?.isRemoved == false

    /** Plays the hurt animation (the red flash and the stagger) on [seat]'s body for every human that can see it. */
    fun hurt(seat: Int) {
        val e = bodies[seat]?.entity?.takeIf { !it.isRemoved } ?: return
        sendToWatchers(e, net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket(e.id, e.yRot))
    }

    /** The entity id of the body of [seat], or null when it has none. */
    fun entityId(seat: Int): Int? = bodies[seat]?.entity?.takeIf { !it.isRemoved }?.id

    val count: Int get() = bodies.values.count { !it.entity.isRemoved }

    /** Sends [pkt] to the players that see [e] (the ones the server tracks it for). */
    private fun sendToWatchers(e: Entity, pkt: Packet<in ClientGamePacketListener>) {
        try { (e.level() as? ServerLevel)?.chunkSource?.sendToTrackingPlayers(e, pkt) } catch (t: Throwable) { /* no world (headless) */ }
    }

    /**
     * Brings the body of [seat] to ([x], [z]) facing [yaw], creating it when [show] and the column is loaded, removing it when not [show].
     * Returns the height the body stands at, or [fallbackY] when there is no body. With [yOverride] the body is put at that height instead of on the
     * ground (a bot that has just appeared above the floor and is dropping onto it). A body that moves more than [BLINK_BLOCKS] blocks in one tick
     * (a blink or a leap) is taken out of the world and put back at the destination, as the server does with a teammate that teleports.
     */
    fun place(seat: Int, cls: String, look: BotLook, x: Double, z: Double, frame: BodyFrame, show: Boolean, fallbackY: Double, held: String, yOverride: Double? = null): Double {
        val yaw = frame.bodyYaw
        val existing = bodies[seat]?.takeIf { !it.entity.isRemoved }
        if (!show) { if (existing != null) remove(seat); return fallbackY }
        if (!RunBlocks.isLoaded(Math.floor(x).toInt(), Math.floor(z).toInt())) { if (existing != null) remove(seat); return fallbackY }
        // The plan walks through walls (it knows doors and room middles, not what is between); the body does not: it stops at a wall and slides along it,
        // and goes to the plan only when it has been held back too long or the plan is far away (as a blink would take it).
        val held0 = if (existing != null && yOverride == null) clip(existing, x, z) else null
        existing?.let { it.behind = if (held0 != null) it.behind + 1 else 0 }
        val px = held0?.x ?: x; val pz = held0?.z ?: z
        val col = (Math.floor(px).toLong() shl 32) xor (Math.floor(pz).toLong() and 0xffffffffL)
        val y = yOverride ?: held0?.y ?: if (existing != null && existing.col == col) existing.y else groundY(px, pz, existing?.y ?: fallbackY)
        val b = existing ?: spawn(seat, cls, look, px, y, pz, yaw) ?: return fallbackY
        if (b.player) announce(b)
        val hop = existing != null && b.player && Math.hypot(px - b.x, pz - b.z) > BLINK_BLOCKS
        b.y = y
        b.col = col
        b.yaw = yaw
        if (hop) reAdd(b, px, y, pz, frame) else {
            b.entity.snapTo(px, y, pz, yaw, 0f)
            b.entity.yHeadRot = frame.headYaw
            b.entity.yBodyRot = yaw
        }
        b.x = px; b.z = pz
        if (b.held != held) { b.held = held; b.entity.setItemSlot(EquipmentSlot.MAINHAND, item(held)) }
        val e = b.entity
        if (e.isSprinting != frame.sprint) e.isSprinting = frame.sprint
        if (e.isShiftKeyDown != frame.crouch) { e.isShiftKeyDown = frame.crouch; e.pose = if (frame.crouch) Pose.CROUCHING else Pose.STANDING }
        if (frame.swing) swing(e)
        return y
    }

    private fun spawn(seat: Int, cls: String, look: BotLook, x: Double, y: Double, z: Double, yaw: Float): Body? = try {
        val player = asPlayers()
        val entity: Avatar = if (player) AdsBotBody(Sim.level, look.profileId) else Mannequin(EntityTypes.MANNEQUIN, Sim.level).also { m ->
            m.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(GameProfile(look.profileId, look.name)))
            m.setCustomName(Component.literal(look.tag))
            m.isCustomNameVisible = true
            hideDescription(m)
        }
        entity.isPermanentlyInvulnerable = true
        entity.setNoGravity(true)
        dress(entity, cls)
        entity.snapTo(x, y, z, yaw, 0f)
        val body = Body(seat, entity, player, look, y, yaw, "")
        body.x = x; body.z = z
        bodies[seat] = body
        if (player) { BotRegistry.add(look.profileId); announce(body) }
        Sim.spawn(entity)
        body
    } catch (t: Throwable) { log.warn("[ads] could not make the body of bot ${look.name}", t); null }

    /**
     * Gives every human's client what it needs before the player entity arrives: the unlisted tab entry (the client refuses a player add without
     * one) and the team that puts the prefix and suffix round the name. Sent once per human and body; the packets queue in order, so they
     * reach the client before the add the server sends when it starts tracking the body.
     */
    private fun announce(b: Body) {
        val humans = try { Sim.players() } catch (t: Throwable) { return }
        for (p in humans) {
            if (!b.announced.add(p.uuid)) continue
            try {
                HudPackets.tabAddBots(listOf(b.look.profileId to b.look.name))?.let { p.connection.send(it) }
                for (pk in teamPackets(b.look, b.seat, add = true)) p.connection.send(pk)
            } catch (t: Throwable) { log.warn("[ads] could not announce bot ${b.look.name} to ${p.scoreboardName}: ${t.message}") }
        }
    }

    /** The body leaves the clients' world and comes back at the destination in the same tick: what a teammate's blink or leap looks like from outside. */
    private fun reAdd(b: Body, x: Double, y: Double, z: Double, frame: BodyFrame) {
        val e = b.entity as AdsBotBody
        val yaw = frame.bodyYaw
        sendToWatchers(e, ClientboundRemoveEntitiesPacket(e.id))
        e.snapTo(x, y, z, yaw, 0f)
        e.yHeadRot = frame.headYaw
        e.yBodyRot = yaw
        sendToWatchers(e, e.addPacket())
        val data = e.entityData.nonDefaultValues
        if (data != null) sendToWatchers(e, ClientboundSetEntityDataPacket(e.id, data))
        val worn = EquipmentSlot.entries.filter { it.type == EquipmentSlot.Type.HUMANOID_ARMOR || it == EquipmentSlot.MAINHAND || it == EquipmentSlot.OFFHAND }
            .map { com.mojang.datafixers.util.Pair(it, e.getItemBySlot(it).copy()) }.filter { !it.second.isEmpty }
        if (worn.isNotEmpty()) sendToWatchers(e, ClientboundSetEquipmentPacket(e.id, worn))
        sendToWatchers(e, ClientboundRotateHeadPacket(e, Mth.floor(frame.headYaw * 256f / 360f).toByte()))
    }

    fun remove(seat: Int) { bodies.remove(seat)?.entity?.let { if (!it.isRemoved) it.discard() } }

    /** Removes every body and the name teams the clients were given. */
    fun removeAll() {
        val looks = bodies.values.filter { it.player }.map { it.look to it.seat }
        for (s in bodies.keys.toList()) remove(s)
        BotRegistry.clear()
        if (looks.isEmpty()) return
        val humans = try { Sim.players() } catch (t: Throwable) { emptyList() }
        for ((look, seat) in looks) for (p in humans) try { for (pk in teamPackets(look, seat, add = false)) p.connection.send(pk) } catch (t: Throwable) { /* the player is gone */ }
    }

    /** Forgets bodies the world already removed (a savepoint restore clears every sim entity). */
    fun prune() { bodies.entries.removeIf { it.value.entity.isRemoved } }

    /** The entity's position and facing for a leap onto the bot (null when it has no body). */
    fun facing(seat: Int): Pair<Float, Float>? = bodies[seat]?.takeIf { !it.entity.isRemoved }?.let { it.yaw to 0f }

    /**
     * The height someone standing in column ([x], [z]) would stand at, nearest [hint]: the first height with a block under, and room for feet
     * and head. Full blocks are in the way; slabs, stairs and carpets are not. Nothing within 14 blocks: [hint].
     */
    fun groundY(x: Double, z: Double, hint: Double): Double = findGround(x, z, hint) ?: hint

    /** [groundY], or null when no height within 14 blocks of [hint] can be stood at (the column is not loaded, or solid all the way). */
    private fun findGround(x: Double, z: Double, hint: Double): Double? {
        val level = SimServer.level ?: return null
        val bx = Math.floor(x).toInt(); val bz = Math.floor(z).toInt()
        if (!RunBlocks.isLoaded(bx, bz)) return null
        val h = Math.floor(hint).toInt()
        fun blocks(y: Int): Boolean {
            val pos = BlockPos(bx, y, bz)
            val s = level.getBlockState(pos)
            return !s.isAir && s.isCollisionShapeFullBlock(level, pos)
        }
        fun standOn(y: Int): Boolean {
            val s = level.getBlockState(BlockPos(bx, y, bz))
            return !s.isAir && s.fluidState.isEmpty
        }
        for (d in 0..14) for (y in if (d == 0) intArrayOf(h) else intArrayOf(h + d, h - d)) {
            if (standOn(y - 1) && !blocks(y) && !blocks(y + 1)) return y.toDouble()
        }
        return null
    }

    private class Clipped(val x: Double, val z: Double, val y: Double)
    private class Reach(val x: Double, val z: Double, val y: Double, val whole: Boolean)

    /**
     * Where the body ends up this tick going from where it stands to the plan's ([x], [z]) when something is in the way: as far along the line as it can
     * stand, then sliding along the wall. Null when the way is clear, when the plan is too far away to walk to ([SNAP_BLOCKS]) or the body has been held
     * back for [LAG_TICKS] ticks: it then goes to the plan.
     */
    private fun clip(b: Body, x: Double, z: Double): Clipped? {
        val dist = Math.hypot(x - b.x, z - b.z)
        if (dist < 0.05 || dist > SNAP_BLOCKS) return null
        val first = reach(b.x, b.z, b.y, x, z)
        if (first.whole) return null
        if (b.behind >= LAG_TICKS) return null
        val alongX = reach(first.x, first.z, first.y, x, first.z)
        val alongZ = reach(alongX.x, alongX.z, alongX.y, alongX.x, z)
        return Clipped(alongZ.x, alongZ.z, alongZ.y)
    }

    /** Walks from ([fx], [fz]) at height [fy] towards ([tx], [tz]) in steps of 0.4 blocks while each step can be stood on without climbing more than [STEP_UP] or dropping more than [STEP_DOWN]. */
    private fun reach(fx: Double, fz: Double, fy: Double, tx: Double, tz: Double): Reach {
        val dist = Math.hypot(tx - fx, tz - fz)
        if (dist < 1e-6) return Reach(fx, fz, fy, true)
        val steps = maxOf(1, Math.ceil(dist / 0.4).toInt())
        var cx = fx; var cz = fz; var cy = fy
        for (i in 1..steps) {
            val sx = fx + (tx - fx) * i / steps; val sz = fz + (tz - fz) * i / steps
            val gy = findGround(sx, sz, cy) ?: return Reach(cx, cz, cy, false)
            if (gy - cy > STEP_UP || cy - gy > STEP_DOWN) return Reach(cx, cz, cy, false)
            cx = sx; cz = sz; cy = gy
        }
        return Reach(cx, cz, cy, true)
    }

    /** The arm swing of [seat]'s body, shown to everyone who can see it (a kill, a secret, a click on a lever). */
    fun swing(seat: Int) { bodies[seat]?.entity?.takeIf { !it.isRemoved }?.let { swing(it) } }

    private fun swing(e: Avatar) { try { e.swing(InteractionHand.MAIN_HAND, e.mainHandItem.interactAnimation, false) } catch (t: Throwable) { /* no world (headless) */ } }

    /** One of each item a teammate holds, made once (the real items have long tooltips); a body gets a copy. */
    private val stacks = HashMap<String, ItemStack>()

    private fun item(key: String): ItemStack = stacks.getOrPut(key) {
        when (key) {
            "terminator" -> SimItems.TERMINATOR
            "breaker" -> SimItems.DUNGEONBREAKER
            "aotv" -> SimItems.AOTV
            "leap" -> SimItems.LEAP
            "superboom" -> SimItems.SUPERBOOM
            "batwand" -> ItemStack(Items.BONE).also { it.set(DataComponents.CUSTOM_NAME, Sim.legacy("§6Bat Wand")) }
            else -> SimItems.HYPERION
        }
    }.copy()

    /** What a class holds standing about outside P3 (the boss sim's own rule): the Healer the Dungeonbreaker, the Archer the Terminator, the rest a Hyperion. */
    fun restingItem(cls: String): String = when (cls) { "HEALER" -> "breaker"; "ARCHER" -> "terminator"; else -> "hyperion" }

    /** Teammates' gear (measured from recorded runs, as the boss sim has it): a mask and dyed leather; each class wears one set seen on that class. */
    private fun dress(m: Avatar, cls: String) {
        m.setItemSlot(EquipmentSlot.HEAD, if (cls == "BERSERK") Masks.BONZO_MASK else Masks.SPIRIT_MASK)
        val (chest, legs, boots) = when (cls) {
            "HEALER" -> Triple(0x1793c4, 0x17a8c4, 0x8969c8)
            "MAGE" -> Triple(0x1793c4, 0x17a8c4, 0x1cd4e4)
            "TANK" -> Triple(-1, 0x5d2fb9, 0x8969c8)
            "ARCHER" -> Triple(0xe7413c, 0xe75c3c, 0x8969c8)
            else -> Triple(0x3e05af, 0x5d23d1, 0x7c44ec)
        }
        fun dyed(item: net.minecraft.world.item.Item, rgb: Int) =
            if (rgb < 0) ItemStack(Items.CHAINMAIL_CHESTPLATE) else ItemStack(item).also { it.set(DataComponents.DYED_COLOR, net.minecraft.world.item.component.DyedItemColor(rgb)) }
        m.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, chest))
        m.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, legs))
        m.setItemSlot(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, boots))
    }

    private val hideMethod by lazy { Mannequin::class.java.getDeclaredMethod("setHideDescription", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
    private fun hideDescription(m: Mannequin) { runCatching { hideMethod.invoke(m, true) } }

    companion object {
        /** Highest step a body takes without a jump, and the deepest drop it takes in one step. */
        const val STEP_UP = 1.3
        const val STEP_DOWN = 4.0

        /** A body held back by a wall goes to the plan when it is this many blocks from it, or has been held back this many ticks in a row (the plan may cross a wall the body cannot). */
        const val SNAP_BLOCKS = 16.0
        const val LAG_TICKS = 40

        /** A body that moves farther than this in one tick got there by a blink or a leap, and is taken out of the world and put back (REAL: a teammate's blink hops are 10 blocks, leaps farther). */
        const val BLINK_BLOCKS = 6.0

        private val board = Scoreboard()

        /** The name of the scoreboard team of a bot seat (the clients' scoreboards hold it for the run). */
        fun teamName(seat: Int): String = "adsbot$seat"

        private fun colour(code: String): TeamColor? = when (code) {
            "§b" -> TeamColor.AQUA; "§a" -> TeamColor.GREEN; "§6" -> TeamColor.GOLD; "§c" -> TeamColor.RED; "§f" -> TeamColor.WHITE
            else -> null
        }

        /**
         * The packets that make a client show [look]'s text round the name: a team with the prefix, the suffix and the name's colour, holding the bot's name,
         * that never collides (so a client never pushes a teammate or is pushed by one); or, with [add] false, the packet that takes the team away.
         */
        fun teamPackets(look: BotLook, seat: Int, add: Boolean): List<Packet<ClientGamePacketListener>> {
            val team = PlayerTeam(board, teamName(seat))
            if (!add) return listOf(ClientboundSetPlayerTeamPacket.createRemovePacket(team))
            team.setPlayerPrefix(Sim.legacy(look.prefix))
            team.setPlayerSuffix(Sim.legacy(look.suffix))
            colour(look.nameColour)?.let { team.setColor(Optional.of(it)) }
            team.setNameTagVisibility(Team.Visibility.ALWAYS)
            team.setCollisionRule(Team.CollisionRule.NEVER)
            team.setAllowFriendlyFire(true)
            team.setSeeFriendlyInvisibles(true)
            return listOf(
                ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true),
                ClientboundSetPlayerTeamPacket.createPlayerPacket(team, look.name, ClientboundSetPlayerTeamPacket.Action.ADD),
            )
        }
    }
}
