package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.engineerclient.mixin.HudTitleAccessor
import com.adm.utils.hexText
import com.adm.utils.noControlCodes
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3

/** Hypixel's terminal, lever, device and gate titles in your own format. */
object TermTitles {
    private val titleRegex = Regex("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d+)/(\\d+)\\)$")
    private val gateDestroyed = Regex("^The gate has been destroyed!$")
    private val gateOpens = Regex("^The gate will open in (\\d+) seconds?!$")
    private val revived = Regex("^You will be revived in \\ds$")
    private val deathTitles = setOf("You became a ghost!", "Hopefully your teammates will be able to revive you!", "BEING REVIVED")
    private val colorCode = Regex("&([0-9A-FK-ORa-fk-or])")
    private val ssPosition = Vec3(108.0, 119.0, 94.0)

    // Hypixel sends the progress with a blank title or subtitle beside it, which would wipe ours.
    private var replacedAt = 0L

    @JvmStatic
    fun intercept(text: Component): Boolean {
        if (!Terminals.titlesOn || !DungeonUtils.inDungeons) return false
        val plain = text.string.noControlCodes
        val p3 = DungeonUtils.getF7Phase() == M7Phases.P3
        if (p3 && hiddenHere() && plain !in deathTitles && !revived.matches(plain)) return true
        if (plain.isBlank()) return System.currentTimeMillis() - replacedAt < 250
        if (Terminals.replaceGate) {
            if (gateDestroyed.matches(plain)) return show("§aGate destroyed!")
            gateOpens.find(plain)?.let { return show("§aGate opens in §f${it.groupValues[1]}s") }
        }
        val (name, action, type, current, total) = titleRegex.find(plain)?.destructured ?: return false
        if ((Terminals.selfOnly || Terminals.hideOthers) && name != mc.player?.name?.string) return Terminals.hideOthers
        if (Terminals.phaseDone && current == total) return show("§aPhase Done!")
        val replace = when (type) {
            "terminal" -> Terminals.replaceTerminal
            "lever" -> Terminals.replaceLever
            else -> Terminals.replaceDevice
        }
        if (!replace) return false
        val clazz = DungeonUtils.dungeonTeammates.firstOrNull { it.name == name }?.clazz?.takeIf { it != DungeonClass.EMPTY }
        val cur = if (Terminals.progressColor) progressColor(current.toInt(), total.toInt()) + current else current
        val title = Terminals.customTitle
            .replace("{name}", name).replace("{action}", action).replace("{type}", type)
            .replace("{current}", cur).replace("{total}", total)
            .replace("{class}", clazz?.let { "${it.hexCode}${it.name.lowercase().replaceFirstChar(Char::uppercase)}" } ?: "")
            .replace(colorCode, "§$1")
        return if (title.isEmpty()) true else show(title)
    }

    private fun hiddenHere(): Boolean {
        val p = mc.player ?: return false
        if (Terminals.hideAtSs && p.position().distanceTo(ssPosition) <= 3.0) return true
        return Terminals.hideAtPre4 && p.x in 63.0..64.0 && p.y == 127.0 && p.z in 35.0..36.0
    }

    private fun progressColor(current: Int, total: Int) = when {
        current >= total -> "§a"
        current * 2 >= total -> "§e"
        else -> "§c"
    }

    private fun show(title: String): Boolean {
        val hud = mc.gui.hud as HudTitleAccessor
        val text = hexText(title)
        hud.`ec$setTitle`(if (Terminals.useSubtitle) Component.empty() else text)
        hud.`ec$setSubtitle`(if (Terminals.useSubtitle) text else Component.empty())
        hud.`ec$setFadeIn`(0)
        hud.`ec$setStay`(Terminals.titleDuration)
        hud.`ec$setFadeOut`(5)
        hud.`ec$setTitleTime`(Terminals.titleDuration + 5)
        replacedAt = System.currentTimeMillis()
        return true
    }
}
