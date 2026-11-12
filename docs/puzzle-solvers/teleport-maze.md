# Teleport Maze: design note

Scripts and intermediate output are in this folder: `extract.py` pulls a recording's maze window, `tps.py` and `track.py` read
teleports and tracks in Odin's room frame, `facing.py` and `ores.py` compare room blocks, `odinsim.py` replays Odin's logic,
`sim.py` and `simteam.py` run strategy Monte Carlo, and `x/*.jsonl` and `b*.json` hold the extracted lines and decoded blocks.
Pad names follow the repo (`MazePads.label`): cells 1-7 around the U, then N or S and W or E.

## 0. How much data there is (thin)

- The maze appears in 22 of the 89 recordings. Odin marks it GREEN in 10 of them.
- **In none of them did the recorder's own player enter the maze.** There are 0 `me` ticks in the box and 0 own
  `player_position` teleports there. Anything about our own teleport packet (relative flags, `accept_teleportation` timing,
  setbacks) is therefore **not measured**.
- **2 recordings show a teammate solving it in tracking range.** Everything measured below comes from these two:
  - `2026-10-12_20-05-34_Dungeon_672ef8` (maze 1, EAST): 11 teleports.
  - `2026-10-13_20-27-21_F7_568bfb` (maze 2, SOUTH): 6 teleports.
  - In the other 20 recordings the maze was solved out of view (no entity rows in the box) or never done.
- The repo's own `tools/tpmaze/README.md` reports a much bigger public-ADR study: 2,185 mazes and 4,604 teleports. I cannot
  rerun it here. My 2 mazes agree with every rule it states that they can test (section 2).

## 1. How it looks in packets

**Room.** 30 `end_portal_frame` blocks at y 69 (`eye=false` on all of them). That is 28 pads in 7 cells of 4, plus the START pad
in the entrance corridor and the END pad. Floor slabs top out at y 69.5.
- Frame `facing` is identical per pad index across all 3 decoded mazes. It is part of the schematic, not a wiring hint.
- Apart from the door edges and the chest, the 3 rooms are block-identical at y 66-75.
- The redstone ore under cell 1 changes `lit` when someone walks on it. It means nothing.
- `rawchunks.py` was used for blocks, because the JSON `rle` is broken.

**Room activation.** When the first player walks in, one `section_blocks_update` places iron bars and a chest at relative
(15, 70, 20) in the END area (maze 1 at n 24894, maze 2 at n 50803). An armor stand (the chest's label) spawns at the same spot,
and Odin fires `CheckmarkUpdateEvent NONE`.

**A pad teleport, seen for a teammate.** It arrives as `minecraft:entity_position_sync`, which 15 of 15 teammate pad teleports
used. It carries:
- the exact position `(X.5, 69.5, Z.5)`: the middle of the landing cell, which is the cell diagonally next to the partner
  pad's frame, 1.41 blocks from it (13 of 13 landings);
- `yRot` set by the server, and `xRot` 0.

`teleport_entity` was never used. Ordinary movement arrives as `move_entity_pos` / `move_entity_pos_rot`, about every 2 server
ticks.

**The landing yaw names the final pad.** In all 13 landings the yaw points at the middle of the final pad's landing cell with
0.000 degrees error. This matches the repo's finding.
- In maze 1, the first landing (from START) already pins it alone (5SE).
- In maze 2, the first landing narrows it to {2SW, 2SE} and the second leaves 2SW. Yaws of 0, 90, 180 or -90 line up with 3-5
  pads.

**Final pad, END and success.**
- The final pad drops you on the END pad's landing cell (16.5, 69.5, 15.5). The yaw there aims at no pad.
- The END pad sends you back to the corridor at (15 or 16, 69.0, 8) with yaw 180. That spot is not on the .5 grid.
- **Success means opening the chest.** A `block_event` (b0=1, b1=1) on the chest comes 9 ticks after the END landing in maze 2
  and 26 ticks after it in maze 1, at the same tick as Odin's GREEN.
- There is no chat line and no sound in the room for any of this. The pad teleports carry no sound packet in the box.

## 2. What is fixed and what is random

**Fixed:** the geometry (`MazePads.FRAMES`), which pad is START or END, the landing law (diagonal cell of the partner), and the
frame decorations.

**Random per run**
- Maze 1: START leads to 2NW, final 5SE. Maze 2: START leads to 6SW, final 2SW.
- The repo's 2,185 mazes show nothing repeating, about 1 in 28 shared finals, and uniform statistics.
- No block, entity or sound in the room marks the correct pad, before or during the run (checked: frame states, all blocks at
  y 66-75, entities in the box).

**Rules my data is consistent with**
- No pad pairs inside its own cell (0 of 13).
- The start landing's cell is not one step from the final cell.
- Every observed pad was used once only, so two-way pairing can't be tested from 2 mazes. The repo shows it in 102 of 102 pads.

**When you can know what**

| when | what |
|---|---|
| room load | nothing about the wiring |
| first landing anywhere (yours or a teammate's) | the final pad, alone 59% of the time; after two landings 96% (repo figures) |
| every teleport | one pair, A to B, so B's twin A is known too |

The hard part is not *which* pad: it is *how to get to its cell*. The wiring between cells is only learned by stepping on pads.

## 3. Timing (server ticks, from teammate packets: inferred, not from our own client)

- **Pads are checked about every 5 ticks (250 ms).** The 17 teleport ticks fall on a 5-tick grid. Circular concentration at
  period 5 is 0.85 (maze 1) and 0.81 (maze 2); no other period from 2 to 24 is high in both. The residues are 4 4 4 4 4 0 4 0 4 4 0
  and 3 3 3 4 4 4, so ±1 tick of jitter. Two mazes is still a thin sample.
- **From first seen standing on the frame (y 69.8125) to the teleport: 1-4 ticks** (13 cases). Positions are sampled every 2
  ticks, so the true wait is about 0-5 ticks, as a 5-tick poll predicts.
- You must be on or in the frame. A player hovering above 3SE at y 70.0, from a jump or an adjacent block, for about 8 ticks
  was not teleported.
  - Implied, not observed: a fast player running straight across a 1-block frame can be on it for less than 5 ticks and **not
    be teleported**. Stop on the pad.
- **Time between hops: 15-40 ticks** (maze 1: 20 20 20 40 26 34 26 24 15; maze 2: 20 15 16 20). The fastest was 0.75 s for
  a 5.1-block walk.
  - After each landing the player was still for 2-21 ticks before the first movement packet: the teleport-accept round trip
    plus reading the screen. That is where most of the time goes.
  - Walking is about 5.1 blocks to one of the two adjacent frames of the cell, or 7.07 to the diagonal one (the frame you
    landed beside is 1.41 away and leads back).
  - Observed speeds were 0.5-1.3 blocks per tick.
- **Cooldown:** none observed. The shortest gap was 15 ticks, which walking explains. Whether stepping straight back onto the
  pad you landed beside (1.4 blocks away) teleports at once was **not observed**.
- **Totals**
  - Maze 1: entry, then 14 ticks to the START pad, then 225 ticks for 10 hops, then the END pad, then 26 ticks to the chest:
    about 13.3 s. The final pad was known after the first landing, so about 9 hops were spent finding the way to cell 5.
  - Maze 2: entry, then 11 ticks, then 71 ticks for 4 hops, then 9 ticks: about 4.6 s.

## 4. How the observed players solved it

| maze | teleports from START to END | when the final pad was known | what the hops did |
|---|---|---|---|
| maze 1 | 11 | after landing 1 | 9 hops wandering cells 2, 6, 4, 3, 2, 1, 3, 1, 5. It looks like no route knowledge, possibly no solver; it can't be told. |
| maze 2 | 5 | after landing 2 | 3 exploring hops, then the final pad |

Simulated under the repo's rules (`sim.py`, 20k mazes, teleports counted from START to END inclusive):

| strategy | mean | median | p90 | worst |
|---|---|---|---|---|
| random pad in your cell | 10.7 | 8 | 21 | 126 |
| unused pad, else known route (repo `MazePlan`) | 7.8 | 7 | 12 | 13 |

The repo's real-world figure is a mean of 6.8 for walks from START to END.

Team play (`simteam.py`, everyone splitting the unused pads with shared knowledge; rounds until someone takes the final pad):

| players | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| rounds | 7.75 | 5.53 | 4.79 | 4.58 | 4.49 |

The rule floor is 4 teleports, so 2-3 players get most of the benefit.

## 5. The existing solvers

**Odin 0.4.0 `TPMazeSolver`** (from `javap`)
- How it works:
  - It acts only on your own `ClientboundPlayerPositionPacket`, when x and z are on the half-block grid and y is 69.5.
  - It marks as visited the pads within 1 block of the landing spot and of your bounding box.
  - Candidates are pads not yet visited whose frame box (1×4×1, widened 0.75) an XZ ray at the landing yaw hits within 32
    blocks. These are intersected across landings.
  - `best` is a pad of your current cell that is still a candidate, else the candidate with the smallest yaw error to the
    frame's middle. If you landed by START or END, `best` is cleared.
- Replayed on the 13 observed landings, it finds the right final pad every time (`odinsim.py`). Its fat boxes give 3
  candidates after maze 1's first landing, where the exact landing-cell test gives 1.
- Shortfalls:
  - It never learns pairs, so it **gives no route to the final cell**. That is exactly what cost maze 1 its 9 extra hops.
  - It ignores teammates' teleports.
  - It has no split between teammates.
  - It aims at frames instead of landing cells, which leans on the "best" heuristic when yaws are axis-aligned.

**Repo `TeamTpMaze` / `MazeMap` / `MazePlan`** (this replaces Odin's solver; `TpMazeRenderMixin` switches Odin's off)
- What is right:
  - Exact landing-cell aim, with 0.5 degrees of tolerance.
  - Two-way pairs; candidates exclude any pad with a known partner or landed beside.
  - Breadth-first routing over known pairs, with a stop back in the corridor costing the extra START hop.
  - Unused pads split by name among clients that run it.
  - Teammates read from `entity_position_sync` (the packet Hypixel actually uses) as "not sure" pairs, and the whole map
    shared over Odin's websocket.
- Problems found:
  1. **Teammate origin is unreliable.** `seen()` reads `positionCodec.base` on the network thread. That lags the server by up
     to 2 ticks of sampling, and by any move packets still waiting for the client thread. In maze 1 the last known position was
     0.14-2.88 blocks from the pad actually used: 3 of 10 were more than `REACH` 1.2, so the pair was lost. One (2.88, really
     1NW) was nearest to the *wrong* frame and was only safe because of the reach limit.
     - Fix: take the base inside the `mc.execute` (queued before the sync packet's own handler, so it is in order).
       Extrapolate along the last two samples, and pick the frame of that cell the motion points at.
  2. **Own origin is read as the current client position** when the teleport packet arrives. That is one-way latency plus
     0-5 ticks of polling after you touched the pad, so a fast player may already be off it. Fix: remember the last frame your
     own client stood on, within about 10 ticks.
  3. **The plan ignores walking distance.** It takes fresh pads in index order. It should prefer the two adjacent frames
     (5.1 blocks) over the diagonal one (7.07), and order teammates' assignments by distance, not only by name.
  4. **Splitting covers only teammates who run the mod** (`heard`). Others can collide. One fix: infer a non-client
     teammate's target from their heading and treat that pad as claimed.
  5. **There is no warning to stop on the pad**, which matters given the 5-tick poll. Also none about the chest, which is
     what actually completes the puzzle.
  6. Unverified against our own packets: relative flags on our pad teleport, and `Y_ROT` relative handling. The code does
     handle both cases.

## 6. Possible designs

1. **Final pad only** (Odin): highlight the pad the yaw points at. Cheap, but no help reaching its cell. About 10.7
   teleports on average if you pick pads at random.
2. **Solo map with routing**: record pairs, route over known pads, otherwise explore an unused pad. About 7.8 teleports.
3. **Local team map**: (2) plus teammates' `entity_position_sync` teleports read locally, with no network. It works when
   teammates are in tracking range, which is always true inside the room. Pairs are ambiguous only where the origin is unsure.
4. **Networked team map** (the current repo): (3) plus exact pairs and assigned pads over the websocket.
5. **Precomputed or lookup layouts**: impossible. There is no fixed wiring.

**Recommended: (4) built on (3).**
- Keep reading teammates' teleports locally as the base layer, since the websocket can be down or a teammate may not run the
  mod. Fix their origin estimate (problem 1).
- Use the websocket only to add certainty and coordination.
- Display:
  - one big tracer to *your* next pad, drawn the tick the teleport packet arrives;
  - the final pad in green as soon as it is known, even from a teammate's landing;
  - "→ cell N" labels on used pads;
  - teammates' assigned pads;
  - a "stand still" cue while on a pad;
  - a chest highlight after the END landing.
- Choose the nearest unused pad.
- Lag: everything is driven by packets, not render positions. Ignore landings off the .5 / 69.5 grid or with yaws that aim at
  no pad (setbacks). Merge pairs idempotently. Prefer a pair from a player's own client over one seen from outside.

## 7. Data still needed

- **Recordings where we walk the maze ourselves** (10 or more). They would give our `player_position` fields and relative
  flags, the time from standing on the pad to the packet in our own ticks, the `accept_teleportation` round trip, whether
  movement sent before the accept is ignored, and setbacks.
- **Deliberate tests**:
  - stepping back onto the pad you landed beside right away (cooldown?);
  - sprinting straight across a pad (is it missed?);
  - standing on a pad (repeat teleports?);
  - jumping onto a pad;
  - two players on one pad in the same tick.
- **More mazes**, about 20 more teleports, to confirm the 5-tick poll and whether its phase is global or per room.
- **Team runs where several clients record**, to measure how much the shared map actually saves.
