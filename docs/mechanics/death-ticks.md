# Goldor's death ticks (F7 phase 3)

Every 60 server ticks during phase 3, Goldor kills (or burns a save of) every player standing in
certain zones. This page is the rule, the zones, the evidence for each edge, and what is still
unprobed. The data behind it is the three probe runs of 2026-10-04 (own position, one sample per
death tick, [death-ticks-probes.csv](death-ticks-probes.csv)); the older Better PF recordings only
supply the timing and the hit signature. Sim implementation: `p3sim/GoldorPhase.kt`.

## The rule

At each death tick, a player is hit if their **feet position** is inside one of the four section
zones (table below) and either

1. the zone's section has **not started yet** (it is ahead of the section in progress), or
2. it is the section in progress **and Goldor has left his track segment for that section**: he has
   reached that segment and walked on past it. While he is still behind it (his start stretch on the
   S4 line, or on the previous section's line before his catch-up sprint) the zone is safe.

A zone goes passive once its section starts (Andrew, 2026-10-04): zones of finished sections are
never hit. (The probe runs all stayed in S1, where every other zone is still ahead, so they could
not show this.)

Goldor's start stretch on the S4 line, before the S1 corner (his first lap, from "Who dares
trespass" until he reaches the S1 corner), counts as being in S1. So at the start of the phase the
S1 zone is safe, and S2, S3 and S4 are lethal.

- The zones are exactly four block-aligned rectangles, one per section. Between them, at the
  gates, there are gaps; the gaps, the core box and the middle of the arena are never hit.
- Test: feet position, y 106 (inclusive) to 146 (exclusive), x/z half-open `[min, max)`.
- The Wither Cloak (Creeper Veil) does **not** stop death ticks.
- The hit is lethal: the player dies, or a mask/pet save fires in the same tick (Spirit Mask,
  Bonzo's Mask, Phoenix Pet, Revive Stone). Hit evidence in chat is `[BOSS] Goldor: What do you
  think you are doing there!` together with your own mask proc or death on the same tick. The line
  plays one quiet `entity.wither.ambient` (HOSTILE, volume 1.0, pitch 1.0).
- "Punishment served, nothing survives my reach." is a **separate** Goldor melee attack. It procs
  masks too, but it is not a death tick and is not modelled.

## Zones

| section | x1, y1, z1 | x2, y2, z2 |
|---|---|---|
| S1 | 90, 106, 26 | 114, 146, 121 |
| S2 | 20, 106, 122 | 114, 146, 146 |
| S3 | -6, 106, 51 | 18, 146, 146 |
| S4 | -6, 106, 26 | 90, 146, 50 |

Gaps (never hit): z 121 to 122 between S1 and S2 (x 90 to 114), x 18 to 20 between S3 and S2
(z 122 to 146), z 50 to 51 between S4 and S3 (x -6 to 18), and everything inside neither
(x 18 to 90 at z 51 to 121 is the middle and core).

## Timing

Death ticks land at server tick `n = 60k - 1` after the tick that received `[BOSS] Goldor: Who
dares trespass into my domain?` (k = 1, 2, ...), for the whole phase, not reset by sections. The
chat line itself is stamped ±1 tick, so a hit's evidence can sit one tick early (see Open
questions). From the Better PF recordings: gaps between consecutive lines 60 ± 1-2.

## Evidence

All samples are in [death-ticks-probes.csv](death-ticks-probes.csv). The probes: 11:45 (164
samples), 11:59 (30), 12:14 (226); in all three the section in progress was S1 throughout (no
section was completed). Goldor's segment is known for
11:45 and 12:14, not for 11:59. "k" is the death tick number.

### Per edge: safe sample just outside, hit sample just inside

| edge | safe (outside) | hit (inside) |
|---|---|---|
| S1 x 90 (west) | x 89.58, 12:14 k170 | x 90.30, 11:45 k73 |
| S1 x 114 (east) | x 114.30, 11:45 k50 | x 113.30, 11:45 k51 |
| S1 z 121 (north, S1/S2 gap) | z 121.47, 11:45 k66; z 121.99 (x 69.3), 11:45 k131 | S1: z 119.54, 11:45 k51 |
| S1 z 26 (south) | z 25.30 / 25.52, 12:14 k146-148, k150 | z 26.59, 12:14 k149; z 27.70, 11:45 k102 |
| S2 z 122 (south) | z 121.99, 11:45 k131 (x 69.3) | z 122.30, 11:45 k125 |
| S2 z 146 (north) | z 146.30, 11:45 k9 | z 145.53, 11:45 k13 |
| S2 x 114 | x 114.30, 11:45 k12 | x 113.30, 11:45 k13 |
| S3 x -6 (west) | x -6.70, 11:59 k27 | x -5.54, 12:14 k76 |
| S3 x 18 (east) | x 18.45, 11:45 k143 | x 17.70, 11:45 k145 |
| S3 z 146 | z 146.35, 11:59 k24 | z 145.30, 11:59 k23 |
| S4 z 50 (north) | z 50.33, 11:45 k154 | z 49.70, 11:45 k153 |
| S4 z 26 (south) | z 25.30, 12:14 k127 | z 26.30, 12:14 k105 |
| S4 x -6 (west) | none | x -5.67, 12:14 k105 |
| y top (146) | y 146.00 inside S2 x/z, 12:14 k6 (safe: exclusive); 147-149 safe | y 145.00, 12:14 (many) |
| y bottom (106) | y 105.88, 12:14 k213 | y 106.00, 12:14 k210 |

The y 146 sample (90.30, 146.00, 122.18) is at a lethal zone's top face and was not hit, which
is what makes the upper bound exclusive.

### The Goldor-segment rule

Inside the S1 rectangle (S1 in progress throughout):

- **Goldor in S1's segment: 21 samples (all 11:45), 0 hits**, plus the 9 first-lap samples with
  him still on the S4 start stretch (11:45 k1-5, 12:14 k1-4): 0 hits. Total 30 samples inside S1,
  none hit.
- **Goldor out of S1's segment: 9 samples, 9 hits.** 11:45: k45 (Goldor in S2's segment), k51
  (S2), k73 (S3), k88 (S4, second lap), k102 (S4), k162 (S2). 12:14: k145 (S2), k149 (S2), k171
  (S3).
  His segment is the track segment he is on (`Goldor.segment`), S1 0-90.7, S2 to 182.1, S3 to
  272.8, S4 to 364.2 (see goldor.md).

The segment-gated zone is only ever S1 here; the same rule for S2 to S4 in progress is the
extrapolation (see Open questions).

### Everything else the probes show

- Samples in the core box (x 39 to 71, z 54 to 118) and the strip in front of the core door: 0
  hits (`CORE_BOX`, `STRIP`).
- Samples in the gaps: 0 hits (S1/S2 gap z 121 to 122, S2/S3 gap x 18 to 20 at its tested end
  (x 18.45), S4/S3 gap is untested).

## How the sim implements it

`src/main/kotlin/com/engineerclient/p3sim/GoldorPhase.kt`:

- `deathTick()` runs at `n % 60 == 59` (in `tick()`), skips spectators/creative/ghost (the cloak
  does not stop it), looks at the player's server-seen position (`Fight.seenPos`), returns for
  `inSafeSpot` (`CORE_BOX` / `STRIP`), then `dtZone(seen)` (-1 outside every zone returns).
- `goldorIn` = 1 while `goldor.firstLap && goldor.s >= Goldor.START_S`, else
  `Goldor.segment(goldor.s) + 1`. The player is spared only when `at == section && goldorIn ==
  section`.
- `DT_ZONES` holds the table above (`AABB`, y 106 to 146); the first matching zone wins.
- Hit presentation: Goldor's "What do you think you are doing there!" and one quiet
  `WITHER_AMBIENT` (HOSTILE 1/1), then the consequence by `P3Sim.deathTicks`: the first death tick
  only shows the line, the second the line and a "Death tick (S<zone> during S<section>)" title,
  later ones go through `Masks.hit` (mask/pet save or the plain death).

## Open questions

Each with the probe that settles it. All probes: stand at a fixed feet position at the death
tick (`n = 60k - 1`), record own position, health, `hp`/`dmg`/chat, Goldor's track position from
the Boss Recorder, and the section in progress.

1. **S1's south edge** (z 26). The analysis summary only bounded it at <= 37.7, but the CSV has
   a bracket from one run (12:14: safe z 25.30 to 25.52 at x 112.3-113.6, hit z 26.59 at x
   112.41, Goldor in S2). Treat it as a single-run bracket. Probe: repeat at z 25.8, 26.0, 26.3
   and x 100, 112, with Goldor out of S1.
2. **Exactly when Goldor's S1 segment starts and ends.** The model is S1_ENTRY s 361.5 (on the
   S4 line x 95.5 to 98) to s 90.7, from his track. The lethal flip of the S1 zone is only
   sampled at the extremes (him far in S1 vs far out). Probe: stand inside S1 at the death
   ticks that fall within ±1 s of his corner and of the segment end, in a run where he is slowed
   enough to put a death tick on each boundary.
3. **The S2/S3 and S3/S4 gaps come from gate positions, not probes.** The S2/S3 gap x 18 to 20
   has only the x 18.45 safe sample; the S3/S4 gap z 50 to 51 only has z 50.33 (S4 side). S3's
   south edge z 51 and S2's west edge x 20 never have a sample at the edge. Probe: stand at x
   18.9, 19.5, 19.9, 20.1 (z 130) and z 50.5, 50.9, 51.1 (x 5), with S2 and S3 not in progress.
4. **S2, S3, S4 in progress never sampled.** The rule that "the section in progress is also
   lethal once Goldor leaves its segment" is an extrapolation from S1. Probe: finish S1 (and S2,
   S3), then stand in the current section's zone at death ticks with Goldor inside and then
   outside his segment, for each section.
5. **A cloak test inside a lethal zone.** The cloak not stopping death ticks is Andrew's ruling;
   the probes did not wear it inside a zone with a hit (the 11:45 samples with the cloak, e.g.
   k21, were inside S1 with Goldor in S1, safe anyway). Probe: cloak on, stand in a known-lethal
   zone point (S2 z 130) for a death tick.
6. **Post-proc invincibility.** After a mask or pet proc, the player is invulnerable for a short
   time; whether it makes the next death tick in the same place safe is not measured (it is 60
   ticks later). To be investigated.
7. **The 1-tick-early outlier.** In 8 of the 29 hit samples the hit evidence (the line plus the
   proc) was stamped one tick before `60k - 1` (11:45 k102; 12:14 k105, 108, 128, 145, 149, 171,
   208), the rest on the tick. Chat is ±1 tick, so it is probably stamping, but a probe with the
   server tick of the health drop (`hp` per tick) would settle it.

## History

The earlier rule was "anyone standing in the section ahead of the one in progress, plus S4 while
S1 is in progress, is hit; the section in progress is always safe". Checked against 164 Better PF
runs (9,896 points) it scored **recall 0.82, precision 0.41**: it missed hits in S1 once Goldor
left S1 (and in the gaps' neighbours), and flagged 928 points as hit that were not. Each of the
three probe runs of 2026-10-04 replaced one piece of it: every non-current zone is lethal (11:45),
the zones are four rectangles with gaps (11:59 and the S2/S3 edges), and the in-progress section
is lethal once Goldor has left it (12:14, with his track position).
