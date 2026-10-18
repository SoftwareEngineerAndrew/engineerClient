package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimServer
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import org.slf4j.LoggerFactory

/**
 * The live world behind a savepoint ([SavepointWorld]): the human players' positions and every inventory slot (as the game's own item
 * codec writes them, so lore, custom data and models come back exactly), the regions' resync, and the removal of the sim's entities.
 * Server thread only.
 */
object MinecraftSavepointWorld : SavepointWorld {
    private val log = LoggerFactory.getLogger("engineerclient")

    private fun ops() = SimServer.level?.registryAccess()?.createSerializationContext(JsonOps.INSTANCE)

    override fun capturePlayers(): JsonArray {
        val out = JsonArray()
        val ops = ops() ?: return out
        for (p in Sim.players()) out.add(JsonObject().also { o ->
            o.addProperty("uuid", p.uuid.toString())
            o.addProperty("x", p.x); o.addProperty("y", p.y); o.addProperty("z", p.z)
            o.addProperty("yaw", p.yRot.toDouble()); o.addProperty("pitch", p.xRot.toDouble())
            o.addProperty("selected", p.inventory.selectedSlot)
            val items = JsonArray()
            for (i in 0 until p.inventory.containerSize) {
                val s = p.inventory.getItem(i)
                if (s.isEmpty) continue
                val enc = ItemStack.OPTIONAL_CODEC.encodeStart(ops, s).result().orElse(null) ?: continue
                items.add(JsonObject().also { e -> e.addProperty("slot", i); e.add("item", enc) })
            }
            o.add("items", items)
        })
        return out
    }

    override fun restorePlayers(data: JsonArray) {
        val ops = ops() ?: return
        for (e in data) {
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val uuid = o.get("uuid")?.asString ?: continue
            val p: ServerPlayer = Sim.players().firstOrNull { it.uuid.toString() == uuid } ?: continue
            Sim.asPlayer(p) {
                Sim.tp(p, o.get("x").asDouble, o.get("y").asDouble, o.get("z").asDouble, o.get("yaw").asFloat, o.get("pitch").asFloat)
                p.inventory.clearContent()
                o.getAsJsonArray("items")?.forEach { it ->
                    val j = it.asJsonObject
                    val stack = ItemStack.OPTIONAL_CODEC.parse(ops, j.get("item")).result().orElse(null)
                    if (stack == null) log.warn("[ads] a saved item could not be read back (slot ${j.get("slot")})") else p.inventory.setItem(j.get("slot").asInt, stack)
                }
                p.inventory.setSelectedSlot(o.get("selected")?.asInt ?: 0)
                p.connection.send(net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(p.inventory.selectedSlot))
                p.containerMenu.broadcastChanges(); p.inventoryMenu.broadcastChanges()
            }
        }
    }

    override fun resyncRegions(): Int {
        val level = SimServer.level ?: return 0
        return WorldRegions.resyncAll(level)
    }

    override fun clearEntities() { Sim.clearEntities() }
}
