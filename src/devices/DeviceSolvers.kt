package com.engineerclient.devices

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.settings.AdsSettingsRegistry
import com.engineerclient.ads.settings.Settings
import com.engineerclient.ads.settings.SettingsPanel
import com.engineerclient.p3sim.P3Sim
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.rustclear.mod.engine.Engine

/**
 * ADS's own P3 device solvers, ported from Odin so they work without it: Simon Says ([AdsSimonSays]), the Arrows
 * Device / i4 ([AdsArrowsDevice]) and Arrow Align ([AdsArrowAlign]). They run only in ADS's worlds ([inAdsWorld]:
 * the P3 Sim and Rustclear's dungeon worlds) and on SS Practice's device; on Hypixel nothing of this runs. With Odin
 * installed, Odin's own three stand down in ADS's worlds (OdinDevicesStandDownMixin), so nothing is drawn twice.
 *
 * Settings: each solver's own file in config/engineerclient (devices-*.json), in `/ads` under Simon Says, Arrows
 * Device and Arrow Align.
 * Fed from vanilla through the mixins in com.engineerclient.mixin (DeviceBlocksMixin, DevicePacketsMixin,
 * DeviceInputMixin), which call the hooks here.
 */
object DeviceSolvers {

    /** The colours the solvers offer (Minecraft's chat colours, as Odin's defaults are). */
    enum class DeviceColor(val rgb: Int) {
        GREEN(0x55FF55), GOLD(0xFFAA00), RED(0xFF5555), AQUA(0x55FFFF), LIGHT_PURPLE(0xFF55FF),
        BLUE(0x5555FF), YELLOW(0xFFFF55), WHITE(0xFFFFFF);

        /** With [alpha] 0-1. */
        fun argb(alpha: Double): Int = ((alpha.coerceIn(0.0, 1.0) * 255).toInt() shl 24) or rgb
    }

    /** In ADS's own worlds, as of this client tick (the network thread reads it too). */
    @Volatile private var inAds = false

    /** In a P3 Sim (yours or a party's) or a Rustclear dungeon world. Client thread. */
    @JvmStatic
    fun inAdsWorld(): Boolean = P3Sim.inSim || Engine.isEngineClient()

    // ------------------------------------------------------------------ server ticks

    @Volatile private var pings = 0L
    @Volatile private var pingAt = 0L
    @Volatile private var clientTicks = 0L

    /**
     * The server's tick, for timing the device's packets: the integrated server's own count when you host (the sim,
     * your Rustclear world), else the server's ticks as its pings count them (Rustclear sends one a tick, as Hypixel
     * does), else client ticks. Any thread.
     */
    fun serverTick(): Long {
        mc.singleplayerServer?.let { return it.tickCount.toLong() }
        return if (System.currentTimeMillis() - pingAt < 1000) pings else clientTicks
    }

    // ------------------------------------------------------------------ hooks (from the mixins)

    /** Every packet as it arrives, on the network thread. */
    @JvmStatic
    fun onPacket(packet: Packet<*>) {
        if (packet is ClientboundPingPacket && packet.id != 0) { pings++; pingAt = System.currentTimeMillis() }
        if (!inAds) return
        EngineerClient.safely("devices packet") { AdsSimonSays.arrived(packet) }
    }

    /** A block about to change in the client's world ([old] still in it). */
    @JvmStatic
    fun onBlockChange(pos: BlockPos, old: BlockState, updated: BlockState) {
        if (!inAds || !mc.isSameThread) return
        EngineerClient.safely("devices block") {
            AdsSimonSays.blockChanged(pos, old, updated)
            AdsArrowsDevice.blockChanged(pos, old, updated)
        }
    }

    /** A right click on a block, before it's sent: true cancels it (a wrong click blocked). */
    @JvmStatic
    fun onUseBlock(hit: BlockHitResult): Boolean {
        if (!inAds) return false
        var cancel = false
        EngineerClient.safely("devices use") { cancel = AdsSimonSays.blocksClick(hit.blockPos) }
        return cancel
    }

    /** A right click on an entity, before it's sent: true cancels it. */
    @JvmStatic
    fun onUseEntity(entity: Entity): Boolean {
        if (!inAds) return false
        var cancel = false
        EngineerClient.safely("devices use entity") { cancel = AdsArrowAlign.onClick(entity) }
        return cancel
    }

    /** [key] is held (a keyboard key or one of the three mouse buttons), as ADS's keybinds read keys. */
    fun isDown(key: InputConstants.Key): Boolean = when (key.type) {
        InputConstants.Type.MOUSE -> when (key.value) {
            InputConstants.MOUSE_BUTTON_LEFT -> mc.mouseHandler.isLeftPressed
            InputConstants.MOUSE_BUTTON_MIDDLE -> mc.mouseHandler.isMiddlePressed
            InputConstants.MOUSE_BUTTON_RIGHT -> mc.mouseHandler.isRightPressed
            else -> false
        }
        else -> key != InputConstants.UNKNOWN && InputConstants.isKeyDown(key.value)
    }

    /** The P3 Sim restarted (or practice started): a new P3, so the solvers start clean. */
    fun simRestart() {
        AdsSimonSays.reset()
        AdsArrowsDevice.reset()
    }

    private fun resetAll() {
        AdsSimonSays.reset()
        AdsArrowsDevice.reset()
        AdsArrowAlign.reset()
    }

    fun init() {
        for (s in SOLVERS) s.load()
        AdsSettingsRegistry.register { SOLVERS.map { SettingsPanel(it, it.title, it.all) } }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> EngineerClient.safely("devices join") { resetAll() } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> inAds = false; EngineerClient.safely("devices leave") { resetAll() } }
        ClientTickEvents.END_CLIENT_TICK.register {
            clientTicks++
            inAds = mc.level != null && inAdsWorld()
            if (!inAds) return@register
            EngineerClient.safely("devices tick") {
                AdsSimonSays.tick()
                AdsArrowsDevice.tick()
                AdsArrowAlign.tick()
            }
        }
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (overlay || !inAds) return@register
            EngineerClient.safely("devices chat") {
                val text = message.string.replace(CONTROL_CODES, "")
                AdsSimonSays.chat(text)
                AdsArrowsDevice.chat(text)
            }
        }
        LevelRenderEvents.COLLECT_SUBMITS.register { ctx ->
            if (!inAds) return@register
            EngineerClient.safely("devices draw") {
                AdsSimonSays.draw(ctx)
                AdsArrowsDevice.draw(ctx)
                AdsArrowAlign.draw(ctx)
            }
        }
    }

    private val CONTROL_CODES = Regex("§.")

    private val SOLVERS: List<Settings> by lazy { listOf(AdsSimonSays, AdsArrowsDevice, AdsArrowAlign) }
}
