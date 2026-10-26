# How Hypixel makes an F7 map: the current model

What the 2476 complete F7/M7 maps recorded on ADR (Sep-Oct 2026) pin down about the game's own generation, in the
order the game does it. **Sure** = no recorded map breaks it, or one test with a clear answer. **Likely** = the best
of every candidate tested, with the evidence and what still doesn't fit. Details, numbers and the scripts behind
each are in README.md ("How the maps are made", "What the game's code could be doing"). `sampler3.mjs` +
`tree3.mjs` run exactly these steps as a predictor, conditioned on what the map shows, with no fitted tables.

The grid is 6x6 cells; a cell is a 1x1 room or part of a big room.

## 1. The entrance

- **Sure:** on the map's edge, never a corner, with exactly one door, and that door goes inward (2476 of 2476).
- **Sure:** nothing after this depends on the map's own directions: turned so the entrance is on the top row, maps
  that needed a mirror and maps that didn't have the same big rooms and dead ends cell by cell, and so do
  north/south-entrance maps against east/west ones (`asym.mjs`). So nothing scans rows or columns.
- **Open:** which edge cell. The north/south edges come up 3x as often as west/east, and the cells next to a corner
  2x as often as mid-edge, the same on both kinds of edge; that is in however the code picks it (restarting the
  whole map on a failure explains only 1.24x of the 2x).

## 2. The big-room mix

- **Sure:** 7 big rooms of 22-24 tiles (99%; never 20-21, never 8 rooms), at most two of a shape; otherwise 6
  rooms of exactly 22 (1%, the most 6 can make). Shapes 1x2, 1x3, 1x4, 2x2, L.
- **Likely:** random shapes added until the big tiles reach 22, more than 7 rooms and it draws again
  (`mixrule.mjs`, -2.46 a map against the best possible -2.415), the 4-tile shapes drawn less, a second copy of a
  shape x0.7. Still gives 23 tiles 15-17% of the time against 11%, so the exact rule isn't found; the predictor
  uses the recorded mix frequencies (over each mix's success rate), which this doesn't change.

## 3. Placing the big rooms

- **Sure:** a room is placed by the top-left corner of its bounding box: no L has its north-west cell missing
  (0 of 2200+), the other three L's about a third each.
- **Likely:** one random spot inside the map per room, and the whole placement again if any two clash - every
  complete layout of the mix alike (`placelik.mjs`: the exact chance of each map's big rooms under each
  procedure; this and "random order with the hard spots discounted" lead, about +0.4 a map over biggest first,
  under every door-tree model tried; self-checked on maps made by known procedures). Ruled out: a row/column
  scan, biggest first, the special rooms' cells chosen first, rooms grown by merging cells.

## 4. The door tree

- **Sure:** the doors make one tree (no loops) over all 21 rooms, from the entrance.
- **Sure:** grown room by room - when the tree enters a room, all its cells join (the cells of a 2x2 are joined
  inside by a tree, which is why some wither paths go round inside one): +0.5 a map over growing cell by cell
  (`treelik.mjs`: the chance a rule makes each real map's doors and blood given its layout, self-checked).
- **Likely:** the next room to grow from is picked with chance proportional to the square root of its free sides;
  a free side weighs exp(-0.5 x its cell's steps from where that room was entered) x exp(-0.2 x the new cell's
  grid steps from the entrance) - doors leave a room near where you came in and branch back toward the
  entrance, which keeps the tree shallow (`tree3.mjs` rule `room/node/0/0/alpha=0.5/beta=0.5/mu=0.2`). +0.49 a
  map over the best rule before it on 300 maps, +0.495 on 600 maps it wasn't tuned on. Into a big room with two
  shared sides the door is on the one nearer the parent's entry 67% of the time (old rule 51%), with three 70%
  (37%).
- **Open:** real trees are still less likely under it than its own trees are, so something is missing - but only
  by 0.23 a map (s.e. 0.10, 600 maps, its own trees regrown to the same dead-end count), not the 0.4-0.7 an earlier
  self-check suggested. It sends doors into a 1x1 in an L's notch 70% of the time against 51%. Real doors lean
  more than the rule toward cells on the map's edge or in a corner and away from cells with many free sides
  (scorediag.mjs: z +5.2, +3.2, -6.1); being tested. Tried and no better: growing cell by cell with a room's own
  sides weighted (its own-tree gap is 0.78 a map), a different pull for doors into 1x1s, a room-then-cell mix.

## 5. Keep the map or make it again

- **Sure:** the 1x1 dead ends besides the entrance and blood are exactly the special rooms: puzzles + trap +
  yellow, plus 0-3 rares on 5-puzzle maps (7-10 such dead ends there); a normal 1x1 is never a dead end. A tree
  that doesn't come out that way is made again.
- **Sure:** blood is any 1x1 dead end 9 or 10 steps from the entrance (counted cell by cell, through the rooms'
  insides) that has a 1x1 exactly 4 steps before it, all such alike; that 1x1 is the fairy. 9 when entrance and
  blood are an odd number of tiles apart, else 10. No such spot and the map is made again.
- **Sure:** the doors on the path entrance -> blood are wither doors, the last one the blood door.

## 6. The special rooms

- **Sure:** trap, yellow, the puzzles and rares go on the other dead ends at random.
- **Sure:** the tab list lists the puzzles in their order on the map, column by column (x west to east, each
  column north to south); a puzzle's name shows once someone has been in it.
- **Sure:** each room's name comes from its shape's (or type's) pool, at most once a map, always with the same
  secrets (MapStats.NAMES).

## Room mix given what the map shows

- **Sure** (by construction, and measured): maps with 6 big rooms are possible (1%), so the predictor allows those
  mixes; and a mix with c rooms of a shape of which k are already on the map is c!/(c-k)! times as likely to have
  put them there (two 1x3s are twice as likely as one to have a 1x3 where the map shows one). Without the two
  the game-process predictor scores 0.007 worse at 9-12 rooms known (s.e. 0.004, 1500 samples, fresh maps).

## How good it is as a predictor

`sampler3.mjs` with the rule above, on held-out maps (independently checked on fresh maps and a new seed): +0.037
ll/tile over its first version at 9-12 rooms known, and ahead of the mod's current predictor at every stage
(+0.020 at 9-12 rooms known, +0.022 at 2-4); with 1500 samples per snapshot, on 150 maps it was never tuned
on, -1.0737 against the current predictor's -1.0847 at 2-6 rooms known (+0.011, s.e. 0.0045). Being ported into
the mod (`src/mappred`) for F7.

Sampler switches measured at 1500 samples on fresh maps, late stages (9-12 known; all off by default, none
helps clearly so far): rarew (each rare past the 7th dead end x0.5) +0.002 (s.e. 0.002), move (shifting
unreached big rooms in copied particles) -0.003, joined (an extra shown-door constraint) -0.004; prune and
distinct still being measured.

## Long rooms side by side

- **Likely:** two long straight rooms of the same orientation in neighbouring rows sit side by side a little less
  often than the model gives, and flush less often (`sidebyside.mjs`, 2476 maps; real / game-process predictor):
  1x3 next to 1x4 0.306 / 0.322 a map, flush 53% / 57%; two 1x4s 0.075 / 0.084, flush 33% / 42%. A 1x2 next to a
  1x3 is as the model gives (0.243 / 0.237, flush 66% / 65%). Not yet explained.

## Open questions, biggest first

1. What the door tree does that the rule above doesn't (step 4).
2. The exact rule for the room mix, and why 23 tiles comes up less than any rule tried gives (step 2).
3. How the entrance's cell is picked (step 1).
