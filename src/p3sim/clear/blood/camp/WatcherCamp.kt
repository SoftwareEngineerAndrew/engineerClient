package com.engineerclient.p3sim.clear.blood.camp

import com.engineerclient.p3sim.clear.blood.BloodHost
import com.engineerclient.p3sim.clear.blood.WatcherStats
import com.engineerclient.p3sim.run.MobKilled
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.api.MobSpec
import com.engineerclient.p3sim.run.plugin.BloodCamp
import com.engineerclient.p3sim.run.plugin.BloodCampHost
import com.engineerclient.p3sim.run.plugin.BloodRoomSpec
import com.engineerclient.p3sim.clear.blood.BloodGeometry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The Watcher's camp on the blood room, as the real server plays it. It builds the wall (what each head wears), then feeds
 * [CampEngine] the host's clock and kills and puts the camp's mobs into the room through the host. The Watcher flies his legs
 * (facing the way he flies) and says his lines when his chat queue prints them; each thrown head leaves its stand on a straight
 * flight. Every change of the counted kills goes to the host, which steps the Watcher's health and bar and takes the defeat (the
 * fifteenth) as the start of the exit: the portal and the Watcher's removal count from it.
 *
 * This is the camp the blood room runs when no plug-in supplies another ([supports] needs a wall of at least [MIN_HEADS] heads).
 */
class WatcherCamp(private val host: BloodCampHost) : BloodCamp, CampPort {
    private val log = LoggerFactory.getLogger("engineerclient")
    private var engine: CampEngine? = null
    private var wall: List<WallStation> = emptyList()
    private var slots: List<Int> = emptyList()
    private lateinit var home: Vec3
    private var opening = CampLines.INTRO
    private var config = CampConfig()

    override val finished: Boolean get() = engine?.finished ?: false

    override fun start() {
        buildRoom()
        val look = WallLook.of(host.run.packs.rooms?.textures.orEmpty())
        wall = WallPlan.assign(stands(), host.random)
        for (st in wall) host.setHead(slots[st.slot], look.keyFor(st, host.random))
        engine = CampEngine(home, wall, host.random, this, config)
        opening = CampLines.opening(host.random)
        log.info("[ads] the Watcher's camp started: ${wall.count { it.cls == WallClass.STANDARD }} standard, ${wall.count { it.cls == WallClass.BOSS }} boss heads on ${wall.size} stands")
    }

    override fun tick() {
        val e = engine ?: return
        val alive = host.mobsAlive().mapTo(HashSet()) { it.toString() }
        e.step(host.ticksSinceOpen, alive)
    }

    override fun onEvent(event: RunEvent) {
        if (event is MobKilled) engine?.noteDeath(event.mob.id.toString(), host.ticksSinceOpen)
    }

    // ---- the room ------------------------------------------------------------------------------------------------------------

    /** Reads the wall's slots and the Watcher's home from the room. */
    private fun buildRoom() {
        val room = host.room
        slots = room.heads.sortedBy { it.index }.map { it.index }
        home = Vec3(room.watcherX, room.watcherY + WatcherStats.RISE, room.watcherZ)
    }

    /** A head hangs on the wall as a stand [BloodGeometry.STAND_ABOVE_CELL] above its block cell. */
    private fun stands(): List<Vec3> = host.room.heads.sortedBy { it.index }.map { Vec3(it.x + 0.5, it.y + BloodGeometry.STAND_ABOVE_CELL, it.z + 0.5) }

    // ---- CampPort ------------------------------------------------------------------------------------------------------------

    override fun spawn(landing: Landing): String? {
        val st = landing.station
        // The mob is added at the head's real end point, 5.5 to 7.6 blocks over the floor, and falls to the floor under gravity.
        val end = engine?.endOf(landing) ?: return null
        val floor = host.room.watcherY
        val y = floor + CampMobs.SPAWN_RISE_LOW + host.random.nextDouble() * (CampMobs.SPAWN_RISE_HIGH - CampMobs.SPAWN_RISE_LOW)
        val mob = CampMobs.roll(st, host.random)
        val extras = CampMobs.extras(mob, if (landing.initial) "initial" else "later")
        val kind = when (st.cls) {
            WallClass.GIANT -> KIND_GIANT
            WallClass.BOSS -> KIND_BOSS
            else -> KIND_MOB
        }
        val id = host.spawnMob(MobSpec(kind, end.x, y, end.z, roomIndex = host.room.roomIndex, hp = mob.hp, extras = extras))
        return id?.toString()
    }

    override fun killsCounted(total: Int) { host.watcherKills(total) }

    override fun flyHead(station: WallStation, end: Vec3, ticks: Int) { host.flyHead(slots[station.slot], end.x, end.y, end.z, ticks) }

    override fun watcherAt(x: Double, y: Double, z: Double, yaw: Float) { host.moveWatcher(x, y, z, yaw) }

    override fun say(line: CampMech.Line) {
        val text = if (line == CampMech.Line.INTRO) opening else CampLines.pick(line, host.random) ?: return
        host.watcherSays(text)
    }

    override fun log(text: String) { log.info("[ads] $text") }

    // ---- savepoints ----------------------------------------------------------------------------------------------------------

    override fun snapshot(): JsonObject? {
        val e = engine ?: return null
        return JsonObject().also { o ->
            o.addProperty("opening", opening)
            o.add("wall", JsonArray().also { a -> for (st in wall) a.add(JsonArray().also { p -> p.add(st.cls.name); p.add(st.mobName) }) })
            o.add("engine", e.snapshot())
        }
    }

    override fun restore(data: JsonObject) {
        buildRoom()
        val saved = data.getAsJsonArray("wall")?.map { it.asJsonArray } ?: return
        val stands = stands()
        if (saved.size != stands.size) { log.warn("[ads] the saved camp had a wall of ${saved.size} heads but this room has ${stands.size}; the camp starts over"); start(); return }
        wall = stands.mapIndexed { i, v -> WallStation(i, v, WallClass.valueOf(saved[i][0].asString), saved[i][1].asString) }
        val state = data.getAsJsonObject("engine") ?: return
        val order = state.getAsJsonArray("order").map { wall[it.asInt] }
        val e = CampEngine(home, wall, host.random, this, config, order)
        if (!e.restore(state)) { start(); return }
        engine = e
        opening = data.get("opening")?.asString ?: opening
        // The mobs the camp had made are the mob service's to bring back; the host must count them as this camp's again.
        (host as? BloodHost)?.let { h ->
            state.getAsJsonArray("mobs").forEach { m -> m.asJsonObject["h"]?.asString?.let { id -> try { h.adopt(UUID.fromString(id)) } catch (_: IllegalArgumentException) {} } }
        }
        val rest = e.watcherRest
        host.moveWatcher(rest.x, rest.y, rest.z)
    }

    companion object {
        /** Mob kinds handed to the mob service (specials outside its pack; it stands a plain mob in for a kind it lacks). */
        const val KIND_GIANT = "blood_giant"
        const val KIND_BOSS = "blood_boss"
        const val KIND_MOB = "blood_mob"

        /** A real wall has 30 heads; the camp needs at least the 4 + 11 it throws. */
        const val MIN_HEADS = 4 + CampMech.LATER_THROWS

        /** Whether the camp can run this room build. */
        fun supports(room: BloodRoomSpec): Boolean = room.heads.size >= MIN_HEADS
    }
}
