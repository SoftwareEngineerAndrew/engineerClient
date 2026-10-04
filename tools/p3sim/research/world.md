# F7 boss world: the arena's state at each phase start, and the world rules that are not frames

Source: 101 complete F7 runs (2026-10-01..04, Better PF uploads + local, all with chat `n`), block
changes extracted per run (`/home/cam/backups/2026-10-03/analysis/world/extract.mjs` → `runs/*.json`)
and folded into the real state at P1, P2, P3 (Goldor's line), S2/S3/S4 (door + 8), core (+25), P4
(Necron's first line) and END (`agg.mjs`, `agg.txt`). `compare.mjs <POINT> <anims>` diffs that
against arena.bin + the animations the sim plays by then. Scripts for the numbers below are in the
same folder (`p1end*.mjs`, `plat*.mjs`, `carve*.mjs`, `tnt2.mjs`, `pillars.mjs`, `stripcheck.mjs`,
`strip3.mjs`, `stripperiod.mjs`, `lavacols.mjs`, `p4hole.mjs`, `spots.mjs`).

Where the sim's world comes from: `arena.bin` = the Better PF room library's 110 `Boss|F7|cx,cz`
volumes (captured 0-12 s into P1) + `tools/p3sim/arena-fixes.json` (the P1 strip caught
mid-animation), built by `tools/p3sim/build-arena.mjs`; the void world generator fills chunks from
it (`Arena.fill`). Everything later is `Blocks`: recorded frames (`anims-p3.json`,
`anims-p124.json`), the rules below, and the phases' own sets (devices, levers, pillars).

## 1. Phase starts (real majority vs the sim)

- **P1:** no position differs from the library in ≥50% of runs at Maxor's line. The strip's start
  is not one state: in 49/101 runs only chunks x48-79 get conveyor update 0 (the other chunks were
  already on that frame), in 36 all five; from update 1 (+20) every run is the same. arena.bin
  matches the commonest case closely enough (the difference lives 10 ticks).
- **P2:** the platforms (crumble, §2), the centre hole (p1end), the strip where Maxor died, the beacon
  column bedrock (221) under red stained glass (222-224). (73,220,47-49) → air at -15 in 99-100% of
  runs (was missing from p1end; added). Lights-device lamps lit in ~50% (pre-dev: players).
- **P3 (Goldor's line):** + Storm's pillars. Purple (97,62) and Yellow (43,62), the two that crush,
  hang with their bottom at y181-186 (Purple, ~50/50) / 183 (Yellow, 99%), and **every** block
  from there to y189 is polished diorite: the four diorite columns are polished there too (lib
  diorite) in 97-100% of runs. Hypixel never puts plain diorite back. Green and Red as built.
  Lamps lit 92% (pre-dev).
- **S2/S3/S4:** gates, doors, SS buttons as anims-p3; plus Goldor's carving (§3) along the S4 → S1
  corner (x83-104, y114-124, z35-58) and the S1 TNT cube (§4).
- **Core / P4:** core door, p3end as recorded; nothing else on the P3 level beyond §3/§4. At P4 a
  3x3 at (53-55, 63, 113-115) on the Necron platform is gone in 87% of runs: broken one block per
  1-2 ticks 2-120 ticks before Necron's line, back 220 ticks later in all 101 runs: a player's
  Dungeonbreaker (220 t regen, dungeonbreaker.md §4), not the world.
- **END:** P4 lava (P4 agent): the first lava column falls at one of 7 spots ~220 ticks before
  "Let's make some space!" and the other spots start at the line (-4..+26): (92,76) 75%,
  (82,48) 73%, (26,48) 72%, (54,38) 71%, (26,104) 70%, (82,104) 69%, (16,76) 52% of runs.

## 2. The P1 → P2 crumble is random

The two crystal platforms (x46-64 / x82-100, y215-224) do not all go: of their 1174 blocks that go in
≥10% of runs, 552 go in ≥95%, the other ~620 (mostly y216, 219, 220: stone, stone bricks, andesite)
in 50-70%, a **different set every run** (per run 63% of them, median; speckled holes, see
`platmap.mjs`). All at the burst tick (-24 from Storm's line). The recorded p1end kept only frames
seen in ≥5/8 runs, so it removed a fixed 70% subset. Now those frames carry a 6th element, the
chance (`world_p1end.mjs`), rolled per fight.

## 3. Goldor carves the walkway (P3)

Every 40 server ticks from n 37 (bursts at n 37, 78, 119, 158, 199, 239, 279, 319, 359, 400, 439 in
the tracked runs: 37 + 40k ±1), the blocks in the box round Goldor's block (x, z ±5, y -5..+5: y114-124
on the S1 line) go **each with a ~60% chance**, rolled again every pass (126 passes with him in view,
30 runs: carved / in box: stone bricks 0.59, carpet 0.56, cracked 0.60, mossy 0.59, stone 0.58,
andesite 0.55, quartz 0.56-0.71, stairs 0.58-0.71 ...). So a wall column right of his path goes in
steps (x90 z35 nether bricks: 6, 4, 1 blocks at +117, +158, +200). Never carved: barriers (3189 in
boxes), gold blocks (92). Always carved: cobblestone, cobblestone walls, nether brick fences (the S1
entrance portcullis at x91 goes whole at +110..118 in every run). Levers are never carved in the data
(Goldor never reaches one before he sprints on); the sim leaves levers, buttons and block entities.
The few y106 lava blocks under the start (x77-87 z35-45 → air at +56..69) are not modelled
(under the floor, 8-12 blocks).

## 4. TNT cubes: not a 200-tick timer

The 3x3x3 TNT cubes (anims-p3.json `tnt.cubes`) go one at a time, in one tick. Cube 0 (x99-101
y127-129 z85-87) in 57/101 runs at +227..301 (median ~+238), cube 1 in 7; each later cube in only
1-4 runs, so there is no every-200-ticks slot (anims-p3 `tnt` _doc). In 10 of 15 checked runs the
removal is the tick (±4) of a "What do you think you are doing there!" line the recorder got (the
death tick at n 239/299); the rest had no such line in its chat. Most likely the death tick's
explosion, which the data can't fully pin down (the line may only reach the player hit). Not
simulated here; the sim's death tick (GoldorPhase) is the place for it.

## 5. Spots

All standable in arena.bin. The S1-S4 starts were inside the walkway carpet (feet y = carpet's block);
now +0.0625, on it.
