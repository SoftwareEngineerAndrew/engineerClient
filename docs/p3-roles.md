# P3 roles: jobs, spots, and every movement between them

What each role does in Goldor (P3) for every P3 Sim skill preset, where it stands, and the one fixed way
it gets from each job to the next, with its time in ticks (20 a second). Terms are a solved game: the moves
are the same every run, so the bots should play them the same every run, and only the job time is variable.

Written 2026-10-15. Sources, marked in the tables:

- **U**: your best P3 Sim runs (`config/engineerclient/p3sim-best/<skill>/<CLASS>.json.gz`), your
  real position every tick. Theoretical Archer 388 t, Tank 393, Healer 397, Mage 397, Berserk 413; PF,
  Quality PF and Prototype 1 runs too. These paths already work in the sim's world, so they are the best
  base for the bots.
- **A**: the fastest 30 F7 P3s in the ADR recordings (341 runs; fastest 584 t = 29.2 s), median / best
  of each transition. A real player on Hypixel, not the sim.
- **P**: your practice personal bests (`p3sim-pb.properties`). These run until the job is done, so
  they include the terminal.
- **E**: estimated. There is no clean recording of it yet.

Ticks are counted from leaving one stand to arriving at the next. They do not include standing at the
job, which is terminal or lever time. A leap is 1–2 t (Infinileap, onto whoever is on the target spot).

## Spots

| spot | x, y, z | notes |
|---|---|---|
| S1 T1 / T2 / T3 / T4 | 110.56 113 75.07 / 108.91 119 79.53 / 92.45 112 92.74 / 92.99 122 100.87 | |
| S1 east / west lever | 106.74 122 111.7 / 94.46 122 111.43 | east = `ll` (left facing the gate) |
| S1 SS | 108.3 120 94.0 | default spawn (all but Berserk) |
| S2 T1 / T2 / T3 / T4 / T5 | 69.69 109 122.4 / 59.57 120 125.24 / 45.86 109 122.43 / 39.5 109 140.53 / 39.69 124 125.53 | Theoretical T2: 59.5 120 125.2, top of the stairs |
| S2 high / low lever | 23.3 132 138.47 / 27.33 124 127.68 | high = `ll` |
| S2 Lights (normal EE2) | 60.5 132 139 | Theoretical mage spawn |
| **i2** (far EE2) | 35.99 131 138.96, facing 92.2 / 3.9 | Theoretical EE2, the cee3 practice start |
| S3 T1 / T2 / T4 | 0.89 109 112.44 / 1.0 119 93.6 / 0.45 109 78.24 | |
| **S3 T3 = high EE3 (cee3)** | 18.7 121.5 91.3 | Theoretical/Prototype EE3; Theoretical bers mines quartz x15-16 y122 z93-94 |
| EE3 low (normal EE3) | 2.0 109 102 | PF / Quality PF |
| S3 west / east lever | 2.44 122 55.68 / 14.67 122 55.44 | west = `ll` |
| S3 Arrows | 0.36 117 79.63 | |
| S4 T1 / T2 / T3 / T4 | 41.12 109 32.96 / 43.96 121 31.14 / 66.85 109 32.04 / 72.78 115 45.45 | |
| S4 low / high lever | 83.48 120.06 35.81 / 85.15 126 44.7 | low = `ll` |
| S4 Target (i4) | 63.5 127 35.5 | Berserk spawn |
| **core front (EE4)** | 54.5 115.06 50.5 | Theoretical EE4, everyone's core spot |
| recore | 52.5 115 57.6 | |

## The move catalogue

These are the canonical moves. A bot always makes a move the same way and on the same path: a straight
line between its turns, never a recomputed search. **Fixed** is the time a bot should take. It is your
own sim time where one exists, otherwise the ADR median; best times show how much room is left.

### S1

| move | how | fixed | best | source |
|---|---|---|---|---|
| east lever → west lever | walk the ledge (12.3 blocks) | 12 | 12 | U 12–14 (A 6/5, from closer click spots) |
| west lever → S1 T4 | walk, step down | 12 | 12 | U |
| S1 T4 → S1 T3 | run off the edge, drop | 15 | 15 | U 15; A 20/18 |
| S1 T2 → S1 T1 | drop | 12 | 9 | A 12/9 |
| S1 T1 → S1 T3 | run, jump, drop | 31 | 28 | A 31.5/28 |
| S1 SS → S1 T2 | run | 20 | 17 | A 20.5/17 |
| S1 SS → S1 T4 | jerry + run | 35 | 22 | A 35/22 |
| S1 SS → east lever | bonzo + run | 15 | 14 | A 15/14 |
| Lights spawn → i2 | walk west along the top (24.6 blocks) | 25 | 25 | U 25, 25, 26 |
| west lever → Lights (normal EE2) | lava bounce, run, drop | 114 | 103 | A 114/103 |
| any S1 job → Lights / i2 | leap onto the EE2 player | 1 | 1 | U, A |
| S4 Target (i4) → i2 / Lights | leap | 1 | 1 | U |

### S2

| move | how | fixed | best | source |
|---|---|---|---|---|
| i2 → S2 high lever | walk | 11 | 10 | U 10–13 |
| i2 → S2 T5 | walk, drop | 18 | 18 | U 18, 20 |
| i2 → S2 T2 (top of stairs) | walk, drop | 29 | ≤29 | U 32; P 29 *with* the terminal (Mage) |
| Lights → S2 T2 | run, drop | 24 | 20 | A 24/20 |
| Lights → S2 T1 | run, drop | 33 | 29 | A 33/29 |
| Lights → S2 T4 | walk, drop | 44 | 44 | U 44, 45 |
| Lights → S2 high lever | bonzo | 35 | 31 | A 35/31 |
| Lights → S2 T5 | bonzo | 40 | 37 | A 40/37 |
| S2 high lever → low lever | walk, drop | 16 | 14 | U 16 |
| S2 low lever → S2 T4 | walk, drop | 31 | 31 | U 31 |
| S2 T4 → low lever | lava bounce (+bonzo) | 38 | 34 | U 38; A 39/34 |
| S2 T5 → S2 T3 | drop | 17 | 12 | A 17/12 (U 33–40: a slower way, use the drop) |
| S2 T2 → S2 T1 | walk, drop | 28 | 28 | U 28 |
| S2 T1 → S2 T3 | leap onto T3 | 3 | 3 | A |
| S2 T3 → low lever | lava bounce | 32 | 29 | A 32/29 |
| S2 high lever → EE3 low | run, bonzo, drop | 67 | 65 | U 65, 77; A 71.5/67 |
| **i2 → high EE3 (cee3)** | high lever side, bonzo / pet rod up | 93 | 93 | U 11 + 82; P 97 (cee3) |
| **S2 T3 → core front (archer EE4)** | lava bounce, mine in | 88 | 88 | U 88 (Theoretical), 97 (Prototype) |
| S2 job → EE3 low / high EE3 | leap onto the EE3 player | 1 | 1 | U, A |

### S3

| move | how | fixed | best | source |
|---|---|---|---|---|
| EE3 low → S3 T1 | run | 10 | 10 | U 10; A 12.5/10 |
| EE3 low → S3 T4 | walk south | 32 | 32 | U 32, 34 |
| EE3 low → high EE3 | lava bounce | 45 | 44 | U 45; A 46.5/44 |
| EE3 low → S3 T2 | lava bounce | 46 | 45 | A 46/45 |
| high EE3 → S3 T1 | walk, drop | 30 | 30 | U 30 |
| high EE3 → S3 T2 | walk, drop | 17 | 17 | U 17 |
| high EE3 → S3 Arrows | bonzo | 24 | 22 | A 24/22; U 25, 28 |
| high EE3 → S3 T4 | leap onto T4 | 2 | 2 | A; U 18 (leap + walk) |
| S3 Arrows → S3 T4 | walk | 8 | 8 | U 8, 9 |
| S3 T2 → S3 Arrows | run | 27 | 16 | A 27.5/16 |
| S3 T1 → S3 T4 | leap onto T4 | 2 | 2 | A |
| S3 T4 → west lever | lava bounce | 39 | 35 | A 39.5/35 |
| S3 T4 → east lever | bonzo + lava bounce | 43 | 36 | A 43/36; U 44, 45 |
| west lever → east lever | bonzo | 16 | 7 | A 16/7 |
| any S3 job → core front | leap onto the EE4 player | 2 | 1 | U, A |

### S4 and the core

| move | how | fixed | best | source |
|---|---|---|---|---|
| core front → S4 T1 | run, drop | 24 | 21 | U 24, 29; A 28/21 |
| core front → S4 T2 | lava bounce | 37 | 27 | U 37; A 40/27 |
| core front → S4 T3 | run, drop | 20 | 11 | U 22; A 20/11 |
| core front → S4 T4 | run | 18 | 18 | U 18 ×3; A 20/18 |
| core front → S4 low lever | lava / bonzo | 44 | 44 | U 44; E (A T3 → low lever 34/27) |
| S4 low lever → high lever | jerry | 16 | 15 | U 16; A 19/15 |
| S4 high lever → low lever | bonzo | 15 | 8 | A 15/8 |
| S4 T4 → high lever | lava bounce | 34 | 32 | A 34/32 |
| S4 T1 → S4 T2 | lava bounce | 13 | 9 | A 13/9 |
| S4 T2 → S4 T1 | drop | 14 | 11 | A 14.5/11 |
| S4 T4 → core front | run | 19 | 18 | U 18, 19; A 21/20 |
| core front → recore | walk | 9 | 1 | A 9 |
| any S4 job → core | leap onto whoever is in | 2 | 2 | U, A |

## Theoretical (the one to get exact)

Roles: Healer `ss gate / 1 / 1 / 1`; Berserk `i4 / ee3 / 3 bl / bl`; Tank `21 / 2 / i3 4 / 2`; Mage
`i2 ee2 / bl 4 / 2 / 3`; Archer `bl 43 / 53 / ee4 / 4`.
EE2 is the mage on i2. EE3 is the bers on high EE3. EE4 is the archer on core front.

Each row gives the job, the plan's time for it (seconds into that section), the move to the next job and
its fixed ticks. *Slack* is the time left over for the job itself once the fixed move is taken.

### Mage

| section | job (plan) | then: move | fixed |
|---|---|---|---|
| S1 | Lights 0.05 (spawns on it) | Lights → i2, on it by 1.0 | 25 |
| S1 | holds i2: bers, tank, archer leap on | i2 → S2 high lever, before S2 opens | 11 |
| S2 | high lever 0.05, gate 0.1 (superboom) | high → low lever | 16 |
| S2 | low lever 0.85 | low lever → S2 T4 | 31 |
| S2 | T4 3.7 (≈1.9 s to solve) | leap onto bers (high EE3) → high EE3 → S3 T2 | 1 + 17 |
| S3 | T2 3.0 | leap onto archer (core front) → S4 T3 | 2 + 20 |
| S4 | T3 3.0 | core | – |

### Archer

| section | job (plan) | then: move | fixed |
|---|---|---|---|
| S1 | east lever at spawn, west lever ≈0.6 | east → west lever | 12 |
| S1 | | west lever → S1 T4 | 12 |
| S1 | T4 5.3, T3 6.8 | T4 → T3 | 15 |
| S1 | | leap onto mage (i2) → S2 T5, there as S2 opens (S1 ends ≈7.8) | 1 + 18 |
| S2 | T5 2.0 | T5 → T3 (drop) | 17 |
| S2 | T3 3.95 | T3 → core front (lava bounce, mine in): EE4 | 88 |
| S3 | holds core front: healer, mage, bers leap on | core front → S4 T4 | 18 |
| S4 | T4 2.5 | core | – |

### Tank

| section | job (plan) | then: move | fixed |
|---|---|---|---|
| S1 | T2 2.6 | T2 → T1 | 12 |
| S1 | T1 4.9 | leap onto mage (i2) → S2 T2 (top of stairs) | 1 + 29 |
| S2 | T2 1.8 | leap onto bers (high EE3) → S3 Arrows | 1 + 24 |
| S2 | Arrows (i3) 3.5 | Arrows → S3 T4 | 8 |
| S3 | T4 2.5 | leap onto archer (core front) → S4 T2 | 2 + 37 |
| S4 | T2 2.0 | core | – |

### Healer

| section | job (plan) | then: move | fixed |
|---|---|---|---|
| S1 | SS; gate 5.6 with the explosive bow during SS's 4th cycle; SS done 7.8 | leap onto tank (S2 T2) → S2 T1 | 1 + 28 |
| S2 | T1 3.5 | leap onto bers (high EE3) → S3 T1 | 1 + 30 |
| S3 | T1 4.0 | leap onto archer (core front) → S4 T1 | 2 + 24 |
| S4 | T1 3.25 | core | – |

### Berserk

| section | job (plan) | then: move | fixed |
|---|---|---|---|
| S1 | i4 3.4 (spawns on it) | leap onto mage (i2) → high EE3 (cee3) | 1 + 93 |
| S2 | on high EE3 by 1.0 (from 3.5 s in S1: 4.3 + 1.0 s = 106 t for a 94 t move), mines the quartz; tank, healer, mage leap on | – | – |
| S3 | T3 1.6 | leap onto tank (S3 T4) → west lever | 2 + 39 |
| S3 | | west → east lever (bonzo) | 16 |
| S3 | levers + gate 4.5 | leap onto archer (core front) → S4 low lever | 2 + 44 |
| S4 | | low → high lever (jerry) | 16 |
| S4 | levers 3.0 | core | – |

### Where Theoretical is tight

- **Bers S3 levers:** T3 is done at 32 t. Then leap (2), T4 → west lever (39) and west → east (16) bring
  it to the east lever at 89 t, against a plan of 90. Only the bonzo's best (7 instead of 16) leaves
  any room.
- **Bers S4 levers:** leap (2), core → low lever (44) and low → high (16) arrive at 62 t, against a plan
  of 60. The core → low lever move is estimated: your one run took 44, the ADR T3 → low lever took 27–34.
- **Archer S1 → S2:** T3 at 6.8 s, the leap, then i2 → T5 (18 t) gets there at 7.75 s, just as S1 ends
  (≈7.8).
- **Archer EE4:** S2 T3 is done at 3.95 s. The 88 t move puts it on core front about 0.5 s before S3 ends
  (4.5 s), which is just in time for the leaps.
- Everything else has at least 0.5 s to spare at fixed speed.

## PF, Quality PF (= Dynamic), Prototype 1

Their roles and times are in `src/p3sim/Roles.kt`, and they use the same moves from the catalogue. Spots:
PF and Quality PF use Lights as EE2, EE3 low, and core front for the core. Prototype 1 uses EE2
34.05 131 139.05 (≈ i2) and EE3 16.5 123 93.7 (≈ high EE3), and has no EE4.

Things the fixed moves show up:

- **PF / Quality PF mage EE2 is faster in the plan than any recorded way.** The mage does both S1 levers
  and the gate (QPF 2.05 s, PF 2.85 s), then needs west lever → Lights: A 114 / best 103 t. That puts
  Lights at ≥ 7.2 s (QPF) or ≥ 8.0 s (PF). The plans say Lights 5.1 / 7.1 and on the spot 5.8 / 7.8.
  The bots currently make that time only by moving at up to 4× speed. Either the plan times are wrong
  or the route is (an i2-style far EE2 would need someone other than the mage on Lights).
- **PF mage S3 "core"** (S2 T2 → into the core during S3): there's no clean move for it yet. ADR has 15
  core early enters straight after S2 T2, but the route isn't extracted. E.
- **Quality PF tank EE3** (S2 high lever → EE3 low, 67 t): ll is at 0.8 s and the spot at 3.2 s. That is
  48 t of room, so the move doesn't fit at fixed speed. Planned EE3 at ≥ 4.2 s, or the S2 lever goes
  to someone else.
- Prototype 1: tank `ee2` holds 34.05 131 139.05 (use the i2 moves); bers `ee3` is the cee3 move (93).
  The archer's S2 (`2`: T2 after the levers and T1) and its S3 dev (Arrows from core) have no clean
  moves. Your Prototype archer run took 100 t+ legs there. E.

## How the sim should use this (proposal)

1. **One table of legs** (`Legs.kt`). Each leg has its from-stand, its to-stand, how it's done, a fixed
   path of turn points (taken from your best-run frames where you've done it, else from the ADR fastest
   sample) and its fixed ticks. It covers everything in the catalogue above.
2. **Bots play the leg exactly**: the same path at the same speed, every run. They stop being sped up to
   meet the plan, they stop searching the recorded-runs graph, and they no longer use Routes.simplify,
   THEORY_HURRY or the Moves recorder. If a plan time can't be met, the job is what gives (it's done
   the tick the bot arrives); the move doesn't.
3. **Times that check themselves**: at plan load, each role's timeline (fixed moves plus its jobs) is
   checked against the plan's times, and the Plan tab shows each job's slack (red when negative). The
   tight spots above would show up there, as would the PF mage problem.
4. A leg with no path yet (marked E) falls back to today's route search until it's filled in.
