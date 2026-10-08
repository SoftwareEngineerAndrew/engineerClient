package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Random Stuff's Clean Menus, Esc side: the pause menu is built as just Back to Game, Options | Mods,
 * (Open to LAN in singleplayer) and Disconnect - no advancements, statistics, server links or icon row.
 * Replaces the whole build so nothing else's injections into it land half-way through a layout we
 * don't use; P3 Sim's button is added after init and finds Disconnect by its key.
 */
@Mixin(PauseScreen.class)
public abstract class PauseMenuMixin extends Screen {
    @Shadow private Button disconnectButton;

    protected PauseMenuMixin(Component title) { super(title); }

    @Inject(method = "createPauseMenu", at = @At("HEAD"), cancellable = true)
    private void ec$cleanPauseMenu(CallbackInfo ci) {
        if (!RandomStuff.INSTANCE.cleansMenus()) return;
        ci.cancel();
        Screen self = this;
        GridLayout grid = new GridLayout();
        grid.defaultCellSetting().padding(4, 4, 4, 0);
        GridLayout.RowHelper helper = grid.createRowHelper(2);
        helper.addChild(Button.builder(Component.translatable("menu.returnToGame"), b -> {
            minecraft.gui.setScreen(null);
            minecraft.mouseHandler.grabMouse();
        }).width(204).build(), 2, grid.newCellSettings().paddingTop(50));
        Button.Builder options = Button.builder(Component.translatable("menu.options"),
            b -> minecraft.gui.setScreen(new OptionsScreen(self, minecraft.options, true)));
        Screen mods = RandomStuff.INSTANCE.modsScreen(self);
        if (mods != null) {
            helper.addChild(options.width(98).build());
            helper.addChild(Button.builder(Component.literal("Mods"), b -> minecraft.gui.setScreen(RandomStuff.INSTANCE.modsScreen(self))).width(98).build());
        } else {
            helper.addChild(options.width(204).build(), 2);
        }
        if (minecraft.hasSingleplayerServer())
            helper.addChild(Button.builder(Component.translatable("menu.multiplayerOptions.button"),
                b -> minecraft.gui.setScreen(new MultiplayerOptionsScreen(self))).width(204).build(), 2);
        disconnectButton = helper.addChild(Button.builder(CommonComponents.disconnectButtonLabel(minecraft.isLocalServer()), b -> {
            b.active = false;
            minecraft.getReportingContext().draftReportHandled(minecraft, self, () -> minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE), true);
        }).width(204).build(), 2);
        grid.arrangeElements();
        FrameLayout.alignInRectangle(grid, 0, 0, width, height, 0.5F, 0.25F);
        grid.visitWidgets(this::addRenderableWidget);
    }
}
