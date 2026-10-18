package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.run.SecretKind
import kotlin.random.Random

/*
 * The three rooms with a Redstone Key: the key is a skull that is only picked up (no item, no count), and the secret is counted when
 * the key is USED on its socket (a redstone block) a while later. Using it plays a piston sound, puts a head on the socket, turns the
 * room's redstone-block door to air and in two of the rooms makes chests appear.
 *
 * The positions are in the room's canonical frame (what the rooms pack's designs use) and come from five recorded uses of the socket,
 * converted back through each room's placement; the cells of the door are the redstone blocks the pack's own volume holds. The rooms
 * pack has no field for them (its loader belongs to the layout work item), so the table lives here. Pure.
 */

/** A chest that appears when the key is used. [counts] false: it is loot only (the room's printed count is already met by the socket). */
class RevealChestSpec(val id: String, val pos: Pos, val facing: String, val counts: Boolean)

/** One key room in the room's canonical frame. */
class KeyRoomSpec(
    val designId: String,
    /** Where the key skull can stand (one is picked per run); empty: the rooms pack already lists the key site(s), which become the gate. */
    val keySpots: List<Pos>,
    val socket: Pos,
    /** The head put on the socket: on its top (a floor head) or against one of its sides (a wall head facing [wallFacing]). */
    val head: Pos,
    val wallFacing: String?,
    /** The redstone blocks of the door, which turn to air. */
    val opens: List<Pos>,
    val chests: List<RevealChestSpec>,
)

/** The head put on the socket, in the world. [wallFacing] is null for a floor head, whose [rotation] (0 to 15) is the room's quarter turn. */
class HeadCell(val pos: Pos, val wallFacing: String?, val rotation: Int)

/** A chest that appears on use, in the world. */
class RevealChest(val site: SecretSite, val counts: Boolean)

/** The socket of a key room, in the world: the block clicked, the head that appears, the door cells and the chests. */
class SocketInfo(val head: HeadCell, val opens: List<Pos>, val chests: List<RevealChest>)

/** A key skull that is only a gate (picking it up counts nothing). */
class KeyGate(val room: Int, val key: String, val x: Int, val y: Int, val z: Int, val quarterTurns: Int)

object RedstoneKeys {
    private val CRYPT_DOOR = listOf(Pos(12, 70, 15), Pos(13, 70, 15), Pos(14, 70, 15), Pos(15, 70, 15), Pos(13, 69, 15), Pos(14, 69, 15), Pos(15, 69, 15))

    val ROOMS: List<KeyRoomSpec> = listOf(
        KeyRoomSpec("Redstone_Crypt", emptyList(), Pos(26, 72, 6), Pos(26, 73, 6), null, CRYPT_DOOR, emptyList()),
        KeyRoomSpec(
            "Redstone_Key", listOf(Pos(10, 70, 26), Pos(19, 66, 7)), Pos(27, 68, 7), Pos(27, 69, 7), null,
            listOf(Pos(18, 68, 23), Pos(19, 68, 23), Pos(20, 68, 23), Pos(21, 68, 23)),
            listOf(RevealChestSpec("KC", Pos(28, 62, 23), "south", counts = true)),
        ),
        KeyRoomSpec(
            "Golden_Oasis", emptyList(), Pos(10, 71, 3), Pos(10, 71, 4), "south",
            listOf(Pos(26, 70, 25), Pos(27, 70, 25), Pos(28, 70, 25)),
            listOf(RevealChestSpec("R1", Pos(12, 63, 23), "north", counts = false), RevealChestSpec("R2", Pos(13, 63, 25), "north", counts = false)),
        ),
    )

    fun forDesign(id: String?): KeyRoomSpec? = id?.let { d -> ROOMS.firstOrNull { it.designId == d } }

    /** The key skull spot of a run: the pack's, or one of the spec's by the seed. */
    fun pickKeySpot(spec: KeyRoomSpec, random: Random): Pos? = if (spec.keySpots.isEmpty()) null else spec.keySpots[random.nextInt(spec.keySpots.size)]

    /** The socket resolved through [placement] into the world. [room] and [quarterTurns] are for the chest sites it makes. */
    fun resolve(spec: KeyRoomSpec, room: Int, placement: Placement): SocketInfo {
        val q = placement.quarterTurns
        fun world(p: Pos) = placement.toWorld(p.x, p.y, p.z).let { Pos(it.x, it.y, it.z) }
        val head = HeadCell(world(spec.head), spec.wallFacing?.let { SecretPlan.rotateFacing(it, q) }, Math.floorMod(q * 4, 16))
        val chests = spec.chests.map { c ->
            val w = world(c.pos)
            RevealChest(SecretSite(room, c.id, SecretKind.CHEST, w.x, w.y, w.z, SecretPlan.rotateFacing(c.facing, q), null, false, q, false, hidden = true), c.counts)
        }
        return SocketInfo(head, spec.opens.map(::world), chests)
    }

}
