package com.engineerclient.p3sim.layout

import com.engineerclient.p3sim.clear.layout.DoorPlanner
import com.engineerclient.p3sim.clear.layout.LayoutRegion
import com.engineerclient.p3sim.clear.layout.RoomInstance
import com.engineerclient.p3sim.clear.layout.Slot
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.rooms.RoomBlockEntity
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.chunk.ProtoChunk
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [LayoutRegion.fill] on a real (bootstrapped) Minecraft chunk: rooms are written at the place and rotation the placement says, with
 * their block states rotated, door edits on top, and the block entities made. Skipped where Minecraft cannot be bootstrapped.
 */
class RegionFillTest {
    companion object {
        private var booted = false
        @JvmStatic @BeforeAll fun boot() { booted = TestChunks.boot() }
    }

    private fun state(c: ProtoChunk, x: Int, y: Int, z: Int) = c.getBlockState(BlockPos(x, y, z))

    /** A 31 x 31 room whose volume has a few interesting blocks above the floor: a stair, a chest, a sign, a head. */
    private fun furnished(): RoomVolume {
        val base = TestRooms.volume()
        val palette = base.palette + listOf(
            "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",  // 3
            "minecraft:chest[facing=north,type=single,waterlogged=false]",                      // 4
            "minecraft:oak_sign[rotation=0,waterlogged=false]",                                 // 5
            "minecraft:player_head[rotation=0,powered=false]",                                  // 6
        )
        val cells = base.cells.copyOf()
        fun idx(x: Int, y: Int, z: Int) = ((y - base.y0) * 31 + z) * 31 + x
        cells[idx(5, 69, 5)] = 3; cells[idx(6, 69, 5)] = 4; cells[idx(7, 69, 5)] = 5; cells[idx(8, 69, 5)] = 6
        return RoomVolume(31, 31, base.h, base.y0, palette, cells)
    }

    private fun furnishedRoom(rot: Rot, tx: Int, tz: Int): RoomInstance {
        val vol = furnished()
        val entities = listOf(
            RoomBlockEntity(Pos(7, 69, 5), "sign", null, listOf("Hello", "", "", ""), false, null),
            RoomBlockEntity(Pos(8, 69, 5), "skull", 0, null, false, null),
        )
        val d = TestRooms.design("Room_F", vol, emptySet(), entities)
        return RoomInstance(d, vol, d.place(rot, listOf(Tile(tx, tz))))
    }

    private val textures = listOf(SkullTexture("0123456789abcdef", "eyJ0ZXh0dXJlcyI6e319", java.util.UUID.nameUUIDFromBytes("test-skull".toByteArray()).toString(), "essence"))

    @Test fun aRoomIsWrittenWhereThePlacementSaysWithItsStatesTurned() {
        assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        val room = furnishedRoom(Rot.WEST, 1, 1)
        val region = LayoutRegion("dungeon", listOf(room), emptyList(), textures)
        val cx = DungeonFrame.tileMin(1) shr 4; val cz = DungeonFrame.tileMin(1) shr 4
        // Fill the nine chunks the tile touches (31 x 31 blocks from -168 cover three chunks each way).
        val chunks = HashMap<Pair<Int, Int>, ProtoChunk>()
        for (dx in 0..2) for (dz in 0..2) chunks[(cx + dx) to (cz + dz)] = TestChunks.chunk(cx + dx, cz + dz).also { region.fill(it) }
        fun at(wx: Int, y: Int, wz: Int) = chunks.getValue((wx shr 4) to (wz shr 4)).let { state(it, wx, y, wz) }
        // The floor: every column of the tile has stone at y 67.
        for (x in 0 until 31 step 7) for (z in 0 until 31 step 5) assertEquals(Blocks.STONE, at(room.placement.originX + x, 67, room.placement.originZ + z).block)
        // The stair, canonical (5,69,5) facing east, is turned one quarter clockwise by WEST: it faces south, at (D-1-z, x) from the origin.
        val w = room.placement.toWorld(5, 69, 5)
        val stair = at(w.x, 69, w.z)
        assertEquals(Blocks.OAK_STAIRS, stair.block)
        assertEquals("south", stair.getValue(net.minecraft.world.level.block.StairBlock.FACING).serializedName)
        // Block entities: the chest exists, the sign carries its text, the head its texture.
        val chest = assertNotNull(chunks.getValue((room.placement.toWorld(6, 69, 5).x shr 4) to (room.placement.toWorld(6, 69, 5).z shr 4)).getBlockEntity(BlockPos(room.placement.toWorld(6, 69, 5).x, 69, room.placement.toWorld(6, 69, 5).z)))
        assertTrue(chest is ChestBlockEntity)
        val sw = room.placement.toWorld(7, 69, 5)
        val sign = chunks.getValue((sw.x shr 4) to (sw.z shr 4)).getBlockEntity(BlockPos(sw.x, 69, sw.z)) as SignBlockEntity
        assertEquals("Hello", sign.getText(SignTextSlot.FRONT).getMessages(false)[0].string)
        val hw = room.placement.toWorld(8, 69, 5)
        val head = chunks.getValue((hw.x shr 4) to (hw.z shr 4)).getBlockEntity(BlockPos(hw.x, 69, hw.z)) as SkullBlockEntity
        assertEquals(textures[0].profileId, head.ownerProfile?.partialProfile()?.id?.toString(), "profile ${head.ownerProfile} state ${head.blockState}")
        assertTrue(region.stats().chunks == 9 && region.stats().cells > 5_000, region.stats().toString())
    }

    @Test fun doorEditsAreWrittenOnTopOfTheRooms() {
        assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        val a = TestRooms.instance("Room_A", 1, 1); val b = TestRooms.instance("Room_B", 2, 1)
        val edits = DoorPlanner.plan(listOf(a, b), mapOf(Slot('E', 1, 1) to DoorKind.WITHER))
        val region = LayoutRegion("dungeon", listOf(a, b), edits, emptyList())
        val (gx, gz) = DungeonFrame.doorSpot('E', 1, 1)
        val c = TestChunks.chunk(gx shr 4, gz shr 4).also { region.fill(it) }
        // The door body: 3 x 4 x 3 of coal around the slot centre, written into a chunk that holds the end of room A and the gap column.
        for (x in gx - 1..gx + 1) for (y in 69..72) for (z in gz - 1..gz + 1) if (x shr 4 == gx shr 4) assertEquals(Blocks.COAL_BLOCK, state(c, x, y, z).block, "$x,$y,$z")
        // The frame column stands in the gap beside the opening; the opening behind the body in room A (plane 1) is air.
        assertEquals(Blocks.STONE_BRICKS, state(c, gx, 70, gz - 2).block)
        assertEquals(Blocks.AIR, state(c, gx - 2, 70, gz).block)
    }

    @Test fun regionBoundsCoverTheRoomsAndTheGapColumn() {
        assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        val a = TestRooms.instance("Room_A", 1, 1); val b = TestRooms.instance("Room_B", 2, 1)
        val region = LayoutRegion("dungeon", listOf(a, b), DoorPlanner.plan(listOf(a, b), mapOf(Slot('E', 1, 1) to DoorKind.NORMAL)), emptyList())
        assertEquals(a.placement.originX, region.minX)
        assertEquals(b.placement.originX + 30, region.maxX)
        assertEquals(a.placement.originZ, region.minZ)
        assertTrue(region.maxZ >= a.placement.originZ + 30)
    }

    @Test fun dispensersAndHoppersGetNoBlockEntityAndChestsDo() {
        assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        val base = TestRooms.volume()
        val palette = base.palette + listOf(
            "minecraft:dispenser[facing=north,triggered=false]",   // 3
            "minecraft:hopper[enabled=true,facing=down]",          // 4
            "minecraft:chest[facing=north,type=single,waterlogged=false]",  // 5
            "minecraft:dropper[facing=up,triggered=false]",        // 6
        )
        val cells = base.cells.copyOf()
        fun idx(x: Int, y: Int, z: Int) = ((y - base.y0) * 31 + z) * 31 + x
        cells[idx(5, 69, 5)] = 3; cells[idx(6, 69, 5)] = 4; cells[idx(7, 69, 5)] = 5; cells[idx(8, 69, 5)] = 6
        val vol = RoomVolume(31, 31, base.h, base.y0, palette, cells)
        // The pack lists the dispenser as a block entity ({id:"Trap"} on the real server); the sim still makes none for it.
        val d = TestRooms.design("Room_D", vol, emptySet(), listOf(RoomBlockEntity(Pos(5, 69, 5), "dispenser", null, null, false, null)))
        val room = RoomInstance(d, vol, d.place(Rot.SOUTH, listOf(Tile(1, 1))))
        val region = LayoutRegion("dungeon", listOf(room), emptyList(), emptyList())
        val chunks = HashMap<Pair<Int, Int>, ProtoChunk>()
        val cx = DungeonFrame.tileMin(1) shr 4; val cz = DungeonFrame.tileMin(1) shr 4
        for (dx in 0..2) for (dz in 0..2) chunks[(cx + dx) to (cz + dz)] = TestChunks.chunk(cx + dx, cz + dz).also { region.fill(it) }
        fun be(x: Int) = room.placement.toWorld(x, 69, 5).let { w -> chunks.getValue((w.x shr 4) to (w.z shr 4)).getBlockEntity(BlockPos(w.x, 69, w.z)) }
        fun block(x: Int) = room.placement.toWorld(x, 69, 5).let { w -> chunks.getValue((w.x shr 4) to (w.z shr 4)).getBlockState(BlockPos(w.x, 69, w.z)).block }
        // The blocks are there, as recorded ...
        assertEquals(Blocks.DISPENSER, block(5)); assertEquals(Blocks.HOPPER, block(6)); assertEquals(Blocks.CHEST, block(7)); assertEquals(Blocks.DROPPER, block(8))
        // ... a dispenser, hopper or dropper has no entity (a vanilla one answers a powered tripwire hook beside it with an empty-dispenser click), a chest has.
        assertEquals(null, be(5), "dispenser"); assertEquals(null, be(6), "hopper"); assertEquals(null, be(8), "dropper")
        assertTrue(be(7) is ChestBlockEntity, "chest")
    }

    @Test fun theMakerForbidsTheEntitiesTheSimMustNeverHave() {
        assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        val maker = com.engineerclient.p3sim.clear.layout.BlockEntityMaker(emptyList())
        fun st(s: String) = com.engineerclient.p3sim.clear.layout.BlockStates.parse(s)
        for (s in listOf("minecraft:dispenser[facing=north,triggered=false]", "minecraft:dropper[facing=up,triggered=false]", "minecraft:hopper[enabled=true,facing=down]")) {
            assertTrue(!maker.wants(st(s)) && maker.forbids(st(s)), s)
        }
        assertTrue(maker.wants(st("minecraft:chest[facing=north,type=single,waterlogged=false]")) && !maker.forbids(st("minecraft:chest[facing=north,type=single,waterlogged=false]")))
        assertTrue(!maker.wants(st("minecraft:stone")) && !maker.forbids(st("minecraft:stone")))
    }

    @Test fun aSkullGetsItsTexture() {
        assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        val maker = com.engineerclient.p3sim.clear.layout.BlockEntityMaker(textures)
        val state = com.engineerclient.p3sim.clear.layout.BlockStates.parse("minecraft:player_head[rotation=0,powered=false]")
        val be = maker.create(BlockPos(0, 70, 0), state, RoomBlockEntity(Pos(0, 70, 0), "skull", 0, null, false, null))
        assertTrue(be is SkullBlockEntity, "be $be")
        val p = (be as SkullBlockEntity).ownerProfile
        assertNotNull(p, "no profile on the head")
    }
}
