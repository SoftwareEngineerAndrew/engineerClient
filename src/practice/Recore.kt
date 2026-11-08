package com.engineerclient.practice

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity

/**
 * F7/M7 core leap (was Term Info's Core Leap, then Sub Splits'). Paste Last In Core is set here and sent by
 * DungeonSplits, whose Goldor tracking times everyone into the core (it runs for this with Sub Splits off). Core Leap Highlight: once S3 is done, the side of the screen where the first teammate
 * inside the core will be in Odin's Leap Menu, in gold, so you're already flicking there when you open the menu.
 * Highlight All Players In Core: every teammate who got to the core before you, each in their own part of the
 * screen. Behind terminals too: only ever drawn as part of the HUD, which every screen draws over, so it never
 * covers or recolours a slot.
 */
object Recore : Module(
    name = "Recore",
    category = Category.custom("Engineer Client", 860, 10),
    description = "The core after S4: the Core Leap Highlight (where the teammates already in the core are in Odin's Leap Menu, so you flick there as it opens) and Paste Last In Core.",
    key = null,
) {
    val pasteLast by BooleanSetting("Paste Last In Core", true, desc = "Says in party chat who was last into the core after S4 and their time (e.g. \"Last in core: Name 1.35s\"), once all five are in. Only when every time is exact (each teammate in view just before they came in) and nobody has died.")
    private val coreLeap by BooleanSetting("Core Leap Highlight", true, desc = "Once S3 is done, the side of the screen where the first teammate inside the core will be in Odin's Leap Menu, in gold, so you flick there as the menu opens. Shown behind terminals too, never over them.")
    private val allInCore by BooleanSetting("Highlight All Players In Core", true, desc = "Highlights every teammate who made it to the core before you, each where they'll be in the leap menu, not just the first one. All of them go once you're in the core yourself (with Hide When In Core).").withDependency { coreLeap }
    private val look = LeapHighlightLook(this, "Core Leap Look", { coreLeap }, "someone is in the core",
        "Shows the highlight now, top right, as if someone were in the core (also over this menu), to try the looks below; with Highlight All Players In Core, bottom left too. Turn it off after.",
        Color(255, 170, 0, 121 / 255f))
    private val hideInCore by BooleanSetting("Hide When In Core", true, desc = "Off for the rest of the run once you've been in the core yourself (after S3), even if you step back out.").withDependency { look.open }

    private val CONTROL_CODES = Regex("§.")
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPEN = "The Core entrance is opening!"
    private const val GATE = "The gate has been destroyed!"
    private val TASK_COUNT = Regex("^\\w+ (?:activated|completed) a (?:terminal|lever|device)! \\((\\d+)/(\\d+)\\)$")

    /** Terminals going (Goldor's first line or the first task message, to the core opening). */
    private var terms = false
    /** S3 done: watching for teammates in the core, until Necron or a new world. */
    @Volatile private var coreWatch = false
    /**
     * Sections over, counted as Sub Splits does: a section is over once its last completion (n/n) and its gate
     * are both in, in either order. Doesn't lean on every single term/lever/device message being read the way
     * Term Info's section count does, so a missed one doesn't keep this from showing in S4.
     */
    private var sectionsOver = 0
    /** You've been in the core since watching started: it stays off until the next run. */
    private var selfBeenInCore = false
    private var lastIn = false
    private var gateIn = false

    private fun countSectionEnd(msg: String) {
        if (sectionsOver >= 3) return
        if (msg == GATE) gateIn = true
        else {
            val m = TASK_COUNT.matchEntire(msg) ?: return
            if (m.groupValues[1] != m.groupValues[2]) return
            lastIn = true
        }
        if (!lastIn || !gateIn) return
        sectionsOver++; lastIn = false; gateIn = false
        if (sectionsOver == 3) coreWatch = true
    }

    /** Term Info's own count got S3 done (it reads every task message): the core's watched from now. */
    fun s3Done() { if (enabled) coreWatch = true }

    /**
     * The P3 sim starting at section [section] (2-4: the earlier ones done, no Goldor line; 5 the core):
     * counts from that section, as if this had seen the run so far.
     */
    @JvmStatic
    fun simStart(section: Int) {
        reset()
        if (section !in 1..4) return
        terms = true
        sectionsOver = section - 1
        // S1-S3 done: the core's watched from S4's start (as simS4() has it on the host's game).
        coreWatch = section == 4
    }

    /** Teammates inside the core box, by name, with when they were first seen there (ms). */
    private val inCoreSince = LinkedHashMap<String, Long>()

    /** DungeonSplits' core box (and P3 Sim's CORE_BOX): reaches down to the core's floor. */
    private fun inCore(p: Entity) = p.x >= 39 && p.x < 71 && p.y < 155.5 && p.z >= 54 && p.z < 118

    /** Your own P3 sim in S4 (straight off the sim, as Term Info has it: a restart or later start has no chat). */
    private fun simS4(): Boolean {
        if (!com.engineerclient.p3sim.P3Sim.localSim) return false
        return (com.engineerclient.p3sim.Fight.phase as? com.engineerclient.p3sim.GoldorPhase)?.section == 4
    }

    private fun watchingCore() = coreWatch || simS4()

    /** Keeps [inCoreSince] up to date: teammates in the core box, by when they were first seen there. */
    private fun trackCore() {
        if (!enabled || !coreLeap || !watchingCore()) { inCoreSince.clear(); return }
        val level = mc.level ?: return
        if (mc.player?.let { inCore(it) && !LeapTeammates.inFlight(it) } == true) selfBeenInCore = true
        val now = System.currentTimeMillis()
        for (m in DungeonUtils.leapTeammates) {
            if (m.name == "Empty") continue
            val p = LeapTeammates.entity(m, level)
            // Unseen (out of render distance) keeps its place; dead or out of the box loses it. Passing
            // through on a leap's drag is neither: not in, and whatever was known stays.
            if (p == null) continue
            if (com.engineerclient.DeadPlayers.isDead(m) || !inCore(p)) inCoreSince.remove(m.name)
            else if (!LeapTeammates.inFlight(p)) inCoreSince.putIfAbsent(m.name, now)
        }
    }

    /**
     * The part of the screen where the first teammate seen in the core (or each one, with Highlight All Players In Core) sits
     * in Odin's Leap Menu, so the flick is already on its way when the menu opens ([LeapHighlightLook]).
     */
    private fun drawRecore(gfx: GuiGraphicsExtractor) {
        if (!enabled || !coreLeap || mc.gui.hud.isHidden) return
        val self = mc.player ?: return
        if (!look.screenAllows()) return
        val targets = if (look.preview) {
            val since = System.currentTimeMillis() / 3000 * 3000
            listOfNotNull(
                LeapHighlightLook.Target(1, "Teammate", "mage", self.skin, since),
                if (allInCore) LeapHighlightLook.Target(2, "Teammate", "archer", self.skin, since) else null,
            )
        } else {
            if (!watchingCore()) return
            if (hideInCore && selfBeenInCore) return
            val mates = DungeonUtils.leapTeammates
            // Earliest first: a Side or Top / Bottom half two of them share is drawn once, as the first one's.
            val inOrder = inCoreSince.entries.sortedBy { it.value }
            (if (allInCore) inOrder else inOrder.take(1)).mapNotNull { (n, since) ->
                val i = mates.indexOfFirst { it.name == n }.takeIf { it in 0..3 } ?: return@mapNotNull null
                LeapHighlightLook.Target(i, mates[i].name, mates[i].clazz.name.lowercase(), mates[i].playerSkin ?: self.skin, since)
            }
        }
        look.draw(gfx, targets)
    }

    private fun reset() {
        terms = false
        coreWatch = false
        inCoreSince.clear()
        sectionsOver = 0; lastIn = false; gateIn = false
        selfBeenInCore = false
    }

    override fun onEnable() {
        // Whatever was half-seen while off is stale.
        reset()
        super.onEnable()
    }

    init {
        on<LevelEvent.Load> { reset() }
        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("engineerclient", "recore")) { gfx, _ ->
            // The HUD is drawn under any open screen too, so this is also the behind-the-terminal draw.
            com.engineerclient.EngineerClient.safely("recore") { trackCore(); drawRecore(gfx) }
        }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CONTROL_CODES, "")
            when {
                // A lever can be flicked the tick before Goldor speaks: its message has started terminals already.
                msg == GOLDOR_START -> if (!terms) { reset(); terms = true }
                msg == CORE_OPEN -> { terms = false; coreWatch = true }
                msg.startsWith("[BOSS] Necron:") -> coreWatch = false
                else -> {
                    // The first task message starts terminals too (Goldor's line missed).
                    if (!terms && TASK_COUNT.matches(msg)) { reset(); terms = true }
                    if (terms) countSectionEnd(msg)
                }
            }
        }
    }
}
