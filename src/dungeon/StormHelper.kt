package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.PersonalBest
import com.odtheking.odin.utils.playSoundAtPlayer
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.AABB
import java.util.Locale
import kotlin.math.hypot

object StormHelper : Module(
    name = "Storm Helper",
    category = Category.BOSS,
    description = "Distance to the yellow pillar's ledge, the pillar explode timer and predev time.",
    key = null,
) {
    private val ledgeYellowOnly by BooleanSetting("Ledge At Yellow Only", true, desc = "Only show the ledge distance while on the yellow pad.")
    private val ledgeAllClasses by BooleanSetting("Ledge All Classes", false, desc = "Show the ledge distance on every class, not just the crushing ones.")
    private val ledgeHud by HUD("Distance To Ledge", "How far past the yellow pillar's ledge you are.", true, 420, 350, 2.4f) { example ->
        if (example) return@HUD draw(fmt(1.25), WHITE)
        ledge()?.let { draw(fmt(it), WHITE) } ?: (0 to 0)
    }
    private val pillarHud by HUD("Pillar Explode Timer", "Counts down the 1 s from Storm being crushed to the pillar exploding.", true, 430, 150, 1.1f) { example ->
        if (example) return@HUD draw(fmt(0.5), RED)
        if (pillarTicks <= 0) return@HUD 0 to 0
        draw(fmt(pillarTicks / 20.0), if (pillarTicks < 6) GREEN else RED)
    }
    private val predev by BooleanSetting("Predev Time", true, desc = "Time from Maxor's first line until you leap away from the third device.")
    private val predevAll by BooleanSetting("Predev All Classes", true, desc = "Track predev on every class, not just healer.").withDependency { predev }

    private val predevPbs = PersonalBest(this, "PredevPBs")

    private val WHITE = Color(0xFFFFFFFF.toInt())
    private val GREEN = Color(0xFF55FF55.toInt())
    private val RED = Color(0xFFFF5555.toInt())

    private val YELLOW_PAD = AABB(20.0, 163.0, 0.0, 58.0, 213.0, 107.0)
    private const val LEDGE_X = 33.704
    private const val PILLAR_TICKS = 20

    private const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private val CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")
    private val LEAP = Regex("^You have teleported to .+!$")

    private var inStorm = false
    private var pillarTicks = 0
    private var bossStart = 0L
    private var predevTracking = false
    private var atThirdDev = false

    private fun fmt(n: Double) = String.format(Locale.ROOT, "%.2f", n)

    private fun GuiGraphicsExtractor.draw(s: String, color: Color): Pair<Int, Int> {
        text(s, 0, 0, color, true)
        return mc.font.width(s) to 9
    }

    private fun isClass(vararg c: DungeonClass) = DungeonUtils.currentDungeonPlayer.clazz in c

    private fun ledge(): Double? {
        if (!inStorm) return null
        val p = mc.player ?: return null
        if (ledgeYellowOnly && !YELLOW_PAD.contains(p.position())) return null
        if (!ledgeAllClasses) {
            val crushers = if (DungeonUtils.floor?.isMM == true) arrayOf(DungeonClass.MAGE, DungeonClass.ARCHER) else arrayOf(DungeonClass.TANK, DungeonClass.ARCHER)
            if (!isClass(*crushers)) return null
        }
        return p.x - LEDGE_X
    }

    private fun predevOn() = predev && (predevAll || isClass(DungeonClass.HEALER))

    init {
        on<MessageEvent.Chat> {
            when {
                message == MAXOR_START -> { bossStart = System.currentTimeMillis(); predevTracking = true; atThirdDev = false }
                message == STORM_START -> inStorm = true
                message == STORM_DEAD -> inStorm = false
                message == GOLDOR_START -> { inStorm = false; predevTracking = false }
                message in CRUSHED && inStorm -> {
                    pillarTicks = PILLAR_TICKS
                    mc.execute { playSoundAtPlayer(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f) }
                }
                LEAP.matches(message) && atThirdDev && predevTracking && predevOn() -> {
                    predevPbs.time("Predev", (System.currentTimeMillis() - bossStart) / 1000f, message = "§aPredev completed in §e")
                    atThirdDev = false
                    predevTracking = false
                }
            }
        }

        on<TickEvent.Server> { if (pillarTicks > 0) pillarTicks-- }

        on<TickEvent.End> {
            if (!predevTracking || atThirdDev || !predevOn()) return@on
            val p = mc.player ?: return@on
            if (hypot(p.x - 1, p.z - 77) <= 3) atThirdDev = true
        }

        on<LevelEvent.Load> { inStorm = false; pillarTicks = 0; predevTracking = false; atThirdDev = false }
    }
}
