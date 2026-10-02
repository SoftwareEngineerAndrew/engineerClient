package com.engineerclient.recorder

import com.google.gson.JsonParser
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.SharedConstants
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The network-side codec mirror: absolute positions for relative moves, exactly as the client decodes them. */
class EntityMirrorTest {

    companion object {
        init {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @AfterTest
    fun reset() = EntityMirror.reset()

    private fun obj(members: String?) = JsonParser.parseString("{${members ?: ""}}").asJsonObject

    private fun spawn(id: Int, x: Double, y: Double, z: Double) =
        EntityMirror.annotate(ClientboundAddEntityPacket(id, UUID(0, id.toLong()), x, y, z, 0f, 0f, EntityType.ZOMBIE, 0, Vec3.ZERO, 0.0))

    @Test
    fun `a relative move decodes against the spawn position and becomes the new base`() {
        assertNull(spawn(5, 10.0, 64.0, 10.0))
        assertTrue(5 in EntityMirror.packetSpawned)
        val a = obj(EntityMirror.move(5, ClientboundMoveEntityPacket.Pos(5, 4096, 0, -2048, true)))
        assertEquals(listOf(11.0, 64.0, 9.5), a.getAsJsonArray("abs").map { it.asDouble })
        // The next delta is against the decoded position, not the spawn.
        val b = obj(EntityMirror.move(5, ClientboundMoveEntityPacket.Pos(5, 1, 0, 0, true)))
        assertEquals(11.0 + 1.0 / 4096, b.getAsJsonArray("abs")[0].asDouble)
        assertEquals(Vec3(11.0 + 1.0 / 4096, 64.0, 9.5), EntityMirror.base(5))
    }

    @Test
    fun `rotation only moves say degrees and no position`() {
        spawn(6, 0.0, 0.0, 0.0)
        val o = obj(EntityMirror.move(6, ClientboundMoveEntityPacket.Rot(6, 64, (-32).toByte(), false)))
        assertEquals(false, o.has("abs"))
        assertEquals(listOf(90.0, -45.0), o.getAsJsonArray("deg").map { it.asDouble })
    }

    @Test
    fun `an unknown entity says it has no base`() {
        val o = obj(EntityMirror.move(99, ClientboundMoveEntityPacket.Pos(99, 1, 1, 1, false)))
        assertTrue(o.get("abs").isJsonNull)
        assertEquals("no base", o.get("why").asString)
    }

    @Test
    fun `a position sync sets the base, a teleport does not`() {
        spawn(7, 0.0, 0.0, 0.0)
        val s = obj(EntityMirror.annotate(ClientboundEntityPositionSyncPacket(7, PositionMoveRotation(Vec3(1.5, 2.0, 3.0), Vec3.ZERO, 0f, 0f), true)))
        assertEquals(listOf(1.5, 2.0, 3.0), s.getAsJsonArray("abs").map { it.asDouble })
        val rel = obj(EntityMirror.annotate(ClientboundTeleportEntityPacket(7, PositionMoveRotation(Vec3(1.0, 0.0, 0.0), Vec3.ZERO, 0f, 0f), setOf(Relative.X), false)))
        assertTrue(rel.get("absPending").asBoolean)
        EntityMirror.annotate(ClientboundTeleportEntityPacket(7, PositionMoveRotation(Vec3(100.0, 0.0, 0.0), Vec3.ZERO, 0f, 0f), emptySet(), false))
        assertEquals(Vec3(1.5, 2.0, 3.0), EntityMirror.base(7))
    }

    @Test
    fun `entity data says the type, removal forgets it`() {
        spawn(8, 0.0, 0.0, 0.0)
        assertEquals("minecraft:zombie", obj(EntityMirror.annotate(ClientboundSetEntityDataPacket(8, emptyList()))).get("etype").asString)
        EntityMirror.annotate(ClientboundRemoveEntitiesPacket(IntArrayList.of(8)))
        assertNull(EntityMirror.base(8))
        assertTrue(obj(EntityMirror.annotate(ClientboundSetEntityDataPacket(8, emptyList()))).get("etype").isJsonNull)
    }

    @Test
    fun `keyframe seeds fill only entities the mirror does not know`() {
        spawn(9, 1.0, 1.0, 1.0)
        EntityMirror.seed(EntityMirror.Seed(9, "minecraft:zombie", 50.0, 50.0, 50.0))
        EntityMirror.seed(EntityMirror.Seed(10, "minecraft:item", 2.0, 3.0, 4.0))
        EntityMirror.annotate(ClientboundSetEntityDataPacket(1, emptyList()))
        assertEquals(Vec3(1.0, 1.0, 1.0), EntityMirror.base(9))
        assertEquals(Vec3(2.0, 3.0, 4.0), EntityMirror.base(10))
    }
}
