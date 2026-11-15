package com.engineerclient.render

import com.engineerclient.EngineerClient
import com.engineerclient.chat.ChatHider
import com.engineerclient.misc.ScoreboardLines
import com.engineerclient.mixin.ChatCopyAccessor
import com.engineerclient.terminals.TerminalUtils
import com.engineerclient.terminals.sim.TermSimGUI
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.ParticleEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.texture
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.FriendsButton
import net.minecraft.client.gui.components.PlainTextButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.SmokeParticle
import net.minecraft.client.particle.TerrainParticle
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.FogType
import java.time.LocalTime

object Visibility : Module(
    name = "Visibility",
    category = Category.RENDER,
    description = "Screen effects, clutter, menus, sidebar, chat and tab list tweaks.",
    key = null,
) {
    private val screenDropdown by DropdownSetting("Screen", desc = "Overlays, fog, light, dark mode, sky and items.")
    private val noVignette by BooleanSetting("No Vignette", false, desc = "No dark screen edges.").withDependency { screenDropdown }
    private val noWaterOverlay by BooleanSetting("No Water Overlay", false, desc = "No underwater screen texture.").withDependency { screenDropdown }
    private val noSuffocationOverlay by BooleanSetting("No Suffocation Overlay", false, desc = "No block texture when your head is inside a block.").withDependency { screenDropdown }
    private val hidePotionHud by BooleanSetting("Hide Potion Effect HUD", false, desc = "Hides the effect icons at the top right and effect swirl particles.").withDependency { screenDropdown }
    private val hideInventoryEffects by BooleanSetting("Hide Inventory Effects", false, desc = "Hides the potion effects in your inventory screen.").withDependency { screenDropdown }
    private val noFog by BooleanSetting("No Fog", false, desc = "No world fog; lava, water, blindness and darkness fog stay.").withDependency { screenDropdown }
    private val fullbright by BooleanSetting("Fullbright", false, desc = "Everything fully lit.").withDependency { screenDropdown }
    private val noTextShadow by BooleanSetting("No Text Shadow", false, desc = "Text is drawn without its shadow.").withDependency { screenDropdown }
    private val noPaneHighlight by BooleanSetting("No Glass Pane Highlight", false, desc = "No hover highlight on unnamed glass panes in menus.").withDependency { screenDropdown }
    private val hideOffhandBackground by BooleanSetting("Hide Offhand Slot Background", false, desc = "No shield outline in the empty offhand slot.").withDependency { screenDropdown }
    private val darkMode by BooleanSetting("Dark Mode", false, desc = "Darkens the screen.").withDependency { screenDropdown }
    private val darkOpacity by NumberSetting("Dark Mode Opacity", 25, 1..80, 1, desc = "Strength of the dark tint.", unit = "%").withDependency { screenDropdown && darkMode }
    private val darkTintHud by BooleanSetting("Dark Mode Tint HUD", false, desc = "The tint covers the HUD too.").withDependency { screenDropdown && darkMode }

    private enum class Time(val ticks: Long) { DAY(1000), NOON(6000), SUNSET(12000), NIGHT(13000), MIDNIGHT(18000), SUNRISE(23000), REAL_TIME(-1), CUSTOM(-1) }

    private val timeChanger by BooleanSetting("Time Changer", false, desc = "Changes the time of day the sky shows.").withDependency { screenDropdown }
    private val timeMode by SelectorSetting("Time", Time.NOON, desc = "Time of day to show.").withDependency { screenDropdown && timeChanger }
    private val customTime by NumberSetting("Custom Time", 6000, 0..23999, 100, desc = "World time in ticks.").withDependency { screenDropdown && timeChanger && timeMode == Time.CUSTOM }
    private val blackSky by BooleanSetting("Black Sky", true, desc = "Black sky and distant fog instead of blue.").withDependency { screenDropdown }
    private val noGlint by BooleanSetting("No Enchant Glint", true, desc = "No enchantment glint on items, except in terminals.").withDependency { screenDropdown }
    private val hideItemNames by BooleanSetting("Hide Item Names", false, desc = "No item name above the hotbar when you switch items.").withDependency { screenDropdown }
    private val hideActionBar by BooleanSetting("Hide Action Bar", false, desc = "Hides the whole action bar above the hotbar.").withDependency { screenDropdown }
    private val noDropAnimation by BooleanSetting("No Drop Animation", false, desc = "In dungeons, a rejected drop leaves the held item raised.").withDependency { screenDropdown }
    private val hidePortalOverlay by BooleanSetting("Hide Portal Overlay", false, desc = "No purple overlay inside a nether portal.").withDependency { screenDropdown }

    private val cleanupDropdown by DropdownSetting("Cleanup", desc = "Particles, entities and players that clutter the screen.")
    private val hideBlockBreak by BooleanSetting("Hide Block Break Particles", false, desc = "No block crack and break particles.").withDependency { cleanupDropdown }
    private val hideSmoke by BooleanSetting("Hide Smoke Particles", false, desc = "No smoke particles.").withDependency { cleanupDropdown }
    private val disableExplosion by BooleanSetting("Hide Explosion Particles", false, desc = "No explosion particles.").withDependency { cleanupDropdown }
    private val hypeOnly by BooleanSetting("Only Hyperion Explosions", false, desc = "Only Hyperion's Implosion is hidden.").withDependency { cleanupDropdown && disableExplosion }
    private val hideEntityFire by BooleanSetting("Hide Entity Fire", true, desc = "No fire drawn on burning entities.").withDependency { cleanupDropdown }
    private val disableFireOverlay by BooleanSetting("Hide Fire Overlay", true, desc = "No fire on your screen while burning.").withDependency { cleanupDropdown }
    private val disableGlowing by BooleanSetting("Disable Glowing", true, desc = "No glowing outline on entities.").withDependency { cleanupDropdown }
    private val hideFallingBlocks by BooleanSetting("Hide Falling Blocks", true, desc = "No falling blocks.").withDependency { cleanupDropdown }
    private val hideLightning by BooleanSetting("Hide Lightning", true, desc = "No lightning bolts.").withDependency { cleanupDropdown }
    private val hideExperienceOrbs by BooleanSetting("Hide Experience Orbs", true, desc = "No experience orbs.").withDependency { cleanupDropdown }
    private val hideDeathAnimation by BooleanSetting("Hide Death Animation", true, desc = "Hides mobs that are dying.").withDependency { cleanupDropdown }
    private val hideDyingMobsArmorStand by BooleanSetting("Hide Dying Armor Stands", false, desc = "Hides armor stands of mobs that are dying.").withDependency { cleanupDropdown && hideDeathAnimation }
    private val hideArcherBoneMeal by BooleanSetting("Hide Archer Passive", true, desc = "Hides the archer passive's floating bone meal.").withDependency { cleanupDropdown }
    private val hideFairy by BooleanSetting("Hide Healer Fairy", true, desc = "Hides the healer fairy held by some mobs.").withDependency { cleanupDropdown }
    private val hideWeaver by BooleanSetting("Hide Soul Weaver", true, desc = "Hides the soul weaver helmet worn by some mobs.").withDependency { cleanupDropdown }
    private val hideTentacle by BooleanSetting("Hide Tentacle Head", true, desc = "Hides the tentacle head worn by some mobs.").withDependency { cleanupDropdown }
    private var hidePlayers by BooleanSetting("Hide Players", false, desc = "Hides players near you.").withDependency { cleanupDropdown }
    private val playersOnlyInDungeons by BooleanSetting("Hide Players Only In Dungeons", false, desc = "Players are only hidden in dungeons.").withDependency { cleanupDropdown && hidePlayers }
    private val hideAllPlayers by BooleanSetting("Hide All Players", false, desc = "Hides players at any distance.").withDependency { cleanupDropdown && hidePlayers }
    private val playerDistance by NumberSetting("Hide Players Distance", 3f, 0..32, .5, "Players this close are hidden.", unit = " blocks").withDependency { cleanupDropdown && hidePlayers && !hideAllPlayers }
    private val hideSheep by BooleanSetting("Hide Dungeon Sheep", true, desc = "Sheep are not drawn in dungeons.").withDependency { cleanupDropdown }

    private val menusDropdown by DropdownSetting("Menus", desc = "Title, Esc, loading and container screens.")
    private val cleanMenus by BooleanSetting("Clean Menus", true, desc = "Fewer buttons on the title and Esc screens, and a Mods button.").withDependency { menusDropdown }
    private val skipLoadingScreen by BooleanSetting("Clean Loading Screen", true, desc = "No \"Loading terrain\" screen: the world shows as it loads.").withDependency { menusDropdown }
    private val hideRecipeBook by BooleanSetting("Hide Recipe Book", false, desc = "No recipe book in the inventory and crafting screens.").withDependency { menusDropdown }
    private val blurInGui by BooleanSetting("Blur In GUI", false, desc = "Blurs the world behind any open GUI; the HUD stays sharp.").withDependency { menusDropdown }
    private val blurStrength by NumberSetting("Blur Strength", 4, 1..10, 1, desc = "Blur radius in pixels.").withDependency { menusDropdown && blurInGui }

    private val scoreboardDropdown by DropdownSetting("Scoreboard", desc = "Position, colors, lines and server id.")
    internal val scoreboardHud by HUD("Scoreboard HUD", "Moves and scales the scoreboard. Off: it stays where the game puts it.", true, 400, 100, 1f) { example ->
        ScoreboardNametags.hud(this, example)
    }.withDependency { scoreboardDropdown }
    internal val customOpacity by BooleanSetting("Custom Opacity", false, desc = "Uses your own scoreboard background opacity.").withDependency { scoreboardDropdown }
    internal val opacity by NumberSetting("Background Opacity", 30, 0..100, 5, desc = "Opacity of the scoreboard background.", unit = "%").withDependency { scoreboardDropdown && customOpacity }
    private val sidebarColors by BooleanSetting("Sidebar Colors", false, desc = "Custom scoreboard background colors.").withDependency { scoreboardDropdown }
    private val sidebarTitleColor by ColorSetting("Sidebar Title Color", Color(0, 0, 0, 0.4f), true, desc = "Behind the scoreboard title.").withDependency { scoreboardDropdown && sidebarColors }
    private val sidebarBodyColor by ColorSetting("Sidebar Body Color", Color(0, 0, 0, 0.3f), true, desc = "Behind the scoreboard lines.").withDependency { scoreboardDropdown && sidebarColors }
    private val hideSbLines by BooleanSetting("Clean Scoreboard", false, desc = "Hides the noisy sidebar lines; Purse, Bits and teammates stay.").withDependency { scoreboardDropdown }
    private val hideSbCatacombs by BooleanSetting("Scoreboard: Hide Catacombs Location", true, desc = "Hides The Catacombs location line in dungeons.").withDependency { scoreboardDropdown && hideSbLines }
    private val hideSbElapsed by BooleanSetting("Scoreboard: Hide Time Elapsed", false, desc = "Hides the dungeon's Time Elapsed line.").withDependency { scoreboardDropdown && hideSbLines }
    private val hideSbCleared by BooleanSetting("Scoreboard: Hide Cleared %", false, desc = "Hides the dungeon's Cleared % line.").withDependency { scoreboardDropdown && hideSbLines }
    internal val serverIdHider by BooleanSetting("Server ID Hider", false, desc = "Hides the server id and lobby address on the sidebar.").withDependency { scoreboardDropdown }
    internal val serverReplacement by StringSetting("Server Text", "m1A", 16, desc = "Shown instead of the server id.", placeholder = "").withDependency { scoreboardDropdown && serverIdHider }
    internal val ipReplacement by StringSetting("Address Text", "", 32, desc = "Shown instead of the lobby address; empty hides it.", placeholder = "").withDependency { scoreboardDropdown && serverIdHider }
    internal val hideIp by BooleanSetting("Hide Address", true, desc = "Also replace www.hypixel.net.").withDependency { scoreboardDropdown && serverIdHider }

    private val nametagsDropdown by DropdownSetting("Nametags", desc = "Nametags, and a fake name for yours.")
    internal val showOwn by BooleanSetting("Show Own Nametag", false, desc = "Draws your own nametag above your head.").withDependency { nametagsDropdown }
    internal val noBackground by BooleanSetting("Hide Nametag Background", false, desc = "Removes the black box behind nametags.").withDependency { nametagsDropdown }
    internal val shadow by BooleanSetting("Shadowed Nametag", false, desc = "Draws nametag text with a shadow.").withDependency { nametagsDropdown }
    internal val hideDinnerbone by BooleanSetting("Hide Dinnerbone Nametag", false, desc = "Hides the nametag of anything named Dinnerbone.").withDependency { nametagsDropdown }
    internal val nameChanger by BooleanSetting("Name Changer", false, desc = "Shows a fake name instead of yours everywhere text is drawn.").withDependency { nametagsDropdown }
    internal val fakeName by StringSetting("Fake Name", "Player", 16, desc = "The name shown instead of yours.", placeholder = "").withDependency { nametagsDropdown && nameChanger }

    private val chatDropdown by DropdownSetting("Chat", desc = "Chat history, stacking, copying and hiding.")
    private val removeLimit by BooleanSetting("Remove Chat Limit", false, desc = "Keeps more than vanilla's 100 chat lines.").withDependency { chatDropdown }
    private val maxMessages by NumberSetting("Max Messages", 10000, 100..100000, 100, desc = "How many lines the chat keeps.").withDependency { chatDropdown && removeLimit }
    private val compactChat by BooleanSetting("Compact Chat", false, desc = "A message repeated within the last 100 replaces its older copy, with a count.").withDependency { chatDropdown }
    private val hideEmpty by BooleanSetting("Hide Empty Messages", false, desc = "Drops chat lines with nothing but spaces.").withDependency { chatDropdown }
    private val compactHoppity by BooleanSetting("Compact Hoppity Messages", true, desc = "Joins the two lines of a Hoppity rabbit find into one.").withDependency { chatDropdown }
    private val copyChat by BooleanSetting("Copy On Right Click", true, desc = "Right click a chat message to copy it, shift for one line.").withDependency { chatDropdown }
    private val copyLineOnly by BooleanSetting("Copy Line Only", false, desc = "Always copies just the clicked line.").withDependency { chatDropdown && copyChat }
    private val cleanChat by BooleanSetting("Clean Chat", true, desc = "Hides the lines its rules block (config/engineerclient/chat-hider.json).").withDependency { chatDropdown }
    private val logHidden by BooleanSetting("Log Hidden Lines", false, desc = "Logs each hidden line and its rule to the session log.").withDependency { chatDropdown && cleanChat }
    private val reloadChatRules by ActionSetting("Reload Rules", desc = "Re-reads the chat rules.") { ChatHider.reload() }.withDependency { chatDropdown && cleanChat }

    private val tabDropdown by DropdownSetting("Tab", desc = "Tab list cleanup.")
    private val removePing by BooleanSetting("Remove Tab Ping", false, desc = "Hides the ping bars next to each name.").withDependency { tabDropdown }
    private val removeHeads by BooleanSetting("Remove Useless Tab Heads", false, desc = "Hides the gray heads Hypixel puts on its info lines.").withDependency { tabDropdown }

    @JvmField var extractingSky = false

    private const val TENTACLE_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTcxOTg1NzI3NzI0OSwKICAicHJvZmlsZUlkIiA6ICIxODA1Y2E2MmM0ZDI0M2NiOWQxYmY4YmM5N2E1YjgyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJSdWxsZWQiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzdkODM2NzQ5MjZiODk3MTRlNmI1YTU1NDcwNTAxYzA0YjA2NmRkODdiZjZjMzM1Y2RkYzZlNjBhMWExYTVmNSIKICAgIH0KICB9Cn0="
    private const val HEALER_FAIRY_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTcxOTQ2MzA5MTA0NywKICAicHJvZmlsZUlkIiA6ICIyNjRkYzBlYjVlZGI0ZmI3OTgxNWIyZGY1NGY0OTgyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJxdWludHVwbGV0IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzJlZWRjZmZjNmExMWEzODM0YTI4ODQ5Y2MzMTZhZjdhMjc1MmEzNzZkNTM2Y2Y4NDAzOWNmNzkxMDhiMTY3YWUiCiAgICB9CiAgfQp9"
    private const val SOUL_WEAVER_TEXTURE = "eyJ0aW1lc3RhbXAiOjE1NTk1ODAzNjI1NTMsInByb2ZpbGVJZCI6ImU3NmYwZDlhZjc4MjQyYzM5NDY2ZDY3MjE3MzBmNDUzIiwicHJvZmlsZU5hbWUiOiJLbGxscmFoIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yZjI0ZWQ2ODc1MzA0ZmE0YTFmMGM3ODViMmNiNmE2YTcyNTYzZTlmM2UyNGVhNTVlMTgxNzg0NTIxMTlhYTY2In19fQ=="

    // The gray placeholder skin Hypixel gives every non-player tab entry.
    private const val GRAY_SKIN = "skins/f712b3a0a04da402d569cc02f3fcbc9228b2303a"

    private const val STACK_RANGE = 100
    private val stacks = LinkedHashMap<String, Pair<Component, Int>>()

    private val hoppityName = Regex("^§D§LHOPPITY'S HUNT §7You found (.+) §.\\(.+\\)!$", RegexOption.IGNORE_CASE)
    private val hoppityInfo = Regex("^(§.§.)(DUPLICATE|NEW) RABBIT! (.+)")
    private var hoppityRabbit = ""

    init {
        on<TickEvent.End> {
            ScoreboardLines.hideLines = enabled && hideSbLines
            ScoreboardLines.hideCatacombsLocation = hideSbCatacombs
            ScoreboardLines.hideTimeElapsed = hideSbElapsed
            ScoreboardLines.hideCleared = hideSbCleared
            ScoreboardNametags.moving = enabled && scoreboardHud.enabled
        }
        on<LevelEvent.Unload> { ScoreboardLines.hideLines = false }

        on<MessageEvent.Overlay> {
            if (hideActionBar) cancel()
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is TitleScreen && cleansMenus()) cleanTitleScreen(screen)
        }

        on<ParticleEvent> {
            if (hidePotionHud && particle.type == ParticleTypes.ENTITY_EFFECT) cancel()
        }

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

        on<MessageEvent.Chat> {
            if (!compactHoppity) return@on
            val text = component.string
            if (message == "HOPPITY'S HUNT You found a Hitman Egg!") {
                cancel()
                return@on
            }
            hoppityName.find(text)?.let {
                hoppityRabbit = it.groupValues[1]
                cancel()
                return@on
            }
            hoppityInfo.find(text)?.let {
                val (style, status, chocolate) = it.destructured
                val line = Component.literal("$style$status $hoppityRabbit $chocolate")
                mc.execute { mc.gui.hud.chat.addClientSystemMessage(line) }
                hoppityRabbit = ""
                cancel()
            }
        }
    }

    fun noVignette() = enabled && noVignette
    @JvmStatic fun hidesPortalOverlay() = enabled && hidePortalOverlay
    fun noWaterOverlay() = enabled && noWaterOverlay
    fun noSuffocationOverlay() = enabled && noSuffocationOverlay
    fun hidePotionHud() = enabled && hidePotionHud
    fun hideInventoryEffects() = enabled && hideInventoryEffects
    fun noFog(): Boolean {
        if (!enabled || !noFog) return false
        val player = mc.player ?: return true
        return mc.gameRenderer.mainCamera().fluidInCamera == FogType.NONE &&
            !player.hasEffect(MobEffects.BLINDNESS) && !player.hasEffect(MobEffects.DARKNESS)
    }
    fun fullbright() = enabled && fullbright
    fun noTextShadow() = enabled && noTextShadow

    fun hidesPaneHighlight(slot: Slot?): Boolean {
        if (!enabled || !noPaneHighlight || slot == null) return false
        val stack = slot.item
        return BuiltInRegistries.ITEM.getKey(stack.item).path.endsWith("glass_pane") && stack.hoverName.string.isBlank()
    }

    fun slotIcon(icon: Identifier?): Identifier? =
        if (enabled && hideOffhandBackground && icon == InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD) null else icon

    fun drawDarkMode(graphics: GuiGraphicsExtractor, afterHud: Boolean) {
        if (!enabled || !darkMode || darkTintHud != afterHud) return
        val alpha = (darkOpacity * 255 / 100) shl 24
        graphics.fill(0, 0, mc.window.guiScaledWidth, mc.window.guiScaledHeight, alpha)
    }

    fun changesTime() = enabled && timeChanger

    fun time(): Long = when (timeMode) {
        Time.REAL_TIME -> LocalTime.now().let { Math.floorMod(it.hour * 1000L + it.minute * 1000L / 60 - 6000, 24000L) }
        Time.CUSTOM -> customTime.toLong()
        else -> timeMode.ticks
    }

    fun sidebarColor(title: Boolean, original: Int): Int {
        if (!enabled || !sidebarColors) return original
        return if (title) sidebarTitleColor.rgba else sidebarBodyColor.rgba
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

    fun chatLimit(vanilla: Int): Int = if (enabled && removeLimit) maxMessages else vanilla

    fun hides(contents: Component): Boolean = enabled && hideEmpty && blank(contents.string)

    private val INVISIBLE = Regex("§.|[\\s\u00A0\u200B-\u200D\u2800\uFEFF]")
    private fun blank(s: String) = INVISIBLE.replace(s, "").isEmpty()

    /** A message's wrapped lines without the blank ones (the gaps inside Hypixel's multi-line messages). */
    @JvmStatic
    fun dropBlankLines(lines: List<FormattedCharSequence>): List<FormattedCharSequence> {
        if (!enabled || !hideEmpty || lines.size < 2) return lines
        val kept = lines.filter { l -> val sb = StringBuilder(); appendPlain(sb, l); !blank(sb.toString()) }
        return kept.ifEmpty { lines }
    }

    fun compactOn(): Boolean = enabled && compactChat

    /**
     * A message seen within the last [STACK_RANGE] messages: the older copy goes and this one says how many there have been,
     * so spam stays one line.
     */
    fun compact(contents: Component, all: MutableList<GuiMessage>, lines: MutableList<GuiMessage.Line>): Component {
        val key = ChatFormatting.stripFormatting(contents.string) ?: ""
        if (blank(key)) return contents
        val prev = stacks.remove(key)
        var count = 1
        if (prev != null) {
            val i = (0 until minOf(STACK_RANGE, all.size)).firstOrNull { all[it].content() === prev.first }
            if (i != null) {
                val old = all.removeAt(i)
                lines.removeIf { it.parent() === old }
                count = prev.second + 1
            }
        }
        val out = if (count > 1) contents.copy().append(Component.literal(" ($count)").withStyle(ChatFormatting.GRAY)) else contents
        stacks[key] = out to count
        if (stacks.size > STACK_RANGE) stacks.remove(stacks.keys.first())
        return out
    }

    fun copyChat(event: MouseButtonEvent) {
        if (!enabled || !copyChat || event.button() != InputConstants.MOUSE_BUTTON_RIGHT) return
        val chat = mc.gui.hud.chat as ChatCopyAccessor
        if (chat.`ec$width`() + 4 < event.x()) return
        val lineHeight = (9 * (mc.options.chatLineSpacing().get() + 1.0) * chat.`ec$scale`()).toInt()
        if (lineHeight <= 0) return
        val index = ((Mth.floor(mc.window.guiScaledHeight - 40.0) - event.y()) / lineHeight).toInt() + chat.`ec$scroll`()
        val lines = chat.`ec$lines`()
        if (index !in lines.indices) return

        val clicked = lines[index]
        val sb = StringBuilder()
        if (copyLineOnly || event.hasShiftDown()) appendPlain(sb, clicked.content())
        else for (i in lines.indices.reversed()) if (lines[i].parent() === clicked.parent()) appendPlain(sb, lines[i].content())

        mc.keyboardHandler.clipboard = sb.toString().replace(Regex("§."), "")
        EngineerClient.msg("§7Copied chat message.")
    }

    private fun appendPlain(sb: StringBuilder, text: FormattedCharSequence) {
        text.accept { _, _, codePoint -> sb.appendCodePoint(codePoint); true }
    }

    fun hidesPing(): Boolean = enabled && removePing

    fun hidesHead(texture: Identifier): Boolean = enabled && removeHeads && texture.path == GRAY_SKIN

    fun skipsLoadingScreen(): Boolean = enabled && skipLoadingScreen

    fun cleansMenus(): Boolean = enabled && cleanMenus

    fun cleansChat(): Boolean = enabled && cleanChat

    fun logsHiddenChat(): Boolean = logHidden

    fun blackSkyActive(): Boolean = enabled && blackSky

    fun hidesItemNames(): Boolean = enabled && hideItemNames

    fun hidesRecipeBook(): Boolean = enabled && hideRecipeBook

    fun hidesSheep(): Boolean = enabled && hideSheep && DungeonUtils.inDungeons

    fun hidesDropAnimation(): Boolean = enabled && noDropAnimation

    // In a terminal the glint is how you see what's been clicked; the solvers read the component, not the render.
    fun hidesGlint(context: ItemDisplayContext): Boolean =
        enabled && noGlint && !(context == ItemDisplayContext.GUI && (TerminalUtils.currentTerm != null || mc.gui.screen() is TermSimGUI))

    fun hidesArmorGlint(): Boolean = enabled && noGlint

    // Three mixins read this in the same extract pass, so they agree: the game throws if one frame blurs twice.
    fun blursGui(): Boolean = enabled && blurInGui && mc.gui.screen() != null && mc.level != null

    /** Set by GuiBlurMixin while the frame's GUI is built (render thread only). */
    var buildingGui = false

    fun blursAfterReset(): Boolean = buildingGui && blursGui()

    fun blurRadius(): Int = blurStrength.toInt()

    /** Mod Menu's mod list over [parent], or null without Mod Menu (the Esc menu's Options then stays full width). */
    fun modsScreen(parent: Screen): Screen? = runCatching {
        Class.forName("com.terraformersmc.modmenu.gui.ModsScreen").getConstructor(Screen::class.java).newInstance(parent) as Screen
    }.getOrNull()

    private val titleIconKeys = setOf("options.language", "options.accessibility", "accessibility.onboarding.accessibility.button")

    private fun cleanTitleScreen(screen: TitleScreen) {
        val widgets = Screens.getWidgets(screen)
        val icons = widgets.filter { it is FriendsButton || (it is Button && (it.message.contents as? TranslatableContents)?.key in titleIconKeys) }
        val row = icons.firstOrNull()?.y ?: return
        widgets.removeAll(icons)
        widgets.filter { it.y > row && it !is PlainTextButton }.forEach { it.y -= 24 }
    }

    // Off, the tick that writes the hider's flag stops, so the sidebar is handed back whole here.
    override fun onDisable() {
        super.onDisable()
        ScoreboardLines.hideLines = false
        ScoreboardNametags.moving = false
    }
}
