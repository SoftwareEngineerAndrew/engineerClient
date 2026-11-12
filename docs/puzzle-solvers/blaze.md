# Higher or Lower (Higher Blaze / Lower Blaze): design note

Data: 89 recordings (Oct 12-16 2026, mc 26.1.2, recorded client ran **Odin 0.3.4**; the repo builds against Odin 0.4.0).
Scripts and outputs are all in this folder: `scan.py` (one event list per recording, in `scans/`), `games.py` → `games.json`,
`blocks.py` → `blk_*.txt`, `chest.py`, `aim.py` → `aim_all.txt`, `dmg.py`, `tl.py`.
All times are **server ticks `n`** (20/s), unless marked otherwise.

## 0. How much data there is (thin)

| | count | recordings |
|---|---|---|
| recordings with a Blaze room on the map | 51 | `puzzles_scan.jsonl` |
| rooms activated with the blazes in this client's range | **10** (8 Lower, 2 Higher) | |
| complete solves seen kill by kill | **5, all Lower** | a18b44, 63aa70, 457232, cce77b, 56b8d7 (the retry) |
| wrong kills (fails) | **2** | 9dc4de (no retry seen), 56b8d7 (retried and solved) |
| Higher Blaze | 1 partial (last 4 kills seen: 6436e9), 1 fly-by (ead6cf, 8 blazes, no kills) | |
| activated, then the team left for boss | 2 | aa7022, 1c3411 |

4 of the 5 complete solves are by the same player (the recorder, using a Terminator). Higher Blaze is under-sampled.
Everything below marked **measured** comes from these games. Everything else is marked **assumed**.

## 1. What the packets show

**Room** (measured, same in every game): a 1x1 room. The centre column is at (x0+15, z0+15), where x0 = -200+32·tx.
- **Lower**: blazes at y = 27, 31, …, 63 (10 levels, 4 blocks apart). The player enters on the floor at y≈69.
- **Higher**: blazes at y = 77, 81, …, 113. The player enters at y≈70.
- Each level holds one blaze, on one of the 4 cardinal offsets (±2, 0) / (0, ±2) from the column. Rotation does not matter.
- Blazes do not move: only a few `entity_position_sync` / `move_entity_pos` packets arrive for them.

**Activation** (measured): nothing exists until the **first player enters the room**. 8-16 ticks after that player enters
(8, 9, 10 and 16 ticks seen), the server sends in **one tick**:
- 10 × `add_entity type=minecraft:blaze`, each followed by an `add_entity minecraft:armor_stand` nametag. The tag's id is the blaze's id **+1**; it sits at the blaze's y+2.
- a `block_update` that puts a **chest** in the column at the top of the travel: Lower (x0+15, 69, z0+15), Higher y=70 (Higher also gets iron bars at y=119).

Entity ids are fresh on every activation, and fresh again on a reset.

**Where the health is** (measured, 100 % agreement over 92 blazes):
- the blaze's own `set_entity_data` index **9** (DATA_HEALTH, float);
- `update_attributes` `minecraft:max_health` `base`;
- the tag's custom name (index 2): `"[Lv11]  Blaze 3,522/3,522❤"`. The text after `]` is ``, a private-use icon glyph, not a space.

In the spawn bundle both health values are a placeholder **20.0**. The real values arrive **1 tick later** (n+1), for all 10 at once.
**So the whole solution is known 1 tick after activation.**

Hypixel often sends the bundle twice in the same tick, with the same ids. The client then logs `egone DISCARDED found:false` and re-adds the entity. Expect duplicate adds.

**Kill** (measured): every blaze died to one hit. At ~200k-8.7M damage against 1,002-5,040 HP, partial health updates were never seen. In one server tick the client gets `hurt_animation` → `entity_event eventId=3` → `set_entity_data [9]=0.0, [6]=DYING`.
`remove_entities [blaze, tag]` follows **2 ticks later**.

**Fail** (measured, 2 cases): `system_chat "PUZZLE FAIL! <name> killed a Blaze in the wrong order! Yikes!"` arrives in the **same tick** as the wrong `entity_event 3`.
- +2 ticks: every remaining blaze is removed, and the progress chest stops.
- Odin's map check turns RED.
- 56b8d7: **+20 ticks after the wrong kill, a new set of 10 blazes spawns with the same positions and the same HPs**. The ids are new, and the chest goes back to the top.
- 9dc4de: no respawn was seen. The killer was a teammate, the recorder was outside the room, and the team left for boss. The respawn condition is therefore unknown (maybe a player has to be in the room).

**Success**: the server sends **no "PUZZLE SOLVED" chat** for this puzzle. The "Blaze puzzle solved!" seen in party chat is Odin's own auto-message. The real completion is the **progress chest** reaching the end of its travel (next section). In 5/5 games the room's map check went GREEN/WHITE 0-6 ticks after the chest's last step.

**Order** (measured): **Lower = kill the HIGHEST HP first (descending). Higher = LOWEST first (ascending).**
- Lower: 5 clean solves are descending, and both "PUZZLE FAIL" kills broke descending order.
- Higher: 6436e9 is ascending and went GREEN.
- This matches Odin's `sort()` (Lower → `sortByDescending(maxHealth)`). **The brief's wording ("ascending in Lower") is backwards.**

**HP** (measured): Lv11 blazes, 1,002-5,040 HP, every value distinct within a room. The smallest gaps were **1** (1478/1479, aa7022), 8 and 10 HP. HP is not related to height (random per level).
**Assumed**: HP and placement are random per activation, except that a reset keeps the layout (56b8d7).

## 2. The server-timed part: the progress chest (the key finding)

After the first kill, the chest moves **one block every 5 ticks**, on a fixed 5-tick clock: it starts 2-5 ticks after the first kill. It leaves iron bars behind in Lower and air in Higher.
- Lower: from 69 down to 20.
- Higher: from 70 up to 119.

The travel is always **49 blocks**. The chest's **depth is capped at 5 × kills**. It waits when the players are behind: 56b8d7's retry stalled 19 ticks at depth 5, because the second kill came at +35.

Checked in 7 runs (`chest.py`): no step ever went past 5 × kills, and the minimum slack was 0.

| run | activation → 1st kill | 1st kill → 10th kill | 1st kill → chest end (completion) |
|---|---|---|---|
| cce77b | 34 | 157 | 242 |
| a18b44 | 30 | 95 | 245 |
| 63aa70 | 28 | 111 | 243 |
| 457232 | 32 | 95 | 243 |
| 56b8d7 retry | 14 (from respawn) | 125 | 256 (one stall) |

Consequences:
- **Completion = first kill + ~243 ticks (12.2 s), whatever you do after it**, as long as kill k lands before the chest needs it: about first kill + 25·(k−1) + 5 ticks, i.e. one kill per 25 ticks on average.
- Every observed team was 2-3× faster than the chest needs. After the first kill, killing faster only frees the *player*: the chest runs on its own, so they can leave after the 10th kill (a18b44's solver was not even near the room).
- The only levers on completion time are **an earlier first kill** and **no stall / no fail**.
- A fail cost 56b8d7 **~72 ticks (3.6 s)**: the reset is fast, the chest restarts, and the HPs are identical the second time. On the map the room went RED then GREEN. **Assumed**: a retried puzzle counts as completed for score.

## 3. Timing, pre-firing and what is ignored

- Shots before activation hit nothing: the blazes do not exist yet. No evidence of a spawn invulnerability window. The first Terminator shot in each game killed: 30 ticks after activation in cce77b. The ~30-tick first-kill delay is the player (teleport in, swap to the bow, find the target), not the server.
- Flight time from `use_item` to `entity_event 3` (Terminator, measured): **2 ticks at 4 blocks, ~4 at 10, 5-6 at 15, 7 at 22.**
- **The players never pre-fired**: the next shot always went out ≥2 ticks after the previous death. Per kill (36 kills): the median gap was 9.5 ticks for turns <40°, 12.5 for 40-90° and 17 for >90°. There was one shot per kill (no misses).
- Pre-firing is possible in principle, because kills are resolved server-side in arrival order. But it is **dangerous**: shooting k+1 before k lands fails if k+1's arrow arrives first, e.g. a near target after a far one. A safe pre-fire needs ETA(k) < ETA(k+1) by a ≥2-tick margin. It saves about one flight time (~4 ticks) per kill, i.e. ~1.8 s of player time. It does **not** change completion, which is chest-bound.
- Both fails were **wrong-target aims, not misreads**. In 56b8d7 Odin's list showed the right next target (3995). The shot went at pitch +5° into the 2532 blaze 4 blocks away, instead of the 3995 one 8.5 blocks away and below.

## 4. Odin

**0.3.4** (what was recorded):
- `odin.solver` shows `blazes:[{eid: <tag id>}…]`, `roomType`, `lastBlazeCount`. The list is correct and sorted, and it drops an entry ~2 ticks after the kill, when the tag is removed.
- At the respawn it flickered 10→9→10 because of the duplicate adds.
- It sent "Blaze puzzle solved!" 3 ticks after the 10th kill, which is ~4.3-7.5 s *before* the room actually completes.

**0.4.0** (`libs/odin`, from bytecode):
- Tracks only **ArmorStand nametags** matching `^\[Lv+\d+]  Blaze [\d,]+/([\d,]+)❤$`, using the max health from the name. It adds them on `SetData` and sorts on add or room enter.
- It removes a tag when that ArmorStand is removed.
- Draws boxes (first, second and third colours, then "all") and lines to the next N.

Shortcomings:
1. **Nametags arrive less reliably than the blazes.** 0/4, 0/8, 5/10 and 7/10 tags were received in 4 of the 10 activations (each time the recorder was outside or at the edge of the room). The blazes with their HP were present every time.
   A missing tag means a missing entry, so the solver **highlights the wrong "next" with confidence**.
   With the player inside the room, all 10 tags arrived in every game.
2. `onEntityDeath` compares the tag's (ArmorStand) id with the Blaze's id. They always differ by 1, so `removeAll` never matches, and the completion branch never runs: no `PuzzleStatus.Completed`, no timer, no "pc Blaze puzzle solved!".
   This is read from bytecode and has not been tested at runtime. List entries still disappear via `onEntityRemove`, 2 ticks late.
3. Renders only while `WorldScan.currentRoom` is the blaze room, so nothing shows for someone shooting in from the doorway.
4. No progress or chest information, no fail or reset handling beyond the removes/adds, and no guard against a wrong shot or attack.

## 5. Solver designs

| # | design | verdict |
|---|---|---|
| A | Parse the nametags (Odin 0.4.0) | Fragile: tags are missing at range. Keep it only as a cross-check. |
| B | **Entity data**: blaze `max_health` attribute (or DATA_HEALTH 9 > 20), keyed by level y (27+4i / 77+4i) and UUID; next = extreme of the *alive* set; advance on `entity_event 3` (or health 0 / DYING) | Core. Known 1 tick after activation, and needs no history: whatever is still alive defines what comes next. |
| C | B + **completeness check**: expect 10 levels. A level is dead if its death event was seen, or if the chest depth shows kills ≥ ⌈depth/5⌉. The ids come in consecutive (blaze, tag) pairs, so a gap in the run of ids marks blazes that are dead or not tracked. Unknown levels → show "?", never a confident wrong next | Needed for late arrival, out-of-range blazes and teammates. |
| D | **Shot/attack guard**: cancel a melee `interact` ATTACK on a non-next blaze, and cancel `use_item` when the crosshair ray (or the 3-arrow Terminator spread, or a Hyperion/AOE radius) reaches a wrong blaze first. Also block a shot while an earlier arrow at the current target is still in flight and would land later | Would have prevented both observed fails. Client-only and toggleable. Arrow simulation needs care. |
| E | **Pre-fire scheduler**: ETA model from distance; allow the next shot once ETA ordering is safe | Saves ~2 s of player time and nothing on completion. Make it opt-in, used with D. |
| F | **Aim plan**: positions are known at activation, so show the order with turn angles and suggest a standing spot (mid-column ledge) that minimises big turns. Gaps were ~17 ticks for turns >90° vs ~10 for <40° | Cheap HUD addition. |
| G | **Chest-progress timer**: `blk` chest y → depth, kills, ETA = (49 − depth)·5 ticks; "you may leave" once 10 kills are done; stall warning if kills < ⌈(depth+1)/5⌉ | Shows the real completion time instead of Odin's early "solved". |
| X | Auto-aim / auto-shoot | Excluded (cheating / ban risk). |

**Recommended: B + C + G for display, D on by default (guard), E off by default.**
- **Track**: on `add_entity blaze` inside the room box, record UUID, id, pos and level. Take HP from `update_attributes max_health` or `set_entity_data[9]`, ignoring 20.0. Mark dead on `entity_event 3`, the same tick as the death (not on the remove 2 ticks later). Survive remove/re-add by keying on level and UUID.
- **Reset**: on `PUZZLE FAIL` chat or a mass remove, clear the alive set, then re-seed the expected layout from the previous HPs. The respawn has the same HP per position, so the display can come back before the new entities do.
- **Display**:
  - at activation +1 tick: box the next target; the 2nd and 3rd in fading colours, each with its HP number; a line to the next two;
  - every other blaze dim; the wrong ones red outlines when aimed at;
  - a HUD line `Lower 3/10 · next 3,995 · chest 15/49 · done in 8.5 s`;
  - "?" on any level that is not tracked.
- **Edge cases**:
  - teammates: use global death events, never only your own attacks;
  - lag: the chest and death events are server-tick facts, show times in ticks;
  - entities out of range: C;
  - duplicate adds: dedupe by id/UUID;
  - Higher vs Lower from Odin's room name; if the room is unknown, infer from blaze y (≥77 = Higher).

## 6. What more data would help

- **More Higher Blaze solves** (only 4 kills seen) to confirm ascending order and the identical chest rule.
- Fails with the solver in the room and away, to learn whether a respawn always happens and what triggers it. Also the dungeon score effect of a failed-then-retried puzzle.
- A non-one-shot player (low damage) to see whether DATA_HEALTH, the nametag and the ordering use current or max HP.
- Two blazes killed in the same tick (AOE/Hyperion, piercing arrows) to learn how the server orders simultaneous kills.
- More players than one Terminator user, for timing variance. Also arrow ETA versus distance for other bows, to calibrate D and E.
- Unexplained: a second chest appears in a room corner 13-35 ticks after the 10th kill (cce77b (-101,68,-38), 457232, 6436e9). Probably the reward/secret chest; unverified.
