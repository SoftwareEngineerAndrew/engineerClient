package com.engineerclient.p3sim.state

import com.engineerclient.p3sim.clear.state.BodyBlocks
import com.engineerclient.p3sim.clear.state.Cue
import com.engineerclient.p3sim.clear.state.StateEffects
import com.engineerclient.p3sim.pack.Fixtures
import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomDesign
import com.engineerclient.p3sim.pack.rooms.RoomSecret
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.KeyKind
import com.engineerclient.p3sim.run.PlacedDoor
import com.engineerclient.p3sim.run.PlacedRoom
import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.TestPlans
import java.util.UUID

/**
 * An invented seven-room dungeon in one row of tiles: entrance, A, B, Fairy, C, D, blood. Doors: entrance, wither (A-B), the free
 * Fairy door (B-Fairy), wither (Fairy-C), wither (C-D), blood (D-blood). Path = the row, so the key rooms are A, B, C (D gives the
 * blood key): three paid doors, three keys.
 */
object StatePlans {
    private fun room(i: Int, name: String, type: String, tx: Int, tz: Int, secrets: Int) =
        """{"i":$i,"name":"$name","family":"$name","type":"$type","shape":"1x1","tiles":[[$tx,$tz]],"rot":"NORTH","secretsMax":$secrets}"""

    private fun door(axis: Char, a: String, b: String, ra: Int, rb: Int, kind: String) =
        """{"axis":"$axis","a":$a,"b":$b,"ra":$ra,"rb":$rb,"kind":"$kind"}"""

    val layoutJson = """
    {"format":"ads-layouts","version":1,"layouts":[
     {"id":"row-1","floor":"F7","doorsComplete":true,"path":[0,1,2,3,4,5,6],"playable":true,"party":[],
      "rooms":[
       ${room(0, "Entrance", "entrance", 0, 0, 0)},
       ${room(1, "Alpha", "normal", 1, 0, 2)},
       ${room(2, "Bravo", "normal", 2, 0, 0)},
       ${room(3, "Fairy", "fairy", 3, 0, 0)},
       ${room(4, "Charlie", "normal", 4, 0, 3)},
       ${room(5, "Delta", "normal", 5, 0, 1)},
       ${room(6, "Blood", "blood", 5, 1, 0)}
      ],
      "doors":[
       ${door('E', "[0,0]", "[1,0]", 0, 1, "entrance")},
       ${door('E', "[1,0]", "[2,0]", 1, 2, "wither")},
       ${door('E', "[2,0]", "[3,0]", 2, 3, "fairy")},
       ${door('E', "[3,0]", "[4,0]", 3, 4, "wither")},
       ${door('E', "[4,0]", "[5,0]", 4, 5, "wither")},
       ${door('S', "[5,0]", "[5,1]", 5, 6, "blood")}
      ],
      "keys":[],"puzzles":[]}
    ]}
    """.trimIndent()

    fun plan(): DungeonPlan {
        val pack = LayoutPack.fromJson(Fixtures.json(layoutJson))!!
        return (DungeonPlan.build(pack.layout("row-1")!!, 1, null) as DungeonPlan.Result.Ok).plan
    }

    fun run(seed: Long = 7, plan: DungeonPlan = plan()): Run = Run("r", RunConfig(seed = seed), plan, TestPlans.emptyPacks(), systems = emptyList())

    /** The name of the invented trap room of [trapPlan]. */
    const val TRAP_NAME = "Test Trap"

    /** Canonical cells of the invented trap room's two chests: the one that finishes the room, and another. */
    val END_CHEST = Pos(26, 90, 14)
    val SIDE_CHEST = Pos(5, 67, 12)

    /**
     * The same seven-room row with room 2 (Bravo) replaced by an invented 1x1 trap room placed with [rot]: a design with an end chest
     * ([END_CHEST]) and a side chest ([SIDE_CHEST]), three secrets in all. Nothing here is recorded data.
     */
    fun trapPlan(rot: Rot): DungeonPlan {
        val base = plan()
        val old = base.rooms[2]
        fun chest(id: String, p: Pos) = RoomSecret(id, "chest", null, p, "south", null, emptyList(), false, "test")
        val (w, d) = Placement.footprint("1x1")
        val design = RoomDesign(
            "TestTrap", TRAP_NAME, "TestTrap", null, "trap", "1x1", Placement.canonicalTiles("1x1"), w, d, 1, 60, 100, Rot.entries, "1000", null, null, emptyList(), emptyList(),
            listOf(chest("s1", SIDE_CHEST), chest("s2", END_CHEST)), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, null, emptyList(), emptyList(),
        )
        val placement = design.place(rot, old.tiles)
        val trap = PlacedRoom(2, TRAP_NAME, design.family, design.id, design, RoomType.TRAP, "1x1", old.tiles, placement.rot, placement, 3, null, null, null, null, null)
        return DungeonPlan(base.layoutId, base.floor, base.seed, base.rooms.map { if (it.index == 2) trap else it }, base.doors, base.path, base.recordedKeys, base.puzzleNames)
    }

    fun addPlayer(run: Run, name: String, bot: Boolean = false, x: Double = 0.0, z: Double = 0.0): PlayerRecord {
        val p = PlayerRecord(UUID.nameUUIDFromBytes(name.toByteArray()), name, "HEALER", bot)
        p.x = x; p.y = 69.0; p.z = z
        run.state.players[p.uuid] = p
        return p
    }
}

/** Records what the controller does to the world. */
class RecordingEffects : StateEffects {
    val chat = ArrayList<Pair<Int, String>>()
    val sounds = ArrayList<Cue>()
    val bodies = ArrayList<Triple<Int, Int, BodyBlocks>>()
    val debris = ArrayList<Int>()
    val stands = LinkedHashMap<Int, Triple<KeyKind, Double, Double>>()
    var now = { 0 }

    override fun chat(text: String, to: UUID?) { chat += now() to text }
    override fun sound(cue: Cue, to: UUID?) { sounds += cue }
    override fun setBody(door: PlacedDoor, blocks: BodyBlocks, onlyFirst: Boolean) { bodies += Triple(now(), door.index, blocks) }
    override fun spawnStand(id: Int, kind: KeyKind, x: Double, z: Double, floorGuessY: Double): Double { stands[id] = Triple(kind, x, z); return floorGuessY }
    override fun spawnDoorDebris(door: PlacedDoor) { debris += door.index }
    override fun removeStand(id: Int) { stands.remove(id) }
    override fun clearStands() { stands.clear() }

    fun plainChat() = chat.map { it.first to com.engineerclient.p3sim.clear.state.StateText.plain(it.second) }
}
