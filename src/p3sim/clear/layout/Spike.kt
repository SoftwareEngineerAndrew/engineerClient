package com.engineerclient.p3sim.clear.layout

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Fight
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimServer
import com.engineerclient.p3sim.SimWorld
import com.engineerclient.p3sim.pack.PackSet
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.LayoutSource
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.WorldRegions
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer

/**
 * The layout builder's feasibility spike and its dev commands (`/ads dev rooms`, `/ads dev layout`, `/ads dev probe`): build rooms into a
 * fresh ADS world, put the player in one, and have the client ask the UNMODIFIED Odin what it makes of them.
 *
 * Flow: the client thread (command) loads the packs off-thread, makes a [LayoutRegion], registers it with [WorldRegions] and rebuilds the
 * ADS world, so the chunks generate from the region (Odin scans a room once, when its centre chunk loads). When the player joins the new
 * world, the server teleports them to [pendingSpawn]. Meanwhile the client watches Odin's scanner and reports once everything expected is
 * named, or after [GIVE_UP_TICKS] with what is missing.
 */
object Spike {
    /** Where to put the player once the rebuilt world has them (server thread reads, client thread writes). */
    @Volatile var pendingSpawn: StandSpot? = null

    /** What the probe expects Odin to find. */
    @Volatile var expected: List<ExpectedRoom> = emptyList()

    /** The region of the last build, so the stats can be read afterwards. */
    @Volatile var lastRegion: LayoutRegion? = null
    @Volatile var lastLabel: String = ""

    /** Client ticks the probe waits after the world has opened before it reports what is missing. */
    const val GIVE_UP_TICKS = 400
    /** Ticks after joining that the player is teleported (the sim's own join puts them in the boss lobby and welcomes them 20 ticks later). */
    private const val TELEPORT_DELAY = 40

    // ------------------------------------------------------------------ building

    class Prepared(val built: LayoutBuilder.Built, val expected: List<ExpectedRoom>, val spawn: StandSpot?, val label: String, val textures: List<com.engineerclient.p3sim.pack.rooms.SkullTexture>)

    /** Starts a build from the client thread: [prepare] runs off-thread (it decodes volumes), the world is rebuilt on the client thread. */
    fun launch(reply: (String) -> Unit, prepare: (PackSet, (String) -> Unit) -> Prepared?) {
        Packs.loadAsync().thenAccept { set ->
            val lines = ArrayList<String>()
            val prepared = try {
                if (!set.canBuildDungeon) { set.userLines().ifEmpty { listOf("The room library and the layout pool are needed.") }.forEach { lines += it }; null }
                else prepare(set) { lines += it }
            } catch (t: Throwable) { EngineerClient.logger.error("[ads] layout spike failed", t); lines += "That failed: ${t.message}"; null }
            Minecraft.getInstance().execute {
                lines.forEach(reply)
                if (prepared != null) start(prepared, reply)
            }
        }
    }

    private fun start(p: Prepared, reply: (String) -> Unit) {
        val region = LayoutRegion("dungeon", p.built.instances, p.built.edits, p.textures)
        WorldRegions.register(region)
        lastRegion = region; lastLabel = p.label
        pendingSpawn = p.spawn
        expected = p.expected
        watch()
        for (w in p.built.warnings) reply(w)
        reply("Building ${p.label}: ${p.built.instances.size} rooms, ${p.built.edits.size} door and wall edits. Rebuilding the ADS world...")
        SimWorld.rebuild()
    }

    /** `/ads dev rooms`: Andesite (1x1), Blue Skulls (1x1, turned west) and Pirate (an L, turned north) side by side. */
    fun prepareRooms(set: PackSet, say: (String) -> Unit): Prepared? {
        val rooms = set.rooms ?: return null
        fun design(id: String, name: String) = rooms.design(id) ?: rooms.designsByName(name).firstOrNull()
        val andesite = design("Andesite", "Andesite"); val skulls = design("Blue_Skulls", "Blue Skulls"); val pirate = design("Pirate", "Pirate")
        if (andesite == null || skulls == null || pirate == null) {
            say("The room library lacks ${listOfNotNull("Andesite".takeIf { andesite == null }, "Blue Skulls".takeIf { skulls == null }, "Pirate".takeIf { pirate == null }).joinToString()}.")
            return null
        }
        val picks = listOf(
            Triple(andesite, Rot.SOUTH, Placement.tilesFor(andesite.shape, Rot.SOUTH, 1, 1)),
            Triple(skulls, Rot.WEST, Placement.tilesFor(skulls.shape, Rot.WEST, 2, 1)),
            Triple(pirate, Rot.NORTH, Placement.tilesFor(pirate.shape, Rot.NORTH, 1, 2)),
        )
        val instances = ArrayList<RoomInstance>(); val expect = ArrayList<ExpectedRoom>()
        for ((d, rot, tiles) in picks) {
            val vol = rooms.volume(d) ?: run { say("The blocks of ${d.name} could not be read from the room library."); return null }
            val placement = d.place(rot, tiles)
            instances += RoomInstance(d, vol, placement)
            expect += ExpectedRoom(d.name, rot.name, tiles.map { it.x to it.z }.toSet())
        }
        // Two doors, to see the carving: a wither door between Andesite and Blue Skulls, a plain doorway between Blue Skulls and Pirate.
        val doors = mapOf(Slot('E', 1, 1) to DoorKind.WITHER, Slot('S', 2, 1) to DoorKind.NORMAL)
        val built = LayoutBuilder.assemble(instances, doors, rooms)
        val spawn = SpawnFinder.find(instances[0], 1, 1)
        return Prepared(built, expect, spawn, "the three-room spike", rooms.textures)
    }

    /** `/ads dev layout <id|random|seed N>`: a whole recorded layout. */
    fun prepareLayout(config: RunConfig): (PackSet, (String) -> Unit) -> Prepared? = { set, say ->
        val rooms = set.rooms!!
        when (val planned = LayoutModule.plan(config, set)) {
            is DungeonPlan.Result.Failed -> { say(planned.failure.message); null }
            is DungeonPlan.Result.Ok -> {
                val plan = planned.plan
                when (val b = LayoutBuilder.build(plan, rooms)) {
                    is LayoutBuilder.Result.Failed -> { say(b.message); null }
                    is LayoutBuilder.Result.Ok -> {
                        val entrance = plan.entrance
                        val inst = entrance?.let { e -> b.built.instances.firstOrNull { it.placement.tiles() == e.tiles } }
                        val spawn = if (entrance != null && inst != null) SpawnFinder.find(inst, entrance.tiles.first().x, entrance.tiles.first().z) else null
                        val expect = plan.rooms.mapNotNull { r -> r.design?.let { ExpectedRoom(it.name, r.rotation.name, r.tiles.map { t -> t.x to t.z }.toSet()) } }
                        say("Layout ${plan.layoutId} (seed ${plan.seed}): ${plan.rooms.size} rooms, ${plan.doors.count { it.kind.connected }} doorways.")
                        Prepared(b.built, expect, spawn, plan.layoutId, rooms.textures)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ server side

    /** Registered once from [LayoutModule.onInit]. */
    fun installServerHooks() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val spot = pendingSpawn ?: return@register
            if (!SimServer.isSim(server)) return@register
            val player = handler.player
            if (!com.engineerclient.p3sim.Sim.isHost(player)) return@register
            pendingSpawn = null
            Fight.later(TELEPORT_DELAY, "layout spike teleport") {
                Sim.asPlayer(player) { Sim.tp(player, spot.x, spot.y, spot.z, player.yRot, 0f) }
                Sim.note("Standing in the first room. §f/ads dev probe§7 asks Odin what it sees.")
            }
        }
        // The dungeon region belongs to the world it was registered for (ARCHITECTURE 2): when the sim's server stops its used regions go.
        ServerLifecycleEvents.SERVER_STOPPED.register { s ->
            if (s is IntegratedServer && s.worldData.levelName == SimWorld.NAME) WorldRegions.worldStopped()
        }
    }

    // ------------------------------------------------------------------ client side: the probe

    private var watching = false
    private var watchTicks = 0
    private var lastCurrent: String? = null
    private val entered = ArrayList<String>()
    private var retried = 0

    /** The room entries the report remembers (the last ones). */
    private const val MAX_ENTERED = 64
    private var rescanned = 0

    private fun watch() { watching = true; watchTicks = 0; lastCurrent = null; entered.clear(); retried = 0; rescanned = 0 }

    /** Registered once from [LayoutModule.onInit]; runs every client tick. */
    fun installClientHooks() {
        ClientTickEvents.END_CLIENT_TICK.register { EngineerClient.safely("ads layout probe") { clientTick() } }
    }

    private fun clientTick() {
        val mc = Minecraft.getInstance()
        // Dev probe state: nothing to do (and no reflection into Odin) unless a probe is watching.
        if (!watching || mc.level == null || !P3Sim.inSim) return
        // Odin's room-enter state changes (WorldScan.currentRoom): counted for the report. The bridge still forces "in boss" while no run
        // is on, and Odin reports no room entries in the boss; see the report line.
        val cur = OdinProbe.currentRoom()
        if (cur != lastCurrent) { lastCurrent = cur; if (cur != null) { entered += cur; if (entered.size > MAX_ENTERED) entered.removeAt(0) } }
        watchTicks++
        if (watchTicks % 20 != 0) return
        rescanned += OdinProbe.rescanUnnamed()
        retried += OdinProbe.retryRotations()
        val lines = ProbeReport.compare(expected, OdinProbe.rooms())
        if (ProbeReport.allOk(lines) || watchTicks >= GIVE_UP_TICKS) {
            watching = false
            for (l in report(lines)) EngineerClient.msg(l)
        }
    }

    /** The probe's answer now: for each expected room what Odin says, then the state of Odin's scan and of the last build. */
    fun report(given: List<ProbeLine>? = null): List<String> {
        if (given == null) { rescanned += OdinProbe.rescanUnnamed(); retried += OdinProbe.retryRotations() }
        val lines = given ?: ProbeReport.compare(expected, OdinProbe.rooms())
        val out = ArrayList<String>()
        if (expected.isEmpty()) out += "Nothing was built this session; use /ads dev rooms or /ads dev layout first. Odin holds ${OdinProbe.rooms().size} rooms."
        val ok = lines.count { it.ok }
        out += (if (lines.isNotEmpty() && ok == lines.size) "§aOdin recognises all $ok built rooms." else "§eOdin recognises $ok of ${lines.size} built rooms.")
        // A full layout prints only the failures (and the first few good ones); the spike prints all.
        val show = if (lines.size <= 6) lines else lines.filter { !it.ok } + lines.filter { it.ok }.take(3)
        for (l in show) out += (if (l.ok) "§a ok §7" else "§c no §7") + l.text
        out += "§7Odin: in dungeon ${OdinProbe.inDungeons()}, in boss ${OdinProbe.inBoss()}, standing in ${OdinProbe.currentRoom() ?: "no room"}; rooms entered: ${entered.joinToString().ifEmpty { "none" }}."
        if (rescanned > 0) out += "§7The probe offered $rescanned tile centre chunk(s) to Odin's world scan again: they had loaded before the sim told Odin it was in a dungeon, and Odin looks at a chunk only once."
        if (retried > 0) out += "§7The probe asked Odin's own rotation check again $retried time(s) for rooms it had named but not turned (their corner marker was in a chunk that had not loaded yet)."
        if (OdinProbe.inBoss()) out += "§7Odin reports room entries only outside the boss, and the sim's bridge still says it is in the boss until a run sets its stage; names and rotations do not depend on that."
        lastRegion?.let { out += "§7Last build ($lastLabel): ${it.stats()}." }
        return out
    }
}
