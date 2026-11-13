# Handoff: Water Board solver, puzzle research, recordings tooling (sessions of 2026-11-12/13)

Everything this work produced, where it is, how to run it, and what's open. Start here.

## 1. State of the code

- **`main`** (`2f88863`) has the whole Water Board solver: simulator fixes, objectives, plan table, solver wiring,
  tick-based countdown. Pushing to `main` runs `.github/workflows/release.yml` (it builds and releases).
- **`claude/confident-einstein-7193oi`** is `main` plus the documentation and tools not merged yet: `tools/tictactoe/`,
  `docs/puzzle-solvers/`, `tools/waterboard/`, `tools/recordings/`, this file. None of it changes the mod.
- `.gitignore` ignores `libs/` (the Odin jar the build compiles against; CI downloads it).
- **Not tested in game yet:** the Water Board plan table, objective selector, click sound, live recovery and countdown.
  All are unit-tested and the full mod builds; the first real runs are the next check (record them with the Dungeon
  Recorder).

## 2. Data: the Dungeon Recorder bundle

The user hosts `dungeon-recorder-data-2026-10-16.zip` (26.6 GB; ask the user for the link). Contents (its `INDEX.md`):
`recordings/devgineerclient-recordings/` (89 runs, Oct 12-16, about 5.2 GB, **the first 4.54 GB of the zip**),
`recordings/engineerclient-recordings/` (433 runs, Oct 3-6, about 23 GB, after that), and `notes/` (format reference
`notes/hypixel-lag/ref/dungeon-recorder.md` and the decoder `read.py`).

**Download quirks:**
- The server advertises range support but ignores it: every request starts at byte 0.
- Long transfers get cut at random points (1.0-6.4 GB in), so the full 26.6 GB has never come through.
- The devgineer part came through in one pass.
- `tools/recordings/stream_save.py` saves entries as the zip streams, stops at the engineerclient section and skips
  files already saved: `curl -sS <url> | python3 -I stream_save.py OUTDIR LOG`.
- `stream_zip.py` is a variant that keeps only files you choose, filtered on the fly.
- The zip uses no data descriptors, so parsing the local headers is enough.
- For the engineerclient part, ask the user to re-host it in pieces (`zip -s 500m`), or only the recordings needed.

**Recorder bug (not fixed):** `src/dev/recorder/ChunkCapture.kt` `indices()` returns all zeros, so a JSON
`level_chunk_with_light` section with more than one block type is written as one run of its first palette entry.
Block data must come from the raw frames (`partNNNN.raw.gz`): `tools/recordings/rawchunks.py REC X0 Z0 FIRST_SEQ out.json`
decodes the 31x31 room box (y 48..111, world coordinates) as it was just before packet `FIRST_SEQ`. Its palette order is
checked against the block_update id pairs and never mismatched. Likely cause: `packed.storage()`/`bitsPerEntry()` in
26.1's `PalettedContainerRO.PackedData`; check against the real API before fixing.

## 3. Water Board: what the server does (all measured on recordings)

Full detail lives in the comments of `src/waterboard/WaterSim.kt`, `Tracker.kt` and `Planner.kt`.
- **Water:** Minecraft 1.8 `BlockDynamicLiquid`/`BlockStaticLiquid` with scheduled ticks (5-tick water), as the sim
  already modelled. Matched packet for packet, on the tick.
- **Random ticks:** flowing water is randomly ticked (1.8 `BlockLiquid.setTickRandomly(true)`). About 1 per 350
  flowing-cell-ticks (3 seen in 1116, so a wide interval). They only ever make water early, never late. This makes the
  board non-deterministic: in about 8% of Monte Carlo trials the outcome order changes.
- **Hypixel differs from vanilla here:** a water block that dries up does **not** leave a level-7 block below it.
  Seen once, unambiguously; this contradicts the earlier code comment that cited 40 runs.
- **Pistons:** a pushed block's moving-piston destination is placed with flag 4 and not sent. The client sees nothing
  until the block lands 2 ticks later, unless the push destroyed water, in which case it sees `moving_piston`.
- **Gates:** water arriving at an outlet toggles that gate on the same tick. Both closing and opening were seen; the
  opening was predicted within 0.3 ticks on a blind board. The chest comes about 11 ticks after the last gate opens
  (the planner's `HOLD` is 10).
- **The water lever:** the reservoir above (x14-16, y82-89) is still water; the first flow comes 4 ticks after the
  lever packet. Turning the water **off** was never observed.
- **Layouts:** exactly 4 boards (Odin's patterns: ours 0,1,2,3 are Odin's 1,3,0,2), each seen in all 4 rotations, always
  with 3 of 5 gates closed. Rotation barely matters (2 of 40 board+gate combinations differ, by 1 tick).
- **Validation:** 2 tuning boards (NORTH, SOUTH) plus 4 blind boards (WEST): every server write reproduced
  (cell+value), tick-exact or within ±2 ticks of a fitted server clock. **No EAST board has been checked.**

## 4. Water Board: what's built (`src/waterboard/`)

- `WaterSim`: the simulator. Random-tick support (`step(flips, random)`, `randomTick`, plus `rng`/`randomRate` for
  Monte Carlo), no dried-up flow, unsent piston destinations.
- `Tracker`: follows the server from its packets. When nothing explains a packet it tries a random tick on that cell
  or a neighbour, keeps the one that does, and replays (`randomAt`). On a88e9b this raised matched packets from 198/218
  to 218/218.
- `Planner`:
  - `Objective` FASTEST / SAFEST / EASIEST / BALANCED. SAFEST and BALANCED are scored by Monte Carlo over random ticks
    and ±2-tick clicks.
  - Candidates are chosen on trials the search never saw (it used to overfit its own seeds).
  - `Spacing` is calibrated from one recorded solve: 4.5-block reach, 4-tick minimum gap.
  - `robustness()` and `checkCost()`.
- `PlanTable`: loads `src/resources/assets/engineerclient/waterboard/plans.json` (640 plans, 4 layouts x 4 rotations x
  10 gate sets x 4 objectives, each with its solve rate over 1000 trials). Matched by the board's rows exactly.
- `WaterBoardSolver`:
  - "Water Board Plan" selector (default Safest) and "Click Sound".
  - On a fresh board the table plan is adopted, shifted so it starts once the player can reach the first lever.
  - Afterwards the live planner replans with the same objective.
  - The countdown counts whole server ticks (tracker clock + click latency) shown as seconds; the click sound uses the
    same count.

**Table results (all 160 boards, 1000 trials each):**

| Objective | Average solve rate | Worst board | Median solve |
|---|---|---|---|
| Safest | 98.8% | 89% | 11.0 s |
| Balanced | 98.8% | 89% | 11.2 s |
| Fastest | 65.5% | 6% | 10.1 s |
| Easiest | 60.8% | 3% | 11.5 s |
| Odin `false` table | 97.9% | 64% | 13.3 s |
| Odin `true` table | 75.8% | 0.5% | 10.5 s |

Odin's `true` table never solves 26 of the boards in the simulation.

**Tests** (`test/waterboard/`, resources in `test/resources/`):
- `WaterBoardRecordedTest`: every recorded server packet on its tick, gate timing, random ticks, tracker lock on
  the 4 blind boards.
- `PlanTableTest`: table complete and self-consistent, recorded boards found, a late flip is recovered from.
- `PlanTableGen`: builds the table, only with `WB_GEN=1`.

**Open items:**
1. Real-game check of everything in this section, especially the click sound and countdown timing, and how the
   recovery behaves.
2. An EAST-facing board in the validation.
3. Balanced came out almost the same as Safest. Make it favour fewer clicks more if a distinct option is wanted.
4. `Spacing` rests on one solve. Players with Aspect of the Void or etherwarp move faster; consider a setting.
5. The random-tick rate (1/350) comes from 3 events. More recordings would tighten it, and it feeds SAFEST.
6. Not observed: the water lever turned off, and a gate re-closing after it opened.
7. Odin's plans were treated as infeasible on some boards by the walking model. If players really do them, the
   model is too strict.

## 5. Building and running (what worked here)

- **Full mod:** JDK 25 (Adoptium), Gradle 9.8.0, the Odin 0.4.0 jar unpacked to `libs/odin`
  (`https://github.com/odtheking/Odin/releases/download/0.4.0/Odin-0.4.0.jar`), then
  `gradle build -x test` (what CI runs). The system Gradle 8.14 can't load Loom.
- Maven Central often returns **429**: retry with backoff.
- Build in a separate `git worktree`; don't `git stash` while a build runs.
- **Water Board code alone (fast):** `tools/waterboard/standalone/` (`setup.sh`, then `gradle test` there; Gradle 8.14
  and JDK 21 are fine). Runs both water board test classes against the repo's sources.
- **Regenerate the plan table:** `tools/waterboard/gen-chunks.sh` (16 resumable chunks, about 35 min on 4 cores), then
  `python3 tools/waterboard/merge-chunks.py src/resources/assets/engineerclient/waterboard/plans.json`.
  Regenerate after any change to `WaterSim`, `Planner`'s objectives or `Spacing`; `PlanTableTest.everyPlanSolvesWhenItSays`
  catches a stale table.
- **Python harness (research):** `tools/waterboard/`. Port of the sim (`sim.py`), extraction from a recording
  (`extract.py`, `wbprep.py`, `layouts.py`), alignment to server ticks (`align.py` DP, `linfit.py` clock fit),
  Monte Carlo (`mc.py`). Run on recording data with `python3 -I`, except these scripts import each other: run them
  from their folder without `-I`.

## 6. Tic Tac Toe (paused)

`tools/tictactoe/HANDOFF.md`. Only 4 games were in view: the AI is perfect but random among equal moves (as far as 4
games show), replies after a fixed ~3 s, and ignores clicks before its move, so spam-clicking is safe. A guaranteed safe
pre-fire exists for 51-87% of moves depending on the opening. Needs 50+ recorded games before modelling further.

## 7. The other puzzles (research done, nothing built)

`docs/puzzle-solvers/README.md` summarises Higher/Lower Blaze, Creeper Beams, Boulder, Ice Fill, Ice Path, Three
Weirdos, Quiz and Teleport Maze, with one detailed note per puzzle: packets, fixed vs random, server timing, Odin's
gaps, the recommended design and the data still needed. The best-supported puzzles to build next: **Blaze** (track HP
from entity data, wrong-target guard, chest timer), **Boulder** (live grid, a plan per layout), **Ice Path** (fixed
route, "now" cue), **Quiz** (exact parsing, countdown to the answer gate). The research scripts for those notes were not
kept (they were in the session scratchpad); the notes give enough detail to redo them.

## 8. Gotchas from these sessions

- Odin's `TickEvent.Server` exists, but the tracker deliberately uses the water packets as its server clock (Hypixel's
  pings aren't one per tick).
- Recordings: entity spawn times are re-tracking times, not creation times; use entity ids (allocated in order) or the
  block change that comes with the action. Stairs next to changed blocks are re-sent unchanged (protocol translation
  noise). One server tick's packets can arrive split across two network batches (by chunk column) or merged with the
  next tick's.
- The repo has a stop hook that asks to commit and push everything: stage paths explicitly (keep `libs/` and build
  output out).
- A force push to this branch was blocked by the session's safety rules; merge instead of rebasing when a branch
  is already pushed.
