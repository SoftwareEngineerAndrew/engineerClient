package com.engineerclient

import com.odtheking.odin.clickgui.settings.impl.SelectorSetting

/*
 * Odin 0.3.6's SelectorSetting picks a value of an enum (shown by its toString) instead of an
 * index into a list of strings. These are engineerClient's selectors' choices, in their old order
 * and with their old labels, and [index] reads or sets one by position as the old Int value did.
 */

/** The choice's position in the selector, the Int Odin's selectors held before 0.3.6. */
var <E : Enum<E>> SelectorSetting<E>.index: Int
    get() = options.indexOf(value)
    set(i) { value = options[i] }

enum class HeadSmoothingChoice(private val label: String) {
    VANILLA("Vanilla"), RAW("Raw"), CUSTOM("Custom");
    override fun toString() = label
}

enum class KillersChoice(private val label: String) {
    DUO("Duo"), TRIO("Trio"), QUAD("Quad");
    override fun toString() = label
}

enum class BrRoleChoice(private val label: String) {
    ALL_BOXES("All Boxes"), DOOR("Door"), ROLE_1("Role 1"), ROLE_2("Role 2"), ROLE_3("Role 3"), ROLE_4("Role 4");
    override fun toString() = label
}

enum class ClassChoice(private val label: String) {
    HEALER("Healer"), BERSERK("Berserk"), ARCHER("Archer"), TANK("Tank"), MAGE("Mage");
    override fun toString() = label
}

enum class DeathTicksChoice(private val label: String) {
    OFF("Off"), WARN("Warn"), MASKS("Masks");
    override fun toString() = label
}

enum class TerminalChoice(private val label: String) {
    RANDOM("Random"), ORDER("Order"), PANES("Panes"), RUBIX("Rubix"), STARTS_WITH("Starts With"), SELECT("Select"), MELODY("Melody");
    override fun toString() = label
}

enum class MaskChoice(private val label: String) {
    SPIRIT("Spirit"), BONZO("Bonzo");
    override fun toString() = label
}

enum class PbTypeChoice(private val label: String) {
    S_PLUS("S+"), S("S"), ANY("Any");
    override fun toString() = label
}

enum class RecordWhereChoice(private val label: String) {
    DUNGEONS("Dungeons"), DUNGEONS_AND_HUB("Dungeons + Hub"), EVERYWHERE("Everywhere");
    override fun toString() = label
}

enum class SplitsLookChoice(private val label: String) {
    ODIN("Odin Splits"), ENGINEER("Engineer Splits");
    override fun toString() = label
}

enum class PaceFloorChoice(private val label: String) {
    F7("F7"), M7("M7");
    override fun toString() = label
}
