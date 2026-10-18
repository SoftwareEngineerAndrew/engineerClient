package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomSecret
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.SecretKind
import kotlin.random.Random

/*
 * Where every secret of a dungeon stands, in the WORLD frame, resolved once from the plan (the rooms pack's secrets in the
 * canonical frame plus each room's placement). Pure: no Minecraft types, so the whole of it is unit-tested headless.
 */

/**
 * One secret the player can find: a chest, a locked chest, an item on the floor, a bat, a wither essence skull or the redstone key
 * skull. [facing] is the chest's facing in the WORLD (already rotated); [lever] is the world position of the lever that opens a
 * locked chest. [baked] says the rooms pack's block volume already holds it (the layout module pastes it); an unbaked one is put
 * into the world by this module when a player comes near ([SecretsModule]).
 */
class SecretSite(
    val room: Int,
    val id: String,
    val kind: SecretKind,
    val x: Int, val y: Int, val z: Int,
    val facing: String?,
    val lever: Pos?,
    val baked: Boolean,
    /** The room's rotation in quarter turns, for rotating block states placed here. */
    val quarterTurns: Int,
    /** From the supplemental file: positions that disagree with the room's own count lose to the main data. */
    val supplemental: Boolean,
    /** The socket of a key room: this site is the redstone block the key is used on (the secret counts there, not at the key). */
    val socket: SocketInfo? = null,
    /** A chest that does not exist until something reveals it (a key room's door opening). */
    val hidden: Boolean = false,
) {
    /** The key this site goes by in the book and in a savepoint. */
    val key: String get() = "$room:$id"

    fun at(px: Int, py: Int, pz: Int) = x == px && y == py && z == pz
}

/**
 * The secrets of one room: what can be placed, and the number the bars print. [gates] are a key room's key skulls (picked up, not
 * counted); [bonus] are chests that appear with the key but are loot only (the printed count is already met without them).
 */
class RoomSecrets(val room: Int, val max: Int, val sites: List<SecretSite>, val gates: List<KeyGate> = emptyList(), val bonus: List<SecretSite> = emptyList()) {
    /** How many secrets this room can really hold (never more than [max]). */
    val placeable: Int get() = sites.size

    /** The site standing exactly at this block, or null. */
    fun siteAt(x: Int, y: Int, z: Int): SecretSite? = sites.firstOrNull { it.at(x, y, z) }

    /** The key skull gate at this block, or null. */
    fun gateAt(x: Int, y: Int, z: Int): KeyGate? = gates.firstOrNull { it.x == x && it.y == y && it.z == z }

    /** The loot-only chest at this block, or null. */
    fun bonusAt(x: Int, y: Int, z: Int): SecretSite? = bonus.firstOrNull { it.at(x, y, z) }

    /** How many the bars count that the data cannot place; they are credited when the last placeable one is found. */
    val missing: Int get() = (max - sites.size).coerceAtLeast(0)
}

/** All rooms' secrets for a plan. */
class SecretPlan(val rooms: List<RoomSecrets>) {
    private val byKey: Map<String, SecretSite> = rooms.flatMap { it.sites + it.bonus }.associateBy { it.key }

    fun room(index: Int): RoomSecrets? = rooms.getOrNull(index)
    fun site(key: String): SecretSite? = byKey[key]
    val all: Collection<SecretSite> get() = byKey.values

    /** The site standing exactly at this block of [room], or null. */
    fun siteAt(room: Int, x: Int, y: Int, z: Int): SecretSite? = rooms.getOrNull(room)?.siteAt(x, y, z)

    fun gateAt(room: Int, x: Int, y: Int, z: Int): KeyGate? = rooms.getOrNull(room)?.gateAt(x, y, z)
    fun bonusAt(room: Int, x: Int, y: Int, z: Int): SecretSite? = rooms.getOrNull(room)?.bonusAt(x, y, z)

    /** Every key skull gate of the dungeon. */
    val gates: List<KeyGate> get() = rooms.flatMap { it.gates }

    companion object {
        /** The facing words in clockwise order seen from above (x east, z south): a quarter turn clockwise moves one step along it. */
        private val CLOCKWISE = listOf("north", "east", "south", "west")

        /** A canonical facing word turned [quarterTurns] clockwise quarter turns; unknown words and null stay as they are. */
        fun rotateFacing(facing: String?, quarterTurns: Int): String? {
            val i = CLOCKWISE.indexOf(facing?.lowercase())
            if (i < 0) return facing
            return CLOCKWISE[Math.floorMod(i + quarterTurns, 4)]
        }

        fun kindOf(type: String): SecretKind? = when (type) {
            "chest" -> SecretKind.CHEST
            "lockedChest" -> SecretKind.LOCKED_CHEST
            "item" -> SecretKind.ITEM
            "bat" -> SecretKind.BAT
            "essence" -> SecretKind.ESSENCE
            "redstoneKey" -> SecretKind.REDSTONE_KEY
            else -> null
        }

        /**
         * Resolves the plan's rooms. A room without a design or a placement (a headless test plan) has no sites. When the data
         * lists more secrets than the room's maximum (one design does), the first [RoomSecrets.max] are kept, the supplemental
         * ones last. When a secret names alternative spots (`alt`), [random] picks one of them, so the same seed gives the
         * same spot.
         */
        fun build(plan: DungeonPlan, random: Random): SecretPlan {
            val rooms = plan.rooms.map { r ->
                val design = r.design
                val placement = r.placement
                if (design == null || placement == null) return@map RoomSecrets(r.index, r.secretsMax, emptyList())
                val q = placement.quarterTurns
                val all = design.secrets.mapNotNull { s -> site(r.index, s, placement, q, random) }
                // Main data first, supplemental last, original order inside each group; then cap at the room's maximum.
                val kept = all.sortedBy { if (it.supplemental) 1 else 0 }.take(r.secretsMax)
                val spec = RedstoneKeys.forDesign(design.id) ?: return@map RoomSecrets(r.index, r.secretsMax, kept)
                keyRoom(r.index, r.secretsMax, spec, placement, random, kept)
            }
            return SecretPlan(rooms)
        }

        /**
         * A room with a Redstone Key: the key skull becomes a gate (picking it up counts nothing) and the secret is the socket the key is
         * used on, with the chest that opening reveals when the room counts it. Rooms whose data already lists the key skull keep that
         * spot as the gate; the others get one of the spec's spots by the seed.
         */
        private fun keyRoom(room: Int, max: Int, spec: KeyRoomSpec, placement: Placement, random: Random, kept: List<SecretSite>): RoomSecrets {
            val q = placement.quarterTurns
            val socket = RedstoneKeys.resolve(spec, room, placement)
            val gates = ArrayList<KeyGate>()
            for (k in kept) if (k.kind == SecretKind.REDSTONE_KEY) gates += KeyGate(room, k.key, k.x, k.y, k.z, q)
            if (gates.isEmpty()) RedstoneKeys.pickKeySpot(spec, random)?.let { p -> placement.toWorld(p.x, p.y, p.z).let { gates += KeyGate(room, "$room:KEY", it.x, it.y, it.z, q) } }
            val p = placement.toWorld(spec.socket.x, spec.socket.y, spec.socket.z)
            val site = SecretSite(room, "SK", SecretKind.REDSTONE_KEY, p.x, p.y, p.z, null, null, false, q, false, socket = socket)
            val chestCells = socket.chests.map { Triple(it.site.x, it.site.y, it.site.z) }.toSet()
            val own = kept.filter { it.kind != SecretKind.REDSTONE_KEY && Triple(it.x, it.y, it.z) !in chestCells }
            val counting = (own + site + socket.chests.filter { it.counts }.map { it.site }).take(max)
            return RoomSecrets(room, max, counting, gates, socket.chests.filter { !it.counts }.map { it.site })
        }

        private fun site(room: Int, s: RoomSecret, placement: Placement, q: Int, random: Random): SecretSite? {
            val kind = kindOf(s.type) ?: return null
            val choices = listOf(s.pos) + s.alt
            val p = if (choices.size > 1) choices[random.nextInt(choices.size)] else s.pos
            val w = placement.toWorld(p.x, p.y, p.z)
            val lever = s.lever?.let { l -> placement.toWorld(l.x, l.y, l.z).let { Pos(it.x, it.y, it.z) } }
            return SecretSite(room, s.id, kind, w.x, w.y, w.z, rotateFacing(s.facing, q), lever, s.baked, q, s.src == "supplemental")
        }
    }
}
