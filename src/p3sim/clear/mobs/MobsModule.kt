package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimServer
import com.engineerclient.p3sim.pack.mobs.MobArchetype
import com.engineerclient.p3sim.pack.mobs.MobPack
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.DamageSource
import com.engineerclient.p3sim.run.EndReason
import com.engineerclient.p3sim.run.MobDamaged
import com.engineerclient.p3sim.run.MobKilled
import com.engineerclient.p3sim.run.MobSpawned
import com.engineerclient.p3sim.run.PlayerJoined
import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunBlocks
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.api.Audience
import com.engineerclient.p3sim.run.api.MobRecord
import com.engineerclient.p3sim.run.api.MobService
import com.engineerclient.p3sim.run.api.MobSpec
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.engineerclient.ads.settings.BooleanSetting
import com.engineerclient.ads.settings.NumberSetting
import net.minecraft.world.entity.Entity
import java.util.UUID
import kotlin.random.Random

/**
 * The mob factory and registry of the clear.
 *
 * What a player sees: every room's mobs are in place before they get there (they appear once a player is within 48 blocks,
 * after the run has started); a starred mob is a body with a floating name tag showing its HP; Crypt Dreadlords, Souleaters, Zombie Commanders,
 * the minibosses and the Shadow Assassin look like real players to the client (so Odin's and Blade Addons' star ESP draws them);
 * Fels sleep invisible until someone steps within 9 blocks; the last required mob of a room drops its key; some mobs stand still, about
 * 40 percent walk toward a player who comes close; only about half of the miniboss rooms hold a miniboss.
 *
 * How: at run start every room's table is rolled into [PlannedMob]s (ids and numbers: the records other modules see exist at
 * once, far rooms included), and the entities of a mob are built when its chunk is loaded and a player is near, and built again
 * if the chunk was unloaded in between. HP lives in the record; the damage module changes it through [setHp], which updates the tag,
 * posts the events and on 0 HP removes body and tag on the same tick and drops the key.
 *
 * FILLED BY: the `mobs` work item (owns `src/p3sim/clear/mobs/` and below, `src/p3sim/pack/mobs/` and below).
 */
object MobsModule : AdsModule, MobService {
    override val id = "mobs"
    override val owner = "mobs"

    private val log = org.slf4j.LoggerFactory.getLogger("engineerclient")

    /** A body gone within this many ticks of being built counts as vanishing, not unloading. */
    private const val QUICK_VANISH_TICKS = 20
    private const val RETRY_AFTER_VANISH_TICKS = 200

    /** Ticks before a mob whose build failed is tried again; doubles with each failure up to 16 times. */
    private const val BUILD_BACKOFF_TICKS = 40

    // ---- settings (null until registerSettings; every read has the default) -------------------------------------------

    private var sEnabled: BooleanSetting? = null
    private var sFakePlayers: BooleanSetting? = null
    private var sReplay: BooleanSetting? = null
    private var sHealthData: BooleanSetting? = null
    private var sAttacks: BooleanSetting? = null
    private var sSpawnDistance: NumberSetting<Int>? = null
    private var sWakeRadius: NumberSetting<Double>? = null
    private var sPartial: NumberSetting<Double>? = null
    private var sMinibossChance: NumberSetting<Int>? = null
    private var sMoverShare: NumberSetting<Int>? = null
    private var sStroll: BooleanSetting? = null
    private var sAggro: NumberSetting<Double>? = null
    private var sLose: NumberSetting<Double>? = null
    private var sMeleeReach: NumberSetting<Double>? = null
    private var sMeleeCadence: NumberSetting<Int>? = null
    private var sRangedRange: NumberSetting<Double>? = null
    private var sRangedCadence: NumberSetting<Int>? = null
    private var sStaticReach: NumberSetting<Double>? = null
    private var sZombieSpeed: NumberSetting<Double>? = null
    private var sSkeletonSpeed: NumberSetting<Double>? = null
    private var sWitherSpeed: NumberSetting<Double>? = null
    private var sTeleportEvery: NumberSetting<Int>? = null
    private var sOtherSpeed: NumberSetting<Double>? = null
    private var sPatrolSpeed: NumberSetting<Double>? = null

    override fun registerSettings(host: com.engineerclient.ads.settings.Settings) {
        sEnabled = host.registerSetting(BooleanSetting("Mobs Spawn", true, desc = "The clear has its mobs. Off: rooms stay empty (they count as cleared at once)."))
        sFakePlayers = host.registerSetting(BooleanSetting("Mobs Fake Players", true, desc = "Crypt Dreadlords, Souleaters, Zombie Commanders, the minibosses and the Shadow Assassin reach your game as real players (UUID version 2), as on Hypixel, so Odin's and Blade Addons' star ESP draw them. Off: they are zombies."))
        sReplay = host.registerSetting(BooleanSetting("Mobs Replay Rooms", true, desc = "A room gets the mobs of one recorded visit (clusters and counts stay together). Off: every spawn cell rolls on its own with the chance the recordings gave it."))
        sHealthData = host.registerSetting(BooleanSetting("Mobs Health Data", true, desc = "Writes a mob's HP into its body's health data too (Hypixel does), so recorders that read the entity data agree with the name tag."))
        sAttacks = host.registerSetting(BooleanSetting("Mobs Attack", true, desc = "Mobs that reach you hit you (through the damage ledger). Off: they walk and shoot at nothing."))
        sSpawnDistance = host.registerSetting(NumberSetting("Mobs Spawn Distance", 48, 16..160, 4, unit = " blocks", desc = "A room's mobs appear once a player is this close (recorded Hypixel bodies first appear at a median of 41 blocks, 85 percent within 3 chunks)."))
        sMinibossChance = host.registerSetting(NumberSetting("Mobs Miniboss Chance", 65, 0..100, 5, unit = "%", desc = "How often an ordinary room that can hold a miniboss really does (Dungeon Recorder: 65 percent). Yellow miniboss rooms always hold theirs."))
        sWakeRadius = host.registerSetting(NumberSetting("Mobs Wake Radius", 9.0, 1.0..16.0, 0.5, unit = " blocks", desc = "A sleeping Fels wakes when a player's body comes this close, in 3D (measured about 9, always awake at 8; only a body wakes it, not arrows or Implosion)."))
        sPartial = host.registerSetting(NumberSetting("Mobs Partial Weight", 0.25, 0.0..1.0, 0.05, desc = "How often a room replays a recording that only caught part of the room (fewer than half the usual stars), relative to a full one."))
        sMoverShare = host.registerSetting(NumberSetting("Mobs Chaser Share", 0, 0..100, 5, unit = "%", desc = "Share of ordinary mobs that chase a player they see until they reach it. 0 is Hypixel: the Dungeon Recorder shows no chases, only short walks (Mobs Stroll)."))
        sStroll = host.registerSetting(BooleanSetting("Mobs Stroll", true, desc = "Mobs that do not chase take short walks now and then, more often the nearer you are, a third of them at you (Dungeon Recorder: median 6 blocks; 7 walks a minute within 6 blocks, 1.4 beyond 32). Off: they stand still."))
        sAggro = host.registerSetting(NumberSetting("Mobs Follow Range", 35.0, 2.0..60.0, 1.0, unit = " blocks", desc = "A walking mob takes as its target a player it can see within this (1.8's follow range; recorded first steps reach p90 32 to 43 blocks)."))
        sLose = host.registerSetting(NumberSetting("Mobs Give Up Range", 35.0, 4.0..80.0, 1.0, unit = " blocks", desc = "A chasing mob gives up when its target is farther than this, or out of sight for 3 seconds (1.8)."))
        sMeleeReach = host.registerSetting(NumberSetting("Mobs Hit Reach", 1.43, 1.0..6.0, 0.05, unit = " blocks", desc = "How close a mob must be to hit (1.8's reach for a zombie-sized body; not in the recordings)."))
        sMeleeCadence = host.registerSetting(NumberSetting("Mobs Melee Cadence", 20, 5..100, 1, unit = " ticks", desc = "Ticks between one mob's hits (1.8's attack timer)."))
        sRangedRange = host.registerSetting(NumberSetting("Mobs Shot Range", 15.0, 4.0..40.0, 1.0, unit = " blocks", desc = "Skeletons, the Sniper and the Withermancer shoot what they see from at most this far (1.8: 15; recorded shots p90 13.5 blocks)."))
        sRangedCadence = host.registerSetting(NumberSetting("Mobs Shot Gap", 60, 10..200, 1, unit = " ticks", desc = "Ticks between one shooter's shots at the edge of its range; 20 at point blank, in between by distance (1.8)."))
        sStaticReach = host.registerSetting(NumberSetting("Mobs Standing Reach", 1.43, 0.0..6.0, 0.05, unit = " blocks", desc = "A mob that stands still still hits a player this close. 0: standing mobs never hit."))
        sZombieSpeed = host.registerSetting(NumberSetting("Mobs Zombie Speed", 0.28, 0.05..1.5, 0.01, unit = " b/tick", desc = "Chase speed of zombie-bodied mobs the speed table has no number for (most archetypes have their own measured one)."))
        sSkeletonSpeed = host.registerSetting(NumberSetting("Mobs Skeleton Speed", 0.137, 0.05..1.5, 0.01, unit = " b/tick", desc = "Chase speed of skeleton-bodied mobs (same source)."))
        sWitherSpeed = host.registerSetting(NumberSetting("Mobs Wither Skeleton Speed", 0.27, 0.05..1.5, 0.01, unit = " b/tick", desc = "Chase speed of the Withermancer (same source; the spread is wide)."))
        sOtherSpeed = host.registerSetting(NumberSetting("Mobs Walker Speed", 0.32, 0.05..1.5, 0.01, unit = " b/tick", desc = "Chase speed of Fels and of any body without its own number (Fels: measured 0.32)."))
        sPatrolSpeed = host.registerSetting(NumberSetting("Mobs Patrol Speed", 0.25, 0.05..1.0, 0.01, unit = " b/tick", desc = "How fast a miniboss walks its patrol loop before it sees anyone (recorded adventurers: median 0.25 to 0.29)."))
        sTeleportEvery = host.registerSetting(NumberSetting("Mobs Assassin Teleport", 0, 0..400, 5, unit = " ticks", desc = "The Shadow Assassin appears behind its target this often. 0: it walks, as recorded assassins do (2 of 65 ever jumped)."))
    }

    private fun enabled() = sEnabled?.value ?: true
    internal fun fakePlayersOn() = sFakePlayers?.value ?: true
    internal fun healthDataOn() = sHealthData?.value ?: true
    private fun attacks() = sAttacks?.value ?: true
    private fun spawnDistance() = (sSpawnDistance?.value ?: 48).toDouble()
    private fun wakeRadius(arch: MobArchetype) = sWakeRadius?.value ?: 9.0

    private fun params() = AiParams(
        moverShare = (sMoverShare?.value ?: 0) / 100.0, strolls = sStroll?.value ?: true,
        aggroRadius = sAggro?.value ?: 35.0, loseRadius = sLose?.value ?: 35.0,
        meleeReach = sMeleeReach?.value ?: 1.43, meleeCadence = sMeleeCadence?.value ?: 20,
        rangedRange = sRangedRange?.value ?: 15.0, rangedCadence = sRangedCadence?.value ?: 60,
        staticReach = sStaticReach?.value ?: 1.43,
        zombieSpeed = sZombieSpeed?.value ?: 0.28, skeletonSpeed = sSkeletonSpeed?.value ?: 0.137, witherSkeletonSpeed = sWitherSpeed?.value ?: 0.27,
        otherSpeed = sOtherSpeed?.value ?: 0.32, patrolSpeed = sPatrolSpeed?.value ?: 0.25,
        assassinTeleportEvery = sTeleportEvery?.value ?: 0,
    )

    /** The world behind the module; tests replace it. */
    internal var stage: MobStage = RealStage
    /** The services the module calls; tests replace them. */
    internal var doors: () -> com.engineerclient.p3sim.run.api.DoorService = { Services.doors }
    internal var damage: () -> com.engineerclient.p3sim.run.api.DamageService = { Services.damage }
    internal var chat: (String) -> Unit = { Services.channels.chat(it) }
    /** Puts an action-bar segment for one player (the Combat XP line); tests replace it. */
    internal var bar: (player: UUID, text: String) -> Unit = { p, text ->
        Services.channels.actionBarSegment("combat-xp", text, com.engineerclient.p3sim.clear.hud.ActionBarModel.REPLACES_DEFENSE, COMBAT_BAR_TTL, Audience.Only(p))
    }

    /** UNMEASURED per mob type: a kill gives 600 to 780 Combat XP (recorded batches: +608, +655.4, +671.8 ...). */
    const val COMBAT_XP_MIN = 600.0
    const val COMBAT_XP_MAX = 780.0
    /** UNMEASURED: the Combat XP total every player starts a run on (it only has to look like a long-time player's). */
    const val COMBAT_XP_START = 25_000_000.0
    /** How long the Combat XP line replaces the defense segment, in ticks. */
    const val COMBAT_BAR_TTL = 45

    /** The Combat XP segment as Hypixel writes it: `+655.4 Combat (25,000,655/0)`; a whole gain drops the decimal. */
    fun combatBarText(gain: Double, total: Double): String {
        val g = java.math.BigDecimal(gain).setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString().removeSuffix(".0")
        return "§3+$g Combat (" + String.format(java.util.Locale.ROOT, "%,d", Math.round(total)) + "/0)"
    }
    private val combatTotals = HashMap<UUID, Double>()

    // ---- state of the current run -------------------------------------------------------------------------------------

    private val entries = LinkedHashMap<UUID, MobEntry>()
    /** The mobs' idle talk (real world only). */
    private val chatter = MobChatter()
    private var chatterRng: Random = Random(0)
    /** Rolls who looks for a target this tick (1.8: one chance in ten a tick). */
    private var aiRng: Random = Random(0)
    private val byRoom = HashMap<Int, MutableList<MobEntry>>()
    private val byEntityId = HashMap<Int, MobEntry>()
    private val rolled = HashSet<Int>()

    /** A replay's own mobs for a room (the recording's, as they were), in place of the rolled ones; null for a room it has none for. */
    @Volatile var roomMobs: ((Run, Int) -> List<PlannedMob>?)? = null
    /** A replay's HP factor for a room behind a WITHER door (the recording's), in place of the run's drawn one; null for a room it has none for. */
    /** A replay: where the recording has the mob with this ADS id this tick (x, y, z), or null to let ADS move it. */
    @Volatile var replayPose: ((java.util.UUID) -> DoubleArray?)? = null

    @Volatile var doorScale: ((Run, Int) -> Double?)? = null
    /**
     * The run's drawn HP factor. A room's mobs spawn at their base HP and take a factor the tick after the WITHER door into their room
     * opens (DR, 139 runs: every scaled mob's HP jumped then and only then; a room reached without a WITHER door never scales, the Blood
     * door scales nothing). Hypixel's factor is a multiple of 0.05 that grows through the run, near 1 + 0.05 per opened room but not
     * exactly that (UNKNOWN), so outside a replay the drawn one stands in.
     */
    private var scale = 1.0
    /** Rooms a WITHER door has scaled, and those to scale on the next tick. */
    private val scaledRooms = HashSet<Int>()
    private val pendingScale = ArrayList<Int>()
    private var noPackSaid = false
    /** MobSpawned events of the start-up roll wait for the first tick: the other modules have not all begun yet while the run starts. */
    private var deferSpawnEvents = false
    private val pendingSpawned = ArrayList<com.engineerclient.p3sim.run.MobRef>()
    /** Mobs made by `/ads dev disguise`, outside any run. */
    private val spikes = ArrayList<MobEntry>()

    private fun reset() {
        combatTotals.clear(); entries.clear(); byRoom.clear(); byEntityId.clear(); rolled.clear(); scale = 1.0; scaledRooms.clear(); pendingScale.clear(); noPackSaid = false; pendingSpawned.clear(); deferSpawnEvents = false
    }

    // ---- the run lifecycle ---------------------------------------------------------------------------------------------

    override fun onRunStart(run: Run) {
        FairyRoom.clear(); RoomDecor.clear()
        discardAll()
        reset()
        val pack = run.packs.mobs
        if (pack == null) {
            noPackSaid = true
            chat("§8[§6ADS§8] §7The mob tables (${com.engineerclient.p3sim.pack.PackFiles.MOBS}) could not be loaded, so this dungeon has no mobs. /ads dev packs says why.")
            return
        }
        if (!enabled()) return
        val floor = run.config.floor
        scale = run.config.mobHpScale ?: pack.hpScaleFor(floor)?.let { MobRolls.drawHpScale(it, run.subRandom("mobs.scale")) } ?: 1.0
        chatterRng = run.subRandom("mobs.chatter")
        aiRng = run.subRandom("mobs.ai")
        deferSpawnEvents = true
        for (room in run.plan.rooms) if (room.type != RoomType.ENTRANCE && room.type != RoomType.FAIRY && room.type != RoomType.BLOOD) spawnRoom(run, room.index)
        deferSpawnEvents = false
    }

    override fun onStage(run: Run, from: RunStage?, to: RunStage) {
        // The clear's mobs are gone when the party jumps to the boss.
        if (to == RunStage.BOSS_ENTRY || to == RunStage.BOSS || to == RunStage.END) clearAll(run)
    }

    override fun onRunEnd(run: Run, reason: EndReason) {
        clearAll(run)
    }

    override fun onEvent(run: Run, event: com.engineerclient.p3sim.run.RunEvent) {
        // A game that joins later needs the fake players' info entries before it tracks their bodies.
        if (event is PlayerJoined) stage.announceTo(event.player, entries.values)
        if (event is com.engineerclient.p3sim.run.DoorOpened && event.kind == com.engineerclient.p3sim.pack.layouts.DoorKind.WITHER)
            run.plan.doors.getOrNull(event.door)?.let { pendingScale += behind(run, it.roomA, it.roomB) }
    }

    /** Of the two rooms a door joins, the one farther from the Entrance (counted in doorways). */
    private fun behind(run: Run, a: Int, b: Int): Int {
        val start = run.plan.entrance?.index ?: return b
        val dist = HashMap<Int, Int>()
        val q = ArrayDeque<Int>(); q += start; dist[start] = 0
        while (q.isNotEmpty()) {
            val r = q.removeFirst()
            for (d in run.plan.doors) if (d.kind.connected) {
                val o = if (d.roomA == r) d.roomB else if (d.roomB == r) d.roomA else continue
                if (o !in dist) { dist[o] = dist.getValue(r) + 1; q += o }
            }
        }
        return if ((dist[a] ?: Int.MAX_VALUE) > (dist[b] ?: Int.MAX_VALUE)) a else b
    }

    /** Puts a room's mobs (HP and max HP) on the WITHER door's factor. */
    private fun scaleRoom(run: Run, room: Int) {
        if (!scaledRooms.add(room)) return
        val f = doorScale?.invoke(run, room) ?: scale
        if (f == 1.0) return
        for (e in byRoom[room].orEmpty()) {
            val r = e.record
            if (!r.alive) continue
            r.maxHp = Math.round(r.maxHp * f).toDouble(); r.hp = Math.round(r.hp * f).toDouble()
            if (e.live) stage.refresh(e)
        }
    }

    override fun onTick(run: Run) {
        if (pendingScale.isNotEmpty()) { val q = pendingScale.toList(); pendingScale.clear(); for (r in q) scaleRoom(run, r) }
        if (pendingSpawned.isNotEmpty()) { val q = pendingSpawned.toList(); pendingSpawned.clear(); for (m in q) run.bus.post(MobSpawned(run.t, m)) }
        // Nothing stands in the world before the run starts: the first bodies reach a real client at the start tick or up to 30 ticks after it.
        if (run.t < 0) return
        stage.tick()
        if (stage === RealStage && run.stage.inDungeon && run.stage != RunStage.BOSS_ENTRY && run.stage != RunStage.BOSS) com.engineerclient.EngineerClient.safely("ads fairies") { FairyRoom.tick(run); RoomDecor.tick(run) }
        if (entries.isEmpty() || !stage.available() || run.stage == RunStage.BOSS_ENTRY || run.stage == RunStage.BOSS || run.stage == RunStage.END) return
        val pack = run.packs.mobs ?: return
        val players = run.state.alivePlayers()
        val t = run.serverTick
        val params = params()
        val maxDist = spawnDistance()
        // A snapshot: a hit on a player posts events whose handlers may spawn or remove mobs (a camp), which must not break this walk.
        for (e in entries.values.toList()) {
            if (!e.alive) continue
            // A body that is gone (its chunk unloaded, or the void took it) is rebuilt from the record when it can be.
            if (e.live && stage.isGone(e)) {
                e.record.entityId?.let { byEntityId.remove(it) }; stage.release(e); e.record.entityId = null
                // A body that disappears within a second of being built is not a chunk unloading: something removes it (peaceful difficulty?). Wait before trying again.
                if (t - e.builtAt < QUICK_VANISH_TICKS) {
                    e.retryAt = t + RETRY_AFTER_VANISH_TICKS
                    if (++e.vanishes == 3) log.warn("[ads] the ${e.arch.id} body vanished right after being built 3 times; the world may be on peaceful difficulty")
                }
            }
            if (!e.live) {
                if (t >= e.retryAt && (t + e.plan.id.hashCode()) % 5 == 0 && near(players, e, maxDist) && stage.isLoaded(Math.floor(e.x).toInt(), Math.floor(e.z).toInt())) {
                    if (stage.materialise(e)) { e.builtAt = t; e.buildFailures = 0; e.record.entityId?.let { byEntityId[it] = e } }
                    else {
                        // A build that throws would otherwise be tried (and logged) every five ticks for as long as a player stands near: back off, and after three
                        // failures try an ordinary body (a disguise is the likeliest thing to fail).
                        e.buildFailures++
                        e.retryAt = t + (BUILD_BACKOFF_TICKS shl minOf(e.buildFailures - 1, 4))
                        if (e.buildFailures == 3 && !e.plainBody) { e.plainBody = true; log.warn("[ads] the ${e.arch.id} body failed to build 3 times; trying a plain body") }
                    }
                }
                continue
            }
            stage.rescue(e)
            val pos = stage.position(e) ?: continue
            // A replay: a mob the recording sees is where the recording has it, and does nothing of its own.
            val rec = replayPose?.invoke(e.record.id)
            if (rec != null) {
                if (e.asleep) { e.asleep = false; stage.wake(e) }
                stage.place(e, rec[0], rec[1], rec[2])
                stage.position(e)?.let { e.record.x = it[0]; e.record.y = it[1]; e.record.z = it[2] }
                continue
            }
            if (e.asleep) {
                val r = wakeRadius(e.arch)
                if (players.any { dist3(it, pos) <= r }) { e.asleep = false; stage.wake(e) } else continue
            }
            runAi(run, e, pos, pack, players, params)
            stage.position(e)?.let { e.record.x = it[0]; e.record.y = it[1]; e.record.z = it[2] }
        }
        if (stage === RealStage) com.engineerclient.EngineerClient.safely("ads mob chatter") { chatter.tick(t, entries.values, chatterRng) }
    }

    private fun near(players: List<PlayerRecord>, e: MobEntry, d: Double): Boolean {
        val d2 = d * d
        return players.any { val dx = it.x - e.x; val dz = it.z - e.z; dx * dx + dz * dz <= d2 }
    }

    private fun dist3(p: PlayerRecord, pos: DoubleArray): Double { val dx = p.x - pos[0]; val dy = p.y - pos[1]; val dz = p.z - pos[2]; return Math.sqrt(dx * dx + dy * dy + dz * dz) }

    // ---- AI v0 -------------------------------------------------------------------------------------------------------

    /**
     * One mob's AI tick. Its target is the nearest living player on its level, a bot as much as a person: real mobs go for whichever teammate is
     * near (ADR: zombies move 48% of the ticks they are seen, skeletons 67%), and a mob that ignored the bots would stand still in every room they
     * clear. A bot it reaches is swung at but not hurt here: the bots module hurts bots on the measured schedule of a fighting teammate.
     */
    private fun runAi(run: Run, e: MobEntry, pos: DoubleArray, pack: MobPack, targets: List<PlayerRecord>, p: AiParams) {
        val kind = MobPlanner.aiKind(e.arch, p.assassinTeleportEvery > 0)
        // The nearest living player that is on the mob's level (a mob cannot reach a player several blocks above or below it).
        var best: PlayerRecord? = null
        var bestD = Double.MAX_VALUE
        for (h in targets) {
            val dx = h.x - pos[0]; val dz = h.z - pos[2]
            var d = Math.sqrt(dx * dx + dz * dz)
            if (Math.abs(h.y - pos[1]) > 3.0) d += 1000.0
            if (d < bestD) { bestD = d; best = h }
        }
        // Sight is looked at only when it can matter: a target within the follow range (or the shooting range of a mob that never walks).
        val reach = if (e.plan.mover) maxOf(p.aggroRadius, p.loseRadius) else maxOf(p.rangedRange, p.staticReach, if (p.strolls) 20.0 else 0.0)
        val seen = best != null && bestD <= reach && stage.canSee(e, best.x, best.y + 1.62, best.z)
        val target = best?.let { AiTarget(it.x, it.z, bestD, seen) }
        val speed = MobAi.speedFor(e.arch.id, e.arch.entity, e.modifier?.id == "Speedy", p)
        val action = MobAi.step(kind, e.plan.mover, pos[0], pos[2], speed, target, e.ai, e.plan.loop, p, search = aiRng.nextDouble() < p.searchChance, rng = aiRng)
        if (e.hidden && e.ai.chasing) { e.hidden = false; stage.reveal(e) }
        stage.act(e, action, best?.y ?: Double.NaN, best?.x ?: Double.NaN, best?.z ?: Double.NaN)
        if (!attacks() || best == null || best.isBot) return
        when (action) {
            AiAction.Melee -> damage().meleeHit(run, e.record.id, best.uuid)
            AiAction.Shoot -> {
                val table = pack.playerDamage?.projectile?.let { it[if (e.arch.entity == "minecraft:wither_skeleton") "wither_skull" else "arrow"] ?: it["arrow"] }
                if (table != null) damage().hurtPlayer(run, best.uuid, MobRolls.sampleHit(table, run.subRandom("mobs.shot." + run.serverTick + e.plan.id)), DamageSource.PROJECTILE, e.record.id, false)
            }
            else -> {}
        }
    }

    // ---- MobService ----------------------------------------------------------------------------------------------------

    override fun spawnRoom(run: Run, roomIndex: Int): List<MobRecord> {
        if (roomIndex !in rolled) {
            rolled += roomIndex
            val pack = run.packs.mobs
            val room = run.plan.rooms.getOrNull(roomIndex)
            val placement = room?.placement
            if (pack != null && room != null && placement != null && enabled()) {
                val alias = run.packs.rooms?.let { r -> { n: String -> r.familyOf(n) } }
                val params = PlanParams(run.config.floor, 1.0,if (sReplay?.value == false) SpawnMode.CELLS else SpawnMode.LAYOUTS, sPartial?.value ?: MobRolls.PARTIAL_LAYOUT_WEIGHT, (sMoverShare?.value ?: 0) / 100.0, minibossChance = (sMinibossChance?.value ?: 65) / 100.0)
                val plans = roomMobs?.invoke(run, roomIndex) ?: MobPlanner.planRoom(pack, room.name, alias, placement, params, run.subRandom("mobs.room.$roomIndex"))
                for (pm in plans) register(run, pack, pm, roomIndex)
            }
        }
        return byRoom[roomIndex]?.map { it.record }.orEmpty()
    }

    /** An archetype by index: the pack's, one of the blood camp's ([BloodLooks]) or a levelled room mob ([LevelLooks]). */
    private fun archOf(pack: MobPack, index: Int): MobArchetype? = pack.archetype(index) ?: BloodLooks.archetype(index) ?: LevelLooks.archetype(index)

    private fun register(run: Run, pack: MobPack, pm: PlannedMob, roomIndex: Int?): MobEntry? {
        val arch = archOf(pack, pm.arch) ?: return null
        val rec = MobRecord(pm.id, arch.id, arch.name, roomIndex, pm.star, pm.required, arch.cls == "miniboss", arch.disguise && fakePlayersOn())
        rec.hp = pm.maxHp; rec.maxHp = pm.maxHp; rec.x = pm.x; rec.y = pm.y; rec.z = pm.z
        val e = MobEntry(rec, pm, arch, pack.modifier(pm.modifier).takeIf { pm.modifier.isNotEmpty() })
        entries[pm.id] = e
        if (roomIndex != null) {
            byRoom.getOrPut(roomIndex) { ArrayList() } += e
            if (pm.required) run.state.room(roomIndex)?.let { it.mobsRequired++; it.mobsRequiredAlive++ }
        }
        if (deferSpawnEvents) pendingSpawned += rec.ref() else run.bus.post(MobSpawned(run.t, rec.ref()))
        return e
    }

    private val standInSaid = HashSet<String>()
    /** The archetype that stands in for a spawn request the mob pack has no kind for. */
    const val STAND_IN_KIND = "zombie_soldier"

    override fun spawn(run: Run, spec: MobSpec): MobRecord? {
        val pack = run.packs.mobs ?: return null
        // A kind the pack does not have (a plug-in's camp mobs) stands in as the plain soldier (or the hint in extras), so a camp always has mobs to kill.
        val arch = (if (spec.extras["camp"] == "blood") BloodLooks.forName(spec.extras["name"]) else null)
            ?: pack.archetypeById(spec.kind)
            ?: LevelLooks.byId(spec.kind)
            ?: spec.extras["standIn"]?.let { pack.archetypeById(it) }
            ?: pack.archetypeById(STAND_IN_KIND)?.also { if (standInSaid.add(spec.kind)) log.info("[ads] the mob pack has no '${spec.kind}'; spawning a $STAND_IN_KIND in its place") }
            ?: run { log.warn("[ads] the mob pack has no '${spec.kind}', so it cannot be spawned"); return null }
        val word = spec.extras["modifier"]?.takeIf { it.isNotEmpty() }
        val mod = word?.let { pack.modifier(it) }
        // A room's mob has its room's factor: the run's once a WITHER door scaled the room, none before (the Blood camp's keep the run's).
        val roomScale = spec.roomIndex?.let { r -> run.plan.rooms.getOrNull(r)?.takeIf { it.type != RoomType.BLOOD } }?.let { if (it.index in scaledRooms) doorScale?.invoke(run, it.index) ?: scale else 1.0 } ?: scale
        val hp = spec.hp ?: MobRolls.spawnHp(arch.hpFor(run.config.floor) ?: return null, roomScale, mod)
        val rng = run.subRandom("mobs.spec." + entries.size + spec.kind)
        val id = if (arch.disguise) MobRolls.uuidV2(rng) else MobRolls.uuidV4(rng)
        // A camp mob keeps its word even when the pack has no row for it (Stealth, Boomer): its tag is written from the word.
        val pm = PlannedMob(id, arch.index, spec.starred, spec.required, if (BloodLooks.isBlood(arch)) word.orEmpty() else mod?.id.orEmpty(), hp, spec.x, spec.y, spec.z, rng.nextFloat() * 360f - 180f, arch.movement != "static", null)
        return register(run, pack, pm, spec.roomIndex)?.record
    }

    override fun mob(id: UUID): MobRecord? = entries[id]?.record
    override fun inRoom(roomIndex: Int): List<MobRecord> = byRoom[roomIndex]?.map { it.record }.orEmpty()
    override fun all(): Collection<MobRecord> = entries.values.map { it.record }
    override fun requiredAlive(roomIndex: Int): Int = byRoom[roomIndex]?.count { it.alive && it.record.required } ?: 0

    /** The record of the mob whose body (or tag) has this entity id, or null. For the damage module's melee, bow and beam hits. */
    override fun recordOfEntity(entityId: Int): MobRecord? = byEntityId[entityId]?.record

    /** Whether the mob is a sleeping Fels (it cannot be hurt until it wakes). */
    override fun isAsleep(id: UUID): Boolean = entries[id]?.asleep == true

    override fun setHp(run: Run, id: UUID, newHp: Double, by: UUID?): MobRecord? {
        val e = entries[id] ?: return null
        val r = e.record
        if (!r.alive) return r
        // Only a body can wake a Fels, so nothing hurts it while it sleeps.
        // A teammate bot wakes it (bots clear rooms nobody is near, where no body exists to do it); anything else cannot hurt it.
        if (e.asleep && newHp < r.hp) {
            if (by != null && run.state.players[by]?.isBot == true) { e.asleep = false; if (e.live) stage.wake(e) } else return r
        }
        val old = r.hp
        if (newHp <= 0.0) { kill(run, e, by); return r }
        r.hp = minOf(newHp, r.maxHp.coerceAtLeast(newHp))
        if (e.live) stage.refresh(e)
        if (r.hp < old) {
            if (e.live) stage.hurtFlash(e)
            run.bus.post(MobDamaged(run.t, r.ref(), old - r.hp, r.hp, by))
        }
        return r
    }

    private fun kill(run: Run, e: MobEntry, by: UUID?) {
        val r = e.record
        r.hp = 0.0; r.alive = false
        val pos = if (e.live) stage.position(e) else null
        val x = pos?.get(0) ?: e.x; val z = pos?.get(2) ?: e.z
        if (e.live) stage.die(e)
        stage.retract(e)
        r.entityId?.let { byEntityId.remove(it) }
        r.entityId = null
        val room = r.roomIndex
        var lastRequired = false
        if (r.required && room != null) {
            run.state.room(room)?.let { it.mobsRequiredAlive = maxOf(0, it.mobsRequiredAlive - 1); lastRequired = it.mobsRequiredAlive == 0 }
        }
        run.bus.post(MobKilled(run.t, r.ref(), by))
        if (by != null && run.state.players[by]?.isBot == false && e.arch.cls != "special") {
            val gain = Math.round(run.subRandom("mobs.xp." + e.plan.id).nextDouble(COMBAT_XP_MIN, COMBAT_XP_MAX) * 10.0) / 10.0
            val total = (combatTotals[by] ?: COMBAT_XP_START) + gain
            combatTotals[by] = total
            bar(by, combatBarText(gain, total))
        }
        // The last required mob of a room drops the room's key, if the key plan gives it one.
        // A replay drops its keys where and when the recording shows them.
        if (lastRequired && room != null && !com.engineerclient.p3sim.clear.state.StateController.keysFromRecording && doors().keyForRoom(run, room) != null) doors().dropKey(run, room, x, z)
    }

    override fun remove(run: Run, id: UUID) {
        val e = entries.remove(id) ?: return
        val r = e.record
        stage.release(e); stage.retract(e)
        r.entityId?.let { byEntityId.remove(it) }
        val room = r.roomIndex
        if (room != null) {
            byRoom[room]?.remove(e)
            if (r.required) run.state.room(room)?.let { if (r.alive) it.mobsRequiredAlive = maxOf(0, it.mobsRequiredAlive - 1); it.mobsRequired = maxOf(0, it.mobsRequired - 1) }
        }
        r.alive = false
    }

    override fun clearAll(run: Run) {
        FairyRoom.clear(); RoomDecor.clear()
        discardAll()
        reset()
    }

    private fun discardAll() {
        chatter.clear()
        stage.flush()
        for (e in entries.values) { stage.release(e); stage.retract(e) }
        for (e in spikes) { stage.release(e); stage.retract(e) }
        spikes.clear()
    }

    // ---- savepoints ----------------------------------------------------------------------------------------------------

    override fun snapshot(run: Run): JsonObject {
        val o = JsonObject()
        o.addProperty("scale", scale)
        val a = JsonArray()
        for (e in entries.values) {
            val p = e.plan
            val m = JsonObject()
            m.addProperty("id", p.id.toString()); m.addProperty("arch", p.arch); m.addProperty("star", p.star); m.addProperty("req", p.required)
            m.addProperty("mod", p.modifier); m.addProperty("max", p.maxHp); m.addProperty("rmax", e.record.maxHp); m.addProperty("hp", e.record.hp); m.addProperty("alive", e.record.alive)
            m.addProperty("room", e.record.roomIndex ?: -1)
            m.addProperty("px", p.x); m.addProperty("py", p.y); m.addProperty("pz", p.z); m.addProperty("yaw", p.yaw)
            m.addProperty("x", e.x); m.addProperty("y", e.y); m.addProperty("z", e.z)
            m.addProperty("mover", p.mover); m.addProperty("asleep", e.asleep); m.addProperty("hidden", e.hidden)
            p.loop?.let { l -> m.add("loop", JsonArray().also { arr -> for (pt in l) arr.add(JsonArray().also { it.add(pt[0]); it.add(pt[1]) }) }) }
            a.add(m)
        }
        o.add("mobs", a)
        o.add("rolled", JsonArray().also { arr -> rolled.sorted().forEach(arr::add) })
        o.add("scaled", JsonArray().also { arr -> scaledRooms.sorted().forEach(arr::add) })
        return o
    }

    override fun restore(run: Run, data: JsonObject) {
        discardAll()
        reset()
        val pack = run.packs.mobs ?: return
        scale = data.get("scale")?.asDouble ?: 1.0
        data.getAsJsonArray("rolled")?.forEach { rolled += it.asInt }
        data.getAsJsonArray("scaled")?.forEach { scaledRooms += it.asInt }
        for (el in data.getAsJsonArray("mobs") ?: JsonArray()) {
            val m = el.asJsonObject
            val arch = archOf(pack, m.get("arch").asInt) ?: continue
            val loop = m.getAsJsonArray("loop")?.map { p -> doubleArrayOf(p.asJsonArray[0].asDouble, p.asJsonArray[1].asDouble) }
            val pm = PlannedMob(UUID.fromString(m.get("id").asString), arch.index, m.get("star").asBoolean, m.get("req").asBoolean, m.get("mod").asString, m.get("max").asDouble,
                m.get("px").asDouble, m.get("py").asDouble, m.get("pz").asDouble, m.get("yaw").asFloat, m.get("mover").asBoolean, loop)
            val room = m.get("room").asInt.takeIf { it >= 0 }
            val rec = MobRecord(pm.id, arch.id, arch.name, room, pm.star, pm.required, arch.cls == "miniboss", arch.disguise && fakePlayersOn())
            rec.maxHp = m.get("rmax")?.asDouble ?: pm.maxHp; rec.hp = m.get("hp").asDouble; rec.alive = m.get("alive").asBoolean
            rec.x = m.get("x").asDouble; rec.y = m.get("y").asDouble; rec.z = m.get("z").asDouble
            val e = MobEntry(rec, pm, arch, pack.modifier(pm.modifier).takeIf { pm.modifier.isNotEmpty() })
            e.x = rec.x; e.y = rec.y; e.z = rec.z
            e.asleep = m.get("asleep").asBoolean; e.hidden = m.get("hidden").asBoolean
            entries[pm.id] = e
            if (room != null) byRoom.getOrPut(room) { ArrayList() } += e
        }
        // The room counters are this module's to write: bring them in line with the restored records.
        for (r in run.state.rooms) { r.mobsRequired = 0; r.mobsRequiredAlive = 0 }
        for (e in entries.values) {
            val room = e.record.roomIndex ?: continue
            if (!e.record.required) continue
            run.state.room(room)?.let { it.mobsRequired++; if (e.record.alive) it.mobsRequiredAlive++ }
        }
    }

    // ---- the spike ---------------------------------------------------------------------------------------------------

    override fun onInit() { DisguiseSpike.register() }

    override fun devCommands(): List<AdsCommand> = listOf(DisguiseSpike.command(this), mobsCommand, aiCommand)

    /** `/ads dev mobs`: what the module holds, room by room, to check a run against. */
    private val mobsCommand = AdsCommand("mobs", "/ads dev mobs", "Lists the mobs of the run room by room: required alive of total, bodies built, sleepers.", server = { ctx ->
        val run = ctx.run
        if (run == null) ctx.reply("No run is on.") else for (line in summaryLines(run)) ctx.reply(line)
    })

    /** `/ads dev mobai`: the AI state of the mobs nearest you, to see why one does or does not chase. */
    private val aiCommand = AdsCommand("mobai", "/ads dev mobai", "The AI state of the ten mobs nearest you: kind, mover, chasing, distance, sight.", server = { ctx ->
        val run = ctx.run
        val me = run?.state?.alivePlayers()?.firstOrNull { !it.isBot }
        if (run == null || me == null) ctx.reply("No run or no player.") else {
            ctx.reply("t=${run.t} stage=${run.stage} available=${stage.available()} humans=${run.state.alivePlayers().count { !it.isBot }}")
            val p = params()
            for (e in entries.values.filter { it.alive }.sortedBy { Math.hypot(it.x - me.x, it.z - me.z) }.take(10)) {
                val pos = stage.position(e)
                val d = Math.hypot(e.x - me.x, e.z - me.z)
                ctx.reply("${e.arch.id} kind=${MobPlanner.aiKind(e.arch, p.assassinTeleportEvery > 0)} mover=${e.plan.mover} live=${e.live} asleep=${e.asleep} chasing=${e.ai.chasing} unseen=${e.ai.unseen} d=${"%.1f".format(d)} see=${stage.canSee(e, me.x, me.y + 1.62, me.z)} nav=${(e.body as? net.minecraft.world.entity.Mob)?.let { m -> "done=" + m.navigation.isDone + " path=" + (m.navigation.path?.let { pa -> "" + pa.nodeCount + "@" + pa.target } ?: "none") + " ai=" + !m.isNoAi + " spd=" + m.speed } ?: e.body?.javaClass?.simpleName} lastNav=${"%.1f".format(e.lastNavX)},${"%.1f".format(e.lastNavZ)} pos=${pos?.joinToString(",") { "%.1f".format(it) }}")
            }
        }
    })

    internal fun summaryLines(run: Run): List<String> {
        if (entries.isEmpty()) return listOf("The module holds no mobs.")
        val lines = ArrayList<String>()
        lines += "${entries.size} mobs, HP scale ${"%.2f".format(java.util.Locale.ROOT, scale)}, ${entries.values.count { it.live }} bodies built."
        for ((room, list) in byRoom.toSortedMap()) {
            val name = run.plan.rooms.getOrNull(room)?.name ?: "room $room"
            val req = list.filter { it.record.required }
            lines += "$name: ${req.count { it.alive }}/${req.size} required alive, ${list.count { it.live }}/${list.count { it.alive }} bodies built" + (list.count { it.asleep }.takeIf { it > 0 }?.let { ", $it asleep" } ?: "")
        }
        // A room that should drop a key but has no required mob would never drop it.
        val stuck = run.plan.rooms.filter { r -> doors().keyForRoom(run, r.index) != null && (byRoom[r.index]?.count { it.record.required } ?: 0) == 0 }
        if (stuck.isNotEmpty()) lines += "§cNo required mob, so no key will drop in: " + stuck.joinToString { it.name }
        return lines
    }

    /** Spike support: removes the spike mob. */
    internal fun spikeClear(): String {
        val n = spikes.size
        for (e in spikes) { stage.release(e); stage.retract(e) }
        spikes.clear()
        return if (n == 0) "No spike mob is standing." else "Removed the spike mob."
    }

    /** Spike support: makes one mob outside any run in front of [player], owned by the spike list. Returns a description for the player. */
    internal fun spikeSpawn(player: net.minecraft.server.level.ServerPlayer, arch: MobArchetype, dist: Double): String {
        val level = SimServer.level ?: return "The sim world is not running."
        for (e in spikes) { stage.release(e); stage.retract(e) }
        spikes.clear()
        val look = player.lookAngle
        val x = player.x + look.x * dist; val z = player.z + look.z * dist
        val rng = Random(System.nanoTime())
        val hp = (arch.hpFor("F7") ?: 840_000L).toDouble()
        val id = if (arch.disguise) MobRolls.uuidV2(rng) else MobRolls.uuidV4(rng)
        val pm = PlannedMob(id, arch.index, true, false, "", hp, x, player.y, z, player.yRot + 180f, false, null)
        val rec = MobRecord(id, arch.id, arch.name, null, true, false, arch.cls == "miniboss", arch.disguise)
        rec.hp = hp; rec.maxHp = hp; rec.x = x; rec.y = player.y; rec.z = z
        val e = MobEntry(rec, pm, arch, null)
        e.asleep = false; e.hidden = false
        if (!MobWorld.materialise(e, level, fakePlayers = true, healthData = healthDataOn())) return "The mob could not be made (see the log)."
        spikes += e
        return "A ${arch.name} stands ${"%.0f".format(java.util.Locale.ROOT, dist)} blocks ahead: body id ${e.body?.id}, tag id ${e.stand?.id}, uuid version ${id.version()}, body ${e.body?.javaClass?.simpleName}."
    }
}
