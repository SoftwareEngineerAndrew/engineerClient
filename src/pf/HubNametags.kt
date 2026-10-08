package com.engineerclient.pf

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.render.drawText
import net.minecraft.world.entity.EntityAttachment
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * In the Dungeon Hub, every real player gets a stat line floating over their nametag — the same
 * numbers and colours as Party Finder Stats, from the same store ([PlayerStats]):
 *
 * ```
 *   §e47.3 §8| §b41.2k §8| §d4:31
 * ```
 *
 * Catacombs level, total secrets, and the S+ time on a chosen floor (F7 by default — the tooltip
 * reads the listing's floor, but a hub has no listing, so here it is a setting).
 *
 * Dungeon Hub only, so the busy main hub stays uncluttered — read live off the tab list's
 * "Area: Dungeon Hub" info line, the same line Odin's LocationUtils parses (Odin only parses it
 * while its cached area is Unknown, so a packet landing before its world-load reset is missed for
 * the whole lobby; reading the tab list directly has no ordering to get wrong). NPCs pose as players
 * all over the hub; real players are the ones with a version-4 UUID.
 */
object HubNametags : Module(
    name = "Hub Nametag Stats",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Catacombs level, secrets and S+ floor PB over players' nametags in the Dungeon Hub.",
    toggled = true,
) {
    private val showCata by BooleanSetting("Cata Level", true, desc = "Catacombs level, one decimal.")
    private val showSecrets by BooleanSetting("Secrets", true, desc = "Total secrets found, in thousands.")
    private val showPb by BooleanSetting("Floor PB", true, desc = "S+ time on the chosen floor.")
    /** A floor as the profile keys it: catacombs or master mode, floor "0".."7" (Entrance is 0). */
    enum class PbFloor(val mode: String, val floor: String) {
        E("f", "0"), F1("f", "1"), F2("f", "2"), F3("f", "3"), F4("f", "4"), F5("f", "5"), F6("f", "6"), F7("f", "7"),
        M1("m", "1"), M2("m", "2"), M3("m", "3"), M4("m", "4"), M5("m", "5"), M6("m", "6"), M7("m", "7"),
    }

    private val pbFloor by SelectorSetting("PB Floor", PbFloor.F7, desc = "Which floor the PB column shows.")
    private val scale by NumberSetting("Text Scale", 1f, 0.5..2.0, 0.1f, desc = "The scale of the stat line.")
    private val gap by NumberSetting("Gap", 0f, 0.0..1.0, 0.05f, desc = "Extra space between the player's nametag and the stat line, in blocks.")

    /** Vanilla draws name tags within 64 blocks of the camera and the below-name score within 10. */
    private const val RANGE_SQ = 64.0 * 64.0
    private const val SCORE_RANGE_SQ = 10.0 * 10.0

    /** One line of vanilla name-tag text: 9 px at the name tag's 0.025 scale, with its 1.15 line spacing. */
    private const val LINE = 9 * 1.15 * 0.025

    init {
        on<RenderExtractEvent> {
            if (!inDungeonHub()) return@on
            val level = mc.level ?: return@on
            val me = mc.player ?: return@on
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
            val cam = mc.gameRenderer.mainCamera().position()
            for (p in level.players()) {
                if (p === me || !p.isAlive || p.isInvisible) continue
                if (p.uuid.version() != 4) continue // hub NPCs pose as players; real ones are v4
                val pos = p.getPosition(pt)
                if (pos.distanceToSqr(cam) > RANGE_SQ) continue
                val nameTop = nameTop(p, pos, cam, pt) ?: continue
                val line = lineFor(p.name.string, pbFloor.mode, pbFloor.floor)
                if (line.isEmpty()) continue
                // drawText puts the top of the text at pos, as name tags do: one line (at our scale) above
                // the name's top leaves the same space vanilla leaves between the name and the score.
                drawText(line, pos.add(0.0, nameTop + LINE * scale + gap, 0.0), scale, false)
            }
        }

        // Odin's Module only EventBus-subscribes on an enabled TRANSITION (onEnable) or, in the
        // constructor, when alwaysActive. A module born on (toggled = true) whose saved state is
        // also on never transitions, so its listeners register but never dispatch. Mirror the
        // alwaysActive path; the set-based bus keeps a later toggle's (un)subscribe idempotent.
        if (enabled) EventBus.subscribe(this)
    }

    /**
     * Where vanilla draws the top of [p]'s name, above their feet: the NAME_TAG attachment plus 0.5, and
     * one line higher when the below-name score (Hypixel's health) sits under it - which the game shows
     * only within 10 blocks of the camera. Null: no name tag.
     */
    private fun nameTop(p: Player, pos: Vec3, cam: Vec3, pt: Float): Double? {
        val attachment = p.attachments.getNullable(EntityAttachment.NAME_TAG, 0, p.getYRot(pt)) ?: return null
        val scoreShown = p.belowNameDisplay() != null && pos.distanceToSqr(cam) < SCORE_RANGE_SQ
        return attachment.y + 0.5 + if (scoreShown) LINE else 0.0
    }

    /** The tab list's "Area: Dungeon Hub" info line (all entries: the info lines are fake players that need not be listed). */
    private fun inDungeonHub(): Boolean {
        val infos = mc.connection?.onlinePlayers ?: return false
        return infos.any { info ->
            val s = info.tabListDisplayName?.string?.trim() ?: return@any false
            s.startsWith("Area:") && "Dungeon Hub" in s
        }
    }

    private fun lineFor(name: String, mode: String, floor: String): String =
        when (val entry = PlayerStats.lookup(name)) {
            is PlayerStats.Loading -> "§7…"
            is PlayerStats.Failed -> "§c?"
            is PlayerStats.Ready -> buildList {
                if (showCata) add("§e" + PlayerStats.cataStr(entry.stats))
                if (showSecrets) add("§b" + PlayerStats.secretsStr(entry.stats.secrets))
                if (showPb) add("§d" + PlayerStats.pbStr(entry.stats, mode, floor))
            }.joinToString(" §8| ")
        }
}
