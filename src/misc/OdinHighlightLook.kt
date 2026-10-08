package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.Highlight
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.BoxStyle
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.renderBoundingBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Enderman
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB

/**
 * A "Look" for Odin's Highlight: Odin's, or one remade from Blade Addons' Mob Highlight.
 *
 * The Blade look tells mobs apart, each kind in its own colour: starred mobs (Odin's Highlight
 * colour), tanks (Zombie Commander / Lord, Skeleton Lord, Withermancer, Super Archer), minibosses
 * (Lost Adventurer, Angry Archaeologist, Frozen Adventurer, King Midas), Fels, Shadow Assassins and
 * Mimics. A mob is found from its name tag by entity id - the mob is spawned just before its tag,
 * so it is the tag's id less one (three for a Withermancer) - rather than by looking under the tag,
 * so a mob in a crowd is never mixed up with its neighbour. Shadow Assassins have no tag: they are
 * the fake players holding "Silent Death" in leather boots. An invisible Fel shows only its head.
 *
 * Like the Splits look (OdinSplitsLook), the settings are registered into Odin's module and saved
 * with it; Odin's Render Style, Hide non-starred names and Teammate Class Glow still apply.
 * `HighlightRenderMixin` skips Odin's own boxes while this look is on.
 */
object OdinHighlightLook {

    private enum class Look { ODIN, BLADE }
    private enum class Kind { STAR, TANK, MINI, FEL, ASSASSIN, MIMIC }

    private val look = SelectorSetting("Look", Look.ODIN,
        desc = "Odin's Highlight, or Blade: each kind of mob in its own colour (starred, tanks, minibosses, Fels, Shadow Assassins, Mimics), each mob found from its name tag exactly. Added by engineerClient.")

    private val blade: Boolean get() = look.value == Look.BLADE

    private val tankColor = ColorSetting("Tank Color", Color(255, 0, 0, 1f), true, desc = "Blade: Zombie Commanders and Lords, Skeleton Lords, Withermancers and Super Archers.").withDependency { blade }
    private val miniColor = ColorSetting("Miniboss Color", Color(255, 255, 0, 1f), true, desc = "Blade: Lost Adventurers, Angry Archaeologists, Frozen Adventurers and King Midas.").withDependency { blade }
    private val felColor = ColorSetting("Fel Color", Color(0, 255, 255, 1f), true, desc = "Blade: starred Fels.").withDependency { blade }
    private val assassinColor = ColorSetting("Shadow Assassin Color", Color(128, 0, 128, 1f), true, desc = "Blade: Shadow Assassins.").withDependency { blade }
    private val mimicColor = ColorSetting("Mimic Color", Colors.WHITE, true, desc = "Blade: Mimics.").withDependency { blade }
    private val hideInvisible = BooleanSetting("Hide Invisible", true, desc = "Blade: no box on an invisible Shadow Assassin, and only the head of an invisible Fel.").withDependency { blade }
    private val notWhileBlind = BooleanSetting("Not While Blind", true, desc = "Blade: no boxes while you have Blindness.").withDependency { blade }

    /** Read by HighlightRenderMixin: Odin's own boxes are skipped while the Blade look is on. */
    @JvmStatic
    fun replacesOdin(): Boolean = blade

    private val TANKS = listOf("Zombie Commander", "Zombie Lord", "Skeleton Lord", "Withermancer", "Super Archer")
    private val MINIS = listOf("Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer")

    private val found = HashMap<Int, Pair<Entity, Kind>>()
    /** Name tags already looked at (a tag's name never changes once it has one). */
    private val seenTags = HashSet<Int>()

    private val mc: Minecraft get() = Minecraft.getInstance()

    private fun kindOf(name: String): Kind? = when {
        "King Midas" in name -> Kind.MINI
        "Mimic" in name -> Kind.MIMIC
        "✯ " !in name -> null
        "Fel" in name -> Kind.FEL
        MINIS.any { it in name } -> Kind.MINI
        TANKS.any { it in name } -> Kind.TANK
        else -> Kind.STAR
    }

    private fun isShadowAssassin(p: Player): Boolean =
        p.uuid.version() == 2 && p.mainHandItem.customName?.string == "Silent Death" &&
            p.getItemBySlot(EquipmentSlot.FEET).`is`(Items.LEATHER_BOOTS)

    private fun color(kind: Kind): Color = when (kind) {
        Kind.STAR -> (Highlight.settings["Highlight color"] as? ColorSetting)?.value ?: Colors.WHITE
        Kind.TANK -> tankColor.value
        Kind.MINI -> miniColor.value
        Kind.FEL -> felColor.value
        Kind.ASSASSIN -> assassinColor.value
        Kind.MIMIC -> mimicColor.value
    }

    private fun box(e: Entity): AABB {
        var box = e.renderBoundingBox
        if (e is Enderman && e.isInvisible && hideInvisible.value) box = box.inflate(0.0, -1.8, 0.0).move(0.0, -1.2, 0.0)
        if (e is Zombie && e.isBaby) box = box.inflate(0.15, 0.2, 0.15)
        return box
    }

    private fun active(): Boolean =
        blade && Highlight.enabled && DungeonUtils.inClear && (Highlight.settings["Highlight Starred Mobs"] as? BooleanSetting)?.value != false

    private fun scan() {
        val level = mc.level ?: return
        for (e in level.entitiesForRendering()) {
            when (e) {
                is ArmorStand -> {
                    if (e.id in seenTags) continue
                    val name = e.customName?.string ?: continue
                    seenTags += e.id
                    val kind = kindOf(name) ?: continue
                    val offset = if ("withermancer" in name.lowercase()) 3 else 1
                    val mob = level.getEntity(e.id - offset) ?: continue
                    if (mob is ArmorStand || !mob.isAlive) continue
                    found[mob.id] = mob to kind
                }
                is Player -> if (e.id !in found && isShadowAssassin(e)) found[e.id] = e to Kind.ASSASSIN
                else -> {}
            }
        }
        found.values.removeIf { (e, _) -> !e.isAlive || e.isRemoved }
    }

    /** Adds the settings to Odin's Highlight; before OdinSplitsLook.install, which re-reads Odin's config. */
    fun install() {
        for (s in listOf(look, tankColor, miniColor, felColor, assassinColor, mimicColor, hideInvisible, notWhileBlind)) Highlight.registerSetting(s)

        on<TickEvent.End> { if (active()) scan() }

        on<RenderExtractEvent> {
            if (!active()) return@on
            if (notWhileBlind.value && mc.player?.hasEffect(MobEffects.BLINDNESS) == true) return@on
            val style = (Highlight.settings["Render Style"] as? SelectorSetting)?.value as? BoxStyle ?: BoxStyle.OUTLINE
            for ((e, kind) in found.values) {
                if (!e.isAlive) continue
                if (hideInvisible.value && e is Player && e.isInvisible) continue
                drawStyledBox(box(e), color(kind), style, true)
            }
        }

        on<LevelEvent.Load> { found.clear(); seenTags.clear() }
        EventBus.subscribe(this)
    }
}
