package com.adm.utils.skyblock.dungeon

import com.adm.AdmMod.mc
import com.adm.AdmMod.scope
import com.adm.events.*
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.impl.dungeon.LeapMenu
import com.adm.features.impl.dungeon.LeapMenu.coreSorting
import com.adm.features.impl.dungeon.Mimic
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.utils.ServerUtils
import com.adm.utils.network.WebUtils.hasBonusPaulScore
import com.adm.utils.noControlCodes
import com.adm.utils.romanToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundTabListPacket
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.player.Player
import kotlin.jvm.optionals.getOrNull
import kotlin.math.abs
import kotlin.math.floor

object DungeonListener {

    var dungeonTeammates: ArrayList<DungeonPlayer> = ArrayList(5)
    var dungeonTeammatesNoSelf: List<DungeonPlayer> = ArrayList(4)
    var leapTeammates: List<DungeonPlayer> = ArrayList(4)

    private var watcherTick = -1
    private var bossTick = -1
    private var roomsTick = -100
    private var clearedTick = -100
    private var bossRoomCounted = false
    var roomTotal = 0
        private set

    // the blood room and the room counted on entering boss score from the start, until the tab has them
    val scoreRooms: Int
        get() = dungeonStats.completedRooms + (if (dungeonStats.bloodDone) 0 else 1) + (if (bossRoomCounted) 0 else 1)
    var dungeonStats = DungeonStats()
    var puzzles = ArrayList<Puzzle>()
    var floor: Floor? = null
    var inBoss = false
    var paul = false

    private fun getBoss(): Boolean = with(mc.player) {
        if (this == null || floor?.floorNumber == null) return false
        when (floor?.floorNumber) {
            1 -> x > -71 && z > -39
            in 2..4 -> x > -39 && z > -39
            in 5..6 -> x > -39 && z > -7
            7 -> x > -7 && z > -7
            else -> false
        }
    }

    init {
        on<TickEvent.End> {
            if (!DungeonUtils.inDungeons) return@on
            if (inBoss && !bossRoomCounted && ServerUtils.serverTicks - bossTick >= BOSS_ROOM_WAIT) {
                bossRoomCounted = true
                dungeonStats.bloodDone = true
                DungeonUtils.updateScore()
            }
            val newInBoss = getBoss()
            if (newInBoss == inBoss) return@on
            inBoss = newInBoss
            if (newInBoss && bossTick < 0) bossTick = ServerUtils.serverTicks
            DungeonUtils.updateScore()
            com.adm.events.core.SubscribeWhile.changed()
        }

        on<LevelEvent.Load> {
            Blessing.entries.forEach { it.reset() }
            dungeonTeammatesNoSelf = emptyList()
            dungeonStats = DungeonStats()
            watcherTick = -1
            bossTick = -1
            roomsTick = -100
            clearedTick = -100
            bossRoomCounted = false
            roomTotal = 0
            leapTeammates = emptyList()
            dungeonTeammates.clear()
            puzzles.clear()
            inBoss = false
            floor = null
            paul = false
            DungeonUtils.updateScore()
            com.adm.events.core.SubscribeWhile.changed()
        }

        on<RoomEnterEvent> {
            dungeonStats.knownSecrets = DungeonScan.rooms.sumOf { if (it.walkedInto) it.data?.maxSecrets ?: 0 else 0 }
        }

        onReceive<ClientboundPlayerInfoUpdatePacket> {
            if (!DungeonUtils.inDungeons) return@onReceive
            val tabListEntries = entries().mapNotNull { it.displayName?.string }.ifEmpty { return@onReceive }
            updateDungeonTeammates(tabListEntries)
            val rooms = dungeonStats.completedRooms
            updateDungeonStats(tabListEntries)
            if (dungeonStats.completedRooms != rooms) roomsChanged(dungeonStats.completedRooms > rooms)
            getDungeonPuzzles(tabListEntries)
            DungeonUtils.updateScore()
        }

        onReceive<ClientboundSetPlayerTeamPacket> {
            val text = parameters.getOrNull()?.let { it.playerPrefix.string.plus(it.playerSuffix.string).noControlCodes } ?: return@onReceive

            if (floor == null && text.contains("The Catacombs (")) floorRegex.find(text)?.groupValues?.get(1)?.let {
                scope.launch(Dispatchers.IO) { paul = hasBonusPaulScore() }
                val detectedFloor = Floor.valueOf(it)
                floor = detectedFloor
                FloorEnterEvent(detectedFloor).postAndCatch()
            }
            if (!DungeonUtils.inDungeons) return@onReceive

            if (text.startsWith("Cleared: ")) clearedRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                if (dungeonStats.percentCleared != it) {
                    dungeonStats.percentCleared = it
                    clearedTick = ServerUtils.serverTicks
                    updateRoomTotal()
                }
                DungeonUtils.updateScore()
            }

            if (text.startsWith(" Time: ")) dungeonStats.elapsedTime = timeRegex.find(text)?.groupValues?.get(1) ?: dungeonStats.elapsedTime
        }

        onReceive<ClientboundTabListPacket> {
            if (!DungeonUtils.inDungeons) return@onReceive
            val text = footer.string
            Blessing.entries.forEach { blessing ->
                blessing.regex.find(text)?.let { blessing.current = romanToInt(it.groupValues[1]) }
            }
        }

        on<MessageEvent.Chat> {
            if (!DungeonUtils.inDungeons) return@on
            if (message.startsWith("[BOSS] The Watcher: You have proven yourself") && expectingBloodRegex.matches(message) && watcherTick < 0) watcherTick = ServerUtils.serverTicks
            if (message.contains(" opened a ")) doorOpenRegex.find(message)?.let { dungeonStats.doorOpener = it.groupValues[1] }
            if (message.contains(" and became a ghost")) deathRegex.find(message)?.let { match ->
                dungeonTeammates.find { teammate ->
                    teammate.name == (match.groupValues[1].takeUnless { it == "You" } ?: mc.player?.name?.string)
                }?.deaths?.inc()
            }
            if (!dungeonStats.mimicKilled && DungeonUtils.isFloor(6, 7) && message.contains(MIMIC_CHARMED)) mimicFound()
            // A Charm kill sends only its CHARM line, not "A Prince falls".
            if (!dungeonStats.princeKilled && DungeonUtils.inClear && (message == PRINCE_FALLS || message.contains(PRINCE_CHARMED))) princeFound()

            if (!message.startsWith("Party > ")) return@on
            val partyMatch = partyMessageRegex.find(message) ?: return@on
            when (partyMatch.groupValues[2].lowercase()) {
                "mimic killed", "mimic slain", "mimic killed!", "mimic dead", "mimic dead!", ->
                    if (DungeonUtils.isFloor(6, 7)) dungeonStats.mimicKilled = true

                "prince killed", "prince slain", "prince killed!", "prince dead", "prince dead!", ->
                    dungeonStats.princeKilled = true

                "bat killed", "bat slain", "bat killed!", "bat dead", "bat dead!" ->
                    dungeonStats.batKillers.add(partyMatch.groupValues[1])

                "blaze done!", "blaze done", "blaze puzzle solved!" ->
                    puzzles.find { it == Puzzle.BLAZE }.let { it?.status = PuzzleStatus.Completed }

                else -> return@on
            }
            DungeonUtils.updateScore()
        }

        // The Mimic dies as a baby zombie; its death event counts for the score whether or not the Mimic module is on.
        on<EntityEvent.Event> {
            if (id != 3.toByte() || dungeonStats.mimicKilled || !DungeonUtils.isFloor(6, 7) || !DungeonUtils.inClear) return@on
            if ((entity as? Zombie)?.isBaby == true) mimicFound()
        }

        on<EntityEvent.Remove> {
            DungeonUtils.dungeonTeammates.forEach {
                val id = it.entity?.id ?: return@forEach
                if (entity.id == id) it.entity = null
            }
        }

        on<EntityEvent.Add> {
            if (entity.type == EntityTypes.PLAYER && entity.uuid.version() != 4 && DungeonUtils.inDungeons)
                DungeonUtils.dungeonTeammates.find { it.entity == null && it.name == entity.name.string }?.entity = entity as? Player
        }
    }

    private const val MIMIC_CHARMED = "You charmed a Mimic"
    private const val PRINCE_FALLS = "A Prince falls. +1 Bonus Score"
    private const val PRINCE_CHARMED = "You charmed the Prince"

    private fun princeFound() {
        dungeonStats.princeKilled = true
        DungeonUtils.updateScore()
        Mimic.announcePrince()
    }

    // the tab counts the blood room 61-110 server ticks after the Watcher's line, and it and the boss room within 20 ticks of entering boss
    private const val BLOOD_WAIT = 60
    private const val BOSS_ROOM_WAIT = 40

    private fun roomsChanged(increased: Boolean) {
        roomsTick = ServerUtils.serverTicks
        if (increased) {
            if (inBoss) {
                bossRoomCounted = true
                dungeonStats.bloodDone = true
            } else if (watcherTick >= 0 && roomsTick - watcherTick >= BLOOD_WAIT) dungeonStats.bloodDone = true
        }
        updateRoomTotal()
    }

    // the sidebar's Cleared % and the tab's Completed Rooms can update a second apart; only a pair from the same tick gives the total
    private fun updateRoomTotal() {
        val done = dungeonStats.completedRooms
        val cleared = dungeonStats.percentCleared
        if (done == 0 || cleared == 0 || abs(roomsTick - clearedTick) > 1) return
        roomTotal = floor(done / (cleared * 0.01).toFloat() + 0.4).toInt()
    }

    private fun mimicFound() {
        dungeonStats.mimicKilled = true
        DungeonUtils.updateScore()
        Mimic.announceMimic()
    }

    private fun getDungeonPuzzles(tabList: List<String>) {
        for (entry in tabList) {
            val (name, status, player) = puzzleRegex.find(entry)?.destructured ?: continue
            val puzzle = Puzzle.entries.find { it.displayName == name }?.takeIf { it != Puzzle.UNKNOWN } ?: continue
            if (puzzle !in puzzles) puzzles.add(puzzle)

            if (player.isNotEmpty()) puzzle.player = player
            puzzle.status = when (status) {
                "✖" -> PuzzleStatus.Failed
                "✔" -> PuzzleStatus.Completed
                "✦" -> PuzzleStatus.Incomplete
                else -> continue
            }
        }
    }

    private fun updateDungeonStats(text: List<String>) {
        for (entry in text) {
            with(dungeonStats) {
                secretsPercent = secretPercentRegex.find(entry)?.groupValues?.get(1)?.toFloatOrNull() ?: secretsPercent
                completedRooms = completedRoomsRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: completedRooms
                secretsFound = secretCountRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: secretsFound
                openedRooms = openedRoomsRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: openedRooms
                puzzleCount = puzzleCountRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: puzzleCount
                deaths = deathsRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: deaths
                crypts = cryptRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: crypts
                elapsedTime = timeRegex.find(entry)?.groupValues?.get(1) ?: elapsedTime
            }
        }
    }

    private fun updateDungeonTeammates(tabList: List<String>) = mc.execute {
        dungeonTeammates = DungeonUtils.getDungeonTeammates(dungeonTeammates, tabList)
        val me = mc.player?.name?.string
        dungeonTeammatesNoSelf = dungeonTeammates.filter { it.name != me }

        leapTeammates =
            when (LeapMenu.type) {
                LeapMenu.Sorting.DEFAULT -> coreSorting(dungeonTeammatesNoSelf.sortedBy { it.clazz.priority }).toList()
                LeapMenu.Sorting.CLASS -> dungeonTeammatesNoSelf.sortedWith(compareBy({ it.clazz.ordinal }, { it.name }))
                LeapMenu.Sorting.NAME -> dungeonTeammatesNoSelf.sortedBy { it.name }
                LeapMenu.Sorting.CUSTOM -> dungeonTeammatesNoSelf.sortedBy { DungeonUtils.customLeapOrder.indexOf(it.name.lowercase()).takeIf { index -> index != -1 } ?: Int.MAX_VALUE }
                LeapMenu.Sorting.NONE -> dungeonTeammatesNoSelf
            }
    }

    private val puzzleRegex = Regex("^ (\\w+(?: \\w+)*|\\?\\?\\?): \\[([✖✔✦])] ?(?:\\((\\w+)\\))?$")
    private val expectingBloodRegex = Regex("^\\[BOSS] The Watcher: You have proven yourself. You may pass.")
    private val doorOpenRegex = Regex("^(?:\\[\\w+] )?(\\w+) opened a (?:WITHER|Blood) door!")
    private val secretPercentRegex = Regex("^ Secrets Found: ([\\d.]+)%$")
    private val deathRegex = Regex("☠ (\\w{1,16}) .* and became a ghost\\.")
    private val timeRegex = Regex("^ Time: ((?:\\d+h ?)?(?:\\d+m ?)?\\d+s)$")
    private val completedRoomsRegex = Regex("^ Completed Rooms: (\\d+)$")
    private val clearedRegex = Regex("^Cleared: (\\d+)% \\(\\d+\\)$")
    private val secretCountRegex = Regex("^ Secrets Found: (\\d+)$")
    private val openedRoomsRegex = Regex("^ Opened Rooms: (\\d+)$")
    private val floorRegex = Regex("The Catacombs \\((\\w+)\\)$")
    private val partyMessageRegex = Regex("^Party > (?:\\[[^]]+] )*(\\w{1,16}): (.+)$")
    private val puzzleCountRegex = Regex("^Puzzles: \\((\\d+)\\)$")
    private val deathsRegex = Regex("^Team Deaths: (\\d+)$")
    private val cryptRegex = Regex("^ Crypts: (\\d+)$")

    data class DungeonStats(
        var secretsFound: Int = 0,
        var secretsPercent: Float = 0f,
        var knownSecrets: Int = 0,
        var crypts: Int = 0,
        var openedRooms: Int = 0,
        var completedRooms: Int = 0,
        var deaths: Int = 0,
        var percentCleared: Int = 0,
        var elapsedTime: String = "0s",
        private var _mimicKilled: Boolean = false,
        var princeKilled: Boolean = false,
        val batKillers: MutableSet<String> = mutableSetOf(),
        var batKilled: Boolean = false,
        var doorOpener: String = "Unknown",
        var bloodDone: Boolean = false,
        var puzzleCount: Int = 0,
    ) {
        val numBatsKilled: Int get() = batKillers.size

        var mimicKilled: Boolean
            get() = _mimicKilled
            set(value) {
                if (value && !DungeonUtils.isFloor(6, 7)) {
                    error("Attempted to set mimicKilled = true on floor that has no mimic")
                }
                _mimicKilled = value
            }
    }
}