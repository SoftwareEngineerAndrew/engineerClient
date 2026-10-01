# Sub splits from the measured mechanics

Proposed sub splits for the Watcher, Maxor, Storm and Necron, built on what the mechanics
reports found ([watcher.md](watcher.md), [maxor.md](maxor.md), [storm.md](storm.md),
[necron.md](necron.md)). Terminals and Goldor are left as they are (their spread is in the
last table).

The rule for choosing a boundary: **each sub split is either fixed (the game's script, nobody can
speed it up) or player-controlled, never a mix.** Then a fixed split that runs long points at
something specific (lag, a missed grid tick), and a controlled split's excess over its floor is
exactly the time the party lost. Every boundary below can be seen live: a chat line, a block
change, or the boss wither's position.

Times are server ticks. "Floor" is the fastest the game allows; "fast / median" are the fastest
10% (p10) and the median of the recorded runs.

**Data.** 173 five-player runs with server ticks, measured by `tools/boss-mechanics/subsplits.py`:
128 from the 2026-09-23 to 09-29 recordings the mechanics reports used, and 45 more from 09-29
to 10-01 (414 recordings in all). Alpha-server runs are left out (the four known ones and
`d4bd4083`); none of the new runs shows the alpha signature (Storm leaving 98-113 ticks after
the lightning, or "Ah, we meet again..."). The new runs moved no floor and no median by more than
a tick or two, except the Storm pin (below). "Seen in" is how often the boundary could be
detected as a live client would.

## The Watcher (blood camp)

The camp is a clock. The dialogue runs on fixed delays, and the Watcher's move comes on a 40-tick
grid. Only the kills are the party's; head order is luck.

| sub split | from → to | detect | floor | fast / median | kind | what makes it slow |
|---|---|---|---|---|---|---|
| **Dialogue** | blood door → "Let's see how you can handle this." | chat | D+385 | 417 / 457 | fixed* | *only kill speed during the 4 dialogue mobs: "handle this" waits for the speech gap after the last spawn line |
| **Wait** | "handle this" → the Watcher's move (first fetch leg) | Watcher leaves the middle (entity); seen in 40% | D+480, ≥ handle +42 | 51 / 83 | fixed | which step he picks past D+480 looks random (moves at D+480: 11, 520: 12, 540: 17, 560: 21, 600+: 8); nothing to fix, but shows a bad-luck camp |
| **Camp** | move → last blood mob spawned (19th mob lands) | 19th mob entity added (the 17th regular when the dialogue-phase mobs were missed) | 645 seen, 690 in the fastest 10% | 690 / 758 | luck + kills | mobs alive slow him to 0.44 b/t and make him skip about half his steps: kill as they land |
| **Clear** | last spawn → "You have proven yourself" | chat | 0-8 after the last death (10-tick grid) | 3 / 11 | controlled | killing the last mobs |

The existing blood detail already logs "handle this" and spawns. The two new ones are **Wait**,
which separates bad luck from slow kills, and **Clear**.

- The move was never seen before D+480 (packet arrivals at D+481-483) or within 42 ticks of
  "handle this" (closest 44), as watcher.md says.
- The Watcher is often out of the recorder's view when he leaves, so Wait was measurable in 69
  of 173 runs. Without him in view, show **Wait + Camp** ("handle this" → last spawn) as one split.
- The last spawn is seen in every run: all 19 mobs in 72, the 17 regulars (the last mob is always
  one) in the rest.
- New best camp: 1118 ticks (56.6 s real time), `20261001-013704`, three recordings agreeing.
  The earlier best was 1137. The floor of about 1085-1095 stands.

## Maxor

Everything Maxor's controller does is on 10-tick checks. The first hit can't come before s0+206,
and the second is exactly 10 s of real time after the first.

| sub split | from → to | detect | floor | fast / median | kind | what makes it slow |
|---|---|---|---|---|---|---|
| **Crystals** | first line → "The Energy Laser is charging up!" | chat | 194 (both placed on the s0+166 check, +28) | 194 / 195 | controlled | crystals picked late, carriers not at the pylons by 166 |
| **Lure** | charging → hit 1 | the hit: stun line, or (when an ability holds the line) the placed crystals vanishing at hit +42 or the top crystals returning at hit +41 | the first check ≥ s0+206 (12 ticks after a 194 charge) | hit 1 at s0+205 / 206 | controlled | Maxor not inside ~3.5 blocks of (73.5, 73.5) at the check |
| **Cooldown** | hit 1 → hit 2 | as above | **10.0 s real time** (200 ticks at 20 TPS) | 10.05 / 10.15 s (200 / 200 ticks) | fixed + controlled | Maxor out of the beam area at +200, laser not recharged, an ability on the hit. Show it in **real seconds**: lag makes the tick count look short (as low as 110 ticks) |
| **Kill** | hit 2 → kill | **beacon at (73, 221, 73) turns to bedrock**; seen in every run | 0-1 | 2 / 6 | controlled | damage |
| **Animation** | kill → Storm's first line | chat | 102 | 101 / 102 | fixed | lag only (98-104) |

This replaces Move / Stun / Dps / Stun / Dps. The enrage ("Maxor is enraged!") changes nothing about
when the next hit lands, so time split on it isn't a loss anywhere. It can stay as a detail line.

- The second hit was silent (stun line held by an ability) in 31 of 173 runs. In all of them the
  kill came 0-21 ticks after the hit, before hit +42, so the placed crystals vanished with the
  kill, not with the hit. The top crystals still came back at hit +41, which is the fallback that
  works there.
- Crystals at 192-193 (7 runs) are the ±2 of chat timing, not a faster placement.
- The Maxor split as a whole: 510 / 518 (floor 509).

## Storm

Storm's clock is the crush check, every 20 ticks from his wither (19 mod 20 after his first line).
A late crush costs whole 20-tick steps.

| sub split | from → to | detect | floor | fast / median | kind | what makes it slow |
|---|---|---|---|---|---|---|
| **Opening** | first line → departure | lightning line + 139 (or Storm leaving (102.375, 183, 52.375)) | 687 | 686 / 687 | fixed | lag only |
| **Crush 1** | departure → crush 1 ("Oof" / "Ouch, that hurt!") | chat | 12 (the t 699 check) | 11 / 12 | controlled | the Purple pad not pressed on the t 639 check; Storm not lured under Purple. Show **which check** it landed on (699 in 164 of 172 runs; the rest 779-859) |
| **Pin** | crush 1 → "⚠ Storm is enraged! ⚠" | chat | 0 | 1 / 6 | controlled | the Mage's beam (storm.md §5). The new runs are faster: median 9.5 on the old runs alone |
| **Flight** | enrage → arrival at Yellow (within ~2.4 blocks of (46, 172.8, 65)) | Storm's position; seen in 137 of 165 Purple crushes | ~85 measured (81 once) | 87 / 92 | fixed (+6% skipped moves) | nothing to fix: the 1 s save needs a pin at x ≈ 97.8 and a 0-tick pin |
| **Crush 2** | arrival → crush 2 | chat (or the pillar's reset 20 ticks later if the line is missing) | the next check | 0 / 7 | controlled | the Yellow pad pressed on the 739 or 759 check; Storm's head in the pillar |
| **Kill** | crush 2 → "I should have known that I stood no chance." | chat | 0 | 2 / 5 | controlled | damage at Yellow |
| **Animation** | death → Goldor's first line | chat | 102 | 101 / 102 | fixed | lag only |

Flight and Crush 2 can be shown as one split where Storm isn't in view: enrage → crush 2, fast /
median 91 / 98.

- The Flight floor was given as ~65 (distance ÷ 0.7157). Measured from the enrage line it is
  never under 81: he sets off 2-3 ticks after the enrage and slows down over the last ~26 blocks
  (maxor-storm-movement.md §3.5).
- The 2.4-block arrival is not always reached before the crush: in 10 of 136 runs crush 2 came
  1-7 ticks before it (he is crushed anywhere in the 6x6 zone). Show those as Crush 2 = 0.
- Crush 2 landed 100 ticks after crush 1 in 86 runs, 120 in 32, 140 in 10, 160 in 16, later in
  the rest. The three "+80" runs are a crush 1 on Yellow followed by a chase to Green, not the
  Purple → Yellow flight.
- The Storm split as a whole: 904 / 924 (floor 900-901).

## Necron

Necron runs a 62-tick dialogue queue and a 20-tick grid (n ≡ 5 mod 20). Only the two trips off mid
are the party's. Each is followed by a fixed lock that rounds up to the grid.

| sub split | from → to | detect | floor | fast / median | kind | what makes it slow |
|---|---|---|---|---|---|---|
| **Intro** | first line → leaves mid (L1) | Necron leaves (54, 66, 76) | 159 | 158 / 159 | fixed | lag only |
| **Trip 1** | L1 → back at mid (B1) | the teleport to exactly mid | 7 (his scripted sidestep) | 10 / 16 | controlled | damage. B1 ≤ ~184 makes ARGH 1 at 330 |
| **Lock 1** | B1 → "ARGH!" | chat | first grid tick ≥ B1 + 141 (and ≥ taunt + 82) | 148 / 156 (ARGH 1 at 330 in 142 of 167) | fixed | shows the grid: a B1 one tick too late costs 20 |
| **Space** | ARGH 1 → leaves mid again (L2) | Necron leaves mid | volley 2 + 60 (L2 at 387-409) | 60 / 67 | fixed | volley 2 starting late (3-24, sometimes 40-100 ticks after the grid tick), cause open |
| **Trip 2** | L2 → back at mid (B2) | the teleport | 1 | 1 / 5 | controlled | damage. B2 ≤ ~404 makes ARGH 2 at 545 |
| **Lock 2** | B2 → "ARGH!" | chat | first grid tick ≥ B2 + 141 | 143 / 150 (ARGH 2 at 545 in 94 of 166) | fixed | the grid again |
| **Animation** | ARGH 2 → "All this, for nothing..." | chat | 62 | 61 / 62 | fixed | lag only |

Floor for the whole split: 607 ticks (30.35 s), made (605-609) by 93 of 166 runs (56%). A slower
run is slower by whole 20-tick steps, and Trip 1 / Trip 2 say which trip cost it. Trips were
seen in every run for trip 1 and in 149 of 166 for trip 2.

## Fixes to the current sub splits (`SubSplits.kt`)

These are wrong by the reports, whether or not the new splits go in. Re-checked on all runs
(173 five-player runs with server ticks):

1. **Four Storm taunts advance the stopwatch:** "THAT WAS ONLY IN MY WAY!", "Slowing me down will be
   your greatest accomplishment!", "This factory is too small for me!" and "BEGONE PILLAR!". They are
   random taunts from t ≈ 899, every 60-63 ticks. In a slow Storm they move the step on at a random
   moment: one of them came before Storm's death in 13 of 172 runs.
2. **"I'M TOO YOUNG TO DIE AGAIN!" ends Maxor's last Dps.** It is a timer (second stun line + 82;
   80-84 in all 100 runs that had it), said only if he is still alive: 73 of 173 runs never had
   it. Where it came, it was 26-79 ticks after the kill. The kill is the beacon turning to
   bedrock (seen in every run); Animation is then a fixed 102.
3. **`STORM_LIGHTNING_TICKS = 688` is counted from the lightning line.** Storm leaves 139 ticks after
   the lightning line (138-141 in the middle 80% of 167 runs; 688 is the time from his *first*
   line). As written, the Animation step runs ~549 ticks long whenever crush 1 hasn't come first.
4. **`BossDetail.STORM_FREE`** (and `Scorecard.STORM_FREE`) treats "Slowing me down..." as Storm
   breaking free. "⚠ Storm is enraged! ⚠" is the break-free moment. "Slowing me down..." came in
   only 4 runs, 90-247 ticks after the enrage line.
5. **Storm deaths with no second crush line** (14 of 173 runs): the next step never starts. The
   pillar reset (crush + 20) marks the missed crush.

## Spread of every split (colour bands)

All 5-player runs with server ticks, all data, alpha left out. Ticks except where the unit is s
(real seconds from the recorders' clocks): Maxor's Cooldown and the terminal sections. Terminal
sections run door to door (a section ends at max(last completion, gate), when its door's barrier
blocks turn to air; goldor.md), S4 ends on "The Core entrance is opening!". Goldor Leaps is the
core opening → everyone in the core box (`DungeonSplits.everyoneInCore`), Goldor Kill everyone in →
Necron's first line. "Fixed" marks splits whose middle 80% is within 4 ticks: the script, with
only lag in the spread.

| split | unit | n | min | p5 | p10 | p25 | p50 | p75 | p90 | max | |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Watcher Dialogue | t | 173 | 385 | 413 | 417 | 443 | 457 | 478 | 508 | 522 | |
| Watcher Wait | t | 69 | 44 | 46 | 51 | 65 | 83 | 102 | 118 | 175 | |
| Watcher Camp | t | 69 | 645 | 674 | 690 | 725 | 758 | 806 | 826 | 892 | |
| Watcher Clear | t | 173 | 0 | 2 | 3 | 7 | 11 | 17 | 25 | 962 | |
| Maxor Crystals | t | 173 | 192 | 194 | 194 | 194 | 195 | 197 | 210 | 466 | |
| Maxor Lure | t | 173 | 0 | 1 | 2 | 10 | 11 | 12 | 41 | 225 | |
| Maxor Cooldown | s | 173 | 9.45 | 10.00 | 10.05 | 10.06 | 10.15 | 10.35 | 11.14 | 21.35 | |
| Maxor Kill | t | 172 | 0 | 1 | 2 | 3 | 6 | 9 | 12 | 461 | |
| Maxor Animation | t | 173 | 98 | 101 | 101 | 102 | 102 | 103 | 103 | 104 | fixed |
| Storm Opening | t | 173 | 685 | 686 | 686 | 687 | 687 | 687 | 688 | 691 | fixed |
| Storm Crush 1 | t | 173 | 10 | 11 | 11 | 12 | 12 | 13 | 13 | 172 | (grid) |
| Storm Pin | t | 173 | 0 | 1 | 1 | 3 | 6 | 16 | 25 | 204 | |
| Storm Flight | t | 137 | 81 | 86 | 87 | 89 | 92 | 94 | 100 | 793 | |
| Storm Crush 2 | t | 137 | -7 | -1 | 0 | 4 | 7 | 27 | 62 | 1287 | |
| Storm Flight + Crush 2 | t | 166 | 85 | 89 | 91 | 96 | 98 | 126 | 160 | 1517 | |
| Storm Kill | t | 172 | 0 | 2 | 2 | 4 | 5 | 10 | 24 | 100 | |
| Storm Animation | t | 172 | 81 | 100 | 101 | 101 | 102 | 102 | 103 | 105 | fixed |
| Terminals S1 | s | 172 | 9.60 | 11.45 | 12.00 | 12.65 | 13.75 | 15.80 | 23.45 | 82.65 | |
| Terminals S2 | s | 168 | 5.45 | 7.00 | 7.81 | 9.73 | 12.50 | 15.45 | 19.50 | 53.05 | |
| Terminals S3 | s | 168 | 6.55 | 8.15 | 9.10 | 10.85 | 12.80 | 15.85 | 18.60 | 43.60 | |
| Terminals S4 | s | 167 | 4.55 | 6.20 | 6.90 | 8.32 | 10.46 | 13.25 | 18.30 | 32.40 | |
| Goldor Leaps | t | 164 | 7 | 11 | 14 | 17 | 20 | 34 | 62 | 155 | |
| Goldor Kill | t | 164 | 53 | 98 | 105 | 119 | 138 | 159 | 179 | 357 | |
| Necron Intro | t | 167 | 158 | 158 | 158 | 159 | 159 | 160 | 160 | 162 | fixed |
| Necron Trip 1 | t | 167 | 6 | 9 | 10 | 12 | 16 | 22 | 28 | 212 | |
| Necron Lock 1 | t | 167 | 142 | 146 | 148 | 151 | 156 | 159 | 161 | 164 | (grid) |
| Necron Space | t | 150 | 56 | 59 | 60 | 63 | 67 | 72 | 78 | 162 | |
| Necron Trip 2 | t | 150 | 1 | 1 | 1 | 3 | 5 | 8 | 20 | 164 | |
| Necron Lock 2 | t | 150 | 139 | 141 | 143 | 146 | 150 | 155 | 159 | 333 | (grid) |
| Necron Animation | t | 166 | 55 | 61 | 61 | 62 | 62 | 62 | 63 | 211 | fixed |
| **Blood** (Watcher's first line → proven) | t | 173 | 1118 | 1198 | 1226 | 1257 | 1312 | 1377 | 1427 | 2440 | |
| **Maxor** (→ Storm's first line) | t | 173 | 429 | 508 | 510 | 511 | 518 | 531 | 604 | 942 | |
| **Storm** (→ Goldor's first line) | t | 172 | 901 | 903 | 904 | 905 | 924 | 965 | 1029 | 1654 | |
| **Terms** (→ core opening) | s | 167 | 34.20 | 38.95 | 40.92 | 44.35 | 50.45 | 59.10 | 71.70 | 106.10 | |
| **Goldor** (core opening → Necron's first line) | t | 167 | 118 | 124 | 129 | 146 | 166 | 187 | 213 | 504 | |
| **Necron** (→ "All this, for nothing...") | t | 166 | 599 | 606 | 606 | 607 | 608 | 627 | 647 | 1038 | |

Grid steps reached (the grid-quantised splits):

- Storm crush 1, check (t after his first line): 699: 165, 779: 1, 799: 2, 819: 2, 839: 1, 859: 2.
- Storm crush 2, check: 799: 86, 819: 30, 839: 10, 859: 15, 879: 6, 899: 6, 919: 3, 939: 7, later: 10.
- Necron ARGH 1 slot: 325 (said at 330): 142, 345: 21, 365: 1, 505-525: 3.
- Necron ARGH 2 slot: 545: 94, 565: 50, 585: 11, 605: 3, later: 9.
- Watcher move (D+, 20-tick steps; seen in 69): 480: 11, 520: 12, 540: 17, 560: 21, 600: 5,
  640: 1, 680: 2.

Notes on the tails:

- Maxor Cooldown under 10.0 s (5 runs, 9.45-9.97) and the ticks as low as 110 are the recorders'
  clocks and lag; the 21 s maximum is a missed second hit.
- Goldor Kill under 82 (5 runs, 53-70) is shorter than Goldor's own death → Necron gap, so
  "everyone in" was seen late there (a player's position reaching the recorder late). 3 runs
  where nobody saw the last player enter are left out.
- Watcher Clear 962 and Storm Crush 2 1287 are runs where the party stalled.
- Necron Trip 1 at 6 is the ±1 of reading the teleport's arrival.
