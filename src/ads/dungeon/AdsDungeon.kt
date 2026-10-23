// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.dungeon

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

/** A dungeon class: its colour, the leap menu corner it goes in and its place in the leap menu's sort (Odin's DungeonClass). */
enum class AdsClass(val color: Int, val defaultQuadrant: Int, val priority: Int) {
    ARCHER(0xFFFFAA00.toInt(), 0, 2),
    BERSERK(0xFFAA0000.toInt(), 1, 0),
    HEALER(0xFFFF55FF.toInt(), 2, 2),
    MAGE(0xFF55FFFF.toInt(), 3, 2),
    TANK(0xFF00AA00.toInt(), 3, 1),
    EMPTY(0xFFFFFFFF.toInt(), 0, 0);

    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

/** One person in the run (Odin's DungeonPlayer): [mapX], [mapZ] and [yaw] come from the map item while their entity is too far to see. */
class AdsTeammate(val name: String, var clazz: AdsClass, val skin: PlayerSkin?) {
    var entity: Player? = null
    var isDead = false
    var mapX = 0
    var mapZ = 0
    var yaw = 0f
    val renderYaw: Float get() = entity?.yRot ?: yaw
}

/**
 * What ADS knows about the dungeon it's in, without Odin (Odin's DungeonListener and DungeonUtils, the parts the
 * minimap and the leap menu need): the floor from the sidebar's "The Catacombs (F7)", whether you're in the boss
 * from where you stand, and the party, their classes and the puzzle count from the tab list. Hypixel and ADS's own
 * worlds (whose sidebar and tab list are drawn the same way) read alike.
 */
object AdsDungeon {
    /** "E", "F1".."F7" or "M1".."M7"; null outside a dungeon. */
    var floor: String? = null
        private set

    /** 0 for the entrance, 1 to 7 for F1-F7 and M1-M7; -1 outside a dungeon. */
    val floorNumber: Int get() = floor?.let { if (it == "E") 0 else it.drop(1).toIntOrNull() } ?: -1

    val inDungeon: Boolean get() = floor != null

    var inBoss = false
        private set

    val inClear: Boolean get() = inDungeon && !inBoss

    var puzzleCount = 0
        private set

    /** Everyone in the run, you included, in the tab list's order. */
    var teammates: List<AdsTeammate> = emptyList()
        private set

    val teammatesNoSelf: List<AdsTeammate> get() = mc.player?.name?.string.let { self -> teammates.filter { it.name != self } }

    private val onReset = mutableListOf<() -> Unit>()
    private val onFloor = mutableListOf<(Int) -> Unit>()

    /** [action] runs whenever the world changes (Odin's LevelEvent.Load): every run starts from nothing. */
    fun onWorldChange(action: () -> Unit) { onReset += action }

    /** [action] runs once a run's floor is known, with its number (Odin's FloorEnterEvent). */
    fun onFloorEnter(action: (Int) -> Unit) { onFloor += action }

    private val floorRegex = Regex("The Catacombs \\((E|[FM][1-7])\\)")
    private val teammateRegex = Regex("^\\[(\\d+)] (?:\\[\\w+] )*(\\w+) .*?\\((\\w+)(?: (\\w+))*\\)$")
    private val puzzleCountRegex = Regex("^Puzzles: \\((\\d+)\\)$")
    private val formatting = Regex("§.")

    private var level: ClientLevel? = null
    private var ticks = 0
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        ClientTickEvents.END_CLIENT_TICK.register { EngineerClient.safely("ads dungeon") { tick() } }
    }

    private fun tick() {
        val now = mc.level
        if (now !== level) {
            level = now
            reset()
        }
        if (now == null) return
        ticks++
        if (floor == null && ticks % 10 == 0) readFloor()
        if (!inDungeon) return
        inBoss = bossNow()
        if (ticks % 10 == 0) readTab()
        for (t in teammates) if (t.entity?.isRemoved != false) t.entity = now.players().firstOrNull { it.name.string == t.name }
    }

    private fun reset() {
        floor = null
        inBoss = false
        puzzleCount = 0
        teammates = emptyList()
        for (action in onReset) EngineerClient.safely("ads dungeon reset") { action() }
    }

    /** The sidebar's title and lines, as the game draws them. */
    private fun readFloor() {
        val board = mc.level?.scoreboard ?: return
        val objective = board.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return
        val lines = sequenceOf(objective.displayName.string) + board.listPlayerScores(objective).asSequence().map { e ->
            PlayerTeam.formatNameForTeam(board.getPlayersTeam(e.owner()), e.ownerName()).string
        }
        val found = lines.firstNotNullOfOrNull { floorRegex.find(it.replace(formatting, ""))?.groupValues?.get(1) } ?: return
        floor = found
        val number = floorNumber
        for (action in onFloor) EngineerClient.safely("ads dungeon floor") { action(number) }
    }

    /** Odin's boss check: past the corner where each floor's boss room starts. */
    private fun bossNow(): Boolean {
        val p = mc.player ?: return false
        return when (floorNumber) {
            1 -> p.x > -71 && p.z > -39
            in 2..4 -> p.x > -39 && p.z > -39
            in 5..6 -> p.x > -39 && p.z > -7
            7 -> p.x > -7 && p.z > -7
            else -> false
        }
    }

    /** The party and the puzzle count from the tab list (Odin's getDungeonTeammates and dungeon stats). */
    private fun readTab() {
        val connection = mc.connection ?: return
        val rows = connection.listedOnlinePlayers.mapNotNull { it.tabListDisplayName?.string?.replace(formatting, "")?.trim() }
        if (rows.isEmpty()) return
        val list = teammates.toMutableList()
        for (row in rows) {
            puzzleCountRegex.find(row)?.let { puzzleCount = it.groupValues[1].toInt() }
            val (_, name, clazz) = teammateRegex.find(row)?.destructured ?: continue
            val known = list.firstOrNull { it.name == name }
            if (known != null) {
                if (known.clazz == AdsClass.EMPTY) known.clazz = classOf(clazz) ?: AdsClass.EMPTY
                known.isDead = clazz == "DEAD"
                continue
            }
            val info = connection.getPlayerInfo(name) ?: continue
            list += AdsTeammate(name, classOf(clazz) ?: continue, info.skin).also { it.entity = mc.level?.getPlayerByUUID(info.profile.id) }
        }
        teammates = list
    }

    private fun classOf(s: String): AdsClass? = AdsClass.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
}
