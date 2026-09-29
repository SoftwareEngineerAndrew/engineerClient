# The Watcher (F7 blood room): mechanics measured from Better PF recordings

Measured from the Better PF recordings of 2026-09-23 to 09-29. Scripts: `tools/boss-mechanics/watcher/`
(see its README); every number here is printed by `analyze.py` or `sim.py`. The camp is taken from
the Watcher's first line ("Things feel a little more roomy now, eh?") to "You have proven yourself.
You may pass." and the portal.

**Data.** 278 recordings, 267 of which reach the Watcher, form 218 runs (176 with one recording,
40 with two, 2 with three). 152 runs have server ticks (`st`) in every recording. Those are the
only ones used for timing, and 142 of them reach "proven". Left out: the four alpha-server
recordings, and `20260928-204121-d4bd4083` as suspected alpha. That run was on the same server
(mini5J) as an alpha recording and has the alpha-only one-line dialogue "Ah, we meet again. As I
foresaw...". Siblings are aligned on their shared Watcher lines to within +-3 ticks and merged:
blood mobs by name, wall skulls by server entity id.

**Conventions.** Times are server ticks (20 a second) after the chat line "The BLOOD DOOR has been
opened!" (**D**). Each line gets the `st` value last seen before it, and chat is +-1 tick. Positions
are relative to the middle of the blood room (the block corner at room origin + 16). The room's two
orientations just swap x and z. Watcher and skull positions are de-interpolated (`wlib.delerp`, the
same 3-step lerp inversion as `tools/boss-movement`). A blood mob's **death** is its removal minus
20: the 0-health name tag leads the removal by 19-21 ticks (mode 20, n = 540). "p10-p90" is the
middle 80%. **Measured** means the recordings show it. **Conjecture** marks an explanation the data
does not prove.

## Summary

- **The camp is 19 mobs.** Every run has the same 17 named blood mobs, plus a Giant and one of
  Scarf / Bonzo / Livid / Spirit Bear. Each one sits as a **head on the wall** until the Watcher
  flies to it. He "throws" it: the head flies to a random point near the middle of the room and
  the mob appears where it lands. There is no alive cap, only the Watcher's pace.
- **The Watcher is a clock.** In the dialogue phase he acts on a **30-tick grid** from D (first
  move D+240) and fetches 4 heads. After "Let's see how you can handle this." he acts on a
  **40-tick grid** from D. With no blood mob alive he flies **0.61 blocks/tick**, and every 40-tick
  step starts the next head: one head per 40 ticks, two steps when a leg is longer than ~24 blocks.
  While **any blood mob is alive** he flies **0.44 blocks/tick** and waits about half of his
  20-tick steps.
- **The camp ends on kills.** "You have proven yourself" comes on the first 10-tick check (D+10k)
  after the 19th mob dies, 0-8 ticks later. The exception is a Watcher line said shortly before:
  then it waits for the speech gap (+41-44 after a kill taunt, +61-63 after a spawn line).
- **Fastest possible Watcher split: about 1090 ticks (54.5 s)** with the best head order there is,
  if every mob dies the tick it lands and he moves at D+480 (the earliest recorded).
  - A 1-in-100 order gives ~1110 ticks (55.5 s) and a typical one ~1225 ticks (61 s).
  - Recorded best: **1137 ticks (56.85 s)** and 1147 ticks. 5-player median: 1308 ticks (65.4 s).

## A typical run

`20260929-003031-3b28a4d6` (5 players), server ticks after D. Skulls: slot -> flight -> mob.

| D+ | what |
|---|---|
| 0 | blood door opens; the Watcher is at the middle (-0.5, 73.0, -0.5) with two "Watchful Eye" zombies |
| 2 / 84 / 166 | "Things feel a little more roomy now, eh?" / "I've knocked down those pillars..." / "Plus I needed to give my new friends some space to roam..." |
| 240 | leaves the middle (0.70 b/t) for his first head; arrives 262 |
| 270 | Giant head launched (0.20 b/t, 81-tick flight) -> Giant at 351 |
| 271-302, 330-340, 361-397 | three more legs (on the 30-grid): Spirit Bear head 310 (-> 390), Ooze 350 (-> 431), Psycho 406 (-> 486) |
| 420-445 | flies back to the middle (0.50 b/t) |
| 468 | "Let's see how you can handle this." (waits for the 62-tick gap after "This guy looks like a fighter." at 405) |
| 640 | the "Watcher move": first leg of the fetch phase (0.61 b/t); then legs at 680, 720, 760, 800, ... all on D+40k |
| 670 ... 1331 | 15 more heads (0.30 b/t, 40-42-tick flights), each launched 7-11 ticks after he reaches its niche |
| 901, 1080, 1140, 1260 | legs flown at 0.44 b/t: blood mobs were alive |
| 1360-1384 | after the last head, back to the middle |
| 1371 | last mob (Leech) lands; the last death is at 1400 |
| 1413 | "You have proven yourself. You may pass." |

## Dialogue and the Watcher's speech

**Measured.**
- The three dialogue lines are on fixed delays from D: **+2** (1-4), **+84** (83-86) and **+166**
  (164-168), n = 151. The split therefore starts at D+2.
- After that his lines are spawn lines ("You'll do.", "Go, fight!", "Go and live again!",
  "Hmmm... this one!", "This guy looks like a fighter."), kill taunts ("Not bad.", "Aw, I liked that
  one.", "That one was weak anyway.", "I'm impressed.", "Very nice.") and a few others.
- The lines are spaced by the type of the line before (p5 / median of the gap to the next line):

  | line before | gap to next |
  |---|---|
  | a kill taunt | 41 / 42 (n = 989) |
  | a spawn line or "handle this" | 61 / 62 (n = 1304) |
  | a dialogue line | 81 / 82 |

  Lines are not 1:1 with events: runs have 3-14 spawn lines and 1-12 kill taunts for 19 mobs.
- **"Let's see how you can handle this."** comes when the Watcher is back in the middle after his 4
  dialogue-phase heads. Of 41 runs where his return was recorded, 15 said it on arrival (-3..+2
  ticks). 26 said it later, exactly **41-65 ticks (24 of them at 61-63) after the previous line**,
  when the arrival fell inside that gap. Range D+385..521, median D+453.
- "That will be enough for now." is a queued line once all heads are thrown. It comes after
  "proven" in 39 runs, before it in 22, and is often never said, so it marks nothing.
- Rare lines not studied here: "My Watchful Eyes see all!" and "...see you up there! Come down and
  fight!".

**Conjecture.** The lines go through a speech queue with a cooldown of 42 ticks after a taunt, 62
after a spawn line and 82 after dialogue. Party-chat mod messages ("Watcher will move in 1.20s /
1.35s / 1.50s", "Normal / Slow Watcher") sort runs by the time of "handle this": 1.20 s is H <= ~443
and "Slow" is H >= ~504. They do not match the measured delay to the move (below).

## Blood mobs

**Measured.**
- **Count.** Every run where they were in view has exactly the **17 regulars**: Cannibal, Flamer,
  Freak, Frost, Leech, Mr. Dead, Mute, Ooze, Parasite, Psycho, Putrid, Reaper, Revoker, Skull, Tear,
  Vader, Walker (217 of 217 runs). They are player-shaped entities (uuid v2) named "Name ".
- **Mini-bosses.** Every run also has a **Giant** (a `minecraft:giant`) plus **one** of Scarf, Bonzo,
  Livid or Spirit Bear, **19 mobs in all**. In runs where the recorder saw the start: Giant + Spirit
  Bear 22, + Livid 16, + Bonzo 14, + Scarf 11.
  - The upside-down "Dinnerbone" giant that sometimes appears is the Giant's sword prop, not a mob.
  - "Undead" and "WanderingSoul" player entities seen in a few runs are not Watcher spawns.
- **Mob stats.** From name tags in the recordings that have them (the first tag seen is often
  already damaged, so these are maxima): regulars have up to 331k HP, Ooze up to 412k, the Giant
  9.7M-72.4M (median 19.5M) and the other mini-bosses 10M-48.3M. Each has a normal dungeon
  modifier (Speedy, Healthy, Stealth, Golden, Boomer, Stormy).
- **The wall skulls.** 30 armor stands wearing heads stand in **10 wall niches x 3 heights**
  (y 71.75, 75.75, 79.75):
  - two opposite walls have 3 niches each, 12 blocks out, at 0 and +-4 along the wall;
  - the other two walls have 2 niches each, 12 blocks out, at +-9.

  22 of the heads have a mob's skin, one per mob (the skin -> mob table is printed by
  `analyze.py mobs`; 2 mismatches in 1,200). Odin-style "next mob" predictions read this. The
  other 8 skins are decoys that never move.
- **Spawn.** The mob appears **when its head lands** (first seen 0-2 ticks before the head's
  removal, n = 1173), 1.75 blocks above the head.
  - Before "handle this" heads fly **0.200 blocks/tick** (81 ticks, n = 229). After it they fly
    **0.300 blocks/tick** (40-42 ticks, median 41, n = 1025).
  - Landing points are random around the middle block: median 2.3 blocks from it, p90 4.3; y 72.7-74.8.
  - Landing points are not aimed at players: the nearest player is 7.4 blocks away (median).
  - So the spawn "grid" is really the Watcher's route plus a fixed 41-tick flight.
- **Order.** In the dialogue phase: **Giant, mini-boss, regular, regular** (18 of the 30 runs with
  those launches recorded; the rest are the same minus heads thrown before the recorder was in
  view). After that the 15 remaining regulars come in random order. The next head's distance rank
  among those left is flat: nearest 62, then 51, 61, 43, 44 (farthest).
- **No cap.** Up to 18 were alive at once in solo runs, and the Watcher kept spawning.

## How the Watcher moves

**Measured.**
- **Identity.** He is a zombie with head skin `5662b6fb4b8b...` (every F7 run). He starts at the
  middle (-0.5, 73.0, -0.5), next to two "Watchful Eye" zombies (skin `37cc76e7af29`).
- **Dialogue phase (D+240 to his return).**
  - He leaves the middle at **D+240** (16 of 16 recorded).
  - Every departure is on a **30-tick grid from D** (109 of 131 at +0..2 mod 30).
  - To a head he flies at **0.699 b/t** (p10-p90 0.695-0.704); back to the middle at **0.500**.
  - He fetches **4 heads** and returns to the middle; departures for the return: D+360..452.
  - The heads leave 7-11 ticks after he reaches each niche.
- **The move.** The first leg after "handle this" is the "Watcher move".
  - It comes at **D+480 to D+760** (p10 481, median 541, n = 59). The values are only D+480, 520,
    540, 560, 600, 640, 680...
  - It is **42-292 ticks after "handle this"** (p10 61, median 83, p90 172). BossDetail.kt's
    55-148 is inside this.
  - It is never earlier than D+480 and never earlier than H+42:
    - H <= D+424: 7 of 9 moved at D+480;
    - H = D+425..449: mostly D+520-561;
    - H = D+450..499: mostly D+540-562;
    - H >= D+500: D+601-681.
  - Mobs still alive do not block it: solo runs with all 4 dialogue mobs alive moved at D+540.
    Beyond that the exact step is not predictable from the data (see conjecture).
- **Fetch phase: 15 heads.**
  - Departures with no blood mob alive are on the **40-tick grid from D** (507 of 520).
  - The Watcher leaves on the **first D+40k tick after he arrives** at a niche (481 of 508). That
    can be 1 tick after arriving, even before that head has left. 18 cases left one step later.
  - The head leaves **7-11 ticks after he arrives** (mode 9, n = 507).
  - He flies at **0.613 b/t** (429 of 474 legs). So one head per 40 ticks, and two steps for a leg
    over ~24 blocks (cross-room, which the random order makes common).
  - With **at least one blood mob alive** he flies at **0.438 b/t** (79 of 84 legs). He departs
    on 40-grid or 20-grid ticks alike (45 / 42), and waits in 90 of 161 idle 20-tick steps.
    Rare 0.913 b/t legs (13) are short hops between neighbouring niches.
  - He stops about 0.5 block out from the niche, 1 block above the head.
- **End.** After the last head he flies back to the middle at 0.50 b/t.

**Conjecture.**
- A 20-tick AI loop that acts every second loop while no blood mob is alive and every loop with a
  coin flip (or another state we cannot see) while one is.
- D+480 = 16 x 30 = 12 x 40 is where the dialogue phase's 30-tick grid hands over to the 40-tick
  one, which would be why no move happens earlier. A move at D+440 (with "handle this" before
  ~D+398) is not ruled out, but was never seen.
- The extra 0-3 grid steps before the move look random.

## What ends the camp, and the portal

**Measured.**
- **The camp ends when all 19 Watcher mobs are dead**, not on a timer. "Proven" never came before
  the last death: P - last death >= -1. In slow solo runs it waited up to 25,800 ticks.
- 42 of 62 complete runs: "proven" is on the **D+10k grid** (mod 10 = 9/0, chat +-1), **-1..8
  ticks after the last death** (a few 15-23 where the last death is uncertain).
- The other 20 waited for the Watcher's speech. Each came exactly **41-44 ticks after a kill taunt**
  (11) or **62-63 after a spawn line** (8) said just before. That cost 4-32 ticks (up to ~1.6 s).
- The **portal** (12 nether portal blocks at once, in the middle of a wall) appears **59-92 ticks
  after "proven"** (median 84, n = 132), on the D+10k grid (119 of 132).

## Fastest possible Watcher split

The split is D+2 -> "proven". It breaks down as:
**(move - D) + (last head's landing - move) + (proven - last landing) - 2**.

**Measured.**
- **Splits** (141 runs): min **1137**, p10 1224, median 1317, p90 1507 ticks.
  - 5-player runs: min 1137, p10 1218, median 1308, p90 1436.
  - Solo: median 2348.
- **Fastest recorded.**
  - `20260927-225424-0e465daa`: 1137 ticks (56.85 s), H = D+417, incomplete view.
  - `20260925-235519-9107c57f`: 1147 ticks (57.35 s). H = D+422, move D+480, last landing D+1146,
    last death 1147, proven 1149. There were only 70 ticks with a mob alive after the move, and
    16 steps for 15 heads.
- **Fast-kill 5-player runs** (<130 ticks with a mob alive after the move, n = 42): last landing
  - move = min 623, p10 682, median 738, p90 810. Proven - last landing: p10 3, median 10.

**What the fast runs did differently.** Fastest 10 vs the other 37 complete 5-player runs,
medians:

| | fastest 10 | other 37 |
|---|---|---|
| "handle this" | D+444 | D+459 |
| move | D+521 | D+542 |
| last landing - move | 690 | 760 |
| ticks with a mob alive after the move | 70 | 89 |

Correlation with the split: move 0.57, "handle this" 0.54, alive ticks 0.27 (n = 47). In order,
the fast runs had:
1. **Luck in the head order.** Fewer cross-room legs means fewer 40-tick steps lost; this is the
   largest part.
2. **An early "handle this"**, from dialogue-phase heads near each other and no speech backlog,
   so the move came at D+480-521.
3. **Mobs killed as they land.** This keeps the Watcher at 0.61 b/t with no waiting steps.
4. **No taunt said just before the last kill**, which would cost up to 44 ticks.

**Model (sim.py).**
- The fetch phase is simulated with the measured rules: 0.61 b/t, launch +9, depart on the first
  D+40k tick after arriving, head 0.30 b/t to within 3 blocks of the middle, 15 heads in random
  order from 30 slots, every mob killed on landing.
- It reproduces the fast-kill runs: last landing - move is p10 / median / p90 670 / 740 / 819,
  against 682 / 738 / 810 recorded. Doubling up heads at one stop, which the simulation allows,
  makes it faster than any recorded run, so it is not what happens in practice.
- With the move at D+480 (200,000 simulated runs), the last landing is at:

  | route | last landing | split |
  |---|---|---|
  | best | D+1087 | **1085-1095 ticks** |
  | p1 | D+1108 | ~1110 (55.5 s) |
  | p10 | D+1150 | ~1155 (57.7 s) |
  | median | D+1219 | ~1225 (61 s) |

  The split adds proven - last landing (0-8 on the grid) and subtracts 2.
- A move at D+440 (never seen) would take 40 ticks off each.

**Floor: ~1085-1095 ticks (54.3-54.8 s).** It needs the best head order, the move at D+480,
instant kills and the proven check right after the last death. A realistic "everything went right"
run is ~1110-1155 (55.5-57.7 s), which is where the recorded best (1137, 1147) sit.

## Measured vs conjecture

| Measured (spread in the text) | Conjecture |
|---|---|
| 17 regulars + Giant + 1 of 4 mini-bosses; mob = its wall head's landing | why a mini-boss is picked |
| dialogue lines at D+2/84/166; speech gaps 42/62/82 by the line before | a speech queue with per-type cooldowns |
| dialogue-phase 30-tick grid, 0.70 b/t, 4 heads at 0.20 b/t; first move D+240 | the phase switch at D+480 |
| fetch phase: 40-tick grid, 0.61 b/t and no waits when no mob alive; 0.44 b/t and ~half waits when one is | a 20-tick loop that acts every other loop when idle |
| launch 7-11 after arrival; head 0.30 b/t, 41 ticks; random head order | - |
| move = D+480...760, >= H+42 | the extra 0-3 steps are random |
| proven on the D+10k grid 0-8 after the last death, or at the speech gap; portal +59..92 on the D+10k grid | - |

## Caveats

- Blood mob spawn times come from the recorder's first sighting. A mob first seen on the floor or
  away from the middle is dropped. Early mini-bosses are often missed when the recorder entered the
  room late, which is why only ~40% of runs show them.
- Deaths are removals - 20. The 0-health tag is only in some recordings.
- The Watcher's track has gaps in many recordings, so leg-level counts use the runs where it was
  seen. Short hops between neighbouring niches can merge into one leg.
- 66 runs without server ticks are used only for counts (mob names, skins), not for timing.
