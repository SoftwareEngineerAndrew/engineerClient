package com.engineerclient.p3sim.clear.blood

import com.engineerclient.p3sim.BossBar
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.BloodDoorOpened
import com.engineerclient.p3sim.run.CheckMark
import com.engineerclient.p3sim.run.EndReason
import com.engineerclient.p3sim.run.ModuleEvent
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.WatcherDone
import com.engineerclient.p3sim.run.api.Services
import com.engineerclient.p3sim.run.plugin.AdsPlugins
import com.engineerclient.p3sim.run.plugin.BloodCamp
import com.engineerclient.p3sim.clear.blood.camp.WatcherCamp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.engineerclient.ads.settings.BooleanSetting
import com.engineerclient.ads.settings.NumberSetting
import net.minecraft.world.InteractionResult
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * The public blood room and the boss seam.
 *
 * When the doors module posts `BloodDoorOpened`, this module moves the run to BLOOD, marks the blood room RED, hangs the Watcher (an
 * invisible zombie with two Watchful Eyes and name tags) in the middle of the room, puts the 30 wall-head stands on their stations, and
 * hands the room to the camp: the first plug-in camp that supports the build ([AdsPlugins.bloodCamp]), else the Watcher's own camp
 * ([WatcherCamp], the camp as the real server plays it), and only a room with too few wall heads for that gets the plain stand-in
 * [NeutralCamp]. While a player is in the room the red "The Watcher" boss bar shows his health. The camp ticks until it says it is
 * finished; then the Watcher says his pass line, `WatcherDone` is posted.
 *
 * The way out does not wait for anyone ([BloodExit]): from the camp's fifteenth counted kill (the defeat), the lightning bolt comes at
 * +79/+89 ticks, the portal in the back alcove at +80/+90 (the room turns GREEN then: `ModuleEvent("blood", "portal")`), and the Watcher
 * and his Eyes vanish at +81. One tick after a player's body first touches the portal, [BossEntry] does the one-tick jump of the whole
 * party to Maxor and hands over to the engine's boss stage.
 *
 * What the camp does is in `camp/` (the Watcher's 10-tick task, his flights and throws, the mobs, the counted kills); a plug-in may
 * replace it through [AdsPlugins.bloodCamp]. Dev commands: `/ads dev blood`.
 * FILLED BY: the `blood` work item (owns `src/p3sim/clear/blood/` and below).
 */
object BloodModule : AdsModule {
    override val id = "blood"
    override val owner = "blood"

    private val log = LoggerFactory.getLogger("engineerclient")

    // ---- settings (created in registerSettings; the defaults below are used before that, and in headless tests) ----------------
    /** UNMEASURED: five minutes, only so a camp that never ends cannot hold a run for ever. */
    const val DEFAULT_CAMP_TIMEOUT = 6000
    const val DEFAULT_PLACEHOLDER_MOBS = 6
    const val DEFAULT_PLACEHOLDER_FIRST = 60
    const val DEFAULT_PLACEHOLDER_INTERVAL = 40

    /** Ticks between the Watcher bar's resends of name, progress and style (recorded 19 to 21). */
    const val BAR_RESEND_TICKS = 20

    /** The most steps one tick can answer (pass line, strike, portal, removal, entry, with room to spare). */
    private const val MAX_STEPS = 8

    /** A portal cooldown given to every human while the portal is open, so vanilla's own portal never takes a player to another world. */
    private const val PORTAL_COOLDOWN = 40

    private lateinit var campTimeoutS: NumberSetting<Int>
    private lateinit var headsS: BooleanSetting
    private lateinit var placeholderMobsS: NumberSetting<Int>
    private lateinit var placeholderFirstS: NumberSetting<Int>
    private lateinit var placeholderIntervalS: NumberSetting<Int>

    override fun registerSettings(host: com.engineerclient.ads.settings.Settings) {
        campTimeoutS = host.registerSetting(NumberSetting("Blood Camp Timeout", DEFAULT_CAMP_TIMEOUT, 0..24000, 100, unit = " ticks", desc = "If the blood room's camp has not finished this long after the blood door opened, the Watcher lets the party pass anyway. 0: never. Only a safety net; not measured."))
        headsS = host.registerSetting(BooleanSetting("Blood Wall Heads", true, desc = "Put the blood room's wall heads on their stands when the blood door opens. Off: bare walls."))
        placeholderMobsS = host.registerSetting(NumberSetting("Blood Placeholder Mobs", DEFAULT_PLACEHOLDER_MOBS, 0..40, 1, desc = "Only for a blood room with too few wall heads for the Watcher's camp: the plain stand-in releases this many ordinary mobs, one at a time. Not the real camp."))
        placeholderFirstS = host.registerSetting(NumberSetting("Blood Placeholder First Mob", DEFAULT_PLACEHOLDER_FIRST, 0..1200, 5, unit = " ticks", desc = "Ticks from the blood door opening to the stand-in's first mob."))
        placeholderIntervalS = host.registerSetting(NumberSetting("Blood Placeholder Interval", DEFAULT_PLACEHOLDER_INTERVAL, 1..400, 1, unit = " ticks", desc = "Ticks between the stand-in's mobs."))
    }

    private fun campTimeout() = if (::campTimeoutS.isInitialized) campTimeoutS.value else DEFAULT_CAMP_TIMEOUT
    private fun wallHeads() = if (::headsS.isInitialized) headsS.value else true
    private fun phMobs() = if (::placeholderMobsS.isInitialized) placeholderMobsS.value else DEFAULT_PLACEHOLDER_MOBS
    private fun phFirst() = if (::placeholderFirstS.isInitialized) placeholderFirstS.value else DEFAULT_PLACEHOLDER_FIRST
    private fun phInterval() = if (::placeholderIntervalS.isInitialized) placeholderIntervalS.value else DEFAULT_PLACEHOLDER_INTERVAL

    // ---- state of the current run's blood stage ----------------------------------------------------------------------------
    private var flow = newFlow(null)
    private var room: BloodRoom? = null
    private var host: BloodHost? = null
    private var camp: BloodCamp? = null
    private var campBroken = false
    private var forcePass = false

    /** A replay: the recording's Watcher let the party pass now, so ADS's does on this tick. False when no camp is running. Server thread. */
    fun passNow(): Boolean { if (flow.phase != BloodFlow.Phase.CAMP) return false; forcePass = true; return true }

    /** A replay whose recording shows the portal: ADS's waits for [portalNow] instead of its rolled delay. Server thread. */
    @Volatile var portalFromReplay = false

    /** A replay: the recording's portal opened now, so ADS's does on this tick. False when the camp is not open or the portal is up. */
    fun portalNow(): Boolean {
        if (flow.phase != BloodFlow.Phase.CAMP && flow.phase != BloodFlow.Phase.PASSED) return false
        if (flow.phase == BloodFlow.Phase.CAMP) forcePass = true
        flow.portalNow(); return true
    }
    private var watcher: WatcherEntity? = null
    private var heads: WallHeads? = null

    /** Whether the Watcher's boss bar is up (this module made it, so this module takes it down), and what it last showed. */
    private var barUp = false
    private var barKills = -1
    private var barSince = 0

    private fun newFlow(run: Run?) = BloodFlow(campTimeout(), run?.subRandom("blood-exit") ?: Random(0)).also { it.portalHeld = portalFromReplay }

    private fun reset(run: Run?) {
        // The Watcher's bar goes with the room (a restore to before the blood door); tearDown alone leaves it for the entry to rename.
        if (barUp) BossBar.hide()
        tearDown()
        flow = newFlow(run)
        room = null; camp = null; host = null; campBroken = false; forcePass = false
        barKills = -1; barSince = 0
    }

    /** Removes the Watcher, the wall-head stands and the camp's leftovers. The portal blocks stay (the dungeon's blocks stand through the boss entry). */
    private fun tearDown() {
        try { host?.cleanUp() } catch (t: Throwable) { log.warn("[ads] blood cleanup failed", t) }
        try { watcher?.remove() } catch (t: Throwable) { log.warn("[ads] blood watcher removal failed", t) }
        try { heads?.removeAll() } catch (t: Throwable) { log.warn("[ads] blood heads removal failed", t) }
        watcher = null; heads = null
        barUp = false
    }

    override fun onRunStart(run: Run) = reset(run)

    override fun onRunEnd(run: Run, reason: EndReason) = reset(run)

    override fun onStage(run: Run, from: RunStage?, to: RunStage) {
        when {
            // A savepoint restore went back before the blood door: the blood room is as generated again.
            to.ordinal < RunStage.BLOOD.ordinal -> reset(run)
            // The entry renamed the Watcher's bar to Maxor's in place: it is the boss stage's now.
            to.ordinal > RunStage.BLOOD.ordinal -> tearDown()
            else -> {}
        }
    }

    override fun onEvent(run: Run, event: RunEvent) {
        if (event is BloodDoorOpened) { onBloodDoor(run); return }
        val c = camp ?: return
        if (flow.phase != BloodFlow.Phase.CAMP || campBroken) return
        try { c.onEvent(event) } catch (t: Throwable) { breakCamp("onEvent", t) }
    }

    override fun onTick(run: Run) {
        if (flow.phase == BloodFlow.Phase.CLOSED || flow.phase == BloodFlow.Phase.ENTERED) return
        if (run.stage != RunStage.BLOOD) return
        host?.tick()
        val c = camp
        if (flow.phase == BloodFlow.Phase.CAMP && c != null && !campBroken) {
            try { c.tick() } catch (t: Throwable) { breakCamp("tick", t) }
        }
        updateBar(run)
        val finished = forcePass || campBroken || c == null || try { c.finished } catch (t: Throwable) { breakCamp("finished", t); true }
        repeat(MAX_STEPS) {
            if (flow.phase == BloodFlow.Phase.PORTAL) keepVanillaPortalAway()
            val contact = flow.phase == BloodFlow.Phase.PORTAL && portalContact(run)
            when (flow.step(run.serverTick, finished, contact)) {
                BloodFlow.Step.SAY_PASS -> sayPass(run)
                BloodFlow.Step.STRIKE -> BossEntry.strike(room?.exit)
                BloodFlow.Step.OPEN_PORTAL -> openPortal(run)
                BloodFlow.Step.REMOVE_WATCHER -> watcher?.remove()
                BloodFlow.Step.ENTER -> { enter(run); return }
                BloodFlow.Step.NONE -> return
            }
        }
    }

    // ---- the blood door ----------------------------------------------------------------------------------------------------

    private fun onBloodDoor(run: Run) {
        if (flow.phase != BloodFlow.Phase.CLOSED) return
        val placed = run.plan.blood
        val rooms = run.packs.rooms
        val volume = if (placed?.design != null) rooms?.volumeFor(placed.design, placed.variantId) else null
        val r = BloodRoom.of(run.plan, volume)
        if (r == null) {
            // A layout without a blood room would leave the run in BLOOD with a closed flow and nothing to end it: skip the stage and go to the boss.
            log.warn("[ads] this layout has no blood room; skipping to the boss")
            Sim.note("This layout has no blood room, so the run goes straight to Maxor.")
            if (run.stage == RunStage.CLEAR || run.stage == RunStage.BLOOD) {
                run.state.milestones.bloodOpenT = run.state.milestones.bloodOpenT ?: run.t
                run.state.milestones.watcherDoneT = run.t
                BossEntry.enter(run)
            }
            return
        }
        if (run.stage == RunStage.CLEAR) run.requestStage(RunStage.BLOOD)
        if (run.stage != RunStage.BLOOD) { log.warn("[ads] the blood door opened in stage ${run.stage}; the blood room does not start"); return }
        run.state.milestones.bloodOpenT = run.state.milestones.bloodOpenT ?: run.t
        room = r
        // The room's RED mark is the doors module's (it answers the BLOOD stage change); it is the only writer and keeps the counters.

        flow = newFlow(run).also { it.open(run.serverTick) }
        startRoom(run, r, rooms?.textures.orEmpty()) { wh -> wh.placeAll() }
        campBroken = false; forcePass = false
        try { camp?.start() } catch (t: Throwable) { breakCamp("start", t) }
    }

    /** Stands the Watcher, the wall-head stands and the camp up; [heads] fills the wall (everything plain on a new room, the saved keys on a restore). */
    private fun startRoom(run: Run, r: BloodRoom, textures: List<com.engineerclient.p3sim.pack.rooms.SkullTexture>, heads: (WallHeads) -> Unit) {
        // The doorway starts open: some recorded builds have the fight's portal standing in it.
        if (!flow.portalOpen) r.exit?.let { BossEntry.clearPortal(it) }
        val w = WatcherEntity(r.watcherX, r.watcherHomeY, r.watcherZ, r.watcherYaw, textures, run.subRandom("blood-watcher"))
        if (!flow.watcherRemoved) { w.spawn(); w.setKills(flow.kills) }
        watcher = w
        val wh = WallHeads(if (wallHeads()) r.heads else emptyList(), textures)
        heads(wh)
        this.heads = wh
        val h = BloodHost(run, r, flow, wh, w)
        host = h
        camp = makeCamp(run, h, r)
    }

    private fun makeCamp(run: Run, h: BloodHost, r: BloodRoom): BloodCamp {
        val factory = AdsPlugins.bloodCamp(r)
        if (factory != null) {
            try { return factory.create(h) } catch (t: Throwable) { log.warn("[ads] the blood camp plug-in failed to start; using the Watcher's own camp", t) }
        }
        if (WatcherCamp.supports(r)) return WatcherCamp(h)
        val known = run.packs.mobs?.archetypes?.map { it.id }?.toSet()
        val kinds = NeutralCamp.DEFAULT_KINDS.filter { known == null || it in known }.ifEmpty { NeutralCamp.DEFAULT_KINDS }
        return NeutralCamp(h, phMobs(), phFirst(), phInterval(), kinds)
    }

    private fun breakCamp(what: String, t: Throwable) {
        if (!campBroken) {
            campBroken = true
            log.warn("[ads] the blood camp failed in $what; ending the Watcher stage", t)
            Sim.note("The blood room's camp failed ($what), so the Watcher lets you pass.")
        }
    }

    // ---- the Watcher's bar -------------------------------------------------------------------------------------------------

    /**
     * The red "The Watcher" bar: up from the first tick a human is inside the blood room (recorded 0 to 21 ticks after the room row
     * switches to Blood), its fill the Watcher's health, its name, fill and style sent again every [BAR_RESEND_TICKS] ticks. It stays up
     * until the entry renames the same bar to Maxor's.
     */
    private fun updateBar(run: Run) {
        val h = host ?: return
        val progress = WatcherStats.barProgress(flow.kills)
        if (!barUp) {
            if (run.state.players.values.none { !it.isBot && h.inRoom(it.x, it.z) }) return
            BossBar.show(BAR_NAME, progress)
            barUp = true; barKills = flow.kills; barSince = run.serverTick
            return
        }
        // A new human, or a human who came in after the bar went up, sees it too.
        BossBar.show(BAR_NAME, progress)
        if (flow.kills != barKills) { barKills = flow.kills; barSince = run.serverTick }
        if (run.serverTick - barSince >= BAR_RESEND_TICKS) { BossBar.resend(); barSince = run.serverTick }
    }

    // ---- the pass line, the portal and the jump ------------------------------------------------------------------------------

    private fun sayPass(run: Run) {
        watcher?.say(WatcherLines.PASS) ?: Services.channels.chat(WatcherLines.chat(WatcherLines.PASS))
        if (flow.timedOut) Sim.note("The blood room's camp took longer than the Blood Camp Timeout setting, so the Watcher lets you pass.")
        run.state.milestones.watcherDoneT = run.t
        run.bus.post(WatcherDone(run.t))
    }

    private fun openPortal(run: Run) {
        BossEntry.openPortal(run, room?.exit)
        // The room turns GREEN now (the doors module answers this notice), as it does on Hypixel.
        run.bus.post(ModuleEvent(run.t, "blood", "portal"))
        keepVanillaPortalAway()
    }

    /** Whether a living member's body touches the open portal. Without an alcove (a room with no blocks) the jump simply follows the portal tick. */
    private fun portalContact(run: Run): Boolean {
        val site = room?.exit ?: return true
        for (rec in run.state.players.values) {
            if (rec.ghost) continue
            if (site.touches(rec.x, rec.y, rec.z)) return true
        }
        return false
    }

    /** Vanilla would take a player standing in a nether portal to another dimension; a cooldown keeps it off. */
    private fun keepVanillaPortalAway() {
        for (p in Sim.players()) if (p.portalCooldown < PORTAL_COOLDOWN / 2) p.setPortalCooldown(PORTAL_COOLDOWN)
    }

    private fun enter(run: Run) {
        tearDown()
        BossEntry.enter(run)
    }

    // ---- clicks on the wall --------------------------------------------------------------------------------------------------

    /** The wall-head stands are scenery: a click on one does nothing (it would swap its head or knock it off). */
    override fun onUseEntity(run: Run, player: net.minecraft.server.level.ServerPlayer, hand: net.minecraft.world.InteractionHand, entity: net.minecraft.world.entity.Entity): InteractionResult? =
        if (heads?.owns(entity) == true) InteractionResult.FAIL else null

    override fun onAttackEntity(run: Run, player: net.minecraft.server.level.ServerPlayer, hand: net.minecraft.world.InteractionHand, entity: net.minecraft.world.entity.Entity): InteractionResult? =
        if (heads?.owns(entity) == true) InteractionResult.FAIL else null

    // ---- savepoints --------------------------------------------------------------------------------------------------------

    override fun snapshot(run: Run): JsonObject? {
        if (flow.phase == BloodFlow.Phase.CLOSED) return null
        val s = flow.snapshot(run.serverTick)
        return JsonObject().also { o ->
            o.addProperty("phase", s.phase.name)
            s.sinceOpen?.let { o.addProperty("sinceOpen", it) }
            s.sincePass?.let { o.addProperty("sincePass", it) }
            o.addProperty("timedOut", s.timedOut)
            o.addProperty("kills", s.kills)
            s.sinceDefeat?.let { o.addProperty("sinceDefeat", it) }
            s.portalDelay?.let { o.addProperty("portalDelay", it) }
            s.sinceContact?.let { o.addProperty("sinceContact", it) }
            o.addProperty("struck", s.struck)
            o.addProperty("watcherGone", s.watcherGone)
            o.add("heads", JsonArray().also { a -> heads?.keys()?.forEach { k -> a.add(k ?: "") } })
            try { camp?.snapshot()?.let { o.add("camp", it) } } catch (t: Throwable) { log.warn("[ads] the blood camp could not be saved", t) }
        }
    }

    override fun restore(run: Run, data: JsonObject) {
        reset(run)
        val phase = try { BloodFlow.Phase.valueOf(data.get("phase").asString) } catch (e: Exception) { return }
        if (phase == BloodFlow.Phase.CLOSED || phase == BloodFlow.Phase.ENTERED) return
        val s = BloodFlow.FlowSnapshot(
            phase, data.get("sinceOpen")?.asInt, data.get("sincePass")?.asInt, data.get("timedOut")?.asBoolean ?: false,
            data.get("kills")?.asInt ?: 0, data.get("sinceDefeat")?.asInt, data.get("portalDelay")?.asInt, data.get("sinceContact")?.asInt,
            data.get("struck")?.asBoolean ?: false, data.get("watcherGone")?.asBoolean ?: false,
        )
        val placed = run.plan.blood
        val volume = if (placed?.design != null) run.packs.rooms?.volumeFor(placed.design, placed.variantId) else null
        val r = BloodRoom.of(run.plan, volume) ?: return
        room = r
        flow = newFlow(run).also { it.restore(s, run.serverTick) }
        val saved = data.getAsJsonArray("heads")?.map { it.asString.ifEmpty { null } }.orEmpty()
        startRoom(run, r, run.packs.rooms?.textures.orEmpty()) { wh -> wh.reapply(saved) }
        // The reset took the portal blocks away with the rest of the room: put them back when the portal was open.
        if (flow.portalOpen) r.exit?.let { BossEntry.setPortal(it) }
        data.getAsJsonObject("camp")?.let { try { camp?.restore(it) } catch (t: Throwable) { log.warn("[ads] the blood camp could not be restored", t) } }
    }

    // ---- dev commands ------------------------------------------------------------------------------------------------------

    override fun devCommands(): List<AdsCommand> = listOf(
        AdsCommand("blood", "/ads dev blood <status|open|pass|kills N|enter|heads>", "Drives the blood room and the boss entry: status, open the blood door, end the camp, set the Watcher's counted kills (15 = defeat, portal 80 or 90 ticks later), jump to Maxor now, list the wall heads.", server = { ctx ->
            val run = ctx.run
            if (run == null) { ctx.reply("No run is on. Start one with /ads run random."); return@AdsCommand }
            when (ctx.arg(0)) {
                null, "status" -> {
                    ctx.reply("Stage ${run.stage}, blood phase ${flow.phase}, ${flow.ticksSinceOpen(run.serverTick)} ticks since the door opened.")
                    ctx.reply("Camp: " + when { camp == null -> "none yet"; camp is NeutralCamp -> "the plain stand-in"; camp is WatcherCamp -> "the Watcher's camp"; else -> "a plug-in camp" } + (if (campBroken) " (failed)" else ""))
                    val e = flow.exit
                    ctx.reply("Watcher: ${flow.kills} counted kills, health ${WatcherStats.health(flow.kills)}" + (if (e == null) ", not defeated" else ", defeated ${run.serverTick - e.defeatTick} ticks ago, portal at +${e.portalDelay}, gone at +${BloodExit.WATCHER_GONE}"))
                    ctx.reply("Heads: ${heads?.slots?.size ?: 0} slots, ${heads?.keys()?.count { it != null } ?: 0} on the wall. Portal ${if (flow.portalOpen) "open" else "closed"}.")
                }
                "open" -> {
                    if (run.stage != RunStage.CLEAR) ctx.reply("The blood door can only open in the clear (the stage is ${run.stage}).")
                    else { run.bus.post(BloodDoorOpened(run.t)); ctx.reply("Blood door opened.") }
                }
                "pass" -> if (flow.phase != BloodFlow.Phase.CAMP) ctx.reply("The camp is not running.") else { forcePass = true; ctx.reply("The Watcher will let you pass this tick.") }
                "kills" -> {
                    val n = ctx.arg(1)?.toIntOrNull()
                    val h = host
                    if (n == null) ctx.reply("Usage: /ads dev blood kills <0 to 15>")
                    else if (h == null || flow.phase == BloodFlow.Phase.CLOSED) ctx.reply("The blood door has not opened.")
                    else { h.watcherKills(n); ctx.reply("The Watcher has ${flow.kills} counted kills.") }
                }
                "enter" -> when (flow.phase) {
                    BloodFlow.Phase.CAMP, BloodFlow.Phase.PASSED, BloodFlow.Phase.PORTAL -> { flow.expedite(); ctx.reply("Skipping the waits: the pass line, the portal and the jump to Maxor.") }
                    else -> ctx.reply("The blood door has not opened (or the jump is done).")
                }
                "heads" -> {
                    val h = heads ?: run { ctx.reply("The blood room is not open yet."); return@AdsCommand }
                    h.slots.forEach { s -> ctx.reply("slot ${s.index}: ${s.x} ${s.y} ${s.z} facing ${s.facing}, shows ${h.keys()[s.index] ?: "nothing"}") }
                }
                else -> ctx.reply("Usage: /ads dev blood <status|open|pass|kills N|enter|heads>")
            }
        }),
    )

    /** The boss bar's name while the Watcher is the one it is about. */
    private const val BAR_NAME = "§c§lThe Watcher"
}
