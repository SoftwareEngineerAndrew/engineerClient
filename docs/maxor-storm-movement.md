# How Maxor and Storm move (F7 P1/P2), and the fastest possible Storm

Measured from the Better PF recordings (2026-09-23 to 09-29). The scripts are in
`tools/boss-movement/` (see its README); every number here is printed by `analyze.py` or
`fit.py`. Storm's crush rule (the 20-tick checks, the crush zone, head height, "stepped down in
the last 60 ticks") was measured separately and is in [`docs/storm-crush.md`](storm-crush.md) /
`StormCrush.kt`. It is used here, not re-derived.

> **Alpha-server runs are disregarded.** Four recordings (`b120f4c1`, `33c77ab5`, `1f5e9ba7`,
> `24f19367`, all 1-3-player runs) were made on Hypixel's alpha server, where Storm leaves his
> spot about 40 ticks early after the lightning. They are not the live game, so no conclusion
> here rests on them. The scripts now leave them out (`recording.ALPHA_RUNS`). The fastest-split
> analysis (§4) only ever used 5-player runs, so it never included them; where earlier sections
> saw their early departure it was reported apart (and is now marked as the alpha server).
> §3.5 and §4.4 were redone without them.

Times are **server ticks** (20 a second) counted from the boss's first line ("WELL! WELL! WELL!
LOOK WHO'S HERE!" for Maxor, "Pathetic Maxor, just like expected." for Storm) unless said
otherwise. "p10-p90" is the middle 80% of runs.

## Summary

- **Both bosses fly straight at the 3D-closest living player.** Where the 3D-closest and the
  horizontally-closest players differ, the heading matches the 3D one to a median of 2-3° and
  misses the horizontal one by 110-135°. Speed grows with the distance to that player, about
  `0.2 + 0.021·d` blocks/tick, capped at 0.9.
- **Maxor** has inertia (his velocity eases toward that pursuit velocity, about 22% a tick).
  He hovers about 0.9 above the player's feet and stops about 2.7 blocks short horizontally.
  A laser stun freezes him in place until "⚠ Maxor is enraged! ⚠"; he moves again 0-6 ticks
  after that line. He is not vanilla 1.8 wither AI: vanilla constants predict 45% worse.
- **Storm** follows a fixed script until his lightning, identical in every run:
  - He spawns at (103, 188, 53) one tick before his first line.
  - He flies a diamond through the waypoints (73,183,83) → (43,183,53) → (73,183,23) →
    (103,183,53) at exactly 0.40 blocks/tick, one move per server tick.
  - He parks at (102.375, 183, 52.375) from t≈424, 0.88 blocks short of the last waypoint.
  - The lightning line comes at t 548 (546-552). He leaves the spot at t 687, which is 139
    (138-140) ticks after that line.
- **After the lightning** Storm chases the 3D-closest player at up to 0.90 blocks/tick. He
  aims about 3 blocks above their feet.
- **Crushes.** A crush pins him until "⚠ Storm is enraged! ⚠"; he moves 1-4 ticks after that
  line. The pin lasts 0-183 ticks (median 10). 2-3 ticks after the enrage line he flies to the
  next pillar whatever the players do: Purple → a point over Yellow (46, ~172.8, 65), Yellow →
  Green, Green → Yellow. Red is never used. That flight is one 0.7157-block move a server tick,
  slowing inside ~26 blocks of the point. About 4.6% of ticks are skipped for no reason found
  (§3.5). For the last ~5 blocks he chases the closest player again.
- **Fastest Storm split ≈ 900 server ticks (45.0 s): crush 1 at 699, crush 2 at 799, dead at
  799-800, Goldor's line 101-102 ticks later.**
  - Everything else is fixed dialogue or fixed movement. The best recorded run is 901; ten
    runs made 901-903.
  - Crush 2 a check earlier (779) would need a pin of ≤2 ticks *and* a flight in the fastest
    few percent. The flight to Yellow takes 87 ticks (83-92) after Storm starts moving, 76 at
    best. No run did it: the earliest zone entry was 81 ticks after crush 1. That would be
    Goldor at ~880; see §4.4 (possible, about 1 in 15 with a perfect start).
  - Where the median run loses time: crush 2 misses the +100 check in 51% of 5-player runs
    (median +18, 4,819 ticks in total). 39 of those 66 misses had a pin over 15 ticks; others
    arrived slightly late or had his head below Yellow's bottom. The kill after crush 2 costs
    another median 5.
- **Data caveats.**
  - Positions in the recordings are the client's 3-tick interpolation of what the server
    sent; I undo it (recovered positions from two recordings of one run agree to 0.015 blocks).
  - 85 of 256 recordings have no server ticks; timing statistics use only the 146 runs whose
    every recording has them.
  - Other players' positions are the recorder's view: interpolated, and stale when out of
    range.
  - Whatever ends Storm's pin and kills him (damage, presumably) is not in the recordings: no
    health is recorded.

## 1. Data

### Used

| | count |
|---|---|
| recordings in `ids.txt` (all downloaded) | 278 |
| ... with a Maxor or Storm line | 256 (22 never reached the boss) |
| runs (recordings grouped by `group`) | 212 (170 with one recording, 40 with two, 2 with three) |
| runs where every recording has server ticks (`st` lines) | 146 |
| runs with Maxor's wither identified | 211 (all by his name tag or his fixed spawn point) |
| runs with Storm's wither identified | 204 (180 by tag / spawn at (103,188,53), 24 as the first wither seen above y 175 during his phase) |
| 5-player server-timed runs from Storm's first line to Goldor's | 129 |

Recorder versions: 0.6.11-0.6.13 (237 recordings), 0.6.14-0.6.20 (19). All but one wrote
positions to 1/1000 of a block; the newest wrote 1/100 and, beyond 32 blocks, only every other
tick.

### What a recorded boss position is, and how I got the server's back

- **The client interpolates.** The recorder writes the client's entity position at the end of
  each client tick. A modern client does not jump a mob to a new server position: each move
  packet starts a 3-step lerp (`pos += (target - pos) / stepsLeft`). The recorded track is
  therefore smoothed and trails the server by 1-3 ticks.
- **The lerp can be undone.** On the tick a packet arrives, `recorded = previous + (T - previous)/3`,
  so `T = previous + 3·(recorded - previous)`. Ticks that follow the running lerp are not packets
  (`bosslib.delerp`).
- **Check: Maxor.** His recovered positions sit on the 1/32-block grid of Hypixel's 1.8
  protocol: rms distance from the grid is 0.034 grid units (p90 0.17), where uniform would be
  0.289. That confirms the 3-step lerp model.
- **Check: Storm.** His recovered positions are *not* on the 1/32 grid (0.28, i.e. uniform),
  though his spawn positions are. Yet two recordings of the same run recover the same positions
  to about 0.015 blocks and the same tick (±1). So Storm's moves are sent at finer precision
  than 1/32 (1/4096 relative moves presumably); Maxor's are not.
- **Cadence.** Positions arrive about every 2 server ticks while a boss moves (gaps of 1/2/3
  ticks: Maxor 25/57/15%, Storm 21/56/21%; the 1s and 3s are arrival jitter around 2). They
  never come every tick. One packet usually carries two ticks of movement.
  - 1-2% of consecutive packets carry the same server-tick count: they arrived in different
    client ticks during one server tick.
  - Two packets arriving in the *same* client tick cannot be told apart; only the last is
    visible.
- **Rotation.** Yaw comes in 1/256 turns (1.4°). Storm's yaw points along his motion (median
  2.1° off); Maxor's lags his turns (9.4°).

### Timelines and alignment

- `t` (client ticks) runs ahead of `n` (server ticks, Odin's per-tick ping) while the server
  lags. Recordings from before late 0.6.13 have no `st` lines: 85 recordings, so 66 runs are on
  client ticks only.
- **Transfer speed per server tick is steadier than per client tick** (between-run sd 0.043 vs
  0.065 blocks/tick), so server ticks are the right clock. They are used for everything timed.
- **Siblings (several recordings of one run)** are put on the reference recording's server
  ticks. The offset comes from the ~20 boss lines both recorded; the lines agree to ±1 tick
  (30 pairs) or ±2 (14). On client ticks it is ±1 except one pair with a 14-tick client stall.
- **Merging.** The uploader drops a mob from a recording when a sibling already has as many
  events for it, so a boss's track can be split between siblings. Tracks are merged by entity
  id, and a move packet seen by two recordings (within 2 ticks and 0.06 blocks) is kept once.

### Thrown out or handled

| what | why / how |
|---|---|
| recordings without server ticks (85) | client ticks run fast when the server lags (one run had a 14-tick stall); kept for position and path analysis, left out of every timing statistic |
| 49 other withers first seen at y 160-180 during Storm (median 447 ticks after his first line) | no Storm tag, moving ~0.2-0.25 blocks/tick near the floor, not at Storm's spawn. Players in this phase die "killed by Wither Guard", so these are probably those adds. Storm is always the tagged wither, or the one at his spawn point, or the first one above y 175 |
| boss out of the recorder's view | a `gone` line and later a new `spawn` line. The de-lerp restarts at each spawn line (its position is exact), and every statistic that needs the boss in view checks the view spans (e.g. Storm's departure time is taken only when he was in view for the 12 ticks before) |
| player positions | the recorder's own position is exact. Everyone else is the recorder's view: interpolated like mobs, and frozen at the last value when out of range. Each player is taken from their own recording when the run has one |
| dead players | " ☠ X ... became a ghost." / " ❣ X was revived by Y!" / " ☠ X reconnected." bound ghost spans; ghosts are never targets |
| teleports (leaps, etherwarp) | the closest player is recomputed every tick, so a leap shows as an instant target switch |
| practice runs with 1-3 players | kept for movement, left out of the fastest-split analysis |
| batched or lagged packets | a packet arriving 1 tick late looks like 0.72 blocks in 1 tick followed by 0.72 in 3. Speeds are averaged over ≥4 ticks or fitted over 16-tick horizons |

## 2. Maxor

### Timeline

| event | ticks after "WELL! WELL! WELL!" (p10-p90) |
|---|---|
| spawns at (73, 226, 53), holds still through the intro | 0 (all 145 runs with him in view) |
| "I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU." | 62 (61-63) |
| "DON'T DISAPPOINT ME, ..." | 124 (123-125) |
| first move | 46 after that line: 46 in 60 runs, 45 in 21, 47 in 19, 48 in 3. `SubSplits.MAXOR_MOVE_TICKS = 46` confirmed. Four runs (three of them 1-3-player practice runs) showed him moving at -1..+1, cause unknown |
| first laser stun line ("THAT BEAM! IT HURTS!" / "YOU TRICKED ME!") | 212 / 240 median, earliest 204-205 (p10 205) |
| "⚠ Maxor is enraged! ⚠" (end of the stun) | 346 (216-444) |
| second stun line | 406-416 (p10 405) |
| "I'M TOO YOUNG TO DIE AGAIN!" (said in 79 of 146) | 489 (487-822) |
| Storm's first line | 518 (510-732) |

### Stuns

- **Where he is stunned.** Always at the lasers' meeting point: 168 of 175 stuns caught his feet
  within x 71-75, z 71-76, y 224-227, most at (73-74, 226, 72-75). He gets there 204-206 ticks
  in at the earliest (about 35 ticks of flight from his spawn, 20.5 blocks).
- **He freezes on the stun line and stays put.** He moves again 0-6 ticks after "⚠ Maxor is
  enraged! ⚠" (median 3, n=90).
- **Stun length (stun line → enrage line).** Median 126 ticks, p10 9, p90 170, range 4-239. It
  is bimodal: 9-16 ticks in some parties, 110-190 in most. That looks like a damage threshold
  with a timeout, but the recordings have no health to prove it.

### Targeting

Heading (least-squares velocity over ±4 ticks) against the bearing to candidates, 3,350 samples
while he moved at ≥0.2 blocks/tick:

| candidate | median error | within 10° |
|---|---|---|
| 3D-closest living player | 3.5° | 81% |
| horizontally-closest | 3.7° | 78% |
| ...where those two differ (120 samples): 3D | **2.1°** | 87% |
| ...where those two differ: horizontal | 133.5° | 3% |

So he targets the **3D-closest living player** (feet position, distance in 3D).

### Speed and height

Speed per server tick against the 3D distance to that player (one recording per run, packets
1-4 ticks apart):

| d (blocks) | 0-4 | 4-8 | 8-12 | 12-16 | 16-20 | 20-24 | 24-28 | 28-32 | 32-36 | 44-48 |
|---|---|---|---|---|---|---|---|---|---|---|
| median speed | 0.22 | 0.32 | 0.43 | 0.52 | 0.61 | 0.67 | 0.73 | 0.82 | 0.86 | 0.90 |

- **Speed.** About `min(0.9, 0.19 + 0.021·d)`, the same profile as Storm's chase (§3.3). It is
  not constant, and not capped per axis: the direction is the straight 3D line (errors above).
- **Height.** Vertical speed against (player y - Maxor y): -12 → -0.45, -6 → -0.20,
  -3 → -0.06, 0 → 0.00, +3 → +0.15, +6 → +0.25, +12 → +0.36. When he stands still next to his
  target, he is 0.9 (0.2-1.8) above their feet.
- **Stopping.** He stops 2.7 blocks horizontally from them (p10 2.0, p90 15.5; n=43; the high
  end is a stale position of a player out of view). The 3-block horizontal stop matches
  vanilla's `d² > 9` rule, but the rest does not.

### Model

Multi-step prediction: start at each recovered position with the velocity of the last 2-6
ticks, run the model against the real target trajectory, and compare with the positions the
server sent 4/8/12/16 ticks later. 2,015 starts, 9,764 comparisons, rms error in blocks:

| model | h4 | h8 | h12 | h16 |
|---|---|---|---|---|
| constant velocity (baseline) | 0.82 | 1.58 | 2.60 | 3.70 |
| constant speed 0.475 + stop 4.6, aim +0.9 | 0.85 | 1.42 | 1.94 | 2.36 |
| speed = min(0.645, 0.10 + 0.031·d), aim +0.9 | 0.75 | 1.23 | 1.67 | 2.00 |
| **inertia (below)** | **0.66** | **1.07** | **1.52** | **1.90** |
| vanilla 1.8 `EntityWither` with vanilla constants (pull 0.5, lerp 0.6, friction 0.91, stop 3, gravity) | 0.91 | 1.62 | 2.29 | 2.76 |
| vanilla structure, constants fitted (pull 0.59, lerp 0.25, stop 6.6, hover 0.75) | 0.75 | 1.27 | 1.85 | 2.35 |

The noise floor is about 0.5-0.7 blocks: arrival jitter of ±1 tick at 0.5-0.9 blocks/tick.

```
// Maxor, one server tick. pos, vel: Vec3. Starts at (73, 226, 53), vel 0, on "WELL! WELL! WELL!".
if (stunned) return                                   // stun line .. "Maxor is enraged!" (+0..6 ticks)
if (tick < intro_end_line + 46) return                // holds still through his intro
target = 3D-closest living player (feet)
aim    = target + (0, 0.9, 0)
d      = |aim - pos|
want   = if (horizontalDistance(aim, pos) < 2.7) 0 else min(1.05, 0.19 + 0.021 * d)
vel   += (unit(aim - pos) * want - vel) * 0.225
pos   += vel
```

Fitted constants (grid then coordinate descent): a = 0.225, c = 0.19, k = 0.021, vmax = 1.05,
aim +0.875. The 2.7 stop is from the standstill measurements. No runs had Maxor far enough away
to pin the cap down; above 36 blocks the medians sit at 0.88-0.90.

## 3. Storm

### Timeline

| event | ticks after "Pathetic Maxor, just like expected." (p10-p90, min-max) |
|---|---|
| his wither spawns at (103, 188, 53) | -1 (66 runs), 0 (20), -2 (16) |
| "Don't boast ..." / "My abilities ..." / "The memory of your death ..." | 62 / 124 / 186 (±1) |
| parks at (102.375, 183.0, 52.375) | 424 (422-427 in runs where he was in view; later values are him coming into view) |
| "The power of lightning ..." / "I'd be happy to show you ..." | 424 / 486 (±1) |
| lightning: "ENERGY HEED MY CALL!" or "THUNDER LET ME BE YOUR CATALYST!" (50/50) | **548 (547-549, 546-552)**, off the 20-tick grid |
| leaves the parking spot | **687 (686-688)** = lightning + **139 (138-140)**, n=110 (the 98-113 seen in 4 runs was the alpha server) |
| crush 1 ("Oof" / "Ouch, that hurt!") | 700 (698-839); 95% of 5-player runs within 5 ticks of 699 |
| "⚠ Storm is enraged! ⚠" | crush 1 + 10 (1-29, 0-183) |
| crush 2 | 800 (798-860) |
| "I should have known that I stood no chance." | 823 (802-927) |
| "At least my son died by your hands." | death + 62 |
| Goldor's "Who dares trespass into my domain?" | death + 102 (101-103) |

Crush lines land on the 20-tick grid, t ≡ 19 (mod 20), per `storm-crush.md`.

### 3.1 Opening: a fixed waypoint route, not a circle

Storm flies straight at each waypoint in turn at **exactly 0.40 blocks per server tick**, 3D:

```
spawn (103, 188, 53)  at t = -1
  -> (73, 183, 83) -> (43, 183, 53) -> (73, 183, 23) -> (103, 183, 53)
```

- **The route.** A diamond (a square turned 45°) centred on (73, 53), vertices 30 blocks out
  along the axes, plus the 5-block descent on the first leg. 170.0 blocks = 425 ticks.
- **Speed.** Every packet step is a whole number of 0.40-block moves: rms off a whole number
  0.036 of a step, 0.978 moves per server tick.
- **Turns.** Instant. He heads for the next waypoint once within about 1 block of the current
  one, so each leg bends by ≤1 block (the x−z drift after the first corner is a straight line
  from ~1 block before the vertex).
- **Parking.** He stops 0.88 blocks short of the last waypoint, at (102.375, 183.0, 52.375);
  the first position within 1 block ends the route.
- **Same every run.** Placing the path at 0.4·(t+1) blocks along it fits every packet of 128
  server-timed runs with median error 0.23 blocks (p90 1.1, near the corners). The best time
  offset is 0 ticks in 80 runs and ±1 in 46. Players do not affect it.

### 3.2 Lightning and departure

- **He sits still for ~263 ticks,** through the lightning line at 548, and leaves at
  lightning + 139. No move packets come while parked.
- **Short lightning phases were the alpha server.** The 4 runs that left at lightning + 98-113
  (one with crush 1 at t 659) were recorded on Hypixel's alpha server; disregard them. Every
  live run leaves at lightning + 138-143.

### 3.3 Chase (lightning + 139 until a crush)

- **Target: the 3D-closest living player.** Where the 3D- and horizontally-closest differ (353
  samples): 2.7° median vs 111° for the horizontal one. Overall 3.8° median, 74% within 10°.
- **Height.** He aims about **3 blocks above the target's feet**: his vertical speed is 0 when
  they are 3 blocks below him. With the usual lure he descends at ~0.3 blocks/tick.
- **Speed** against 3D distance to the target:

| d | 8-12 | 12-16 | 16-20 | 20-24 | 24-28 | 28-32 | 32-36 | 36-40 | 40-44 |
|---|---|---|---|---|---|---|---|---|---|
| median | 0.44 | 0.56 | 0.67 | 0.69 | 0.78 | 0.83 | 0.85 | 0.88 | 0.90 |

  That is ≈ `min(0.90, 0.2 + 0.023·d)`. He leaves the parking spot at full speed (the first
  packet after departure already shows ~0.9/tick 3D: 0.84 horizontal, -0.3 vertical); no
  acceleration is visible. Turns are instant (yaw within 2° of the motion).
- **In practice** the lure is 35-45 blocks away, so he flies at 0.9 blocks/tick, crosses
  Purple's crush zone ~11 ticks after leaving (t ≈ 697-698) and is crushed on the t 699 check.

### 3.4 Crush and pin

- **The rule** is `storm-crush.md`: checks every 20 ticks; feet in the pillar's 6x6 zone; head
  (y + 2.975) at or above the pillar's lowest block; pillar stepped down in the last 60 ticks.
- **Not crushed again while pinned.** In 7 runs he was still pinned in Purple's zone at the next
  check (t 719), head in the pillar, Purple stepped 41-45 ticks before. The rule says crush; he
  wasn't crushed. So a pinned Storm is immune, or the pillar that crushed him is spent.
- **A pillar stepping onto him pushes him down.** He does not collide with pillars: he flies
  into them (`storm-crush.md` saw crushes with his head up to 13 blocks inside).
  - But when a pillar *steps down* while his hitbox (y to y + 3.5) reaches into the new layer,
    he is pushed down by the overlap. In 47 such steps with him off the floor, he dropped a
    median 1.00 block over the next 5 ticks (p10 1.56, p90 0.55), about the overlap (median
    0.98). On the floor he cannot drop.
  - That leaves his head 0.525 below the pillar's bottom, so that pillar cannot crush him.
    **Pillars have to be down before he gets there.**
- **Pinned.** He holds still where the check caught him.
- **Break free.** He moves again **1-4 ticks after "⚠ Storm is enraged! ⚠"** (median 2,
  n=107).
- **Pin length (crush 1 → enrage).** Median 10, p10 1, p90 29, max 183.
- **What ends the pin is not in the data.** Recordings carry no health. It does not correlate
  with the same party's Maxor stun length (Spearman 0.03, n=130), and only weakly with how long
  they take to kill him after crush 2 (0.31, n=117).
- **Taunt lines are not events.** "Slowing me down will be your greatest accomplishment!",
  "BEGONE PILLAR!", "THAT WAS ONLY IN MY WAY!", "This factory is too small for me!" and six
  others come from a random taunt pool.
  - The first taunt comes 197 ticks (p10 61, p90 201) after crush 1, the next ones 60-63 ticks
    apart. Storm is usually not in a crush zone then and no pillar is moving.
  - So `BossDetail.STORM_FREE` ("Slowing me down..." = broke free) is not a break-free signal:
    the enrage line is.

### 3.5 After a crush: the flight to the next pillar, move by move

Redone on the 141 live Purple crush-1 flights on server ticks (`transfer.py`), from the positions
the server sent (de-lerped), after dropping the 0.02-block fake moves that undoing the lerp makes
out of the recorder's rounding.

- **Which pillar.** Purple → Yellow in every live run with a Purple crush 1. (Yellow → Green and
  Green → Yellow were seen 5 and 2 times.) Red never moves in F7.
- **When he sets off: 2-3 ticks after "⚠ Storm is enraged! ⚠"** (86 of 141; p10-p90 2-7). The
  enrage line itself is what the players control (the pin, crush line → enrage line: min 0,
  p10 1, median 10). Before it he holds still where the check caught him.
- **Where he flies: straight at (46, ~172.8, 65)**, Yellow's middle column and about 3 blocks
  above the floor. Fitting a line to each flight from x 96 to 68, the lines meet x 46 at z
  64-67 (median 65.3) and y ~172.8. So he aims at a point, not along a fixed axis, give or take
  about 0.7 blocks sideways (0.8°). Players do not change the direction (heading error to the
  point 4-9° beyond x 56, to the closest player 29-45°).
- **How fast: whole moves of 0.7157 blocks (3D), one a server tick,** while he is more than
  ~26 blocks (horizontally) from the point.
  - Packets come every ~2 ticks and carry 2 moves; the per-move length far out is 0.715 median
    (0.706-0.723 middle half).
  - Per-tick timing is exact: over x 94 → 74 (~28 ticks), the ticks a flight loses against
    one move a tick come out as whole numbers. 29 flights lost 0, 33 lost 1, 22 lost 2, 16 lost
    3, 16 lost 4 or more.
- **Slowing near Yellow:** his step shrinks from 0.716 at ~26 blocks to 0.41 at 4 blocks,
  about `min(0.7157, 0.36 + 0.0134·d)` with d the horizontal distance to the point (±0.03).
  It depends on the distance to the point, not to any player. From x 72 to the zone's edge at
  x 49 that takes 37.6 ticks at best, 40.6 at p10, 43.4 median.
- **The last ~5 blocks are a chase.** From about x 51 (5 blocks from the point) he heads for
  the 3D-closest player instead (heading error at x 44-50: 17° to the player, 60° to the point).
  So where the bait stands sets the last blocks. From x 53 to 49 it takes a median 9.1 ticks
  (best 6.6) with the bait at x 30-37 west of Yellow. It takes 10.7 with the bait at x 37-43,
  and 19.4 with them standing on Yellow (x 43-50).
- **Skipped moves (not explained).** Besides the whole ticks lost above, 26 of 152 flights have
  a stretch of 5-57 ticks at exactly half speed: one 0.716 move every other tick, like a slow
  effect. The single skips look random, about 4.6% of ticks. They are not tied to:
  - players nearby: nobody was within 8 blocks of him on the straight in any flight, the nearest
    ~42;
  - arrows fired: the skip rate is 5.1% with none in the last 8 ticks;
  - the 20-tick check grid: flat over all 20 phases;
  - the server running slow: no correlation with real milliseconds per server tick (r = 0.17).

  What causes them is the one open part of how he moves. It is what decides §4.4.

### 3.6 Later chase

- **Target: the 3D-closest player again.** Where it and Yellow are >20° apart: 15° vs 68°.
  Overall 7.8° median.
- **Slower than the first chase:** 0.37-0.45 blocks/tick at 4-16 blocks, 0.60-0.65 at 20-28.
  Most samples are close to players.
- **Pushed by pillars.** A pillar stepped down onto him pushes him down (§3.4), all the way to
  the floor at y 169 if it keeps going.
- **Second lightning.** If he lives long enough, the lightning line repeats 877-979 ticks after
  the first, and a third 561-612 after that (5 slow runs).

### 3.7 Model and residuals

Same test as for Maxor (server-timed runs, one recording each). Constant velocity first, then
the best model; rms error in blocks at h4 / h8 / h12 / h16:

| phase (starts) | constant velocity | best model | best model's error |
|---|---|---|---|
| transfer (1,839) | 1.05 / 1.89 / 2.93 / 3.62 | inertia: a 0.15, speed min(0.645, 0.25 + 0.015·d), aim (46, 173.1, 65) | 0.90 / 1.43 / 2.04 / **2.40** |
| chase (461) | 1.68 / 2.50 / 3.78 / 5.12 | inertia: a 0.09, aim +4.6 | 1.49 / 2.15 / 3.23 / 3.97 |
| later (599) | 1.59 / 2.90 / 3.76 / 4.08 | inertia: a 0.11, aim +2.6 | 1.31 / 2.15 / 2.74 / 3.67 |

- **Why inertia wins although Storm turns instantly.** Easing his velocity carries each run's
  own speed forward, and that speed varies between runs (0.47-0.73 in the transfer).
- **Chase and later errors are high.** The target is another player whose position is the
  recorder's (often stale) view. The lure usually stands 35-45 blocks from the recorder.
- **Vanilla wither steering does worse in every phase,** fitted or not (transfer h16 2.81
  fitted, 3.69 vanilla).

```
// Storm, one server tick. t = ticks since "Pathetic Maxor, just like expected."
ROUTE = [(73,183,83), (43,183,53), (73,183,23), (103,183,53)]
PARK  = (102.375, 183.0, 52.375)
NEXT  = { Purple -> (46, 173, 65) /*Yellow*/, Yellow -> (46, 173, 41) /*Green, assumed*/, Green -> (46, 173, 65) }

when (state) {
  OPENING  -> { // from t = -1 at (103, 188, 53)
                if (|ROUTE[i] - pos| < 1) { i++; if (i == ROUTE.size) { pos = PARK; state = PARKED; return } }
                pos += unit(ROUTE[i] - pos) * 0.40 }
  PARKED   -> if (t >= lightningLine + 139) state = CHASE         // ≈ t 687
  CHASE    -> { aim = closest3D(livingPlayers) + (0, 3, 0)
                pos += unit(aim - pos) * min(0.90, 0.20 + 0.023 * |aim - pos|) }
  PINNED   -> if (t >= enrageLine + 2) state = TRANSFER           // 2-3 ticks after the line
  TRANSFER -> { aim = NEXT[lastCrushPillar]                        // (46, 172.8, 65) for Yellow
                d = horizontal(aim - pos)
                if (d < 5) { state = CHASE; return }                // the last ~5 blocks chase the bait
                if (skipped()) return                              // ~4.6% of ticks, cause unknown (§3.5)
                pos += unit(aim - pos) * min(0.7157, 0.36 + 0.0134 * d) }
}
// On t % 20 == 19 (checks from his wither's spawn): StormCrush.judge(...) for each pillar;
// crushed -> state = PINNED at pos, lastCrushPillar = that pillar.
// Dead: after enough damage (0-30 ticks after crush 2 in the recordings, median 6).
```

## 4. The fastest possible Storm (first line → Goldor's first line)

### What is fixed and what the players control (measured, 5-player server-timed runs)

| step | ticks | spread | who controls it |
|---|---|---|---|
| dialogue to the lightning line | 548 | 546-552; no run lost >5 | fixed |
| lightning → Storm leaves the parking spot | 139 | 138-140 (p10-p90); every 5-player run 138-143 | fixed |
| leaving → crush 1 | 12 → the t **699** check | 95% of runs within 5 ticks of 699 | lure position + Purple timing |
| pin (crush 1 → enrage) | 0-183 | median 10 | not in the data (damage?) |
| flight Purple → Yellow zone (from his first move) | 87 | 83-92, min 76 | his movement (speed varies between runs, cause unknown) |
| crush 2 | **crush 1 + 100** at best | within 5 ticks of +100 in 49% of runs | pin ≤ ~12 + Yellow timing |
| crush 2 → death | 0-30 | median 6, p10 3 | damage |
| death → Goldor's line | 102 | 101-103 | fixed |

- **Crush 1 cannot come earlier than 699.** Storm leaves at 687 and needs ~11 ticks at 0.9 to
  reach Purple's zone; the check before 699 (679) is before he moves. No live run had crush 1
  before 697.
- **Crush 2 a check earlier (+80 = t 779) is possible only with luck.** He would have to be in
  Yellow's zone 80 ticks after crush 1.
  - That takes a pin of 1-2 ticks *and* a flight of ≤ 77-78. Flights take 87 (83-92).
  - Of 76 flights, one took 76, but with a 5-tick pin (zone at +81, and Yellow wasn't ready).
    No run made +80.
  - Crush position hardly matters: travel = 1.65 ticks per block of crush x (rms 4.6), and crush
    x varies only 99-103.
- **Crush 2 on Purple again is impossible.** He leaves Purple for Yellow by himself, and a
  pinned Storm is not crushed again: 7 runs had him still pinned in Purple's zone at the next
  check with the rule satisfied, uncrushed.

### Best-case timeline (server ticks after Storm's first line)

| t | what happens |
|---|---|
| -1 | Storm's wither spawns at (103, 188, 53) |
| 424 | parks at (102.375, 183, 52.375) |
| 548 | lightning line |
| ~636-656 | Purple pad held for 5 steps (186 → 181, 4 ticks a step). The last step must be ≥ 639 (60 ticks before the check) and the bottom ≤ his head at the check (~181.5-183) |
| 687 | Storm leaves toward the lure |
| ~698 | enters Purple's zone |
| **699** | crush 1 |
| 699-702 | "Storm is enraged!" at once (pin ≤ ~3) |
| ~701-704 | starts the flight to Yellow |
| ~755-776 | Yellow pad held for 5 steps (181 → 176). The last step must be ≥ 739 and the bottom ≤ 176 |
| ~783-795 | Storm enters Yellow's zone at y ≈ 173.3 |
| **799** | crush 2 |
| 799-800 | dead |
| **900-901** | Goldor's first line |

**Floor ≈ 900 server ticks = 45.0 s.** The best recorded run is 901: crush 1 at 700, pin 2,
crush 2 at 799, dead 802, Goldor 99 later. Nine more runs made 902-903.

- The recorded best is at the floor to within the ±1-2 ticks of line jitter.
- The only slack left is the 1-3 ticks between crush 2 and death.
- **A faster Storm needs a mechanic not in these recordings** (§4.3).

### 4.1 Where time is lost (129 five-player server-timed runs)

Median split 925 (p10 904, p90 1026, min 901, max 1654). Ticks lost against the best case:

| step | median | runs losing > 5 ticks | total over all runs |
|---|---|---|---|
| lightning (vs 548) | 0 | 0% | 45 |
| crush 1 (vs 699) | 0 | 5% | 750 |
| **crush 2 (vs crush 1 + 100)** | **+18** | **51%** | **4,819** |
| death (vs crush 2 + 1) | +4 | 34% | 924 |
| Goldor (vs death + 101) | +1 | 0% | 120 |

- **The pin decides crush 2.** Crush 2 − crush 1 was 99-101 in 59 runs, 120-121 in 24, 139-140
  in 7, 159-160 in 9, and up to 262. When crush 2 came at +100 the pin was 0-15 (median 4). When
  it came later, the pin was median 18 (p10 3, p90 41).
- **Why the +100 check was missed (66 runs).** Storm was not in Yellow's zone yet in 35 (23 of
  them with a pin > 15); he was out of the recorder's view in 15; his head was below the
  pillar in 4 more. Some runs combine these with the pillar not stepped in the last 60 ticks.
- **Short pin but still late.** Some runs with pins ≤ 15 still arrived 0.1-3 blocks short
  (x 49.1-52.3) because of the flight-speed spread.
- **Short pin, in the zone, still missed.** 31 of 43 runs with a pin ≤ 3 made +100. Of the 12
  that didn't, 4 had Storm inside the zone at +100 but with his head 0.1-1.6 below Yellow's
  bottom. He had sunk to y 172.3-172.9, or Yellow was still stepping onto him (pushing him
  down).
- **Pillar timing, among runs with Storm in the zone and Yellow armed at the +100 check:**
  - bottom 176, finished before he arrived: crushed in 45 of 47;
  - bottom ≤ 175, finished before he arrived: 2 of 2;
  - still stepping after he arrived: 2 of 4.

### 4.2 What the fastest runs did (positions 3 ticks before each crush)

Examples from the ten fastest runs, 901-903 (from `analyze.py fastest`; * = Storm's 3D-closest):

- `83dd99da` crush 1: *CatGirlZ (95,165,90), p3wr (38,170,90) on Yellow's pad side,
  imgettin (53,169,72), two players already in P3 at (108,120,94) / (65,128,36).
- `c0f40164` crush 1: *dmif (93,164,94); p3wr (59,169,65) between the pillars; AtkLxve
  (37,170,91) by Yellow's pad; two in P3. Crush 2: *p3wr (36,170,65), dmif (34,170,66).
- `2ae03bac` crush 1: *TheAdmin (113,170,94) = on Purple's pad; crush 2: *meowingi
  (46,170,66) standing on Yellow.

Common pattern:

- **Who holds aggro.** The lure has it from t 687 to crush 1. During the flight to Yellow
  nobody does: he flies to the pillar regardless. At Yellow the 3D-closest bait takes it.
- **Lure.** One player about 35-45 blocks from Storm's parking spot, south of Purple, typically
  at (93-95, 164-165, 90-94) or on Purple's pad (113, 170, 94). The straight line from
  (102.4, 52.4) to them crosses Purple's zone, so the 0.9-block/tick chase lands Storm in it
  11 ticks after he leaves.
- **Pads.** Located from the recorders' own positions while a pillar stepped down: Purple
  ≈ (113, 170, 94), Yellow ≈ (32, 170, 94), Green ≈ (33, 170, 13).
  - In all 61 runs that made 699/+100, Purple starts stepping at 640 (p10 638) and stops at
    181, its last step at 656 (655-676).
  - Yellow steps 740 (718-760) → 775 (755-776) to 176 in those runs. In the others its last
    step is later (median 815) and lower (median 171).
- **Bait at Yellow.** Someone at (33-37, 170, 65-67) west of it, or on it at (46, 170, 66).
  It doesn't speed up the flight (the slowdown is fixed), but it keeps Storm's later chase
  there and gives the DPS a spot.
- **Two players already in P3,** at (108, 120, 94) and (63, 127, 36).

### 4.3 Strat recommendation

Measured; each step protects a tick that is otherwise lost:

1. **Lure.** Stand 35-45 blocks from (102.4, 183, 52.4) on a line through Purple's crush zone
   (x 97-103, z 62-68): (93-96, 164-166, 90-94) as in the fastest runs. Be the 3D-closest
   player from t 687 to 699, and don't let anyone become closer to him in that window (e.g. by
   leaping toward him). He targets 3D distance, so the P3 players at y 120 do not steal him.
2. **Purple pad** (113, 170, 94). Hold it until the bottom is at 181 (5 steps from 186) or
   lower, **with the last step between t 639 and his arrival (~t 695)**. In practice start at
   ~t 636.
   - Every one of the 61 runs that made 699 and +100 stopped at 181.
   - Finishing before 639 disarms the pillar by 699 (the 60-tick rule).
   - A bottom above ~181.5-183 is above his head at the check.
   - A step onto him pushes him down.
3. **The pin must be short.** Crush 2 at +100 needs Storm moving by ~crush 1 + 12: pins of 0-15
   made it, and 31 of 43 runs with a pin ≤ 3 did. Presumably this means bursting him the moment
   he is pinned at ~(100, 179, 63.5) (not proven, see §5).
4. **Yellow pad** (32, 170, 94). Hold it for 5 steps, 181 → 176, **finishing after t 739 and
   before Storm arrives (~t 780)**, e.g. 756-776.
   - Finishing before 739 disarms the pillar by 799 (the 60-tick rule).
   - Stepping it onto him pushes him down out of reach.
   - His head clears 176 by only 0.2-0.9 blocks. One more step (to 175, finished by ~780) adds a
     block of margin; 2 of 2 runs that did this were crushed, too few to be sure.
   - (Inference) Once he is back to chasing, ~5 blocks from Yellow's middle, he aims ~3 above
     the closest player. So a bait standing lower than the arena floor could pull his head
     below 176 before the check.
5. **Kill at crush 2.** The fastest runs die 0-3 ticks after the crush-2 line; the median run
   loses 5 more ticks.

**Conjecture, not shown by the data:**

- ~~A shorter lightning phase~~: those runs were on the alpha server (see the note at the top).
- **Damage ends the pin.** Suggested by the enrage line ending both Maxor's stun and Storm's
  pin, and by the pin's wide spread. Not proven: there is no health data, and pins do not
  correlate with Maxor stun lengths.
- **Crush 2 on the +80 check (t 779), −1.0 s:** worked out in §4.4. Possible, but about 1 in 15
  even with everything else perfect.

### 4.4 Can crush 2 come a check earlier (t 779, −1.0 s)?

**Yes in principle, but only about 1 time in 15 even with everything the players control done
perfectly.** The skipped moves (§3.5), which nobody has found a cause for, decide it.

Storm has to be inside Yellow's zone (x ≤ 49, the 6x6 zone's +x edge) at the t 779 check,
with his head at or above Yellow's bottom and Yellow stepped down within the last 60 ticks. His
path there is fixed once crush 1 happens:

| step | best case | what sets it |
|---|---|---|
| crush 1 | t 699 | the lure, as now |
| pin (crush line → enrage line) | 0 | the players (probably damage; 0 happened in several live runs) |
| sets off | enrage + 2-3 | fixed |
| pinned at x → x 95 | (x − 95) / 0.712 ticks | where the 699 check catches him on Purple |
| x 95 → x 72 | 32.3 ticks | one 0.7157 move a tick, minus skips |
| x 72 → x 49 | 37.6-40.6 ticks | the slow-down, the bait (x 30-37 west of Yellow), minus skips |

- **Where crush 1 pins him matters most.** At the 699 check he is ~12.3 blocks (3D) from his
  parking spot, so the furthest west he can be pinned inside Purple's zone is about x 97.3-97.8
  (seen: 97.78). Every block further east costs 1.4 ticks. The median run pins him at x ~100.
- **Arrival with no skipped moves:** pinned at x 97.8 → t 775.4; at x 100 → t 778.6; at x 100.5 →
  t 779.2 (too late).
- **With the skips real flights had,** taking each of the 103 recorded straights and slow-downs
  and giving it the ideal start (pin 0, pinned at x 97.8): in the zone by 779 in 5 of 103 (5%),
  8 of 103 pinned at 97.5, 3 of 103 at 98.5, none from x 100.5. The median arrival is t ~783, too
  late for 779 and in time for 799. Each skipped move costs a tick, and there are 3-4 slack ticks
  at most.
- **Head height is not the problem.** He arrives at y ~173.4-173.6, head 176.4-176.6, so Yellow at
  176 (one 5-step batch from its 181) works; 175 gives a block of margin. Its last step must be
  after t 719 and before he is under it (about t 772+).
- **No live run has done it.** The closest reached x 49 at crush + 80.0 exactly
  (`92f33058`: pinned at x 99.97, pin 2).

So a party that pins him at x ≤ 98 with a 0-tick pin, keeps the Yellow bait at x 30-37 on the
floor and never slows him would make the 779 check about 5-8% of the time and fall back to 799
otherwise, losing nothing. Finding what makes Storm skip moves is the only thing that could make
it reliable.

## 5. Open questions, and what data would settle them

- **Storm's pin and his kill time.** Recording the boss bar (health) or the wither's health
  metadata, even every 5 ticks, would show whether the pin ends at a health threshold, and how
  much damage crush 2 needs.
- **Storm's skipped moves** (§3.5): ~5% of ticks in the flight between pillars, cause unknown.
  They are what stands between the fastest split and one a second faster (§4.4).
- **Skipped moves (~6% of ticks in the transfer, ~2% in the opening, uncorrelated with server
  lag).** Odin's ping count written on every line, rather than once per client tick, would
  tell a server hiccup from a real pause.
- **Other players' positions** are the recorder's view: lerped, and stale when out of range. The
  lure is usually 35-45 blocks from the recorder, which limits the chase model (h16 error 4 blocks).
  Every party member recording (or recording the server's packets for players too) would fix
  it.
- **How fast they retarget.** Only 13 (Storm) and 16 (Maxor) clean switches of the closest
  player by >60° exist.
  - Where the heading followed, it did so within 0-2 ticks in most cases (Maxor 8 of 10, Storm
    3 of 6).
  - The rest never followed within 25 ticks, most likely because the "switch" was a stale
    position of a player out of the recorder's view.
  - Whether they re-pick the target every tick or on a timer is open. Recordings by every party
    member would settle it.
- **Green and the Yellow → Green flight** (5 runs): the target point (46, 173, 41) is assumed by
  symmetry. Red's pad and behaviour: never used in F7.
- **Maxor beyond ~36 blocks and while armoured:** too few samples to pin his top speed or check
  for vanilla's armoured/unarmoured hover change.
- **For maintainers** (not changed here):
  - `BossDetail.STORM_FREE` treats "Slowing me down..." as Storm breaking free; it is a random
    taunt (§3.4). "⚠ Storm is enraged! ⚠" is the break-free moment.
  - `SubSplits.STORM_LIGHTNING_TICKS = 688` is armed by the *lightning* line, but Storm leaves
    his spot 139 ticks after that line. 688 matches his *first* line (687).

## 6. Reproducing

```
python3 tools/boss-movement/extract.py DATA_DIR OUT_DIR
python3 tools/boss-movement/tracks.py OUT_DIR
python3 tools/boss-movement/analyze.py OUT_DIR          # sections: data maxor storm fastest
python3 tools/boss-movement/fit.py OUT_DIR              # the model tables
python3 tools/boss-movement/transfer.py OUT_DIR         # §3.5 and §4.4
```

`DATA_DIR` holds `runs.json`, `ids.txt` and `runs/<id>.gz`; see `tools/boss-movement/README.md`.
