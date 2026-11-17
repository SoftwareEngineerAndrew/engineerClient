package com.adm.features.impl.boss

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.*
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.Module
import com.adm.utils.*
import com.adm.utils.render.drawText
import com.adm.utils.render.drawTracer
import com.adm.utils.render.BoxBatch
import com.adm.utils.render.drawBoxes
import com.adm.utils.render.textDim
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.EntityTypes

object WitherDragons : Module(
    name = "Wither Dragons",
    description = "Tools for managing M7 dragons timers, boxes, priority, health and alerts."
) {
    private val dragonTimerDropDown by DropdownSetting("Dragon Timer Dropdown", desc = "Shows settings for the M7 dragon spawn timer.")
    private val dragonTimer by BooleanSetting("Dragon Timer", true, desc = "Displays a timer for when M7 dragons spawn.").withDependency { dragonTimerDropDown }
    private val dragonTimerStyle by SelectorSetting("Timer Style", TimerStyle.MILLISECONDS, desc = "The style of the dragon timer.").withDependency { dragonTimer && dragonTimerDropDown }
    private val showSymbol by BooleanSetting("Timer Symbol", true, desc = "Displays a symbol for the timer.").withDependency { dragonTimer && dragonTimerDropDown }
    private val hud by HUD("Dragon Timer HUD", "Displays the dragon timer in the HUD.") { example ->
        if (example) textDim("§5P §a4.5s", 0, 0, Colors.WHITE)
        else {
            val dragon = priorityDragon
            if (dragon == null || dragon.timeToSpawn <= 0) 0 to 0
            else textDim(timerLabel(dragon), 0, 0, Colors.WHITE)
        }
    }.withDependency { dragonTimerDropDown }

    private val dragonBoxes by BooleanSetting("Dragon Boxes", true, desc = "Displays boxes for where M7 dragons spawn.")
    private val dragonTracers by BooleanSetting("Target Tracer", true, desc = "Draws a line to priority spawning dragon.")

    private val dragonAlerts by DropdownSetting("Dragon Alerts Dropdown", desc = "Shows chat alert settings for dragon deaths and spawns.")
    val dragonTitle by BooleanSetting("Dragon Title", true, desc = "Displays a title for spawning dragons.").withDependency { dragonAlerts }
    val sendTime by BooleanSetting("Send Dragon Time Alive", true, desc = "Sends a message when a dragon dies with the time it was alive.").withDependency { dragonAlerts }
    val sendSpawned by BooleanSetting("Send Dragon Spawned", true, desc = "Sends a message when a dragon has spawned.").withDependency { dragonAlerts }
    val sendSpray by BooleanSetting("Send Ice Sprayed", true, desc = "Sends a message when a dragon has been ice sprayed.").withDependency { dragonAlerts }

    private val dragonHealth by BooleanSetting("Dragon Health", true, desc = "Displays the health of M7 dragons.")

    val spawnOrder = mutableListOf<WitherDragonsEnum>()
    val priorityDragon: WitherDragonsEnum? get() = spawnOrder.firstOrNull()
    var currentTick = 0L

    val dragonPBs = PersonalBest(this, "DragonPBs")

    private val boxes = BoxBatch()

    init {
        onReceive<ClientboundLevelParticlesPacket> {
            if (DungeonUtils.getF7Phase() == M7Phases.P5) handleSpawnPacket(this)
        }

        on<EntityEvent.SetItemSlot> {
            if (DungeonUtils.getF7Phase() == M7Phases.P5) DragonCheck.dragonSprayed(this)
        }

        on<EntityEvent.Add> {
            if (DungeonUtils.getF7Phase() == M7Phases.P5 && entity.type == EntityTypes.ENDER_DRAGON) DragonCheck.dragonSpawn(this)
        }

        on<EntityEvent.SetData> {
            if (DungeonUtils.getF7Phase() == M7Phases.P5) DragonCheck.dragonUpdate(this)
        }

        on<BlockUpdateEvent> {
            if (DungeonUtils.getF7Phase() == M7Phases.P5 && updated.isAir)
                WitherDragonsEnum.entries.find { it.statuePos == pos }?.setDead(false)
        }

        on<TickEvent.Server> {
            WitherDragonsEnum.entries.forEach {
                if (it.timeToSpawn > 0) it.timeToSpawn--
                else if (it.state == WitherDragonState.SPAWNING) it.setAlive(null)
            }
            currentTick++
        }

        on<RenderExtractEvent> {
            if (DungeonUtils.getF7Phase() == M7Phases.P5) drawDragons()
        }

        on<LevelEvent.Load> {
            DragonCheck.dragonHealthMap.clear()
            WitherDragonsEnum.reset()
        }
    }

    private val dragons = WitherDragonsEnum.entries
    private val spawnCenters = Array(dragons.size) { dragons[it].spawnPos.center }
    private val labelKeys = IntArray(dragons.size) { -1 }
    private val labels = arrayOfNulls<String>(dragons.size)
    private val healthTexts = FormatCache(8) { healthText(it.toFloat()) }

    private fun RenderExtractEvent.drawDragons() {
        if (dragonHealth) for (tracked in DragonCheck.dragonHealthMap.values) {
            val hp = tracked.second
            val dragon = tracked.first
            if (hp > 0 && !dragon.isRemoved) drawText(healthTexts(hp.toDouble()), dragon.renderPos, 5f, false)
        }

        boxes.clear()
        for (i in dragons.indices) {
            val dragon = dragons[i]
            if (dragonTimer && dragon.timeToSpawn > 0) drawText(timerLabel(dragon), spawnCenters[i], 5f, false)
            if (dragonBoxes && dragon.state != WitherDragonState.DEAD) boxes.outline(dragon.aabbDimensions, dragon.color, depth = true)
        }
        drawBoxes(boxes)

        val priority = priorityDragon ?: return
        if (dragonTracers && priority.state == WitherDragonState.SPAWNING && mc.player != null)
            drawTracer(spawnCenters[priority.ordinal], priority.color, true)
    }

    private fun timerLabel(dragon: WitherDragonsEnum): String {
        val ticks = dragon.timeToSpawn
        val key = (ticks shl 3) or (dragonTimerStyle.ordinal shl 1) or (if (showSymbol) 1 else 0)
        val i = dragon.ordinal
        if (labelKeys[i] != key || labels[i] == null) {
            labelKeys[i] = key
            labels[i] = "§${dragon.colorCode}${dragon.name.first()}: ${timerText(ticks)}"
        }
        return labels[i]!!
    }

    private fun timerText(ticks: Int): String {
        val colour = if (ticks <= 20) "§c" else if (ticks <= 60) "§e" else "§a"
        return when (dragonTimerStyle) {
            TimerStyle.MILLISECONDS -> "$colour${ticks * 50}${if (showSymbol) "ms" else ""}"
            TimerStyle.SECONDS -> "$colour${(ticks / 20f).toFixed(1)}${if (showSymbol) "s" else ""}"
            TimerStyle.TICKS -> "$colour$ticks${if (showSymbol) "t" else ""}"
        }
    }

    enum class TimerStyle { MILLISECONDS, SECONDS, TICKS }

    private fun healthText(health: Float): String {
        val colour = when {
            health >= 750_000_000 -> "§a"
            health >= 500_000_000 -> "§e"
            health >= 250_000_000 -> "§6"
            else -> "§c"
        }
        return when {
            health >= 1_000_000_000 -> "$colour${(health / 1_000_000_000).toFixed(1)}b"
            health >= 1_000_000 -> "$colour${(health / 1_000_000).toFixed(1)}m"
            health >= 1_000 -> "$colour${(health / 1_000).toFixed(1)}k"
            else -> "$colour${health.toInt()}"
        }
    }
}
