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
- Every special room (entrance, blood, trap, yellow, puzzles, rares) is a 1x1 dead end, and a normal 1x1 is
  never a dead end: the 1x1 dead ends besides entrance and blood are exactly puzzles + trap + yellow + rares.
  Rares only on 5-puzzle maps (the dead ends past the 5th puzzle).
- Wither doors only on the path entrance -> blood; the fairy is always on it.
- Walked tile by tile, that path is 9 or 10 steps (96.6%; the parity of the entrance-blood distance decides
  which; 7/8 where a big room is crossed corner to corner), and the fairy is exactly 4 steps before blood.
- Trap, yellow and the puzzles are spread uniformly at random over the remaining 1x1 dead ends.
- No two maps were the same: it is generated fresh each run, so predictions are chances, not lookups.
- A room name comes up at most once a map, always with the same secrets, and within its pool (shape or type) about
  equally often (yellow Default and New Trap twice the others): with the tab list's secret total that names the last
  rooms (`node gen-names.mjs` writes MapStats.NAMES).
