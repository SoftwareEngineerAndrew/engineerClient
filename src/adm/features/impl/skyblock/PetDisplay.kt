package com.adm.features.impl.skyblock

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.StringSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.SetSlotEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.Module
import com.adm.features.ModuleManager
import com.adm.utils.customData
import com.adm.utils.loreString
import com.adm.utils.render.text
import com.adm.utils.skyblock.LocationUtils
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.engineerclient.misc.DiskWrites
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.util.IdentityHashMap
import java.util.Optional

object PetDisplay : Module(
    name = "Pet Display",
    description = "Current pet HUD and candy used on pets.",
    key = null,
) {
    private val candyOverlay by BooleanSetting("Candy On Item", true, desc = "Shows candy used on pets in slots.")
    private val candyTooltip by BooleanSetting("Candy In Tooltip", true, desc = "Adds the candy used line to pet tooltips.")
    private val autopetLevel by BooleanSetting("Autopet Level", true, desc = "Shows the pet's level in the Autopet popup.")
    private val autopetDungeons by BooleanSetting("Autopet Only In Dungeons", false, desc = "Only shows the Autopet popup in dungeons.")
    private var savedPet by StringSetting("Last Pet", "", 128, "", "").hide()

    private val hud by HUD("Current Pet", "Shows your current pet.") { example ->
        val name = if (example) "§7[Lvl 100] §6Golden Dragon" else pet ?: savedPet.takeIf { it.isNotEmpty() } ?: return@HUD 0 to 0
        text(name, 1, 1)
        mc.font.width(name) + 2 to 1 + mc.font.lineHeight
    }

    private val autopetHud by HUD("Autopet Popup", "The pet an Autopet rule just equipped, for a few seconds.", true, 400, 150, 2f) { example ->
        val name = if (example) (if (autopetLevel) "§7[Lvl 100] " else "") + "§6Golden Dragon"
            else autopetShown?.takeIf { System.currentTimeMillis() < autopetUntil } ?: return@HUD 0 to 0
        text(name, 1, 1)
        mc.font.width(name) + 2 to 1 + mc.font.lineHeight
    }

    @Volatile private var pet: String? = null
        set(value) {
            if (field == value && savedPet == (value ?: "")) return
            field = value
            mc.execute {
                savedPet = value ?: ""
                DiskWrites.write { ModuleManager.saveConfigurations() }
            }
        }
    @Volatile private var autopetShown: String? = null
    @Volatile private var autopetUntil = 0L
    private const val AUTOPET_MS = 2500L

    private val autopet = Regex("^Autopet equipped your \\[Lvl (\\d+)] (.+?)!(?: VIEW RULE)?$")
    private val summoned = Regex("^You summoned your (.+?)!$")
    private val despawned = Regex("^You despawned your (.+?)!$")
    private val candyLore = Regex("\\((\\d+)/10\\) Pet Candy Used")

    private val candyCache = HashMap<String, Int>()

    private class CandyMemo(val name: Component, val candy: Int)
    private val candyMemo = IdentityHashMap<CustomData, CandyMemo>()

    init {
        on<MessageEvent.Chat> {
            autopet.matchEntire(message)?.let { m ->
                val name = colored(component, m.groupValues[2])
                pet = "§7[Lvl ${m.groupValues[1]}] $name"
                if (autopetDungeons && !DungeonUtils.inDungeons) return@on
                autopetShown = if (autopetLevel) pet else name
                autopetUntil = System.currentTimeMillis() + AUTOPET_MS
                return@on
            }
            summoned.matchEntire(message)?.let { m ->
                pet = colored(component, m.groupValues[1])
                return@on
            }
            if (despawned.matches(message)) pet = null
        }

        on<SetSlotEvent> {
            val title = mc.gui.screen()?.title?.string ?: return@on
            if (slotIndex >= menu.slots.size - 36) return@on
            if (title.startsWith("Pets") && itemStack.loreString.any { "Click to despawn" in it }) pet = itemStack.hoverName.legacy()
        }

        // The tab list's Pet widget line, " [Lvl 100] Golden Dragon", gives the pet and its level without a summon.
        onReceive<ClientboundPlayerInfoUpdatePacket> {
            if (!LocationUtils.isInSkyblock || !actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)) return@onReceive
            for (entry in entries()) {
                val line = entry.displayName ?: continue
                if (!line.string.startsWith(" [Lvl ")) continue
                pet = line.legacy().replaceFirst(" [Lvl ", "[Lvl ").let { if (it.startsWith("[")) "§7$it" else it }
                return@onReceive
            }
        }

        on<LevelEvent.Load> { candyCache.clear(); candyMemo.clear() }

        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!enabled || !candyTooltip || !LocationUtils.isInSkyblock) return@register
            val candy = candyUsed(stack) ?: return@register
            if (lines.any { "Pet Candy Used" in it.string }) return@register
            // Hypixel's own line and place, which it leaves out on max level pets and pets without candy
            val at = lines.indexOfFirst { "MAX LEVEL" in it.string || "Progress to Level" in it.string }
            val line = Component.literal("§a($candy/10) Pet Candy Used")
            if (at < 0) lines.add(1.coerceAtMost(lines.size), line)
            else if (at > 0 && lines[at - 1].string.isBlank()) lines.addAll(at, listOf(line, Component.empty()))
            else lines.add(at, line)
        }
    }

    /** The slot count text for a pet: its candy used, or vanilla's when none. */
    @JvmStatic
    fun countText(stack: ItemStack, vanilla: String?): String? {
        if (vanilla != null || !enabled || !candyOverlay || !LocationUtils.isInSkyblock) return vanilla
        val candy = candyUsed(stack) ?: 0
        return if (candy > 0) "§c$candy" else vanilla
    }

    /** The candy used on a pet; null if it isn't one. */
    private fun candyUsed(stack: ItemStack): Int? {
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val name = stack.hoverName
        candyMemo[data]?.let { if (it.name === name) return it.candy }
        val candy = candyUsed(stack, name) ?: return null
        if (candyMemo.size >= 512) candyMemo.clear()
        candyMemo[data] = CandyMemo(name, candy)
        return candy
    }

    private fun candyUsed(stack: ItemStack, name: Component): Int? {
        val info = stack.customData.getString("petInfo").orElse(null) ?: return null
        val key = name.string + info
        candyCache[key]?.let { return it }
        val json = runCatching { JsonParser.parseString(info).asJsonObject }.getOrNull()
        val candy = json?.get("candyUsed")?.asInt
            ?: stack.loreString.firstNotNullOfOrNull { candyLore.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        if (candyCache.size >= 512) candyCache.clear()
        return candy.also { candyCache[key] = it }
    }

    private val RGB = intArrayOf(
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    )


    /** [name] with the colour it has in [component]. */
    private fun colored(component: Component, name: String): String {
        // The colour comes either as the style or as § codes inside the text
        val plain = StringBuilder()
        val codes = ArrayList<String>()
        component.visit(FormattedText.StyledContentConsumer<Unit> { style, piece ->
            val base = codeOf(style)
            var code = base
            var i = 0
            while (i < piece.length) {
                val c = piece[i]
                if (c == '§' && i + 1 < piece.length) {
                    val k = piece[i + 1].lowercaseChar()
                    if (k in "0123456789abcdef") code = "§$k" else if (k == 'r') code = base
                    i += 2
                    continue
                }
                plain.append(c)
                codes.add(code)
                i++
            }
            Optional.empty()
        }, Style.EMPTY)
        val at = plain.indexOf(name)
        return (if (at < 0) "§f" else codes[at]) + name
    }

    private fun codeOf(style: Style): String {
        val rgb = style.color?.value ?: return "§f"
        val i = RGB.indexOf(rgb)
        return if (i < 0) "§f" else "§" + "0123456789abcdef"[i]
    }

    private fun Component.legacy(): String {
        val out = StringBuilder()
        visit(FormattedText.StyledContentConsumer<Unit> { style, piece ->
            if (piece.isNotEmpty()) out.append(codeOf(style)).append(piece)
            Optional.empty()
        }, Style.EMPTY)
        return out.toString()
    }
}
