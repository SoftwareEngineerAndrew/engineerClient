package com.engineerclient

import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawStyledBox
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

/** An enum's constant as Odin 0.3.6 shows it in a selector: ONLY_IN_BOSS is "Only In Boss". */
fun enumLabel(e: Enum<*>): String =
    e.name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercaseChar) }

/** Odin 0.3.6's enum selector, as an Odin 0.3.4 selector over the constants' labels (its value is the index). */
fun <E : Enum<E>> enumSelector(name: String, default: E, desc: String): SelectorSetting =
    SelectorSetting(name, enumLabel(default), default.declaringJavaClass.enumConstants.map(::enumLabel), desc)

/** A selector's place in its option list (Odin 0.3.4's selectors hold the index itself). */
var SelectorSetting.index: Int
    get() = value
    set(i) { value = i }
