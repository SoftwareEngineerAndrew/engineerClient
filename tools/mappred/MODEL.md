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

## 5-puzzle maps (the rares)

- **Sure:** the puzzle count follows from the dead-end count: besides entrance and blood 4, 5, 6 dead ends on 2, 3, 4 puzzles
  and 7-10 on 5 (2486 maps: 710, 899, 582, then 236, 49, 7, 2 - one smooth fall), the ones past the 5th puzzle, trap and yellow
  rares, placed among the dead ends at random (depth-rank percentile 0.496, 70 rares).
- **Likely:** given the puzzle count the layout moves as the sampler's trees with that many dead ends do (2 to 5 puzzles; 2x2
  blocks of 1x1s real 1.54/1.28/1.03/0.83, sampler from the entrance alone 1.28/1.11/0.92/0.67; leaves 8.0 to 10.2, 8.1 to 10.4;
  the room behind the entrance a 1x1 in 37% to 15%, 49% to 25%; deepest room, blood, the dead ends' depth and the normal 1x1s'
  places within 0.3 of the maps), so the predictor, which has the puzzle count from the tab list, needs no dead-end process of
  its own. The tree rule fits 5-puzzle maps as it does the rest (treelik PUZ=5, 294 maps, against the rule: alpha 0.2-0.4 and mu
  0.15-0.25 within +0.07, beta 0.35 +0.11 s.e. 0.05, 0.65 -0.17; on 2-4 puzzle maps beta 0.35 +0.015).
- **Open:** what sets the count. The tree's own counts run high: from 2 rooms known on 300 training maps, D = 4..10 dead ends
  real 81/127/64/21/7/0/0, the sampler's trees (kept at 4-10) 61/91/84/47/14/2.4/0.3, about x0.7 a dead end, so 5-puzzle maps
  come out 21% against 9%. A count drawn first and the tree grown again until it fits, or something the tree rule lacks (step 4).
  Within 5-puzzle maps the same lean: the sampler makes 0.32 rares a map, the maps 0.24 (7/8/9/10 dead ends 73/22/4/0.4% against
  80/17/2.4/0.7%; the whole layout given, 62 maps 73/23/3/0.4 against 86/13/2/0); rarew=0.7 puts the mass of R right but moves no
  score (fresh maps +0.0012 s.e. 0.0040, training maps -0.0035 s.e. 0.0033: its places are no better), so it stays off.
  5-puzzle maps have the mix of two 1x2, a 1x3, two 1x4, a 2x2 and an L (21211) 18% against 12% (chi-square 25 on about 16, the
  sampler's own 5-puzzle samples 12%): handing the 5-puzzle snapshots the 5-puzzle training maps' mixes (20 maps' worth of the
  all-map shares added) +0.0035 s.e. 0.0034 on the 147 held-out maps (sampler3 at 300 samples), not kept.
- **Sure, and the answer to why it seemed to lose:** run as the mod runs them (2000 samples, 120 batches' worth) the game process
  is ahead of the classic sampler on 5-puzzle maps, as on the rest: 147 held-out 5-puzzle maps (the 38 in the test split and 109
  recorded since, 735 snapshots at 2/4/6/9/12 rooms known) -1.0369 against -1.0539, +0.0170 s.e. 0.0045 (by map), -1.0350 and
  +0.0189 s.e. 0.0037 with its calibration (below); ahead on 97 of the 147 maps and at every stage, +0.013 s.e. 0.004 on the 117
  maps without a rare room, +0.042 on the 30 with one. With the mix table made without the 109 (GameStats.kt counts every map, as
  the classic sampler's tables from the older snapshot do not): +0.0156 s.e. 0.0046 on them (the table's own maps were worth
  0.0036). 100 held-out 2-4 puzzle maps -1.0198 against -1.0368, +0.0170 s.e. 0.0049. The 45 snapshots that had it 0.032 behind
  were 9 maps without a single rare room, where sampler3 puts 0.24 tiles a snapshot on R against the classic sampler's 0.13 (a
  map's gap has a standard deviation of 0.05, so 9 maps are good for +-0.017; the same 9 maps on other snapshots, both in the mod:
  -0.001, the other 29 test maps +0.018; the 45 rerun with the calibration -0.012 s.e. 0.014).
  What the game loses on every 5-puzzle set is the normal 1x1s (-0.009 a tile) - its chances for them are too sure; it gains the
  puzzles (+0.013) and the rares (+0.006). At sampler3's 300 samples it is ahead of the classic sampler at 300 samples (+0.015 on
  38 test maps, +0.031 on 148 training ones) and level with the classic one at 2000 and calibrated (+0.002 s.e. 0.007, 109 fresh
  maps): the classic sampler needs its 2000, sampler3 gains 0.02 from the mod's budget.
- **Calibration** (`cal=1`, `CAL`; `cal=2`, `CAL_MOD`): the game's samples are too sure of themselves, more at 300 samples (a
  0.75-0.95 per class, the rares 1.0) than at the mod's budget (0.81-0.97), as the classic sampler's are. Fitted on training maps
  (148 5-puzzle, 120 2-4 puzzle) and held out: +0.0047 s.e. 0.0019 on 109 fresh 5-puzzle maps and +0.0034 s.e. 0.0018 on 100 2-4
  puzzle maps at 300 samples; the mod's own samples, `cal=2` (GameSampler.CALIBRATION), +0.0019 s.e. 0.0012 on the 147 5-puzzle
  maps and +0.0007 s.e. 0.0011 on the 2-4 puzzle ones (the same samples with and without it).

## How good it is as a predictor

`sampler3.mjs` with the rule above, on held-out maps (independently checked on fresh maps and a new seed): +0.037
ll/tile over its first version at 9-12 rooms known, and ahead of the mod's current predictor at every stage
(+0.020 at 9-12 rooms known, +0.022 at 2-4); with 1500 samples per snapshot, on 150 maps it was never tuned
on, -1.0737 against the current predictor's -1.0847 at 2-6 rooms known (+0.011, s.e. 0.0045). In the mod
(`src/mappred/GameSampler.kt`) it is the F7 predictor.

In the mod, run as its worker runs it (2000 samples, with its calibration), against the classic sampler on 247
held-out maps (1235 snapshots at 2/4/6/9/12 rooms known; `targets.mjs`, paired, s.e. clustered by map), it is ahead
on each thing the map is read for and never behind by more than noise at any stage or in any set:
- blood: log chance at blood's tile +0.051 (s.e. 0.015); top-1 51.9% / 50.5%, top-3 73.7% / 73.1% (ties);
- puzzles: per-tile log loss of puzzle or not +0.0052 (s.e. 0.0012); of the r likeliest tiles 40.9% / 39.3% right;
- big rooms: per-tile log loss of big or not +0.0091 (s.e. 0.0022); tiles at 70%+ big that are right, 4.51 / 4.11
  a snapshot (s.e. 0.05);
- all classes: +0.0184 ll/tile (s.e. 0.0028).
Its lead is the same size on the three (at most 1.0 s.e. apart), so the mod has one F7 predictor rather than one
per target.

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
