# Storm's crush check, from the recorded runs

What decides whether a pillar crushes Storm (F7 phase 2), measured from the Better PF recordings,
and what the **Storm Phase** module draws because of it. The rule itself is `StormCrush.kt`; the
measurement is `tools/storm-crush/crush.py` (re-run it when there is more data - see the end).

## Data

Every F7 run on the site long enough to reach the boss (278 recordings, 2026-09-23 to 09-29).
248 reach Storm. 157 recordings from 132 runs have crush lines (299 lines), and 211 crushes
have Storm in view when he was pinned (with the several recordings of one run counted once).
Most crushes are on Yellow (127) and Purple (80); only 4 are on Green.

Two things about the recordings matter here:

- **Server ticks.** `st` lines count Odin's server ticks (one per ping the server sends). A line's
  count is the one at the start of the client tick it arrived in, so any two lines can be a
  tick apart, one way or the other. All the timings below are in server ticks.
- **Storm's position is the client's.** The client slides a mob to each position the server sends
  over 3 ticks, so what was recorded trails the server by about 3 ticks. The server sends Storm's
  position only every 2-3 ticks, so a moving Storm is uncertain by about half a block. A pinned
  Storm is exact: after a crush he holds still, and the client settles on exactly where the
  server had him, 3 ticks after the crush line in 191 of 232 cases.

## The four pillars

Each crusher is a rounded 7x7 pillar of polished diorite (37 blocks a layer, rows of
3-5-7-7-7-5-3) hanging from y 205, colour-coded by the terracotta floor under it. Pistons step
the whole pillar down one block every 4 ticks. A step shows as the piston column
(the square's middle) turning to moving pistons. Resetting, the extended layers turn back to
air.

| pillar | 7x7 square (x, z) | crushes recorded |
|---|---|---|
| Purple | 97-103, 62-68 | 80 |
| Yellow | 43-49, 62-68 | 127 |
| Green | 43-49, 38-44 | 4 |
| Red | 97-103, 38-44 | never moved in F7 |

## When: every 20 server ticks from the phase start

- Crush lines land on a 20-tick grid. Two crushes of one run are 0 mod 20 apart, ±1 tick,
  in 133 of 142 pairs.
- Against Storm's first line ("Pathetic Maxor, just like expected."), crush lines arrive at
  19 mod 20: 132 at 19, 68 at 18, 72 at 0, 11 each at 17 and 1. That is 91% within a tick of 19.
- Against Storm's wither appearing, the grid is 0 mod 20 (81 at 0, 34 at 1, 26 at 19). The wither
  appears a tick before his first line.

So the phase's clock starts with his wither, a tick before the first line, and the crush
checks are its 20th, 40th, 60th... ticks. The module counts that way: the first line reads 1, and
with **Modulo 20** on, every check is a rollover to 0. **Tick Offset** moves the count, and the
ticks it treats as checks, if testing in game disagrees.

The lightning ("ENERGY HEED MY CALL!" / "THUNDER LET ME BE YOUR CATALYST!") comes 546-552 server
ticks after the first line, and not on the grid (6-12 mod 20).

## Where: Storm's position in a 6x6 square, his head in the pillar

On a check, a pillar crushes Storm when all three of these hold:

1. **Sideways:** his position (his feet, x and z) is inside the pillar's **crush zone**, the 6x6
   square `[minX, minX + 6] x [minZ, minZ + 6]`. The pillar itself is 7x7, so the zone is the pillar
   shrunk by one block on its +x and +z sides. It is centred on the pillar's middle block's
   corner rather than its centre, as if the check used the middle block's coordinates as the
   pillar's centre. Yellow's middle block is (46, 65), and the check is `|x - 46| <= 3` and
   `|z - 65| <= 3`.
   - All 211 crush positions are inside it, down to 0.012 blocks from the -x edge. None are
     past the +x/+z edges (the furthest reached 5.875 of 6), although the pillar's own blocks go
     a whole block further.
   - 8 crushes were in the square's rounded corners, where the pillar has no block. So the zone
     is a square, not the pillar's blocks.
   - Three checks in one run, with Storm holding still at 3.56 from Green's centre, didn't crush him. They
     were outside the square (3.25 along z), while crushes happened up to 3.62 from the centre
     along a diagonal. So it is a square, not a circle.
2. **Up:** his **head**, 2.975 above his feet (a wither's eye height), is at or above the pillar's
   lowest block.
   - Crushed with his feet up to 2.947 below the pillar's bottom. Not crushed at 3.104 below (holding
     still, inside the zone), nor at 3.4.
   - So the head sits between 2.947 and 3.10 above his feet. That rules out the top of the vanilla
     hitbox (3.5 tall).
   - Above the bottom there is no limit: crushes happened with his head up to 13 blocks into the
     pillar. It is one solid column up to y 205.
3. **Recently lowered:** the pillar stepped down within the last **60 ticks** (3 checks).
   - Crushes came 3-4, 23-24 and 43-44 ticks after a pillar's last step, and once at 60.
   - Checks with Storm holding still inside, head in the pillar, 63 and 64 ticks after its last
     step did not crush him, and neither did six later ones.

Checks with Storm holding still, inside a zone with his head in the pillar, each run counted once
(from `crush.py`):

| ticks since the pillar's last step | crushed | not crushed |
|---|---|---|
| 0-9 | 26 | 1 |
| 10-19 | 2 | 0 |
| 20-29 | 32 | 0 |
| 30-39 | 2 | 0 |
| 40-49 | 38 | 0 |
| 60-69 | 0 | 2 |
| 80+ | 0 | 6 |

The one miss the rule does not explain is run `20260926-030223-c2fe8de8`, a check 6 ticks after
Yellow's last step. The pillar had just pushed Storm onto the floor and he wasn't crushed until
the next check. That is one case in 101. Every other check with him in place, up to 60 ticks
after the pillar's last step, crushed him.

## The hitbox the module draws

"His position inside a 6x6 zone" can be stated the other way round. Put a 1x1 column with its
-x/-z corner on his position; he is inside the zone exactly when that column fits completely
inside the pillar's 7x7 square, rounded corners included. Make the column 2.975 tall and its top
is his head. So **Storm Phase** draws that column as Storm's crush hitbox, and the pillar's square
at its bottom:

- Crushed: the hitbox is completely inside the square outline, and its top reaches the outline.
- Green: he was inside on that check. Red: he wasn't.
- The label says by how much: sideways in/out of the zone, and his head above or below the
  pillar's bottom. It adds "(not lowered)" when that pillar hadn't stepped down in the last 60
  ticks.

The hitbox is drawn from the lightning on, at every check with Storm within 5 blocks of a crush
zone, for 5 seconds. It uses the last position the server sent for Storm, not where his wither is
drawn, which trails it by 3 ticks. With the server's 2-3 tick updates a moving Storm can still be
a tick stale.

## Better data would settle

- The one unexplained miss. More checks with a pillar still coming down onto a pinned Storm would
  show whether a step in progress blocks the crush.
- The +z edge. No check had Storm near it with a lowered pillar. It is assumed to match +x
  (6, not 7).
- Green and Red. There are 4 Green crushes and no Red ones, so both are assumed to work like
  the other two.
- Sub-tick timing. With Odin's tick count written as each line arrives (instead of once per
  client tick), and Storm's server positions instead of the client's, the ±1 tick and
  ±0.5 block uncertainty above would go away.

## Re-running

```
python3 tools/storm-crush/crush.py fetch   /tmp/storm-data   # every F7 run that reaches the boss
python3 tools/storm-crush/crush.py analyze /tmp/storm-data   # the numbers above
```
