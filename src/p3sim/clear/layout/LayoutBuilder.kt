package com.engineerclient.p3sim.clear.layout

import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.rooms.OdinCore
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.run.DungeonPlan

/**
 * Turns a resolved [DungeonPlan] into the pieces the world region is made of: the pasted rooms with decoded volumes, the door
 * kinds by slot and the edits. Pure up to the room pack (no Minecraft types); the first place a missing or damaged room volume shows.
 */
object LayoutBuilder {
    class Built(
        val instances: List<RoomInstance>,
        val doors: Map<Slot, DoorKind>,
        val edits: List<Edit>,
        /** Rooms Odin may not recognise (their tile-centre column hashes to a core the pack does not know for that room), plain English. */
        val warnings: List<String>,
    )

    sealed interface Result {
        class Ok(val built: Built) : Result
        class Failed(val message: String) : Result
    }

    /** The door kind of every slot between two rooms; the keys are the layout's slots. */
    fun doorKinds(plan: DungeonPlan): Map<Slot, DoorKind> = plan.doors.associate { Slot(it.axis, it.a.x, it.a.z) to it.kind }

    fun build(plan: DungeonPlan, rooms: RoomPack, doors: Map<Slot, DoorKind> = doorKinds(plan)): Result {
        val instances = ArrayList<RoomInstance>(plan.rooms.size)
        for (r in plan.rooms) {
            val design = r.design ?: return Result.Failed("${r.name} has no design in the room library, so ${plan.layoutId} cannot be built.")
            val placement = r.placement ?: return Result.Failed("${r.name} has no placement in ${plan.layoutId}.")
            val volume = rooms.volumeFor(design, r.variantId) ?: return Result.Failed("The blocks of ${r.name} (${design.id}) could not be read from the room library, so ${plan.layoutId} cannot be built.")
            instances += RoomInstance(design, volume, placement)
        }
        return Result.Ok(assemble(instances, doors, rooms, plan.seed))
    }

    /** Assembles instances you already have (the spike builds its rooms by hand). [rooms] is only used to check what Odin will make of each tile. */
    fun assemble(instances: List<RoomInstance>, doors: Map<Slot, DoorKind>, rooms: RoomPack?, seed: Long = 0L): Built {
        val warnings = ArrayList<String>()
        if (rooms != null) for (r in instances) {
            for (t in Placement.canonicalTiles(r.design.shape)) {
                val core = OdinCore.ofVolume(r.volume, 32 * t.x + 15, 32 * t.z + 15)
                val entry = rooms.designByCore(core)
                val name = entry?.let { rooms.design(it.design)?.name }
                val at = r.placement.worldTileOf(t.x, t.z)
                if (entry == null) warnings += "${r.design.name}: Odin will not recognise tile ${at.x},${at.z} (core $core is not in the library's table)."
                else if (name != r.design.name) warnings += "${r.design.name}: Odin will call tile ${at.x},${at.z} $name."
            }
        }
        return Built(instances, doors, RegionEdits.build(instances, doors, seed), warnings)
    }
}
