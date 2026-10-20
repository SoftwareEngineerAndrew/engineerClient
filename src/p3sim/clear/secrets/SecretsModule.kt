package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.BlessingGranted
import com.engineerclient.p3sim.run.BonusKind
import com.engineerclient.p3sim.run.MobKilled
import com.engineerclient.p3sim.run.RoomCleared
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunBlocks
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.SecretKind
import com.engineerclient.p3sim.clear.hud.ActionBarModel
import com.engineerclient.p3sim.run.api.Audience
import com.engineerclient.p3sim.run.api.SecretService
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.random.Random

/**
 * Secrets of every type from the rooms pack, crypts, Superboom walls, the Dungeonbreaker's dungeon rules, blessings and loot; the
 * counts the action bar, tab and Odin read.
 *
 * What a player sees. Chests and levers stand in the world from the start of the run (like Hypixel's, placed with the first chunks); a
 * floor item shows up when a party member is within about 32 blocks of it (sideways), a bat within 14, a skull within 16 (a timer
 * every 20 ticks, as measured). Right-clicking a chest counts the secret and opens its lid; about half of the chests then play a
 * five-note harp and give their loot 24 ticks after the click (a blessing or a lump of essence), the rest give nothing. A skull
 * counts when it is first clicked (or hit with the Dungeonbreaker) and pays out 20 ticks later, after a pling and five notes. A
 * Redstone Key is only picked up; the secret counts when the key is used on its socket. A bat dies to any hit, an item is taken by
 * walking into it. Every find moves the room's `found/max`, the team and player counts, and the room turns green when the count is
 * full (that last step is the doors module's, on the `SecretFound` event posted here). Teammates' finds run the same ceremonies, so
 * their lines, sounds and loot reach the player too.
 *
 * The counts are in [SecretBook]; what is where is in [SecretPlan] and [CellGroups]; the timings and draws are in the pure classes of
 * SecretFinales.kt, Loot.kt and Blessings.kt; this object is the glue to the world and the bus. Its state is per run, reset in
 * [onRunStart]. Server thread only.
 */
object SecretsModule : AdsModule, SecretService {
    override val id = "secrets"
    override val owner = "secrets"

    /** A chest that has been opened (by the player, or by a teammate's find): what it holds and how far its ceremony has got. */
    private class ChestRec(val site: SecretSite, val drop: Drop, val by: UUID?, val start: Int, val trapped: Boolean = false) {
        val reward: Boolean get() = drop !is Drop.Nothing
        val done = HashSet<Int>()
        var finished = trapped
    }

    /** A wither essence skull that has been counted and is playing its sequence. */
    private class SkullRec(val site: SecretSite, val by: UUID?, val start: Int) {
        val done = HashSet<Int>()
    }

    /** A lever wall that is opening and ticking. */
    private class WallTicks(val at: Vec3, val start: Int) {
        val done = HashSet<Int>()
    }

    /** Everything this module knows about the running dungeon. */
    private class RunData(val run: Run, val plan: SecretPlan, val groups: CellGroups, val book: SecretBook, val lootRandom: Random) {
        val levers = HashMap<Long, LeverInfo>()
        /** Site keys of skulls the sim has put in the world (cleared on a restore: the resync took them out). */
        val placed = HashSet<String>()
        /** Live floor items and bats by site key. */
        val entities = HashMap<String, UUID>()
        val unlocked = HashSet<String>()
        /** Lever block key to the player who pulled it (null UUID is stored as a nil id). */
        val leverUsed = HashMap<Long, UUID>()
        /** Lever walls closing: (server tick when the barrier turns to air, cells). */
        val walls = ArrayList<Pair<Int, List<Pos>>>()
        /** Blocks to write once their chunk is loaded (a restore, or a far blast). */
        val fixes = LinkedHashMap<Long, Pair<Pos, BlockState>>()
        /** The Nth counted secret-bat kill carries the bonus point (index 0: this run has none), and how many have been counted. */
        val bat = BonusBatTracker(0)
        /** Run ticks at which this run's Mimic and Prince die (the pack's measured odds and times), or null when that bonus is not scored in this run. */
        var mimicAt: Int? = null
        var princeAt: Int? = null
        /** Whether the Prince's chat line shows when it falls (it did in about a fifth of the clears that scored it). */
        var princeLoud = false
        var lastSpawnPass = -SPAWN_PERIOD
        val skullTextures = HashMap<SecretKind, SkullTexture?>()
        /** Tomb blasts by teammates still to happen: (server tick, group, who). */
        val botBlasts = ArrayList<Triple<Int, CellGroup, UUID?>>()
        val miscRandom: Random = run.subRandom("secrets-blessings")
        val sounds: Random = run.subRandom("secrets-sounds")
        val dropRandom: Random = run.subRandom("secrets-drops")
        /** The chest that is the Mimic's (a trapped chest), or null. */
        var trapped: String? = null
        val chests = HashMap<String, ChestRec>()
        val skulls = HashMap<String, SkullRec>()
        val wallTicks = ArrayList<WallTicks>()
        /** Chests opened before a restore whose lid the clients still have to be told is open. */
        val lidsOwed = HashSet<String>()
        /** The key skulls picked up (by gate key), and the rooms they were picked up in: a key is the party's, held by state. */
        val gatesTaken = HashSet<String>()
        val keyRooms = HashSet<Int>()
        /** Who dealt the last required kill of each room: the room clear's drop is theirs. */
        val lastKiller = HashMap<Int, UUID?>()
        /** Small things to do on a later server tick (a skull going a tick after its pickup, the Mimic's zombie). Not saved in savepoints. */
        val later = TimedQueue<() -> Unit>()
        /** The server tick of the last "That chest is locked!" each player was told. */
        val lockedSaid = HashMap<UUID, Int>()
        val mimicBodies = ArrayList<UUID>()
    }

    private var data: RunData? = null

    private val NIL = UUID(0, 0)

    private fun d(run: Run): RunData? = data?.takeIf { it.run === run }

    // ---- lifecycle ----------------------------------------------------------------------------------------------------------------

    override fun registerSettings(host: com.odtheking.odin.features.Module) { SecretsSettings.register(host) }

    override fun onRunStart(run: Run) {
        data = null
        val random = run.subRandom("secrets")
        val plan = SecretPlan.build(run.plan, random)
        val groups = CellGroups.build(run.plan)
        val book = SecretBook(plan, run.state, run.bus, { run.t }, { SecretsSettings.autoCredit })
        val rd = RunData(run, plan, groups, book, run.subRandom("secrets-loot"))
        rd.bat.index = replayBonusBat?.invoke(run) ?: if (run.subRandom("secrets-bat").nextInt(100) < SecretsSettings.bonusBatPercent) BonusBat.draw(run.subRandom("secrets-bat-index")) else 0
        rollBonusMobs(rd)
        val textures = run.packs.rooms?.textures.orEmpty()
        rd.skullTextures[SecretKind.ESSENCE] = SecretWorld.essenceTexture(SecretWorld.textureFor(textures, "essence"), SecretWorld.textureFor(textures, SecretWorld.ESSENCE_SKIN_ROLE))
        rd.skullTextures[SecretKind.REDSTONE_KEY] = SecretWorld.textureFor(textures, "redstoneKey")
        Levers.build(run.plan, plan).let { (levers, free) -> rd.levers.putAll(levers); rd.unlocked.addAll(free) }
        data = rd
        pickTrappedChest(rd)
        placeChests(rd)
        val placeable = plan.rooms.sumOf { it.placeable }
        org.slf4j.LoggerFactory.getLogger("engineerclient").info("[ads] secrets: {} placeable of {} printed, {} crypt/wall groups, trapped chest {}", placeable, run.plan.secretsTotal, groups.groups.size, rd.trapped ?: "none")
    }

    /**
     * The Mimic and the Prince are not spawned as mobs in version 1, but their points are in most real scores (mimic 82 %, prince 62 % of clears, two
     * points and one), so each run decides from the pack's `bonus` section whether it scores them and when. Without the section nothing is scored.
     * A dungeon with a trapped chest scores its Mimic when that chest is opened instead ([pickTrappedChest]).
     */
    private fun rollBonusMobs(rd: RunData) {
        val b = rd.run.packs.runstate?.section("bonus") ?: return
        val random = rd.run.subRandom("secrets-bonus-mobs")
        fun roll(frac: String, tick: String): Int? {
            val f = b.get(frac)?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
            val dist = com.engineerclient.p3sim.pack.runstate.Dist.parse(b.get(tick)) ?: return null
            val u = random.nextDouble(); val at = dist.sample(random.nextDouble()).toInt()
            return if (u < f) at else null
        }
        rd.mimicAt = roll("mimicKilledFrac", "mimicTick")
        rd.princeAt = roll("princeKilledFrac", "princeTick")
        val chat = b.get("princeChatPerClear")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        val prince = b.get("princeKilledFrac")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 1.0
        val loudShare = if (prince > 0.0) chat / prince else 0.0
        rd.princeLoud = random.nextDouble().let { it < loudShare }
    }

    /**
     * One chest of most dungeons (151 of 211) is a trapped chest, the Mimic's: it never opens, and a zombie stands where the click was.
     * It is one of the plain chests the sim places. Such a dungeon scores its Mimic when the chest is opened, not by the clock.
     */
    private fun pickTrappedChest(rd: RunData) {
        val candidates = rd.plan.all.filter { it.kind == SecretKind.CHEST && !it.baked && !it.hidden && rd.run.plan.rooms.getOrNull(it.room)?.type.let { t -> t == RoomType.NORMAL || t == RoomType.RARE || t == RoomType.YELLOW } }
        rd.trapped = TrappedChest.pick(candidates, TrappedChest.DEFAULT_CHANCE, rd.run.subRandom("secrets-trapped"))?.key
        if (rd.trapped != null) rd.mimicAt = null
    }

    /**
     * Puts every chest of the dungeon in the world: Hypixel places them with the first chunks, so they can be seen from across the dungeon. The
     * key rooms' sockets are redstone blocks (the Oasis's and the Key room's cells are air in the rooms pack), so they are placed here too.
     */
    private fun placeChests(rd: RunData) {
        val redstone = Blocks.REDSTONE_BLOCK.defaultBlockState()
        for (s in rd.plan.all) if (s.socket != null) setOrFix(rd, s.x, s.y, s.z, redstone)
        for (s in rd.plan.all) {
            if ((s.kind != SecretKind.CHEST && s.kind != SecretKind.LOCKED_CHEST) || s.hidden || s.baked) continue
            setOrFix(rd, s.x, s.y, s.z, if (s.key == rd.trapped) SecretWorld.trappedChestState(s) else SecretWorld.chestState(s))
        }
    }

    /**
     * While a replay runs the team is the recording's: no Mimic or Prince kill drawn for bots, no tombs blown for a teammate's room check-off.
     * The replay credits what the recording counted (secrets, crypts, the bonuses) on the tick it rose.
     */
    @Volatile var teamFromRecording = false

    /**
     * A replay's drops are the recording's: what a chest, a kill or a cleared room gives is a draw on Hypixel whose mechanism is
     * UNMEASURED, so a replay draws none ([recordedBlessing], [recordedItem] and [recordedEssence] do the recorded ones on their tick).
     */
    @Volatile var dropsFromRecording = false

    /** A blessing the recording shows: [finder] null for a nameless pickup, [obtained] when the "has obtained" line came with it, [timed] with the run clock. */
    fun recordedBlessing(run: Run, finder: UUID?, b: Blessing, level: Int, timed: Boolean, obtained: Boolean) {
        val rd = data?.takeIf { it.run === run } ?: return
        val t = run.t
        val f = who(rd, finder)
        if (obtained && finder != null) Services.channels.chat(Blessings.obtained(f.label, b))
        for (p in humans(rd)) {
            val lines = ArrayList<String>()
            if (finder == null) { lines += Blessings.pickedUp(b); lines += Blessings.announceOwn(b, level, t) }
            else lines += if (p.uuid == finder) Blessings.announceYouFound(b, level, if (timed) t else null) else Blessings.announceOther(f.name, b, level, f.colour, if (timed) t else null)
            lines += Blessings.grantLines(b, level, SecretsSettings.blessingBoost)
            for (l in lines) Services.channels.chat(l, Audience.Only(p.uuid))
            run.state.secrets.blessings.merge(b.name, level, Int::plus)
            run.bus.post(BlessingGranted(t, b.name, level, p.uuid))
        }
    }

    /** An item the recording shows someone obtained (Superboom TNT, a Revive Stone). */
    fun recordedItem(run: Run, finder: UUID?, item: LootItem, count: Int) {
        val rd = data?.takeIf { it.run === run } ?: return
        giveItem(rd, who(rd, finder), item, count)
    }

    /** A chest's essence the recording shows. */
    fun recordedEssence(run: Run, finder: UUID?, type: String, amount: Int) {
        val rd = data?.takeIf { it.run === run } ?: return
        val f = who(rd, finder)
        val drop = Drop.Essence(type, amount)
        tellEach(rd, f.uuid, SecretLines.chestEssenceSelf(drop), SecretLines.chestEssenceOther(f.colour, f.name, drop))
        Services.channels.actionBarSegment("essence", "§d+${drop.amount} ${drop.type} Essence", ActionBarModel.REPLACES_DEFENSE, ESSENCE_SEGMENT_TICKS, Audience.Everyone)
        for (h in humans(rd)) orbPing(h)
    }

    /** One standing cell of each tomb group of [room] not yet blown (world coordinates), for crediting a teammate's crypt. */
    fun standingCrypts(run: Run, room: Int): List<com.engineerclient.p3sim.pack.Pos> {
        val rd = d(run) ?: return emptyList()
        return rd.groups.ofRoom(room, GroupKind.CRYPT).filter { !rd.groups.isDestroyed(it) }.mapNotNull { it.cells.firstOrNull() }
    }

    private fun tickBonusMobs(rd: RunData) {
        if (teamFromRecording) return
        val led = rd.run.state.secrets
        val t = rd.run.t
        rd.mimicAt?.let { if (t >= it && !led.mimicKilled) bonus(rd, BonusKind.MIMIC, null) }
        rd.princeAt?.let { if (t >= it && !led.princeKilled) bonus(rd, BonusKind.PRINCE, null, rd.princeLoud) }
    }

    override fun onRunEnd(run: Run, reason: com.engineerclient.p3sim.run.EndReason) {
        FloorDrops.clear(); WallDebris.clear()
        data?.takeIf { it.run === run }?.let { discardEntities(it) }
    }

    // ---- ticking ------------------------------------------------------------------------------------------------------------------

    override fun onTick(run: Run) {
        val rd = d(run) ?: return
        if (!run.stage.inDungeon) return
        val now = run.serverTick
        if (now % 20 == 0) { applyFixes(rd); sendOwedLids(rd) }
        if (now - rd.lastSpawnPass >= SPAWN_PERIOD) { rd.lastSpawnPass = now; spawnPass(rd) }
        tickBonusMobs(rd)
        FloorDrops.tick(run); WallDebris.tick()
        tickItems(rd)
        tickBats(rd)
        tickWalls(rd, now)
        tickBotBlasts(rd, now)
        tickChests(rd, now)
        tickSkulls(rd, now)
        tickWallTicks(rd, now)
        for (job in rd.later.takeDue(now)) job()
    }

    /** The timer of the measured proximity spawn: a repeating 20-tick pass over the party. */
    const val SPAWN_PERIOD = 20

    private fun humans(rd: RunData): List<ServerPlayer> = Sim.players().filter { p -> rd.run.state.players[p.uuid]?.let { !it.isBot && !it.ghost } ?: false }

    /**
     * Brings items, bats and skulls into the world when a party member is near: by the distance sideways (not the room), the way Hypixel's
     * timer does. Chests are not here: they exist from the start of the run.
     */
    private fun spawnPass(rd: RunData) {
        val near = humans(rd)
        if (near.isEmpty()) return
        fun within(x: Int, z: Int, range: Double): Boolean {
            val r2 = range * range
            return near.any { p -> val dx = x + 0.5 - p.x; val dz = z + 0.5 - p.z; dx * dx + dz * dz <= r2 }
        }
        for (s in rd.plan.all) {
            val range = when (s.kind) {
                SecretKind.ITEM -> SecretsSettings.itemRange
                SecretKind.BAT -> SecretsSettings.batRange
                SecretKind.ESSENCE -> SecretsSettings.skullRange
                else -> continue
            }
            if (within(s.x, s.z, range)) ensure(rd, s)
        }
        for (g in rd.plan.gates) if (g.key !in rd.gatesTaken && within(g.x, g.z, SecretsSettings.skullRange)) ensureGate(rd, g)
    }

    /** Makes sure a site is in the world: its skull placed, its floor item or bat alive. A found one-shot secret is left alone. */
    private fun ensure(rd: RunData, s: SecretSite) {
        if (!RunBlocks.isLoaded(s.x, s.z)) return
        val found = rd.book.isFound(s)
        when (s.kind) {
            SecretKind.ESSENCE -> if (!found && rd.placed.add(s.key)) SecretWorld.ensureSkull(s, rd.skullTextures[s.kind])
            SecretKind.ITEM -> if (!found && !alive(rd, s)) {
                val item = LootTables.itemSecret(Random(rd.run.config.seed xor s.key.hashCode().toLong()))
                SecretWorld.spawnItem(s, item)?.let { rd.entities[s.key] = it }
            }
            SecretKind.BAT -> if (!found && !alive(rd, s)) SecretWorld.spawnBat(s)?.let { rd.entities[s.key] = it }
            else -> {}
        }
    }

    private fun ensureGate(rd: RunData, g: KeyGate) {
        if (!RunBlocks.isLoaded(g.x, g.z)) return
        if (rd.placed.add(g.key)) SecretWorld.ensureSkull(g.x, g.y, g.z, g.quarterTurns, rd.skullTextures[SecretKind.REDSTONE_KEY])
    }

    private fun alive(rd: RunData, s: SecretSite): Boolean {
        val id = rd.entities[s.key] ?: return false
        val e = SecretWorld.entity(id)
        if (e == null || e.isRemoved || !e.isAlive) { rd.entities.remove(s.key); return false }
        return true
    }

    private fun tickItems(rd: RunData) {
        if (rd.entities.isEmpty()) return
        val pad = SecretsSettings.pickupRange
        val players = humans(rd)
        for ((key, id) in rd.entities.entries.toList()) {
            val s = rd.plan.site(key) ?: continue
            if (s.kind != SecretKind.ITEM) continue
            val e = SecretWorld.entity(id) as? ItemEntity
            if (e == null || e.isRemoved) { rd.entities.remove(key); continue }
            // The pickup box: the player's, widened by [SecretsSettings.pickupRange] sideways and half a block up and down.
            val p = players.firstOrNull { it.boundingBox.inflate(pad, 0.5, pad).intersects(e.boundingBox) } ?: continue
            val at = e.position()
            if (SecretWorld.pickUp(e, p)) {
                rd.entities.remove(key)
                // Hypixel adds a server sound to the client's own pickup sound.
                soundTo(p, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, at.x, at.y, at.z, 0.2f, SecretSounds.pickup(rd.sounds))
                claim(rd, s.room, SecretKind.ITEM, p.uuid, s.x, s.y, s.z)
            }
        }
    }

    private fun tickBats(rd: RunData) {
        if (rd.entities.isEmpty()) return
        for ((key, id) in rd.entities.entries.toList()) {
            val s = rd.plan.site(key) ?: continue
            if (s.kind != SecretKind.BAT) continue
            val e = SecretWorld.entity(id) as? Bat
            if (e == null || e.isRemoved) { rd.entities.remove(key); continue }
            // A secret bat has 100 health and dies to any hit: whatever hurt it (an arrow, a blast), it is dead now.
            if (e.isAlive && e.health >= SecretWorld.BAT_HEALTH) continue
            batDied(rd, s, e, killer(rd, e))
        }
    }

    /** Who killed [e]: the last player to hurt it, else the nearest human. */
    private fun killer(rd: RunData, e: Entity): UUID? {
        ((e as? net.minecraft.world.entity.LivingEntity)?.lastHurtByMob as? ServerPlayer)?.let { return it.uuid }
        return humans(rd).minByOrNull { it.distanceToSqr(e) }?.uuid
    }

    /** A secret bat dies: its death sound (the bat's own voice, 0.95 of the pitch), the body falls and stays 20 ticks, then the secret counts. */
    private fun batDied(rd: RunData, s: SecretSite, e: Bat, by: UUID?) {
        rd.entities.remove(s.key)
        killBatBody(rd, e)
        claim(rd, s.room, SecretKind.BAT, by, s.x, s.y, s.z)
    }

    private fun killBatBody(rd: RunData, e: Bat) {
        Sim.sound(SoundEvents.BAT_DEATH, BAT_VOLUME, SecretSounds.batDeath(rd.sounds), e.position(), SoundSource.NEUTRAL)
        SecretWorld.killBat(e)
    }

    /** Odin's bat secret detection listens for the bat's death sound at exactly this volume (a kill plays only the death sound, as recorded). */
    private const val BAT_VOLUME = 0.1f

    /** The block leaving a wall cell flies off as debris ([WallDebris]). */
    private fun debris(x: Int, y: Int, z: Int) {
        RunBlocks.get(x, y, z)?.let { WallDebris.spawn(x, y, z, it) }
    }

    private fun tickWalls(rd: RunData, now: Int) {
        if (rd.walls.isEmpty()) return
        val it = rd.walls.iterator()
        while (it.hasNext()) {
            val (due, cells) = it.next()
            if (now < due) continue
            it.remove()
            for (c in cells) { debris(c.x, c.y, c.z); setOrFix(rd, c.x, c.y, c.z, Blocks.AIR.defaultBlockState()) }
        }
    }

    // ---- sounds and lines ----------------------------------------------------------------------------------------------------------

    private fun soundTo(p: ServerPlayer, sound: SoundEvent, source: SoundSource, x: Double, y: Double, z: Double, vol: Float, pitch: Float) =
        soundTo(p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source, x, y, z, vol, pitch)

    private fun soundTo(p: ServerPlayer, sound: Holder<SoundEvent>, source: SoundSource, x: Double, y: Double, z: Double, vol: Float, pitch: Float) =
        p.connection.send(ClientboundSoundPacket(sound, source, x, y, z, vol, pitch, p.level().random.nextLong()))

    /** The orb-pickup ping a human hears at their own position. */
    private fun orbPing(p: ServerPlayer, vol: Float = 1f, pitch: Float = SecretSounds.ORB_PITCH) =
        soundTo(p, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, p.x, p.y, p.z, vol, pitch)

    /** The colour of a teammate's name when nothing else is known, and the rank in front of it (the commonest rank). */
    private const val RANK_COLOUR = "§b"
    private const val RANK_BRACKET = "§b[MVP§r§c+§r§b]"

    /** Who found something: a party member of the run, with the name colour and rank their lines show. [player] is set for a human only. */
    private class Who(val uuid: UUID?, val name: String, val colour: String, val bracket: String, val player: ServerPlayer?, val bot: Boolean) {
        /** `[MVP+] name`, the front of a "has obtained" line. */
        val label: String get() = "$bracket $name"
    }

    private fun who(rd: RunData, id: UUID?): Who {
        val rec = id?.let { rd.run.state.players[it] }
        val player = id?.let { u -> Sim.players().firstOrNull { it.uuid == u } }
        val name = rec?.name ?: player?.gameProfile?.name ?: "Someone"
        val colour = (rec?.extras?.get("nameColour") as? String) ?: RANK_COLOUR
        val bracket = (rec?.extras?.get("rankBracket") as? String) ?: RANK_BRACKET
        return Who(id, name, colour, bracket, player.takeIf { rec != null && !rec.isBot }, rec?.isBot == true)
    }

    /** One line to every human: [self] to [finder], [other] to the rest. */
    private fun tellEach(rd: RunData, finder: UUID?, self: String, other: String) {
        for (h in humans(rd)) Services.channels.chat(if (h.uuid == finder) self else other, Audience.Only(h.uuid))
    }

    // ---- counting -----------------------------------------------------------------------------------------------------------------

    /** Counts a secret and applies what that does to the world. */
    private fun claim(rd: RunData, room: Int, kind: SecretKind, by: UUID?, x: Int, y: Int, z: Int): Claim {
        val c = rd.book.claim(room, kind, by, x, y, z)
        if (c is Claim.Credited) {
            c.site?.let { afterFound(rd, it, by) }
            if ((c.site?.kind ?: kind) == SecretKind.BAT) {
                batCounted(rd, by)
                if (rd.miscRandom.nextDouble() < BAT_ANNOUNCE) botSays(rd, by, "Bat Killed!")
            }
            if (c.autoCredited > 0 && rd.book.autoCreditedRooms.contains(room)) {
                org.slf4j.LoggerFactory.getLogger("engineerclient").info("[ads] secrets: room {} counted {} secret(s) the room data does not place", room, c.autoCredited)
            }
        }
        return c
    }

    /** A site was found by whoever: its skull starts going, its floor item is taken, its bat falls, its socket opens. */
    private fun afterFound(rd: RunData, s: SecretSite, by: UUID?) {
        when (s.kind) {
            SecretKind.ESSENCE -> startSkull(rd, s, by)
            SecretKind.REDSTONE_KEY -> if (s.socket != null) openSocket(rd, s, by) else removeLater(rd, s.x, s.y, s.z)
            SecretKind.ITEM -> rd.entities.remove(s.key)?.let { id ->
                val e = SecretWorld.entity(id) as? ItemEntity ?: return@let
                val collector = by?.let { u -> SecretWorld.entity(u) }
                SecretWorld.takeBy(e, collector)
            }
            SecretKind.BAT -> rd.entities.remove(s.key)?.let { id -> (SecretWorld.entity(id) as? Bat)?.takeIf { it.isAlive }?.let { killBatBody(rd, it) } }
            else -> {}
        }
    }

    private fun removeLater(rd: RunData, x: Int, y: Int, z: Int) {
        rd.later.add(rd.run.serverTick + 1, { setOrFix(rd, x, y, z, Blocks.AIR.defaultBlockState()) })
    }

    private fun setOrFix(rd: RunData, x: Int, y: Int, z: Int, state: BlockState) {
        if (RunBlocks.isLoaded(x, z)) { RunBlocks.set(x, y, z, state); rd.fixes.remove(CellGroups.key(x, y, z)) }
        else rd.fixes[CellGroups.key(x, y, z)] = Pos(x, y, z) to state
    }

    private fun applyFixes(rd: RunData) {
        if (rd.fixes.isEmpty()) return
        val it = rd.fixes.values.iterator()
        while (it.hasNext()) {
            val (p, st) = it.next()
            if (!RunBlocks.isLoaded(p.x, p.z)) continue
            RunBlocks.set(p.x, p.y, p.z, st)
            it.remove()
        }
    }

    private fun discardEntities(rd: RunData) {
        rd.entities.values.forEach { SecretWorld.entity(it)?.discard() }
        rd.entities.clear()
        rd.mimicBodies.forEach { SecretWorld.entity(it)?.discard() }
        rd.mimicBodies.clear()
    }

    // ---- SecretService ------------------------------------------------------------------------------------------------------------

    override fun found(run: Run, room: Int, kind: SecretKind, by: UUID?, x: Int, y: Int, z: Int): Boolean {
        val rd = d(run) ?: return false
        val c = claim(rd, room, kind, by, x, y, z)
        if (c !is Claim.Credited) return false
        // A teammate's chest: it opens by itself the way a click would, so the lid, the harp and the loot lines reach the player.
        c.site?.let { s -> if ((s.kind == SecretKind.CHEST || s.kind == SecretKind.LOCKED_CHEST) && who(rd, by).bot) startChest(rd, s, by) }
        return true
    }

    override fun cryptDestroyed(run: Run, room: Int, by: UUID?, x: Int, y: Int, z: Int) {
        val rd = d(run) ?: return
        val g = rd.groups.standingAt(x, y, z)
        if (g != null) destroyGroup(rd, g, by) else rd.book.crypt(room, by)
    }

    override fun bonus(run: Run, kind: BonusKind, by: UUID?) {
        val rd = d(run) ?: return
        bonus(rd, kind, by)
    }

    // ---- teammates' mods in party chat ----------------------------------------------------------------------------------------------
    // ADR, 147 five-player runs, T0 to Maxor: teammates (not the recorder) post "Bat Killed!" 1.29 times a run, "Mimic Killed!" from 0 to 3
    // different teammates (55 / 57 / 29 / 6 runs, about 0.3 of each teammate when it dies), "Prince Killed!" by one teammate in 39 percent
    // of runs (about 0.6 of the killings). UNMEASURED: the delay after the kill (a mod posts at once; here 1 to 15 ticks).
    /** Chance a bot's bat kill is posted. */
    const val BAT_ANNOUNCE = 0.55
    /** Chance each bot posts the Mimic's death. */
    const val MIMIC_ANNOUNCE = 0.3
    /** Chance the Prince's killer posts it. */
    const val PRINCE_ANNOUNCE = 0.6

    /** A bot's mod posting [text] in party chat a moment later; nothing for a human (their own mods post for them) or nobody. */
    private fun botSays(rd: RunData, id: UUID?, text: String) {
        val w = who(rd, id)
        // A replay's teammates said what the recording says they did.
        if (id == null || !w.bot || teamFromRecording) return
        val line = "§9Party §8> ${w.bracket} ${w.name}§f: $text"
        rd.later.add(rd.run.serverTick + 1 + rd.miscRandom.nextInt(15), { Services.channels.chat(line) })
    }

    private fun bots(rd: RunData): List<UUID> = rd.run.state.players.filterValues { it.isBot && !it.ghost }.keys.toList()

    private fun bonus(rd: RunData, kind: BonusKind, by: UUID?, loud: Boolean = true) {
        if (!rd.book.bonus(kind, by)) return
        when (kind) {
            BonusKind.MIMIC -> for (b in bots(rd)) if (rd.miscRandom.nextDouble() < MIMIC_ANNOUNCE) botSays(rd, b, "Mimic Killed!")
            BonusKind.PRINCE -> {
                val killer = by ?: bots(rd).takeIf { it.isNotEmpty() }?.let { it[rd.miscRandom.nextInt(it.size)] }
                if (rd.miscRandom.nextDouble() < PRINCE_ANNOUNCE) botSays(rd, killer, "Prince Killed!")
            }
            else -> {}
        }
        when (kind) {
            BonusKind.PRINCE -> if (loud) {
                // Only the killer gets the line (139 DR runs: 16 lines, none in a run where a teammate posted "Prince Killed!"; the 54
                // runs where a teammate killed it have none). A timed roll has no killer: it shows as yours.
                val line = "§r§eA Prince falls. §r§a+1 Bonus Score"
                if (by == null) Services.channels.chat(line) else for (h in humans(rd)) if (h.uuid == by) Services.channels.chat(line, Audience.Only(h.uuid))
            }
            BonusKind.BAT -> {
                // Only the bat's killer gets the line and the pings (139 DR runs: 52 lines, each on the recorded player's own kill, all with
                // the pings; of 30 runs whose bonus bat a teammate killed, no line, and a ping in one).
                for (h in humans(rd)) if (h.uuid == by) {
                    Services.channels.chat("§r§eA Bat has been slain. §r§a+1 Bonus Score", Audience.Only(h.uuid))
                    // Two orb pings at the killer's position: a spread one at volume 0.5 and the usual one.
                    orbPing(h, 0.5f, SecretSounds.bonusPing(rd.sounds))
                    orbPing(h)
                }
            }
            BonusKind.MIMIC -> {}   // Hypixel says nothing; the party-chat "Mimic Killed!" line is a mod's
        }
    }

    /** One more secret bat counted (by anyone): the run's bonus point is on the Nth of them. */
    private fun batCounted(rd: RunData, by: UUID?) {
        if (rd.bat.count()) bonus(rd, BonusKind.BAT, by)
    }

    override fun breakerRefusal(run: Run, x: Int, y: Int, z: Int): String? {
        val rd = d(run) ?: return null
        // Before T0 the server puts every block back and says nothing (139 DR runs: 5,596 digs before T0, none broke, no line).
        if (run.t0Tick == null || run.t < 0) return ""
        val state = RunBlocks.get(x, y, z)
        // Any chest is a secret block, found or not: the Dungeonbreaker never digs one.
        if (SecretWorld.isChestBlock(state)) return BreakerRules.REFUSAL
        // A skull is not dug either: the server puts the head back and takes the hit for a click (no line, nothing broken).
        if (SecretWorld.isSkullBlock(state)) {
            val room = run.state.roomAt(x + 0.5, z + 0.5)?.index
            if (room != null) {
                val gate = rd.plan.gateAt(room, x, y, z)
                val site = rd.plan.siteAt(room, x, y, z)
                if (gate != null || site?.kind == SecretKind.ESSENCE) {
                    val who = Sim.acting?.takeIf { rd.run.state.players[it.uuid]?.isBot != true } ?: humans(rd).minByOrNull { it.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) }
                    if (who != null) {
                        if (gate != null) pickUpKey(rd, who, gate) else if (site != null && site.key !in rd.skulls) claim(rd, room, SecretKind.ESSENCE, who.uuid, site.x, site.y, site.z)
                    }
                    return ""
                }
            }
        }
        return BreakerRules.refusal(run.plan, rd.groups, { px, py, pz -> uncollectedSecretAt(rd, px, py, pz) }, x, y, z)
    }

    private fun uncollectedSecretAt(rd: RunData, x: Int, y: Int, z: Int): Boolean {
        val room = rd.run.state.roomAt(x + 0.5, z + 0.5)?.index ?: return false
        val s = rd.plan.siteAt(room, x, y, z) ?: return false
        return s.kind == SecretKind.CHEST || s.kind == SecretKind.LOCKED_CHEST
    }

    override fun onSuperboom(run: Run, by: UUID?, x: Int, y: Int, z: Int): Int {
        val rd = d(run) ?: return 0
        var blocks = 0
        for (g in rd.groups.hitBy(x + 0.5, y + 0.5, z + 0.5, SecretsSettings.superboomRadius)) blocks += destroyGroup(rd, g, by, Pos(x, y, z))
        return blocks
    }

    /**
     * Destroys a whole crypt tomb or Superboom wall, counts a crypt for a tomb. Returns the number of blocks removed. As on Hypixel the
     * block nearest [aim] goes at once and the rest of the tomb one tick later.
     */
    private fun destroyGroup(rd: RunData, g: CellGroup, by: UUID?, aim: Pos? = null): Int {
        if (!rd.groups.destroy(g)) return 0
        val air = Blocks.AIR.defaultBlockState()
        val first = (if (aim == null) g.cells.firstOrNull() else g.cells.minByOrNull { sq(it.x - aim.x) + sq(it.y - aim.y) + sq(it.z - aim.z) })
        if (first != null) { debris(first.x, first.y, first.z); setOrFix(rd, first.x, first.y, first.z, air) }
        val rest = g.cells.filter { it !== first }
        if (rest.isNotEmpty()) rd.walls += (rd.run.serverTick + 1) to rest
        if (g.kind == GroupKind.CRYPT) rd.book.crypt(g.room, by)
        return g.cells.size
    }

    private fun sq(v: Int) = v.toLong() * v

    /** Chance (per tomb, when a teammate clears its room) that the team blows it: about 6 of the ~35 tombs of a map get blasted (median 6, p10 3, p90 10). */
    const val CRYPT_BOT_P = 0.17
    /** A teammate's blast comes up to this many ticks after their room check-off. */
    const val CRYPT_BOT_DELAY = 40
    /** Ticks between two chests a teammate's room check-off opens one after the other. */
    const val CHEST_STAGGER = 6

    private fun tickBotBlasts(rd: RunData, now: Int) {
        if (rd.botBlasts.isEmpty()) return
        val it = rd.botBlasts.iterator()
        while (it.hasNext()) {
            val (due, g, by) = it.next()
            if (now < due) continue
            it.remove()
            val c = g.cells.firstOrNull() ?: continue
            if (destroyGroup(rd, g, by, Pos(c.x, c.y, c.z)) > 0)
                Sim.sound(SoundEvents.GENERIC_EXPLODE, 1f, SecretSounds.quantise((0.81 + rd.miscRandom.nextDouble() * 0.174).toFloat()), Vec3(c.x + 0.5, c.y + 0.5, c.z + 0.5))
        }
    }

    /**
     * A teammate cleared [room]: counts all of its remaining secrets for [by] (the bots module calls this when a bot's room check-off
     * comes due). The world is brought in line the way a find would: skulls play their sequence, chests open (one after the other),
     * floor items and bats leave. Returns how many were counted.
     */
    override fun creditRoom(run: Run, room: Int, by: UUID?): Int {
        val rd = d(run) ?: return 0
        val r = run.plan.rooms.getOrNull(room) ?: return 0
        val c = r.tiles.firstOrNull()?.let { com.engineerclient.p3sim.pack.DungeonFrame.tileCentre(it.x) to com.engineerclient.p3sim.pack.DungeonFrame.tileCentre(it.z) } ?: (0 to 0)
        val pending = rd.book.pending(room)
        val n = rd.book.creditRemaining(room, by, c.first, c.second)
        var chestsOpened = 0
        for (s in pending) {
            if (!rd.book.isFound(s)) continue
            afterFound(rd, s, by)
            if (s.kind == SecretKind.BAT) batCounted(rd, by)
            if (s.kind == SecretKind.CHEST || s.kind == SecretKind.LOCKED_CHEST) startChest(rd, s, by, delay = CHEST_STAGGER * chestsOpened++)
        }
        // A teammate blows some of the room's tombs too (the team Crypts count rises without the player), a moment after the check-off.
        if (!teamFromRecording) for (g in rd.groups.ofRoom(room, GroupKind.CRYPT)) {
            if (rd.groups.isDestroyed(g) || rd.botBlasts.any { it.second === g }) continue
            if (rd.miscRandom.nextDouble() < CRYPT_BOT_P) rd.botBlasts += Triple(rd.run.serverTick + rd.miscRandom.nextInt(CRYPT_BOT_DELAY + 1), g, by)
        }
        return n
    }

    /** The secrets of [room] not yet found, for a bot or a tool that wants to go and find them (world coordinates). */
    /** Set by a replay: which counted bat kill of [Run] carries the bonus point (0: none), as the recording had it; null for a drawn one. */
    @Volatile var replayBonusBat: ((Run) -> Int?)? = null

    fun pendingSecrets(run: Run, room: Int): List<SecretSite> = d(run)?.book?.pending(room).orEmpty()

    /** Whether [room] has a bat secret at all (found or not). */
    fun hasBat(run: Run, room: Int): Boolean = d(run)?.book?.hasKind(room, SecretKind.BAT) == true

    /**
     * Kills every secret bat inside [box] (an Implosion, a Mage beam): the damage module calls this next to its mob hits, since a secret
     * bat is a plain bat entity, not a registered mob. Returns how many died.
     */
    fun killBats(run: Run, box: AABB, by: UUID?): Int {
        val rd = d(run) ?: return 0
        var n = 0
        for ((key, id) in rd.entities.entries.toList()) {
            val s = rd.plan.site(key) ?: continue
            if (s.kind != SecretKind.BAT) continue
            val e = SecretWorld.entity(id) as? Bat ?: continue
            if (e.isAlive && box.intersects(e.boundingBox)) { batDied(rd, s, e, by); n++ }
        }
        return n
    }

    // ---- clicks -------------------------------------------------------------------------------------------------------------------

    override fun onUseBlock(run: Run, player: ServerPlayer, hand: InteractionHand, hit: BlockHitResult): InteractionResult? {
        val rd = d(run) ?: return null
        if (rd.run.state.players[player.uuid]?.ghost == true) return null
        val pos = hit.blockPos
        val room = run.state.roomAt(pos.x + 0.5, pos.z + 0.5)?.index ?: return null
        val block = RunBlocks.get(pos.x, pos.y, pos.z)
        rd.plan.gateAt(room, pos.x, pos.y, pos.z)?.let { gate -> if (SecretWorld.isSkullBlock(block)) return pickUpKey(rd, player, gate) }
        val site = rd.plan.siteAt(room, pos.x, pos.y, pos.z)
        if (site != null) return when (site.kind) {
            SecretKind.CHEST, SecretKind.LOCKED_CHEST -> if (SecretWorld.isChestBlock(block)) chestClick(rd, player, site) else null
            SecretKind.ESSENCE -> if (SecretWorld.isSkullBlock(block)) skullClick(rd, player, site) else null
            SecretKind.REDSTONE_KEY -> if (site.socket != null) socketClick(rd, player, site) else if (SecretWorld.isSkullBlock(block)) skullClick(rd, player, site) else null
            else -> null
        }
        rd.plan.bonusAt(room, pos.x, pos.y, pos.z)?.let { if (SecretWorld.isChestBlock(block)) return lootChestClick(rd, player, it) }
        val lever = rd.levers[CellGroups.key(pos.x, pos.y, pos.z)]
        if (lever != null) return leverClick(rd, player, lever)
        return null
    }

    // ---- chests -------------------------------------------------------------------------------------------------------------------

    /** The loot of a chest is drawn from a generator of its own, so a savepoint restore rolls the same chest again. */
    private fun chestRandom(rd: RunData, s: SecretSite): Random = Random(rd.run.config.seed xor (s.key.hashCode().toLong() * -0x61c8864680b583ebL))

    private fun chestClick(rd: RunData, p: ServerPlayer, s: SecretSite): InteractionResult {
        val now = rd.run.serverTick
        if (s.kind == SecretKind.LOCKED_CHEST && s.key !in rd.unlocked) {
            // The refusal answers once; the right-click repeats and gets nothing.
            if (LockedChest.answers(rd.lockedSaid[p.uuid], now)) {
                rd.lockedSaid[p.uuid] = now
                Sim.chat(SecretRules.CHEST_LOCKED)
                Sim.sound(SoundEvents.ENDERMAN_TELEPORT, SecretSounds.DENIED_VOLUME, 0f, p.position(), SoundSource.HOSTILE)
            }
            return InteractionResult.SUCCESS
        }
        val rec = rd.chests[s.key]
        if (rec != null) {
            // The Mimic's chest never opens and says nothing more; a reward chest is silent while its harp rolls; the rest are searched.
            if (SecretRules.repeatClick(rec.trapped, rec.reward, rec.finished) == SecretRules.Repeat.SEARCHED) Sim.chat(SecretRules.CHEST_SEARCHED)
            return InteractionResult.SUCCESS
        }
        val c = claim(rd, s.room, SecretKind.CHEST, p.uuid, s.x, s.y, s.z)
        if (c !is Claim.Credited) { Sim.chat(SecretRules.CHEST_SEARCHED); return InteractionResult.SUCCESS }
        startChest(rd, s, p.uuid)
        return InteractionResult.SUCCESS
    }

    /** A chest that only holds loot (the chests a key reveals when the room's count is already met): it opens like any chest and counts nothing. */
    private fun lootChestClick(rd: RunData, p: ServerPlayer, s: SecretSite): InteractionResult {
        val rec = rd.chests[s.key]
        if (rec == null) { startChest(rd, s, p.uuid); return InteractionResult.SUCCESS }
        if (SecretRules.repeatClick(false, rec.reward, rec.finished) == SecretRules.Repeat.SEARCHED) Sim.chat(SecretRules.CHEST_SEARCHED)
        return InteractionResult.SUCCESS
    }

    /**
     * A chest was opened by [by]: decide what it holds, and start its ceremony [delay] ticks from now (a teammate's several chests come one
     * after the other). The Mimic's trapped chest does not open: it brings a zombie.
     */
    private fun startChest(rd: RunData, s: SecretSite, by: UUID?, delay: Int = 0) {
        if (s.key in rd.chests) return
        val start = rd.run.serverTick + delay
        if (s.key == rd.trapped) {
            rd.chests[s.key] = ChestRec(s, Drop.Nothing, by, start, trapped = true)
            openTrapped(rd, s, by, start)
            return
        }
        rd.chests[s.key] = ChestRec(s, if (dropsFromRecording) Drop.Nothing else LootTables.chest(chestRandom(rd, s)), by, start)
    }

    /** The Mimic: a plain zombie stands at the chest a tick after the click, and is dead a few ticks later; the Mimic's point counts then. */
    private fun openTrapped(rd: RunData, s: SecretSite, by: UUID?, start: Int) {
        val delay = TrappedChest.KILL_DELAY.first + rd.miscRandom.nextInt(TrappedChest.KILL_DELAY.last - TrappedChest.KILL_DELAY.first + 1)
        var body: UUID? = null
        rd.later.add(start + 1, {
            val level = RunBlocks.level
            if (level != null && RunBlocks.isLoaded(s.x, s.z)) {
                EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT)?.let { z ->
                    z.setPos(s.x + 0.5, s.y.toDouble(), s.z + 0.5)
                    z.isNoAi = true
                    z.setPersistenceRequired()
                    Sim.spawn(z)
                    body = z.uuid
                    rd.mimicBodies += z.uuid
                }
            }
        })
        rd.later.add(start + 1 + delay, {
            body?.let { id -> (SecretWorld.entity(id) as? net.minecraft.world.entity.LivingEntity)?.let { z -> RunBlocks.level?.let { l -> if (z.isAlive) z.kill(l) } } }
            bonus(rd, BonusKind.MIMIC, by)
        })
    }

    private fun tickChests(rd: RunData, now: Int) {
        if (rd.chests.isEmpty()) return
        for (rec in rd.chests.values.toList()) {
            if (rec.finished) continue
            val schedule = SecretChestFinale.schedule(rec.reward)
            val elapsed = now - rec.start
            for (i in Beats.due(schedule.map { it.at }, elapsed, rec.done)) chestBeat(rd, rec, schedule[i])
            if (rec.done.size >= schedule.size) rec.finished = true
        }
    }

    private fun chestBeat(rd: RunData, rec: ChestRec, item: SecretChestFinale.Item) {
        val s = rec.site
        val corner = Vec3(s.x.toDouble(), s.y.toDouble(), s.z.toDouble())
        when (item.step) {
            SecretChestFinale.Step.CORNER_OPEN -> Sim.sound(SoundEvents.CHEST_OPEN, 0.5f, SecretSounds.chestOpen(rd.sounds), corner, SoundSource.BLOCKS)
            SecretChestFinale.Step.LID_AND_CENTRE_OPEN -> {
                sendLid(s)
                Sim.sound(SoundEvents.CHEST_OPEN, 0.5f, SecretSounds.chestOpen(rd.sounds), Vec3(s.x + 0.5, s.y + 0.5, s.z + 0.5), SoundSource.BLOCKS)
            }
            SecretChestFinale.Step.HARP -> Sim.sound(SoundEvents.NOTE_BLOCK_HARP, 1f, SecretSounds.HARP[item.note], corner, SoundSource.BLOCKS)
            SecretChestFinale.Step.REVEAL -> applyChestDrop(rd, rec)
        }
    }

    /** The lid goes up for everyone, and stays up (the packet's block is the cherry log Hypixel names; the client ignores it). */
    private fun sendLid(s: SecretSite) {
        if (!RunBlocks.isLoaded(s.x, s.z)) return
        val packet = ClientboundBlockEventPacket(BlockPos(s.x, s.y, s.z), Blocks.CHERRY_LOG, 1, 1)
        for (p in Sim.players()) p.connection.send(packet)
    }

    /** After a restore the chests opened before it are shut on the clients; tell them again once the chest is back in a loaded chunk. */
    private fun sendOwedLids(rd: RunData) {
        if (rd.lidsOwed.isEmpty()) return
        val it = rd.lidsOwed.iterator()
        while (it.hasNext()) {
            val s = rd.plan.site(it.next())
            if (s == null) { it.remove(); continue }
            if (!RunBlocks.isLoaded(s.x, s.z) || !SecretWorld.isChestBlock(RunBlocks.get(s.x, s.y, s.z))) continue
            sendLid(s)
            it.remove()
        }
    }

    // ---- loot ---------------------------------------------------------------------------------------------------------------------

    private fun applyChestDrop(rd: RunData, rec: ChestRec) {
        val f = who(rd, rec.by)
        when (val drop = rec.drop) {
            is Drop.Item -> giveItem(rd, f, drop.item, drop.count)
            is Drop.Essence -> {
                tellEach(rd, f.uuid, SecretLines.chestEssenceSelf(drop), SecretLines.chestEssenceOther(f.colour, f.name, drop))
                // Everyone gets the essence, so everyone's bar shows it, whoever opened the chest.
                Services.channels.actionBarSegment("essence", "§d+${drop.amount} ${drop.type} Essence", ActionBarModel.REPLACES_DEFENSE, ESSENCE_SEGMENT_TICKS, Audience.Everyone)
                for (h in humans(rd)) orbPing(h)
            }
            is Drop.Blessing -> grantBlessing(rd, f, drop.blessing)
            Drop.Nothing -> {}
        }
    }

    /** `[MVP+] name has obtained Superboom TNT x2!` to everyone; the item goes to the finder (a human), a Revive Stone is counted for whoever it is. */
    private fun giveItem(rd: RunData, f: Who, item: LootItem, count: Int) {
        Services.channels.chat(obtainedLine(f.label, item, count))
        if (item == LootItem.REVIVE_STONE) f.uuid?.let { id -> rd.run.state.players[id]?.let { it.extras["reviveStones"] = ((it.extras["reviveStones"] as? Number)?.toInt() ?: 0) + 1 } }
        f.player?.let { p ->
            SecretWorld.give(p, SecretWorld.stack(item, count, rd.run.packs.rooms?.textures.orEmpty()))
            soundTo(p, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, p.x, p.y, p.z, 1f, 1f)
        }
    }

    /** `<who> has obtained Superboom TNT x2!`: the item in its colour, the quantity (when more than one) dark grey. [who] is the rank and name. */
    internal fun obtainedLine(who: String, item: LootItem, count: Int): String = SecretLines.obtained(who, item, count)

    /** A blessing out of a secret chest: no "has obtained" line, no clock, level I, II or V for each viewer; everyone gets the buff. */
    private fun grantBlessing(rd: RunData, finder: Who, b: Blessing) {
        val t = rd.run.t
        val boost = SecretsSettings.blessingBoost
        for (p in humans(rd)) {
            val mine = p.uuid == finder.uuid
            val level = Blessings.chestLevel(b, rd.miscRandom)
            val lines = ArrayList<String>()
            lines += if (mine) Blessings.announceYouFound(b, level, null) else Blessings.announceOther(finder.name, b, level, finder.colour, null)
            lines += Blessings.grantLines(b, level, boost)
            for (l in lines) Services.channels.chat(l, Audience.Only(p.uuid))
            rd.run.state.secrets.blessings.merge(b.name, level, Int::plus)
            rd.run.bus.post(BlessingGranted(t, b.name, level, p.uuid))
        }
    }

    // ---- room-clear drops -----------------------------------------------------------------------------------------------------------

    /** The party member a room clear is credited to: whoever dealt the last required kill, else a member picked at random. */
    private fun clearer(rd: RunData, room: Int): UUID? {
        rd.lastKiller[room]?.let { return it }
        val members = rd.run.state.players.keys.toList()
        return if (members.isEmpty()) null else members[rd.dropRandom.nextInt(members.size)]
    }

    /** A cleared room may drop Superboom TNT or a Revive Stone for whoever cleared it (UNMEASURED mechanism, measured rate; see [LootTables.roomClear]). */
    private fun roomClearDrop(rd: RunData, room: Int) {
        if (dropsFromRecording) return
        val drop = LootTables.roomClear(rd.dropRandom) as? Drop.Item ?: return
        giveItem(rd, who(rd, clearer(rd, room)), drop.item, drop.count)
    }

    /**
     * A starred mob died to [killer]: maybe an item for them, or a blessing for the party (see [LootTables.mobKill]). It lies where the mob fell
     * ([FloorDrops]) until it is picked up; the line and the item come then. Without the mob's position it is given at once.
     */
    private fun mobKillDrop(rd: RunData, killer: UUID, mob: UUID) {
        if (dropsFromRecording) return
        val drop = LootTables.mobKill(rd.dropRandom) ?: return
        val rec = Services.mobs.mob(mob)
        val at = rec?.let { doubleArrayOf(it.x, it.y, it.z) }?.takeIf { !it[0].isNaN() && !(it[0] == 0.0 && it[2] == 0.0) }
        val run = rd.run
        when (drop) {
            is Drop.Item -> {
                if (at == null) { giveItem(rd, who(rd, killer), drop.item, drop.count); return }
                val superboom = drop.item == LootItem.SUPERBOOM
                val name = drop.item.colour + drop.item.displayName + (if (drop.count > 1) " §r§8x${drop.count}" else "")
                val model = if (superboom) com.engineerclient.p3sim.SimItems.SUPERBOOM.copy() else FloorDrops.head("revive_stone", run)
                FloorDrops.drop(run, name, model, superboom, at[0], at[1], at[2], 0, killer, rd.dropRandom) { by -> giveItem(rd, who(rd, by ?: killer), drop.item, drop.count) }
            }
            is Drop.Blessing -> {
                if (at == null) { grantKillBlessing(rd, who(rd, killer), drop.blessing); return }
                FloorDrops.drop(run, "§dBlessing of " + drop.blessing.displayName, FloorDrops.head(FloorDrops.BLESSING_TEXTURE, run), false, at[0], at[1], at[2], 1, killer, rd.dropRandom) { by -> grantKillBlessing(rd, who(rd, by ?: killer), drop.blessing) }
            }
            else -> {}
        }
    }

    /** A dropped blessing: always V with the run clock; nameless `was picked up` or the finder's `has obtained`; everyone gets the buff. */
    private fun grantKillBlessing(rd: RunData, finder: Who, b: Blessing) {
        val t = rd.run.t
        val level = Blessings.OWN_LEVEL
        val boost = SecretsSettings.blessingBoost
        val nameless = rd.miscRandom.nextDouble() < Blessings.KILL_PICKED_UP_SHARE
        if (!nameless) Services.channels.chat(Blessings.obtained(finder.label, b))
        for (p in humans(rd)) {
            val lines = ArrayList<String>()
            if (nameless) { lines += Blessings.pickedUp(b); lines += Blessings.announceOwn(b, level, t) }
            else lines += if (p.uuid == finder.uuid) Blessings.announceYouFound(b, level, t) else Blessings.announceOther(finder.name, b, level, finder.colour, t)
            lines += Blessings.grantLines(b, level, boost)
            for (l in lines) Services.channels.chat(l, Audience.Only(p.uuid))
            rd.run.state.secrets.blessings.merge(b.name, level, Int::plus)
            rd.run.bus.post(BlessingGranted(t, b.name, level, p.uuid))
        }
    }

    // ---- skulls and keys ----------------------------------------------------------------------------------------------------------

    private fun skullClick(rd: RunData, p: ServerPlayer, s: SecretSite): InteractionResult {
        // The first click counts and starts the sequence; later clicks, while it runs or after, do nothing.
        if (s.key in rd.skulls || rd.book.isFound(s)) return InteractionResult.SUCCESS
        claim(rd, s.room, s.kind, p.uuid, s.x, s.y, s.z)
        return InteractionResult.SUCCESS
    }

    /** The secret was counted at the click; the pling, the five notes and the reward follow over the next 20 ticks. */
    private fun startSkull(rd: RunData, s: SecretSite, by: UUID?) {
        if (s.key in rd.skulls) return
        rd.skulls[s.key] = SkullRec(s, by, rd.run.serverTick)
    }

    private fun tickSkulls(rd: RunData, now: Int) {
        if (rd.skulls.isEmpty()) return
        for ((key, rec) in rd.skulls.entries.toList()) {
            val f = who(rd, rec.by)
            val schedule = SkullFinale.schedule(withPling = true)
            for (i in Beats.due(schedule.map { it.at }, now - rec.start, rec.done)) skullBeat(rd, rec, f, schedule[i])
            if (rec.done.size >= schedule.size) rd.skulls.remove(key)
        }
    }

    private fun skullBeat(rd: RunData, rec: SkullRec, f: Who, item: SkullFinale.Item) {
        val s = rec.site
        when (item.step) {
            SkullFinale.Step.PLING -> f.player?.let { soundTo(it, SoundEvents.NOTE_BLOCK_PLING, SoundSource.BLOCKS, it.x, it.y, it.z, SecretSounds.SKULL_PLING_VOLUME, SecretSounds.SKULL_PLING_PITCH) }
            SkullFinale.Step.HARP -> Sim.sound(SoundEvents.NOTE_BLOCK_HARP, 1f, SecretSounds.HARP[item.note], Vec3(s.x.toDouble(), s.y.toDouble(), s.z.toDouble()), SoundSource.BLOCKS)
            SkullFinale.Step.REWARD -> {
                setOrFix(rd, s.x, s.y, s.z, Blocks.AIR.defaultBlockState())
                tellEach(rd, f.uuid, SecretLines.WITHER_ESSENCE_SELF, SecretLines.witherEssenceOther(f.colour, f.name))
                for (h in humans(rd)) repeat(SkullFinale.orbPings(h.uuid == f.uuid)) { orbPing(h) }
                // Only the finder's bar shows the +1 (the essence is everyone's, but the number is the finder's).
                f.uuid?.let { id -> if (f.player != null) Services.channels.actionBarSegment("essence", "§d+1 Wither Essence", ActionBarModel.REPLACES_DEFENSE, ESSENCE_SEGMENT_TICKS, Audience.Only(id)) }
            }
        }
    }

    /** How long an essence segment stays on the action bar: three bars at the 10-tick cadence (median 3 bars recorded, about 25 to 30 ticks). */
    const val ESSENCE_SEGMENT_TICKS = 30

    /**
     * The Redstone Key skull is only picked up: the finder is told, hears the item pickup, and the skull goes a tick later. No item and
     * nothing counts; the key is the party's, held by state until it is used on its socket (UNMEASURED who may use it).
     */
    private fun pickUpKey(rd: RunData, p: ServerPlayer, gate: KeyGate): InteractionResult {
        if (!rd.gatesTaken.add(gate.key)) return InteractionResult.SUCCESS
        rd.keyRooms += gate.room
        Services.channels.chat(SecretRules.KEY_FOUND, Audience.Only(p.uuid))
        soundTo(p, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, p.x, p.y, p.z, 1f, 1f)
        removeLater(rd, gate.x, gate.y, gate.z)
        return InteractionResult.SUCCESS
    }

    /** A click on the socket of a key room: with the key it opens the room's door; without it nothing happens (UNMEASURED). */
    private fun socketClick(rd: RunData, p: ServerPlayer, s: SecretSite): InteractionResult? {
        if (s.room !in rd.keyRooms || rd.book.isFound(s)) return null
        claim(rd, s.room, SecretKind.REDSTONE_KEY, p.uuid, s.x, s.y, s.z)
        return InteractionResult.SUCCESS
    }

    /**
     * The key is used on [s]'s socket: a piston sound, a head on the socket, the redstone door gone, the room's hidden chests there and
     * its locked chests open. [by] hears it and is told in gray italics; a teammate's use is heard only by whoever stands near.
     */
    private fun openSocket(rd: RunData, s: SecretSite, by: UUID?, immediate: Boolean = false) {
        val socket = s.socket ?: return
        rd.keyRooms += s.room
        for (g in rd.plan.room(s.room)?.gates.orEmpty()) { rd.gatesTaken += g.key; setOrFix(rd, g.x, g.y, g.z, Blocks.AIR.defaultBlockState()) }
        if (!immediate) {
            val f = who(rd, by)
            val centre = Vec3(s.x + 0.5, s.y + 0.5, s.z + 0.5)
            Sim.sound(SoundEvents.PISTON_CONTRACT, 1f, 1f, f.player?.position() ?: centre, SoundSource.BLOCKS)
            f.player?.let { Services.channels.chat(SecretRules.SOCKET_USED, Audience.Only(it.uuid)) }
        }
        val air = Blocks.AIR.defaultBlockState()
        if (RunBlocks.isLoaded(socket.head.pos.x, socket.head.pos.z)) SecretWorld.placeHead(socket.head, rd.skullTextures[SecretKind.REDSTONE_KEY])
        else rd.later.add(rd.run.serverTick + 20, { SecretWorld.placeHead(socket.head, rd.skullTextures[SecretKind.REDSTONE_KEY]) })
        for (c in socket.opens) setOrFix(rd, c.x, c.y, c.z, air)
        for (c in socket.chests) setOrFix(rd, c.site.x, c.site.y, c.site.z, SecretWorld.chestState(c.site))
        for (c in rd.plan.room(s.room)?.sites.orEmpty()) if (c.kind == SecretKind.LOCKED_CHEST) rd.unlocked += c.key
    }

    // ---- levers -------------------------------------------------------------------------------------------------------------------

    private fun leverClick(rd: RunData, p: ServerPlayer, l: LeverInfo): InteractionResult {
        val key = CellGroups.key(l.pos.x, l.pos.y, l.pos.z)
        val used = rd.leverUsed[key] != null
        for (line in SecretRules.leverLines(used, p.isShiftKeyDown)) Sim.chat(line)
        Sim.sound(SoundEvents.ANVIL_BREAK, 1f, SecretSounds.ANVIL_BREAK_PITCH, p.position(), SoundSource.BLOCKS)
        if (used) {
            SecretWorld.resend(p, l.pos.x, l.pos.y, l.pos.z)
            return InteractionResult.SUCCESS
        }
        rd.leverUsed[key] = p.uuid
        if (rd.sounds.nextDouble() < LeverWall.LEVER_CLICK_SHARE)
            Sim.sound(SoundEvents.LEVER_CLICK, SecretSounds.LEVER_CLICK_VOLUME, SecretSounds.LEVER_CLICK_PITCH, Vec3(l.pos.x + 0.5, l.pos.y + 0.5, l.pos.z + 0.5), SoundSource.BLOCKS)
        openLever(rd, l, immediate = false)
        SecretWorld.resend(p, l.pos.x, l.pos.y, l.pos.z)
        return InteractionResult.SUCCESS
    }

    /** Powers the lever, unlocks its chests and starts opening its wall (barrier first, air [SecretsSettings.leverWallTicks] later, nine ticking clicks meanwhile). */
    private fun openLever(rd: RunData, l: LeverInfo, immediate: Boolean) {
        SecretWorld.powerLever(l.pos.x, l.pos.y, l.pos.z)
        rd.unlocked.addAll(l.chests)
        if (l.opens.isEmpty()) return
        if (immediate) { for (c in l.opens) setOrFix(rd, c.x, c.y, c.z, Blocks.AIR.defaultBlockState()); return }
        val barrier = Blocks.BARRIER.defaultBlockState()
        for (c in l.opens) setOrFix(rd, c.x, c.y, c.z, barrier)
        rd.walls += (rd.run.serverTick + SecretsSettings.leverWallTicks) to l.opens
        if (LeverWall.ticks(rd.run.plan.rooms.getOrNull(l.room)?.name.orEmpty())) {
            val n = l.opens.size
            val centre = Vec3(l.opens.sumOf { it.x } / n + 0.5, l.opens.sumOf { it.y } / n + 0.5, l.opens.sumOf { it.z } / n + 0.5)
            rd.wallTicks += WallTicks(centre, rd.run.serverTick)
        }
    }

    private fun tickWallTicks(rd: RunData, now: Int) {
        if (rd.wallTicks.isEmpty()) return
        val ats = LeverWall.TICKS.toList()
        val it = rd.wallTicks.iterator()
        while (it.hasNext()) {
            val w = it.next()
            for (i in Beats.due(ats, now - w.start, w.done)) Sim.sound(SoundEvents.WOODEN_PRESSURE_PLATE_CLICK_ON, 2f, SecretSounds.WALL_TICK_PITCH, w.at, SoundSource.MASTER)
            if (w.done.size >= ats.size) it.remove()
        }
    }

    override fun onAttackEntity(run: Run, player: ServerPlayer, hand: InteractionHand, entity: Entity): InteractionResult? {
        val rd = d(run) ?: return null
        if (entity !is Bat) return null
        val key = rd.entities.entries.firstOrNull { it.value == entity.uuid }?.key ?: return null
        val s = rd.plan.site(key) ?: return null
        batDied(rd, s, entity, player.uuid)
        return InteractionResult.SUCCESS
    }

    // ---- savepoints ---------------------------------------------------------------------------------------------------------------

    override fun snapshot(run: Run): JsonObject? {
        val rd = d(run) ?: return null
        val now = run.serverTick
        return rd.book.snapshot().also { o ->
            o.add("destroyed", JsonArray().also { a -> rd.groups.destroyedIds.forEach(a::add) })
            o.add("levers", JsonObject().also { j -> rd.leverUsed.forEach { (k, v) -> j.addProperty(k.toString(), v.toString()) } })
            o.add("unlocked", JsonArray().also { a -> rd.unlocked.forEach(a::add) })
            o.addProperty("bonusBatIndex", rd.bat.index)
            o.addProperty("batClaims", rd.bat.counted)
            o.add("keyRooms", JsonArray().also { a -> rd.keyRooms.forEach(a::add) })
            o.add("gatesTaken", JsonArray().also { a -> rd.gatesTaken.forEach(a::add) })
            // Ceremonies under way, as ticks since they began: the harp keeps rolling from where it was.
            o.add("chests", JsonArray().also { a -> rd.chests.values.filter { !it.finished }.forEach { r -> a.add(JsonObject().also { j -> j.addProperty("key", r.site.key); j.addProperty("by", r.by?.toString()); j.addProperty("elapsed", now - r.start) }) } })
            o.add("skulls", JsonArray().also { a -> rd.skulls.values.forEach { r -> a.add(JsonObject().also { j -> j.addProperty("key", r.site.key); j.addProperty("by", r.by?.toString()); j.addProperty("elapsed", now - r.start) }) } })
        }
    }

    /**
     * The region resync has put every block back to the start state and the engine has removed every entity; put the chests, opened
     * walls, destroyed crypts, used levers, used keys and collected skulls back, and let the proximity pass bring the rest in again.
     */
    override fun restore(run: Run, data: JsonObject) {
        val rd = d(run) ?: return
        val now = run.serverTick
        rd.book.restore(data)
        rd.placed.clear(); rd.entities.clear(); rd.walls.clear(); rd.fixes.clear(); rd.leverUsed.clear()
        rd.chests.clear(); rd.skulls.clear(); rd.wallTicks.clear(); rd.lidsOwed.clear(); rd.later.clear(); rd.lockedSaid.clear()
        rd.mimicBodies.forEach { SecretWorld.entity(it)?.discard() }; rd.mimicBodies.clear()
        val destroyed = data.getAsJsonArray("destroyed")?.map { it.asString }.orEmpty()
        rd.groups.setDestroyed(destroyed)
        val air = Blocks.AIR.defaultBlockState()
        for (id in destroyed) rd.groups.group(id)?.cells?.forEach { setOrFix(rd, it.x, it.y, it.z, air) }
        rd.unlocked.clear()
        data.getAsJsonArray("unlocked")?.forEach { rd.unlocked += it.asString }
        // Chests without a lever were never locked.
        Levers.build(run.plan, rd.plan).second.let { rd.unlocked.addAll(it) }
        data.getAsJsonObject("levers")?.entrySet()?.forEach { (k, v) ->
            val key = k.toLongOrNull() ?: return@forEach
            val used = runCatching { UUID.fromString(v.asString) }.getOrDefault(NIL)
            rd.leverUsed[key] = used
            rd.levers[key]?.let { openLever(rd, it, immediate = true) }
        }
        rd.keyRooms.clear(); rd.gatesTaken.clear()
        data.getAsJsonArray("keyRooms")?.forEach { rd.keyRooms += it.asInt }
        data.getAsJsonArray("gatesTaken")?.forEach { rd.gatesTaken += it.asString }
        for (g in rd.plan.gates) if (g.key in rd.gatesTaken) setOrFix(rd, g.x, g.y, g.z, air)
        // The chests are back (they stood from the start), the opened ones with their lids to be raised again; the sockets used are used.
        placeChests(rd)
        for (s in rd.plan.all) {
            if (!rd.book.isFound(s)) continue
            when (s.kind) {
                SecretKind.ESSENCE -> if (data.getAsJsonArray("skulls")?.any { it.asJsonObject.get("key")?.asString == s.key } != true) setOrFix(rd, s.x, s.y, s.z, air)
                SecretKind.REDSTONE_KEY -> if (s.socket != null) openSocket(rd, s, null, immediate = true) else setOrFix(rd, s.x, s.y, s.z, air)
                SecretKind.CHEST, SecretKind.LOCKED_CHEST -> if (s.key != rd.trapped) rd.lidsOwed += s.key   // the Mimic's chest never opened
                else -> {}
            }
        }
        // Ceremonies that were under way carry on from where they were.
        data.getAsJsonArray("chests")?.forEach { e ->
            val j = e.asJsonObject
            val s = rd.plan.site(j.get("key").asString) ?: return@forEach
            val elapsed = j.get("elapsed")?.asInt ?: 0
            val by = j.get("by")?.takeIf { !it.isJsonNull }?.asString?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val rec = ChestRec(s, if (dropsFromRecording) Drop.Nothing else LootTables.chest(chestRandom(rd, s)), by, now - elapsed)
            rec.done += Beats.playedBy(SecretChestFinale.schedule(rec.reward).map { it.at }, elapsed)
            rd.chests[s.key] = rec
            if (elapsed >= 1 && s.key != rd.trapped) rd.lidsOwed += s.key
        }
        data.getAsJsonArray("skulls")?.forEach { e ->
            val j = e.asJsonObject
            val s = rd.plan.site(j.get("key").asString) ?: return@forEach
            val elapsed = j.get("elapsed")?.asInt ?: 0
            val by = j.get("by")?.takeIf { !it.isJsonNull }?.asString?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val rec = SkullRec(s, by, now - elapsed)
            rec.done += Beats.playedBy(SkullFinale.schedule(withPling = true).map { it.at }, elapsed)
            rd.skulls[s.key] = rec
        }
        rd.bat.index = data.get("bonusBatIndex")?.asInt ?: rd.bat.index
        rd.bat.counted = data.get("batClaims")?.asInt ?: 0
        rd.lastSpawnPass = -SPAWN_PERIOD
    }

    // ---- events -------------------------------------------------------------------------------------------------------------------

    override fun onEvent(run: Run, event: RunEvent) {
        val rd = d(run) ?: return
        if (event is MobKilled) {
            val room = event.mob.room
            if (room != null && event.mob.required) rd.lastKiller[room] = event.by
            if (room != null && event.mob.starred && event.by != null && run.plan.rooms.getOrNull(room)?.type != RoomType.BLOOD) mobKillDrop(rd, event.by, event.mob.id)
            return
        }
        if (event is RoomCleared) {
            val room = run.plan.rooms.getOrNull(event.room) ?: return
            roomClearDrop(rd, event.room)
            if (room.type == RoomType.PUZZLE || room.type == RoomType.BLOOD) return   // a puzzle's secret is its reward chest
            val centre = room.tiles.firstOrNull()?.let { com.engineerclient.p3sim.pack.DungeonFrame.tileCentre(it.x) to com.engineerclient.p3sim.pack.DungeonFrame.tileCentre(it.z) } ?: return
            rd.book.roomCleared(event.room, null, centre.first, 70, centre.second)
        }
    }

    // ---- /ads dev -----------------------------------------------------------------------------------------------------------------

    override fun devCommands(): List<AdsCommand> = SecretsDev.commands()

    /** For the dev commands: the run data's pieces without exposing the private class. */
    internal fun devInfo(run: Run): DevInfo? = d(run)?.let { DevInfo(it.plan, it.groups, it.book, it.unlocked, it.entities, it.leverUsed.keys) }

    internal class DevInfo(val plan: SecretPlan, val groups: CellGroups, val book: SecretBook, val unlocked: Set<String>, val entities: Map<String, UUID>, val leversUsed: Set<Long>)

    internal fun devGroupDestroy(run: Run, g: CellGroup, by: UUID?): Int = d(run)?.let { destroyGroup(it, g, by) } ?: 0
}
