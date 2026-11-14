package com.engineerclient.render

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.ParticleEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.texture
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.SmokeParticle
import net.minecraft.client.particle.TerrainParticle
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object RenderCleanup : Module(
    name = "Render Cleanup",
    category = Category.RENDER,
    description = "Hides particles, entities, players and overlays that clutter the screen.",
    key = null,
) {
    private val hideBlockBreak by BooleanSetting("Hide Block Break Particles", false, desc = "No block crack and break particles.")
    private val hideSmoke by BooleanSetting("Hide Smoke Particles", false, desc = "No smoke particles.")
    private val disableExplosion by BooleanSetting("Hide Explosion Particles", false, desc = "No explosion particles.")
    private val hypeOnly by BooleanSetting("Only Hyperion Explosions", false, desc = "Only Hyperion's Implosion is hidden.").withDependency { disableExplosion }
    private val hideEntityFire by BooleanSetting("Hide Entity Fire", true, desc = "No fire drawn on burning entities.")
    private val disableFireOverlay by BooleanSetting("Hide Fire Overlay", true, desc = "No fire on your screen while burning.")
    private val disableGlowing by BooleanSetting("Disable Glowing", true, desc = "No glowing outline on entities.")
    private val hideFallingBlocks by BooleanSetting("Hide Falling Blocks", true, desc = "No falling blocks.")
    private val hideLightning by BooleanSetting("Hide Lightning", true, desc = "No lightning bolts.")
    private val hideExperienceOrbs by BooleanSetting("Hide Experience Orbs", true, desc = "No experience orbs.")
    private val hideDeathAnimation by BooleanSetting("Hide Death Animation", true, desc = "Hides mobs that are dying.")
    private val hideDyingMobsArmorStand by BooleanSetting("Hide Dying Armor Stands", false, desc = "Hides armor stands of mobs that are dying.").withDependency { hideDeathAnimation }
    private val hideArcherBoneMeal by BooleanSetting("Hide Archer Passive", true, desc = "Hides the archer passive's floating bone meal.")
    private val hideFairy by BooleanSetting("Hide Healer Fairy", true, desc = "Hides the healer fairy held by some mobs.")
    private val hideWeaver by BooleanSetting("Hide Soul Weaver", true, desc = "Hides the soul weaver helmet worn by some mobs.")
    private val hideTentacle by BooleanSetting("Hide Tentacle Head", true, desc = "Hides the tentacle head worn by some mobs.")
    private var hidePlayers by BooleanSetting("Hide Players", false, desc = "Hides players near you.")
    private val playersOnlyInDungeons by BooleanSetting("Hide Players Only In Dungeons", false, desc = "Players are only hidden in dungeons.").withDependency { hidePlayers }
    private val hideAllPlayers by BooleanSetting("Hide All Players", false, desc = "Hides players at any distance.").withDependency { hidePlayers }
    private val playerDistance by NumberSetting("Hide Players Distance", 3f, 0..32, .5, "Players this close are hidden.", unit = " blocks").withDependency { hidePlayers && !hideAllPlayers }

    private const val TENTACLE_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTcxOTg1NzI3NzI0OSwKICAicHJvZmlsZUlkIiA6ICIxODA1Y2E2MmM0ZDI0M2NiOWQxYmY4YmM5N2E1YjgyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJSdWxsZWQiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzdkODM2NzQ5MjZiODk3MTRlNmI1YTU1NDcwNTAxYzA0YjA2NmRkODdiZjZjMzM1Y2RkYzZlNjBhMWExYTVmNSIKICAgIH0KICB9Cn0="
    private const val HEALER_FAIRY_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTcxOTQ2MzA5MTA0NywKICAicHJvZmlsZUlkIiA6ICIyNjRkYzBlYjVlZGI0ZmI3OTgxNWIyZGY1NGY0OTgyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJxdWludHVwbGV0IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzJlZWRjZmZjNmExMWEzODM0YTI4ODQ5Y2MzMTZhZjdhMjc1MmEzNzZkNTM2Y2Y4NDAzOWNmNzkxMDhiMTY3YWUiCiAgICB9CiAgfQp9"
    private const val SOUL_WEAVER_TEXTURE = "eyJ0aW1lc3RhbXAiOjE1NTk1ODAzNjI1NTMsInByb2ZpbGVJZCI6ImU3NmYwZDlhZjc4MjQyYzM5NDY2ZDY3MjE3MzBmNDUzIiwicHJvZmlsZU5hbWUiOiJLbGxscmFoIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yZjI0ZWQ2ODc1MzA0ZmE0YTFmMGM3ODViMmNiNmE2YTcyNTYzZTlmM2UyNGVhNTVlMTgxNzg0NTIxMTlhYTY2In19fQ=="

    init {
        on<EntityEvent.Add> {
            when (entity.type) {
                EntityTypes.EXPERIENCE_ORB if hideExperienceOrbs -> entity.remove(Entity.RemovalReason.DISCARDED)
                EntityTypes.FALLING_BLOCK if hideFallingBlocks -> entity.remove(Entity.RemovalReason.DISCARDED)
                EntityTypes.LIGHTNING_BOLT if hideLightning -> entity.remove(Entity.RemovalReason.DISCARDED)
            }
        }

        on<EntityEvent.SetData> {
            if (hideArcherBoneMeal && DungeonUtils.inDungeons) {
                val item = synchedDataValues.find { it.id == 8 }?.value as? ItemStack ?: return@on
                if (!item.isEmpty && item.item == Items.BONE_MEAL) entity.remove(Entity.RemovalReason.DISCARDED)
            }
        }

        // Implosion is the only explosion Hypixel sends with both override limiter and always show set.
        on<ParticleEvent> {
            if (!disableExplosion || (particle != ParticleTypes.EXPLOSION && particle != ParticleTypes.EXPLOSION_EMITTER)) return@on
            if (!hypeOnly || particle == ParticleTypes.EXPLOSION && overrideLimiter && alwaysShowParticles) cancel()
        }

        on<EntityEvent.SetItemSlot> {
            if (!DungeonUtils.inDungeons || stack.isEmpty) return@on
            val texture = stack.texture ?: return@on

            if (
                (hideFairy && slot == EquipmentSlot.MAINHAND && texture == HEALER_FAIRY_TEXTURE) ||
                (hideWeaver && slot == EquipmentSlot.HEAD && texture == SOUL_WEAVER_TEXTURE) ||
                (hideTentacle && slot == EquipmentSlot.HEAD && texture == TENTACLE_TEXTURE)
            ) entity.remove(Entity.RemovalReason.DISCARDED)
        }
    }

    fun hidesParticle(particle: Particle): Boolean = enabled &&
        (hideBlockBreak && particle is TerrainParticle || hideSmoke && particle is SmokeParticle)

    fun hidesEntityFire() = enabled && hideEntityFire

    fun disablesGlowing() = enabled && disableGlowing

    fun hidesFireOverlay() = enabled && disableFireOverlay

    fun hidesDeathAnimation() = enabled && hideDeathAnimation

    fun hidesDyingArmorStands() = enabled && hideDyingMobsArmorStand

    fun hidesPlayers() = enabled && hidePlayers

    fun toggleHidePlayers() {
        if (hidesPlayers()) hidePlayers = false
        else {
            hidePlayers = true
            if (!enabled) toggle()
        }
    }

    fun withinHideRange(entity: Entity): Boolean {
        if (hideAllPlayers) return true
        return entity.distanceToSqr(mc.player ?: return false) <= playerDistance * playerDistance
    }

    fun shouldRenderPlayer(entity: Entity): Boolean =
        !hidesPlayers() || entity !is Player || entity.uuid.version() != 4 || entity == mc.player ||
            playersOnlyInDungeons && !DungeonUtils.inDungeons || !withinHideRange(entity)
}
