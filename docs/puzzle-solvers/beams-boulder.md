# Creeper Beams and Boulder: what the recordings show, and solver designs

Data: 89 Dungeon Recorder recordings (Hypixel, 2026-10-12..16). Scripts and outputs are in this folder (listed at the end).
Coordinates are **room-relative, using Odin's convention**: `real = clay + rot(rel)`, with NORTH (-x,y,-z), WEST (-z,y,x),
SOUTH (x,y,z), EAST (z,y,-x) (`common.py`; this matches Odin's `rotateAroundNorth`). They are checked by the Boulder keys and
the Beams lantern sets, which match across all four rotations. "n" means server ticks (the recorder's `n`).

**How thin the data is.** The recorder **never pushed a Boulder box**: every push here is a teammate's, and we see it only as
block updates. The recorder **was in the Beams room for only one game** (`2026-10-16_22-40-46_Dungeon_8cbe5a`). Sounds and
lantern block changes reach the client only within range, so most other Beams games show only the entity spawns. Nothing in
the data shows a wrong action in either puzzle.

---

## 1. Creeper Beams

### 1.1 How it appears in packets (measured, mainly from 8cbe5a)
- **Room open** (Odin checkmark UNDISCOVERED→NONE, i.e. the first player walks in). In the same tick the server:
  - spawns the **creeper** at rel (15.5,76,15.5) (`add_entity minecraft:creeper`);
  - **pre-links a demo pair** (8,80,8)–(5,73,24): it spawns a guardian, and both lanterns become `prismarine`.
  This pair is not in Odin's table. In all 22 rooms the demo guardian came 0–7 ticks before NONE.
- **A hit on a lantern** registers through either:
  - a left-click: `out player_action START_DESTROY_BLOCK` on the lantern (needs reach; the recorder jumped to y78 to hit (15,84,13));
  - an arrow, which the server detects. Terminator arrows took 4–5 ticks to fly about 10–12 blocks, and the server set a 5-tick `cooldown minecraft:bow`.
- **First lantern selected**, 1 tick after the packet:
  - `sound entity.elder_guardian.hurt` at that lantern, pitch 1.397, volume 0.6;
  - a `block_update` that re-sends `sea_lantern` there (it undoes the client's break prediction).
  - Hitting the selected lantern again just repeats the sound: n132758 and n132780 both hit (4,75,9). We saw no penalty.
- **Partner hit → link**, also 1 tick after the packet. In one tick:
  - `add_entity guardian` at the **2nd** lantern, then `set_entity_data` [17 (attack target) = id-1];
  - id-1 is an `armor_stand` at the **1st** lantern, so the guardian's beam goes from the 2nd lantern to the 1st;
  - sounds: `entity.creeper.hurt` at the creeper, and `entity.elder_guardian.hurt` at the 2nd lantern with pitch 2.0, volume 1.0;
  - both lanterns become `prismarine` (block updates in the same tick or the next one).
  The guardian and armor-stand spawns reach the client from anywhere, so links and both endpoints show up even for remote rooms.
- **Creeper state**:
  - after the 3rd player link: data [17]=true (powered);
  - after the 4th: data [16]=1 (swell) and `entity.creeper.primed`;
  - the creeper explodes 29 ticks later (`generic.explode`), and the server rebuilds the centre with `chest` at (15,69,15). Odin uses that chest as the completion signal, and GREEN arrives 1 tick later.
- **Completion = 4 player links** (5 beams including the demo). This held in all 11 completed games.
- **Wrong pair / reset**: never observed. Odin re-scans whenever prismarine changes back to sea lantern (or the other way), which suggests some reset exists, but we have no data on it.

### 1.2 Fixed vs random
- The creeper position, the demo pair and 9 decorative lanterns are fixed. The decorative ones are (2,71,13), (9,83,17), (15,66,26), (15,67,15), (18,71,2), (21,70,27), (23,66,5), (24,74,6) and (25,72,23).
- Pair endpoints vary per room. We decoded 22 rooms from the raw chunks before the demo link (`beams_layout.py`). The table below lists each pair and how many of the 22 rooms had both ends:

| # | pair (rel) | rooms with both ends | seen linked |
|---|---|---|---|
| 0 | (15,74,15)–(15,84,13), centre vertical | 22/22 | yes, first link in all 11 games |
| 1 | (15,78,3)–(15,76,27) | 12 | yes |
| 2 | (22,80,22)–(4,72,8) | 9 | yes (568bfb, 3bda6c) |
| 3 | (24,77,7)–(5,76,24) | 6 | yes |
| 4 | (27,78,14)–(2,75,16) | 7 | yes |
| 5 | (26,78,12)–(3,76,18) | 10 | yes |
| 6 | (25,79,21)–(4,75,9) | 10 | yes |
| 7 | (25,76,23)–(6,74,5) | 9 | yes (87ccfa) |
| 8 | (18,82,8)–(10,69,27) | 3 | no |
| 9 | (18,81,21)–(9,69,3) | 9 | 2nd end (9,69,3) linked twice |
| 10 | (9,81,20)–(26,70,7) | 10 | yes |
| X | **(10,81,22)–(23,73,4)**, not in Odin | 12 (the two ends always appear together) | never tried |

- Each room has **5 (13 rooms) or 6 (9 rooms) usable pairs**, counting X, and only 4 links are needed. You get a choice.
- **Decoys**, which appear together with a real pair:
  - (7,81,20) appears exactly when (9,81,20) does (10/10 rooms). It sits 2 blocks from the real lantern.
  - (6,70,5) appears exactly when pair 5 does (10/10).
  - (4,72,8) and (25,76,23) are always present, so they are only "lone" when their partner is absent.
- **Geometry does not define pairs.** Pairs 2 and 7 miss the creeper's 0.6×1.7 box by 0.8 blocks and still linked. Several non-pairs come within 0.2–0.6 blocks, for example (8,80,8)–(25,72,23) at 0.19 and X at 0.52. The server must use a fixed table, so a ray-cast solver would be wrong.
- **What is known when the room loads:** the full pair set, from the chunk data. That is before anyone enters: Odin's state was filled 6 ticks before the room opened.

### 1.3 Timing (11 completed games, from `links.py`)
- Room open → first player link: min 16, median 24, max 62 ticks.
- Between player links: min 24, median 34, max 148 ticks.
- 4th link → GREEN: **32–35 ticks, median 33**. This is the creeper fuse plus the chest, it is fixed, and nothing can make it shorter.
- Room open → GREEN: min 140 (3bda6c), median 165, max 391 (c78a60) ticks.
- The server reacts 1 tick after a hit packet or an arrow impact. **We saw no server-side wait or cooldown between links.** The limits are the bow's 5-tick cooldown, arrow flight time and turning.
- Pipelining should work: fire A, flick, fire B while A is still in the air. Hits are processed in arrival order and a pair works in either order. This is assumed, not tested.
- Where time goes in 8cbe5a (224 ticks):
  - link 1 by left-click: 8 ticks from the first hit to the link;
  - link 2: 21 ticks (2 shots);
  - link 3: 98 ticks: a missed shot, then about 2 s spent on a skeleton at the door (n132710→132754), then a duplicate hit on the selected lantern;
  - link 4: 22 ticks.
- Typical good teams do about 25–30 ticks per link, which is 2 shots plus a 180° flick, because the two ends of a pair are on opposite sides of the creeper.

### 1.4 How players solved it
- In all 11 games the first link was pair 0 (the centre vertical).
- The other links were whatever pairs were present. No order repeats between games.
- They were done from the creeper platform (rel y75 around x15–17, z13–15) with a Terminator.

### 1.5 Odin 0.4.0 `BeamsSolver`
- On `RoomEnterEvent` it walks its 13-entry list and keeps each pair whose two blocks are both `SEA_LANTERN` at that moment. It draws each kept pair as two boxes plus an optional line, in a colour cycled from 8.
- It recalculates whenever a block changes between prismarine and sea lantern (in either direction). Completion is air→chest at (15,69,15).
- Where it falls short:
  1. The list has duplicates: pair 9 is listed twice and pair 5 appears in both orders. The same pair is then drawn twice in two different colours (visible in 8cbe5a's `currentLanternPairs`), which makes matching colours confusing.
  2. Pair X is missing (unverified as a real pair).
  3. It shows all 5–6 pairs, without saying which 4 to do or in what order.
  4. It does not track the selected lantern (the select sound). With arrows, the player cannot tell whether the first hit registered or which lantern is half-done.
  5. It scans only once, on entry. If the chunk is not fully loaded then, it shows nothing for the missing lanterns.
  6. It has no fuse or timer cue.

### 1.6 Solver designs
1. **Static table (Odin).** Simple. It has the flaws above.
2. **Geometric ray-through-creeper.** Rejected: it misses 2 real pairs and accepts decoys.
3. **Table plus live state (recommended).**
   - **Scan:** whenever the room's chunk sections arrive, not just on entry, take the pair set from the table (Odin's 11 deduplicated, plus X behind a "test" flag until it is confirmed).
   - **Choose 4 pairs:** take pair 0 first, by left-click (both ends are within reach from the platform, about 4.1 blocks from the eye). Then take the 3 pairs that need the least turning from the platform.
   - **Order the shots** so that consecutive pairs are near each other in yaw: end B of one pair, then end A of the next pair on the same side. That turns 4 flicks of 180° into about 2.
   - **Live tracking:**
     - select sound (elder_guardian.hurt, pitch about 1.4) → "selected X: hit partner Y" with a tracer;
     - guardian + armor stand spawn, or prismarine → pair done, from any player (the arrow's `add_entity` data names the shooter);
     - after the 4th link, show a 33-tick countdown to the chest.
   - **Display:** the next lantern gets a large filled box. Its partner gets an outline. Done pairs are hidden. Decoys (7,81,20) and (6,70,5) get a red X.
   - **Edge cases:**
     - A teammate doing a pair → remove it and re-plan.
     - Lag: we track the server's confirmation, not our own shot.
     - A lantern out of reach or behind a wall → switch to bow aim from the platform.
     - Hitting a decoy or a wrong lantern → selection behaviour unknown; show whatever the server reports.
4. **Aim assist or auto-shoot.** Possible technically (targets are fixed blocks), but it is automation; this note does not recommend it.

### 1.7 More data needed
- Recordings where we hit a wrong lantern, a decoy, pair X and an already-linked lantern.
- Hits made before the room opens.
- Two players selecting at once, to tell whether selection is per player or global.
- Accidental hits from the Terminator's 3-arrow spread.
- More games with the recorder in the room (we have 1) for the selection and sound behaviour.

---

## 2. Boulder

### 2.1 Geometry and packets (measured from decoded chunks plus block updates)
- The playfield is a **7×6 grid of 3×3×3 boxes** (y64–66; the floor is y63):
  - cell centres are rel x ∈ {6,9,…,24} and z ∈ {9,12,…,24}, the same grid as Odin (Odin reads y66 at the centres to build a 42-bit key);
  - boxes are `birch_planks` or `jungle_planks` in a checkerboard pattern;
  - the entry strip is z 5–7, north of row z9;
  - the **chest is at (15,66,29)** in an alcove that opens onto the strip at z26–28, south of row z24.
  - Solved means a walkable path from the entry strip to the z26 strip (flood-fill check in `bfs.py`; it reproduces 1778e4's solved state).
- **Faces.** Each box face that lies in an empty cell has wall signs, with its centre block (y65) being either:
  - a **`stone_button`** if the push target behind the box is empty: 541 of 541 faces;
  - an **`oak_wall_sign`** if the target is a box (193 of 193) or outside the grid (92 of 92).
  So the server's blocks tell you which pushes go into an empty cell.
- **A push** is one `section_blocks_update` (sometimes split across 2–3 in the same tick, 39–129 changes) that does three things:
  - moves all 27 blocks of the box one cell (3 blocks) away from the clicked face;
  - places air where it was;
  - rebuilds every affected sign and button.
  There is no animation, no sound packet and no chat. The button is never seen powered.
- **Pushing into an occupied cell works, and the box already there disappears.** We saw this 4 of 4 times, and it is exactly what Odin's "sign" clicks do:
  - 46ebd4 twice;
  - 012c37, the last push: B(12,21) into J(9,21);
  - 71b575, the last push: J(21,21) into B(18,21).
  The box count drops by 1 each time. All 4 cases were pushes toward −x in room coordinates, and all of Odin's into-box clicks are toward −x. **We have not seen this in the other directions, or for pushes out of the grid.**
- **Completion**: a player opens the chest. `block_event` at the chest (b0=1, b1=1) arrives in the **same tick as GREEN** (34a5cc, n61478). Tab-list puzzle status changes to "Completed" about 10 ticks later.
- **Not explained:** in 34a5cc and 2b53ef, a teammate walked over the grid at y69 with `white_stained_glass` placed at y68 under them at about 1 block per tick. Each block turned into `barrier` exactly 40 ticks later. It looks like an item or ability effect and does not bear on the solver.

### 2.2 Fixed vs random
- **There are exactly 8 layouts, and they are Odin's 8.** All 24 decoded rooms match one Odin key, using "?" for cells whose chunks never loaded. The 24th (1778e4) is layout D captured after its one push.
- Counts: D 5, B 4, A 3, E 3, F 3, C 2, G 2, H 1, all 8 seen. The letters follow the order of Odin's JSON file.
- The layout is in the room's chunk data as soon as it loads (the walls are open), before anyone enters.
- The layout is rotated with the room. Odin's room-relative keys work in all 4 rotations; we checked NORTH, EAST, SOUTH and WEST.

### 2.3 Timing (teammates only; 10 completed rooms, ticks from room open)
- First push: 16–60 ticks.
- Between pushes:
  - same box pushed again from behind (the player walks 3 blocks): 7–8 ticks (71b575: 8, 8, 7, 10);
  - a different box: 5–17 ticks.
- Last push → GREEN (walk to the chest and open it): 18–46 ticks.
- Open → GREEN: 45–100 ticks (2.3–5 s). D and E need 1 push and take about 45 ticks; F needs 5 and took 90.
- We never saw our own click, so **click→push latency is not measured.** For Beams it was 1 tick.
- Whether clicks are rate-limited, whether clicks before the room opens work, and what a click on an out-of-grid sign does are all unknown.

### 2.4 How players solved it
- In every observed sequence (6 rooms) the teammates' pushes were **exactly Odin's solution**, in Odin's order.
- Time goes into: walking into the grid to the face cell (the entry drop, then about 10 blocks), one walk-and-click per push, and the walk back out to the chest.

### 2.5 Odin 0.4.0 `BoulderSolver`
- On room enter it reads (x,66,z) for z 24→9 and x 24→6 to build the 42-bit key, then looks it up in `boulder-solutions.json`.
- Each solution entry is [render x,z, click x,z] at y65.
- It draws the first click, or all of them (an option).
- It removes a step when **the local player's** `UseItemOn` hits that exact click block.
- Where it falls short:
  1. A teammate's push is never removed, so the display goes stale. In every observed game, teammates were the ones pushing.
  2. It removes the step on our click even if no push happened (lag, wrong face, rejected).
  3. It scans only on enter: if any cell is unloaded, the key misses and nothing is shown, silently.
  4. It does not re-plan from a non-standard state.
  5. It is **not push-optimal**. Under our measured rules (`bfs.py`), with into-box pushes toward −x only, which is what we observed:
     - A takes 1 push (Odin 2);
     - F takes 4 (Odin 5);
     - B, C, D, E, G and H tie with Odin.
     If into-box pushes work in every direction (not verified), B takes 3 and F takes 2.
     Odin's F does 4 pushes of one box down column x24 from behind, which is easy to follow but slow.
  6. It shows no standing spot, no path and no chest cue.

### 2.6 Solver designs
1. **Table lookup (Odin).**
2. **Table plus live state tracking.** Read the grid at y65 cell centres on every block update and re-plan with BFS from whatever the state is. This handles teammates, mistakes and a partially loaded room once it fills in.
3. **Time-optimal planner (recommended, combined with 2).**
   - Search over (grid, player cell). Cost = walking time to a spot within 4.5 blocks of the face's centre block with line of sight, plus click time, plus the final walk to the chest.
   - Precompute the plan offline for the 8 layouts and verify each one in game. Use BFS only for recovery.
   - Prefer button faces. Use sign faces (into-box) only toward −x until the other directions are verified.
   - **Display:**
     - before entry: a layout name and a "N pushes" line;
     - after entry: the next face block filled, the standing spot as a floor marker, the walking path as a line, and the next step as a ghost;
     - once the path is open: the chest highlighted with a tracer.
   - **Edge cases:**
     - A teammate's push → state changes → re-plan.
     - Our click with no push within about 3 ticks → keep the step and flag "not registered".
     - Unloaded cells → wait for full data, then match with "?" as a wildcard (any 7–12 known cells already identified the layout uniquely in our data).
     - Teleporting is blocked in the room (the repo's `Zpew` `NO_TELEPORT`).
4. **Auto-click when in reach.** Possible, but it is automation; this note does not recommend it.

### 2.7 More data needed
- **Recordings where we push**, to measure:
  - click→push latency and any rate limit;
  - a sign click into a box in each of the 4 directions;
  - a sign click toward the outside of the grid;
  - clicking from the maximum reach;
  - clicks before the room opens;
  - two players clicking in the same tick.
- Our own runs of the shorter A, B and F plans, to confirm them in game.

---

## Scripts and outputs (this folder)
- `common.py`: rotation and room helpers.
- `dump.py REC ROOM`: room timeline.
- `boulder.py`: layouts, keys and pushes → `boulder_rooms.json`, `boulder_out.txt`, `layout_*.json`.
- `pushes.py`, `snap.py`: push-by-push grid snapshots.
- `faces.py`: the button/sign rule.
- `bfs.py`: optimal pushes under each move rule.
- `beams_scan.py`, `beams_ev.py`: Beams events → `beams_events.json`, `beams_ev.txt`.
- `beams_layout.py`: lantern sets (`beams_*.json`).
- `geom.py`: the ray-cast check.
- `links.py`: link timing and pairs → `beams_links.json`.
