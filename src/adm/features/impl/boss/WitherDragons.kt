package com.adm.features.impl.boss

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.clickgui.settings.impl.StringSetting
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
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.BowItem

object WitherDragons : Module(
    name = "Wither Dragons",
    description = "Tools for managing M7 dragons timers, boxes, priority, health and alerts."
) {
    private val dragonTimerDropDown by DropdownSetting("Dragon Timer Dropdown", desc = "Shows settings for the M7 dragon spawn timer.")
    private val dragonTimer by BooleanSetting("Dragon Timer", true, desc = "A spawn timer under each statue, separate from the HUD timer.").withDependency { dragonTimerDropDown }
    private val displayStyle by SelectorSetting("Display Style", DisplayStyle.DEFAULT, desc = "Blade shows each spawning dragon's seconds in its colour.").withDependency { (dragonTimer || hud.enabled) && dragonTimerDropDown }
    private val dragonTimerStyle by SelectorSetting("Timer Style", TimerStyle.MILLISECONDS, desc = "The style of the dragon timer.").withDependency { (dragonTimer || hud.enabled) && dragonTimerDropDown && displayStyle == DisplayStyle.DEFAULT }
    private val showSymbol by BooleanSetting("Timer Symbol", true, desc = "Displays a symbol for the timer.").withDependency { (dragonTimer || hud.enabled) && dragonTimerDropDown && displayStyle == DisplayStyle.DEFAULT }
    private val hud: com.adm.clickgui.settings.impl.HudElement by HUD("Dragon Timer HUD", "An on-screen spawn timer, separate from the statue timer.") { example ->
        if (example) {
            val blade = displayStyle == DisplayStyle.BLADE
            val a = textDim(if (blade) "§c3.25s" else "§cR: §a3250ms", 0, 0, Colors.WHITE)
            val b = textDim(if (blade) "§54.50s" else "§5P: §a4500ms", 0, 10, Colors.WHITE)
            return@HUD maxOf(a.first, b.first) to 19
        }
        val count = collectSpawning()
        var width = 0
        for (i in 0 until count) {
            val w = textDim(timerLabel(hudOrder[i]!!), 0, i * 10, Colors.WHITE).first
            if (w > width) width = w
        }
        if (count == 0) 0 to 0 else width to count * 10 - 1
    }.withDependency { dragonTimerDropDown }

    private val dragonBoxes by BooleanSetting("Dragon Boxes", true, desc = "Displays boxes for where M7 dragons spawn.")
    private val dragonTracers by BooleanSetting("Target Tracer", true, desc = "Draws a line to priority spawning dragon.")
    val split by BooleanSetting("Split", false, desc = "With two dragons spawning, the later one is yours if your class is listed.")
    private val splitClasses by StringSetting("Later Dragon Classes", "Healer", 64, desc = "Classes that take the later of two dragons, comma separated.", placeholder = "Healer").withDependency { split }

    private val dragonAlerts by DropdownSetting("Dragon Alerts Dropdown", desc = "Shows chat alert settings for dragon deaths and spawns.")
    val dragonTitle by BooleanSetting("Dragon Title", true, desc = "Displays a title for spawning dragons.").withDependency { dragonAlerts }
    val sendTime by BooleanSetting("Send Dragon Time Alive", true, desc = "Sends a message when a dragon dies with the time it was alive.").withDependency { dragonAlerts }
    val sendSpawned by BooleanSetting("Send Dragon Spawned", true, desc = "Sends a message when a dragon has spawned.").withDependency { dragonAlerts }
    val sendSpray by BooleanSetting("Send Ice Sprayed", true, desc = "Sends a message when a dragon has been ice sprayed.").withDependency { dragonAlerts }
    val arrowHits by BooleanSetting("Arrow Hits", false, desc = "Counts your bow hits on the priority dragon for its death message.").withDependency { dragonAlerts }

    private val dragonHealth by BooleanSetting("Dragon Health", true, desc = "Displays the health of M7 dragons.")

    private val lastBreathDropDown by DropdownSetting("Last Breath Dropdown", desc = "Times Last Breath shots to land as a dragon spawns.", label = "Last Breath")
    private val lastBreath by BooleanSetting("Last Breath Release", false, desc = "Times a full draw to land as the next dragon spawns.").withDependency { lastBreathDropDown }
    private val lastBreathDing by BooleanSetting("Release Ding", true, desc = "Dings when to release with your current aim.").withDependency { lastBreathDropDown && lastBreath }
    private val lastBreathBunch by BooleanSetting("Bunch Marker", true, desc = "Marks where to aim so a shot released now lands at the spawn.").withDependency { lastBreathDropDown && lastBreath }
    private val lastBreathColor by ColorSetting("Marker Color", Colors.MINECRAFT_GREEN, true, desc = "Colour of the aim marker.").withDependency { lastBreathDropDown && lastBreath && lastBreathBunch }
    private val lastBreathHud by HUD("Last Breath HUD", "Time to release with your current aim.") { example ->
        if (example) return@HUD textDim("§cRed §frelease in §e1.25s", 0, 0, Colors.WHITE)
        if (!lastBreath) return@HUD 0 to 0
        LastBreathRelease.hudText()?.let { textDim(it, 0, 0, Colors.WHITE) } ?: (0 to 0)
    }.withDependency { lastBreathDropDown && lastBreath }

    val spawnOrder = mutableListOf<WitherDragonsEnum>()
    val priorityDragon: WitherDragonsEnum? get() = spawnOrder.firstOrNull()
    var currentTick = 0L

    val dragonPBs = PersonalBest(this, "DragonPBs")

    private val boxes = BoxBatch()

    init {
        onReceive<ClientboundLevelParticlesPacket> {
            if (DungeonUtils.getF7Phase() == M7Phases.P5) handleSpawnPacket(this)
        }

        // Hypixel's hit ding goes only to the shooter.
        onReceive<ClientboundSoundPacket> {
            if (arrowHits && sound.value() == SoundEvents.ARROW_HIT_PLAYER && DungeonUtils.getF7Phase() == M7Phases.P5) mc.execute(countHit)
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
            if (lastBreath) LastBreathRelease.tick(lastBreathDing, lastBreathBunch)
        }

        on<RenderExtractEvent> {
            if (DungeonUtils.getF7Phase() == M7Phases.P5) {
                drawDragons()
                if (lastBreath && lastBreathBunch) LastBreathRelease.render(this, lastBreathColor)
            }
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
            if (dragonTimer && dragon.timeToSpawn > 0)
                drawText(timerLabel(dragon), spawnCenters[i], if (displayStyle == DisplayStyle.BLADE) 8f else 5f, false)
            if (dragonBoxes && dragon.state != WitherDragonState.DEAD) boxes.outline(dragon.aabbDimensions, dragon.color, depth = true)
        }
        drawBoxes(boxes)

        if (!dragonTracers || mc.player == null) return
        val target = myDragon() ?: return
        drawTracer(spawnCenters[target.ordinal], target.color, true)
    }

    private val countHit = Runnable {
        if (mc.player?.mainHandItem?.item !is BowItem) return@Runnable
        for (i in spawnOrder.indices) {
            val d = spawnOrder.getOrNull(i) ?: return@Runnable
            if (d.state == WitherDragonState.ALIVE) { d.arrowHits++; return@Runnable }
        }
    }

    /** With [split] on, whether your class takes the later of two dragons spawning together. */
    private fun takesLater(): Boolean {
        if (!split) return false
        val clazz = DungeonUtils.currentDungeonPlayer.clazz
        return clazz != DungeonClass.EMPTY && splitClasses.contains(clazz.name, ignoreCase = true)
    }

    /** The spawning dragon that's yours: the first, or with [split] the second of two when your class takes it. */
    fun myDragon(): WitherDragonsEnum? {
        var first: WitherDragonsEnum? = null
        for (i in spawnOrder.indices) {
            val d = spawnOrder.getOrNull(i) ?: break
            if (d.state != WitherDragonState.SPAWNING) continue
            if (first == null) first = d else return if (takesLater()) d else first
        }
        return first
    }

    private fun timerLabel(dragon: WitherDragonsEnum): String {
        val ticks = dragon.timeToSpawn
        val key = (ticks shl 4) or (displayStyle.ordinal shl 3) or (dragonTimerStyle.ordinal shl 1) or (if (showSymbol) 1 else 0)
        val i = dragon.ordinal
        if (labelKeys[i] != key || labels[i] == null) {
            labelKeys[i] = key
            labels[i] =
                if (displayStyle == DisplayStyle.BLADE) "§${dragon.colorCode}${(ticks / 20f).toFixed(2)}s"
                else "§${dragon.colorCode}${dragon.name.first()}: ${timerText(ticks)}"
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

    enum class DisplayStyle { DEFAULT, BLADE }

    private val hudOrder = arrayOfNulls<WitherDragonsEnum>(dragons.size)

    private fun collectSpawning(): Int {
        var count = 0
        for (dragon in dragons) {
            if (dragon.timeToSpawn <= 0 || dragon.state != WitherDragonState.SPAWNING) continue
            var j = count++
            while (j > 0 && hudOrder[j - 1]!!.timeToSpawn > dragon.timeToSpawn) {
                hudOrder[j] = hudOrder[j - 1]
                j--
            }
            hudOrder[j] = dragon
        }
        return count
    }

    fun nextSpawnTicks(): Int {
        if (!enabled || DungeonUtils.getF7Phase() != M7Phases.P5) return -1
        var best = -1
        for (dragon in dragons)
            if (dragon.state == WitherDragonState.SPAWNING && dragon.timeToSpawn > 0 && (best < 0 || dragon.timeToSpawn < best)) best = dragon.timeToSpawn
        return best
    }

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
