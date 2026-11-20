package com.engineerclient.skyblock

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.customData
import com.adm.utils.itemId
import com.adm.utils.skyblock.LocationUtils
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.util.Unit as McUnit
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.IdentityHashMap
import java.util.Optional

object ItemTweaks : Module(
    name = "Item Tweaks",
    category = Category.SKYBLOCK,
    description = "Small item and tooltip tweaks.",
    key = null,
) {
    private val removeGearScore by BooleanSetting("Remove Gear Score", false, desc = "Drops the Gear Score line from dungeon item tooltips.")
    private val noPearlCooldown by BooleanSetting("Disable Ender Pearl Cooldown", false, desc = "Ender pearls never show or wait on the vanilla cooldown.")
    private val itemQuality by BooleanSetting("Item Quality", true, desc = "Adds a dungeon item's stat boost and floor to its tooltip.")
    private val oldPlayerHead by BooleanSetting("Old Player Head Size", true, desc = "Draws player heads in slots at their smaller 1.8 size.")
    private val hideVanillaEnchants by BooleanSetting("Hide Vanilla Enchants", true, desc = "Drops the vanilla enchantment lines above SkyBlock lore.")
    private val noHotbarScroll by BooleanSetting("Disable Hotbar Scroll", true, desc = "The scroll wheel no longer changes your hotbar slot.")
    private val oldMasterStars by BooleanSetting("Old Master Stars", false, desc = "Master stars as red stars instead of a number.")
    private val hideCooldowns by BooleanSetting("Hide Item Cooldowns", false, desc = "No cooldown overlay on any item.")
    private val qualityStyle by SelectorSetting("Item Quality Style", QualityStyle.FRACTION, desc = "Stat boost out of 50, or as a percent of the best.")

    init {
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!enabled) return@register
            if (removeGearScore) dropGearScore(lines)
            if (itemQuality) {
                val data = stack.customData
                val boost = data.getInt("baseStatBoostPercentage").orElse(null) ?: return@register
                val tier = data.getInt("item_tier").orElse(null) ?: return@register
                val floor = floorOf(tier, data.getString("dungeon_skill_req").orElse(""))
                val shown = if (qualityStyle == QualityStyle.PERCENT) "${boost * 2}%" else "$boost/50"
                lines.add(Component.literal("§4Stat boost: $shown req: $floor"))
            }
        }
    }

    enum class QualityStyle(private val label: String) { FRACTION("Out of 50"), PERCENT("Percent"); override fun toString() = label }

    // Master Mode drops share item_tier 4-7 with F4-F7; their Catacombs requirement is the tell.
    private fun floorOf(tier: Int, req: String): String {
        if (tier <= 0) return "E"
        if (tier >= 8) return "M${tier - 3}"
        val level = if (req.startsWith("CATACOMBS:")) req.substring(10).toIntOrNull() ?: 0 else 0
        return if (tier >= 4 && level - tier > 19) "M${tier - 3}" else "F$tier"
    }

    private const val CIRCLED = "➊➋➌➍➎"

    fun redStarsOn(): Boolean = enabled && oldMasterStars

    private val hoverNames = IdentityHashMap<Component, Component>()

    // Every item name goes through here, so each name is rewritten once, not every frame.
    @JvmStatic
    fun hoverName(name: Component): Component {
        if (!enabled || !oldMasterStars) return name
        if (!mc.isSameThread) return redStars(name) ?: name
        hoverNames[name]?.let { return it }
        val shown = redStars(name) ?: name
        if (hoverNames.size >= 512) hoverNames.clear()
        hoverNames[name] = shown
        return shown
    }

    /** "✪✪✪✪✪➋" as it looked before: the first two stars red, no number. */
    private fun redStars(name: Component): Component? {
        val chars = StringBuilder()
        val styles = ArrayList<Style>()
        name.visit(FormattedText.StyledContentConsumer<McUnit> { style, piece ->
            for (c in piece) { chars.append(c); styles.add(style) }
            Optional.empty()
        }, Style.EMPTY)
        val digit = chars.indexOfFirst { it in CIRCLED }
        if (digit < 0) return null
        val master = CIRCLED.indexOf(chars[digit]) + 1
        var start = digit
        while (start > 0 && chars[start - 1] == '✪') start--
        if (digit - start < master) return null
        val red = styles[start].withColor(ChatFormatting.RED)
        for (i in start until start + master) styles[i] = red
        chars.deleteCharAt(digit)
        styles.removeAt(digit)
        val out = Component.empty()
        var i = 0
        while (i < chars.length) {
            var j = i
            while (j < chars.length && styles[j] == styles[i]) j++
            out.append(Component.literal(chars.substring(i, j)).setStyle(styles[i]))
            i = j
        }
        return out
    }

    private fun dropGearScore(lines: MutableList<Component>) {
        var i = 0
        while (i < lines.size) {
            if (GearScorePrefix.matches(lines[i])) lines.removeAt(i) else i++
        }
    }

    private object GearScorePrefix : FormattedText.ContentConsumer<McUnit> {
        private const val PREFIX = "Gear Score: "
        private var seen = 0

        fun matches(line: Component): Boolean {
            seen = 0
            line.visit(this)
            return seen == PREFIX.length
        }

        override fun accept(piece: String): Optional<McUnit> {
            val take = minOf(PREFIX.length - seen, piece.length)
            if (!piece.regionMatches(0, PREFIX, seen, take)) {
                seen = -1
                return FormattedText.STOP_ITERATION
            }
            seen += take
            return if (seen == PREFIX.length) FormattedText.STOP_ITERATION else Optional.empty()
        }
    }

    fun noPearlCooldown(): Boolean = enabled && noPearlCooldown

    @JvmStatic
    fun hidesCooldowns(): Boolean = enabled && hideCooldowns

    @JvmStatic
    fun smallHead(stack: ItemStack): Boolean = enabled && oldPlayerHead && stack.`is`(Items.PLAYER_HEAD)

    @JvmStatic
    fun hidesVanillaTooltip(type: DataComponentType<*>, stack: ItemStack): Boolean =
        enabled && hideVanillaEnchants && (type === DataComponents.ENCHANTMENTS || type === DataComponents.STORED_ENCHANTMENTS) &&
            (LocationUtils.isInSkyblock || stack.itemId.isNotEmpty())

    @JvmStatic
    fun lockHotbarWheel(): Boolean = enabled && noHotbarScroll

    /** In dungeons a shovel (AOTV and the like) right-clicked on grass makes no path on your side; the click still goes out. */
    @JvmStatic
    fun noShovelPath(stack: net.minecraft.world.item.ItemStack): Boolean =
        com.adm.utils.skyblock.dungeon.DungeonUtils.inDungeons && stack.`is`(net.minecraft.tags.ItemTags.SHOVELS)
}
