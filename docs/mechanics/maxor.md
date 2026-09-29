# Maxor (F7 P1): how the fight works

From "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" until Storm's first line, measured from the
Better PF recordings (2026-09-23 to 09-29). Scripts: `tools/boss-mechanics/maxor/` (README
there); every number below is printed by its `analyze.py`. Maxor's de-lerped move packets come
from `tools/boss-movement/` (see `docs/maxor-storm-movement.md` §1 for how positions are
recovered).

Times are **server ticks** (20 a second, the `st` lines) after Maxor's first line ("s0") unless
said otherwise. "p10-p90" is the middle 80% of runs. **Measured** statements are backed by the
tables; **conjecture** is marked as such. Alpha-server recordings are never read.

## Summary

- **Everything the phase controller does happens on 10-tick checks**, at s0 + 6 (mod 10) in
  most runs (1 tick after the wither's spawn tick): crystal placements, the laser firing, the
  beam column's colour, the crystals' respawn. What players do (pickups) and what damage does
  (the enrage, the kill) happens on any tick.
- **Crystals.** Two end crystals appear on the upper platforms (64.5 / 82.5, 238.4, 50.5) with
  the wither at s0 + 5. Clicking one picks it up at once. The pylons at (52.5, 224, 41.5) and
  (94.5, 224, 41.5) accept crystals from the **s0 + 166 check**; a placement registers one tick
  after a check. **28 ticks after the second placement** "The Energy Laser is charging up!"; the
  laser is armed from the next check. The beacon that is the laser is put in at the
  **s0 + 206 check**, so that is the earliest possible stun.
- **A laser hit** lands on a check when: the laser is charged, Maxor's feet are within about
  **3.5 blocks horizontally of the beam (73.5, 73.5)** at his flying height (y 225-227.5), and
  **10 seconds of real (wall-clock) time** (measured 10.0-10.15 s) have passed since the
  previous hit. The cooldown is not in server ticks: with the server lagging, re-hits came
  110-191 ticks later.
- **Abilities.** 161 ticks after the beacon goes in (s0 + 367) or after he breaks free, Maxor
  uses an ability (taunts: "YOUR WEAPONS CAN'T PIERCE...", "YOUR MOBILITY TRICKS...", "I HOPE YOU
  LIKE EXPLOSIONS TOO!", "MY MINIONS WILL..."). A hit during an ability still lands, but silently:
  the stun line (and the freeze) come when the ability ends, **62 ticks after the taunt** (81-82
  after a "Wither Skulls" taunt).
- **After a hit**: the stun line (random: "THAT BEAM! IT HURTS!" or "YOU TRICKED ME!"), he
  freezes with his head at yaw 0. The top crystals come back at hit + 41, the placed ones vanish
  at hit + 42, the beam column goes black and the pylons reset at hit + 70.
- **Stun end ("⚠ Maxor is enraged! ⚠")** is off the checks, 1-240 ticks after the stun line; the
  longest were 238-240 (a 240-tick cap). He moves again 1-5 ticks after it. Presumably a damage
  threshold (conjecture: no health is recorded).
- **The kill.** After the second hit he dies when the party finishes him (median 6 ticks after the
  stun line in 5-player runs, 8-15 solo). The kill is visible as the beacon at (73, 221, 73)
  turning into **bedrock**. The wither despawns **80 ticks** later (79-81), and **Storm's first
  line is 22 ticks** after that: **kill + 102**. "I'M TOO YOUNG TO DIE AGAIN!" is a timer (second
  stun line + 82) that only shows if he is still there.
- **Targeting.** His head points at the **3D-closest living player** (97% of identified samples;
  where 3D and horizontal differ, 139 of 147 on the 3D one). While stunned the head is reset to
  yaw 0.
- **Movement.** No inertia: full speed from his first tick. Each tick he moves straight at the
  target's feet + ~1 block by **min(0.90, 0.24 + 0.020·d)** blocks, d the 3D distance (rms 0.043).
  He stops within about 3 blocks (hovering 2.7 horizontally, 1 above their feet). About 9% of
  ticks carry no move (tentative).
- **Fastest possible Maxor: 509 server ticks (25.45 s at 20 TPS)**: first hit at 206, second hit
  10.0 s later (200 ticks), kill 1 tick later, then the fixed 102. The best lag-free 5-player
  runs made 506-510 ticks (all within the chat lines' ±2 ticks of the floor). The median is 517.

## 1. Data

| | count |
|---|---|
| runs with Maxor's first line (recordings) | 208 (252) |
| runs where every recording has server ticks | 142 (128 five-player, 10 solo, 4 other) |
| server-timed runs with Maxor's move packets | 141 |

- Positions are the de-lerped move packets of `tools/boss-movement` (1/32-block grid, about
  every 2 server ticks). Other players are the recorder's view (interpolated, stale out of range),
  so movement laws use only targets whose own recording is in the run ("exact").
- Wall-clock time comes from the recorders' `time` lines (every 20 client ticks); good to
  about 0.05-0.1 s.
- **Not in the recordings**: Maxor's health (no boss bar or metadata), clicks on entities (the
  "CLICK HERE" stands), other players' abilities. Damage-number stands only appear near the
  recorder and are not usable for Maxor's health.
- One 2-player run (`d4bd4083`) had its intro 20/40 ticks early and the laser phase 85 ticks
  early; its absolute times are left out of the timeline table.

## 2. Timeline (fixed timers)

| event | ticks after s0 (p10-p90) | note |
|---|---|---|
| Maxor's wither spawns at (73, 226, 53); both top crystals appear | 5 (2-6) | |
| "I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU." | 62 (61-63) | |
| "DON'T DISAPPOINT ME, ..." | 124 (123-125) | |
| laser phase starts: beacon slot (73, 221-222, 73) cleared, pylons open | 166 check (block change seen 166-168) | = "DON'T DISAPPOINT" + 42-44 |
| first placements ("1/2 Energy Crystals are now active!") | 167: 166-168 in 120 of 140 runs | one tick after the check |
| Maxor starts moving | "DON'T DISAPPOINT" + 46 (45-47) = s0 + 170 | first move packet |
| "The Energy Laser is charging up!" (first) | 195 (194-305) | second placement + 28 |
| beacon placed at (73, 221, 73) | 206 (205-207) | laser phase start + 40 (the 4th check after) |
| first stun line | 206 (205-366); 96 of 128 five-player runs at 204-208 | |
| second stun line | 415 (405-525) | |
| kill (beacon becomes bedrock) | 416 (408-570) | |
| wither despawns | kill + 80 | |
| Storm's first line | 518 (510-672) | kill + 102 |

## 3. The 10-tick checks

Each run's checks were located from the beam column's block changes (1,119 of 1,481 on the modal
residue, 1,459 within ±1). Other events against those checks (0 = the check tick):

| event | -1 | 0 | +1 | +2 | +3 | rest | on the checks? |
|---|---|---|---|---|---|---|---|
| beacon placed | 9 | 104 | 22 | 2 | 0 | 3 | yes |
| column turns black (laser reset) | 20 | 106 | 13 | 0 | 0 | 0 | yes |
| top crystals reappear | 4 | 40 | 324 | 59 | 8 | 4 | yes (+1) |
| placement lines | 20 | 84 | 346 | 34 | 20 | 48 | yes (+1) |
| stun lines | 25 | 154 | 59 | 6 | 10 | 18 | yes |
| "charging up" | 156 | 29 | 6 | 2 | 5 | 76 | no: placement + 28 |
| death line (I'M TOO YOUNG) | 0 | 1 | 10 | 49 | 15 | 0 | no: stun + 82 |
| pickups | 45 | 119 | 58 | 45 | 40 | 246 | no |
| enrage lines | 19 | 23 | 16 | 8 | 15 | 58 | no |
| kill (bedrock) | 8 | 11 | 12 | 18 | 23 | 62 | no |

- The checks fall at s0 + 5/6/7 (mod 10) (43/77/15 runs), i.e. 1 tick after the wither's spawn
  tick. Like Storm's crush checks, they are probably counted from the boss appearing
  (conjecture).
- Chat lines arrive ±1 tick, hence the spread of ±1.

## 4. Energy crystals and the laser

### 4.1 Spawning and picking up (measured)

- Two end crystals appear at (64.5, 238.375, 50.5) and (82.5, 238.375, 50.5) with the wither
  (s0 + 5), with "Energy Crystal" / "CLICK HERE" name stands.
- "X picked up an Energy Crystal!" comes the moment a player takes one: any tick, not on the
  checks. The crystal entity disappears within 2 ticks of the line (401 of 485).
- After a laser hit both come back at **hit + 41** (40-42 in 356 of 422), one tick after a check.

### 4.2 Placing (measured, except the click)

- The pylons are at (52.5, 224, 41.5) (west) and (94.5, 224, 41.5) (east). A placed crystal
  appears on top at y 224.375, "Energy Crystal Missing" becomes "Crystal Active", and chat says
  "X/2 Energy Crystals are now active!".
- **Placements register on the checks** (one tick after them), never before the laser phase
  starts (s0 + 166). In runs where the carrier waited at the pylon, the placement came exactly
  on that first check (s0 + 167 line).
- **Standing next to the pylon is not enough.** Three carriers stood 2.8-3.3 blocks from their
  pylon at a check with the pylon open and were not placed, then were placed later. Carriers
  were placed at up to 3.5 blocks. So it is probably the right-click on the pylon's stand
  (conjecture: entity clicks are not recorded), taken at the next check.
- **The counter** "X/2": X = 1 + the placements earlier in this laser cycle whose 28-tick charge
  has finished (548 of 552 lines). So two crystals placed on the same check both say "1/2"; "2/2"
  only if the first finished charging; "3/2" (8 times) when a crystal was put in at hit + 60-69,
  before the pylons reset. `BossDetail.kt` says it is "1/2 for both" always: that is only the
  usual case.
- After a hit the placed crystals vanish at **hit + 42** (41-43), and the pylons reset
  (column black) at **hit + 70**. Re-placements came 61-431 ticks after a hit (median 81).

### 4.3 Charging (measured)

- Each placed crystal lights a line of 19 sea lanterns in the floor (y 221) toward the middle,
  then a bar in the ceiling (y 236) lights toward (73, 236, 69). **"The Energy Laser is charging
  up!" comes 28 ticks after the second crystal's placement line** (27-28; 233 of 274).
- The beam column (73, 222-224, 73) shows the state on the next check: **yellow** = one crystal
  charged, **red** = both (the laser is armed), **black** = discharged. The red comes 0-2 ticks
  after "charging up" (223 of 274): the laser can fire on the first check at or after that line.
- The beacon at (73, 221, 73) is put in at s0 + 206 whatever the crystals do (even in a run with
  no crystal placed). Before it the laser cannot fire: charged at s0 + 195, the first stun is
  s0 + 206.

### 4.4 When a hit lands

A laser hit needs, on a check (measured):

1. **The laser charged** (both crystals placed, "charging up" at or before the check) and the
   beacon in (from s0 + 206).
2. **Maxor in the beam area.** Checks with 1 and 3 satisfied and his interpolated position known
   (303 checks, 175 hits):

   | feet y \ horizontal distance from (73.5, 73.5) | 0-1.25 | 1.25-2.25 | 2.25-3.25 | 3.25-3.75 | 3.75+ |
   |---|---|---|---|---|---|
   | 225-227.5 | 117/120 | 42/42 | 14/14 | 0/1 | 1/7 (the hit: 3.76) |
   | 222-224.9 | - | - | 0/3 | - | 0/4 |

   - So: within about **3.5 blocks horizontally of the beam's centre**, with his feet at y 225 or
     more (he flies at 226 over a player on the 225 floor). The edge is uncertain by ~0.5 block
     (packets every 2 ticks at 0.5 blocks a tick). Hits reached z 70.1 on the north side,
     x 71.2 west, x 74.9 east.
   - Three misses inside at y 226:
     - `1471d25c` 306: "charging up" came on that very tick.
     - `56b5a217` 406: his taunt A came one tick later, and the kill 4 ticks later, so this was a
       silent hit (§5.3) whose line came 62 ticks on.
     - `e86c31a5` 406: 10.15 s after the first hit by the recorder's clock; hit on the next
       check.
   - **It is not the 1-block beam.** He is often stunned at z 70-72, 1-3 blocks short of it, as
     he flies in from the north.
3. **10 s of wall-clock time since the previous hit.** With 1 and 2 satisfied and no ability
   running:
   - no hit in 442 checks at 6.0-9.87 s;
   - 23 of 34 at 9.88-10.12 s (the earliest 10.00 s);
   - 38 of 41 later.

   Measured to ~0.1 s from the recorders' clocks, so the cooldown is 10.0-10.15 s.
   - In server ticks the re-hits came 200 ticks after the first when the server kept 20 TPS.
     They came as early as 110-191 ticks when it lagged: `1471d25c` 110 ticks = 10.95 s,
     `ca13433d` 151 = 10.35 s, `0640dc7d` 170 = 10.45 s.
   - The cooldown runs from the hit itself, not from the stun line or the enrage. A
     still-stunned Maxor sitting in the beam was re-hit 10.05-11.2 s after the first hit
     (5 five-player runs); a freed one back in the beam likewise.
4. **Not during an ability, or it is silent** (§5.3).

### 4.5 The check before the first stun

He leaves the spawn at s0 + 170 and needs ~35 ticks to reach the area (17 blocks at 0.5-0.7 a
tick). With the lure south of the beam he arrives at s0 + 204-206: the first stun lands on the
206 check (96 of 128 five-player runs at 204-208). Charged late: the first check after "charging
up" (e.g. second crystal placed on the 186 check: stun at 216).

## 5. Stun, abilities and the kill

### 5.1 The stun (measured)

- On a hit Maxor freezes where the check caught him (his next packets stop within 0-2 ticks) and
  says "THAT BEAM! IT HURTS! IT HURTS!!" (138) or "YOU TRICKED ME!" (134), at random.
- His head goes to yaw 0 (south) and stays there (6,172 of 7,437 stunned head samples), whoever is
  closest.

### 5.2 "⚠ Maxor is enraged! ⚠": the end of a stun

- **Stun line → enrage line**: median 128, p10 10, p90 185, range 1-240. For first stuns:
  - 28 ended within 30 ticks of the line (20 of the 24 five-player ones at 6-12 ticks);
  - the rest 30-199 after it, most 100-199;
  - the longest first stuns ended 238 and 239 ticks after the line. In two runs a second hit
    came during stun 1, and stun 1's enrage came at +240 exactly; he stayed frozen (by the second
    hit) and died.
  - So **a stun ends by itself after 240 ticks** (12 s): none lasted longer.
- The enrage lines are **not on the checks** (uniform residue): something players do ends the
  stun, most likely a damage threshold (**conjecture**: health is not recorded). The 6-12-tick
  group would be parties bursting him.
- He moves again **1-5 ticks after the enrage line** (median 2, n=56).
- After a silent hit (§5.3) the enrage can come before the deferred stun line. This happened in
  4 runs (3 solo), 9-25 ticks after the hit. The late stun line then did not freeze him: he was
  seen flying on in the solo runs.

### 5.3 Abilities (measured timing, meaning is conjecture)

- **First ability ("taunt A")**: one of "YOUR WEAPONS CAN'T PIERCE THROUGH MY SHIELD!", "YOUR
  MOBILITY TRICKS DON'T WORK IN MY DOMAIN!", "I HOPE YOU LIKE EXPLOSIONS TOO!", "MY MINIONS WILL
  HAVE TO WIPE THE FLOOR AFTER I'M DONE WITH YOU ALL!". It comes **161 ticks after the laser
  phase's beacon** if he was never stunned (16 of 17), or **161-164 after an enrage** (21 of 29).
  It was never seen while he was stunned.
- Later ones ("taunt B": "How about you taste some rapid fire Wither Skulls!", "Eat Wither
  Skulls, scum!", "Time for me to blast you away for good!") follow at irregular 60-160-tick gaps
  (few samples). The recorder's damage lines ("Maxor's Frenzy / Wither TNT / Shadow Wave hit
  you") came while he was free in 95 of 100.
- **A hit during an ability is silent.** The laser discharges on schedule (top crystals at hit +
  41, black at hit + 70), and the hit counts (for the cooldown and the kill). But the stun line
  comes only **62 ticks after a taunt A** (9 of 10; hit-to-line 13-53 ticks) or **81-82 after a
  taunt B**.
  - In 26 five-player runs the kill came before the second stun line. That line then came 61-64
    ticks after a taunt A (24) or 81-82 after a taunt B (2).
  - A taunt A line one tick after a check still made that check's hit silent (`56b5a217`). The
    ability starts on or before the check, and its line follows.
  - Typical case: the party breaks stun 1 within ~10 ticks. His taunt A comes at enrage + 162 ≈
    first hit + 172, just before the second hit is due (+200). The hit lands silently at +200 and
    the kill follows at once; the stun line appears ~30 ticks later.

### 5.4 The second hit and the kill (measured, cause conjecture)

- **The kill** is the beacon at (73, 221, 73) turning into bedrock (and the placed crystals
  vanishing). The **wither despawns 80 ticks later** (79-81 in 113 of 119), and **Storm's first
  line comes 22 ticks after that** (21-23 in 111 of 119): **kill + 102** (101-103 in 121 of 128
  five-player runs).
- It comes **after the second hit**, 0-29 ticks after the second stun line (median 6, p10 2, p90
  13; five-player median 6, solo 8-15), on any tick. That fits "he dies when his health reaches 0
  while stunned for the second time" (**conjecture**).
  - `94a44065`: hit 2 came while stun 1 was still running. The party did not finish him and he
    enraged, so a third hit was needed; it killed him on the spot (0 ticks).
- **"I'M TOO YOUNG TO DIE AGAIN!"** is said **82 ticks after the second stun line** (80-84, n=75),
  if he still exists: 63 of 64 runs whose wither lasted past stun 2 + 84 had it, 49 of 54 that
  despawned earlier didn't. It is not the death.
- The Scorecard/SubSplits "Animation" step is therefore a fixed 102 ticks from the kill. The kill
  is visible to the client as the block change at (73, 221, 73).

## 6. Targeting (head yaw, measured)

The wither's `headYaw` points at whom he is after (as for Storm). Head samples where the client's
head lerp had finished, no player within 4 blocks horizontally:

| state | head on one player | ...the 3D-closest | 3D ≠ horizontal: 3D / horizontal | median error to the 3D-closest |
|---|---|---|---|---|
| free (moving) | 5,453 | 5,309 (97.4%) | 139 / 8 | 1.8° |
| intro (not moving yet) | 41 | 41 | - | 2.2° |
| stunned | head at yaw 0 in 83% | - | - | - |

- **Target: the 3D-closest living player** (feet), also during the intro before he moves.
- **Retargeting speed** could not be measured: too few clean switches with exact positions.
- **Range**: no limit seen. In a solo run he flew at 0.90 a tick toward the only player, 80-96
  blocks away (heading within 4-7°).

## 7. Movement (measured)

- **No inertia.** The first move packet (s0 + 170) already carries 1.41 blocks (median, n=111),
  i.e. 0.70 a tick at the ~24 blocks to the lure. The next packets carry 1.38, 1.35, 1.29, 1.28,
  1.22: slowing as he closes in, never accelerating. The ~22%/tick easing in
  `maxor-storm-movement.md` §2 is not real. Presumably it absorbed stale target positions and
  packet timing in the fit.
- **Step per tick = min(0.90, 0.24 + 0.020·d)** straight at the target's feet + ~1 block, with d
  the 3D distance to that point.
  - The law was fitted on 2,173 packet pairs with the target's own recording, from packets that
    carried two ticks of movement: 0.241 + 0.0198·d, rms 0.043. It holds for aim points +0 to +2
    (rms 0.043-0.044).
  - Medians by distance: d 6-8: 0.37, 10-12: 0.46, 14-16: 0.55, 18-20: 0.63, 22-24: 0.68,
    28-30: 0.80.
  - **Cap 0.90**: beyond ~33 blocks the step is 0.88-0.91 (max seen 0.916), at 40 and at 95
    blocks alike. That is Storm's chase cap, and nearly the same profile as Storm's chase
    (`min(0.9, 0.2 + 0.023·d)`).
- **Height**: vertical speed is 0 when the target's feet are 1 block below him (−0.39 at 12 below,
  +0.36 at 9 above). He aims ~1 block above the feet.
- **Stopping**: standing still next to an exact target he is 2.7 horizontally (2.45-2.81), 1.0
  above their feet, **2.8-2.9 in 3D** (n=8). Consistent with "no move once within 3 blocks". The
  lure's usual spot (73.5, 225, 77.3) therefore parks him at z ≈ 74.5, inside the beam area.
- **Heading**: median 3° off the bearing to the target beyond 16 blocks, 6° at 8-16, 12° under 8
  (1/32-block rounding).
- **Moves missing (tentative)**. Packets come every 2 ticks and nearly always carry exactly two
  ticks of movement, even when their arrival is 1 or 3 ticks apart (jitter). But 287 of 1,483
  two-tick packets carry one tick's worth, with no make-up in the next packet. Over 82 chains of
  6+ packets (3,716 ticks) 9.3% of the ticks' moves are missing. That is like Storm's skipped
  moves, but the rate depends on the fitted step law, so treat it as 5-10%.

## 8. The fastest Maxor

### The floor

| step | ticks | who controls it |
|---|---|---|
| s0 → beacon (first possible hit) | 206 | fixed |
| first hit → second hit | 10.0 s wall clock = 200 ticks at 20 TPS | fixed (cooldown) |
| second hit → kill | ≥ 0-1 | damage |
| kill → despawn → Storm's first line | 80 + 22 = 102 | fixed |
| **total** | **≈ 509 ticks = 25.45 s** | |

- The first hit needs both crystals placed by the s0 + 176 check ("charging up" ≤ 206).
  Carriers must be at the pylons by ~s0 + 166 with the crystals taken earlier (they are
  available from s0 + 5). Maxor must be in the beam area at 206, i.e. lured straight south from
  his spawn.
- The second hit needs the laser recharged by first hit + 200. The crystals are back at +41 and
  the pylons reopen at +70, so a second placement any time up to hit + 171 is enough: there is
  lots of slack. He must also be in the beam area at +200. Left stunned, he still sits there;
  freed, he flies to the closest player, and a lure at (73.5, 225, 77.3) parks him in the area.
- Breaking stun 1 early does not by itself cost time: an ability then falls on the +200 hit, but
  the hit lands silently and the kill can follow at once. In the 26 runs killed before their
  second stun line, the kill came 200-226 ticks after the first hit.
- Lag does not help in real time: the cooldown is 10 s of real time whatever the tick rate.

### What the best runs did (5-player, server-timed)

Split median **517 ticks** (p10 510, p90 589); wall clock median 26.35 s (p10 25.80). The
fastest lag-free runs (`45424d55` 506, `31698d44`/`bfb04976` 509, `94d280aa`/`548526c8`/
`a0a219f8` 510) all did the same:

- **Crystals**: two carriers pick them up at s0 + 55-120 from the top platforms and wait at the
  pylons. Both are placed on the s0 + 166 check (lines at 164-167), "charging up" at 194-196.
- **Lure**: one player stands at (73.2-73.9, 225, 76.2-77.3), 3-4 blocks south of the beam (in
  `94d280aa`, in the beam at (74, 225, 73.7)). Maxor flies straight from his spawn, hovers 2.8
  blocks from the lure and is hit at 204-206.
- **Second cycle**: the carriers stand on the top platforms and take the crystals back at
  hit + 40-50 (s0 + 245-256), and place them at s0 + 276-350. He stays in the area (still stunned,
  or back over the lure after the enrage) and is hit again at exactly first hit + 200 (405-407).
- **Kill** 1-3 ticks after the second hit (407-409); Storm's line 101-103 later.

### Where the median run loses time (128 five-player runs, against 509)

| step | median | runs losing > 5 ticks | ticks lost in all |
|---|---|---|---|
| first hit after 206 | 0 | 32 | 2,061 |
| kill after first hit + 201 | 5 | 61 | 2,294 |
| Storm's line after kill + 102 | 0 | 0 | 36 |

Why the kill was late (61 runs):

- **29: slow kill.** The second hit was on time, but the kill took 8-15 ticks (p10-p90; median
  10).
- **14: Maxor not in the beam area at +200.** The second hit came 10-120 ticks late (p10-p90;
  median 11).
- **9: silent second hit in an ability**, with the kill more than 5 ticks after +201.
- **5: an ability ran at +200.**
- **4: the laser not recharged by +200.**

The runs at 429-501 ticks are lag, not speed. The 10 s cooldown in wall-clock time lets a lagging
server skip up to 90 ticks (`1471d25c` 429 ticks took 26.4 s).

## 9. Corrections to `docs/maxor-storm-movement.md` §2 (and the code comments)

- **"Maxor has inertia (his velocity eases toward that pursuit velocity, about 22% a tick)"**:
  wrong. He moves at full speed from his first tick (§7). The model's per-tick law is
  `min(0.90, 0.24 + 0.020·d)` with no easing, and his speed is capped at 0.90 like Storm's (the
  report left the cap open).
- **"Stun length ... bimodal ... a damage threshold with a timeout"**: the timeout is 240 ticks.
  The fast group (6-12 ticks) is presumably burst damage. It also matters less than it looks: the
  second hit is timed from the first, not from the enrage.
- **Stun position "x 71-75, z 71-76 ... at the lasers' meeting point"**: the check accepts anything
  within ~3.5 blocks of (73.5, 73.5) at y 225+. That reaches z 70.1, which is why he is often
  stunned 1-3 blocks before the beam.
- **"second stun line 406-416"**: that is the 10-second (wall-clock) laser cooldown from the first
  hit, plus the check it lands on; with lag it can be 110-191 ticks.
- **"I'M TOO YOUNG TO DIE AGAIN!" (said in 79 of 146)** is a timer (second stun line + 82), not the
  death. The death is the kill (beacon → bedrock) + 80 ticks, and Storm is kill + 102.
- **For maintainers (not changed here)**:
  - `BossDetail.kt` says the active-crystal line "says 1/2 for both"; it says 2/2 or 3/2 when an
    earlier crystal had finished charging (§4.2).
  - `SubSplits` "Dps" after the second stun ends at the kill, which is visible as the block change
    at (73, 221, 73) to bedrock; "Animation" is then a fixed 102 ticks.
  - `SubSplits.MAXOR_MOVE_TICKS = 46` is confirmed (45-47).

## 10. Measured vs conjecture, open questions

- **Measured**: the 10-tick checks and their phase; crystal timings (+28, +41, +42, +70, pylons
  open at the s0 + 166 check, beacon at the 206 check); the placement counter rule; the beam area
  (±0.5 block); the 10.0-10.15 s wall-clock cooldown; ability timing (161 after the beacon or an
  enrage) and the deferred stun line (+62 / +81-82); the 240-tick stun cap; the kill → despawn
  (+80) → Storm (+22) chain; the death line timer; targeting; the step law, the cap and no
  inertia.
- **Conjecture**:
  - the enrage (stun end) and the kill are damage thresholds (no health recorded);
  - placing takes a right-click on the pylon's stand;
  - the checks are counted from the wither's spawn;
  - the moves missing in ~5-10% of ticks are skipped ticks like Storm's.
- **Open**:
  - what the abilities do and whether he is damageable during one;
  - the "taunt B" schedule;
  - retargeting speed;
  - the exact shape of the beam area (circle vs box);
  - the 2-player run whose whole intro ran 20-40 ticks early (`d4bd4083`).
  - Recording Maxor's health (boss bar) and entity clicks would settle the first two
    conjectures.

## Reproducing

```
python3 tools/boss-movement/extract.py DATA_DIR MOVE_OUT && python3 tools/boss-movement/tracks.py MOVE_OUT
python3 tools/boss-mechanics/maxor/extract.py DATA_DIR MAXOR_OUT
python3 tools/boss-mechanics/maxor/analyze.py MAXOR_OUT MOVE_OUT     # sections: data timeline grid crystals laser stun death targeting movement fastest
```
