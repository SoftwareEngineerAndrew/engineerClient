package com.engineerclient.visual

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.loreString
import com.odtheking.odin.utils.skyblock.LocationUtils
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket

object BlazeDaggerHUD : Module(
    name = "Blaze Dagger HUD",
    category = Category.NETHER,
    description = "Shows the attunement of your Blaze Slayer daggers while holding one.",
    key = null,
) {
    private val hideWarning by BooleanSetting("Hide Attunement Warning", true, desc = "Hides the wrong attunement and Hellion Shield chat messages.")
    private val topDagger by SelectorSetting("Top Line Dagger", Dagger.TWILIGHT, desc = "Which dagger's attunement is shown first.")

    private var topText = ""
    private var bottomText = ""
    private var lastInventoryCheck = 0L

    private val attunementWarning = Regex("Strike using the .+ attunement on your dagger!")

    private val topHud by HUD("Dagger Attunement Top", "Active attunements of both daggers.", false, 400, 260, 1f) { example ->
        val text = if (example) "§7[§eAuric§7] §bCrystal" else topText
        if (text.isEmpty()) return@HUD 0 to 0
        text(mc.font, text, 0, 0, -1)
        mc.font.width(text) to 9
    }

    private val bottomHud by HUD("Dagger Attunement Bottom", "Next attunements of both daggers.", false, 400, 272, 1f) { example ->
        val text = if (example) "§8Ashen §fSpirit" else bottomText
        if (text.isEmpty()) return@HUD 0 to 0
        text(mc.font, text, 0, 0, -1)
        mc.font.width(text) to 9
    }

    init {
        on<TickEvent.End> {
            val holding = Dagger.fromStack(mc.player?.mainHandItem)
            if (!LocationUtils.isInSkyblock || holding == null) {
                topText = ""
                bottomText = ""
                return@on
            }
            checkInventory()
            val first = topDagger
            val second = first.other()
            topText = format(second, true, holding) + " " + format(first, true, holding)
            bottomText = format(second, false, holding) + " " + format(first, false, holding)
        }

        onSend<ServerboundUseItemPacket> {
            val dagger = Dagger.fromStack(mc.player?.mainHandItem) ?: return@onSend
            dagger.shields.forEach { it.active = !it.active }
        }

        onReceive<ClientboundSetSubtitleTextPacket> {
            if (Dagger.updateActiveFromTitle(text().string) != null) it.cancel()
        }

        on<MessageEvent.Chat> {
            if (hideWarning && (attunementWarning.matches(message) || message == "Your hit was reduced by Hellion Shield!")) cancel()
        }
    }

    private fun checkInventory() {
        val now = System.currentTimeMillis()
        if (now - lastInventoryCheck < 1000) return
        lastInventoryCheck = now

        for (dagger in Dagger.entries) {
            if (dagger.updated || dagger.shields.any { it.active }) continue
            val found = readFromInventory(dagger)
            if (found != null) {
                found.active = true
                dagger.updated = true
            } else dagger.shields[0].active = true
        }
    }

    private fun readFromInventory(dagger: Dagger): HellionShield? {
        val items = mc.player?.inventory?.nonEquipmentItems ?: return null
        for (stack in items) {
            if (stack.isEmpty || stack.itemId !in dagger.skyblockIds) continue
            for (line in stack.loreString) {
                if (!line.contains("Attuned: ")) continue
                dagger.shields.find { line.contains(it.cleanName) }?.let { return it }
            }
        }
        return null
    }

    private fun format(dagger: Dagger, active: Boolean, holding: Dagger): String {
        val shield = if (active) dagger.activeShield() else dagger.activeShield().other()
        return if (holding == dagger && active) "§7[${shield.chatColor}${shield.cleanName}§7]"
        else "${shield.chatColor}${shield.cleanName}"
    }
}
