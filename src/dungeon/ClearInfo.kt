package com.engineerclient.dungeon

import com.engineerclient.clearbreakdown.ClearBreakdown
import com.engineerclient.lcm.LcmMapInfo
import com.engineerclient.lcm.PaulScore
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.clickgui.settings.impl.StringSetting
import com.adm.features.Category
import com.adm.features.Module
import com.adm.features.impl.boss.ExtraStats
import com.adm.features.impl.dungeon.MapInfo
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.Colors

/** Score and stats through the clear, one section each; the logic lives in the section objects. */
object ClearInfo : Module(
    name = "Clear Info",
    key = null,
    category = Category.DUNGEON,
    description = "Map info, score times, room alerts, end of run stats and the clear breakdown.",
) {
    // ------------------------------------------------------------------ Map Info
    private val mapDrop by DropdownSetting("Map Info", true, desc = "Score, secrets and the missing bonus, as LCM shows them.")
    val mapInfo by BooleanSetting("Enable Map Info", true, desc = "The 300 alert, portal highlight and the HUD.").withDependency { mapDrop }
    private val mapHud by HUD("Map Info HUD", "Secrets found, missing and total, unknown secrets, score and the missing bonus.") { example ->
        if (!mapInfo && !example) 0 to 0 else LcmMapInfo.render(this, example)
    }.withDependency { mapDrop && mapInfo }
    val disableInBoss by BooleanSetting("Disable in boss", true, desc = "No HUD in the boss.").withDependency { mapDrop && mapInfo }
    val scoreAlert by BooleanSetting("300 Score Alert", true, desc = "A title and the run time in chat at 300.").withDependency { mapDrop && mapInfo }
    val scoreAlertText by StringSetting("300 Alert Text", "&c300 Score!", desc = "The 300 title; & for colours.", placeholder = "&c300 Score!").withDependency { mapDrop && mapInfo && scoreAlert }
    val highlightPortal by BooleanSetting("Highlight Portal", true, desc = "Highlights the blood portal, colored by score.").withDependency { mapDrop && mapInfo }
    val portalUnderS by ColorSetting("Portal Under S", Colors.MINECRAFT_RED.withAlpha(0.5f), true, desc = "Portal color under 270. Alpha 0 hides it.").withDependency { mapDrop && mapInfo && highlightPortal }
    val portalS by ColorSetting("Portal S", Colors.MINECRAFT_YELLOW.withAlpha(0.5f), true, desc = "Portal color from 270. Alpha 0 hides it.").withDependency { mapDrop && mapInfo && highlightPortal }
    val portalSPlus by ColorSetting("Portal S+", Colors.MINECRAFT_GREEN.withAlpha(0.5f), true, desc = "Portal color from 300. Alpha 0 hides it.").withDependency { mapDrop && mapInfo && highlightPortal }
    val paulScore by SelectorSetting("Paul Score", PaulScore.Mode.AUTO, desc = "Whether Paul's +10 counts: Auto goes by the mayor.").withDependency { mapDrop }

    // ------------------------------------------------------------------ Skip Counter
    private val skipDrop by DropdownSetting("Skip Counter", false, desc = "Secrets skipped against how many 300 lets you skip.")
    private val skipHud by HUD("Skip Counter HUD", "Secrets left behind in rooms you or your team left, and how many you can skip.") { example ->
        SkipCounter.render(this, example)
    }.withDependency { skipDrop }
    val skipBonusM67 by NumberSetting("Planned Bonus M6/M7", 9.0, 0.0..18.0, 1.0, desc = "Bonus score you plan to get: crypts, Mimic, Prince, bats.").withDependency { skipDrop }
    val skipBonusOther by NumberSetting("Planned Bonus Other", 7.0, 0.0..18.0, 1.0, desc = "The same on other floors.").withDependency { skipDrop }
    val skipHideInBoss by BooleanSetting("Hide Skip Counter In Boss", true, desc = "No skip counter in the boss.").withDependency { skipDrop }

    // ------------------------------------------------------------------ Score Time
    private val scoreDrop by DropdownSetting("Score Time", false, desc = "The run time at S and at the S+ secrets.")
    val scoreTime by BooleanSetting("Enable Score Time", true, desc = "Says the run time at each.").withDependency { scoreDrop }
    val showS by BooleanSetting("S Score", true, desc = "When the score reaches 270.").withDependency { scoreDrop && scoreTime }
    val sText by StringSetting("S Text", "&eS", desc = "What the S line says; & for colours.", placeholder = "&eS").withDependency { scoreDrop && scoreTime && showS }
    val showMinSecrets by BooleanSetting("Min Secrets", false, desc = "When the secrets needed for S+ are found.").withDependency { scoreDrop && scoreTime }
    val scoreTitle by BooleanSetting("Score Time Title", true, desc = "A title as well as the chat line.").withDependency { scoreDrop && scoreTime }
    internal val scoreSound = SoundOption(this, "minecraft:entity.experience_orb.pickup", prefix = "Score Time ") { scoreDrop && scoreTime }

    // ------------------------------------------------------------------ Room Alerts
    private val roomDrop by DropdownSetting("Room Alerts", false, desc = "A title when your room is cleared or has all its secrets.")
    val roomAlerts by BooleanSetting("Enable Room Alerts", true, desc = "Titles for the room you are in.").withDependency { roomDrop }
    val cleared by BooleanSetting("Cleared", true, desc = "When your room gets its checkmark.").withDependency { roomDrop && roomAlerts }
    val secretsDone by BooleanSetting("Secrets Done", true, desc = "When your cleared room turns green.").withDependency { roomDrop && roomAlerts }
    val alertStyle by SelectorSetting("Alert Style", RoomAlerts.Style.TITLE, desc = "A title, or the screen's edges glowing in the alert's color.").withDependency { roomDrop && roomAlerts }
    val displayTime by NumberSetting("Display Time", 0.5, 0.1..3.0, 0.1, desc = "How long the alert stays.", unit = "s").withDependency { roomDrop && roomAlerts }
    val keyRooms by SelectorSetting("Key Rooms", RoomAlerts.Rush.NOT, desc = "Key rooms are the ones next to a wither or blood door.").withDependency { roomDrop && roomAlerts }
    val noBlood by BooleanSetting("No Blood Room", false, desc = "No alert for the blood room.").withDependency { roomDrop && roomAlerts }
    internal val roomSound = SoundOption(this, "block.note_block.pling", prefix = "Room Alert ") { roomDrop && roomAlerts }

    // ------------------------------------------------------------------ Extra Stats
    private val statsDrop by DropdownSetting("Extra Stats", false, desc = "Hypixel's end of run stats in one message.")
    val extraStats by BooleanSetting("Enable Extra Stats", true, desc = "Replaces the end of run stats in chat.").withDependency { statsDrop }
    val showBits by BooleanSetting("Show Bits", true, desc = "Show bits earned.").withDependency { statsDrop && extraStats }
    val showClassEXP by BooleanSetting("Show Class EXP", true, desc = "Show class experience.").withDependency { statsDrop && extraStats }
    val showCombatStats by BooleanSetting("Show Combat Stats", true, desc = "Show damage, enemy kills and healing.").withDependency { statsDrop && extraStats }
    val teamStats by SelectorSetting("Show Team Stats", ExtraStats.TeamStats.BOTH, desc = "Personal stats, the team's or both.").withDependency { statsDrop && extraStats }
    val showTeammates by BooleanSetting("Show Teammates", false, desc = "Show teammates.").withDependency { statsDrop && extraStats }

    // ------------------------------------------------------------------ Clear Breakdown
    private val breakdownDrop by DropdownSetting("Clear Breakdown", false, desc = "Everyone's secrets at the end of a run, below Extra Stats.")
    val clearBreakdown by BooleanSetting("Enable Clear Breakdown", true, desc = "Each player's secrets at the end of every run.").withDependency { breakdownDrop }
    val breakdownMode by SelectorSetting("Breakdown", ClearBreakdown.Mode.FULL, desc = "Just secret totals, or rooms cleared and secrets by room too.").withDependency { breakdownDrop && clearBreakdown }

    val mapInfoOn get() = enabled && mapInfo
    val scoreTimeOn get() = enabled && scoreTime
    val roomAlertsOn get() = enabled && roomAlerts
    val extraStatsOn get() = enabled && extraStats
    val clearBreakdownOn get() = enabled && clearBreakdown

    fun install() {
        MapInfo.install()
        ScoreTime.install()
        RoomAlerts.install()
        ExtraStats.install()
        ClearBreakdown.install()
        SkipCounter.install()
    }
}
