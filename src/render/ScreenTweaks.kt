package com.engineerclient.render

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.material.FogType
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.resources.Identifier
import java.time.LocalTime

object ScreenTweaks : Module(
    name = "Screen Tweaks",
    category = Category.RENDER,
    description = "Removes vanilla screen effects and overlays, darkens the screen, changes the sky time.",
    key = null,
) {
    private val noVignette by BooleanSetting("No Vignette", false, desc = "No dark screen edges.")
    private val noWaterOverlay by BooleanSetting("No Water Overlay", false, desc = "No underwater screen texture.")
    private val noSuffocationOverlay by BooleanSetting("No Suffocation Overlay", false, desc = "No block texture when your head is inside a block.")
    private val hidePotionHud by BooleanSetting("Hide Potion Effect HUD", false, desc = "Hides the effect icons at the top right.")
    private val hideInventoryEffects by BooleanSetting("Hide Inventory Effects", false, desc = "Hides the effect list beside the inventory.")
    private val noFog by BooleanSetting("No Fog", false, desc = "No world fog; lava, water, blindness and darkness fog stay.")
    private val fullbright by BooleanSetting("Fullbright", false, desc = "Everything fully lit.")
    private val noTextShadow by BooleanSetting("No Text Shadow", false, desc = "Text is drawn without its shadow.")
    private val noPaneHighlight by BooleanSetting("No Glass Pane Highlight", false, desc = "No hover highlight on unnamed glass panes in menus.")
    private val hideOffhandBackground by BooleanSetting("Hide Offhand Slot Background", false, desc = "No shield outline in the empty offhand slot.")
    private val sidebarColors by BooleanSetting("Sidebar Colors", false, desc = "Custom scoreboard background colors.")
    private val sidebarTitleColor by ColorSetting("Sidebar Title Color", Color(0, 0, 0, 0.4f), true, desc = "Behind the scoreboard title.").withDependency { sidebarColors }
    private val sidebarBodyColor by ColorSetting("Sidebar Body Color", Color(0, 0, 0, 0.3f), true, desc = "Behind the scoreboard lines.").withDependency { sidebarColors }
    private val darkMode by BooleanSetting("Dark Mode", false, desc = "Darkens the screen.")
    private val darkOpacity by NumberSetting("Dark Mode Opacity", 25, 1..80, 1, desc = "Strength of the dark tint.", unit = "%").withDependency { darkMode }
    private val darkTintHud by BooleanSetting("Dark Mode Tint HUD", false, desc = "The tint covers the HUD too.").withDependency { darkMode }

    private enum class Time(val ticks: Long) { DAY(1000), NOON(6000), SUNSET(12000), NIGHT(13000), MIDNIGHT(18000), SUNRISE(23000), REAL_TIME(-1), CUSTOM(-1) }

    private val timeChanger by BooleanSetting("Time Changer", false, desc = "Changes the time of day the sky shows.")
    private val timeMode by SelectorSetting("Time", Time.NOON, desc = "Time of day to show.").withDependency { timeChanger }
    private val customTime by NumberSetting("Custom Time", 6000, 0..23999, 100, desc = "World time in ticks.").withDependency { timeChanger && timeMode == Time.CUSTOM }

    @JvmField var extractingSky = false

    fun noVignette() = enabled && noVignette
    fun noWaterOverlay() = enabled && noWaterOverlay
    fun noSuffocationOverlay() = enabled && noSuffocationOverlay
    fun hidePotionHud() = enabled && hidePotionHud
    fun hideInventoryEffects() = enabled && hideInventoryEffects
    fun noFog(): Boolean {
        if (!enabled || !noFog) return false
        val player = mc.player ?: return true
        return mc.gameRenderer.mainCamera().fluidInCamera == FogType.NONE &&
            !player.hasEffect(MobEffects.BLINDNESS) && !player.hasEffect(MobEffects.DARKNESS)
    }
    fun fullbright() = enabled && fullbright
    fun noTextShadow() = enabled && noTextShadow

    fun hidesPaneHighlight(slot: Slot?): Boolean {
        if (!enabled || !noPaneHighlight || slot == null) return false
        val stack = slot.item
        return BuiltInRegistries.ITEM.getKey(stack.item).path.endsWith("glass_pane") && stack.hoverName.string.isBlank()
    }

    fun slotIcon(icon: Identifier?): Identifier? =
        if (enabled && hideOffhandBackground && icon == InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD) null else icon

    fun drawDarkMode(graphics: GuiGraphicsExtractor, afterHud: Boolean) {
        if (!enabled || !darkMode || darkTintHud != afterHud) return
        val alpha = (darkOpacity * 255 / 100) shl 24
        graphics.fill(0, 0, mc.window.guiScaledWidth, mc.window.guiScaledHeight, alpha)
    }

    fun changesTime() = enabled && timeChanger

    fun time(): Long = when (timeMode) {
        Time.REAL_TIME -> LocalTime.now().let { Math.floorMod(it.hour * 1000L + it.minute * 1000L / 60 - 6000, 24000L) }
        Time.CUSTOM -> customTime.toLong()
        else -> timeMode.ticks
    }

    fun sidebarColor(title: Boolean, original: Int): Int {
        if (!enabled || !sidebarColors) return original
        return if (title) sidebarTitleColor.rgba else sidebarBodyColor.rgba
    }
}
