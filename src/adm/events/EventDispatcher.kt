package com.adm.events

import com.adm.AdmMod.mc
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.utils.containsOneOf
import com.adm.utils.noControlCodes
import com.adm.utils.render.flushFrameBoxes
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.DungeonUtils.isSecret
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.phys.Vec3

object EventDispatcher {

    init {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> LevelEvent.Load.postAndCatch() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> LevelEvent.Unload.postAndCatch() }

        ClientTickEvents.END_LEVEL_TICK.register { world -> TickEvent.End(world).postAndCatch() }

        LevelRenderEvents.COLLECT_SUBMITS.register { context -> RenderExtractEvent(context).apply { postAndCatch(); flushFrameBoxes() } }

        ScreenEvents.BEFORE_INIT.register { _, screen, _, _ ->
            ScreenMouseEvents.allowMouseClick(screen).register { screen, event ->
                !ScreenEvent.MouseClick(screen, event).postAndCatch()
            }
            ScreenMouseEvents.allowMouseRelease(screen).register { screen, event ->
                !ScreenEvent.MouseRelease(screen, event).postAndCatch()
            }
            ScreenKeyboardEvents.allowKeyPress(screen).register { screen, event ->
                !ScreenEvent.KeyPress(screen, event).postAndCatch()
            }
        }

        ClientEntityEvents.ENTITY_LOAD.register { entity, _ -> EntityEvent.Add(entity).postAndCatch() }
        ClientEntityEvents.ENTITY_UNLOAD.register { entity, _ -> EntityEvent.Remove(entity).postAndCatch() }

        ClientReceiveMessageEvents.MODIFY_GAME.register { message, overlay ->
            if (overlay) MessageEvent.ModifyOverlay(plain(message), message).apply { postAndCatch() }.component
            else MessageEvent.ModifyChat(plain(message), message).apply { postAndCatch() }.component
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (overlay) !MessageEvent.Overlay(plain(message), message).postAndCatch()
            else !MessageEvent.Chat(plain(message), message).postAndCatch()
        }

        onReceive<ClientboundTakeItemEntityPacket> {
            if (!DungeonUtils.inClear) return@onReceive
            val player = mc.player ?: return@onReceive
            if (playerId != player.id) return@onReceive
            val itemEntity = mc.level?.getEntity(itemId) as? ItemEntity ?: return@onReceive
            if (itemEntity.item.hoverName.string.containsOneOf(dungeonItemDrops, true) && itemEntity.distanceTo(player) <= 6)
                mc.execute { SecretPickupEvent.Item(itemEntity).postAndCatch() }
        }

        on<EntityEvent.Remove> {
            if (!DungeonUtils.inClear) return@on
            val entity = entity as? ItemEntity ?: return@on
            if (
                entity.distanceTo(mc.player ?: return@on) <= 6 &&
                entity.item.hoverName.string.containsOneOf(dungeonItemDrops, true)
            ) SecretPickupEvent.Item(entity).postAndCatch()
        }

        onReceive<ClientboundSoundPacket> {
            if (!DungeonUtils.inClear) return@onReceive
            if (volume == 0.1f) {
                val s = sound.value()
                if (s == SoundEvents.BAT_HURT || s == SoundEvents.BAT_DEATH) SecretPickupEvent.Bat(this).postAndCatch()
            }
        }

        on<BlockInteractEvent> {
            if (!DungeonUtils.inDungeons) return@on
            val blockState = mc.level?.getBlockState(pos) ?: return@on
            if (blockState.block is SkullBlock) {
                val distance = mc.player?.eyePosition?.distanceToSqr(Vec3(pos)) ?: return@on
                if (distance > 23) return@on
            }

            if (isSecret(blockState, pos)) SecretPickupEvent.Interact(pos, blockState).postAndCatch()
        }
    }

    // Allow and modify both get the same message: flattened once.
    private var lastMessage: Component? = null
    private var lastPlain = ""

    private fun plain(message: Component): String {
        if (message !== lastMessage) {
            lastPlain = message.string.noControlCodes
            lastMessage = message
        }
        return lastPlain
    }

    private class ChatText(val packet: ClientboundSystemChatPacket, val text: String)

    @Volatile
    private var lastChat: ChatText? = null

    // Same text as replace(Regex("§."), ""), worked out once per packet for every listener that reads it.
    @JvmStatic
    fun chatText(packet: ClientboundSystemChatPacket): String {
        lastChat?.let { if (it.packet === packet) return it.text }
        val text = stripCodes(packet.content().string)
        lastChat = ChatText(packet, text)
        return text
    }

    private fun stripCodes(s: String): String {
        var i = s.indexOf('§')
        if (i == -1) return s
        val out = StringBuilder(s.length)
        out.append(s, 0, i)
        val len = s.length
        while (i < len) {
            val c = s[i]
            if (c == '§' && i + 1 < len && !isLineEnd(s[i + 1])) {
                i += if (s[i + 1].isHighSurrogate() && i + 2 < len && s[i + 2].isLowSurrogate()) 3 else 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun isLineEnd(c: Char) = c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'

    private val dungeonItemDrops = listOf(
        "Health Potion VIII Splash Potion", "Healing Potion 8 Splash Potion", "Healing Potion VIII Splash Potion", "Healing VIII Splash Potion", "Healing 8 Splash Potion",
        "Decoy", "Inflatable Jerry", "Spirit Leap", "Trap", "Training Weights", "Defuse Kit", "Dungeon Chest Key", "Treasure Talisman", "Revive Stone", "Architect's First Draft",
        "Secret Dye", "Candycomb"
    )
}