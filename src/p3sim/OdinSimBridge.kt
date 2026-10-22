package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.impl.render.HidePlayers
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass as OdinClass

/**
 * Everything the sim does with Odin, in one place: telling Odin it is in F7's boss, its Hide Players, its leap
 * order, its i4 solver, and the splits, timers and Term Info that run on Odin. Only [P3Sim.odin] callers reach it, so
 * without Odin none of this (or anything of Odin's) is ever loaded. Nothing here takes or gives an Odin type: classes
 * go over by name.
 */
object OdinSimBridge {

    private fun odin(c: DungeonClass): OdinClass = OdinClass.valueOf(c.name)

    // ------------------------------------------------------------------ dungeon state

    private var bridged = false

    /**
     * Tells Odin it is in F7's boss with a party of five, every tick while in the sim (Odin clears
     * it all on each world load). Odin's dungeon features (terminal solver, leap menu, Simon Says,
     * splits...) then work in the sim as they do on Hypixel.
     */
    fun tick() {
        if (!P3Sim.inSim) {
            if (bridged) { bridged = false; unbridge() }
            return
        }
        bridged = true
        val me = mc.player?.name?.string ?: return
        setArea(Island.Dungeon)
        DungeonListener.floor = Floor.F7
        DungeonListener.inBoss = true
        // The party as the sim's server has it (players and bots, in your leap order); none until it says (the server's
        // own lists aren't this thread's to read).
        val sent = PartyClient.team.takeIf { PartyClient.me != null && it.isNotEmpty() } ?: return
        val names = sent.joinToString { "${it.name}/${it.clazz}" }
        val mine = PartyClient.me ?: return
        if (DungeonListener.dungeonTeammates.size != 1 + sent.size || DungeonListener.dungeonTeammates.none { it.name == me } || teamClass != mine.ordinal || roster != names) {
            teamClass = mine.ordinal
            roster = names
            val team = arrayListOf(DungeonPlayer(me, odin(mine), 50, mc.player?.skin))
            sent.forEach { m ->
                // A player's skin from their entity here, when it's loaded.
                val skin = if (m.player) mc.level?.players()?.firstOrNull { it.gameProfile.name == m.name }?.let { (it as? net.minecraft.client.player.AbstractClientPlayer)?.skin } else null
                team += DungeonPlayer(m.name, odin(m.clazz), 50, skin)
            }

            val others = team.filter { it.name != me }
            DungeonListener.dungeonTeammates = team
            DungeonListener.dungeonTeammatesNoSelf = others
            // Odin's leap menu quadrants in the plan's leap slot order (slot 1 = top left ... 4 = bottom right).
            DungeonListener.leapTeammates = others
        }
    }

    private var teamClass = -1
    private var roster = ""

    private fun unbridge() {
        teamClass = -1
        DungeonListener.floor = null
        DungeonListener.inBoss = false
        DungeonListener.dungeonTeammates = arrayListOf()
        DungeonListener.dungeonTeammatesNoSelf = emptyList()
        DungeonListener.leapTeammates = emptyList()
        setArea(Island.Unknown)
    }

    // Resolved once: if Odin renames them, the bridge stays off instead of failing every tick.
    private val areaField = runCatching { LocationUtils::class.java.getDeclaredField("currentArea").apply { isAccessible = true } }.getOrNull()
    private val skyblockField = runCatching { LocationUtils::class.java.getDeclaredField("isInSkyblock").apply { isAccessible = true } }.getOrNull()

    private fun setArea(area: Island) {
        val areaField = areaField ?: return
        val skyblockField = skyblockField ?: return
        if (LocationUtils.currentArea != area) areaField.set(null, area)
        if (skyblockField.getBoolean(null) != (area == Island.Dungeon)) skyblockField.setBoolean(null, area == Island.Dungeon)
    }

    // ------------------------------------------------------------------ Hide Players

    /** Hide Players is Odin's own (its module and its Hide All / Distance settings); in the sim its rule hides the bots too. */
    val hidePlayers: Boolean get() = HidePlayers.enabled
    fun toggleHidePlayers() = HidePlayers.toggle()

    /** [e] (a bot) is one Odin's Hide Players would hide if it were a player. Client thread. */
    fun hidesBot(e: net.minecraft.world.entity.Entity): Boolean {
        if (!HidePlayers.enabled) return false
        if ((HidePlayers.settings["Hide all"] as? BooleanSetting)?.value == true) return true
        val d = (HidePlayers.settings["Distance"] as? NumberSetting<*>)?.value?.toDouble() ?: 3.0
        val me = mc.player ?: return false
        return e.distanceToSqr(me) <= d * d
    }

    // ------------------------------------------------------------------ leap menu

    /** [classes] in the order Odin's leap menu sorts them by default (class priority, into its quadrants). */
    fun leapOrder(classes: List<DungeonClass>): List<DungeonClass> {
        val players = classes.map { DungeonPlayer(Roles.label(it), odin(it), 50, null) }.sortedBy { it.clazz.priority }
        return com.odtheking.odin.features.impl.dungeon.LeapMenu.odinSorting(players).toList()
            .mapNotNull { p -> classes.firstOrNull { it.name == p.clazz.name } }
    }

    // ------------------------------------------------------------------ the fight's effects

    /** One of the fight's effects on your game ([PartyClient]'s fx): the splits, Odin's timers and solvers, Term Info. */
    fun fx(what: String, n: Int = 0, split: String? = null) {
        when (what) {
            "splitsStart" -> {
                split ?: return
                EngineerClient.safely("p3sim splits") { com.engineerclient.splits.DungeonSplits.simStart(split, n) }
                EngineerClient.safely("p3sim arrows device") { resetArrowsDevice() }
            }
            "splitsStop" -> {
                EngineerClient.safely("p3sim splits stop") { com.engineerclient.splits.DungeonSplits.simStop() }
                // Stop and practice starts too: Odin's i4 solver forgets the blocks it marked hit.
                EngineerClient.safely("p3sim arrows device") { resetArrowsDevice() }
                EngineerClient.safely("p3sim tick timers stop") { com.engineerclient.misc.OdinTickTimers.stopAll() }
            }
            "masksReset" -> EngineerClient.safely("p3sim masks") { com.engineerclient.misc.OdinMasksUsed.resetTimers() }
            "arrowsReset" -> EngineerClient.safely("p3sim arrows device") { resetArrowsDevice() }
            "termStart" -> EngineerClient.safely("p3sim term info") { com.engineerclient.practice.TermInfo.simStart(n) }
            "termClear" -> EngineerClient.safely("p3sim term info") { com.engineerclient.practice.TermInfo.simClearTime() }
            "sectionTime" -> EngineerClient.safely("p3sim term info") { com.engineerclient.practice.TermInfo.simSectionTime(n) }
        }
    }

    /**
     * Odin's 4th device solver (Arrows Device) keeps the blocks hit, the target and "complete" until
     * a world load; a sim restart is a new P3, so it starts clean too.
     */
    private fun resetArrowsDevice() {
        val c = com.odtheking.odin.features.impl.boss.ArrowsDevice::class.java
        fun field(name: String) = c.getDeclaredField(name).apply { isAccessible = true }
        (field("markedPositions").get(null) as? MutableSet<*>)?.clear()
        field("targetPosition").set(null, null)
        field("isDeviceComplete").setBoolean(null, false)
        field("optimalAimPositions").set(null, emptyList<Any>())
    }

    // ------------------------------------------------------------------ title screen

    /** Random Stuff's Join Hypixel (the title screen's Realms button becomes it). */
    fun joinHypixel(screen: net.minecraft.client.gui.screens.Screen) = com.engineerclient.misc.RandomStuff.joinHypixel(screen)
}
