package com.adm.features.impl.skyblock

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.SetSlotEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.customData
import com.adm.utils.loreString
import com.adm.utils.setTitle
import com.adm.utils.render.text
import com.adm.utils.skyblock.LocationUtils
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack
import java.util.Optional

object PetDisplay : Module(
    name = "Pet Display",
    description = "Current pet HUD and candy used on pets.",
    key = null,
) {
    private val candyOverlay by BooleanSetting("Candy On Item", true, desc = "Shows candy used on max level pets in slots.")
    private val candyTooltip by BooleanSetting("Candy In Tooltip", true, desc = "Adds the candy used line to pet tooltips.")
    private val showSource by BooleanSetting("Autopet Title", true, desc = "A short title with the pet an Autopet rule equipped.")
    private val showRules by BooleanSetting("Show Autopet Rules", false, desc = "Lists the rules read from the Autopets menu.")

    private val hud by HUD("Current Pet", "Shows your current pet.") { example ->
        val name = if (example) "§7[Lvl 100] §6Golden Dragon" else pet ?: return@HUD 0 to 0
        var w = mc.font.width(name)
        text(name, 1, 1)
        var y = 1 + mc.font.lineHeight
        if (showRules) for (rule in if (example) listOf("§aRule 1") else rules) {
            text("§8- §r$rule", 1, y)
            w = maxOf(w, mc.font.width("- $rule"))
            y += mc.font.lineHeight
        }
        w + 2 to y
    }

    @Volatile private var pet: String? = null
    private val rules = ArrayList<String>()

    private val autopet = Regex("^Autopet equipped your \\[Lvl (\\d+)] (.+?)!(?: VIEW RULE)?$")
    private val summoned = Regex("^You summoned your (.+?)!$")
    private val despawned = Regex("^You despawned your (.+?)!$")
    private val levelRegex = Regex("\\[Lvl (\\d+)]")
    private val candyLore = Regex("Pet Candy Used: (\\d+)")
    private val ignoredRuleItems = setOf("Close", "Go Back", "Create Rule", "Next Page", "Previous Page", "")

    private val candyCache = HashMap<String, PetCandy>()
    private const val NOT_MAX = -1

    init {
        on<MessageEvent.Chat> {
            autopet.matchEntire(message)?.let { m ->
                val equipped = "§7[Lvl ${m.groupValues[1]}] " + colored(component, m.groupValues[2])
                pet = equipped
                if (showSource) setTitle(equipped)
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
            when {
                title.startsWith("Pets") -> {
                    if (itemStack.loreString.any { "Click to despawn" in it }) {
                        pet = itemStack.hoverName.legacy()
                    }
                }
                title.startsWith("Autopet") -> {
                    if (slotIndex == 0) rules.clear()
                    if (itemStack.isEmpty || itemStack.loreString.isEmpty() || "pane" in itemStack.item.toString()) return@on
                    val name = itemStack.hoverName.string
                    if (name in ignoredRuleItems) return@on
                    val line = itemStack.hoverName.legacy()
                    if (line !in rules && rules.size < 20) rules.add(line)
                }
            }
        }

        on<LevelEvent.Load> { candyCache.clear() }

        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!enabled || !candyTooltip || !LocationUtils.isInSkyblock) return@register
            val candy = pet(stack)?.candy ?: return@register
            if (lines.any { "Pet Candy Used" in it.string }) return@register
            // Where Hypixel puts it on a pet that isn't max level: above the level progress
            val at = lines.indexOfFirst { "MAX LEVEL" in it.string || "Progress to Level" in it.string }
            val line = Component.literal("§7Pet Candy Used: ${if (candy > 0) "§c" else "§a"}$candy§7/10")
            if (at < 0) lines.add(1.coerceAtMost(lines.size), line)
            else if (at > 0 && lines[at - 1].string.isBlank()) lines.addAll(at, listOf(line, Component.empty()))
            else lines.add(at, line)
        }
    }

    /** The slot count text for a max level pet: its candy used, or vanilla's when not one. */
    @JvmStatic
    fun countText(stack: ItemStack, vanilla: String?): String? {
        if (vanilla != null || !enabled || !candyOverlay || !LocationUtils.isInSkyblock) return vanilla
        val candy = maxedCandy(stack)
        return if (candy > 0) "§c$candy" else vanilla
    }

    /** Candy used if the stack is a max level pet, else [NOT_MAX]. */
    private fun maxedCandy(stack: ItemStack): Int = pet(stack)?.takeIf { it.maxed }?.candy ?: NOT_MAX

    private class PetCandy(val maxed: Boolean, val candy: Int)

    /** Whether the stack is a max level pet and the candy used on it; null if it isn't a pet. */
    private fun pet(stack: ItemStack): PetCandy? {
        val info = stack.customData.getString("petInfo").orElse(null) ?: return null
        val name = stack.hoverName.string
        val key = name + info
        candyCache[key]?.let { return it }
        val lore = stack.loreString
        val json = runCatching { JsonParser.parseString(info).asJsonObject }.getOrNull()
        val type = json?.get("type")?.asString ?: ""
        val level = levelRegex.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val maxed = lore.any { "MAX LEVEL" in it } || level >= maxLevel(type, name)
        val candy = json?.get("candyUsed")?.asInt ?: lore.firstNotNullOfOrNull { candyLore.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        if (candyCache.size >= 512) candyCache.clear()
        return PetCandy(maxed, candy).also { candyCache[key] = it }
    }

    private fun maxLevel(type: String, name: String): Int =
        if (type in DRAGONS || DRAGON_NAMES.any { it in name }) 200 else 100

    private val RGB = intArrayOf(
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    )

    private val DRAGONS = setOf("GOLDEN_DRAGON", "JADE_DRAGON", "ROSE_DRAGON")
    private val DRAGON_NAMES = listOf("Golden Dragon", "Jade Dragon", "Rose Dragon")

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
