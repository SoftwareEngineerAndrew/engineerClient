package net.rustclear.mod.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * The title screen's Rustclear button, above Singleplayer and the same size: the slate panel of
 * the mod's screens with the dungeon-map emblem and RUSTCLEAR in gold ([ButtonStyle]). Still a
 * real widget, so keyboard focus and narration work.
 */
public final class RustclearTitleButton extends AbstractButton {
    private final Runnable action;

    public RustclearTitleButton(int x, int y, int width, int height, Runnable action) {
        super(x, y, width, height, Component.literal("Rustclear"));
        this.action = action;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
        ButtonStyle.draw(g, this, true);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
