# F7 boss arena: layers, phase transitions, dynamic blocks

Source: 105 F7 recordings that reach Maxor (99 reach Storm, 91 Goldor, 88 Necron), all
`runs/*.jsonl.gz` up to 2026-10-01. Library: 110 `Boss|F7|cx,cz` volumes in `cache/`. 90 of them
were captured at t≈2392-2635 in a run whose Maxor line came at about t 2390, and 20 (the `-1,*` / `*,-1` edges) at
t 4671-4711 in `2026-09-24_15-11-22` (Maxor at 4663). So the library is a snapshot of the first 0-12 s of P1.
Generated data: `arena-dynamic.json` (blocks) and `arena-entities.json` (entities). The scripts are in
the job scratch dir (`extract.js`, `agg.js`, `final_blocks.js`, `entities.js`).

## Y layers (top to bottom, one column stack, x ≈ 0..112, z ≈ 30..145)

| phase | floor | where | notes |
|---|---|---|---|
| P1 Maxor | y ≈ 215-224 platforms, Maxor spawns at (73, 226, 53) | two crystal platforms west x46-64 / east x82-100, z35-51; centre x68-78 | crystals at (64.5, 238.4, 50.5) and (82.5, 238.4, 50.5); status lamps y236 |
| P2 Storm | y ≈ 169 (pads), ceiling/pillars up to y190 | whole square; pillars/crushers at x43-49 and x97-103, z62-68 | Storm wither appears around (80, 183, 76) |
| P3 Goldor | y ≈ 113-115 walkway ring; core centre x39-69 z99-129; lava at y106 | S1 east (x≈90-110), S2 north (z≈120-143), S3 west (x≈-3..20), S4 south (z≈34-55) | devices: SS (110,120-123,92-95), lights (58-62,133-136,142-143), arrow align frames at x=-2, y120-124, z75-79, targets (64-68,126-130,50) + plate (63,127,35) |
| P4 Necron | y ≈ 63-69 platform over lava (y59-63) | x≈13-94, z≈31-129 | Necron wither spawns at (54, 66, 76) |

## How players get from one phase to the next (they fall)

- **P1 → P2:** at the end of Maxor (median 530 ticks after his first line) the **centre floor** of P1
  x68-78 z32-49 opens: the y220 polished andesite / stone brick stairs floor turns to air, and a block-by-block "falling" animation runs down y219→212 (1166 positions). About 100 ticks into Storm the floor is put back. Both **crystal platforms
  collapse to air** (≈607 positions each, y215-224). Players drop about 50 blocks onto the Storm level (y≈169). The library
  still holds both platforms intact, which is right for the start.
- **P2 → P3:** 22 ticks after Goldor's "Who dares trespass" line, the **red pad floor hole** x96-105 y167-168 z36-45 (152 positions:
  barrier + red terracotta + stone bricks → air) opens. Players fall into the SE corner of P3 (S1 start, y≈115).
- **P3 → P4:** late in P3 (median 1253 ticks after Goldor starts, right before Necron's first line) the **bedrock floor under the core**
  y101-104 x39-69 z99-129 (676 positions) and the core platform's gold/bedrock blocks (y113, x51-57 z111-117) turn to air. Players fall to
  the Necron platform (y≈64).
- Recordings often show some players on the P3 level during Maxor or Storm. These are players getting into position early
  ("pre-dev"). The SS buttons, lights levers and pressure plate change during Storm in many runs, and sometimes during Maxor.

## Dynamic-block clusters (scripted means it changes in ≥30% of the runs that reach the phase)

28,018 positions change during a boss in at least one run. 11,660 of them are scripted, in 123 named clusters. The rest
(16k, in clusters named `rare (...)`) change in fewer than 30% of runs: ghost blocks, client-predicted breaks, explosions and lava flowing.

| cluster | positions | phase | start → states |
|---|---|---|---|
| P1 animated strip (y221-226, z70-76, x34-112) | 1025 | Maxor intro (first ~400 ticks) | cyan terracotta ↔ gray wool rows, plus coal block / player head / air at y225-226, cycling about every 20-70 ticks |
| Maxor crystal status lamps (68-78, 236, 64-69) | 16 | Maxor | coal block → sea lantern → coal (crystal placed/used) |
| Maxor west / east crystal platform collapse | 606 / 607 | Maxor end | stone bricks etc. → air |
| P1→P2 centre floor hole (x68-78 z32-49) | 1166 | Maxor end; refilled ~Storm +100 | y220 floor → air, falling-block animation y219→212 (air → andesite → air), floor restored in Storm |
| P2 pillar/crusher west (green pad) / east (yellow pad) | 814 / 814 | Storm | polished diorite/diorite pillars pushed by pistons (moving_piston → piston → diorite → air), y169-190 |
| P2→P3 drop hole (red pad) | 152 | Goldor +22 | barrier/red terracotta → air |
| S1 door barriers (SE, x89-104 z35-59) | 613 (+small bits) | Goldor 77-584 | walls/barriers → air |
| S1/S2 gate (NE) | 448 + 240 + 94 (+small) | Goldor ~277-624 | → air |
| S2/S3 gate (NW) | 449 + 441 (+small) | Goldor ~563-877 | → air |
| S3/S4 gate (SW) | 471 + 272 (+small) | Goldor ~859-1238 | → air |
| core door (52-56, 115-121, 54) | 35 | Goldor | gold block → barrier → air (gold again on reset) |
| Goldor core platform + P3→P4 bedrock floor | 49 + 676 | end of Goldor | → air |
| SS buttons (110-111, 120-123, 92-95) | 33 | Storm (pre-dev) / Goldor | buttons appear/disappear, obsidian ↔ sea lantern (the SS display) |
| lights device lamps + levers | 26 (20 lamps, 6 levers) | Storm/Goldor | lever powered on/off, lamp lit on/off |
| S4 target blocks (64-68, 126-130, 50) | 9 | Goldor | blue terracotta → emerald → blue |
| pressure plate (63,127,35) | 1 | Storm/Goldor | power 0-3 (S4 device plate) |
| levers (106,124,113), (94,124,113), (27,124,127), (23,132,138), (14,122,55), (2,122,55), (86,128,46), (84,121,34) | 8 | Goldor | powered=false → true (sometimes back) |
| P3 lava (y106) cleared | 67 | Goldor | lava → air |
| P3 TNT block (99-101, 127-129, 85-87) | 27 | Goldor | tnt → air |
| P4 lava rising/flowing | 1551 | Necron | platform blocks → air → lava (various levels) around the platform edges |
| P3 polished granite pillars (lib = air) | 516 | every phase | air ↔ polished granite, 3×3×3 blobs at fixed spots (x2-18 / x53-66, y107-127). These look like helper blocks one of the recording mods places, not Hypixel blocks. |

## Library vs start state

- Every scripted position outside the P1 animated strip has a library state equal to the start state. For these, the first
  boss change in nearly all runs sets a *different* state, and the library state is the one they return to.
- **The P1 animated strip needs fixing (300 positions in `libraryFixes`).** The library caught it mid-animation. At
  those positions the library state equals the first change in most runs, so the start is the other state:
  cyan terracotta → gray wool (107), gray wool → cyan terracotta (106), coal block → air (28) / air → coal block (34),
  head → air (10) / air → head (13). Since it is a looping animation, the simplest fix is to rebuild the strip from the
  pre-first-change state of one run, or to play the animation from the recording.
- The library stores the P3 granite blobs as air, which is correct: they are not part of the start state.

## Entities (arena-entities.json)

- **Maxor:** wither at (73, 226, 53) in all 105 runs. Nametag stands at (73, 229.7 / 230.1, 53): `﴾  Maxor ﴿` plus his dialogue lines.
  End crystals at (64.5, 238.4, 50.5) and (82.5, 238.4, 50.5), each with an invisible stand pair at y238.1 / 238.5. More crystals spawn about 169 ticks
  in at (52.5, 224.4, 41.5) and (94.5, 224.4, 41.5) (the pickup crystals). Wither Guards (wither skeletons) spawn at y≈227-236, x44-60, z73-80.
- **Storm:** arrow-align item frames at x=-2 (yaw 270, facing -x), y120.5-124.5, z75.5-79.5, holding arrows with
  random rotations, plus lime_wool (start) and red_wool (targets). They are only present when the recorder was close
  enough, in about 50% of runs. Wither Guards on y≈172-177.
- **Goldor:** terminal and device stands (`Inactive Terminal`/`Terminal Active`, `CLICK HERE`, `Device`/`Inactive`) at
  (90.5,111,92.5), (110.5,112,73.5), (110.5,118,79.5), (90.5,121,101.5), (110.5,119,91.5) and more. Lever stands
  `Not Activated`/`Activated` at each lever (+0.5, +0.7). A **giant** around (83, 111, 34-40) in every run (Goldor's model).
- **Necron:** wither at (54, 66, 76) in all 88 runs, nametag `﴾  Necron ﴿` at (54, 69.7, 76).
- Entities only show up when they are inside the recorder's tracking range, so absence in a run is weak evidence.

## Surprises

- The P1 floor has a 1025-block animated strip in the first ~20 s. The library caught it mid-cycle.
- The P3 gates are big: the four corner gates are 450-900 blocks each, including barrier and cobblestone fill, and they
  open over hundreds of ticks.
- Lava rises in P4 into roughly 1.5k positions around the platform.
- Polished granite 3×3×3 blobs appear in P3 in almost every run (lib = air). They are probably client-side.
