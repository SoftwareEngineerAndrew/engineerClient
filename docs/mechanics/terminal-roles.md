# Terminal roles (F7 P3): a static five-player role set

Five fixed roles for Goldor's phase: every player does the same job list in every run, with no
pots or hand-offs. It is for a party of five where everyone carries all three invincibility
items (Spirit Mask, Bonzo's Mask, Phoenix). It builds on [goldor.md](goldor.md) (sections, gates,
doors, death ticks, core) and [terminals-strategy.md](terminals-strategy.md) (one item per death
tick spent ahead, free windows at 60k+1, early device credit, first-completion floors).

Scripts: `tools/boss-mechanics/goldor/roles.py` with `roledata.py`, `rolemeasure.py` and
`roleplan.py` (README there). `python3 roles.py OUT_DIR` prints every number below and searches
the plan. Times are server ticks after "[BOSS] Goldor: Who dares trespass into my domain?"
(n = 0), 20 a second.

The measurements are data. The schedule and the predicted times come from a model built on them
and are **conjecture** until a team runs the plan.

## Data and cuts

- 155 recordings with server ticks (the four alpha-server recordings are left out); 126 runs with
  all four section ends.
- **Fast** = the fastest 25% of runs by P3 (start to "The Core entrance is opening!"):
  P3 ≤ 873. That is 31 runs and 34 recordings, 10 of them with Hypixel's completion lines.
- **Top half** = P3 ≤ 996: 63 runs, 72 recordings. It is used where the fast set is too thin
  (walking, levers, gates, devices), and every table says which cut it uses.
- Timing uses the recorder's own track, windows (`gui` / `guiclose`) and teleports (`tp`).
  Teammates' positions are the recorder's view of them. They are used only to say who did what,
  and for the terminal order.
- Completions come from Hypixel's "X activated a terminal! (a/b)" lines, which give the exact
  tick and who did it. Where a chat cleaner removed those lines, a terminal's completion is the
  tick the recorder's own window closed; that happened in the completion tick in 148 of 149
  checks. The status armor stands above each station say *which* station it was.
  - Terminal and device stands only refresh on a 20-tick grid (n = 0 mod 20), so they are not
    used as timers.
  - A lever stand flips 1-3 ticks after the pull.

## Numbering and the stations

**Terminal n** = the n-th terminal a player reaches walking in from the section's start.

- S1 starts on the Simon Says platform (108.3, 120, 94), where the party leaps in from Storm at
  about n = -85.
- S2-S4 start at their door on Goldor's track.
- The order is the distance from the start, except where the recorders' own walked paths (no
  teleports; p10 of ≥ 5 walks to each terminal, i.e. the direct walks) say otherwise:
  - S1 terminals 3/4: the walk to (90, 111, 92) goes round, 33 blocks against 20 to
    (110, 112, 74).
  - S3 terminals 2/3: the climb to (18, 122, 94) is 43 blocks walked, against 37 to (-2, 118, 94).
- The teammates' first arrivals from the start agree for S1.
- In S2-S4 almost nobody walks in from the door (they pre-enter or leap), so the arrival order
  there mostly reflects roles, not geometry.

This numbering is **not** the one in the M7 rotation's role names (`21`, `43`, ...).

| section | name | stand | stand here (median at window open / pull / blow) | start → stand | walked from start, p10 (n) |
|---|---|---|---|---|---|
| S1 | terminal 1 | (110, 118, 80) | (109.1, 118.8, 79.6) | 14 | 9 (22) |
| S1 | terminal 2 | (90, 121, 102) | (92.3, 121.0, 99.7) | 20 | 15 (22) |
| S1 | terminal 3 | (110, 112, 74) | (110.3, 113.0, 73.8) | 22 | 20 (7) |
| S1 | terminal 4 | (90, 111, 92) | (92.1, 112.0, 92.7) | 20 | 33 (20) |
| S1 | east lever | (106, 125, 114) | (106.9, 122.0, 111.7) | | |
| S1 | west lever | (94, 125, 114) | (95.4, 123.1, 113.6) | | |
| S1 | Simon Says | buttons x 110-111, y 120-123, z 92-95 | (108.3, 120, 94) | | |
| S1/S2 | gate 1/2 | x 93-107, z 121-124 | (95.8, 123.9, 121.0) | | |
| S2 | terminal 1 | (68, 108, 122) | (69.0, 109.0, 124.7) | 34 | |
| S2 | terminal 2 | (60, 119, 124) | (59.7, 120.0, 125.3) | 40 | |
| S2 | terminal 3 | (48, 108, 122) | (46.4, 109.0, 122.6) | 53 | |
| S2 | terminal 4 | (40, 123, 124) | (40.2, 124.0, 124.7) | 60 | |
| S2 | terminal 5 | (40, 107, 142) | (39.2, 109.0, 140.5) | 64 | |
| S2 | low lever | (28, 125, 128) | (28.3, 124.0, 128.7) | | |
| S2 | high lever | (24, 133, 138) | (25.6, 132.2, 137.5) | | |
| S2 | Lights | levers (60, 133, 142) (62, 136, 142) | ~(61, 134, 139) | | |
| S2/S3 | gate 2/3 | x 16-19, z 125-139 | (19.3, 123.6, 127.9) | | |
| S3 | terminal 1 | (-2, 108, 112) | (0.0, 109.0, 112.2) | 30 | |
| S3 | terminal 2 | (-2, 118, 94) | (1.0, 119.0, 93.6) | 43 | 37 (8) |
| S3 | terminal 3 | (18, 122, 94) | (16.5, 123.0, 93.7) | 38 | 43 (11) |
| S3 | terminal 4 | (-2, 108, 78) | (0.8, 109.0, 77.5) | 58 | 67 (5) |
| S3 | west lever | (2, 123, 56) | (4.3, 123.1, 55.4) | | |
| S3 | east lever | (14, 123, 56) | (13.0, 122.4, 55.7) | | |
| S3 | Arrow Align | frames x -2, y 120-124, z 76-80 | ~(0.5, 120, 77.5) | | |
| S3/S4 | gate 3/4 | x 1-15, z 48-51 | (12.4, 116.8, 52.7) | | |
| S4 | terminal 1 | (42, 108, 30) | (41.3, 109.0, 32.6) | 40 | |
| S4 | terminal 2 | (44, 120, 30) | (45.1, 121.6, 31.2) | 41 | |
| S4 | terminal 3 | (68, 108, 30) | (67.1, 109.0, 33.1) | 64 | |
| S4 | terminal 4 | (72, 114, 48) | (72.6, 115.0, 45.5) | 64 | |
| S4 | low lever | (84, 122, 34) | (84.4, 122.5, 34.9) | | |
| S4 | high lever | (86, 129, 46) | (85.5, 124.6, 43.6) | | |
| S4 | target | plate (63, 127, 35), targets x 64-68, y 126-130, z 50 | (63.5, 127, 35.5) | | |

Other places:

- **Doors** (section starts on the track): S2 (100, 118, 122.5), S3 (17.5, 118, 132),
  S4 (8, 118, 49.5).
- **Crossing points** (median of recorder crossings): into S2 at (89.8, 115, 132.9), into S3 at
  (8.4, 115, 122.1), into S4 from the track at (9, 114.5, 50) or from the strip at
  (54.6, 115, 49.4).
- **The strip**: in front of the core door, x 45-65, z 50-54.5, outside every section box. The
  recorder stood there at 246 death ticks (15 with S2 in progress, 179 with S3, 52 with S4) and
  was **never hit**, so it is a safe place to wait for S4.
- **The core route**: players reach the core from S2 through the wall at x 59-61, y 109-112,
  z 122, next to S2 terminal 2.
  - Measured in the recordings:
    - the wall's blocks turn to air in one tick, 12 at a time, in the recorder's own world only,
      and come back later;
    - recorders stood inside the core during S2 (1036 samples) and S3 (1738), and walked across
      it to the strip.
  - **Inferred**: a client-side ghost-block trick. It is the M7 rotation's `core` role.
  - Walk from an S2 terminal to the strip: 76-78 ticks (n = 15).

## Measurements

"med (p25, p10; n)". Measured unless marked.

### Terminals

| what | fast | top half |
|---|---|---|
| solve (window open → completion), all | 38 (29, 24; 103) | 40 (30, 23; 201) |
| click in order | 56 (52, 49; 23) | 55 (50, 48; 40) |
| correct all the panes | 40 (38, 35; 20) | 42 (39, 36; 33) |
| change all to same colour | 35 (29, 27; 20) | 35 (30, 27; 37) |
| select all the X | 27 (23, 20; 17) | 27 (23, 21; 32) |
| what starts with | 28 (23, 18; 14) | 27 (23, 18; 31) |
| click the button on time | 152 (133, 101; 9) | 116 (101, 79; 28) |
| open: within 4.5 blocks → window | 6 (4, 3; 98) | 6 (4, 3; 190) |
| standing at it when the door opens: door → window | 4 (4, 4; 7), S2 | |
| … → completion | 45 (44, 44; 6), S2 | |

- **Solve time depends on the window type, not on which terminal it is.**
  - Per-terminal medians are 27-57 in the fast set, with n = 1-10 each. The spread follows
    whichever types the few recorders drew.
  - "Click the button on time" takes ~4× the others. It is about 9% of the windows (9 of 103
    fast), so it is the main source of long sections.
  - The model draws each terminal's solve time from the 103 fast solves.
- **First completion after the door** (fast, chat lines):
  - S2: 68 (60, 53; 8), minimum 28.
  - S3: 74 (52, 47; 8), minimum 42.
  - S4: 61 (48, 44; 8), minimum 42.

### Levers, gates, leaps, devices, core

| what | value |
|---|---|
| lever: arrival within 4 blocks → pull (top half) | 4 (3, 2; 89) |
| lever: standing there at the door → pull (top half) | 5 (2, 0; 19) |
| gate: recorder at it (≤ 8 blocks, nearest) → "The gate has been destroyed!" (top half) | 9 (8, 6; 26) |
| gate destroyed before its own section started | 0 of 455 |
| gate 1/2 / 2/3 / 3/4, from its section's start (fast) | 53 / 75 / 146 |
| Spirit Leap: menu open → landing (fast) | 8 (4, 3; 109) |
| leap made at a door: door → landing (top half), S2 / S3 / S4 | 7 / 19 / 12; all 14 (8, 4; 86) |
| Simon Says completion (fast; top half) | 251 (249, 245; 8); 251 (245, 239; 18), never < 236 |
| target device completion, player on the plate from n ≤ 0 (fast; top half) | 147 (132, 101; 8); 156 (127, 100; 18), range 72-213 |
| Lights done, done in S1 (fast) | 166 (161, 158; 7) |
| Lights: its player entering S2 → Lights done (top half) | 64 (57, 49; 13) |
| Lights: recorder at the levers → done (all runs) | 30 (29, 21; 7) |
| Arrow Align: recorder at the frames → done (all runs) | 12 (10, 7; 21) |
| core: "The Core entrance is opening!" → last player in the core box (fast) | 22 (17, 14; 31), minimum 11 |

How the fast runs use these:

- **i4** leaves the plate after the target by leaping to a teammate in S1, then leaps again into
  S2. In 25 recordings it landed at (61, 132, 139), the Lights player.
- **Leaps** go S1→S2 (24 in the fast set), S2→S3 (18), S3→strip (25) and S4→core (22).

### Walking (top half, recorder only)

What is timed:

- From the recorder's own job done at A to arriving within reach of B.
- Only walks with no teleport and no stop of more than 10 ticks.
- 49 point pairs with ≥ 2 walks, 353 walks. The pairs with ≥ 3 walks are used directly; for
  example (med; n):

| move | ticks |
|---|---|
| S1 terminal 2 → S1 terminal 4 | 42 (12) |
| S1 east lever → west lever / west lever → gate 1/2 | 5 (20) / 4 (19) |
| Lights → S2 terminal 1 / 2 / 4 / 5 / high lever | 29 / 17 / 26 / 36 / 33 |
| S2 terminal 1 → 3; 2 → 3; 4 → 3 | 42; 32; 25 |
| S2 low lever → gate 2/3 | 4 (8) |
| S2 terminal 2 / 3 → strip (core route) | 76 (8) / 78 (7) |
| S3 terminal 2 → Arrow Align; S3 terminal 4 → gate 3/4 | 30 (12); 36 (9) |
| S3 west lever → east lever | 5 (9) |
| strip → S4 terminal 1 / 2 / 3 / 4 | 23 / 31 / 21 / 20 |
| S4 terminal 3 → low lever; S4 terminal 4 → low lever | 36 (14); 57 (7) |

- Every other pair uses the fit: ticks = 5.3 + 1.06 × horizontal blocks + 0.78 × blocks climbed
  (straight line). The rmse is 19 ticks, so single legs are ±20.
- Etherwarps were rare in these walks (11 of 400+ moves).

## The model

`roleplan.py` plays a plan with these rules. Each rule is measured unless marked.

1. A section ends at the later of its last job and its gate.
   - Terminals and levers work only from their section's door.
   - A player standing at a terminal opens it 4 ticks after the door. A player arriving later
     opens it 6 ticks after arriving.
   - The first completion is never sooner than 28 (S2) or 42 (S3, S4) ticks after the door.
2. A gate goes 9 ticks after a player reaches it, and never before its section starts.
3. **Early devices:** Lights and the target credit during S1, and Arrow Align during S2.
   - Simon Says and the target are drawn from the fast runs' completions.
   - Lights takes the measured "enter S2 → done".
   - Arrow Align takes the measured time at the frames.
4. **Death ticks** come at n = 60k. A player standing in a section ahead of the one in progress
   at such a tick uses one item. The core and the strip are safe.
   - A pre-entry is a fixed tick in the plan: one of the free windows 60k+1, or "as soon as your
     job before is done".
   - If the door comes later than the plan expects, the player pays for the extra ticks.
5. **Moving:** measured walks, else the fit.
   - A leap lands on a teammate who is standing still (at a job, or waiting): 8 ticks mid-section,
     14 when made at a door.
   - The core route leads from S2 to the strip: 62 ticks from the wall (**inferred**, from the
     S2 → strip walks minus the walk to the wall).
6. **The core:** the first player is counted in 11 ticks after the opening (the fastest recorded
   everyone-in). The others walk in, or leap to the first one in (8 ticks). Goldor leaves when
   the last is in.
7. **The random parts** are drawn per run from the measured lists: each terminal's solve time,
   Simon Says, the target, Lights and Arrow Align.

**The search.** Simulated annealing over which player does which job, in what order and in which
section's time, and the pre-entry ticks:

- 8 chains × 12 000 steps.
- Starting points: the rotation-shaped plan, a staged plan and random plans.
- Each candidate is scored on the same 80 draws. The score is the mean of (core opening +
  everyone in), plus penalties:
  - 400 per item above 2 in the median draw (one item spare for everyone);
  - 20 000 × the share of draws in which some player would need more than 3.
- Then:
  - entries that change nothing are dropped;
  - every remaining entry is moved to a later free window where that costs ≤ 1 tick and lowers
    item use.
- Spirit is not counted twice.
- The best chains scored 752, 754 and 760, and the worst 814. Plans within ~10 ticks of each
  other are not told apart by this model.

**Calibration** (**inferred** check). The rotation-shaped starting plan (ss / 21 / 43 / i4 /
l+ee2, the others leaping in at each door) over 400 draws, against the fast runs:

| | model median [p25-p75] | recorded fast median [p25-p75] |
|---|---|---|
| S1 | 252 [249-269] | 252 [242-272] |
| S2 | 180 [163-244] | 186 [159-200] |
| S3 | 210 [189-275] | 203 [170-232] |
| S4 | 106 [92-144] | 156 [132-181] |
| P3 | 819 [751-884] | 817 [784-844] |

S1-S3 and P3 reproduce. **S4 comes out ~50 ticks short.** The recorded S4s have their levers at
84+ ticks after the door and players doing a second job, so part of the gap is what the
recorded teams do. The model's S4 is still its least trustworthy section. Read every S4 figure
below as optimistic by up to ~50.

## The five roles

Names follow the rotation's vocabulary where it fits: `ss`, `i4`, `ee2`, `ee3`, `core`.
Positions are the stands in the station table.

The "~n" times are the model's median-draw timeline. They are a guide for what "on time" looks
like, not targets to wait for. **Death ticks** are n = 60, 120, 180, 240, ...; entering at 60k+1
is free until 60k+60.

### ss

- **S1:** Simon Says from the start; done at ~251 (236-259 in the fast runs).
- **S2:** when Simon Says is done, walk into S2 to **terminal 1**; done ~352.
- **S3:** at the S2 door, leap to **ee3** (standing at S3 terminal 1). Pull the **west lever**
  (~460), then the **east lever** (~469). Then leap to **ee2·core** on the strip (~477).
- **S4:**
  - Step into S4 at **n = 481** (the free window after the 480 death tick), or at the S3 door if
    that comes first.
  - Pull the **low lever** (~533), then the **high lever** (~546).
  - Then **terminal 4** (~609): S4's most frequent last job, 169 of 400 draws.
- **Core:** leap to the first player in.
- **Items:** 0 planned (1 if S3's door comes after 540). Draws needing > 3: 1.5%.

### i4

- **S1:**
  - On the target plate (63, 127, 35) from the start. It is done ~158; 72-213 recorded.
  - Then leap to **ss** and walk to the **west lever** (~183). The model leaps to ee3 at the east
    lever: either works.
  - Blow **gate 1/2** (~196).
- **S2:**
  - At the door, leap to **ee2·core**, who is at Lights.
  - Pull the **high lever** (~302).
  - Then **terminal 5** (~366): S2's most frequent last job, 195 of 400 draws.
- **S3:** at the S2 door, leap to **ee3** and do **terminal 3** (~466). Leap to ee3 again (now at
  terminal 4) and do **Arrow Align** (~500): S3's most frequent last job, 213 of 400.
- Then leap to **ee2·core** on the strip (~508).
- **S4:** at the S3 door, step off the strip to **terminal 1** (~575).
- **Items:** the target alone uses 1-3.
  - 60 and 120, plus 180 if the target isn't done by then: 1 item in 101 of 400 draws, 2 in 168,
    3 in 131.
  - Nothing else is planned ahead.
  - Give i4 to whoever has all three items off cooldown.

### ee3 (terminal 1, east lever, ee3)

- **S1:** **terminal 1** from the start (~42). Leap to **ss** and pull the **east lever** (~80).
- **S2:**
  - At the door, leap to **42·gates**, who is at S2 terminal 3. Do **terminal 4** (~330).
  - Then pre-enter S3 straight away: walk to **S3 terminal 1** and stand there (~376).
- **S3:**
  - **Terminal 1** at the door (~420).
  - Leap to **42·gates** (at S3 terminal 2) and do **terminal 4** (~494).
- **S4:** at the S3 door, leap to **42·gates**, who is on the strip. Do **terminal 3** (~578).
- **Items:**
  - The S3 pre-entry costs the 360 tick when S2's door is after 360: 0 in 202 of 400 draws, 1 in
    142, 2 in 44, 3 in 12.
  - If S2 is running late, enter at 361 instead (the free window).

### 42·gates

- **S1:**
  - **Terminal 4** from the start (~42), then **terminal 2** (~106).
  - Enter S2 at **n = 181** and stand at **S2 terminal 3** (~234).
- **S2:**
  - **Terminal 3** at the door (~293).
  - Leap to **ee3** (at S2 terminal 4) and blow **gate 2/3** (~338).
- **S3:**
  - At the S2 door, leap to **ee3** and do **terminal 2** (~456).
  - Leap to **ss** (at the S3 levers) and blow **gate 3/4** (~487).
  - Leap to **ee2·core** on the strip (~495).
- **S4:** at the S3 door, **terminal 2** (~575).
- **Items:** the 240 tick when S1's door is after 240: 0 in 87 of 400 draws, 1 in 289, 2 in 24.

### ee2·core

- **S1:**
  - **Terminal 3** from the start (~42).
  - Enter S2 at **n = 181** and do **Lights** (~245). It credits S2 early.
  - Stay at the Lights levers as the leap target for the S2 door.
- **S2:**
  - **Terminal 2** at the door (~312).
  - Leap to **ee3** (at terminal 4) and pull the **low lever** (~343).
  - Then take the core route: through the core wall next to terminal 2, across the core to the
    strip (~467).
- **S3 / S4:**
  - Hold the strip. ss, 42·gates and i4 leap to you during S3 and step into S4 from there.
  - You have no S4 job: at the opening you are the first in the core, and the rest leap to you.
- **Items:** the 240 tick when S1's door is after 240: 0 in 53 of 400 draws, 1 in 321, 2 in 26.

### Who does what, by section

| | ss | i4 | ee3 | 42·gates | ee2·core |
|---|---|---|---|---|---|
| S1 | Simon Says | target (S4), west lever, gate 1/2 | T1, east lever | T4, T2, → S2 at 181 | T3, → S2 at 181, Lights |
| S2 | T1 | high lever, T5 | T4, → S3 at once | T3, gate 2/3 | T2, low lever, → strip |
| S3 | west lever, east lever, → strip | T3, Arrow Align, → strip | T1, T4 | T2, gate 3/4, → strip | (strip) |
| S4 | → S4 at 481: low lever, high lever, T4 | T1 | T3 | T2 | first into the core |

## Predicted timeline

**Conjecture.** 400 draws; median (p25, p10). "S4 cal." adds the calibration gap (+50) to S4.

| | predicted | recorded fast (31 runs) | best recorded |
|---|---|---|---|
| S1 | 251 (249, 239) | 252 (242, 232) | 193 |
| S2 | 134 (117, 110) | 186 (159, 140) | 109 |
| S3 | 172 (153, 134) | 203 (170, 151) | 129 |
| S4 | 106 (93, 82), S4 cal. ~156 | 156 (132, 121) | 93 |
| **P3** | **727 (662, 627)**; with S4 cal. ~777 | 817 (784, 738) | 681 |
| everyone in the core | +19 (median draw) | +22 (17, 14) | +11 |
| P3 + everyone in | 746 (681, 646) | ~839 | |

In the median draw the doors open at 251 / 365 / 499 and the core at 608. The long tail
(p90 and beyond) is mostly "click the button on time" windows landing on a section's last
terminal.

Where the time comes from, against the recorded fast runs:

- **S2 (−50):** three players already stand at their S2 jobs at the door (42·gates at T3,
  ee2·core at Lights → T2, ss walking in). The rest leap onto them.
- **S3 (−30):** ee3 stands at S3 terminal 1 at the door, and the others leap onto ee3.
- **S4 (up to −50, least certain):** three players wait on the safe strip and step in at the
  door, and ss pre-enters at 481 for the levers.

## Sensitivity

**Critical path.** The most frequent last job per section, over 400 draws:

| section | last jobs |
|---|---|
| S2 | T5 (i4) 195; T1 (ss) 72; low lever (ee2·core) 52; T4 (ee3) 42; gate 2/3 39 |
| S3 | Arrow Align (i4) 213; T4 (ee3) 104; gate 3/4 (42·gates) 83 |
| S4 | T4 (ss) 169; T3 (ee3) 88; T1 (i4) 72; T2 (42·gates) 71 |

S1 is always Simon Says.

**One slower player.** Mean cost of one player taking +20 ticks on each terminal (about p75 − p25
of the solve times) and ×1.5 on his devices:

| role | cost |
|---|---|
| i4 | +60 |
| ee3 | +33 |
| 42·gates | +21 |
| ss | +15 |
| ee2·core | +12 |

- i4 is on the S2 and S3 critical path (terminal 5, then Arrow Align). Put the fastest terminal
  player there, not only the one with items.
- A slow ee2·core costs little: after Lights and S2 they only hold the strip.

## Item budget

| role | planned (median draw) | draws needing 2 / 3 / > 3 |
|---|---|---|
| ss | 0 | 63 / 52 / 6 of 400 |
| i4 | 2 (target) | 168 / 131 / 0 |
| ee3 | 1 | 44 / 12 / 0 |
| 42·gates | 1 | 24 / 0 / 0 |
| ee2·core | 1 | 26 / 0 / 0 |

- Every role keeps at least one item spare in the median draw.
- Only ss can run out (1.5% of draws), when S3 runs long after ss has stepped into S4 at 481.
  Don't step in at 481 if S3 is clearly behind (fewer than ~4 of 7 done by then).
- Spirit's cooldown is not counted, so a second Spirit is extra margin.

## Caveats

- **Small samples.**
  - The fast set has 34 recordings, and the recorders are mostly the same few players in the
    same roles.
  - Per-terminal solve times (n = 1-10), Lights (n = 7-13), Arrow Align (n = 21, mostly slow
    runs) and several walking pairs (n = 3-5) are thin.
- **Model.**
  - Walking between unmeasured pairs is a straight-line fit with ±20-tick legs.
  - The core route's time is inferred.
  - Leaps assume the target is standing still. Several leaps in the plan land on a teammate who
    is standing at a terminal: fine in the game, but the target must be where the card says.
  - S4 is ~50 ticks optimistic for the rotation-shaped plan.
- **Unmeasured.**
  - Whether the target device's time runs from n = 0 or from when its player starts. It is
    started at n = 0 here, as every recorded i4 did.
  - Whether the strip and the core route stay safe for three to four players at once. Only the
    recorder was seen there.
  - How early Lights can be done. The fast runs finished it at 143-179. Here ee2·core enters S2
    at 181 and finishes at ~245, just before S1's door (~251). If Simon Says ends first, Lights
    is simply one more S2 job, done by the player already standing at it.
- **The plan is one of several** within ~10 ticks of each other in the model. The ordering
  inside each section matters less than three things:
  - standing at an S2 job at the door;
  - one player standing at S3 terminal 1 at the door;
  - waiting for S4 on the strip.
- **Static means no recovery.** If a player dies or disconnects, the job list does not adapt.
  The rotation's pots exist for that.
