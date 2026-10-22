package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimStand
import com.engineerclient.p3sim.pack.mobs.MobArchetype
import com.engineerclient.p3sim.pack.mobs.MobModifier
import com.engineerclient.p3sim.pack.mobs.MobTag
import com.engineerclient.p3sim.run.api.MobRecord
import com.mojang.authlib.GameProfile
import com.mojang.datafixers.util.Pair
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** The tag used for an archetype whose pack row carries none. */
internal val DEFAULT_TAG = MobTag(idOffset = 1, dy = 2.09, head = "§6✯ §r", nameColor = "§c", bold = false)

/**
 * One mob as the module tracks it: its record (what other modules see), the roll that made it, and, while its chunk is loaded, the
 * entities that show it. The roll and the record are all that is saved; the entities are rebuilt from them.
 */
internal class MobEntry(val record: MobRecord, val plan: PlannedMob, val arch: MobArchetype, val modifier: MobModifier?) {
    /** Where the mob is now (or was when its body last existed). */
    var x = plan.x
    var y = plan.y
    var z = plan.z
    var body: Entity? = null
    var fillers: List<Entity> = emptyList()
    var stand: SimStand? = null
    /** A sleeping Fels: invisible, cannot be hurt, wakes when a player body comes within the wake radius. */
    var asleep = arch.movement == "sleeper"
    /** The Shadow Assassin stays invisible until it first notices a player. */
    var hidden = arch.movement == "teleport"
    var profile: GameProfile? = null
    var announced = false
    var lastTag = ""
    val ai = AiState()
    var navCooldown = 0
    /** Server tick of the last time the stage built this mob, and the earliest tick it may be built again (a body that vanishes at once backs off). */
    var builtAt = Int.MIN_VALUE / 2
    var retryAt = Int.MIN_VALUE / 2
    var vanishes = 0
    /** Level game time of the last build: the level's lookup is trusted only [MobWorld.LOOKUP_GRACE] ticks after it. */
    var builtGameTime = 0L
    /** Builds that threw, in a row. Each one backs the next try off; the third makes a disguised mob an ordinary body instead. */
    var buildFailures = 0
    var plainBody = false
    var lastNavX = Double.NaN
    var lastNavZ = Double.NaN

    val tag: MobTag get() = arch.tag ?: DEFAULT_TAG
    val alive: Boolean get() = record.alive
    /** The stage has built this mob's entities and they have not been released. */
    var live = false
    val materialised: Boolean get() = live
}

/** Builds and removes the entities of a [MobEntry]. Server thread only. */
internal object MobWorld {
    private val log = org.slf4j.LoggerFactory.getLogger("engineerclient")

    /** The tag text for the mob's current HP. */
    fun tagText(e: MobEntry): String =
        if (BloodLooks.isBlood(e.arch)) BloodLooks.tag(e.arch, e.plan.modifier, e.record.hp, e.record.maxHp)
        else if (LevelLooks.levelled(e.arch)) LevelLooks.tag(e.arch, e.record.hp, e.record.maxHp)
        else MobTags.nametagLegacy(e.tag, e.arch.name, e.modifier, e.record.hp, e.record.maxHp, e.plan.star)

    /**
     * Makes the mob's entities at its current position and adds them to the world: the body (a vanilla type with no brain, or a
     * fake player), the stands between it and its tag, and the tag. Returns false when it could not (nothing is left behind).
     * [fakePlayers] false (or a failure announcing the player info) makes a disguised mob an ordinary zombie body.
     */
    fun materialise(e: MobEntry, level: ServerLevel, fakePlayers: Boolean, healthData: Boolean): Boolean {
        val arch = e.arch
        var disguised = arch.disguise && fakePlayers && !e.plainBody
        var profile: GameProfile? = null
        if (disguised) {
            profile = e.profile ?: Disguise.profile(e.plan.id, Disguise.profileName(arch), arch.skin ?: Disguise.missingSkin(arch))
            if (!e.announced) e.announced = Disguise.announce(profile)
            if (!e.announced) { disguised = false; profile = null }
        }
        e.profile = profile
        val built = try { MobBodies.build(level, e.tag.idOffset.coerceAtLeast(1)) { makeBody(level, arch, disguised) } } catch (t: Throwable) {
            log.warn("[ads] cannot build a ${arch.id}: ${t.message}"); return false
        }
        val body = built.body
        try {
            body.setUUID(e.plan.id)
            body.snapTo(e.x, e.y, e.z, e.plan.yaw, 0f)
            // A fake player's head and body face the same way as the spawn rotation (a stale head yaw of 0 shows a twisted neck).
            (body as? LivingEntity)?.let { it.yHeadRot = e.plan.yaw; it.yBodyRot = e.plan.yaw }
            body.isSilent = true
            body.isPermanentlyInvulnerable = true
            if (body is Mob) { body.setPersistenceRequired(); body.setCanPickUpLoot(false) }
            dress(body, e)
            if (healthData && body is LivingEntity) MobBodies.setHealthData(body, e.record.hp)
            val text = tagText(e)
            MobBodies.dressTag(built.stand, Sim.legacy(text))
            (built.stand as? com.engineerclient.p3sim.TransientStand)?.let { it.trackSquare = TAG_RANGE; tags += it }
            built.stand.snapTo(e.x, e.y + e.tag.dy, e.z, 0f, 0f)
            for (f in built.fillers) f.snapTo(e.x, e.y, e.z, 0f, 0f)
            Sim.spawn(body)
            e.builtGameTime = level.gameTime
            for (f in built.fillers) Sim.spawn(f)
            Sim.spawn(built.stand)
            e.body = body; e.fillers = built.fillers; e.stand = built.stand; e.lastTag = text
            e.record.entityId = body.id
            e.live = true
            return true
        } catch (t: Throwable) {
            log.warn("[ads] cannot place a ${arch.id}: ${t.message}")
            body.discard(); built.fillers.forEach { it.discard() }; built.stand.discard()
            return false
        }
    }

    private fun makeBody(level: ServerLevel, arch: MobArchetype, disguised: Boolean): Entity = when {
        disguised -> AdsDisguise(level)
        arch.entity == "minecraft:skeleton" -> AdsSkeleton(level)
        arch.entity == "minecraft:wither_skeleton" -> AdsWitherSkeleton(level)
        arch.entity == "minecraft:enderman" -> AdsEnderman(level)
        arch.entity == "minecraft:silverfish" -> AdsSilverfish(level)
        arch.entity == "minecraft:spider" -> AdsSpider(level)
        arch.entity == "minecraft:giant" -> AdsGiant(level)
        else -> AdsZombie(level)
    }

    /** Names, held items and the sleeping/hidden looks. */
    private fun dress(body: Entity, e: MobEntry) {
        val arch = e.arch
        arch.bodyName?.let { body.customName = Component.literal(it); body.isCustomNameVisible = false }
        if (body !is LivingEntity) return
        val eq = arch.equipment
        when {
            // The Watcher's NPCs: a stone sword and chainmail; his giant: plain leather armour.
            BloodLooks.isBlood(arch) && body !is AdsGiant -> {
                body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.STONE_SWORD))
                body.setItemSlot(EquipmentSlot.CHEST, ItemStack(Items.CHAINMAIL_CHESTPLATE))
                body.setItemSlot(EquipmentSlot.LEGS, ItemStack(Items.CHAINMAIL_LEGGINGS))
                body.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.CHAINMAIL_BOOTS))
            }
            body is AdsGiant -> {
                body.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.LEATHER_HELMET))
                body.setItemSlot(EquipmentSlot.CHEST, ItemStack(Items.LEATHER_CHESTPLATE))
                body.setItemSlot(EquipmentSlot.LEGS, ItemStack(Items.LEATHER_LEGGINGS))
                body.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.LEATHER_BOOTS))
            }
            MobGear.has(arch.id) -> MobGear.dress(body, arch.id, e.plan.id)
            // Skeletor Prime holds a bone, the Undead Skeleton a bow, the others nothing.
            LevelLooks.isLevel(arch) -> when (LevelLooks.hand(arch)) {
                "minecraft:bone" -> body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.BONE))
                "minecraft:bow" -> body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.BOW))
            }
            eq != null -> {
                if (eq.mainhand == "minecraft:iron_sword") {
                    val sword = ItemStack(Items.IRON_SWORD)
                    eq.mainhandName?.let { sword.set(DataComponents.CUSTOM_NAME, Component.literal(it)) }
                    body.setItemSlot(EquipmentSlot.MAINHAND, sword)
                }
                if (eq.feet == "minecraft:leather_boots") body.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.LEATHER_BOOTS))
            }
            body is AdsDisguise -> body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
            body is AdsSkeleton -> body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.BOW))
            body is AdsWitherSkeleton -> body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.STONE_SWORD))
        }
        // A sleeping Fels wears nothing; it is invisible through a real Invisibility effect (the flag alone is cleared by the game on its first tick).
        if (e.asleep || e.hidden) hide(body)
    }

    /** The infinite ambient Invisibility effect Hypixel gives sleepers and the hidden Shadow Assassin. */
    private fun hide(body: LivingEntity) {
        body.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, true, true, false))
    }

    private fun show(body: Entity?) {
        (body as? LivingEntity)?.let { it.removeEffect(MobEffects.INVISIBILITY); it.isInvisible = false }
    }

    /** Wakes a sleeper: visible again. */
    fun wake(e: MobEntry) {
        e.asleep = false
        show(e.body)
    }

    /** The Shadow Assassin shows itself. */
    fun reveal(e: MobEntry) {
        e.hidden = false
        show(e.body)
    }

    /** Rewrites the tag when the text changed (HP, colour), which sends the new name to the clients. */
    fun updateTag(e: MobEntry) {
        val stand = e.stand ?: return
        val text = tagText(e)
        if (text == e.lastTag) return
        e.lastTag = text
        stand.customName = Sim.legacy(text)
    }

    /** Keeps the tag and the filler stands with a body that walked (or was pushed). */
    fun follow(e: MobEntry) {
        val body = e.body ?: return
        e.x = body.x; e.y = body.y; e.z = body.z
        e.record.x = body.x; e.record.y = body.y; e.record.z = body.z
        e.stand?.let { if (it.x != body.x || it.y != body.y + e.tag.dy || it.z != body.z) it.setPos(body.x, body.y + e.tag.dy, body.z) }
        for (f in e.fillers) if (f.x != body.x || f.y != body.y || f.z != body.z) f.setPos(body.x, body.y, body.z)
    }

    /** Takes the entities away (no event, no packet but the removal the tracker sends). The record stays. */
    fun dematerialise(e: MobEntry) {
        e.body?.let { if (!it.isRemoved) it.discard() }
        e.fillers.forEach { if (!it.isRemoved) it.discard() }
        e.stand?.let { if (!it.isRemoved) it.discard() }
        e.body = null; e.fillers = emptyList(); e.stand = null
        e.live = false
    }

    /** Archetypes that keep their body for the vanilla 20-tick death animation (recorded: Crypt Undead, Giant, Withermancer, Wither Miner and Guard, the bracket-named mini-boss kinds); every other mob is removed 2 ticks after the hit. */
    private val LONG_DEATH = setOf("crypt_undead", "giant", "withermancer", "wither_miner", "wither_guard", "skeletor_prime", "undead_skeleton", "parasite")
    const val DEATH_TICKS = 2L
    const val LONG_DEATH_TICKS = 21L

    fun deathDelay(arch: MobArchetype): Long = if (arch.id in LONG_DEATH) LONG_DEATH_TICKS else DEATH_TICKS

    /** A body that has been killed and waits for its removal tick. */
    private class Dying(val body: Entity, val fillers: List<Entity>, val stand: Entity?, val removeAt: Long)
    private val dying = ArrayList<Dying>()

    /**
     * A death on the wire, as Hypixel does it: no zero-health packet; in the kill tick the hurt animation (yaw 0) and entity event 3
     * go to the trackers, a fake player's five equipment slots are emptied, and body, fillers and tag go together
     * [deathDelay] ticks later (see [tick]). A mob that dies slowly shows 0 HP on its tag first.
     */
    fun die(e: MobEntry, level: ServerLevel) {
        val body = e.body
        if (body != null && !body.isRemoved) {
            fun send(p: net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>) = level.chunkSource.sendToTrackingPlayers(body, p)
            send(ClientboundHurtAnimationPacket(body.id, 0f))
            if (body is AdsDisguise) {
                send(ClientboundSetEquipmentPacket(body.id, listOf(EquipmentSlot.MAINHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD).map { Pair(it, ItemStack.EMPTY) }))
            }
            send(ClientboundEntityEventPacket(body, 3.toByte()))
            val delay = deathDelay(e.arch)
            if (delay > DEATH_TICKS) updateTag(e)
            dying += Dying(body, e.fillers, e.stand, level.gameTime + delay)
            e.body = null; e.fillers = emptyList(); e.stand = null
            e.live = false
            return
        }
        dematerialise(e)
    }

    /**
     * How near a player must be, on x and on z, for a mob's name tag to be sent: Hypixel sends the tag stand only within about 24 blocks
     * (Dungeon Recorder, 223 F7 runs: tag stands added and removed at a sharp edge of 24 to 26 blocks, square on x/z, while the bodies
     * reach 48), so a tag is never seen across the map or through several rooms. Vanilla would track a stand out to the view distance.
     */
    const val TAG_RANGE = 24

    /** Ticks after a build before a body missing from the level's lookup counts as gone. */
    const val LOOKUP_GRACE = 40

    /** The live tag stands, their tracking re-checked every tick (vanilla re-checks only when someone crosses a 16-block section). */
    private val tags = ArrayList<com.engineerclient.p3sim.TransientStand>()

    private val entityMapField by lazy { net.minecraft.server.level.ChunkMap::class.java.getDeclaredField("entityMap").also { it.isAccessible = true } }
    private var updatePlayer: java.lang.reflect.Method? = null

    /** Adds or removes each live tag on every player's client as the 24-block square says. */
    private fun trackTags(level: ServerLevel) {
        tags.removeIf { it.isRemoved }
        if (tags.isEmpty()) return
        try {
            val map = entityMapField.get(level.chunkSource.chunkMap) as it.unimi.dsi.fastutil.ints.Int2ObjectMap<*>
            val players = level.players()
            for (t in tags) {
                val tracked = map.get(t.id) ?: continue
                val m = updatePlayer ?: tracked.javaClass.getMethod("updatePlayer", net.minecraft.server.level.ServerPlayer::class.java).also { it.isAccessible = true; updatePlayer = it }
                for (p in players) m.invoke(tracked, p)
            }
        } catch (t: Throwable) { if (!trackWarned) { trackWarned = true; log.warn("[ads] tag tracking: ${t.message}") } }
    }
    private var trackWarned = false

    /** Removes the bodies whose death delay is over, each with its fillers and tag in the same tick; re-checks who sees each tag. */
    fun tick(level: ServerLevel) {
        trackTags(level)
        if (dying.isEmpty()) return
        val now = level.gameTime
        val it = dying.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (now < d.removeAt) continue
            it.remove()
            discardDying(d)
        }
    }

    /** Removes every waiting body now (the run ended). */
    fun flushDying() {
        for (d in dying) discardDying(d)
        dying.clear()
    }

    private fun discardDying(d: Dying) {
        if (!d.body.isRemoved) d.body.discard()
        d.fillers.forEach { if (!it.isRemoved) it.discard() }
        d.stand?.let { if (!it.isRemoved) it.discard() }
    }

    /** Retracts a fake player's info entry and team once. */
    fun retract(e: MobEntry) {
        val p = e.profile ?: return
        if (e.announced) Disguise.retract(p.id, p.name)
        e.announced = false
    }
}

/** The Minecraft side of [MobStage]: real entities in the sim's level. */
internal object RealStage : MobStage {
    private val level get() = com.engineerclient.p3sim.run.RunBlocks.level

    override fun available() = level != null
    override fun isLoaded(x: Int, z: Int) = com.engineerclient.p3sim.run.RunBlocks.isLoaded(x, z)
    override fun materialise(e: MobEntry): Boolean {
        val lv = level ?: return false
        return MobWorld.materialise(e, lv, MobsModule.fakePlayersOn(), MobsModule.healthDataOn())
    }
    /**
     * The body is gone when it was removed, when its tag was (the tag is unloaded with its chunk), or when the level no longer holds it
     * although its chunk is loaded: a body that is not saved leaves the level with its chunk without being marked removed, and
     * checking the flag alone kept such a mob "live" with nothing in the world for good. Not in the first [MobWorld.LOOKUP_GRACE] ticks:
     * right after a teleport (a practice start) a new body may not be in the lookup yet, and counting it gone held the mob back 10 s.
     */
    override fun isGone(e: MobEntry): Boolean {
        val b = e.body ?: return true
        if (b.isRemoved || e.stand?.isRemoved == true) return true
        val lv = level ?: return false
        if (lv.gameTime - e.builtGameTime < MobWorld.LOOKUP_GRACE) return false
        return lv.getEntity(b.id) !== b && com.engineerclient.p3sim.run.RunBlocks.isLoaded(Math.floor(b.x).toInt(), Math.floor(b.z).toInt())
    }
    override fun release(e: MobEntry) = MobWorld.dematerialise(e)
    override fun die(e: MobEntry) { val lv = level; if (lv != null) MobWorld.die(e, lv) else MobWorld.dematerialise(e) }
    override fun retract(e: MobEntry) = MobWorld.retract(e)
    override fun announceTo(playerId: java.util.UUID, entries: Collection<MobEntry>) {
        val p = Sim.players().firstOrNull { it.uuid == playerId } ?: return
        for (e in entries) { val prof = e.profile; if (e.announced && prof != null) Disguise.announceTo(p, prof) }
    }
    override fun wake(e: MobEntry) = MobWorld.wake(e)
    override fun reveal(e: MobEntry) = MobWorld.reveal(e)
    override fun refresh(e: MobEntry) {
        MobWorld.updateTag(e)
        (e.body as? LivingEntity)?.let { if (MobsModule.healthDataOn()) MobBodies.setHealthData(it, e.record.hp) }
    }
    /** Hypixel shows a hit with the hurt animation (yaw 0) only, never entity event 2. */
    override fun hurtFlash(e: MobEntry) { e.body?.let { b -> level?.chunkSource?.sendToTrackingPlayers(b, ClientboundHurtAnimationPacket(b.id, 0f)) } }
    override fun tick() { level?.let { MobWorld.tick(it) } }
    override fun flush() = MobWorld.flushDying()
    override fun position(e: MobEntry): DoubleArray? {
        val b = e.body ?: return null
        MobWorld.follow(e)
        return doubleArrayOf(b.x, b.y, b.z)
    }
    override fun canSee(e: MobEntry, x: Double, y: Double, z: Double): Boolean {
        val b = e.body ?: return false
        val lv = level ?: return false
        val from = net.minecraft.world.phys.Vec3(b.x, b.y + b.eyeHeight, b.z)
        val hit = lv.clip(net.minecraft.world.level.ClipContext(from, net.minecraft.world.phys.Vec3(x, y, z), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()))
        return hit.type == net.minecraft.world.phys.HitResult.Type.MISS
    }
    override fun place(e: MobEntry, x: Double, y: Double, z: Double) {
        val body = e.body ?: return
        val dx = x - body.x; val dz = z - body.z
        if (dx * dx + (y - body.y) * (y - body.y) + dz * dz < 1e-6) return
        stop(body)
        val yaw = if (dx * dx + dz * dz > 1e-4) Math.toDegrees(Math.atan2(-dx, dz)).toFloat() else body.yRot
        body.snapTo(x, y, z, yaw, body.xRot)
        (body as? LivingEntity)?.let { it.yHeadRot = yaw; it.yBodyRot = yaw }
    }
    override fun rescue(e: MobEntry) { e.body?.let { if (it.y < -8.0) it.snapTo(e.plan.x, e.plan.y, e.plan.z, e.plan.yaw, 0f) } }

    /** Blocks per tick a unit of movement-speed attribute walks at full input (a player's 0.1 is 4.317 blocks a second). */
    private const val BPT_PER_ATTRIBUTE = 2.159

    /**
     * The speed a pathfinding mob needs to walk [bpt] blocks a tick. Its move control sets the speed and the forward input both to it, so it walks
     * BPT_PER_ATTRIBUTE times its square (a zombie's 0.23 is 0.114, the 2.3 blocks a second it is known for), not times the speed as a player does.
     */
    internal fun mobSpeedFor(bpt: Double) = Math.sqrt(bpt.coerceAtLeast(0.0) / BPT_PER_ATTRIBUTE)

    override fun act(e: MobEntry, action: AiAction, targetY: Double, targetX: Double, targetZ: Double) {
        val body = e.body ?: return
        when (action) {
            AiAction.Idle -> stop(body)
            is AiAction.MoveTo -> move(e, body, action.x, if (e.ai.chasing && !targetY.isNaN()) targetY else body.y, action.z, action.speed)
            // A swing or a shot is made standing: a fake player would otherwise keep the walk it had and push into its target.
            AiAction.Melee, AiAction.Shoot -> { stop(body); faceAndSwing(body, targetX, targetZ) }
            is AiAction.Teleport -> body.snapTo(action.x, if (targetY.isNaN()) body.y else targetY, action.z, body.yRot, 0f)
        }
    }

    private fun faceAndSwing(body: Entity, tx: Double, tz: Double) {
        if (!tx.isNaN()) {
            val yaw = Math.toDegrees(Math.atan2(-(tx - body.x), tz - body.z)).toFloat()
            body.yRot = yaw
            (body as? LivingEntity)?.let { it.yHeadRot = yaw; it.yBodyRot = yaw }
        }
        when (body) {
            is Mob -> body.swingForAttack(net.minecraft.world.InteractionHand.MAIN_HAND)
            is LivingEntity -> body.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
            else -> {}
        }
    }

    private fun stop(body: Entity) {
        when (body) {
            is Mob -> if (!body.navigation.isDone) body.navigation.stop()
            is AdsDisguise -> { body.zza = 0f; body.setSpeed(0f) }
        }
    }

    private fun move(e: MobEntry, body: Entity, x: Double, y: Double, z: Double, speed: Double) {
        when (body) {
            is Mob -> {
                if (e.navCooldown > 0) e.navCooldown--
                val moved = e.lastNavX.isNaN() || Math.abs(e.lastNavX - x) + Math.abs(e.lastNavZ - z) > 1.0
                if (e.navCooldown == 0 && (moved || body.navigation.isDone)) {
                    val attr = body.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).coerceAtLeast(0.01)
                    val mod = (mobSpeedFor(speed) / attr).coerceIn(0.1, 6.0)
                    body.navigation.moveTo(x, y, z, mod)
                    e.navCooldown = 8; e.lastNavX = x; e.lastNavZ = z
                }
            }
            is AdsDisguise -> {
                // A fake player has no pathfinder: face the point and walk straight, jumping when it bumps into something.
                val yaw = Math.toDegrees(Math.atan2(-(x - body.x), z - body.z)).toFloat()
                body.yRot = yaw; body.yHeadRot = yaw; body.yBodyRot = yaw
                body.setSpeed((speed / BPT_PER_ATTRIBUTE).toFloat())
                body.zza = 1f
                body.setJumping(body.horizontalCollision)
            }
        }
    }
}
