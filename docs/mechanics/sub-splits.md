# Sub splits from the measured mechanics

Proposed sub splits for the Watcher, Maxor, Storm and Necron, built on what the mechanics
reports found ([watcher.md](watcher.md), [maxor.md](maxor.md), [storm.md](storm.md),
[necron.md](necron.md)). Terminals and Goldor are left as they are.

The rule for choosing a boundary: **each sub split is either fixed (the game's script, nobody can
speed it up) or player-controlled, never a mix.** Then a fixed split that runs long points at
something specific (lag, a missed grid tick), and a controlled split's excess over its floor is
exactly the time the party lost. Every boundary below can be seen live: a chat line, a block
change, or the boss wither's server position.

Times are server ticks. "Floor" is the fastest the game allows; "fast / median" are from the
recorded runs (5-player, alpha runs left out).

## The Watcher (blood camp)

The camp is a clock. The dialogue runs on fixed delays, and the Watcher's move comes on a 40-tick
grid. Only the kills are the party's; head order is luck.

| sub split | from → to | detect | floor | kind | what makes it slow |
|---|---|---|---|---|---|
| **Dialogue** | blood door → "Let's see how you can handle this." | chat | D+385 | fixed* | *only kill speed during the 4 dialogue mobs: "handle this" waits for the speech gap after the last spawn line |
| **Wait** | "handle this" → the Watcher's move (first fetch leg) | Watcher leaves the middle (entity) | D+480, ≥ handle +42 | fixed | which 40-tick step he picks past D+480 looks random; nothing to fix, but shows a bad-luck camp |
| **Camp** | move → last blood mob spawned (19th mob lands) | 19th mob entity added | ~690 in the fastest runs, 760 typical | luck + kills | mobs alive slow him to 0.44 b/t and make him skip about half his steps: kill as they land |
| **Clear** | last spawn → "You have proven yourself" | chat | 0-8 after the last death (10-tick grid) | controlled | killing the last mobs |

The existing blood detail already logs "handle this" and spawns. The two new ones are **Wait**,
which separates bad luck from slow kills, and **Clear**.

## Maxor

Everything Maxor's controller does is on 10-tick checks. The first hit can't come before s0+206,
and the second is exactly 10 s of real time after the first.

| sub split | from → to | detect | floor | fast / median | kind | what makes it slow |
|---|---|---|---|---|---|---|
| **Crystals** | first line → "The Energy Laser is charging up!" | chat | 194 (both placed on the s0+166 check, +28) | 194-196 / – | controlled | crystals picked late, carriers not at the pylons by 166 |
| **Lure** | charging → stun 1 | the hit: stun line, or placed crystals vanishing (hit +42) when an ability holds the line | 206 | 204-206 / 206 | controlled | Maxor not inside ~3.5 blocks of (73.5, 73.5) at the check |
| **Cooldown** | stun 1 → stun 2 | as above | **10.0 s real time** (200 ticks at 20 TPS) | = floor | fixed + controlled | Maxor out of the beam area at +200 (14 runs), laser not recharged (4), an ability on the hit (5). Show it in **real seconds**: lag makes the tick count look short |
| **Kill** | stun 2 → kill | **beacon at (73, 221, 73) turns to bedrock** | 0-1 | 1-3 / 5-6 | controlled | damage (29 of 61 slow kills) |
| **Animation** | kill → Storm's first line | chat | 102 | 101-103 | fixed | lag only |

This replaces Move / Stun / Dps / Stun / Dps. The enrage ("Maxor is enraged!") changes nothing about
when the next hit lands, so time split on it isn't a loss anywhere. It can stay as a detail line.

## Storm

Storm's clock is the crush check, every 20 ticks from his wither (19 mod 20 after his first line).
A late crush costs whole 20-tick steps.

| sub split | from → to | detect | floor | fast / median | kind | what makes it slow |
|---|---|---|---|---|---|---|
| **Opening** | first line → departure | lightning line + 139 (or Storm leaving (102.375, 183, 52.375)) | 687 | 687 | fixed | lag only |
| **Crush 1** | departure → crush 1 ("Oof" / "Ouch, that hurt!") | chat | 12 (the t 699 check) | – | controlled | the Purple pad not pressed on the t 639 check; Storm not lured under Purple. Show **which check** it landed on (699, 719, 739...) |
| **Pin** | crush 1 → "⚠ Storm is enraged! ⚠" | chat | 0 | 3 / 10 | controlled | the Mage's beam (35 of 47 pins end 0-1 ticks after the first beam on target) |
| **Flight** | enrage → arrival at Yellow (within ~2.4 blocks of (46, 172.8, 65)) | Storm's position | ~65 (distance ÷ 0.7157) | – | fixed (+6% skipped moves) | nothing to fix: the 1 s save needs a pin at x ≈ 97.8 and a 0-tick pin |
| **Crush 2** | arrival → crush 2 | chat (or the pillar's reset 20 ticks later if the line is missing) | the next check | – | controlled | the Yellow pad pressed on the 739 or 759 check; Storm's head in the pillar |
| **Kill** | crush 2 → "I should have known that I stood no chance." | chat | 0 | 3 / 6 | controlled | damage at Yellow |
| **Animation** | death → Goldor's first line | chat | 102 | 102 | fixed | lag only |

Flight and Crush 2 can be shown as one split where Storm isn't in view.

## Necron

Necron runs a 62-tick dialogue queue and a 20-tick grid (n ≡ 5 mod 20). Only the two trips off mid
are the party's. Each is followed by a fixed lock that rounds up to the grid.

| sub split | from → to | detect | floor | fast / median | kind | what makes it slow |
|---|---|---|---|---|---|---|
| **Intro** | first line → leaves mid (L1) | Necron leaves (54, 66, 76) | 159 | 159 | fixed | lag only |
| **Trip 1** | L1 → back at mid (B1) | the teleport to exactly mid | 7 (his scripted sidestep) | – / 17 | controlled | damage. B1 ≤ ~184 makes ARGH 1 at 330 |
| **Lock 1** | B1 → "ARGH!" | chat | first grid tick ≥ B1 + 141 (and ≥ taunt + 82) | 330 | fixed | shows the grid: a B1 one tick too late costs 20 |
| **Space** | ARGH 1 → leaves mid again (L2) | Necron leaves mid | volley 2 + 60 (387-409) | – | fixed | volley 2 starting late (3-24, sometimes 40-100 ticks after the grid tick), cause open |
| **Trip 2** | L2 → back at mid (B2) | the teleport | 1 | – | controlled | damage. B2 ≤ ~404 makes ARGH 2 at 545 |
| **Lock 2** | B2 → "ARGH!" | chat | first grid tick ≥ B2 + 141 | 545 | fixed | the grid again |
| **Animation** | ARGH 2 → "All this, for nothing..." | chat | 62 | 62 | fixed | lag only |

Floor for the whole split: 607 ticks (30.35 s), made by 54% of runs. A slower run is slower by whole
20-tick steps, and Trip 1 / Trip 2 say which trip cost it.

## Fixes to the current sub splits (`SubSplits.kt`)

These are wrong by the reports, whether or not the new splits go in:

1. **Four Storm taunts advance the stopwatch:** "THAT WAS ONLY IN MY WAY!", "Slowing me down will be
   your greatest accomplishment!", "This factory is too small for me!" and "BEGONE PILLAR!". They are
   random taunts from t ≈ 899, every 60-63 ticks. In a slow Storm they move the step on at a random
   moment.
2. **"I'M TOO YOUNG TO DIE AGAIN!" ends Maxor's last Dps.** It is a timer (second stun line + 82),
   said only if he is still alive. The kill is the beacon turning to bedrock; Animation is then a
   fixed 102.
3. **`STORM_LIGHTNING_TICKS = 688` is counted from the lightning line.** Storm leaves 139 ticks after
   the lightning line (688 is the time from his *first* line). As written, the Animation step runs
   ~549 ticks long whenever crush 1 hasn't come first.
4. **`BossDetail.STORM_FREE`** (and `Scorecard.STORM_FREE`) treats "Slowing me down..." as Storm
   breaking free. "⚠ Storm is enraged! ⚠" is the break-free moment.
5. **Storm deaths with no second crush line** (21 runs): the next step never starts. The pillar
   reset (crush + 20) marks the missed crush.
