# Layout builder (work item `layout`)

What it does for a player: builds the dungeon of a recorded Hypixel run in the ADS world, at Hypixel's own coordinates, so an unmodified Odin names
the rooms and finds their rotation, with the doors of that run shut and walls where the run had none.

## Pieces (all under `src/p3sim/clear/layout/`, pure ones first)

| file | job | Minecraft types? |
|---|---|---|
| `DoorGeometry.kt` | tile side -> slot, the cells of an opening, the gap-column frame, the door body edits | no |
| `DoorPlanner.kt` | the rules: carve a wall where the layout has a doorway, seal an opening where it has none, scrub a recorded closed door from a wall, wall block pick | no |
| `RegionEdits.kt` | marker signs removed (never in a tile's centre column), then the door edits | no |
| `LayoutBuilder.kt` | `DungeonPlan` -> pasted rooms (decoded volumes), door kinds by slot, edits, "Odin will not recognise this tile" warnings | no |
| `SpawnFinder.kt` | a place to stand near a tile's centre | no |
| `ProbeReport.kt` | compares what was built with what Odin recognised | no |
| `BlockEntityMaker.kt` | block states (shared parse cache), skull textures, sign text, empty block entities (none for hoppers, dispensers and droppers) | yes |
| `LayoutRegion.kt` | the `WorldRegion`: `fill` on generator workers, `resync` on the server thread, fill statistics | yes |
| `OdinProbe.kt`, `Spike.kt` | the spike: dev commands, the teleport into the new world, the client probe | yes |
| `LayoutModule.kt` | `LayoutService.plan/region`, the three dev commands | yes |

## The law, in one place

Room placement is `Placement` (rooms pack, FORMAT-rooms 4): position law, tile law, `quarterTurns` for `BlockState.rotate`. A tile side is named by
the world compass side: canonical side `s` of a room placed with `Q` quarter turns lands on world side `(s + Q) mod 4` (N E S W). Door slots, bodies and
the gap column are in world coordinates (`DoorGeometry`, `DoorModel`). Opening: offsets 14 to 16 along the side, y 69 to 72; frame columns 13 and 17,
lintel y 73, threshold y 68; the gap column is `tile min + 31`.

## Measured (headless, real packs, 2026-10-17; `RealLayoutTest` with ADS_REAL_PACKS)

- All 400 pool layouts build: 0 failures, 0 Odin core warnings; mean 1.24 M blocks per layout (max 1.54 M), busiest chunk 20 k blocks, about 1,530 door and wall edits.
- Every doorway is passable (feet and head height air, or the door's body in the edge plane) and every unused side has no air in its edge plane (15,878 doorway sides, 0 blocked, 0 holes).
- Odin's room scan replayed on the finished world: all 14,400 tile centre columns name the right room; all 5,143 one-tile rooms have their blue marker at the probe corner of the rotation they were placed with; all 2,796 multi-tile rooms give Odin's own rotation from their tiles.
- All 49,127 distinct state strings (volumes, variants, seals) parse on 26.3.
- Filling a whole dungeon (169 chunks, 1.1 to 1.4 M blocks) into real generation chunks: 50 to 100 ms in total on one thread, slowest chunk 15 ms cold and about 1 to 3 ms warm. Lighting is not in that number.

## Room facts the builder relies on (round 2, recorded)

- **Blood room.** One red build (design `Blood`, Odin core 698844034) and one cyan build (`Blood__b3`, core 1898104308). The red build is the recorded one: it matches a
  real client's volume of that room in 8 floor blocks, where an older capture it replaces lacked the four interior pillars and the brick bases (388 blocks).
  The nether portal in its alcove is not in the room: the server paints it only about 80 or 90 ticks after the Watcher falls (in the tick after a lightning bolt strikes the alcove, and
  the party jumps to Maxor when someone touches it), so the volume has the 12 cells as air and the blood module opens the portal itself. A blood room the layout never saw (no core, no
  recorded blocks) is one of the two builds, picked from the run's seed.
- **Tripwires.** In Cathedral (2 lines), Painting (1), Chambers (1), Lava Ravine (5) and Golden Oasis (1) the server builds the hooks and strings a few hundred ticks after it has
  sent the chunk, so the room volumes are patched from the recorded block updates (`stage_h_tripwires.py`): the hooks sit in their settled state (attached) and the strings as sent.
  These are the rooms where real players are hit by a tripwire; the traps module reads the hook pairs from `RoomDesign.tripwires`.
- **Trap markers.** The pack's New Trap and Old Trap already carry the server's marker conversions (dispenser to stone at New Trap `(16,81,16)` and Old Trap `(13,81,9)`, dispenser
  to tripwire hook at New Trap `(16,81,17)`); `RealRoomFixesTest` guards them.
- **Dispensers and droppers have no block entity** in the finished world, like hoppers: the real chunks mark them with `{id:"Trap"}` and nothing in them, and a vanilla dispenser
  with an entity answers a powered tripwire hook (or a plate) next to it with the empty "fail" click that never plays on Hypixel. Without an entity vanilla has nothing to dispense and
  stays silent (a warning line in the log). `LayoutRegion.resync` takes the entity off again when it puts such a block back.

## Unmeasured, named constants

- `DoorPlanner.CARVE_DEPTH = 2`, `CARVE_MAX_DEPTH = 4`, `WALL_PLANE_CELLS = 10`: how deep a wall is carved. Recorded walls are two deep in 234 of 500 sides.
- `DoorPlanner.SEAL_DEPTH = 2`: planes of an unwanted opening that must not stay air.
- `DoorGeometry.FRAME_BLOCK = stone_bricks`: Hypixel's eight decorative frame styles are not reproduced.
- Fill time and lighting cost of a whole dungeon: read `/ads dev probe` after `/ads dev layout`.
