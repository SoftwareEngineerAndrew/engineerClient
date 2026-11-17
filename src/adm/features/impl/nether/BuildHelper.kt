package com.adm.features.impl.nether

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.RenderExtractEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.Colors
import com.adm.utils.NameMemo
import com.adm.utils.render.BoxBatch
import com.adm.utils.render.drawBeaconBeam
import com.adm.utils.render.drawBoxes
import com.adm.utils.render.drawText
import com.adm.utils.render.text
import com.adm.utils.render.textDim
import com.adm.utils.skyblock.KuudraUtils
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object BuildHelper : Module(
    name = "Build Helper",
    description = "Displays various information about the current state of the ballista build."
) {
    private val buildHelperDraw by BooleanSetting("Render on Ballista", false, desc = "Draws the build information on the ballista.")
    private val unfinishedWaypoints by BooleanSetting("Unfinished Waypoints", true, desc = "Draws waypoints over the unfinished piles.")
    private val hideDefaultTag by BooleanSetting("Hide Default Tag", true, desc = "Hides the default tag for unfinished piles.").withDependency { unfinishedWaypoints }
    private val hud by HUD(name, "Shows information about the build progress.") { example ->
        if (!example && (KuudraUtils.phase != 2 || !KuudraUtils.inKuudra)) return@HUD 0 to 0
        val width = textDim(freshersLine(KuudraUtils.freshers.size), 0, 0).first
        text(buildersLine(KuudraUtils.playersBuildingAmount), 0, 9)
        text(buildLine(KuudraUtils.buildDonePercentage), 0, 18)
        width to mc.font.lineHeight * 3
    }

    private val stunNotificationNumber by NumberSetting("Stun Percent", 93f, 0..100, desc = "The build % to notify at (set to 0 to disable).", unit = "%")

    private val ballistaBuildAt = Vec3(-101.5, 82.0, -105.5)
    private val ballistaBuildersAt = Vec3(-101.5, 81.0, -105.5)
    private val stunTitle = Component.literal("§l§3Go to stun")

    private val freshersLine = Line { "§bFreshers: ${builders(it)}" }
    private val buildersLine = Line { "§bBuilders: ${builders(it)}" }
    private val buildLine = Line { "§bBuild: ${build(it)}%" }
    private val ballistaBuild = Line { "§bBuild §c${build(it)}%" }
    private val ballistaBuilders = Line { "§bBuilders ${builders(it)}" }

    private class PileSpot(val pos: BlockPos) {
        val box = AABB(pos)
        val labelAt: Vec3 = Vec3.atCenterOf(pos).add(0.0, 1.7, 0.0)
    }

    private val pileSpots = Int2ObjectOpenHashMap<PileSpot>()
    private val pileLabels = NameMemo { it.string }
    private val boxes = BoxBatch()

    init {
        on<RenderExtractEvent> {
            if (KuudraUtils.phase != 2 || !KuudraUtils.inKuudra) return@on
            val done = KuudraUtils.buildDonePercentage

            if (stunNotificationNumber != 0f && KuudraUtils.kuudraTier >= 3 && done >= stunNotificationNumber) {
                mc.gui.hud.setTimes(0, 20, 5)
                mc.gui.hud.setTitle(stunTitle)
            }

            if (buildHelperDraw) {
                drawText(ballistaBuild(done), ballistaBuildAt, 3f, false)
                drawText(ballistaBuilders(KuudraUtils.playersBuildingAmount), ballistaBuildersAt, 3f, false)
            }

            if (!unfinishedWaypoints || KuudraUtils.buildingPiles.isEmpty() || mc.player == null) return@on
            if (pileSpots.size > 64) pileSpots.clear()
            boxes.clear()
            for (pile in KuudraUtils.buildingPiles) {
                val pos = pile.blockPosition()
                var spot = pileSpots.get(pile.id)
                if (spot == null || spot.pos != pos) {
                    spot = PileSpot(pos)
                    pileSpots.put(pile.id, spot)
                }
                boxes.outline(spot.box, Colors.MINECRAFT_DARK_RED)
                drawBeaconBeam(spot.pos, Colors.MINECRAFT_DARK_RED)
                drawText(pileLabels[pile, pile.name], spot.labelAt, 2f, false)
                if (hideDefaultTag && pile.isCustomNameVisible) pile.isCustomNameVisible = false
            }
            drawBoxes(boxes)
        }
    }

    private class Line(private val make: (Int) -> String) {
        private var last = Int.MIN_VALUE
        private var text = ""

        operator fun invoke(value: Int): String {
            if (value != last) { last = value; text = make(value) }
            return text
        }
    }

    private fun build(percent: Int): String = when {
        percent >= 75 -> "§a$percent"
        percent >= 50 -> "§e$percent"
        percent >= 25 -> "§6$percent"
        else -> "§c$percent"
    }

    private fun builders(count: Int): String = when {
        count >= 3 -> "§a$count"
        count >= 2 -> "§e$count"
        else -> "§c$count"
    }
}
