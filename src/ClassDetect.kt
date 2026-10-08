package com.engineerclient

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

/**
 * Own-class detection, for Party Finder Stats' mark on your class. Odin already parses the
 * dungeon tab list into DungeonUtils.dungeonTeammates (class + level per player); we only watch
 * the entry that is us, and stash the last class seen so it is known outside a dungeon too.
 */
object ClassDetect {

    var detected: DungeonClass? = null
        private set

    fun reset() {
        detected = null
    }

    /** Called every ~second; only does anything in a dungeon. */
    fun poll() {
        if (!DungeonUtils.inDungeons) return
        val clazz = DungeonUtils.currentDungeonPlayer.clazz
        if (clazz == DungeonClass.EMPTY || clazz == detected) return

        detected = clazz
        EngineerClient.logger.info("[ec] detected own class from tab: ${clazz.name}")
        if (EcConfig.data.lastKnownClass != clazz.name) {
            EcConfig.data.lastKnownClass = clazz.name
            EcConfig.save()
        }
    }

    /** Live tab detection, else the last class seen. Null: never seen one. */
    fun myClass(): DungeonClass? =
        detected ?: EcConfig.data.lastKnownClass?.let { stash ->
            DungeonClass.entries.firstOrNull { it != DungeonClass.EMPTY && (it.name == stash || stashName(it) == stash) }
        }

    /** The title-case spelling older config files stored ("Berserker", "Mage", ...). */
    private fun stashName(clazz: DungeonClass): String =
        if (clazz == DungeonClass.BERSERK) "Berserker" else clazz.name.lowercase().replaceFirstChar(Char::titlecase)
}
