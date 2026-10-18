package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.p3sim.run.BonusKind
import com.engineerclient.p3sim.run.BonusScored
import com.engineerclient.p3sim.run.CryptDestroyed
import com.engineerclient.p3sim.run.RunBus
import com.engineerclient.p3sim.run.RunState
import com.engineerclient.p3sim.run.SecretFound
import com.engineerclient.p3sim.run.SecretKind
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID

/** What [SecretBook.claim] decided. */
sealed interface Claim {
    /** The secret counted. [site] is the data site it matched (null for one the data does not list, such as a puzzle's reward chest); [autoCredited] more were counted with it. */
    class Credited(val site: SecretSite?, val autoCredited: Int) : Claim
    /** It was counted before. */
    data object Duplicate : Claim
    /** The room already shows `M/M`; nothing more can be found there. */
    data object RoomFull : Claim
    /** There is no such room. */
    data object NoRoom : Claim
}

/**
 * The secret ledger of one run: which secrets were found, and the one place where the team, room and player counts of
 * [RunState] change (this module is their only writer). Everything the bars print comes from those counts; the HUD module reads
 * them, the doors module turns a room GREEN when its count reaches its maximum (`SecretFound` is posted after the counts moved).
 *
 * Counting rules (FORMAT-runstate 3, measured on the clears): a secret counts once; a room never counts past the maximum it prints
 * (Odin stops tracking a room whose printed M differs from its own); a secret the data cannot place is counted when the last
 * placeable one of the room is found ([autoCredit]), or when the room is cleared if the data places none of them.
 * Pure: no Minecraft types.
 */
class SecretBook(
    private val plan: SecretPlan,
    private val state: RunState,
    private val bus: RunBus,
    private val clock: () -> Int,
    /** `AUTO_CREDIT_MISSING_SECRETS` (the pack's named constant, a setting here). */
    private val autoCredit: () -> Boolean = { true },
) {
    private val found = LinkedHashSet<String>()
    private val anonymous = LinkedHashSet<String>()
    private val creditedRooms = LinkedHashSet<Int>()

    /** Rooms whose missing secrets were credited (each is logged once by the module). */
    val autoCreditedRooms: Set<Int> get() = creditedRooms

    fun isFound(site: SecretSite) = site.key in found
    val foundKeys: Set<String> get() = found

    /** The sites of [room] not yet found. */
    fun pending(room: Int): List<SecretSite> = plan.room(room)?.sites?.filter { it.key !in found }.orEmpty()

    /** How many secrets of [room] are still to find (by the bars' count). */
    fun remaining(room: Int): Int = state.room(room)?.let { it.secretsMax - it.secretsFound } ?: 0

    /**
     * Counts a secret of [room] found by [by] at (x, y, z). The site is the one standing at that block; else the nearest pending site
     * of the same [kind] (a bot that names no exact spot); else the secret is counted as unlisted (a puzzle's reward chest) once
     * per spot.
     */
    fun claim(room: Int, kind: SecretKind, by: UUID?, x: Int, y: Int, z: Int): Claim {
        val rr = state.room(room) ?: return Claim.NoRoom
        if (rr.secretsFound >= rr.secretsMax) return Claim.RoomFull
        val rs = plan.room(room)
        var site = rs?.siteAt(x, y, z)
        if (site == null) {
            site = rs?.sites?.filter { it.key !in found && it.kind == kind }?.minByOrNull { dist2(it, x, y, z) }
        }
        if (site != null) {
            if (!found.add(site.key)) return Claim.Duplicate
        } else if (!anonymous.add("$room:$x,$y,$z")) return Claim.Duplicate
        credit(room, kind, by, site?.x ?: x, site?.y ?: y, site?.z ?: z)
        var extra = 0
        if (autoCredit() && rs != null && rs.missing > 0 && rs.sites.isNotEmpty() && rs.sites.all { it.key in found }) extra = creditMissing(room, kind, by, x, y, z)
        return Claim.Credited(site, extra)
    }

    /**
     * A room whose data places no secret at all (or fewer than it prints, and none left) is cleared: its missing secrets count now.
     * Returns how many were counted. Does nothing when auto-credit is off, or for a room with placeable secrets still to find.
     */
    fun roomCleared(room: Int, by: UUID?, x: Int, y: Int, z: Int): Int {
        val rs = plan.room(room) ?: return 0
        if (!autoCredit() || rs.sites.isNotEmpty() || rs.max <= 0) return 0
        return creditMissing(room, SecretKind.CHEST, by, x, y, z)
    }

    /**
     * Counts everything still to find in [room] as found by [by] (a teammate who cleared it): the pending sites one by one, then
     * whatever the data cannot place. Returns how many were counted.
     */
    fun creditRemaining(room: Int, by: UUID?, centreX: Int, centreZ: Int): Int {
        val before = state.room(room)?.secretsFound ?: return 0
        for (s in pending(room)) claim(room, s.kind, by, s.x, s.y, s.z)
        val rr = state.room(room) ?: return 0
        if (rr.secretsFound < rr.secretsMax) creditMissing(room, SecretKind.CHEST, by, centreX, 70, centreZ)
        return rr.secretsFound - before
    }

    private fun creditMissing(room: Int, kind: SecretKind, by: UUID?, x: Int, y: Int, z: Int): Int {
        val rr = state.room(room) ?: return 0
        var n = 0
        while (rr.secretsFound < rr.secretsMax) { credit(room, kind, by, x, y, z); n++ }
        if (n > 0) creditedRooms += room
        return n
    }

    private fun credit(room: Int, kind: SecretKind, by: UUID?, x: Int, y: Int, z: Int) {
        val rr = state.room(room) ?: return
        val led = state.secrets
        rr.secretsFound++
        led.teamFound++
        if (by != null) {
            led.byPlayer.merge(by, 1, Int::plus)
            state.players[by]?.let { it.secretsFound++ }
        }
        if (kind == SecretKind.ESSENCE) led.witherEssence++
        bus.post(SecretFound(clock(), room, kind, by, x, y, z))
    }

    /** One more crypt tomb destroyed (uncapped: the score counts at most five). */
    fun crypt(room: Int, by: UUID?) {
        state.secrets.crypts++
        bus.post(CryptDestroyed(clock(), room, by))
    }

    /** A bonus mob died. Each of the three counts once toward the score. Returns false if that bonus was already scored. */
    fun bonus(kind: BonusKind, by: UUID?): Boolean {
        val led = state.secrets
        val first = when (kind) {
            BonusKind.MIMIC -> !led.mimicKilled.also { led.mimicKilled = true }
            BonusKind.PRINCE -> !led.princeKilled.also { led.princeKilled = true }
            BonusKind.BAT -> !led.batKilled.also { led.batKilled = true }
        }
        if (first) bus.post(BonusScored(clock(), kind, by))
        return first
    }

    // ---- savepoints ---------------------------------------------------------------------------------------------------------

    fun snapshot(): JsonObject = JsonObject().also { o ->
        o.add("found", JsonArray().also { a -> found.forEach(a::add) })
        o.add("anon", JsonArray().also { a -> anonymous.forEach(a::add) })
        o.add("credited", JsonArray().also { a -> creditedRooms.forEach(a::add) })
        o.add("rooms", JsonArray().also { a -> state.rooms.forEach { a.add(it.secretsFound) } })
        val led = state.secrets
        o.addProperty("team", led.teamFound)
        o.addProperty("crypts", led.crypts)
        o.addProperty("essence", led.witherEssence)
        o.addProperty("mimic", led.mimicKilled); o.addProperty("prince", led.princeKilled); o.addProperty("bat", led.batKilled)
        o.add("players", JsonObject().also { p -> led.byPlayer.forEach { (k, v) -> p.addProperty(k.toString(), v) } })
        o.add("blessings", JsonObject().also { p -> led.blessings.forEach { (k, v) -> p.addProperty(k, v) } })
    }

    /** Puts the ledger back exactly as [snapshot] saved it (a set, never an add, so it agrees with whatever the hub restored). */
    fun restore(o: JsonObject) {
        found.clear(); anonymous.clear(); creditedRooms.clear()
        o.getAsJsonArray("found")?.forEach { found += it.asString }
        o.getAsJsonArray("anon")?.forEach { anonymous += it.asString }
        o.getAsJsonArray("credited")?.forEach { creditedRooms += it.asInt }
        o.getAsJsonArray("rooms")?.forEachIndexed { i, e -> state.room(i)?.secretsFound = e.asInt }
        val led = state.secrets
        led.teamFound = o.get("team")?.asInt ?: led.teamFound
        led.crypts = o.get("crypts")?.asInt ?: led.crypts
        led.witherEssence = o.get("essence")?.asInt ?: led.witherEssence
        led.mimicKilled = o.get("mimic")?.asBoolean ?: false
        led.princeKilled = o.get("prince")?.asBoolean ?: false
        led.batKilled = o.get("bat")?.asBoolean ?: false
        led.byPlayer.clear()
        o.getAsJsonObject("players")?.entrySet()?.forEach { (k, v) -> runCatching { UUID.fromString(k) }.getOrNull()?.let { led.byPlayer[it] = v.asInt } }
        for (p in state.players.values) p.secretsFound = led.byPlayer[p.uuid] ?: 0
        led.blessings.clear()
        o.getAsJsonObject("blessings")?.entrySet()?.forEach { (k, v) -> led.blessings[k] = v.asInt }
    }

    private fun dist2(s: SecretSite, x: Int, y: Int, z: Int): Double {
        val dx = (s.x - x).toDouble(); val dy = (s.y - y).toDouble(); val dz = (s.z - z).toDouble()
        return dx * dx + dy * dy + dz * dz
    }

}
