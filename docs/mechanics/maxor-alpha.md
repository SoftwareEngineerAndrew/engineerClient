# Maxor on the alpha server (F7 P1), and the fastest possible phase

Measured from the **Boss Recorder** files of one evening on Hypixel's alpha network (2026-10-02
21:48 to 10-03 00:28, p3wr's client; party AscentPvP, _Cynapse_, Ryaxzz, catweeyw, p3wr), against
the same day's main-server runs. Scripts: `tools/boss-mechanics/maxor/bossrec/` (README there).
The main-server fight is in [maxor.md](maxor.md); everything here that is not marked as alpha-only
was checked on both.

Times are **server ticks after Maxor's first line** ("WELL! WELL! WELL!", s0), on the corrected
clock below. "Check" is the fight's 10-tick heartbeat ([maxor.md](maxor.md) §3).

## The clock

The Boss Recorder's `n` is **not** a server tick count on today's Hypixel. Hypixel wraps every
packet that changes your own entity flags (set_entity_data index 0) between two extra pings
(`pg, d(self), pg`, inside a bundle), and the recorder before 0.6.17 opened bundles and counted those
pings too: `n` ran 9-18% ahead
of the server (592 pings for 500 ticks in a main run). Odin and Better PF count only top-level
pings and are unaffected (Better PF `st`/client ticks 0.99 on 10-02). The scripts rebuild the clock
from the server's own `gameTime` (sent every 20 ticks), counting the remaining pings within each
second when exactly 20 are left (60-75% of seconds) and client ticks otherwise. On tonight's main
runs this reproduces [maxor.md](maxor.md)'s September timeline to the tick (lines at 62 and 124,
placements 166-168, charge 193-196, beacon 206, stuns 206 / 406, Storm at kill + 102).

## Summary: alpha against main

| | alpha | main |
|---|---|---|
| "I'VE BEEN TOLD..." / "DON'T DISAPPOINT..." | 41 / 83 | 62 / 124 |
| pylons open (first placement possible) | **beacon − 40 ≈ 79** | beacon − 40 = 166 |
| Maxor starts moving | 82 | 170 |
| beacon in = first possible hit | **≈ 119** (116-122) | 206 |
| charge: second placement → "charging up" | 27-28 | 27-28 |
| top crystals back after a hit | hit + 40 (on the check) | hit + 40/41 |
| placed crystals removed after a hit | hit + 41 | hit + 42 |
| pylons take crystals again | at once (right-click hit + 45, proximity the hit + 50 check) | re-placed from hit + 61 (no hurry: the cooldown) |
| second hit at the earliest | **hit 1 + 80** (the crystal cycle) | hit 1 + 10 s wall clock (200 ticks) |
| Maxor's health after stun 1 | never below 25% | never below 25% |
| kill → Storm's first line | **62**, or later if nobody has dropped into Storm's arena | 102 |
| fastest possible phase | **≈ 253 ticks (12.65 s)**: hit 2 on hit 1 + 70 | 509 ticks (25.45 s) |
| best run | **254** (10-03 04:12, Odin 12.759 s) | 509 |

The alpha fight is the same machine with shorter timers and **no 10-second laser cooldown**: the
second hit is limited only by how fast the crystals can go round again.

## The crystal cycle

1. **Spawn.** Two end crystals appear on the top platforms (64.5 / 82.5, 238.4, 50.5) with the
   wither (s0 + 1-2), and again **40 ticks after every hit**, on the check.
2. **Pickup** ("X picked up an Energy Crystal!") is instant, on any tick. Carriers waiting on the
   spawn spot pick up on the respawn tick itself.
3. **Placement** works two ways (Andrew; the data agrees):
   - **proximity**, taken on the checks: a carrier standing on the pylon's platform within about
     2 blocks of it (placed at up to ~3; never past 3.3; carriers ~1 block above the platform
     mid-leap, or 3 below it, were not placed);
   - **right-click** on the pylon, taken on whatever tick it arrives. The off-check placements in
     the data (5 in seconds with a clean clock, one 6 ticks after its pickup) can only be these.
   A pylon that holds a crystal takes no other. Pylons open on the check 40 ticks before the
   beacon (alpha s0 + 79, main 166) and, after a hit, again at once.
4. **Charge.** "The Energy Laser is charging up!" comes 27-28 ticks after the second placement;
   the beam column turns red on the next check and the laser can fire from that check.
5. **Hit.** On a check with the laser charged, the beacon in, and Maxor over the beam (hits were
   within 2.7 blocks of (73.5, 73.5) horizontally, median 1). His synced health flips 1 → 1000
   (damageable) on the hit tick; the stun line follows ~6 ticks later on alpha.
6. **Reset.** Placed crystals vanish at hit + 41; the top ones are already back at hit + 40.

So, counted from a hit: respawn **+40** → placed by proximity on the **+50** check (line +51), or
by right-click up to **+52** → charged **+78-80** → fires on the **+80** check. A carrier one tick
late for the +50 check (without a right-click) costs a full check: the hit moves to +90.

The first cycle has slack: pylons open at beacon − 40, and anything placed by beacon − 28
(proximity on the first or second check after opening, s0 + 79 or 89) is charged by the beacon.

## Health

The boss bar (Maxor's real health; his synced health is only the 1 / 1000 vulnerable flag) never
went below **0.25 between the two hits**, on either server, even when the party burst him to it
within 6-10 ticks of the first hit. So stun 1 cannot kill him: two laser hits are always needed.
After the second hit the party takes the last 25%: 2-7 ticks in good runs. The kill is the
beacon turning into bedrock. **Storm's first line comes 62 ticks later, or about 2 ticks after the
first player falls below y ≈ 196 into his arena, whichever is later.** All 64 alpha runs at kill + 62
had someone below y 195 by then. The 6 slower ones (kill + 63 to 83, all on 10-03 after 01:00, with the
kill only 1 tick after the hit) had everyone still up on Maxor's level at kill + 62, and the line came
-2 to +3 ticks from the first player crossing y 196-197. A fall from Maxor's floor (y 221) to y 196
takes about 27 ticks, so one player has to step off by about kill + 33.

Not settled: what ends stun 1 (the enrage). It came 6-110 ticks after the first hit and is not
simply "reached 25%" (one run enraged at 0.33); it does not matter for the fastest phase.

## The fastest Maxor on alpha

| step | ticks | who controls it |
|---|---|---|
| s0 → beacon (first possible hit) | ≈ 119 | fixed |
| first hit → second hit | 80, or 70 if both crystals are in by hit 1 + 42 | the crystal cycle: 40 + 28, onto a check |
| second hit → kill | 1 | damage (the last 25%); 1 tick in most 10-03 runs after 01:00 |
| kill → Storm's first line | 62 | fixed, if a player is below y ≈ 196 by then |
| **total** | **≈ 253 ticks = 12.65 s** (+70; best 254), **263 = 13.15 s** (+80) | |

What it takes:

- **Cycle 1**: both crystals placed by the s0 + 89 check (or right-clicked by ~s0 + 91). Both
  carriers waiting on their pylon at the s0 + 79 check is the safe version.
- **Maxor in the beam at s0 + 119**: he leaves his spawn at s0 + 82; a lure just south of the beam
  (as on main) parks him there in time.
- **Cycle 2**: both carriers standing on the top spawn spots at hit + 40, picking up on the respawn
  tick, and on the pylon platform by the **hit + 50 check** — or right-clicking the pylon by
  hit + 52. That is 10-12 ticks from pickup to pylon: a leap to the player standing at the pylon,
  with the menu opened at once.
- **Burst at hit 2**: everyone damaging him on the tick he becomes damageable.
- **Someone in Storm's arena by kill + 60**: one player steps off Maxor's level by about kill + 33.

**Hit 2 on the +70 check (10-03, 01:05-02:51 runs).** The carriers now teleport from the top spawn
spots to the pylons and place by right-click, often within 0-6 ticks of the respawn: pickup, teleport
and placement all on the respawn tick itself (hit + 40) in four runs (02-17-16, 02-31-12 east;
02-46-57, 02-48-38 west; clean clock; the crystal
placed then survives, only the previous cycle's crystals are cleared at hit + 41). Per pylon, the
first placement after hit 1 was at +40 / +40 / +43 / +45 ... (west, 31 runs) and +40 / +40 / +43 /
+45 ... (east, 27 runs); the best run had its later crystal at +46 (three runs). Both by **+41** puts
the charge (+28) at +70 and hit 2 on the +70 check: 120 + 70 + 1 + 62 = **253 ticks (12.65 s)**.
**Confirmed** (10-03 04:12, `2026-10-03_04-12-23_F7`, Odin 12.759 s): beacon and hit 1 at 120,
crystals back at 160, both placed at 161 (hit + 41, the last tick that makes it), charging 188,
hit 2 at 191 (the +70 check), kill 192, Storm 254. There is no cooldown between hits on alpha; the
crystal cycle is the only limit. The cutoff is hit + 41, not +42: the charging line always comes at
least a tick before the check it fires on (64 of 64).

The team's best runs are at this floor: **264** (00-24-45: hit 119, hit 199, kill 202, Storm 264),
265, 266, 267, 269 (and 263 twice on 10-03: 01-39-09, 02-02-53). Placed by proximity (the +50 check)
or by a leap that lands after hit + 42, the second hit is on the +80 check and that floor holds:
the beacon and the animation are timers, two hits are required, and the crystals respawn at +40 and
need +28 to charge onto a check. Only right-click placement within 2 ticks of the respawn (above)
gets under it. **12.2 s (244) is out of reach**: it needs hit 2 on the +60 check, crystals in by
+32, before they exist.

## Where tonight's alpha runs lost time

35 complete phases (Storm reached), against floor = beacon + 80 + 2 + 62:

| cause | ticks lost per run (mean) |
|---|---|
| cycle 2: a crystal placed after hit + 52 (hit 2 at +90, +100...) | 45.5 |
| cycle 1: crystals not charged by the beacon | 31.0 |
| hit 1 after the beacon with the laser charged (Maxor not over the beam) | 12.2 |
| kill more than 2 ticks after hit 2 | 7.5 |

By carrier ("on time" = cycle 1 placed by beacon − 28, cycle 2 by hit + 52):

| carrier | cycle 1 on time | cycle 2 on time | cycle 2: pickup / at the pylon / placed (median, after the hit) |
|---|---|---|---|
| _Cynapse_ (west) | 34 / 38 | 21 / 35 | +40 / +50 / +51 |
| AscentPvP (east) | 24 / 35 | 10 / 33 | +42 / +57 / +61 |

The east carrier's leap typically lands 10-17 ticks after his pickup (in the traces looked at he jumps on
the top platform first), missing the +50 check by 1-8 ticks; each miss is 10 ticks of phase. The
west carrier lands 8-10 ticks after his. A right-click on landing rescues a +51/+52 arrival only.

## Measured vs conjecture

- **Measured**: the alpha timers in the table; the 40-tick respawn and the +80 cycle; placement on
  checks by proximity and off-check by right-click; the charge time; the 25% health floor between
  hits (boss bar, both servers); kill → Storm 62 with someone below y ≈ 196; the clock correction.
- **Conjecture / approximate**: the proximity shape and radius (about 2-3 blocks on the platform;
  mid-air carriers were refused, but the sample is small and a right-click on a check looks the
  same as proximity); whether +52 is the exact right-click cutoff for the +80 check (from the
  charge rule, not observed directly); what ends stun 1.
- **Not covered**: Maxor's abilities on alpha (none got in the way of a fast run tonight).

## Reproducing

```
cd tools/boss-mechanics/maxor/bossrec
MAXOR_OUT=/tmp/maxor python3 extract.py 2026-10-02     # every Boss Recorder file since that date -> per-run timelines
MAXOR_OUT=/tmp/maxor python3 cycle_table.py            # the crystal cycle, run by run
MAXOR_OUT=/tmp/maxor python3 losses.py                 # each carrier's trip, pickup -> leap -> pylon -> placed
MAXOR_OUT=/tmp/maxor python3 ledger.py                 # ticks lost against the floor, by cause
MAXOR_OUT=/tmp/maxor python3 placerule.py alpha        # placed / not placed on each check, by distance
```
