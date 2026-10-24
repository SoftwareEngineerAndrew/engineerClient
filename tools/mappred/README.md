# Map Prediction analysis

How `src/mappred` (Room Prediction on Odin's Dungeon Map) was worked out from the runs on ADR, and how to
redo it if Hypixel's dungeon generation changes. Node 20+, `xz` and `gzip` on the PATH. Run the scripts
from an empty data folder (they read and write their data in the current folder):

    node <tools>/fetch-list.mjs                              # list-all.json: every public run
    node <tools>/fetch-layouts.mjs list-all.json layouts.jsonl  # each run's map: rooms and doors (start of each file only)
    node <tools>/rules.mjs                                   # checks every rule below on every complete F7/M7 map
    START=old-tables.json node <tools>/calib2.mjs 450 6 tables.json  # fits the sampler's weights on training maps
    node <tools>/evaluate.mjs 150 400 prim 0.3 tables.json   # scores it on held-out maps (simulated discovery order)
    node <tools>/fetch-timelines.mjs list-all.json timelines.jsonl 400; node <tools>/exp6.mjs tables.json  # real order
    node <tools>/gen-kt.mjs                                  # the numbers for MapStats.kt (edit gen-kt for the tables file)

The Kotlin predictor can be checked against the same maps: `node export-snaps.mjs` writes snaps.txt, then
`ADS_MAP_SNAPSHOTS=<data>/snaps.txt gradle test --tests com.engineerclient.mappred.MapSnapshotsCheck --rerun`
writes snaps.txt.out and `node score-kt.mjs` scores it. Keep the data folder out of the repo.

## What every recorded F7/M7 map does (4494 complete maps, Sep-Oct 2026)

- The doors make one tree (no loops); the entrance is on an edge, never a corner, with one door.
- Exactly 7 big rooms (99.4%), never more than two of one shape, 22 tiles of them (88%) - so 14 1x1s, 21 rooms.
- An L room never has its north-west corner missing (0 of 2200+ Ls; the other three orientations about a third
  each): each room is placed by the top-left corner of its bounding box, which only that L leaves empty.
- A big room takes any spot it fits (every orientation and place alike), not an orientation first: holding the
  rest of each map, the orientations come out as that gives them (`orient.mjs`-style check, within 1 s.e.).
- The 1x1s bunch up more than placing the big rooms at random gives, the more the fewer dead ends (puzzles)
  the map has: 2x2 blocks of four 1x1s are 1.6 a map on 2-puzzle maps, 0.84 on 5-puzzle ones (the sampler
  gave 1.06 and 0.71). The puzzle count isn't just what the dead ends leave (no map has fewer than 5 or more
  than 9 1x1 dead ends besides the entrance, which no free tree gives), so the dead ends are a target, and the
  layout leans to it: `FloorRules.bunch` weighs each 2x2 block of 1x1s (sampler2 `opts.bunch`; off until a
  held-out evaluation backs a value).
- Each shape has its own places, beyond the shared per-tile weights: 1x4s run along the map's edges and into
  its corners, 1x2s keep off them, and not alike top and bottom (the map's own directions): `calib-aw.mjs`
  fits a weight per shape, orientation and top-left tile (`FloorRules.anchorWeights`, sampler2 `tables.AW`; none
  set yet).
- Every special room (entrance, blood, trap, yellow, puzzles, rares) is a 1x1 dead end, and a normal 1x1 is
  never a dead end: the 1x1 dead ends besides entrance and blood are exactly puzzles + trap + yellow + rares.
  Rares only on 5-puzzle maps (the dead ends past the 5th puzzle).
- Wither doors only on the path entrance -> blood; the fairy is always on it.
- Walked tile by tile, that path is 9 or 10 steps (96.6%; the parity of the entrance-blood distance decides
  which; 7/8 where a big room is crossed corner to corner), and the fairy is exactly 4 steps before blood.
- Trap, yellow and the puzzles are spread uniformly at random over the remaining 1x1 dead ends.
- The tab list's puzzle lines are in the puzzles' order on the map, column by column (x west to east, each
  column north to south). A puzzle's name replaces "???" once someone has been in it, so its line says how
  many puzzles come before it: the predictor keeps only puzzle placements that fit (F7 so far; ADR records
  no tab list, so this one isn't checked on its maps).
- No two maps were the same: it is generated fresh each run, so predictions are chances, not lookups.
- A room name comes up at most once a map, always with the same secrets, and within its pool (shape or type) about
  equally often (yellow Default and New Trap twice the others): with the tab list's secret total that names the last
  rooms (`node gen-names.mjs` writes MapStats.NAMES).

## The other floors (1209 complete F1-F6/M1-M6 maps, Sep-Oct 2026)

    node <tools>/floors.mjs                                  # every rule above, floor by floor, and what differs
    FLOORS=1 SAMPLES=150 node <tools>/calib2.mjs 450 6 tables-f1.json      # one table per main grid size:
    FLOORS=2,3,4 SAMPLES=150 node <tools>/calib2.mjs 450 6 tables-f234.json  # 4x5, 5x5, 5x6
    FLOORS=5,6 SAMPLES=100 node <tools>/calib2.mjs 450 6 tables-f56.json
    FLOORS=5,6 node <tools>/evaluate.mjs 200 400 prim 0.3 tables-f56.json   # FLOORS works for every script
    node <tools>/gen-floors.mjs tables-f1.json tables-f234.json tables-f56.json > FloorStats.kt

Every rule above holds on every floor (F and M alike); what differs is in `common.mjs` FLOORS and FloorRules.kt:

- The main grid is 4x5 on F1, 5x5 on F2-F4, 5x6 on F5-F6 (Odin's map draws F4 as 6x5 and F5/F6 as 6x6).
- F4-F6 have a puzzle column east of it (x = 5): only 1x1 dead ends (puzzles; trap or yellow only when the
  main grid has fewer than two), each behind one door from a normal room in column 4 (a big room, or a 1x1 with
  two doors already). It tops the dead ends (puzzles + trap + yellow) up to 5 on F5/F6 (99%) and 6 on F4 (79%,
  else one or two short): as many column rooms as the main grid is short of, none when the tab list's puzzles
  are more than that. So a 4-puzzle F5 has an empty column.
- Big rooms: F1 4 of different shapes, F2 always one of each, F3/F4 5 (sometimes 6), F5/F6 6; never more than
  two of a shape.
- No trap room on F1-F2. Yellow and the fairy on every floor.
- The wither path walks 7 or 8 steps with the fairy 3 before blood on F1-F4; 9 or 10 and 4 on F5-F7.
- The entrance is in a corner only on F1 (about a quarter of maps).
- The lower floors have fewer rooms: a room on one floor is on every floor above it, so a name is left out
  below the first floor it's seen on only where those floors would have had it at least 5 times (F1/F2 have
  5 puzzles and 2 minibosses, no Ice Fill below F7).
- The recorder sometimes reads a door at the dungeon's edge with no room behind it; sim.mjs counts it a wall.
