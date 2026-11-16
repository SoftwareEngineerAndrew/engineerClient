package com.engineerclient.misc

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.EntityEvent
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.impl.dungeon.Highlight
import com.adm.utils.Color
import com.adm.utils.render.drawFilledBox
import com.adm.utils.render.drawWireFrameBox
import com.adm.utils.renderBoundingBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.LivingEntity
import kotlin.math.abs
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
import net.minecraft.world.phys.AABB

/**
 * Blade Addons' Mob Highlight, as the only mob highlight: it lives in Odin's Highlight module, saved with it. Each kind
 * in its own colour, filled, outlined or both: starred mobs, tanks, minibosses, Fels, Shadow Assassins, secret
 * bats and boss withers. A mob is found from its name tag: by entity id first (the mob is spawned just before its tag, one
 * id less, three for a Withermancer), checked to stand under the tag, else the nearest mob under it. Boxes are depth tested,
 * so only what you can see is drawn.
 * Shadow Assassins have no tag: they are the fake players holding "Silent Death" in leather boots.
 */
object HighlightLook {

    private enum class Style { OUTLINE, FILLED, BOTH }
    private enum class Kind { STAR, TANK, MINI, FEL, ASSASSIN, BAT, WITHER }

    private val mobHighlight = BooleanSetting("Mob Highlight", true, desc = "Boxes dungeon mobs by kind.")
    private val style = SelectorSetting("Highlight Style", Style.OUTLINE, desc = "Outline, filled or both.").withDependency { mobHighlight.value }
    private val outlineWidth = NumberSetting("Outline Width", 4, 1..10, 1, desc = "Thickness of the outline.").withDependency { mobHighlight.value && style.value != Style.FILLED }
    private val hideInvisible = BooleanSetting("Don't Highlight Invisible", true, desc = "No box on an invisible Shadow Assassin, and only the head of an invisible Fel.").withDependency { mobHighlight.value }
    private val notWhileBlind = BooleanSetting("Not While Blind", true, desc = "No boxes while you have Blindness.").withDependency { mobHighlight.value }
    private val witherWidth = NumberSetting("Wither Extra Width", 0.0, 0.0..3.0, 0.1, desc = "Widens the boss withers' boxes.").withDependency { mobHighlight.value }
    private val starWidth = NumberSetting("Starred Extra Width", 0.0, 0.0..3.0, 0.1, desc = "Widens starred mobs' boxes.").withDependency { mobHighlight.value }

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
        Kind.BAT to Colours("Bat", -14531329, -14531329),
        Kind.WITHER to Colours("Wither", -2011028958, -2011028958),
    )

    private val BAT_HEALTHS = setOf(100f, 200f, 400f, 800f)

    private val TANKS = listOf("Zombie Commander", "Zombie Lord", "Skeleton Lord", "Withermancer", "Super Archer")
    private val MINIS = listOf("Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer")

    private val STARRED = setOf(Kind.STAR, Kind.TANK, Kind.MINI, Kind.FEL)

    private val found = HashMap<Int, Pair<Entity, Kind>>()
    private val claimedTags = HashSet<Int>()
    /** Each found mob's tag: a mob that unloads frees its tag, so the pair is matched again when they load back in. */
    private val tagOf = HashMap<Int, Int>()
    private val pending = HashMap<Int, Pair<ArmorStand, Kind>>()

    private val mc: Minecraft get() = Minecraft.getInstance()

    private fun kindOf(name: String): Kind? = when {
        "King Midas" in name -> Kind.MINI
        "❤" in name && MINIS.any { it in name } -> Kind.MINI
        "✯ " !in name -> null
        "Fel" in name -> Kind.FEL
        TANKS.any { it in name } -> Kind.TANK
        else -> Kind.STAR
    }

    private fun isShadowAssassin(p: Player): Boolean =
        p.uuid.version() == 2 && p.mainHandItem.customName?.string == "Silent Death" &&
            p.getItemBySlot(EquipmentSlot.FEET).`is`(Items.LEATHER_BOOTS)

    private fun box(e: Entity, kind: Kind): AABB {
        var box = e.renderBoundingBox
        if (e is Enderman && e.isInvisible && hideInvisible.value) box = box.inflate(0.0, -1.8, 0.0).move(0.0, -1.2, 0.0)
        if (e is Zombie && e.isBaby) box = box.inflate(0.15, 0.2, 0.15)
        if (e is WitherBoss) box = box.inflate(witherWidth.value, 0.0, witherWidth.value)
        if (kind in STARRED) box = box.inflate(starWidth.value, 0.0, starWidth.value)
        return box
    }

    private fun under(tag: Entity, m: Entity?): Boolean =
        m is LivingEntity && m !is ArmorStand && m.isAlive && (m !is Player || m !== mc.player && DungeonUtils.dungeonTeammates.none { it.name == m.name.string }) && abs(m.x - tag.x) < 1.5 && abs(m.z - tag.z) < 1.5 && tag.y - m.y in -1.0..4.5

    private fun mobUnder(level: ClientLevel, tag: ArmorStand, offset: Int): Entity? {
        level.getEntity(tag.id - offset)?.takeIf { under(tag, it) }?.let { return it }
        for (o in 1..6) if (o != offset) level.getEntity(tag.id - o)?.takeIf { under(tag, it) && it.id !in found }?.let { return it }
        return level.getEntities(tag, tag.boundingBox.inflate(1.5, 4.5, 1.5)) { under(tag, it) && it.id !in found }.minByOrNull { it.distanceToSqr(tag) }
    }

    private fun active(): Boolean = Highlight.enabled && mobHighlight.value && DungeonUtils.inDungeons

    /** One entity, when it comes in or its name, health or gear changes: starred tags wait in [pending] for their mob. */
    private fun consider(e: Entity) {
        if (e.id in found) return
        when (e) {
            is ArmorStand -> {
                if (e.id in claimedTags) return
                val kind = e.customName?.string?.let(::kindOf)
                if (kind == null) { pending.remove(e.id); return }
                val mob = mc.level?.let { mobUnder(it, e, offsetOf(e, kind)) }
                if (mob == null) pending[e.id] = e to kind else { claim(e, mob, kind); pending.remove(e.id) }
            }
            is Player -> if (isShadowAssassin(e)) found[e.id] = e to Kind.ASSASSIN
            is Bat -> if (DungeonUtils.inClear && e.health in BAT_HEALTHS) found[e.id] = e to Kind.BAT
            is WitherBoss -> if (DungeonUtils.inBoss && e.health != 300f) found[e.id] = e to Kind.WITHER
            else -> {}
        }
    }

    private fun offsetOf(tag: ArmorStand, kind: Kind) = if (kind == Kind.TANK && "withermancer" in (tag.customName?.string ?: "").lowercase()) 3 else 1

    private fun claim(tag: ArmorStand, mob: Entity, kind: Kind) {
        claimedTags += tag.id
        tagOf[mob.id] = tag.id
        found[mob.id] = mob to kind
    }

    /** A mob that unloaded (not one that died: its tag still floats there a moment) gives its tag back. */
    private fun unclaim(id: Int, mob: Entity) {
        val tag = tagOf.remove(id) ?: return
        if (mob is LivingEntity && mob.isDeadOrDying) return
        claimedTags.remove(tag)
    }

    private fun match() {
        val level = mc.level ?: return
        val it = pending.values.iterator()
        while (it.hasNext()) {
            val (tag, kind) = it.next()
            if (tag.isRemoved) { it.remove(); continue }
            val mob = mobUnder(level, tag, offsetOf(tag, kind)) ?: continue
            claim(tag, mob, kind)
            it.remove()
        }
        found.entries.removeIf { (id, v) -> (!v.first.isAlive || v.first.isRemoved).also { if (it) unclaim(id, v.first) } }
    }

    /** Adds the settings to Odin's Highlight; before SplitsLook.install, which re-reads Odin's config. */
    fun install() {
        val all = listOf(mobHighlight, style, outlineWidth, hideInvisible, notWhileBlind, witherWidth, starWidth) + colours.values.flatMap { listOf(it.fill, it.outline) }
        for (s in all) Highlight.registerSetting(s)

        // Each entity is judged as it changes; every few ticks, everything again, for what came in before the dungeon or boss was known.
        var ticks = 0
        on<TickEvent.End> {
            if (!active()) return@on
            if (++ticks % 5 == 0) mc.level?.entitiesForRendering()?.forEach(::consider)
            if (pending.isNotEmpty() || found.isNotEmpty()) match()
        }
        on<EntityEvent.Add> { if (active()) consider(entity) }
        on<EntityEvent.SetData> { if (active()) consider(entity) }
        on<EntityEvent.SetItemSlot> { if (entity is Player && active()) consider(entity) }
        on<EntityEvent.Remove> {
            val id = entity.id
            if (entity is ArmorStand) { claimedTags.remove(id); pending.remove(id) }
            else if (found.remove(id) != null) unclaim(id, entity)
        }

        on<RenderExtractEvent> {
            if (!active()) return@on
            if (notWhileBlind.value && mc.player?.hasEffect(MobEffects.BLINDNESS) == true) return@on
            val filled = style.value != Style.OUTLINE
            val outlined = style.value != Style.FILLED
            for ((e, kind) in found.values) {
                if (!e.isAlive) continue
                if (hideInvisible.value && e is Player && e.isInvisible) continue
                val c = colours.getValue(kind)
                val b = box(e, kind)
                if (filled) drawFilledBox(b, c.fill.value, depth = true)
                if (outlined) drawWireFrameBox(b, c.outline.value, outlineWidth.value.toFloat(), depth = true)
            }
        }

        on<LevelEvent.Load> { found.clear(); claimedTags.clear(); pending.clear(); tagOf.clear() }
        EventBus.subscribe(this)
    }
}
