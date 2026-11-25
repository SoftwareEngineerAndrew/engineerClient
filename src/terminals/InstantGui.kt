package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.practice.TerminalHitboxes
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.utils.ServerUtils
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity

/** Open GUI Instantly: a blank screen the moment you click a terminal, so the mouse is free before Hypixel's window arrives. */
object InstantGui {
    private val refused = setOf("This Terminal has already been completed!", "This Terminal doesn't seem to be responsive at the moment.")
    private var openedAt = 0L

    private class WaitScreen : Screen(Component.literal("")) {
        override fun isPauseScreen() = false
        override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {}
        override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {}
    }

    private fun clicked(entity: Entity) {
        if (!Terminals.enabled || !Terminals.instantGui || P3Sim.localSim || mc.gui.screen() != null) return
        val i = TerminalHitboxes.terminalOf(entity)
        if (i < 0 || TerminalHitboxes.seenDone(i)) return
        openedAt = System.currentTimeMillis()
        mc.gui.setScreen(WaitScreen())
    }

    private fun close() {
        if (mc.gui.screen() is WaitScreen) mc.gui.setScreen(null)
    }

    fun install() {
        UseEntityCallback.EVENT.register { _, level, _, entity, _ ->
            if (level.isClientSide) clicked(entity)
            InteractionResult.PASS
        }
        AttackEntityCallback.EVENT.register { _, level, _, entity, _ ->
            if (level.isClientSide) clicked(entity)
            InteractionResult.PASS
        }
        on<MessageEvent.Chat> { if (message in refused) close() }
        on<TickEvent.End> {
            if (mc.gui.screen() is WaitScreen && System.currentTimeMillis() - openedAt > 400 + ServerUtils.averagePing * 2L) close()
        }
    }
}
