# P3 devices, levers and stands: ground truth for p3sim

Source: 77 Boss Recorder files (server packets, `n` = server tick), 61 with an S4/S3 board in range.
Scripts in `scripts/` (run with the nix python3 from that dir): `devlib.py` (loader), `s4_target.py`,
`s4_timing.py`, `s3_arrows.py`, `s3_arrows2.py`, `s3_timeline.py <file>`, `s2_lights_levers.py`,
`s2_detail.py [file]`. **M** = measured, **C** = conjecture/inference.

## The big one: devices are live before their section ("pre-dev")

- **M** All three non-SS devices can be worked before their section. Players pre-do Lights in
  Maxor/Storm, Arrow Align in Storm, and the S4 target as soon as P3 starts.
- **M** The S4 target lights from **P3's start** (Goldor's "Who dares trespass into my domain?"),
  not from S4. In 56 of 61 runs its targets were lit before S3 ended, mostly finished in S1.
- **M** An early completion prints the line with the **current** section's count, unchanged.
  Example: `_itaytheking_ completed a device! (5/7)` right after a terminal's `(5/7)` in S1. When
  S4 starts its first line reads `(2/7)`, so the early device counts as S4's first.
  GoldorPhase.complete already does this through `early`/`count(shown)`.
- **M** Arrow Align frames turn during Storm and the turns stay. Players leave the board one click
  short, then make that click when S3 starts. The device line comes in the same tick as that click.
- **M** Lights levers and lamps work during Maxor. Players light all 20 lamps there, then flick
  any lever in S2, and the device completes on that click.

## 1. S4 target ("i4", sharpshooter)

- **M** The plate is `minecraft:light_weighted_pressure_plate` at (63,127,35). It shows `power=1`
  with one player on it and `power=2` with two, often for 10-tick blips. Players stand on it from
  Storm onwards, but nothing lights before P3.
- **M** The targets are 9 blocks at x∈{64,66,68}, y∈{126,128,130}, z=50, separated by
  `magenta_stained_glass_pane`. The idle and hit state is `blue_terracotta`; the lit state is
  `emerald_block`. Every change also resends the neighbouring panes with unchanged states, which
  is cosmetic and can be ignored.
- **M** Order: each run is a **random permutation of all 9 cells**, each lit exactly once. No cell
  was ever lit twice in a row, and the per-cell counts were uniform (54-59). Odin's ArrowsDevice
  keeps "marked" (already hit) cells, so it assumes the same thing.
- **M** Timing runs on a **10-tick grid**. While someone is on the plate and no target is lit, a
  new one lights on the next grid tick:
  - P3 start → first light: 0-11 ticks, uniform.
  - hit → next light: 0-9 ticks, roughly uniform, with a small tail out to 14.
  - Light times modulo 10 cluster on one phase per run.
- **M** A hit turns the emerald block back to blue terracotta. The completion line comes in the
  same tick as the 9th hit's block change, or one tick before it (45 of 61: blue one tick after the
  line). Nothing changes after completion: **the board stays all blue terracotta**. The stand
  flips to `§aDevice`/`§aActive`, and Odin also treats a stand named "Active" as done.
- **M** A run needs 9 hits, but **the next target is the target the moment the last one is hit, before it shows**
  (main server, 2026-10-03 Dungeon Recorder runs; `scripts/i4/board.py`, `hidden.py`):
  - **Lights shown before "completed a device!":** 9 lights in 4 of 32 attempts, 8 in 10, 7 in 10, 6 in 4, 5 in 2,
    4 in 2. A cell that never lit was hit while it was the next target but not yet shown: it counts, and no block
    changes.
  - **The light grid:** a hit k ticks after a grid tick shows the next target on the next grid tick (10 − k ticks
    later). A hit on a grid tick shows it **in that same tick**, in the same `section_blocks_update` as the hit's
    blue (17 of 17). So the server takes hits before that tick's light.
  - **No 1-tick delay:** the never-lit cells were hit by arrows of the same volley that hit the lit one. Hydra
    Strike's ±8° arrows land on the neighbouring cells, and the Terminator's ±5.5° pair on the cells either side of
    a gap. For 5 of them, arrows in that same tick were the only candidates. In 22-33-03, 4 cells never lit, and
    the counts only add up to 9 if a target can be hit in the very tick the previous one was. Odin's Show Aim
    Positions is built on this: it aims so one volley hits the target and unhit cells, which can be next.
  - **Not the next target:** an arrow into an unhit cell that isn't the next target does nothing; cells get hit
    many times before they light.
  - **Arrow → blue:** the block goes blue the tick after the traced arrow reaches the face (96 of 121; 0 or −1 in
    the rest).
- **M** Stepping off the plate (power 0) while a target is lit: that target goes blue 3-6 ticks
  later (n=2, which fits the 10-tick grid). **Progress resets.** The next time someone stands on
  the plate, a fresh permutation of 9 lights (00-43-27: 6 hits, step off, then 9 more; 18-20-13:
  5 hits, then 9 more).
- Arrow → block timing: not measurable (arrows were mostly not tracked). **C** The block changes
  in the tick the arrow hits. No dedicated "target hit" sound was found; the vanilla
  `entity.arrow.hit` (neutral, vol 1, pitch 1.08-1.3) is what plays at the board.
- **M** The device stand at (63.5, 126, 34.5) / (63.5, 125.625, 34.5) reads `§cInactive` over `§cDevice`, and turns
  `§aDevice` over `§aActive` 0-19 ticks after the completion line (its 20-tick grid; 42 completions). The line is
  `<name> completed a device! (n/7)` (`§b` for you, `§a` for others).
- Engineer Client (all work on Hypixel and in P3 Sim):
  - **i4 Complete Title:** a title while you're on the plate when your line comes, when the stand turns Active, or
    when a grid tick after a hit passes with nothing lit. Replayed over 36 main-server completions
    (`scripts/i4/title_replay.py`):
    - **Emeralds stopping:** fires 2-12 ticks after the line, and never earlier while someone is on the plate. It
      fires early 3 times, all with nobody on the plate, which its plate check blocks. A hit on a grid tick sends
      the new emerald before the old cell's blue, in one packet, so it tracks which cell is lit.
    - **Device tag:** 3-17 ticks after the line, but only when S4 is already running. For an i4 done early (during
      S1) the stand only turns Active once S4 starts (312-918 ticks later).
  - **i4 Bow Aims:** Odin's aim positions for the bow you hold. A Mosquito with Terror at 10 stacks covers a whole
    row from its middle cell. Aims are raised by the arrows' ~0.6 drop.
- Odin's expectations (ArrowsDevice.kt):
  - It reacts only to block updates `emerald→blue_terracotta` (marks a hit) and
    `blue_terracotta→emerald` (the new target).
  - It only acts in P3, with the player inside the AABB (20,100,30)-(89,151,51).
  - It completes on `^(.{1,16}) completed a device! \((\d)/(\d)\)$` with your own name, or on a
    stand renamed "Active".

## 2. S3 Arrow Align

- **M** Item frames are at x=-2, y120-124, z75-79, facing east (yaw -90, data 5), with **one
  entity per cell for the whole run** (they are never respawned). Odin's index is
  `(y-120) + (z-75)*5`.
- **M** **Frames exist only on the layout's cells.** Each layout's arrow cells (Odin's nine
  solutions are right) plus 2-6 extra frames. The extra frames are presumably not arrows (the
  recorder has no item data, **C**: the start/end markers). Seen layouts, as Odin index: extra
  cells (count):
  - 0: (2,22) (11)
  - 4: (12,20) (4)
  - 7: (0,20) (8)
  - 5: (4,20) (6)
  - 3: (4,12,24) (6)
  - 1: (5,14,15) (2)
  - 6: (0,2,4,20,22,24) (13)
  - 8: (1,3,20,22,24) (11)
  - Layout 2 was not seen.
- **M** Rotation is synced data index 10. A click turns the frame +1 (321 of 344 updates; the rest
  are packets merged under fast clicking). Fastest clicking is 1-2 ticks apart. The sound is
  `entity.item_frame.rotate_item`, player source, vol 1, pitch 1.
- **M** Initial rotations: in fresh runs about 11% of arrows are already right and the rest are
  spread over 1-7 clicks needed, i.e. **uniform 0-7, independent**.
- **M** Completion: the device line comes in the same server tick as the final rotation (9 of 10;
  1 a tick early). **C** It is checked on a click, not continuously; a board pre-solved in Storm
  would otherwise complete early, and players avoid that.

## 3. S2 Lights

- **M** There are 20 levers at x58-62, y133-136, z142 and 20 `redstone_lamp` blocks behind them
  at z143.
- **M** **Rule: lamp (x,y) is lit while any lever in its plus is on**: (x,y), (x±1,y), (x,y±1).
  It is an OR (redstone-like), not a toggle and not one lamp per lever. Example: lever 60,135
  lights 59,135, 60,134, 60,135, 60,136 and 61,135. The lamp follows its lever 0-1 ticks later
  (403 in the same tick, 221 a tick later).
- **M** **Done = all 20 lamps lit**, checked on a lever click in S2. At the device line, lamps lit
  were all 20 in every case. The levers on were:
  - Odin's six {58,133; 58,136; 60,134; 60,135; 62,133; 62,136} in 75 cases.
  - Those six plus an extra flick in 21.
  - The six minus one in 11. The completing click turned a lever off, and the lamp had not yet
    updated.
  - The device line comes 0 ticks after the lever change in 54 of 79 (-2 to +1 otherwise).
- **C** Start state: all levers off and all lamps off. No run showed lamps lit before anyone
  clicked.
- Sounds: `block.lever.click` at vol 0.3, pitch 0.59 for on and 0.49 for off.
- **M** Hypixel sometimes sends `X completed a device! (1/8)` twice in the same tick (9 cases,
  all device lines).

## 4. Simon Says

Devices.SimonSays matches docs/mechanics/simon-says.md on every point checked:
- grid, start button (110,121,91), 6-tick start window and 1/2/3-press shows;
- 8-tick lights, buttons back at +10 (+5/+18 after a stray), 3-tick press;
- next round +6, wrong press: buttons gone +3 and a new skip-style show +25;
- 5 rounds, done 6 ticks after the last press.

Two undocumented choices: the `running && t-startedAt<20` press guard, and accepting a start only
in section 1. Whether SS can be started before P3 is not measured.

## 5. Levers (the 8 section levers)

- Positions (all `face=floor,facing=north`):
  - S1: (106,124,113), (94,124,113)
  - S2: (27,124,127), (23,132,138)
  - S3: (2,122,55), (14,122,55)
  - S4: (84,121,34), (86,128,46)
- Sound on pull: `block.lever.click`, block source, vol 0.3, pitch 0.59.
- **M** Chat: `<name> activated a lever! (k/N)`. The lever block goes `powered=true` 0-1 ticks
  after the line (353 of 384).
- **M** Refusals:
  - `This lever has already been used.` appears only **before P3** (197 times).
  - `Someone has already activated this lever!` appears for a lever already pulled in P3 (100).
  - "This lever doesn't seem to be responsive at the moment." was never seen. Whether a later
    section's lever can be pulled early is not measured.
- Stand: one marker stand `§cNot Activated` → `§aActivated`, 1-3 ticks after the pull
  (terminals.md).

## 6. Stands and progress lines

These are as in terminals.md, which this data confirms.

- Terminal stand: `§cInactive Terminal` over `§e§lCLICK HERE` (0.375 lower). On completion they
  become `§aTerminal Active` over `""`, on a 20-tick refresh grid.
- Device stand: `§cInactive` over `§cDevice` → `§aDevice` over `§aActive`. The S4 device stand
  is at (63.5,126,34.5).
- Progress lines, formatted `§<rank>name§r§a <what> (§r§c<k>§r§a/<N>)`, with N = 7 (8 in S2):
  - `activated a terminal!`
  - `activated a lever!`
  - `completed a device!`
- Terminal refusals: `This Terminal doesn't seem to be responsive at the moment.` (218) and
  `This Terminal has already been completed!` (13).
- `block.note_block.pling` (block source, vol 8, pitch 4.05) plays thousands of times, not tied to
  progress lines. The sim's 0.6/2.0 pling on terminal/lever completion is unverified.

## Update 2026-10-04 (404 runs; scripts in `/home/cam/backups/2026-10-03/analysis/terminals-devices/`)

- **Arrow Align extra frames hold wool**: lime wool = a path's start, red wool = its end, the same
  cells in every run of a layout (newer recordings carry frame items). Layouts seen (of 280 runs
  identified): 1:26 2:40 3:30 4:32 5:28 6:43 7:42 8:39, **layout 0 never**. What §2 called layout 0
  with extras (2,22) is layout 2 (cell 12 is its lime wool, not an arrow). Extras, Odin index:
  L1 lime 5,15 red 14 · L2 lime 12 red 2,22 · L3 lime 4,24 red 12 · L4 lime 20 red 12 ·
  L5 lime 20 red 4 · L6 lime 20,22,24 red 0,2,4 · L7 lime 20 red 0 · L8 lime 20,22,24 red 1,3.
- **Simon Says sounds** (`sspling.mjs`): Hypixel sends no button click at all. Each grid press is
  answered with `block.note_block.pling` vol 8 pitch 4.05 at the presser (all ~400 own presses that
  showed a pressed button), and a start with `entity.enderman.teleport` vol 8 pitch 0 at the
  presser, once per burst of start presses. `entity.experience_orb.pickup` (vol 1, pitch 1) also
  plays at SS now and then; what triggers it is not established.
- SS blocks confirmed: `stone_button[face=wall,facing=west]` on x=110, `sea_lantern`/`obsidian` on
  x=111.

## Mismatches vs the sim

1. **Target only runs in `section >= 4`** (Devices.Target.tick). It should run from P3's start; an
   early finish is handled by GoldorPhase already.
2. **Target `light()` picks any cell but the last.** It should be a random permutation of the 9
   cells, one each.
3. **Target lights instantly** after a hit and on stepping on the plate. Real lights come on a
   10-tick grid (next grid tick, 0-9 ticks later).
4. **`Target.clear()` turns all 9 emerald** on completion (and in `shownDone`). Real: all stay blue
   terracotta. This would also make Odin see 9 "new targets".
5. **Stepping off only unlights the current target.** Real: the lit target goes blue on the grid,
   and **hits reset to 0**, with a fresh permutation next time.
6. Target hit sound `ARROW_HIT_PLAYER` 0.5: no such sound was seen. Use vanilla
   `entity.arrow.hit` or nothing (C).
7. **Arrows spawns 25 frames, empty ones included.** Real: only the layout's arrow cells plus 2-6
   non-arrow frames (cells listed in §2). Empty frames at the other cells do not exist.
8. Arrows initial rotation is biased away from correct (about 4% correct). Real: uniform 0-7
   (about 12%).
9. Arrows frames are placed at P3 start. Real: they exist and turn from the start of the boss
   (pre-dev in Storm). Only matters if the sim adds pre-P3 phases.
10. **Lights rule is wrong.** The sim has "each lever its own lamp, done = exactly the six on".
    Real: a lamp is lit by an OR over the lever plus, and done = all 20 lamps lit, checked on a
    click.
11. **Lights start state is wrong.** The sim has random levers on (25% each). Real: all off (C).
12. **`Lights.use` ignores clicks outside section 2.** Real: levers toggle any time (pre-dev);
    only completion needs S2. A click in S2 with all 20 lit completes, even one that turns a
    lever off.
13. Lever refusal before P3: real `This lever has already been used.`. The sim's wrong-section
    text ("doesn't seem to be responsive") was never observed for levers.
14. Lever stand: the sim renames on the 20-tick grid. Real: 1-3 ticks after the pull.
15. Unverified: pling on terminal/lever completion (GoldorPhase line 163). The duplicate device
    line is not reproduced (harmless).
