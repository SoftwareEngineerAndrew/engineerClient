package com.engineerclient

import com.odtheking.odin.clickgui.settings.impl.HudElement
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.render.roundedFill
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.phys.AABB

/*
 * Minecraft 26.1.2 build: Odin 0.3.4's API, in the shape the rest of the code (written against
 * Odin 0.3.6) expects. Odin 0.3.4's selectors hold the chosen option's index, and its box style is
 * an Int (0 filled, 1 outline, 2 both).
 */

/** Odin 0.3.6's box styles, in Odin 0.3.4's order. */
enum class BoxStyle { FILLED, OUTLINE, FILLED_OUTLINE }

fun RenderEvent.Extract.drawStyledBox(aabb: AABB, color: Color, style: BoxStyle, depth: Boolean = true) =
    drawStyledBox(aabb, color, style.ordinal, depth)

/**
 * An enum's constant as Odin 0.3.6 shows it in a selector: its own label where it has one (a
 * toString of its own), else ONLY_IN_BOSS is "Only In Boss".
 */
fun enumLabel(e: Enum<*>): String =
    e.toString().takeIf { it != e.name }
        ?: e.name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercaseChar) }

/** Odin 0.3.6's enum selector, as an Odin 0.3.4 selector over the constants' labels (its value is the index). */
fun <E : Enum<E>> enumSelector(name: String, default: E, desc: String): SelectorSetting =
    SelectorSetting(name, enumLabel(default), default.declaringJavaClass.enumConstants.map(::enumLabel), desc)

/** A selector's place in its option list (Odin 0.3.4's selectors hold the index itself). */
var SelectorSetting.index: Int
    get() = value
    set(i) { value = i }

/** Odin 0.3.6's rounded rectangle (corners to corners, a float radius), as Odin 0.3.4's roundedFill. */
fun GuiGraphicsExtractor.roundedRect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, radius: Float) =
    roundedFill(x0, y0, x1, y1, color, radius.toInt())

/** Odin 0.3.6's filled and outlined rounded rectangle, as Odin 0.3.4's outlined roundedFill. */
fun GuiGraphicsExtractor.roundedRectOutlined(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, outlineColor: Int, outlineWidth: Float, radius: Float) =
    roundedFill(x0, y0, x1, y1, color, radius.toInt(), outlineColor, outlineWidth)

/** Hidden in P3 Sim practice: Term Info (Section Time stays: the sim feeds it) and Odin's Goldor Hud. */
private val PRACTICE_HIDDEN = setOf("Term Info", "Goldor Hud")

/** Each Odin HUD element's setting name (HudElementMixin sees only the element). */
private val hudNames = java.util.WeakHashMap<HudElement, String>()

/** HudElementMixin: [hud] draws nothing in P3 Sim practice ([PRACTICE_HIDDEN]). */
fun hiddenInPractice(hud: HudElement): Boolean {
    if (!(com.engineerclient.p3sim.Practice.active && com.engineerclient.p3sim.P3Sim.inSim)) return false
    val name = hudNames.getOrPut(hud) { ModuleManager.hudSettingsCache.firstOrNull { it.value === hud }?.name ?: "" }
    return name in PRACTICE_HIDDEN
}
