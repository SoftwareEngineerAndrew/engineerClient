package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.engineerclient.practice.LeapHighlightLook
import com.engineerclient.practice.LeapTeammates
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.BlockInteractEvent
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.SetSlotEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.FormatCache
import com.adm.utils.PersonalBest
import com.adm.utils.equalsOneOf
import com.adm.utils.itemId
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.render.drawCustomBeacon
import com.adm.utils.render.drawFilledBox
import com.adm.utils.render.drawTracer
import com.adm.utils.render.textDim
import com.adm.utils.render.drawWireFrameBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.Floor
import com.adm.utils.skyblock.dungeon.M7Phases
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import java.util.Locale
import kotlin.math.hypot

object Relics : Module(
    name = "Relics",
    category = Category.BOSS,
    description = "P5 relics: correct cauldron beacon and highlight, wrong cauldron click block, place times, PBs and a leap highlight.",
    key = null,
) {
    private val relicBeacon by BooleanSetting("Relic Beacon", true, desc = "Draws a beacon at your relic's cauldron.")
    private val cauldronHighlight by BooleanSetting("Cauldron Highlight", true, desc = "Boxes your relic's cauldron, seen through walls.")
    private val cauldronTracer by BooleanSetting("Cauldron Tracer", false, desc = "Draws a line to your relic's cauldron.")
    private val spawnHud by HUD("Relic Spawn HUD", "Time until the relics appear, from Necron's death throes.") { example ->
        if (example) return@HUD textDim("§dRelics: §a2.95s", 0, 0, Colors.WHITE)
        val t = spawnIn
        if (!enabled || t <= 0) 0 to 0 else textDim(spawnTexts(t.toDouble()), 0, 0, Colors.WHITE)
    }
    private val blockWrong by BooleanSetting("Block Wrong Cauldron", true, desc = "Right clicks on a cauldron that isn't your relic's do nothing.")
    private val allTimes by BooleanSetting("All Relic Times", true, desc = "Once all five relics are in, says in chat when each was placed.")
    private val leapHighlight by BooleanSetting("Relic Leap Highlight", true, desc = "Before P5, where the teammate below Necron's platform is in the leap menu.")
    private val look = LeapHighlightLook(this, "Relic Leap Look", { leapHighlight }, "someone is below Necron's platform",
        "Shows the highlight now, top right, as if someone were below Necron's platform. Turn it off after.",
        Color(170, 0, 255, 121 / 255f))

    private val relicPBs = PersonalBest(this, "Relics")

    private enum class Relic(val id: String, val colorCode: Char, val color: Color, val cauldron: BlockPos) {
        Green("GREEN_KING_RELIC", 'a', Colors.MINECRAFT_GREEN, BlockPos(49, 7, 44)),
        Red("RED_KING_RELIC", 'c', Colors.MINECRAFT_RED, BlockPos(51, 7, 42)),
        Purple("PURPLE_KING_RELIC", '5', Colors.MINECRAFT_DARK_PURPLE, BlockPos(54, 7, 41)),
        Orange("ORANGE_KING_RELIC", '6', Colors.MINECRAFT_GOLD, BlockPos(57, 7, 42)),
        Blue("BLUE_KING_RELIC", 'b', Colors.MINECRAFT_BLUE, BlockPos(59, 7, 44));

        val box = AABB(cauldron)
        val fill = color.withAlpha(0.3f)
    }

    private val PICKED = Regex("^(.+) picked the Corrupted (Red|Purple|Orange|Green|Blue) Relic!$")
    private val RELIC_BOX = AABB(51.5, 3.0, 73.5, 57.5, 8.0, 79.5)

    private var currentRelic: Relic? = null
    private var relicPlaceTick = -1
    private var p5Ticks = -1
    private val placed = HashMap<Relic, Int>()
    private var sent = false
    private val inBoxSince = LinkedHashMap<String, Long>()
    /** Server ticks until the relics appear: 0 with none coming, -1 once they're due this run. */
    @Volatile private var spawnIn = 0
    private val spawnTexts = FormatCache(4) { "§dRelics: §a${String.format(Locale.ROOT, "%.2f", it / 20.0)}s" }

    private fun relicAt(pos: BlockPos) = Relic.entries.firstOrNull { pos.x == it.cauldron.x && pos.z == it.cauldron.z && (pos.y == 7 || pos.y == 6) }

    private val RELICS = Relic.values()
    private const val THROES_TO_RELICS = 59

    private fun nearOpenCauldron(x: Double, z: Double): Boolean {
        for (r in RELICS) if (r !in placed && hypot(x - r.cauldron.x - 0.5, z - r.cauldron.z - 0.5) < 1.5) return true
        return false
    }

    private fun reset() {
        currentRelic = null; relicPlaceTick = -1; p5Ticks = -1; placed.clear(); sent = false; inBoxSince.clear(); spawnIn = 0
    }

    private fun beforeP5() = DungeonUtils.isFloor(7) && DungeonUtils.inBoss && DungeonUtils.getF7Phase() != M7Phases.P5

    private fun trackBox() {
        val level = mc.level
        if (!enabled || !leapHighlight || level == null || !beforeP5()) { inBoxSince.clear(); return }
        val now = System.currentTimeMillis()
        for (m in DungeonUtils.leapTeammates) {
            if (m.name == "Empty") continue
            val p = LeapTeammates.entity(m, level)
            if (p == null || com.engineerclient.DeadPlayers.isDead(m) || !RELIC_BOX.contains(p.position())) inBoxSince.remove(m.name)
            else if (!LeapTeammates.inFlight(p)) inBoxSince.putIfAbsent(m.name, now)
        }
    }

    private fun drawHighlight(gfx: GuiGraphicsExtractor) {
        if (!enabled || !leapHighlight || mc.gui.hud.isHidden) return
        val self = mc.player ?: return
        if (!look.screenAllows() || (inBoxSince.isEmpty() && !look.preview)) return
        val targets = if (look.preview) {
            listOf(LeapHighlightLook.Target(1, "Teammate", "mage", self.skin, System.currentTimeMillis() / 3000 * 3000))
        } else {
            val mates = DungeonUtils.leapTeammates
            inBoxSince.entries.sortedBy { it.value }.mapNotNull { (n, since) ->
                val i = mates.indexOfFirst { it.name == n }.takeIf { it in 0..3 } ?: return@mapNotNull null
                LeapHighlightLook.Target(i, mates[i].name, mates[i].clazz.name.lowercase(), mates[i].playerSkin ?: self.skin, since)
            }
        }
        look.draw(gfx, targets)
    }

    init {
        on<SetSlotEvent> {
            if (DungeonUtils.getF7Phase() != M7Phases.P5 || menu !== mc.player?.inventoryMenu || slotIndex != 44) return@on
            currentRelic = Relic.entries.find { it.id == itemStack.itemId }
            if (currentRelic != null && relicPlaceTick == -1) relicPlaceTick = 0
        }

        on<MessageEvent.Chat> {
            val m = PICKED.matchEntire(message) ?: return@on
            if (m.groupValues[1] == mc.user.name) currentRelic = Relic.entries.find { it.name == m.groupValues[2] }
        }

        on<BlockInteractEvent> {
            if (DungeonUtils.getF7Phase() != M7Phases.P5) return@on
            val relic = currentRelic ?: return@on
            val clicked = relicAt(pos) ?: return@on
            val name = mc.player?.mainHandItem?.hoverName?.string ?: ""
            // The held item can lag behind the pickup, so the SkyBlock Menu and arrows still count as the relic.
            val holdingRelic = name.contains("Relic") || name.contains("SkyBlock Menu", true) || name.contains("Arrow")
            if (blockWrong && !(holdingRelic && clicked == relic)) {
                cancel()
                EngineerClient.chat("§cBlocked a click on the wrong cauldron.")
                return@on
            }
            if (clicked != relic || relicPlaceTick < 0 || !mc.level?.getBlockState(pos)?.block.equalsOneOf(Blocks.CAULDRON, Blocks.ANVIL)) return@on
            relicPBs.time(relic.name, relicPlaceTick / 20f, message = "§${relic.colorCode}${relic.name} relic §7placed in §6")
            relicPlaceTick = -2
        }

        on<RenderExtractEvent> {
            if (DungeonUtils.getF7Phase() != M7Phases.P5) return@on
            val relic = currentRelic ?: return@on
            if (relicBeacon) drawCustomBeacon("", relic.cauldron, relic.color, distance = false)
            if (cauldronTracer) drawTracer(relic.box.center, relic.color, depth = false)
            if (cauldronHighlight) {
                drawFilledBox(relic.box, relic.fill, depth = false)
                drawWireFrameBox(relic.box, relic.color, depth = false)
            }
        }

        // His death throes open with this sound; the relics appear on his death, 59 ticks after it.
        onReceive<ClientboundSoundPacket> {
            if (spawnIn != 0 || volume != 15f || pitch != 1f || sound.value() != SoundEvents.WITHER_HURT) return@onReceive
            if (DungeonUtils.floor == Floor.M7 && DungeonUtils.getF7Phase() == M7Phases.P4) spawnIn = THROES_TO_RELICS
        }

        on<TickEvent.Server> {
            if (spawnIn > 0) { spawnIn--; if (spawnIn == 0) spawnIn = -1 }
            if (DungeonUtils.getF7Phase() == M7Phases.P5 && relicPlaceTick >= 0) relicPlaceTick++
            if (p5Ticks < 0 && DungeonUtils.inBoss && DungeonUtils.getF7Phase() == M7Phases.P5) p5Ticks = 0
            else if (p5Ticks >= 0) p5Ticks++
        }

        on<TickEvent.End> {
            if (!allTimes || sent || p5Ticks < 0) return@on
            val level = mc.level ?: return@on
            for (e in level.entitiesForRendering()) {
                if (e !is ArmorStand) continue
                if (!nearOpenCauldron(e.x, e.z)) continue
                val id = e.getItemBySlot(EquipmentSlot.MAINHAND).itemId.ifEmpty { e.getItemBySlot(EquipmentSlot.HEAD).itemId }
                if (!id.contains("RELIC")) continue
                for (r in Relic.entries) {
                    if (r !in placed && hypot(e.x - r.cauldron.x - 0.5, e.z - r.cauldron.z - 0.5) < 1.5) placed[r] = p5Ticks
                }
            }
            if (placed.size < Relic.entries.size) return@on
            sent = true
            for (r in Relic.entries.sortedBy { placed[it] }) {
                EngineerClient.chat("§${r.colorCode}${r.name} §aRelic placed in §e${String.format(Locale.ROOT, "%.2f", placed.getValue(r) / 20.0)}s§a.")
            }
        }

        on<LevelEvent.Load> { reset() }

        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("engineerclient", "relic_leap")) { gfx, _ ->
            EngineerClient.safely("relic leap") { trackBox(); drawHighlight(gfx) }
        }
    }
}
