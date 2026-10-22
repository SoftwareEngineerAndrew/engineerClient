package com.engineerclient.mixinplugin;

import java.util.Set;

/**
 * Mixin config plugin for engineerclient.mixins.json, which is mostly Minecraft-side and needed
 * without Odin (the P3 Sim's world, bots, bows and party, the accessors). Its mixins listed here
 * hook Minecraft for a feature built on Odin, so they apply only with Odin: Random Stuff's (blur,
 * clean menus, chat, scoreboard and loading screen, item names and glint, sign Enter, arrows, fog),
 * POV Previews', ADR's, Party Finder's, BR Roles' wand and SS Practice's. The Odin-targeted ones are
 * in engineerclient.odin.mixins.json instead.
 */
public class OdinFeatureMixins extends OdinMixins {
    private static final Set<String> NEED_ODIN = Set.of(
        "ArmorFoilMixin", "BlurRadiusMixin", "ChatComponentMixin", "ClickGuiTextMixin", "ContainerTooltipMixin",
        "FogColorMixin", "GameModeRecordMixin", "GameRendererMixin", "GuiBlurMixin", "GuiItemNameMixin",
        "GuiRenderStateBlurMixin", "ItemFoilMixin", "LocalPlayerDropMixin", "OwnArrowsHideMixin", "PauseMenuMixin",
        "PovLevelExtractorMixin", "PovLevelRendererMixin", "ScoreboardSidebarMixin", "ScreenBlurMixin",
        "SignEnterMixin", "SkipLoadingScreenMixin", "SsPracticeInputMixin", "TerminalNumberMixin",
        "WandInputMixin", "WandScrollMixin"
    );

    @Override
    protected boolean needsOdin(String name) {
        return NEED_ODIN.contains(name);
    }
}
