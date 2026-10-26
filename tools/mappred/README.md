# Map Prediction analysis

The current model of how Hypixel makes an F7 map, step by step (sure and likely), is in [MODEL.md](MODEL.md).

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
- None of the last three moves held-out predictions measurably (Oct 2026, 1000 snapshots of 200 held-out maps,
  300 samples each): per-tile log-likelihood -1.0499 before the L fix, -1.0476 with it, -1.0482 with bunch 0.25
  too, -1.0491 with AW, -1.0499 with both; the L fix alone again on another seed -1.0499. The seed moves it
  by about 0.002, as much as any of them. The L fix stays (it only drops placements no map has); bunch and AW
  stay off until something shows they help.
- What does: 5 cells held back while the big rooms are placed (sampler2 `opts.reserve`, `FloorRules.reserve`,
  on for F7): -1.0427 and -1.0436 on two seeds against -1.0476 and -1.0499 (big-room tiles -0.5923/-0.5924 against
  -0.5963/-0.5967); 3 held -1.0468, 7 held -1.0530. Growing the predictor's tree oldest-first, as the game's is,
  does worse (-1.086 at 0.3, -1.130 at 0.5; Prim alone -1.057): it grows only the unknown part, from the doors
  the map shows, so oldest-first there spreads evenly from every door, not out from the entrance.
- On real discovery order (evalbig REAL=timelines.jsonl TLALL=1, 740 snapshots of 220 recorded runs): the held
  cells -1.0738 against -1.0823 without them; refitting the tables (calib2 OPTS) -1.0810, the blood/fairy tables
  flat (FLAT=MW,FM) -1.0815, the 2x2 walk counted the game's way (opts.detour 0.25) -1.0815: none of those kept
  (on simulated order they were within the noise too). 7 snapshots had no sample at all: a room on the map
  before the room it's reached through (18 of 235 runs, 3 s at the median, up to 51 s); the predictor now
  leaves those out until they join (MapView.connectedPart): -1.0669 with nothing left without a prediction.
- The calibration layer (calfit.mjs, KEEPZERO): the samples are a little too sure (a = 0.84-0.94 per class);
  fitted on 300 training maps, held-out -1.0417 to -1.0393 on the same samples (big-room tiles -0.5922 to
  -0.5912, tiles 70%+ big right 76.5% to 77.6%). In the predictor for F7 (FloorRules.calibration).
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

## How the maps are made (F7, reverse engineered from 2476 complete maps, Oct 2026)

What the maps pin down, then what fits them as a generator (`generator.mjs` runs the candidates end to end
and scores them against the real maps). Sure = a rule no map breaks or a test with one clear answer; likely =
the best of the candidates tried, with what still doesn't fit.

Sure:
- The wither path is a walk over single cells of exactly 9 or 10 steps (9 when entrance and blood are an odd
  number of tiles apart, else 10: 60/40), with the fairy exactly 4 steps before blood. Every one of the 71
  maps whose shortest walk is 7 or 8 goes through a 2x2, and going round inside it makes exactly 9 or 10: the
  generator counts steps over cells, and the cells of a 2x2 are joined inside by a tree, not all four sides.
- Blood is any of the 1x1 dead ends 9 or 10 steps out that have a 1x1 four steps back (for the fairy), all
  alike: against the other such dead ends of the same map it is no farther, no likelier on an edge or in a
  corner (577 maps with a choice). 77% of maps have only one. So where blood lands (85% on the map's edge,
  32% in a corner, mostly on the far side) comes from the door tree's shape.
- Trap, yellow, the puzzles and rares go on the other 1x1 dead ends at random: each one's walk, nearness
  rank and x-then-z rank among its map's dead ends are those of a random pick.
- The dead ends are a target, not what's left: besides entrance and blood there are 4-7 (8 with a rare,
  1-2%), i.e. 2-5 puzzles with trap and yellow; no free tree gives that range, so maps outside it are made again.
- Big rooms are placed by their bounding box's top-left corner (no L without its north-west cell), any spot
  that fits alike (the orientations come out as the free spots give them, not an orientation first).
- The door tree is shallow: its deepest tile is 10.6 steps out on average (blood 9.4), the average tile 5.5;
  in 54% of maps nothing is past blood, and what is is mostly a dead end 1-3 steps past. Doors run away from
  the entrance (10.7 across its edge to 9.2 along it, whichever edge).
- The entrance is on the north or south edge 3x as often as west or east, and next to a corner 2x as often as
  mid-edge (13/6/7/12% along the top, 4/1/2/4% down the sides); one attempt from a mid-edge cell passes less
  often, which gives some of the 2x but not the 3x.

Likely (generator.mjs `cells:bfs0.2:rnd:res4`, the closest of the ~40 tried):
1. a door tree over the 36 single cells from the entrance (one door), each new door off the oldest room
   that still has a free neighbour 20% of the time, else off a random room of the tree;
2. 4 random cells held back, the 7 big rooms (the shape mix) merged in random order, each anywhere its
   cells are free and already joined by the tree;
3. blood and the fairy as above; the dead-end range checked; the specials on the dead ends at random.
That gets the tree's depth (deepest tile 10.5 steps out, real 10.6; average tile 5.9, real 5.6), blood (6.7
tiles from the entrance, 87% on an edge, 34% in a corner; real 6.6, 86%, 32%), the path (7.8 rooms, 2.8 of
them big; real 7.6, 2.7), the dead ends' spread, and most of the 1x1 bunching and how it falls with the
puzzles (2x2 blocks of 1x1s 1.30 to 0.78 from 4 to 7 dead ends, real 1.60 to 0.87; 0.85 overall without the
held cells, as the predictor). With a Prim tree it goes deeper (deepest 11.1, average 6.2; 12.4 with the
rooms placed first); a depth-first tree leaves 2 dead ends, not 5.
Still off: the fairy (4.0 tiles from the entrance, 14% on an edge; real 4.4, 28%: the real path runs straight
out first and turns nearer blood), the 1x1s beside the entrance (dead ends 0.45 a map, real 0.76), the path
going round inside a 2x2 (2% of the times it enters and leaves by neighbouring cells, real 11%), the rest of
the bunching, and the entrance's edges.

### Where the big rooms' pulls come from (placefit.mjs on generator.mjs DUMPs)

placefit.mjs fits, for each big room with the rest of its map fixed, what makes its spot likelier than the
others its shape could take (per standard deviation; uniform placement shows what fixing the rest gives alone):

    pull on a big room's spot        real   uniform   cell tree   + 4 held   grow c2   grow c4
    tiles from the entrance        -0.20     +0.02      -0.21      -0.15     -0.22     -0.85
    touches the entrance           +0.16     -0.12      +0.16      +0.17     +0.05     +0.11
    sides on the map's edge        +0.14     -0.42      -0.06      +0.11     +0.07     +0.10
    2x2 blocks of 1x1s left        +0.14     -0.02      -0.08      -0.01     +0.14     +0.11
    sides on other big rooms       -0.05     -0.25      -0.28      -0.17     -0.18     +0.33

The entrance pull is the tree's: big rooms merged only where a shallow cell tree already joins their cells
sit as near the entrance as the real ones, with nothing fitted for it. What's left - big rooms on the map's
edge, against each other, the 1x1s left in blobs - is what the held cells stand in for (the edge, not the
blobs). Making the big rooms while the tree grows (`grow`) leaves the 1x1s in blobs as the real maps do (the
cells grown once the shapes have run out, or where none fits): c2 gets every pull above roughly right, but
too many 1x1s with 3+ doors (2.1 a map, real 1.6) and 1x4 dead ends; c4 gets those and the bunching (1.32,
real 1.24) but puts the big rooms too near the entrance. So the rooms are likely made during the growth, by
a rule not yet found between those two. Growing the wither path first (generator.mjs `pgrow`, blood the end
of a 9-10 step walk) is ruled out: blood comes out 55% on an edge and 9% in a corner (real 86%, 33%) and
the tree too deep (deepest tile 12.6, real 10.6) - blood is picked from the grown tree, as the within-map
test says. Still unexplained: more big rooms entered at walk 2 than any of these make (real 0.88 a map,
0.54-0.73), fewer at 5 (0.72, grow 0.87).

The room behind the entrance (first-room.mjs): it has a door to nearly every neighbour - a 1x1 there has all
4 doors in 34% of maps (the generators 10-18%), a big one 4+ in 80% (51-58%), 3.04 rooms off it (2.3-2.6),
0.47 of them 1x1 dead ends (0.27). That is the extra rooms at walks 2-4 and the 1x1 dead ends beside the
entrance (hazard.mjs: the share of the rooms entered at each walk that are big is as the generators give,
there are just more rooms there; the dip at walk 5 shows on a constant rate too, it is the geometry). So:
the first room is finished at once and given a door to every free neighbouring cell (generator.mjs `f`).
With that, `grow:bfs0.1:c3f` (a cell tree 10% oldest-first, big rooms started as it grows, chance 3 x shapes
left / free cells) is the closest yet with nothing held back (mismatch 1684 against 2474 for the held-cell
cell tree, 1500 maps): the tree, blood, the path, the dead ends, the first room, most of the bunching (1.09,
real 1.25). Still wrong: its big rooms sit too near the entrance and against each other (placefit -0.63 and
+0.23, real -0.20 and -0.05): they start too early in the growth, and starting them later (c2) brings too
many 1x1s with 3+ doors - what decides when a room starts is still open.

What a room's start depends on (startrule.mjs: every room entry, the rooms at a shallower walk taken as
placed, a logistic fit of "big"; grow's own rule shows what the reconstruction does by itself):

    starts a big room, per unit         real   grow c3f   one try (c9a)   tree first, one try (try1)
    log placements that fit there      +0.62     +0.28        +1.06              +0.82
    entry cell on the map's edge       +0.38     +0.08        +0.65              +0.46
    log shapes left                    +1.32     +1.62        +0.79                -
    log free cells                     -2.28     -2.49        -2.43                -

The real start leans on the room there is for a shape, and on edge cells, far more than "start one if any
fits" does: like a try at one placement that may not fit (a random one over the cell, inside the map), a
little less (2-3 tries overshoot as well). Rooms aren't anchored at the cell they're entered by (top-left
entry 27% for a 2x2, 47% for a 1x2: as their size gives). Tries during the growth (c9a) fix how the big
rooms sit against each other (-0.08, real -0.05) but every grow variant still puts them too near the
entrance (-0.47 to -0.93, real -0.20), the quota running out close to it; the tree first with one try per
cell in a random pass (try1) gets the entrance and edge pulls (-0.24, +0.21) but neither the packing
(-0.26) nor the 1x1 blobs (-0.03, real +0.12). No single one of these is the generator yet.

Why 7 big rooms of 22 tiles (2476 maps): the big tiles are never fewer than 22 (22 in 88.5%, 23 in 10.7%, 24
in 0.8%); 1% of maps have 6 big rooms, and those make 22 too. All 9 mixes of 7 rooms (two at most of a shape)
that make 22 turn up, nothing else but a few 23s and 24s. Not a byproduct of the dead-end target: 7 shapes
at random each attempt, the dead ends checked, leave 22 tiles in only 28-34% of the maps (generator.mjs
MIX=random7); not "add rooms until 22" (35%, and up to 25). So a budget: the shapes picked one at a time,
each keeping 7 rooms of exactly 22 tiles reachable (a 23 budget about 11% of the time). Within it a second
copy of a shape is less likely than the first (one 1x3 and one L 17.3%, two 1x3 6.2%, two L 5.3%):
picking alike gives chi-square 533 on the 9 mixes, a second copy at 0.73 the first's weight and shape
weights 1x2 0.39, 1x4 0.83 (1x3, 2x2, L 1) give 79. Not the room designs (1x2 has the most, 14, and is
picked least); not counts decided shape by shape in a fixed order (600 at best); not where they fit (made
as the tree grows under the budget, two 1x4s and two 1x2s come out 13%, real 3%). What sets the weights is
open.

When the big rooms start: the share of the rooms entered at each walk that are big falls from 0.70 at
walk 1 to 0.41 at walk 4 (grow c3: 0.70 to 0.52); a start chance falling faster than the shapes left
(c * shapes left^a / free cells, a = 2 or 3: generator.mjs u al=) makes the first rooms all big and fits
far worse (mismatch 2535-10172 against 1191 for a = 1). The dip at walks 3-4 is the 1x1s the first room's
doors lead to (the cells beside the entrance), not a falling rate.

The systematic search (generator.mjs u:, nothing taken from the real maps: the big rooms from the budget,
w=0.39,1,0.83,1,1 s=0.73; 60 random settings on 400 maps, then 16 around the best on 800): the closest is
`u:mode=grow/q=0.05/f=1/c=8/k=3/r=4/b=22/w=0.39,1,0.83,1,1/s=0.73` - the rooms made as the tree grows (start
chance 8 * shapes left / free cells, 3 tries at a random placement over the cell), the first room's doors to
all its neighbours, 4 cells held back. The first room's doors are needed most (mismatch 1897 without them,
1000-1250 with), 2-3 tries beat 1 and "any that fits", held cells still help (1500 maps: 1449 with 4, 3038
without): in a generator that makes the rooms as the tree grows they keep the big rooms off the entrance
(placefit -0.34, without -0.77, real -0.20) and apart (7.08 pairs touching, without 7.90, real 6.86) - they
stand in for something that holds the big rooms back early, not found.

The entrance's door always leads inward (2476 of 2476 real maps); the generator above sent it to a random
neighbour, along the edge 79% of the time (`i=1` fixes it). With the door inward the "bump" at walk 2 (real
0.89 big rooms entered there) comes by itself (0.83-0.95; along the edge 0.64), and so do the room behind the
entrance's doors (a 1x1 there with 4 doors: real 34%, inward 21-28%, along the edge 14%): no doors-first rule
is needed, the walk-2 cells are simply the room behind the entrance's three free sides. The closest inward
setting is `u:mode=grow/k=3/b=22/w=0.39,1,0.83,1,1/s=0.73/f=0/i=1/q=0/p=0.3/c=8/r=5` (newest-first 0.3, the
predictor's own grow; no first-room flood): 1372 on 1200 maps against 1270 for the old one, 777 against 941 on
600 (a tie within the noise). Held cells are still needed there: without them the room behind the entrance is
big 98% of the time (real 71%; r=5 79%, r=7 72%). Still off: the two cells beside the entrance along the edge
(real 1.04 big rooms, 0.75 dead ends; inward 0.73, 0.99), which a generator that places the big rooms before
the tree reaches - another sign the rooms come first.

In the predictor (big rooms placed first, uniformly) the held cells are mostly smoothing: calibrated, the
no-held version scores -1.0405 held-out against -1.0393 with 5 held (raw -1.0460 against -1.0417), so about
70% of their gain is the calibration's (the sampler too sure of big tiles: sharpness 0.75 without, 0.88 with). Still off: going round inside a 2x2
(0.2%, real 11%), the fairy (14-21% on an edge, real 28%), the entrance's pull (-0.34).

Predicting with it (MCMC on a part-known map) needs the chance of a whole map under the generator, a sum over
every order the tree could have grown in: not workable; what carries over (the held cells) is in the
predictor. Tuning the predictor's own settings on the score (grow, reserve, bunch: 18 settings on training
maps, the best confirmed on held-out ones) found nothing better than what it has: the training maps' best
(grow 0.2, reserve 4, bunch 0.1) was worse held-out (-1.0477, real order -1.0707; now -1.0427, -1.0669).

### What the game's code could be doing (Oct 2026)

Every candidate tried, and what it showed:

- Scanning the grid (rows, columns, a corner first): no. `asym.mjs`: turned so the entrance is on the top row,
  maps that needed a mirror and maps that didn't have the same big rooms and dead ends cell by cell (sum of
  z^2 22.8 and 31.6 over 36 cells, about 36 if alike), and so do north/south-entrance maps against east/west
  ones (40.3, 43.2; dead ends, puzzles, blood, the mix and the tree's depth all within 1.5 s.e.). 1x4s run
  north-south more (1632 to 1353) only because they run across the entrance's edge (59% and 57% on either
  kind of map). The one thing that isn't the same every way round is where the entrance goes (north/south 3x
  as often, next to a corner 2x) - it's in the code that picks the entrance, not anything after it.
- The entrance's door always goes inward (2476 of 2476).
- How the big rooms are placed (`placelik.mjs`): the exact chance of each map's 7 big rooms under a
  procedure, summed over every order (a DP over the rooms placed so far), over the runs that succeed and
  whose door tree is kept (dead ends as the map's, blood 9-10 steps out with a 1x1 fairy 4 before it; each
  success rate worked out once per entrance kind, mix and dead-end count from 3000 runs - from a few runs per
  map the log of a noisy small rate favours the procedures that fail most). Self-checked on maps it made
  itself (`GEN`): it picks the procedure they were made with, 5 held cells from 0, one try from many. On the
  2451 real maps (mean log chance, higher better; a door tree newest-first 0.3):

      biggest first, any spot alike (the predictor without held cells)    -17.02
      biggest first, 5 held cells (the predictor)                         -16.86
      random order, any spot                                              -16.91
      random order, 5 held cells                                          -16.65
      one random spot per room, the whole run again if any clashes        -16.64   (every complete layout alike)
      random order, 2 tries per room, 7 held                              -16.61
      a room and a spot drawn together; a top-left cell first; a cell first, then a room: no better

  The same order with the tree oldest-first or plain Prim (-16.07/-15.90/-15.89, -16.65/-16.45/-16.48 for
  biggest first + 5 held / random + 5 held / one try). So the layouts look like random order with the hard
  spots discounted - most simply one random spot per room and start again on a clash - not biggest first.
- But it doesn't predict better (evalbig, 200 held-out maps, 300 samples): one try -1.0572, two -1.0542,
  random order -1.0532, random order + 2 tries -1.0606, against -1.0427 for biggest first + 5 held (-1.0476
  without). Without the tile weights (fitted under biggest first) too: one try -1.0634, 5 held -1.0477,
  nothing -1.0607. Not the weights, not the samples (one try keeps 149 of 300 effective, held 205): the
  held cells do something for a part-known map the layout chance alone doesn't see.
- Special rooms put down first (puzzles, trap, yellow on random cells, big rooms round them, the tree never
  going on from them - 5 held cells is about the 5.2 such rooms a map has): no. sampler2 `opts.special`:
  -1.1224 against -1.0917 on 60 held-out maps; it also passes 10x less often.
- The room mix (`mixrule.mjs`): 7 rooms, 22-24 tiles, never 20-21, never 8 rooms, and 6 rooms only with 22
  (the most 6 can make). Making the map again on every failure, mix and all, doesn't give the 22s by itself
  (placelik MIXRATE: 29-32% of draws at 22 against 88%). Of the rules tried, "random shapes until 22 tiles,
  more than 7 rooms and draw again" is closest (-2.46 per map with the one-try success rates, the best
  possible -2.415; 4-tile shapes drawn less, a second copy x0.7), but it still gives 23 tiles 15-17% (real
  11%); "7 rooms, again unless 22+" -2.62; "put back a shape that would pass 22" -2.57 (it makes 8-room maps).
- Merging cells into rooms: no - an L made that way would miss its north-west cell a quarter of the time.
- The door tree's rule (treelik.mjs: the chance a tree3.mjs rule makes each real map's doors and blood given
  its layout; a 2x2's inside is now summed over, not drawn - with a drawn one every particle could end on the
  wrong inside, which scored some maps at the floor with peaked rules). Two things the growing-tree rules missed:
  - the door to a room sits near where its parent was entered (treelik STATS=1, with GEN= for a rule's own
    trees): into a big room with two shared sides the one nearer the parent's entry cell 67% (room/node/0.15/0.1
    51%, the rule below 65%), with three 70% (37%, 52%). Into a 1x1 in an L's notch it doesn't (51%; 46%, 70%),
    and children per big-room cell into 1x1s don't fall off with the distance (they do into big rooms), but as
    a likelihood the pull on every side is best: each free side weighs exp(-beta x its cell's steps from the
    room's entry cell, over the room's cells), beta 0.5 - something about doors into big rooms not yet found;
  - a room of the tree is picked by the square root of its free sides (alpha 0.5; alike, room/node, and by
    them, room/side, both worse), and a side the nearer its new cell is to the entrance the likelier
    (exp(-0.2 x grid steps), mu). Newest/oldest first (p, q) then make no difference.
  On the first 300 maps of layouts-snap2 (NP=2000): room/node/0/0/alpha=0.5/beta=0.5/mu=0.2 -14.87 a map,
  room/node/0/0/alpha=0.5/beta=0.5 -14.96, room/node/0/0/beta=0.5 -15.00, room/node/0.15/0.1 -15.36,
  room/side/0.15/0.1 -15.41 (the best +0.49 over room/node/0.15/0.1, s.e. 0.08; on 300 maps that aren't
  evalbig's held-out ones, IDS=, +0.50, s.e. 0.08). Tried and no better (NP=500-1000, paired): beta only into
  big rooms or only within a room pair (bb, b1, pw: -0.1 to -0.3), a node's doors so far (delta), its depth
  (gamma), its size (eta), a cell of it first (wc, cw), the room behind the entrance weighted (f1), a random
  direction tried per room (alpha=1/eta=-1: -0.5), sides waiting longer (ag, ae: +0.02 to +0.05, within the
  noise), a cell-level tree with a room's own sides weighted (lam=3 as good as beta, the rest worse), the walk
  from the entrance (kappa), the growth starting in a random room (start=room: trees far too deep). mu came from
  scorediag.mjs (RULE=: how much the mean log chance would rise per unit weight on each of 22 side features,
  over the growth orders the real tree could have had, less the same over kept free runs): the new cell's grid
  steps from the entrance stood out (z -8); of the others (z 2-5) those tried (kappa, gamma, delta, eta, f1)
  came to little.
- In sampler3 the rule matters at the late stages only with enough samples, and samples were what it lacked:
  a batch of particles all descended from one or two layouts could fill the 300 samples alone (snapshots with
  4-5 layouts in their samples and the truth in none, -1.8 to -2.3 a tile). It now goes on until 20 batches'
  worth of weight ((sum w)^2 / sum w^2 over the batches), sums blood, the fairy and the specials over a 2x2's
  insides, the blood spots and the shuffle instead of drawing them, and lets 5-puzzle maps have 7 to 10 dead
  ends besides blood (9 in 7 of 294 recorded maps, 10 in 2; at most 8 left those snapshots with no sample).
  Dropping trees as soon as their dead-end count is out of reach (deadkill=1) does worse: the batches collapse
  sooner; so do 5 trees per layout resampled only among themselves. evalbig, paired against sampler3 as it was
  (game:room/side/0.15/0.1/inside=draw/types=draw/batches=0/dmax=8):
  held-out maps 0-99 at 9 and 12 rooms known (300 samples) - as it was -0.9340 a tile (another run of the old
  code -0.9249), res5 -0.8878 (+0.046, s.e. 0.013), room/side/0.15/0.1 with the new sampling -0.8839 (+0.050,
  s.e. 0.011), room/node/0/0/alpha=0.5/beta=0.5/mu=0.2 with it -0.8708 (+0.063, s.e. 0.011; +0.017 over res5,
  +0.013 over the old rule at the same sampling, s.e. 0.007; at batches of 100, room/node/0.15/0.1 -0.020
  against it, s.e. 0.007, and without mu -0.001).
  On held-out maps 100-199 (OFFSET=100, all stages): as it was -1.0466, res5 -1.0471, now -1.0307 (+0.016 over
  both, s.e. 0.004 / 0.005; stages 2/4/6/9/12: -1.128/-1.103/-1.038/-0.923/-0.779 against res5's
  -1.141/-1.116/-1.054/-0.951/-0.796), with about the same time as before (29 min against 25 for 500 snapshots).
- Four changes written against the old sampler3 (specials, mix, efficiency, rules) are in it as options, each a
  rule name=value (evalbig game:room/node/0/0/alpha=0.5/beta=0.5/mu=0.2/prune=2/move=1; the list in sampler3's
  header), not yet measured at high sample counts. Two fix the model and are on (=0 turns them off): the mixes
  with 6 big rooms (10 of 1198 training maps; evalbig now hands sampler3 mixesFor(train)), and the mix weight
  times c!/(c-k)! for k seen rooms of a shape the mix has c of (the game places c labelled rooms, the sampler
  only the c - k unseen ones). The rest are off: prune=1/2 (the dead ends out of reach, counting the map's walls
  - deadkill=1 without them did worse; 2 also blood out of reach, on every 2x2 inside), distinct, move (big
  rooms the tree hasn't come next to moved after a resample, an MCMC move that keeps the target), rarew,
  ess, endrs, joined, lshare, legacy (sampler3 as first written). With mixes6=0/mixfac=0 the numbers are
  f7ef0ba's to the last digit (evalbig's per-snapshot DUMPLL, 6 maps, 9 and 12 known; legacy=1 matches
  room/side/0.15/0.1/inside=draw/types=draw/batches=0/dmax=8 too). prune=2 and joined=1 drop only trees
  finishTree would drop (opts.check: of 715537 marked finished trees on 100 snapshots, it kept none). Exact
  speedups always on: a bit-mask placement prefilter, each room's shown doors listed for mapOk, tree3 skipping
  nodes with no free side left, and no steps after every tree is done.

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
