# Hard puzzles and the trap room (work item `traps`)

Code: `src/p3sim/clear/puzzles/hard/` (Water Board, Teleport Maze, Ice Path), `src/p3sim/clear/traps/` (crushers, tripwires, falling floors, arrow banks, flamethrowers, ability lockout).
Tests: `test/p3sim/hard/`, `test/p3sim/traps/`. Pure state machines sit apart from the world glue; the glue is a thin class per puzzle.

## What each piece reads and writes

| piece | reads (from the room's block volume, canonical frame) | writes (through `HardBlocks`, which turns canonical edits by the room's placement) |
|---|---|---|
| Water Board | sticky pistons facing north in the wall layer z = 28 (the gates), what stands at z = 26 / 27 (extended or not), water and solids in z = 24..29, the wool at x = 15, y = 56, z = 19 - k (closed pits) | levers' powered state, gate pistons / heads / blocks, water cells, the five wool pits |
| Teleport Maze | the end-portal frames (30, Odin's order) and the slab patch each stands in | the reward chest (a stepped-on pad teleports the player; no other blocks change) |
| Ice Path | blocks at the fish's height (z/x obstacles), hoppers with fire above (traps) | the reward chest, and the 12-block ice wall (x 14..16, y 68..71, z 25) set to air at the goal (the silverfish entity is moved each tick) |
| Hazards | `design.crushers` (slab, axis, sign, sweep), `design.tripwires` (hooks), `design.fallingBlocks`, the dispensers' facings (`traps.json`) | crusher layers (air / material), the falling floors (air + falling-block entities), arrow entities, flame particles, damage and the red chat lines |

## The reward chest

The three hard puzzles place a chest with the room (`ChestReward`: Ice (15,67,28) north, Teleport Maze (15,70,20) south, Water Board (15,56,22) south). Reaching the
objective (pits open, pad 28, fish at the goal) only marks it done; a right click on the chest then plays chest-open at the block 2 ticks later, the lid block event
(Odin's finish signal) and the solve one tick after that, and five harp notes starting 3 ticks later every 5 (`ChestFinale`). The room still wakes at run start rather than 10 to 15 ticks after
first entry (known gap).

The Ice Path is pushed by arrows (`HardPuzzlesModule.onEvent` on `ModuleEvent("damage", "arrowHitEntity", id, by)`; `Bows.kt` has to post it, see the integration requests),
3 ticks after the hit; a second arrow while it moves prints the refusal. Left click still works but is unverified.

## Clocks and timings

* Water: 1.8 `BlockDynamicLiquid` rules (`WaterSim`), 5-tick steps, a lever click moves the gates and the intake in its own tick (`GATE_DELAY_TICKS` = 0; the old 2 ticks were client latency), and pulling the water lever again plugs the intake with lapis and lets the column drain, a pit opens the tick water reaches its drain.
* Ice Path: 0.4 blocks a tick, axis slides, stops in the middle of the cell before an obstacle.
* Crushers: `CrusherTiming(ticksPerBlock, stillTicks)`, rest at the retracted end, period `(2 * sweep - 1) * ticksPerBlock + stillTicks`. Timings come from the optional
  `traps.json` from the packs (bundled in the jar, or the override folder's copy; the spec is kept outside this repository), else 5 / 30. The hazard clock only runs while a human is in the room.

## The hazards of `src/p3sim/clear/traps/` (what the recordings show, and what the sim does)

All numbers are from the Dungeon Recorder corpus of real runs; "UNVERIFIED" marks an estimate. Every hit is 15.0 percent of max HP as true damage through the damage
service (a crusher 30, one recorded hit) with Hypixel's red line ("The Tripwire Trap / Crusher / Arrow Trap / Flamethrower hit you for N damage!").

* **Crushers are arms.** Each step out adds the next layer of the slab's size (polished granite) and leaves the earlier ones standing, each step back removes the tip
  layer, and the home slab is never touched (a full New Trap arm is 264 blocks, not a sliding plate). The first step comes 4 ticks after the room wakes. The Old Trap's two
  arms are one 13-layer pincer (period 155, tips meeting at x 14 | 15), the New Trap's low crushers 6 to 8 run 3 ticks a step and rest 18, and crushers 4 and 5 draw one of two
  paces per run. An arm reaching full extension slams (`entity.iron_golem.hurt`, 0.492), and so does the arm that hits a player (once per immunity window).
* **Tripwires** hit the player who touched the string on the same tick (no delay, no corridor), the hooks click on and, 10 ticks later, off (0.587 / 0.492); only the 13 rooms
  where real strings were seen to hit hurt anyone (`tripwireHurtRooms`); the Old Trap's strings click and set the floor off; every other string only flips its hooks.
* **Falling floors.** The New Trap's 143-block entrance floor goes in one tick the first time a human stands on it (falling-block entities, lava-extinguish 5.0 / 0.492, no
  fall damage: health did not change in the recorded drop); the Old Trap's walkway goes when a human touches the string at x = 15 (the recorded collapses coincide with that string
  in seven runs; the string at x = 13 powered 14 ticks after one collapse and with the collapse tick in one other, so it is not wired). The other 11 designs with `fallingBlocks` are
  not triggered yet: their triggers are not mined.
* **Arrow banks** (`ArrowBank`, `TrapArrow`). A bank is a set of dispensers that fire one arrow each, all in one tick, on a fixed beat (30, 35, 40 ticks in most rooms, 20 in Dino
  Site and Red Green, 5 in Mines), whether or not anyone is near: the beat is read from the gaps between volleys of the same set (14 banks kept; the rule that keeps a bank is in
  `packs/traps/hazard_sites.py`). The sim runs a bank's clock while a human is within 48 blocks of it and draws the phase once per run. The arrow leaves 0.7 blocks in front of
  the dispenser, aimed along the facing and a tenth up, speed 1.50 (sd 0.07), inaccuracy 6, as vanilla's dispenser does and the 49,000 recorded spawns fly. It is silent at the dispenser
  (0 `entity.arrow.shoot` in 150 volleys), hits only a human or a block (1.8's 0.3 margin), makes `entity.arrow.hit` when it lands and is gone then (recorded arrows lived 1 to 15 ticks
  mostly).
  *Not modelled:* the Arrow Trap room's own trap (nine clicks 5 ticks apart from a plate at (13, 68, 7), one 13-arrow volley 30 ticks after the first click: the pack has no plate block there
  and in all nine visits the recording player stood elsewhere, so a teammate stepped on it); the Old Trap's 30-to-80-arrow bursts at the arm bases (cause open: a probe with entity ticks on is
  needed); banks seen fewer than three times (no beat).
* **Flamethrowers** (`FlameBank`). Every room fires on an 8-tick beat (gaps of 8 in 61 percent of 547 gaps, 7 or 9 in another 22, in the 18 rooms with enough shots). Emitters are dispensers (the sound point
  is the block's corner; each shot is matched to the nearest dispenser, or to one in line 2 to 8 blocks behind it for the far end of the stream). In most rooms one emitter fires per beat and
  a pair alternates; the New Trap's pit shows one to three. The sim fires one emitter per beat (never the one that just fired when another can), the New Trap pit one to three of those within 9
  blocks of a human who is in the pit, with `entity.ghast.shoot` (HOSTILE, 1.0, 0.698) and flame particles. A player in the stream (6 blocks long, 1 to each side, 1.4 tall; UNVERIFIED, from 20
  recorded hits) takes the hit one tick after the shot at the earliest; the recorded lag was 1 to 6 ticks.
* **Quiet hooks.** Hypixel plays no tripwire sound (none in 509 recorded runs) and next to no dispenser click (two); the sim's vanilla tripwire hooks and empty dispensers would. `AdsTrapsQuietHookMixin` and
  `AdsTrapsQuietDispenserMixin` drop those sounds in the sim's world (they need their lines in `engineerclient.mixins.json`: integration request).
* **Deaths.** A fatal trap hit is announced "died to a trap and became a ghost." for a teammate (one recorded line); the sim prints the plain "died" because the damage service prints it
  (integration request, damage module).

In-game checks (`/ads dev traps ...`): `list` shows the rooms with hazards, `tp [n]` takes you beside the first of one, `status` shows clocks, floors and banks, `arrows [n]` fires a room's banks now.
Stand in Slime or Mines: a flame every 8 ticks from the emitters and the red line when one catches you; in Melon, Buttons or Gravel arrows cross the room every 30 to 40 ticks; no tripwire
or dispenser click anywhere (after the mixins are listed).

## Gate checks

* Odin's own Water Board table: every one of its 20 entries (two optimisation levels, ten closed-pit choices) opens all three pits of the real room (`WaterBoardOdinTest`).
* Odin's Teleport Maze solver, ported into `TpMazeModelTest`: following its pointer solves 300 drawn mazes, and its pointer stays right after wrong steps.
* The Ice Path has a way through in the base room and in every recorded state (`HardRealRoomsTest`).

The optional tests need `ADS_REAL_PACKS` (the built packs) and `ADS_ODIN_PUZZLES` (a folder with Odin's `water-solutions.json`); without them they skip.
