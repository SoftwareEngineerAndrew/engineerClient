# Goldor (F7 phase 3): mechanics measured from Better PF recordings

From `[BOSS] Goldor: Who dares trespass into my domain?` through the four terminal sections,
`The Core entrance is opening!`, Goldor's death, up to Necron's first line.

Scripts: `tools/boss-mechanics/goldor/` (README there). All numbers below come from them unless
marked **conjecture**.

## Data and method

- 278 recordings; 240 reach Goldor; the four alpha-server recordings are left out. **Timing
  uses only the 155 recordings (130 runs) with server ticks** (`st` lines). `n` = server ticks
  after the tick the recording received "Who dares trespass" (chat is ±1 tick). Runs with
  several recordings count once (the recording with the most known events).
- Goldor's positions are de-lerped (`tools/boss-movement/bosslib.py`); the residual grid error
  is ~0.01 block. He is only visible within ~45-60 blocks of the recorder, so every run shows
  only stretches of his path.
- Other players' positions are the recorder's (stale) view; statements about "a player was
  there" use the recorder's own position or other players' positions at most 5-20 ticks old.
- Hypixel's `X activated a terminal! (3/7)` lines are hidden by a chat cleaner in about half the
  recordings (76 have them). Section ends are therefore taken from **block updates**, which every
  recording gets for the whole arena: each section's door (228 barrier blocks) turns to air in the
  tick the section ends (see below).

## Timeline at a glance (server ticks, 126 runs with all four section ends)

| step | median | best | p10-p90 |
|---|---|---|---|
| S1 (start -> S1 door) | 272 | 193 | 236-438 |
| S2 | 252 | 109 | 148-382 |
| S3 | 244 | 129 | 179-363 |
| S4 (-> "The Core entrance is opening!") | 207 | 93 | 132-372 |
| **P3** (start -> core opening) | 1000 | 681 | 804-1426 |
| core opening -> Goldor dead | 89 | 37 | 55-132 |
| Goldor dead -> Necron's first line | 82 | 80 | 81-83 |
| core opening -> Necron's first line | 171 | 119 | 135-214 |
| start -> Necron's first line | 1176 | 846 | 958-1602 |

## Sections

### What is in them (measured: 76 recordings with completion lines, stand names, block updates)

| | terminals | levers | device | counter |
|---|---|---|---|---|
| S1 | 4: (110,112,74) (110,118,80) (90,111,92) (90,121,102) | (94,125,114) (106,125,114) | Simon Says, buttons at x 110-111, y 120-123, z 92-95 | /7 |
| S2 | 5: (68,108,122) (60,119,124) (48,108,122) (40,123,124) (40,107,142) | (28,125,128) (24,133,138) | Lights: levers (60,133,142) (62,136,142), lamps behind | /8 |
| S3 | 4: (-2,108,112) (-2,118,94) (18,122,94) (-2,108,78) | (2,123,56) (14,123,56) | Arrow Align: 9 item frames at x -2, y 120-124, z 76-80 | /7 |
| S4 | 4: (42,108,30) (44,120,30) (68,108,30) (72,114,48) | (84,122,34) (86,129,46) | target shooting: plate (63,127,35), 9 targets blue terracotta -> emerald at x 64-68, y 126-130, z 50 | /7 |

(Positions are the terminals' / levers' / devices' armor stands, rounded.) Terminal GUIs seen:
panes, same colour, click in order, click the button on time, starts with, select all.

### Counting

- Each terminal, lever and the section's own device adds 1; the section is complete at n/n.
- **A device of a later section can be done early.** Its "X completed a device!" line prints the
  *current* section's counter without advancing it (97 such lines in S1, 11 in S2), and that
  section later starts already credited: S2's first line was `(2/8)` in 37 of 64 recordings, S4's
  `(2/7)` in 52 of 62 (S3's device was rarely pre-done: 3 of 62).
- Levers show "Someone has already activated this lever!" if pulled again; terminals of a section
  not yet in progress say "This Terminal doesn't seem to be responsive at the moment."
- The next section's terminals/levers work from the tick its predecessor ends: S2's first
  completion came 1-4 ticks after S1's door in the quickest runs (players standing ready).

### Gates and doors (measured)

Between sections there are two barriers at the same spot on the track
(S1/S2: x 93-107, z 121-124; S2/S3: x 16-19, z 125-139; S3/S4: x 1-15, z 48-51):

- **The gate**: ~86-115 cracked stone bricks/stairs. Players blow it up: the player nearest to it
  when "The gate has been destroyed!" comes held Superboom TNT (93 of 182), a Dungeonbreaker (35),
  or something else. It can go at any time, before the section's levers or terminals (in 440 of 451 cases
  it went before its section was complete).
- **The door**: 228 barrier blocks (+ stairs/iron) behind a cobblestone portcullis. It opens
  (barriers -> air in one tick, portcullis 5-11 ticks later) at **max(last completion, gate
  destroyed)**: 0 ticks after the `(n/n)` line in 105 of 142 sections, 1 in 25, 2 in 3, and in the remaining
  9 (4-100 ticks later) it waited for the gate.
- If the section completes with the gate still up, chat says "The gate will open in 5 seconds!"
  and the gate goes 4-58 ticks later when players blow it (8 cases), or at +99 when nobody did
  (1 case) - consistent with a 100-tick automatic opening.
- S4 has no gate: "The Core entrance is opening!" comes in the tick of S4's last completion
  (0 ticks in 38, 1 in 9). The core door (35 gold blocks at x 52-56, y 115-121, z 54) turns to
  barrier in that tick (0-2) and to air **19 ticks later** (17-21; 101 of 150 exactly 19).

**So a section ends at the later of its 7th/8th completion and its gate.** This is what
`SubSplits.kt` already does ("whichever of last device and gate arrives second").

### Death ticks (measured, `deathtick.py`)

- Every **60 server ticks** from Goldor's first line (the chat line lands at n = 60k-1 ± 1-2;
  gaps between consecutive lines: 60 ± 1-2), for the whole phase, not reset by sections.
- Hit: anyone standing **in a section ahead of the one in progress** - the next section (214 of 230
  recorder samples hit) and, while S1 is in progress, S4 (78 of 83; S4 is where the target
  device is done early). Safe: the section in progress (16 hits in 1719, box edges), any
  earlier section (0/70), the core box (0/210).
- The hit is lethal: it shows as a mask/pet save (Spirit Mask, Bonzo's Mask, Phoenix) or a death
  in the same tick, and Goldor says "What do you think you are doing there!" (357 times).
  Pre-entering therefore costs one save per 60 ticks spent ahead, which is why the rotation
  budgets invincibilities for early entries.

### Goldor's other lines

- The intro lines come on a 62-tick grid: "Little ants..." at 62, "I won't let you break the
  factory core..." at 124, "No one matches me in close quarters." at 186 (±3).
- One of "I will replace that gate with a stronger one!" / "YOUR END IS NEAR!!" / "The little ants
  have a brain it seems." follows a section completion, but often only at the next 62-tick slot
  (0-189 ticks late): **not usable as a section timer**.
- Taunts ("Stop touching those terminals!", "Slowing me down only prolongs your pain!", ...) are
  mostly on the same 62-tick grid.

## Goldor's movement (`goldor.py`)

### The track

A rectangle loop at the arena's edge, walked S1 -> S2 -> S3 -> S4 (the players' order):

| line | coordinate | y |
|---|---|---|
| S1 | x = 99.55 (99.3-99.7), z 40.6 -> 131.3 | 119.0 |
| S2 | z = 131.7 (131.3-131.9), x 99.5 -> 8.1 | 118.1-118.9 |
| S3 | x = 8.4 (8.1-8.8), z 131.3 -> 40.6 | 118.0 |
| S4 | z = 40.0 (40.0-40.4), x 8.1 -> 99.5 | 118.1-119.0 |

`s` below is the distance along it from the S4/S1 corner (99.5, 40.6): S1 segment 0-90.7,
S2 90.7-182.1, S3 182.1-272.8, S4 272.8-364.2. The gates sit on it (S1/S2 at s ≈ 82,
S2/S3 at ≈ 164, S3/S4 at ≈ 264).

### Measured

- **Start**: at (80.0, 119.0, 40.0) (s = 344.7) at "Who dares trespass", in every recording that
  saw it (315 sightings at n <= 15), i.e. on the S4 line 19.5 blocks before the S1 corner.
- Position packets every 1-5 server ticks (mode 2).
- **Speeds** along the track (>= 10-tick windows, 3632 windows): a sharp mode at **0.0600
  blocks/tick** (1.2 blocks/s; 0.05-0.07 = 2849 windows), a fast mode at **~0.60 blocks/tick**
  (0.5-0.7: 455 windows), and slowed stretches at 0.01-0.04 (269 windows) during which he is
  being hit (the recorder holding a Terminator/Juju/Hyperion near him; "Slowing me down only
  prolongs your pain!" / "You can't damage me, you can barely slow me down!").
- **Catch-up**: every fast window was Goldor exactly one segment behind the section in progress
  (S2 in progress & Goldor on the S1 line: 185; S3 & S2 line: 190; S4 & S3 line: 72; plus 10
  S3 & S1 line right after S2 ended). Fast stretches end ~1.6 blocks past a corner: s = 92.2-92.4
  (S2 start), 183.6-183.8 (S3), 274.3-276.5 (S4), i.e. he sprints to the start of the section in
  progress and walks again from there.
- **Two kinds of run**: in 62 recordings Goldor was seen catching up; in 76 he was only ever seen
  walking at 0.06 behind the players, often still on the S1 line while S3 or S4 was in progress
  (Goldor at s ≈ 15-60 when the core opens). The S1 door time separates them: catching up
  S1 door 257-657 (median 312), never fast 193-399 (median 252). In the 16 recordings where
  Goldor was seen within 40 ticks of the S1 door: at the door he was still before s ≈ 360
  (x < 95.5 on the S4 line) in all 10 "never fast" runs and at s >= 362.7 in the 5 catch-up runs (the 16th, a failed run, had him past the corner
  with no fast stretch in view).
- He does not damage anything by moving; his damage is separate (below).

**Conjecture (fits all of the above):** when a section ends, Goldor sprints (0.6) to the start
of the next section's segment if he is in the segment of the section that just ended; otherwise he
keeps walking at 0.06. If S1 ends before he has entered the S1 segment (he needs ~250-330 ticks
to walk there from his start, longer when hit), he is never in "the segment that just ended" again
and walks at 0.06 for the whole phase. The exact segment boundary on the S4 line is between
x 95.5 and 98.

### Damage (measured, recorder only)

- "Goldor's Frenzy hit you for ~30-40k": every ~10 ticks, recorder 2-14 blocks from Goldor (32
  hits, 29 of them in the core).
- "Goldor's Greatsword hit you for 120,000 damage": recorder 9-10 blocks from one of the
  **giants holding golden swords** that spawn with Goldor (entity ids right after his) at the S4/S1
  corner and later appear at the other corners and walk around the track (7 hits).
- "Goldor's TNT Trap hit you for 60,000" (2 hits).
- No damage numbers appear on Goldor before the core opens: he cannot be damaged on the track.

## The core

### Everyone in (measured)

After the core opens Goldor keeps walking until the last player is inside the core box
(x 39-71, z 54-118, y < 155.5, `DungeonSplits.everyoneInCore`), then leaves the track:
his last track packet is **0 ticks** after the last player's entry in 14 of 18 runs where the
recorder itself was last in (-1, 1, 2, 6 in the others); 23 of 43 at 0 over all views. Earliest
departure: 4-10 ticks after "The Core entrance is opening!" (players already standing inside);
median 18.

### Flight

- Straight line at **0.80 blocks/tick** (1.6 per 2-tick packet) towards **(54.5, ~117, 40.5)**
  (the S4 line in front of the core entrance), sinking 0.03 blocks/tick.
- The flight time is his distance to that point / 0.8: from the S1 line (s ≈ 14-50 in the
  "never fast" runs) 59-85 ticks; from the S4 line (s ≈ 274-288) 40-57 ticks.

### Kill and death animation

Two endings, decided by whether he is dead before he reaches (54.5, 40.5):

- **Killed in flight** (72 recordings): "[BOSS] Goldor: ...." at departure + 55 (15-113), on average 13
  ticks before he would have arrived; then "[BOSS] Goldor: Necron, forgive me." together with
  Necron's first line **81-83 ticks** after "....".
- **Reaches the core** (45 recordings): "You have done it, you destroyed the factory..." exactly on
  arrival (departure + distance/0.8, median +0.6, -1.8 to +3.6 p10-p90), then on a 62-tick
  cadence "But you have nowhere to hide anymore!" (+62), "YOU ARE FACE TO FACE WITH GOLDOR!"
  (+124), "...." (+186) - a script that runs on even after Necron has started. Necron's first
  line comes 81-190 ticks (median ~91) after "You have done it", never sooner than 81: taking the 82-tick
  death-to-Necron gap from the first ending, he died departure + 83 (47-271), i.e. shortly
  after arriving.
- Hits needed / his health: **not measurable** - the recordings have no boss bar or damage
  events for other players, and damage numbers near him were captured in only 6 runs
  (2-7 numbers of 0.8M-673M each, in the core).

## Fastest runs

| run | P3 | S1 / S2 / S3 / S4 | core -> dead | dead -> Necron | start -> Necron |
|---|---|---|---|---|---|
| 20260927-225414 | 681 | 282 / 109 / 170 / 120 | 83 (reached the core) | 82 | **846** |
| 20260927-001859 | 724 | 232 / 158 / 202 / 132 | 64 (in flight) | 83 | 871 |
| 20260926-012009 | 738 | 260 / 197 / 151 / 130 | 55 (in flight) | 81 | 874 |
| 20260927-225448 | 746 | 248 / 193 / 212 / 93 | 65 (in flight) | 82 | 893 |
| 20260926-012515 | 765 | 251 / 220 / 190 / 104 | 50 (in flight) | 81 | 896 |

What they did: S1 in 232-282; every gate blown early (S1's at 38-89, long before the door), so
the doors opened on the last completion (except 012515's S3 door, which opened with its gate at
n = 661); the S2 and S4 devices done during S1 where completion lines show it (012009,
012515: S2 and S4 started at 2/8, 2/7); everyone in the core 16-28 ticks after it opened; Goldor
never caught up (on the S1 line, s = 14-26, when he left) and was killed in flight in four of
the five, 50-65 ticks after the opening; the fastest (225414) killed him right on arrival.

**Floor (measured parts only)**: the Goldor split cannot be shorter than
flight-to-kill + 81-83; the best observed core -> Necron line was 119 (killed 37 ticks after
the core opened, Goldor on the S4 line, 21 ticks after he left it). Adding the best observed section times gives 193 + 109 + 129 + 93 = 524 for P3
(sum of bests, not a single run) and ~642 to Necron's first line; the fastest real run is 846.
Conjecture: a shorter flight helps only if he can be killed before arriving, so a Goldor that has
caught up to the S4 line (44-57 tick flight) favours the "killed in flight" ending, while a Goldor
left on the S1 line (58-80) is more often reached.

## Measured vs conjecture

Measured: section contents and positions, counting and early-device credit, gate/door rule and the
5-second gate, core door timing (19 ticks), the 60-tick death-tick grid and who it hits, the
62-tick dialogue grid, Goldor's start point, track, 0.0600 walk, 0.60 catch-up (always one
segment behind, ending at the next section's start), 0.80 flight to (54.5, 40.5), departure on
the last player's entry, the two endings and their timings, "...." -> Necron 81-83.

Conjecture: what decides whether Goldor ever catches up (the "was he in the segment of the
section that just ended" rule), what slows him (being hit), the 100-tick automatic gate, and the
kill time in the "reached the core" ending (Necron line - 82).

Open: Goldor's health and the number of hits; the exact everyone-in box Hypixel uses (players
counted "in" 4-10 ticks after the opening, before the door's barriers go at +19, so they were
inside the core volume already).
