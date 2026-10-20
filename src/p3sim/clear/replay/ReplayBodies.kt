package com.engineerclient.p3sim.clear.replay

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.clear.bots.AdsBotBody
import com.engineerclient.p3sim.clear.bots.BotRegistry
import com.engineerclient.p3sim.clear.hud.HudPackets
import com.engineerclient.p3sim.run.RunBlocks
import com.google.common.collect.LinkedHashMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.resources.Identifier
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.EnumSet
import java.util.UUID

/**
 * The recorded teammates' bodies: real `minecraft:player`s on the client with the recorded names and signed skins (the same body class the
 * bots use), placed every tick where the recording has them, turned, crouching and sprinting as recorded, holding what they held and swinging
 * when they swung. A body is in the world while its chunk is loaded and the recording has the player in the world (a dead teammate's body
 * goes as it went on Hypixel). Server thread.
 */
class ReplayBodies(private val mates: List<Mate>) {
    class Mate(val name: String, val uuid: UUID, val cls: String?, val tex: String?, val sig: String?) {
        var x = Double.NaN; var y = 0.0; var z = 0.0; var yaw = 0f; var pitch = 0f; var head = 0f
        var flags = 0
        var present = false
        val held = HashMap<EquipmentSlot, ItemStack>()
        var body: AdsBotBody? = null
        val told = HashSet<UUID>()
        var swing = false
    }

    fun byName(name: String): Mate? = mates.firstOrNull { it.name == name }
    fun all(): List<Mate> = mates

    fun profile(m: Mate): GameProfile {
        if (m.tex == null) return GameProfile(m.uuid, m.name)
        val mm = LinkedHashMultimap.create<String, Property>()
        mm.put("textures", Property("textures", m.tex, m.sig))
        return GameProfile(m.uuid, m.name, PropertyMap(mm))
    }

    /** Every human's client gets the tab entry (with the skin) before the body is added. */
    private fun announce(m: Mate) {
        for (p in Sim.players()) {
            if (!m.told.add(p.uuid)) continue
            try {
                val pk = HudPackets.info(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY),
                    listOf(HudPackets.entry(m.uuid, profile(m), false, null)))
                if (pk != null) p.connection.send(pk)
            } catch (t: Throwable) { /* gone */ }
        }
    }

    /** Puts every body where its mate is now (after the tick's events were applied). */
    fun place() {
        for (m in mates) {
            val show = m.present && !m.x.isNaN() && RunBlocks.isLoaded(Math.floor(m.x).toInt(), Math.floor(m.z).toInt())
            val b = m.body?.takeIf { !it.isRemoved }
            if (!show) { if (b != null) { b.discard(); m.body = null }; continue }
            val body = b ?: spawn(m) ?: continue
            body.snapTo(m.x, m.y, m.z, m.yaw, m.pitch)
            body.yBodyRot = m.yaw
            if (body.yHeadRot != m.head) {
                body.yHeadRot = m.head
                val pk = ClientboundRotateHeadPacket(body, Mth.floor(m.head * 256f / 360f).toByte())
                for (p in Sim.players()) p.connection.send(pk)
            }
            val crouch = m.flags and 0x02 != 0
            if (body.isShiftKeyDown != crouch) { body.isShiftKeyDown = crouch; body.pose = if (crouch) Pose.CROUCHING else Pose.STANDING }
            val sprint = m.flags and 0x08 != 0
            if (body.isSprinting != sprint) body.isSprinting = sprint
            for ((slot, st) in m.held) if (!ItemStack.matches(body.getItemBySlot(slot), st)) body.setItemSlot(slot, st.copy())
            if (m.swing) { m.swing = false; try { body.swing(InteractionHand.MAIN_HAND, body.mainHandItem.interactAnimation, false) } catch (t: Throwable) {} }
        }
    }

    private fun spawn(m: Mate): AdsBotBody? = try {
        announce(m)
        val e = AdsBotBody(Sim.level, m.uuid)
        e.isPermanentlyInvulnerable = true
        e.setNoGravity(true)
        e.snapTo(m.x, m.y, m.z, m.yaw, m.pitch)
        BotRegistry.add(m.uuid)
        Sim.spawn(e)
        m.body = e
        e
    } catch (t: Throwable) { null }

    fun removeAll() {
        for (m in mates) { m.body?.let { if (!it.isRemoved) it.discard() }; m.body = null }
        BotRegistry.clear()
    }

    companion object {
        /** A recorded item as the body wears it: the vanilla item with the recorded name (what anyone sees of a teammate's hand). */
        fun stack(itemId: String?, name: String?): ItemStack {
            if (itemId == null) return ItemStack.EMPTY
            val item = try { BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId)) } catch (t: Throwable) { Items.AIR }
            if (item == Items.AIR) return ItemStack.EMPTY
            val st = ItemStack(item)
            if (name != null) st.set(DataComponents.CUSTOM_NAME, Component.literal(name))
            return st
        }

        fun slot(name: String?): EquipmentSlot? = when (name) {
            "mainhand" -> EquipmentSlot.MAINHAND; "offhand" -> EquipmentSlot.OFFHAND; "head" -> EquipmentSlot.HEAD
            "chest" -> EquipmentSlot.CHEST; "legs" -> EquipmentSlot.LEGS; "feet" -> EquipmentSlot.FEET; else -> null
        }
    }
}
