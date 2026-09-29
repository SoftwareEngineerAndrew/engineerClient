# How Maxor and Storm move (F7 P1/P2), and the fastest possible Storm

Measured from the Better PF recordings (2026-09-23 to 09-29). The scripts are in
`tools/boss-movement/` (see its README); every number here is printed by `analyze.py` or
`fit.py`. Storm's crush rule (the 20-tick checks, the crush zone, head height, "stepped down in
the last 60 ticks") was measured separately and is in [`docs/storm-crush.md`](storm-crush.md) /
`StormCrush.kt`. It is used here, not re-derived.

Times are **server ticks** (20 a second) counted from the boss's first line ("WELL! WELL! WELL!
LOOK WHO'S HERE!" for Maxor, "Pathetic Maxor, just like expected." for Storm) unless said
otherwise. "p10-p90" is the middle 80% of runs.

## Summary

- **Both bosses fly straight at the 3D-closest living player.** Where the 3D-closest and the
  horizontally-closest players differ, the heading matches the 3D one to a median of 2-3° and
  misses the horizontal one by 110-135°. They turn on the same tick the closest player changes.
  Speed grows with the distance to that player, about `0.2 + 0.021·d` blocks/tick, capped at 0.9.
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
    (138-141) ticks after that line.
- **After the lightning** Storm chases the 3D-closest player at up to 0.90 blocks/tick. He
  aims about 3 blocks above their feet.
- **Crushes.** A crush pins him until "⚠ Storm is enraged! ⚠"; he moves 1-4 ticks after that
  line. The pin lasts 0-183 ticks (median 10). He then flies to the next pillar whatever the
  players do: Purple → a point over Yellow (46, ~173, 65), Yellow → Green, Green → Yellow. Red is
  never used. That flight runs at 0.72 blocks per move and slows near the point. About 6% of
  ticks make no move, so the average is 0.64 blocks/tick. Near the pillar he goes back to
  chasing the closest player.
- **Fastest Storm split ≈ 900 server ticks (45.0 s): crush 1 at 699, crush 2 at 799, dead at
  799-800, Goldor's line 101-102 ticks later.**
  - Everything else is fixed dialogue or fixed movement. The best recorded run is 901; ten
    runs made 901-903.
  - Crush 2 cannot realistically come a check earlier (779): the flight to Yellow takes 79-92
    ticks after Storm starts moving. The earliest arrival seen was 81 ticks after crush 1.
  - Where the median run loses time: crush 2 misses the +100 check in 51% of 5-player runs
    (median +18, 4,819 ticks in total), usually because the pin went past ~12 ticks. The kill
    after crush 2 costs another median 5.
- **Data caveats.**
  - Positions in the recordings are the client's 3-tick interpolation of what the server
    sent; I undo it (recovered positions from two recordings of one run agree to 0.015 blocks).
  - 85 of 256 recordings have no server ticks; timing statistics use only the 146 runs whose
    every recording has them.
  - Other players' positions are the recorder's view: interpolated, and stale when out of
    range.
  - Storm's pin length and his kill time depend on damage, which the recordings don't have.

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
  never come every tick. One packet usually carries two ticks of movement. 1-2% of packets
  arrive in the same client tick as another (only the last is visible).
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
| first move | 46 after that line: 46 in 60 runs, 45 in 21, 47 in 19, 48 in 3. `SubSplits.MAXOR_MOVE_TICKS = 46` confirmed. Three 1-3 player practice runs moved at +0/+1 |
| first laser stun line ("THAT BEAM! IT HURTS!" / "YOU TRICKED ME!") | 212 / 240 median, earliest 204-205 (p10 205) |
| "⚠ Maxor is enraged! ⚠" (end of the stun) | 346 (216-444) |
| second stun line | 406-416 (p10 405) |
| "I'M TOO YOUNG TO DIE AGAIN!" (said in 79 of 146) | 489 (487-822) |
| Storm's first line | 518 (510-732) |

### Stuns

- **Where he is stunned.** Always at the lasers' meeting point, feet at (72-74, 226, 72-75): the
  six commonest block positions hold 118 of 145 stuns. He gets there 204-206 ticks in at the
  earliest (about 35 ticks of flight from his spawn, 20.5 blocks).
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

- **Speed.** About `min(0.9, 0.19 + 0.021·d)`, the same profile as Storm's chase (§3.4). It is
  not constant, and not capped per axis: the direction is the straight 3D line (errors above).
- **Height.** Vertical speed against (player y - Maxor y): -12 → -0.45, -6 → -0.20,
  -3 → -0.06, 0 → 0.00, +3 → +0.15, +6 → +0.25, +12 → +0.36. When he stands still next to his
  target, he is 0.9 (0.2-1.8) above their feet.
- **Stopping.** He stops 2.7 blocks horizontally (2.0-15; n=43) from them. The 3-block
  horizontal stop matches vanilla's `d² > 9` rule, but the rest does not.

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
| leaves the parking spot | **687 (686-688)** = lightning + **139 (138-140)**, n=110; 4 practice runs of 1-3 players: lightning + 98-113 |
| crush 1 ("Oof" / "Ouch, that hurt!") | 700 (698-839); 95% of 5-player runs at 699±2 |
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
- **Short lightning phases.** 4 practice runs of 1-3 players left at lightning + 98/99/99/113,
  one with crush 1 at t 659. The recorder took no "Giga Lightning" hits in three of them
  (normal runs log hits at +10 and +20). 1-player runs also had the normal 139 and 152, and
  no 5-player run was short. Cause unknown; see §5.

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
- **Pinned.** He holds still where the check caught him.
- **Break free.** He moves again **1-4 ticks after "⚠ Storm is enraged! ⚠"** (median 2,
  n=107).
- **Pin length (crush 1 → enrage).** Median 10, p10 1, p90 29, max 183.
- **What ends the pin is not in the data.** Recordings carry no health. It does not correlate
  with the same party's Maxor stun length (Spearman 0.04), and only weakly with how long they
  take to kill him after crush 2 (0.32).
- **Taunt lines are not events.** "Slowing me down will be your greatest accomplishment!",
  "BEGONE PILLAR!", "THAT WAS ONLY IN MY WAY!", "This factory is too small for me!" and six
  others come from a random taunt pool.
  - The first taunt comes 197 ticks (p10 61, p90 201) after crush 1, the next ones 60-63 ticks
    apart. Storm is usually not in a crush zone then and no pillar is moving.
  - So `BossDetail.STORM_FREE` ("Slowing me down..." = broke free) is not a break-free signal:
    the enrage line is.

### 3.5 After a crush: straight to the next pillar

After the pin he flies to the next pillar, ignoring the players (pillar = where he is pinned 4
ticks after the crush line):

| sequence (runs) | first heading after the crush |
|---|---|
| Purple → Yellow (114, plus 51 with one crush unseen) | 80-90° (due west) in 117 of 154 |
| Yellow → Green (5) | 140-180° (north) |
| Green → Yellow (2) | 0-20° (south) |

- **Rule: the nearest other pillar that works.** Red would be nearest to Purple, but Red never
  moved in any F7 run, and he never heads for it.
- **The target is a point, not a player.** In the first 140 ticks after a Purple crush, while
  more than 8 blocks from Yellow, look at the 945 samples where Yellow's middle (46, 65) and
  the 3D-closest player are more than 20° apart. Yellow matches (median **3.2°**, 67% within
  10°); the closest player does not (59°).
- **Height.** He descends toward y ≈ 173 (fitted aim 173.1). He enters Yellow's zone at y
  173.2-173.9, so his head is at ≈176.2-176.9 and Yellow must be down to 176.
- **Speed: 0.72 blocks per move (3D).**
  - Far out: packet steps quantise to whole 0.72 moves (rms 0.106 of a step), but only
    0.939 moves per server tick; 4-tick windows show 3 moves instead of 4 in ~20% of cases.
  - Slowing near the point: ≥28 blocks 0.71-0.72, 20-24 0.67, 16-20 0.63, 12-16 0.58, 8-12
    0.53. That is ≈ `min(0.72, 0.40 + 0.012·d)` with d horizontal to (46, 65).
  - Average from x 95 to 55: 0.638 (p10 0.600, p90 0.671) per server tick.
  - Between runs it ranges 0.47-0.73 per server tick and is uncorrelated with server lag
    (r = 0.02). Why some runs are slower (player slows? hit pauses?) is open.
- **Timing, 51 flights watched from start to zone.** First move 5 (2-18) ticks after the crush
  line. Feet inside Yellow's zone **87 (82-92, min 79)** ticks after the first move, i.e.
  **93 (88-108, min 81)** after the crush line.
- **Back to chasing** once within ~8 blocks of the point, then the 3D-closest player again
  (§3.6). The final approach, x 60 → the zone edge at x 49, takes 23 ticks whether the bait
  player stands west of, on, or east of Yellow. The slowdown belongs to the flight, not the
  bait.

### 3.6 Later chase

- **Target: the 3D-closest player again.** Where it and Yellow are >20° apart: 15° vs 68°.
  Overall 7.8° median.
- **Slower than the first chase:** 0.37-0.45 blocks/tick at 4-16 blocks, 0.60-0.65 at 20-28.
  Most samples are close to players.
- **Pushed by pillars.** A lowering pillar pushes him down with it: he was seen dropping 0.5-0.7
  blocks/tick onto the floor at y 169.
- **Second lightning.** If he is still alive about 700-900 ticks after crush 1, the lightning
  line repeats (seen at t 1425-2076).

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
  PINNED   -> if (t >= enrageLine + 2) state = TRANSFER           // +1..4
  TRANSFER -> { aim = NEXT[lastCrushPillar]
                if (horizontal(aim - pos) < 8) { state = CHASE; return }
                if (random() < 0.06) return                        // ~6% of ticks make no move
                pos += unit(aim - pos) * min(0.72, 0.40 + 0.012 * horizontal(aim - pos)) }
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
| lightning → Storm leaves the parking spot | 139 | 138-140 (all 5-player runs) | fixed |
| leaving → crush 1 | 12 → the t **699** check | 95% of runs make 699 | lure position + Purple timing |
| pin (crush 1 → enrage) | 0-183 | median 10 | not in the data (damage?) |
| flight Purple → Yellow zone (from his first move) | 82-92 | min 79 | fixed by his movement |
| crush 2 | **crush 1 + 100** at best | +100 in 49% of runs | pin ≤ ~12 + Yellow timing |
| crush 2 → death | 0-30 | median 6, p10 3 | damage |
| death → Goldor's line | 102 | 101-103 | fixed |

- **Crush 1 cannot come earlier than 699.** Storm leaves at 687 and needs ~11 ticks at 0.9 to
  reach Purple's zone; the check before 699 (679) is before he moves. No run had crush 1
  before 697, apart from one 3-player practice run with the short lightning.
- **Crush 2 cannot come a check earlier (+80 = t 779).** That would need him inside Yellow's
  zone 80 ticks after crush 1, i.e. a pin of 1-2 ticks and a flight ≤ 78. The fastest flight
  seen was 79, and the earliest zone entry 81 ticks after crush 1 (0 of 51 flights made +80).
  Crush position hardly matters: travel = 1.65 ticks per block of crush x (rms 4.6), and crush
  x varies only 99-103.
- **Crush 2 on Purple again is impossible.** He leaves Purple for Yellow by himself, and a
  pillar that just crushed does not crush again: 5 runs had him still in Purple's zone at the
  next check, uncrushed.

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
crush 2 at 799, dead 802, Goldor 99 later. Nine more runs made 902-903. The recorded best is
at the floor to within the ±1-2 ticks of line jitter. Two of those "dead" ticks are the only
slack left. **A faster Storm needs a mechanic not in these recordings** (§4.3).

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
- **Why the +100 check was missed.** Storm was not in Yellow's zone yet in 35 of 66 runs (23 of
  them with a pin > 15); he was out of the recorder's view in 13; his head was still below the
  pillar in 4. Several runs combine these, often with the pillar not stepped in the last 60
  ticks.
- **Short pin but still late.** Some runs with pins ≤ 15 still arrived 0.1-3 blocks short
  (x 49.1-52.3) because of the flight-speed spread.

### 4.2 What the fastest runs did (positions 3 ticks before each crush)

Examples from the six fastest (from `analyze.py fastest`; * = Storm's 3D-closest):

- `83dd99da` crush 1: *CatGirlZ (95,165,90), p3wr (38,170,90) on Yellow's pad side,
  imgettin (53,169,72), two players already in P3 at (108,120,94) / (65,128,36).
- `c0f40164` crush 1: *dmif (93,164,94); p3wr (59,169,65) between the pillars; AtkLxve
  (37,170,91) by Yellow's pad; two in P3. Crush 2: *p3wr (36,170,65), dmif (34,170,66).
- `2ae03bac` crush 1: *TheAdmin (113,170,94) = on Purple's pad; crush 2: *meowingi
  (46,170,66) standing on Yellow.

Common pattern:

- **Lure.** One player about 35-45 blocks from Storm's parking spot, south of Purple, typically
  at (93-95, 164-165, 90-94) or on Purple's pad (113, 170, 94). The straight line from
  (102.4, 52.4) to them crosses Purple's zone, so the 0.9-block/tick chase lands Storm in it
  11 ticks after he leaves.
- **Pads.** Located from the recorders' own positions while a pillar stepped down: Purple
  ≈ (113, 170, 94), Yellow ≈ (32, 170, 94), Green ≈ (33, 170, 13).
  - Purple starts stepping at 640 (p10 638) and stops at 181 by 656 in all 61 runs that made
    699/+100.
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
   player from t 687. He targets 3D distance, so the P3 players at y 120 do not steal him.
   Do not leap in the last ~12 ticks before 699.
2. **Purple pad** (113, 170, 94). Hold it for exactly 5 steps, 186 → 181, **finishing between
   t 639 and 699**; in practice start at ~t 636. Stepping earlier disarms the pillar by 699
   (60-tick rule). Stepping less leaves the bottom above his head (~181.5-183).
3. **The pin must be short.** Crush 2 at +100 needs Storm moving by ~crush 1 + 12. Every run
   with pin ≤ 3 that also had Yellow ready made +100. Presumably this means bursting him the
   moment he is pinned at ~(100, 179, 63.5) (not proven, see §5).
4. **Yellow pad** (32, 170, 94). Hold it for 5 steps, 181 → 176, **finishing between t 739 and
   799**, e.g. 756-776. Storm arrives with his head at ~176.2-176.9, so 176 is enough; the
   60-tick rule forbids finishing before 739.
5. **Kill at crush 2.** The fastest runs die 0-3 ticks after the crush-2 line; the median run
   loses 5 more ticks.

**Conjecture, not shown by the data:**

- **A shorter lightning phase.** It happened only in 1-3-player practice runs: departure at
  lightning + 98 instead of 139, crush 1 at 659. If a 5-player party could trigger it, both
  crushes would move one check earlier: Goldor at ~860, **−40 ticks (−2.0 s)**. What triggers
  it is unknown. In three of the four, the recorder took no Giga Lightning hits.
- **Damage ends the pin.** Suggested by the enrage line ending both Maxor's stun and Storm's
  pin, and by the pin's wide spread. Not proven: there is no health data, and pins do not
  correlate with Maxor stun lengths.
- **Slower flight in some runs** (0.47-0.6 blocks/tick instead of 0.64) might be player slow
  effects. If so, **don't slow Storm between the pillars.**

## 5. Open questions, and what data would settle them

- **Storm's pin and his kill time.** Recording the boss bar (health) or the wither's health
  metadata, even every 5 ticks, would show whether the pin ends at a health threshold, and how
  much damage crush 2 needs.
- **The short lightning phase.** Same need: health, plus lightning strike entities
  (`minecraft:lightning_bolt` spawns are extracted but none were recorded) and everyone's hits
  (only the recorder's "Giga Lightning hit you" lines exist).
- **Skipped moves (~6% of ticks in the transfer, ~2% in the opening, uncorrelated with server
  lag).** Odin's ping count written on every line, rather than once per client tick, would
  tell a server hiccup from a real pause.
- **Other players' positions** are the recorder's view: lerped, and stale when out of range. The
  lure is usually 35-45 blocks from the recorder, which limits the chase model (h16 error 4 blocks).
  Every party member recording (or recording the server's packets for players too) would fix
  it.
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
```

`DATA_DIR` holds `runs.json`, `ids.txt` and `runs/<id>.gz`; see `tools/boss-movement/README.md`.
