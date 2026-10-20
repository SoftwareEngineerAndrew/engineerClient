package com.engineerclient.p3sim.clear.traps

import com.engineerclient.p3sim.clear.puzzles.hard.BlockEdit
import com.engineerclient.p3sim.clear.puzzles.hard.Frames
import com.engineerclient.p3sim.clear.puzzles.hard.HardBlocks
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.Debris
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.AdsServerContext
import com.engineerclient.p3sim.run.DamageSource
import com.engineerclient.p3sim.run.PlacedRoom
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunBlocks
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.api.Audience
import com.engineerclient.p3sim.run.api.Services
import com.engineerclient.p3sim.run.api.TrapService
import com.google.gson.JsonObject
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import kotlin.random.Random
import java.util.Locale
import java.util.UUID

/**
 * The trap room (crushers, tripwires; traps hit for 15% through the damage service), the arrow banks and flamethrowers of ordinary rooms, and the
 * rooms that disable abilities.
 *
 * Every room whose design lists crushers or tripwires (the two trap rooms and a few dozen ordinary rooms with one of the two) gets a
 * [RoomHazards] clock that runs while a player (a bot too) is inside. The crushers are written as blocks through the room's placement and grow as arms of
 * layers; a player inside an arm takes 30 percent of max HP, and a player who touches a tripwire in one of the rooms where real strings were
 * seen to hit ([TrapTimings.tripwireHurtRooms]) takes 15 percent as true damage (`DamageSource.TRAP`) at once, with Hypixel's
 * "The Tripwire Trap hit you for N damage!" line. Strings in every other room only flip their hooks.
 *
 * Arrow banks ([ArrowBank], from `traps.json`) shoot a [TrapArrow] from each dispenser on a fixed beat while a human is within range of the bank,
 * inside the room or not; an arrow that hits a human takes 15 percent and prints "The Arrow Trap hit you for N damage!". Flamethrowers ([FlameBank])
 * fire on an 8-tick beat while a human is in their room; a player in a stream takes 15 percent and "The Flamethrower hit you for N damage!".
 *
 * Abilities (teleports, blinks, the Wither abilities) are off in the trap rooms. Which puzzle rooms switch them off too is not known
 * (UNVERIFIED); a setting turns it on for the puzzle rooms.
 * FILLED BY: the `traps` work item (owns `src/p3sim/clear/traps/` and below).
 */
object TrapsModule : AdsModule, TrapService {
    override val id = "traps"
    override val owner = "traps"

    private lateinit var abilitiesOffInPuzzlesS: BooleanSetting
    private lateinit var abilitiesOffInTrapsS: BooleanSetting

    override fun registerSettings(host: com.odtheking.odin.features.Module) {
        abilitiesOffInTrapsS = host.registerSetting(BooleanSetting("Abilities Off In Trap Rooms", true,
            desc = "Teleports, blinks and the Wither abilities do nothing while you stand in the Old Trap or the New Trap, as on Hypixel."))
        abilitiesOffInPuzzlesS = host.registerSetting(BooleanSetting("Abilities Off In Puzzle Rooms", false,
            desc = "The same for the puzzle rooms. Which puzzle rooms switch abilities off on Hypixel is not known, so this is off."))
    }

    fun abilitiesOffInTrapRooms(): Boolean = if (::abilitiesOffInTrapsS.isInitialized) abilitiesOffInTrapsS.value else true
    fun abilitiesOffInPuzzleRooms(): Boolean = if (::abilitiesOffInPuzzlesS.isInitialized) abilitiesOffInPuzzlesS.value else false

    /** One room with hazards, in a run. */
    private class Entry(val room: PlacedRoom, val placement: Placement, val hazards: RoomHazards, val banks: List<ArrowBank> = emptyList(), val wake: DoubleArray? = null) {
        /** The arrow banks' own clock: it runs while a player (a bot too) is inside [wake] (the world box x0, z0, x1, z1 around the dispensers, grown by the arrow range), not only inside the room. */
        var bankClock = 0
    }

    private var entries: List<Entry> = emptyList()

    /**
     * While a replay runs the recording moves the crushers (a teammate ADS cannot see kept a room going on Hypixel): the arms keep their clocks,
     * their hits and their sounds, but the blocks are the recording's.
     */
    @Volatile var recordingMovesCrushers = false

    /** Whether the canonical cell is in the reach of one of [e]'s crushers. */
    private fun inArm(e: Entry, x: Int, y: Int, z: Int): Boolean = e.hazards.crushers.any { c ->
        val b = c.armBox(c.sweep); x in b[0]..b[3] && y in b[1]..b[4] && z in b[2]..b[5]
    }

    /** Whether the world cell is in the reach of a crusher of this run. */
    fun isCrusherCell(x: Int, y: Int, z: Int): Boolean = entries.any { e -> e.placement.toCanonical(x, z)?.let { (cx, cz) -> inArm(e, cx, y, cz) } == true }
    private var sites: TrapSites = TrapSites.NONE
    private var humanIds: Set<UUID> = emptySet()
    private val arrowHits = ArrayList<UUID>()
    private var trapPercent = 15.0
    private var maxHp = 11351.0

    override fun onRunStart(run: Run) {
        val timings = TrapTimings.load(run.packs.source)
        val plan = timings.roll(run.subRandom("traps"))
        trapPercent = run.packs.runstate?.damage?.trapPctOfMaxHp?.times(100.0)?.takeIf { it > 0.0 } ?: 15.0
        val presets = run.packs.runstate?.sbStatPresets.orEmpty()
        maxHp = (presets.firstOrNull { it.id == run.config.statsPreset } ?: presets.firstOrNull { it.id == "dev-p50" } ?: presets.firstOrNull())?.maxHp?.toDouble()?.takeIf { it > 0 } ?: 11351.0
        sites = timings.sites
        arrowHits.clear()
        entries = run.plan.rooms.mapNotNull { room ->
            val design = room.design ?: return@mapNotNull null
            val pl = room.placement ?: return@mapNotNull null
            val arrowRules = sites.arrows.filter { it.design == design.id }
            val flameRules = sites.flames.filter { it.design == design.id }
            if (design.crushers.isEmpty() && design.tripwires.isEmpty() && arrowRules.isEmpty() && flameRules.isEmpty()) return@mapNotNull null
            val crushers = design.crushers.flatMap {
                val t = plan.timing(it.id)
                val axis = it.axis?.firstOrNull() ?: 'x'
                val sweep = t.sweep ?: it.sweep
                val mat = it.material ?: "minecraft:polished_granite"
                if (!t.pincer) listOf(Crusher(it.id, it.slab, axis, it.sign, sweep, mat, t, plan.startAt(it.id)))
                else {
                    // The far arm's home is the cell past the sweep, and it grows back towards this one.
                    val d = it.sign * (sweep + 1)
                    val far = it.slab.copyOf().also { b -> if (axis == 'x') { b[0] += d; b[3] += d } else { b[2] += d; b[5] += d } }
                    listOf(Crusher(it.id, it.slab, axis, it.sign, sweep / 2, mat, t, plan.startAt(it.id)),
                        Crusher(it.id + "/far", far, axis, -it.sign, sweep - sweep / 2, mat, t, plan.startAt(it.id)))
                }
            }
            val lines = TripLine.pair(design.tripwires.map { Hook(it.pos.x, it.pos.y, it.pos.z, it.facing ?: "") })
            val hurts = timings.tripwiresHurtIn(room.name)
            val extras = ArrayList<Hazard>()
            for (rule in sites.falls) if (rule.design == design.id) fallingFloor(rule, design.fallingBlocks.map { intArrayOf(it.x, it.y, it.z) }, lines)?.let { extras += it }
            for ((i, r) in flameRules.withIndex()) extras += FlameBank(r, sites.flameStream, run.subRandom("traps.flame.${room.index}.$i").nextLong())
            // The banks' beat starts somewhere in its period: where is drawn once per run.
            val banks = arrowRules.mapIndexed { i, r -> ArrowBank(r, run.subRandom("traps.arrow.${room.index}.$i").nextInt(r.period)) }
            Entry(room, pl, RoomHazards(crushers, lines, linesHurt = hurts, linesClick = hurts || timings.tripwiresClickIn(room.name), extras = extras), banks, wakeBox(pl, arrowRules, sites.arrowFlight.range))
        }
    }

    /** The world box (x0, z0, x1, z1) around the dispensers of [rules], [range] blocks wider on every side; null when there are none. */
    private fun wakeBox(pl: Placement, rules: List<ArrowRule>, range: Double): DoubleArray? {
        val ds = rules.flatMap { it.dispensers }
        if (ds.isEmpty()) return null
        val ws = ds.map { pl.toWorld(it.x, it.y, it.z) }
        return doubleArrayOf(ws.minOf { it.x } - range, ws.minOf { it.z } - range, ws.maxOf { it.x } + 1 + range, ws.maxOf { it.z } + 1 + range)
    }

    /** Builds the floor a [FallRule] describes for a room whose falling cells and tripwire lines are given (null when the rule does not fit the room). */
    internal fun fallingFloor(rule: FallRule, falling: List<IntArray>, lines: List<TripLine>): FallingFloor? {
        val cells = FallingFloor.cluster(falling, rule.cluster)
        if (cells.isEmpty()) return null
        val extra = ArrayList<IntArray>()
        for (b in rule.also) for (y in b[1]..b[4]) for (z in b[2]..b[5]) for (x in b[0]..b[3]) extra += intArrayOf(x, y, z)
        val trigger = when (rule.trigger) {
            "string" -> {
                val h = rule.hook ?: return null
                val i = lines.indexOfFirst { it.hasHook(h[0], h[1], h[2]) }
                if (i < 0) return null
                FloorTrigger.StringTouched(i)
            }
            else -> FloorTrigger.StandOn
        }
        return FallingFloor(cells, extra, trigger)
    }

    override fun devCommands() = listOf(AdsCommand(
        "traps", "/ads dev traps <list|tp [room number]|status|arrows [room number]>",
        "Lists the rooms with crushers, tripwires, arrow banks or flamethrowers in this run, takes you to the first hazard of one, shows each room's clock, crushers, floors, banks and flames, or fires a room's arrow banks now.",
        server = { ctx -> devTraps(ctx) },
    ))

    private fun devTraps(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply("No run is on. Start one with /ads run.")
        if (entries.isEmpty()) return ctx.reply("This layout has no room with crushers, tripwires, arrow banks or flamethrowers.")
        when (ctx.arg(0)) {
            "list", null -> for (e in entries) ctx.reply("room ${e.room.index}: ${e.room.name} (${e.room.type}) ${e.hazards.crushers.size} crushers, ${e.hazards.lines.size} tripwires, " +
                "${e.banks.size} arrow banks, ${e.hazards.extras.count { it is FlameBank }} flamethrower groups").also {
                for (c in e.hazards.crushers) {
                    val b = c.armBox(c.sweep); val p = e.placement.toWorld(b[0], b[1], b[2]); val q = e.placement.toWorld(b[3], b[4], b[5])
                    val h = e.placement.toWorld(c.slab[0], c.slab[1], c.slab[2])
                    ctx.reply("  ${c.id}: arm ${minOf(p.x, q.x)}..${maxOf(p.x, q.x)} ${p.y}..${q.y} ${minOf(p.z, q.z)}..${maxOf(p.z, q.z)}, home at ${h.x} ${h.y} ${h.z}, sweep ${c.sweep}, ${c.timing.ticksPerBlock}/${c.timing.stillTicks}")
                }
            }
            "status" -> for (e in entries) ctx.reply("room ${e.room.index}: clock ${e.hazards.clock}, crushers out " + e.hazards.crushers.joinToString { "${it.id.substringAfter('/')}=${it.extensionAt(e.hazards.clock)}/${it.sweep}" } +
                e.hazards.extras.filterIsInstance<FallingFloor>().joinToString("") { ", floor of ${it.cells.size} blocks ${if (it.fired) "gone" else "standing"}" } +
                (if (e.banks.isEmpty()) "" else ", bank clock ${e.bankClock}, banks " + e.banks.joinToString { "${it.rule.dispensers.size}x${it.rule.perDispenser} every ${it.rule.period} (phase ${it.phase})" }))
            "arrows" -> {
                val e = ctx.arg(1)?.toIntOrNull()?.let { n -> entries.firstOrNull { it.room.index == n } } ?: entries.firstOrNull { it.banks.isNotEmpty() }
                if (e == null || e.banks.isEmpty()) return ctx.reply("No room in this run has an arrow bank.")
                for (b in e.banks) fire(e, b.rule)
                ctx.reply("Fired the ${e.banks.size} arrow bank(s) of ${e.room.name} (room ${e.room.index}) now.")
            }
            "tp" -> {
                val e = ctx.arg(1)?.toIntOrNull()?.let { n -> entries.firstOrNull { it.room.index == n } } ?: entries.firstOrNull { it.room.type == RoomType.TRAP } ?: entries.first()
                // A tripwire's first hook has air beside it (the string runs from it); failing that, beside the first crusher's slab, a dispenser's face or a flamethrower's.
                val line = e.hazards.lines.firstOrNull()
                val crusher = e.hazards.crushers.firstOrNull()
                val em = e.banks.firstOrNull()?.rule?.dispensers?.firstOrNull() ?: e.hazards.extras.filterIsInstance<FlameBank>().firstOrNull()?.rule?.emitters?.firstOrNull()
                val (cx, cy, cz) = when {
                    line != null -> Triple(if (line.axis == 'x') line.from + 1.5 else line.fixed + 0.5, line.y.toDouble(), if (line.axis == 'x') line.fixed + 0.5 else line.from + 1.5)
                    crusher != null -> crusher.slab.let { Triple(it[0] + 0.5, it[1].toDouble(), it[2] - 1.5) }
                    em != null -> Triple(em.x + 0.5 + em.dx * 3.0, em.y.toDouble(), em.z + 0.5 + em.dz * 3.0)
                    else -> return ctx.reply("${e.room.name} has no hazard to go to.")
                }
                val (wx, wz) = Frames.toWorld(e.placement, cx, cz)
                Sim.tp(ctx.player, wx, cy, wz, Frames.yawToWorld(e.placement, 0f), 0f)
                ctx.reply("Beside the first ${if (line != null) "tripwire" else if (crusher != null) "crusher" else "dispenser"} of ${e.room.name} (room ${e.room.index}).")
            }
            else -> ctx.reply("Usage: /ads dev traps <list|tp [room number]|status|arrows [room number]>")
        }
    }

    override fun onRunEnd(run: Run, reason: com.engineerclient.p3sim.run.EndReason) { entries = emptyList() }

    override fun onTick(run: Run) {
        if (run.stage != RunStage.CLEAR && run.stage != RunStage.BLOOD) return
        if (entries.isEmpty()) return
        // Teammates run a room's hazards as much as you do (ADR: a crusher kept moving while only a teammate was in its room, 1,100 ticks after the
        // recorder had left it), so bots count for the clocks; only people are hurt (the bots' damage is on their own schedule).
        val present = run.state.players.values.filter { !it.ghost }
        humanIds = run.state.players.values.filter { !it.isBot }.mapTo(HashSet()) { it.uuid }
        // Arrows that hit a human since the last tick.
        if (arrowHits.isNotEmpty()) {
            val ids = arrowHits.toList(); arrowHits.clear()
            for (id in ids) strike(run, HazardHit(id, HazardKind.ARROW))
        }
        for (e in entries) {
            val inside = present.mapNotNull { p ->
                if (run.state.roomAt(p.x, p.z)?.index != e.room.index) return@mapNotNull null
                val c = Frames.toCanonical(e.placement, p.x, p.z) ?: return@mapNotNull null
                PlayerPos(p.uuid, c.first, p.y, c.second)
            }
            if (inside.isNotEmpty()) {
                val r = e.hazards.tick(inside)
                handle(run, e, r.edits, r.hits.filter { it.player in humanIds }, r.cues, r.debris, r.volleys, r.flames)
            }
            // The arrow banks beat on their own clock while a player is near enough to see them, in the room or beside it.
            val w = e.wake
            if (w != null && present.any { it.x >= w[0] && it.x <= w[2] && it.z >= w[1] && it.z <= w[3] }) {
                e.bankClock++
                val out = HazardOut()
                for (b in e.banks) b.tick(e.bankClock, emptyList(), out)
                handle(run, e, out.edits, out.hits.filter { it.player in humanIds }, out.cues, out.debris, out.volleys, out.flames)
            }
        }
    }

    /** Does what a room's hazards asked for this tick: writes the blocks, turns floor cells to debris, plays the sounds, shoots the arrows, draws the flames and hurts. */
    private fun handle(run: Run, e: Entry, edits: List<BlockEdit>, hits: List<HazardHit>, cues: List<SoundCue>, debris: List<IntArray>, volleys: List<ArrowRule>, flames: List<FlameFx>) {
        if (debris.isNotEmpty()) spawnDebris(e, debris)
        val mine = if (recordingMovesCrushers) edits.filter { !inArm(e, it.x, it.y, it.z) } else edits
        if (mine.isNotEmpty()) HardBlocks.apply(e.placement, mine)
        for (cue in cues) play(e, cue)
        for (rule in volleys) fire(e, rule)
        for (fx in flames) draw(e, fx)
        for (hit in hits) strike(run, hit)
    }

    /** One hit of a hazard: 15 percent true damage (a crusher 30) through the damage service, and Hypixel's red line with the damage. */
    private fun strike(run: Run, hit: HazardHit) {
        // A crusher hits for twice a tripwire (30 percent; one recorded hit, see [CRUSHER_PERCENT]).
        val percent = if (hit.kind == HazardKind.CRUSHER) CRUSHER_PERCENT else trapPercent
        val res = Services.damage.hurtPlayer(run, hit.player, percent, if (hit.kind == HazardKind.CRUSHER) DamageSource.CRUSH else DamageSource.TRAP, null, trueDamage = true)
        if (res.applied <= 0.0) return
        val damage = res.applied / 100.0 * maxHp
        val line = when (hit.kind) {
            HazardKind.CRUSHER -> crusherLine(damage)
            HazardKind.TRIPWIRE -> tripwireLine(damage)
            HazardKind.ARROW -> arrowTrapLine(damage)
            HazardKind.FLAME -> flamethrowerLine(damage)
        }
        Services.channels.chat(line, Audience.Only(hit.player))
    }

    /**
     * Every dispenser of [rule] shoots, in this tick: an arrow [ArrowRule.perDispenser] times from 0.7 blocks in front of it, aimed along the facing and
     * a tenth up, the way vanilla's dispenser does and the recorded arrows fly (speed 1.50, sd 0.07). No sound: the real volleys are silent.
     */
    private fun fire(e: Entry, rule: ArrowRule) {
        val level = RunBlocks.level ?: return
        val flight = sites.arrowFlight
        for (d in rule.dispensers) {
            val w = e.placement.toWorld(d.x, d.y, d.z)
            if (!RunBlocks.isLoaded(w.x, w.z)) continue
            val (x0, z0) = Frames.toWorld(e.placement, d.x + 0.5, d.z + 0.5)
            val (x1, z1) = Frames.toWorld(e.placement, d.x + 0.5 + d.dx, d.z + 0.5 + d.dz)
            val dx = x1 - x0; val dz = z1 - z0; val dy = d.dy.toDouble()
            repeat(rule.perDispenser) {
                val a = TrapArrow(level, x0 + 0.7 * dx, w.y + 0.5 + 0.7 * dy, z0 + 0.7 * dz, flight.maxTicks, { it.uuid in humanIds }, { arrowHits += it.uuid })
                a.shoot(dx, dy + 0.1, dz, flight.speed, flight.inaccuracy)
                Sim.spawn(a)
            }
        }
    }

    /** The flame particles of one stretch of a stream. */
    private fun draw(e: Entry, fx: FlameFx) {
        val level = RunBlocks.level ?: return
        val em = fx.emitter
        repeat(5) {
            val along = fx.from + (fx.to - fx.from) * Random.nextDouble()
            val sx = if (em.dx == 0) (Random.nextDouble() - 0.5) * 0.8 else 0.0
            val sz = if (em.dz == 0) (Random.nextDouble() - 0.5) * 0.8 else 0.0
            val cx = em.x + 0.5 + em.dx * (0.5 + along) + sx
            val cz = em.z + 0.5 + em.dz * (0.5 + along) + sz
            val cy = em.y + 0.2 + Random.nextDouble() * 0.8 + em.dy * (0.5 + along)
            val (wx, wz) = Frames.toWorld(e.placement, cx, cz)
            level.sendParticles(ParticleTypes.FLAME, wx, cy, wz, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /** Hypixel's damage figure: a thousands comma and at most one decimal ("1,336" when whole, "1,336.5" otherwise). */
    fun damageText(damage: Double): String = String.format(Locale.US, "%,.1f", damage).removeSuffix(".0")

    /** Hypixel's red line for a tripwire hit. */
    fun tripwireLine(damage: Double): String = "§cThe Tripwire Trap hit you for ${damageText(damage)} damage!"

    /** The same for the other trap-room hazards. */
    fun crusherLine(damage: Double): String = "§cThe Crusher hit you for ${damageText(damage)} damage!"
    fun arrowTrapLine(damage: Double): String = "§cThe Arrow Trap hit you for ${damageText(damage)} damage!"
    fun flamethrowerLine(damage: Double): String = "§cThe Flamethrower hit you for ${damageText(damage)} damage!"

    /** Percent of max HP a crusher takes. Thin evidence: one recorded hit (3,837.3 of 12,791 HP); the other traps are 15.0 in many hits. */
    const val CRUSHER_PERCENT = 30.0

    /** Plays one of a room's sounds at its canonical position. */
    private fun play(e: Entry, cue: SoundCue) {
        val (wx, wz) = Frames.toWorld(e.placement, cue.x, cue.z)
        val (event, source) = soundFor(cue.sound)
        Sim.sound(event, cue.sound.volume, cue.sound.pitch, Vec3(wx, cue.y, wz), source)
    }

    private fun soundFor(s: HazardSound): Pair<SoundEvent, SoundSource> = when (s) {
        HazardSound.LEVER_ON, HazardSound.LEVER_OFF -> SoundEvents.LEVER_CLICK to SoundSource.BLOCKS
        HazardSound.TRIPWIRE_BLAST -> SoundEvents.GENERIC_EXPLODE.value() to SoundSource.BLOCKS
        HazardSound.CRUSHER_SLAM -> SoundEvents.IRON_GOLEM_HURT to SoundSource.NEUTRAL
        HazardSound.FLOOR_COLLAPSE -> SoundEvents.LAVA_EXTINGUISH to SoundSource.BLOCKS
        HazardSound.FLAME_SHOOT -> SoundEvents.GHAST_SHOOT to SoundSource.HOSTILE
    }

    /** One falling-block entity (no motion yet, discarded where it lands) for each canonical cell, made from the block that stands there now. */
    private fun spawnDebris(e: Entry, cells: List<IntArray>) {
        val level = RunBlocks.level ?: return
        for (c in cells) {
            val w = e.placement.toWorld(c[0], c[1], c[2])
            val st = RunBlocks.get(w.x, w.y, w.z) ?: continue
            if (st.isAir || !st.fluidState.isEmpty) continue
            val d = Debris(level, st)
            d.snapTo(w.x + 0.5, w.y + 0.5, w.z + 0.5, 0f, 0f)
            d.deltaMovement = Vec3.ZERO
            Sim.spawn(d)
        }
    }

    override fun snapshot(run: Run): JsonObject? = JsonObject().also { o ->
        for (e in entries) o.add(e.room.index.toString(), e.hazards.snapshot().also { it.addProperty("bankClock", e.bankClock) })
    }

    override fun restore(run: Run, data: JsonObject) {
        for (e in entries) {
            val o = data.getAsJsonObject(e.room.index.toString())
            if (o != null) { e.hazards.restore(o); e.bankClock = o.get("bankClock")?.asInt ?: 0 }
            HardBlocks.apply(e.placement, e.hazards.currentEdits())
        }
    }

    override fun abilitiesDisabled(run: Run, player: UUID): Boolean {
        val rec = run.state.players[player] ?: return false
        val room = run.state.roomAt(rec.x, rec.z)?.plan ?: return false
        return abilitiesOffIn(room.type)
    }

    /** Whether abilities are off in a room of this type under the current settings. */
    fun abilitiesOffIn(type: RoomType): Boolean = when (type) {
        RoomType.TRAP -> abilitiesOffInTrapRooms()
        RoomType.PUZZLE -> abilitiesOffInPuzzleRooms()
        else -> false
    }
}
