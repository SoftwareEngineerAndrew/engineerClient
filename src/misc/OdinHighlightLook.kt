package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.Highlight
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.BoxStyle
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.renderBoundingBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Enderman
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.core.BlockPos

/**
 * Blade Addons' Mob Highlight, as the only mob highlight: it lives in Odin's Highlight module, saved with it. Each kind
 * in its own colour, filled, outlined or both: starred mobs, tanks, minibosses, Fels, Shadow Assassins, Mimics, secret
 * bats and boss withers. A mob is found from its name tag by entity id - the mob is spawned just before its tag, so it
 * is the tag's id less one (three for a Withermancer) - so a mob in a crowd is never mixed up with its neighbour.
 * Shadow Assassins have no tag: they are the fake players holding "Silent Death" in leather boots.
 */
object OdinHighlightLook {

    private enum class Style { OUTLINE, FILLED, BOTH }
    private enum class Kind { STAR, TANK, MINI, FEL, ASSASSIN, MIMIC, BAT, WITHER }

    private val mobHighlight = BooleanSetting("Mob Highlight", true, desc = "Boxes dungeon mobs by kind.")
    private val style = SelectorSetting("Highlight Style", Style.OUTLINE, desc = "Outline, filled or both.").withDependency { mobHighlight.value }
    private val outlineWidth = NumberSetting("Outline Width", 4, 1..10, 1, desc = "Thickness of the outline.").withDependency { mobHighlight.value && style.value != Style.FILLED }
    private val hideInvisible = BooleanSetting("Don't Highlight Invisible", true, desc = "No box on an invisible Shadow Assassin, and only the head of an invisible Fel.").withDependency { mobHighlight.value }
    private val notWhileBlind = BooleanSetting("Not While Blind", true, desc = "No boxes while you have Blindness.").withDependency { mobHighlight.value }
    private val witherWidth = NumberSetting("Wither Extra Width", 0.0, 0.0..3.0, 0.1, desc = "Widens the boss withers' boxes.").withDependency { mobHighlight.value }

    private class Colours(kind: String, fill: Int, outline: Int) {
        val fill = ColorSetting("$kind Fill", Color(fill), true, desc = "$kind filled colour.").withDependency { mobHighlight.value && style.value != Style.OUTLINE }
        val outline = ColorSetting("$kind Outline", Color(outline), true, desc = "$kind outline colour.").withDependency { mobHighlight.value && style.value != Style.FILLED }
    }

    private val colours = mapOf(
        Kind.STAR to Colours("Starred", 0xFF00FF00.toInt(), 0xFF00FF00.toInt()),
        Kind.TANK to Colours("Tank", 0xFFFF0000.toInt(), 0xFFFF0000.toInt()),
        Kind.MINI to Colours("Miniboss", 0xFFFFFF00.toInt(), 0xFFFFFF00.toInt()),
        Kind.FEL to Colours("Fel", 0xFF00FFFF.toInt(), 0xFF00FFFF.toInt()),
        Kind.ASSASSIN to Colours("Shadow Assassin", 0xFF800080.toInt(), 0xFF800080.toInt()),
        Kind.MIMIC to Colours("Mimic", -404288757, 6968661),
        Kind.BAT to Colours("Bat", -14531329, -14531329),
        Kind.WITHER to Colours("Wither", -2011028958, -2011028958),
    )

    private val mimicChests = BooleanSetting("Mimic Chests", true, desc = "Boxes trapped chests (Mimics) in dungeons.")
    private val mimicChestColor = ColorSetting("Mimic Chest Color", Color(231, 11, 11, 0.9f), true, desc = "Mimic chest box.").withDependency { mimicChests.value }

    private val BAT_HEALTHS = setOf(100f, 200f, 400f, 800f)

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

    private fun box(e: Entity): AABB {
        var box = e.renderBoundingBox
        if (e is Enderman && e.isInvisible && hideInvisible.value) box = box.inflate(0.0, -1.8, 0.0).move(0.0, -1.2, 0.0)
        if (e is Zombie && e.isBaby) box = box.inflate(0.15, 0.2, 0.15)
        if (e is WitherBoss) box = box.inflate(witherWidth.value, 0.0, witherWidth.value)
        return box
    }

    private fun active(): Boolean = Highlight.enabled && mobHighlight.value && DungeonUtils.inDungeons

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
                is Bat -> if (e.id !in found && DungeonUtils.inClear && e.health in BAT_HEALTHS) found[e.id] = e to Kind.BAT
                is WitherBoss -> if (e.id !in found && DungeonUtils.inBoss && e.health != 300f) found[e.id] = e to Kind.WITHER
                else -> {}
            }
        }
        found.values.removeIf { (e, _) -> !e.isAlive || e.isRemoved }
    }

    private val CHEST_BOX = AABB(0.0625, 0.0, 0.0625, 0.9375, 0.875, 0.9375)
    private var chests: List<BlockPos> = emptyList()
    private var chestScanTick = 0

    private fun mimicChestsOn(): Boolean = Highlight.enabled && mimicChests.value && DungeonUtils.inClear

    private fun scanChests() {
        val level = mc.level ?: return
        val me = mc.player ?: return
        val cx = me.blockPosition().x shr 4
        val cz = me.blockPosition().z shr 4
        val r = mc.options.renderDistance().get()
        val out = ArrayList<BlockPos>()
        for (x in cx - r..cx + r) for (z in cz - r..cz + r) {
            if (!level.hasChunk(x, z)) continue
            for (be in level.getChunk(x, z).blockEntities.values) if (be is TrappedChestBlockEntity) out += be.blockPos
        }
        chests = out
    }

    /** Adds the settings to Odin's Highlight; before OdinSplitsLook.install, which re-reads Odin's config. */
    fun install() {
        val all = listOf(mobHighlight, style, outlineWidth, hideInvisible, notWhileBlind, witherWidth) + colours.values.flatMap { listOf(it.fill, it.outline) } + listOf(mimicChests, mimicChestColor)
        for (s in all) Highlight.registerSetting(s)

        on<TickEvent.End> {
            if (active()) scan()
            if (mimicChestsOn() && ++chestScanTick % 20 == 0) scanChests()
        }

        on<RenderExtractEvent> {
            if (mimicChestsOn()) for (pos in chests) drawStyledBox(CHEST_BOX.move(pos), mimicChestColor.value, BoxStyle.OUTLINE, true)
            if (!active()) return@on
            if (notWhileBlind.value && mc.player?.hasEffect(MobEffects.BLINDNESS) == true) return@on
            val filled = style.value != Style.OUTLINE
            val outlined = style.value != Style.FILLED
            for ((e, kind) in found.values) {
                if (!e.isAlive) continue
                if (hideInvisible.value && e is Player && e.isInvisible) continue
                val c = colours.getValue(kind)
                val b = box(e)
                if (filled) drawFilledBox(b, c.fill.value, depth = true)
                if (outlined) drawWireFrameBox(b, c.outline.value, outlineWidth.value.toFloat(), depth = true)
            }
        }

        on<LevelEvent.Load> { found.clear(); seenTags.clear(); chests = emptyList() }
        EventBus.subscribe(this)
    }
}
