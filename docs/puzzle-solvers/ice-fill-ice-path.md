# Ice Fill and Ice Path: what the recordings show, and how to build the solvers

Data: 89 Dungeon Recorder recordings (Hypixel, 12-16 Oct 2026). Scripts and outputs are in
`/tmp/claude-0/-home-user-engineerClient/cab32928-feda-5927-beae-797dda127934/scratchpad/puzzles/ice/`:
- `extract.py`: per room, the lines that matter, written to `ex/<rec>.<room>.jsonl`.
- `blocks/*.json`: room blocks rebuilt from the raw chunk frames by `wb/rawchunks.py`.
- Ice Fill analysis: `variants.py`, `grids.py`, `layouts.py`, `minturn.py`, `fillev.py`, `attrib.py`, `resets.py`, `steps.py`, `timing.py`.
- Ice Path analysis: `pathgrid.py`, `sf.py`, `sfhits.py`, `pathsolve.py`.

**Coordinates.** "rel" means Odin's room frame, built from the room line's `clay` corner (cx, cz) and its `rot`:

| rot | world = |
|---|---|
| SOUTH | (cx+x, cz+z) |
| NORTH | (cx−x, cz−z) |
| EAST | (cx+z, cz−x) |
| WEST | (cx−z, cz+x) |

I checked this against Odin's rendered `currentPatterns` in rooms f6fed7 (EAST) and ead6cf (NORTH). Room ids below are the last 6 hex digits of the recording name.

**"Measured" vs "assumed".** "Measured" means seen in packets. "Inferred" or "assumed" is marked as such.

---

## 1. Ice Fill

### 1.1 How it appears in packets (measured)

**Room layout.** There are three floors of ice blocks.

| floor | ice y (world) | stand y | rel x | rel z | start cell | exit cell |
|---|---|---|---|---|---|---|
| f0 | 69 | 70 | 14..16 | 7..9 (3x3) | (15,7) | (15,10) |
| f1 | 70 | 71 | 13..17 | 12..16 (5x5) | (15,12) | (15,17) |
| f2 | 71 | 72 | 12..18 | 19..25 (7x7) | (15,19) | (15,26) |

- Obstacles are `polished_andesite` one block above an ice cell.
- The exit cell is ice and counts as a cell.
- Odin's rel y (70/71/72) is the standing level, one above the ice.

**Puzzle start.** The first time anyone enters, the server places 2 `chest` blocks at the far end, rel (14|16, 29). The tab shows `ICE_FILL Incomplete`.

**A step.**
- It arrives as `block_update` `ice → packed_ice`, preceded by `level_event` 2001 (block break particles, `state` = the white_wool sound/particle) at the same position.
- For the recorder's own steps, the update arrives **2 server ticks / 66-94 ms after the `move_player_pos` that first put us over the block**. That is about one round trip, so the server marks the block when it processes that movement packet.
- For teammates, the `block_update` comes in the same packet batch as their `move_entity_*` (dt 0).
- `onGround` is not required. Etherwarp and AOTV teleports (`player_position` with `ender_dragon.hurt` or `enderman.teleport`) onto a cell registered it (f6fed7, n 51276…).
- **Only the active floor registers.** While f1 was incomplete, standing on f2 cells did nothing (f6fed7 n 51368-51422).

**Floor complete.**
- When every cell of a floor, exit included, is packed, `note_block.harp` plays 3 times (pitch 1.19, 1.29, 1.40, about 4 ticks apart) and the next floor becomes active.
- Order does not matter. In ead6cf the last cell was (14,22), not the exit.
- Stepping on the exit with cells missing does nothing (f6fed7 first f1 attempt: 21/22 cells, no completion).

**Puzzle complete.**
- In the same tick as the last f2 cell: 30× `iron_bars → barrier` (exit gate, 5 wide × 6 tall).
- `barrier → air` follows 20-26 ticks later. Chests open (`chest.open` sounds).
- Odin's room turns GREEN 13-40 ticks after the bars.

**Fail.**
- The active floor collapses: every ice/packed block of that floor and its andesite obstacles go to air in one tick. 4-5 `falling_block` entities spawn and the player falls to the stone at y≈66.
- The floor comes back as fresh ice **exactly 60 ticks (3.0 s) later**. This was measured on all 13 collapses.
- Other floors are untouched. There is no global reset.
- The only chat seen is `Don't move diagonally! Bad!`, sent only to the player who caused the fail. Teammates' fails are silent.

**Diagonal fail.** Confirmed twice:
- Our own case (f6fed7 n 51428): a walking movement packet changed both block x and block z, (14,19)→(15,18). The collapse came 3 ticks later.
- A teammate's case (54a1b0 n 24034): (15,8)→(14,9) in the server's broadcast of their position, then collapse in the same tick.
- Diagonal **teleports did not fail**: f6fed7 n 51335 (15,12)→(14,13) by AOTV on the active floor, and n 51420.
- A slight overlap back onto an already packed cell (f6fed7 n 51333, 0.27 block back onto (15,12)) did **not** fail.

**Unknown.** The causes of the other 11 collapses were not observable. Teammate positions are sparse and their chat is not sent to us. Candidates: diagonal, stepping a packed cell fully, leaving the floor mid-attempt.

### 1.2 Fixed vs random (measured)

- Each floor's layout is drawn from Odin's set. In 18 rooms:
  - f0: 3 of 4 layouts seen
  - f1: 6 of 6
  - f2: 5 of 5
- The three floors look independent. Odin's file implies 120 combinations.
- No layout outside Odin's file was seen.
- The layout is in the chunk data **before anyone enters**. I read all 18 rooms from chunk packets, 11 of them never entered by the recorder. So a route can be computed at chunk load.
- Shortest start→exit routes (Hamiltonian paths covering every cell) per layout:

| floor | routes per layout |
|---|---|
| f0 | 1 |
| f1 | 1 or 4 |
| f2 | 2, 4, 18, 38 or 565 |

  Where there is a choice, the path matters for speed (1.3).

### 1.3 Timing and movement (measured over 9 rooms with step data)

- **Time per cell:**

| move | mean | median | n |
|---|---|---|---|
| straight-on | 2.9 ticks | 3 | 163 |
| after a turn | 4.7 ticks | 4 | 233 |

  A turn costs about 1.8 ticks, so the best route is the one with the fewest turns.
- **Fastest steps:** 1 tick between adjacent cells, seen 20 times.
- **Skipped cells:** none in any successful run. Every consecutive step by one player was 4-adjacent.
- **Unresolved:** whether the server fills in blocks crossed between two packets. The data has no case of a player moving more than one block between packets on the active floor. Assume it does **not**: a packet that skips a block leaves a hole, and a packet that crosses a corner is a diagonal fail.
- **Clean floor times:**

| floor | ticks | per cell |
|---|---|---|
| f0 (8 cells) | 22-51 | 2.8-6.4 |
| f1 (18-24) | 74-122 | 3.4-6.4 |
| f2 (40-46) | 132-250 | 3-6 |

  Best clean total is about 230 ticks, roughly 12 s of stepping.
- **Whole puzzle**, first step → bars: 15.7 s (56b8d7), 29.2, 32.8, 35.6, 46.7, 47.3 s.
- **A mistake costs** the 3.0 s rebuild, plus walking back to the floor start, plus redoing the floor. Typically 6-10 s on f2.
- **Several players can fill one floor at once.** ead6cf f2: two players' steps interleaved over 42 cells, about 133 ticks. That is a real speed-up.

### 1.4 How it was played (measured)

- 18 Ice Fill rooms. Step data for 9:
  - 6 completions with gate data: f6fed7, 54a1b0, be773f, 0bb1fe, 56b8d7, ead6cf.
  - 63aa70 went GREEN but was out of range.
  - 735dd9 and 6436e9 are partial.
- The recorder itself stepped only in f6fed7: f0 plus a failed f1, about 28 steps. Most data are teammates, seen only when in range.
- Out of range, many teammate `block_update`s simply never arrived (0bb1fe, 56b8d7).
- 13 floor collapses over 5 rooms, 0-4 per room. Most time is lost to resets, then to turns.
- Players also entered floors from the side, e.g. f1 from (13,12) or f2 from (17,21), walking around on the andesite rim. Entry point is free.

### 1.5 Odin 0.4.0 (`IceFillSolver`, `ice-fill-floors.json`)

**What it does.** `onRoomEnter` runs once, only while `currentPatterns` is empty. For each floor it picks the first variant whose `identifier[0]` is air and `identifier[1]` is not. It concatenates the 3 waypoint lists, easy or ("Use Optimized Patterns") hard, and draws one static line. "Reset Ice Fill" clears it.

**Shortfalls:**
- **Order-dependent identification.**
  - f0 always matches 2 variants ([0,2] or [1,3]) and f1 sometimes ([2,3]).
  - It is right only because the first match happens to be the correct one.
  - Unloaded chunks read as air, so it gives a wrong or "failed" variant, with no retry until manual reset.
- **No progress.** It does not hide done cells and has no idea of the active floor. After a collapse it still shows the whole line, with no "wait 3 s" and no indication of where you are in the route.
- **Not min-turn.** On f2, easy paths have 26-27 turns against a minimum of 19-25. The hard paths reach 19 on two layouts by entering from the far corner over the rim, but 29 on another. That is up to about 13 ticks lost per floor.
- **No diagonal-corner warning, no teammate split, no lag handling.**

### 1.6 Solver designs

1. **Static line from a table** (Odin). Cheap, but has the problems above.
2. **Table + live state.**
   - Read each floor from the world when its chunks are present, or on `block_update`.
   - Pick the minimum-turn path from a precomputed table, or by DFS live: under 1 ms for 565 paths.
   - Track packed cells from `block_update` plus our own position.
   - Detect collapse (floor blocks → air) and show a 3 s countdown to the rebuild (60 ticks after the air update).
3. **Live solver from the current state.** Recompute the minimum-turn Hamiltonian path from the player's current cell over the remaining ice. This covers a teammate who started elsewhere or a side entry.
4. **Team split.**
   - Partition the remaining cells into two snake paths, one per player, minimising the longer one's time (2.9 t/cell + 4.7 t/turn).
   - Precompute per layout. The data shows this works server-side (ead6cf).
5. **Movement assist (risky).** Hold the player to cell centres at turns, or delay a packet so one packet never changes both block x and z. This is the observed fail trigger. Editing movement packets on Hypixel is an anticheat risk. Prefer a visual warning: show the next turn and a "stop" marker when speed > ~0.3 b/t into a turn.

**Recommended: (2) + (3) with an optional (4).**

What to show, and when:
- **As soon as the chunks load:** the minimum-turn route for every floor, tie-broken by fewer turns near the end.
- **On the active floor only:**
  - next 3-5 cells highlighted
  - done cells greyed (our own position immediately; reconcile with server `packed_ice` one RTT later)
  - turns marked
- **On collapse:** a "rebuilding 3.0 s" timer, and the route restarted from the start cell or from where the player stands.

Edge cases:
- **Teammates:** read their packed cells from `block_update`. Do not assume their position.
- **Partially loaded room or missing updates:** re-read blocks every few ticks rather than relying on events.
- **Lag:** progress is server-timed. Our blocks turn packed after one RTT; do not show a cell as "missed" before about 2 RTT.

Store the identifier logic as an exact match on all obstacles, not two probe blocks.

### 1.7 More data needed

- **The recorder solving Ice Fill itself** with full packets, fast (speed > 400) and with deliberate tests:
  - a diagonal corner at speed
  - stepping back fully onto a packed cell
  - leaving the floor mid-attempt
  - a 2-block skip in one packet (jump or very high speed)
  - etherwarp over cells
- That would settle the fail rules and whether the server checks crossed blocks.
- **Teammate chat** for their collapses (party messages).
- **The missing f0 layout.** Odin variant 2 is not seen; it is identical in identifiers.

---

## 2. Ice Path

### 2.1 How it appears in packets (measured, rooms c78a60 and 74c29a)

**Board.**
- 17×17 at rel x 7..23, z 8..24. The floor is `packed_ice` at y66; the silverfish walks at y67.
- Obstacles are blocks at y67 (16 inside the board plus walls).
- The goal is a 3-wide gap at rel x 14..16, z 25, closed by a 4-high `ice` wall (y68-71).

**Puzzle start.** At first entry: one `chest` at rel (15,28) and `add_entity minecraft:silverfish` at rel block (8,9), world (x.5, 67.0, z.5).

**A hit.**
- Hits came from right-click: `interact` (MAIN_HAND + OFF_HAND interact_at), or `use_item_on` on the floor next to it together with `use_item`. The player stood within about 0.3 block of the silverfish.
- **Push direction = the player's yaw snapped to the nearest cardinal direction**, not their position relative to the silverfish. All 10 hits in 74c29a agree, e.g. yaw 125 → −x, yaw 162 → −z.
- Inferred: the server only checks the click and a short range; the exact range is unknown.
- Each click burst of 3-4 interact events gives one accepted hit and 3-4× `You cannot hit the silverfish while it's moving!`. The duplicates are rejected because the first one already started the move.

**Slide.**
- Sent as `move_entity_pos` **every 3 ticks**, which is the entity tracker interval.
- About 1.19-1.22 blocks per update, i.e. **0.40 blocks/tick (8 b/s) constant**. The first update is partial (0.4-0.8 blocks), and it stops exactly at the cell centre (x.50). There are no stop packets, no `set_entity_motion` for the slide, and nothing in `set_entity_data`.
- An n-block slide takes about 2.5n ticks.

**Completion.**
- The silverfish slides through the gap.
- About 3 ticks after the last move, the 12 gate `ice` blocks go to air and the silverfish is removed (`remove_entities`).
- Odin GREEN follows about 8 ticks later.

**Mistakes.** There is no fail and no reset. A wrong hit only costs time. From every reachable stop cell (54 of them) the goal is still reachable.

### 2.2 Fixed vs random (measured)

- **All 21 Ice Path rooms have the identical board**: same 16 obstacles, read from chunk data before entry.
- The silverfish start is identical in both rooms where it was seen.
- So the solution is a constant: **10 hits, unique** (BFS: exactly one 10-hit route). In rel frame from (8,9):

  +x4, −z1, +x8, +z16, −x1, −z1, +x2, −z9, −x7, +z11 (out through the gap)

- Both recorded solves (a teammate in c78a60, the recorder in 74c29a) used exactly this route. It is about 60 blocks of sliding, roughly 150 ticks of motion.
- It is known at room load. Only the silverfish's live position is needed to know which step you are on.

### 2.3 Timing (measured)

- **Hit to first move packet:** 178-349 ms (one RTT plus up to 3 ticks of tracker granularity).
- **A hit while moving is rejected and lost**, so pre-firing does not work. The earliest accepted hit came 1 tick after the last move packet (74c29a n 32278 → 32279). The server accepts as soon as it is at rest.
- **Idle between slides:** 3-15 ticks in the move-packet quantisation; total idle 75 ticks of about 205.
- **Totals**, first hit → gate: 208 ticks (10.4 s, 74c29a); about 190 ticks (c78a60).
- **Floor:** 150 ticks of sliding + 10 × (RTT + 1-3 ticks), about 8.5 s. So about 2 s is lost to walking around the silverfish and turning.

### 2.4 Games available

- 21 rooms seen, 4 completed.
- Slide data for only 2 solves (c78a60, 74c29a). 457232 and 6436e9 were completed out of tracking range.
- **Thin data. The fixed layout rests on 21 boards, but only 2 starts.**

### 2.5 Odin

There is no Ice Path solver in Odin 0.4.0, and nothing in the repo.

### 2.6 Solver designs

1. **Static arrow list.** Show the 10 moves as text or arrows on the board.
2. **Live step tracker.**
   - Find the silverfish (add_entity, type silverfish, in the room box).
   - Map its rest cell to the step index of the fixed route; if it is off-route, BFS the remaining route from that cell.
   - Show:
     - the next push direction
     - **where to stand and which way to face**: the yaw that snaps to the needed direction, on the side opposite the push
     - the cell where it will stop
3. **Look-ahead positioning.** While it slides, show the stop cell and the stand spot for the *following* hit, so the player is already in place. Show a "now" cue when the slide ends, i.e. 3 ticks with no move packet after reaching a cell centre, or the predicted arrival tick (distance/0.4).

**Recommended: (2) + (3).**
- Precompute the route, with a BFS fallback for any board or start that differs. Re-check the board from chunk data when the room loads, so an unseen variant is not missed.
- Warn against spam-clicking: duplicates are harmless, but each "moving" click is wasted.
- **Do not pre-fire.**
- With lag, aim for the click to arrive just after the stop: predicted stop tick minus RTT/2 at the earliest. A click that lands early is rejected and must be repeated.
- **Teammates:** anyone can push it, so track it by entity position, not by our own clicks.

### 2.7 More data needed

- More full solves with the recorder close by, to confirm:
  - start-position invariance
  - the hit range from the player
  - whether left-click works
  - whether the player body blocks the slide
  - the exact earliest-accept tick, from a test of clicking at chosen offsets after the stop
