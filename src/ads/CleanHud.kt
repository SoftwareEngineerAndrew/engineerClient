package com.engineerclient.ads

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.tutorial.TutorialSteps

/**
 * Minecraft's own clutter that ADS's worlds don't want, gone wherever the mod is installed: the tutorial's toasts
 * ("Destroy the tree", "Open your inventory"...) and the potion effects shown on screen (the HUD's icons top right;
 * the list beside the inventory is HideInventoryEffectsMixin's).
 */
object CleanHud {
    fun init() {
        HudElementRegistry.removeElement(VanillaHudElements.MOB_EFFECTS)
        // The tutorial is a saved option: set to done once, it never shows a toast again (a fresh options.txt
        // starts it over, so it's checked every start).
        ClientLifecycleEvents.CLIENT_STARTED.register { mc ->
            if (mc.options.tutorialStep != TutorialSteps.NONE) {
                mc.options.tutorialStep = TutorialSteps.NONE
                mc.options.save()
            }
        }
    }
}
