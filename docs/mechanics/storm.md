# Storm (F7 phase 2): all the mechanics

An overview of everything Storm does, measured from the Better PF recordings (2026-09-23 to
09-29, alpha-server runs left out). Two earlier documents go deeper and are only summarised here:

- [`storm-crush.md`](../storm-crush.md): the crush check (when, where, "recently lowered").
- [`maxor-storm-movement.md`](../maxor-storm-movement.md) §3-§4: the opening route, parking,
  departure, the first chase, the flight to Yellow, targeting, the fastest split.

New here: the pads and the pillars' whole life cycle, the lightning and Storm's other attacks, what
he does at Yellow and after Yellow, what ends the pin, and what kills him. Numbers come from
`tools/boss-mechanics/storm/mech.py` (see its README). Times are **server ticks after Storm's first
line** ("Pathetic Maxor, just like expected."), from the 134 runs whose every recording has server
ticks; "check" means a crush check, t = 19 (mod 20). **Measured** facts and **conjecture** are
marked as such.

## Timeline at a glance

| t | what | source |
|---|---|---|
| -1 | his wither spawns at (103, 188, 53); the 20-tick check grid starts | storm-crush |
| 19 (first check) | Purple and Yellow drop to the floor when someone stands on their pads (at t 19 in 70 / 86 of 134 runs, by t 59 in ~95) | here, §2 |
| -1 → 424 | fixed diamond route at 0.40 blocks/tick, then parked at (102.375, 183, 52.375) | movement §3.1 |
| 100-460 | Static Field and Lightning Fireball hit players 14-81 blocks away | here, §3 |
| 548 (546-552) | lightning line; **Giga Lightning** strikes at +10 and +20 | here, §3 |
| 687 | leaves the spot (lightning + 139), chases the 3D-closest player at up to 0.9/tick | movement §3.3 |
| 699 | crush 1 on Purple (best case) | storm-crush |
| crush + 0-183 | "⚠ Storm is enraged! ⚠": the pin ends | here, §5 |
| crush + 20 | the crushing pillar resets and is spent for good | here, §2 |
| enrage + 2-3 | flight to Yellow's point at 0.7157/tick with a slow-down | movement §3.5 |
| arrival | chases the 3D-closest player again, slower | here, §4 |
| ~899, then every 60-63 | a random taunt ("BEGONE PILLAR!" is one) | here, §6 |
| crush 2 (799 best) | pinned for good; dead 0-30 ticks later (median 6) | here, §7 |
| death + 62 / + 102 | "At least my son died by your hands." / Goldor's first line | movement §3 |

## 1. The arena

- **Four crushers.** 7x7 rounded pillars (37 blocks a layer) of polished diorite, hanging from
  y 189/190 over the floor (y 169): Purple (x 97-103, z 62-68), Yellow (43-49, 62-68), Green
  (43-49, 38-44), Red (97-103, 38-44). Crush zone and rule: see storm-crush.md.
- **Four pads** (measured from the recorded arena blocks). Each is a 7x7 square of stained glass
  and terracotta in the pad's colour, top at y 169, ringed by stone-brick stairs. Each is 29 blocks
  from its pillar towards the nearer +z/-z wall and 14 blocks further out in x:

| pad | square (x, z) | lowers |
|---|---|---|
| Purple (purple glass, blue terracotta) | 111-117, 91-97 | Purple |
| Yellow | 29-35, 91-97 | Yellow |
| Green (lime) | 29-35, 9-15 | Green |
| Red | 111-117, 9-15 | nothing: 13 checks with a recorder on it, the Red pillar never moved in any run |

## 2. Pads and pillars

**Measured:**

- **Start state.** Purple, Yellow and Green hang with their bottom at y 175 until they first move
  (their first step always reads 174). Red never moved, so its height is unknown. Nothing moves
  during Maxor.
- **Pads are read on the crush checks.** A pillar starts coming down only on a check (t = 19
  mod 20). Of 560 descents from a resting level, the first new layer appeared 0-2 ticks after a
  check in 506 and 1 tick before one in 27 (the recordings' ±1 tick).
- **One check on the pad = exactly 5 steps**, one block every 4 ticks (the check, +4, +8, +12,
  +16). From 186: 140 descents of 5 steps, 5 of 10. From 181: 112 of 5, 11 of 10. Someone still
  on the pad at the next check adds another 5 (10-step descents). The descent stops at the floor
  (169).
- **Who counts.** A player standing on the 7x7 glass-and-terracotta square (y 170) at the check.
  With recorders at a pad at a check, while its pillar was armed: on the square, 41 checks started a
  descent and 15 did not; off it, 0 of 215 did. Nearly all of the 15 had just stepped onto the pad,
  0-4 ticks before the check: the server sees a player's position a ping late. Counting the stairs
  ring as well (a 9x9 square) would add 107 false starts.
- **When the pad does nothing.** The pad has no effect while the pillar sits on the floor, is being
  drawn up or is fully drawn up, or is spent. In those states a recorder stood on the pad at 155
  checks, and the pillar moved at 1. A check right after a descent's last step does start the next
  5.
- **The floor cycle.** A pillar that reaches the floor (169) waits 28 ticks (27-29). It is then
  drawn up a layer every 4 ticks all the way to its top (y 189, about 76 ticks). 24 ticks later it
  steps back down 3 layers to **186**, where it rests armed. Floor to back at 186 takes 136 ticks
  (p10-p90 135-156).
- **The opening drop.** In almost every run someone stands on the Purple and the Yellow pad at the
  first checks (t 19 and 39). The pillar goes 175 → 169 (6 steps: 5 plus 1 to the floor), then
  runs the floor cycle and is armed at 186 from about t 175. Players then lower it again in 5-step
  batches (Yellow is often pre-set to 181 early; Purple to 181 on the t 639 check).
- **No idle reset.** An armed pillar keeps its height indefinitely: seen unchanged for up to 1,144
  ticks at 181, 924 at 186, 584 at 176.
- **Reset after a crush.** 20 ticks after a crush (the next check; 19-21 in 208 resets), every
  extended layer of the crushing pillar turns to air at once. The pillar is then spent: in 0 of 208
  resets did it ever come down again, even in runs where Storm lived another 850 ticks. This is
  why he can't be crushed twice by one pillar. It explains the "7 pinned checks at t 719 that
  didn't crush" in movement §3.4: Purple resets on that very check.
- **Green** moved in 19 runs, from its pad like the others. A first press takes it 175 → 170.

**Conjecture:** the pad check and the crush check are one server routine run every 20 ticks from
the wither's spawn. The ~28 / ~68-tick waits in the floor cycle are timers of their own, not tied
to the 20-tick grid.

## 3. Lightning and other attacks

**Measured:**

- **Lightning line** ("ENERGY HEED MY CALL!" or "THUNDER LET ME BE YOUR CATALYST!", 50/50): t 548
  (546-552, n 131). Off the check grid.
- **Giga Lightning = two strikes**, at line **+10** (8-13) and **+20** (18-22), each
  "Storm's Giga Lightning hit you for N true damage." N is 1,783-18,194 (median 9,350): the same
  for both strikes on one player in 62 of 65 recordings, different between players.
- **Who it hits: everyone except players standing on the floor under a pillar.** A player's
  "under a pillar" means inside one of the four 7x7 squares at y 168.5-170:
  - under a pillar: 161 of 164 strikes missed them;
  - anywhere else: 143 of 148 strikes hit them. That includes players below the arena in P3
    (y 116-120), directly under a pillar's square.
  - The exceptions stood on a square's edge or corner, or had just moved.
- **Second lightning** (Storm still alive about 900 ticks after the first): only 4 runs.
  - The line came 889, 940 and 979 ticks after the first. Only the 940 was on server ticks; the
    others are client ticks.
  - Its strikes came at line +36 and +46, not +10/+20.
  - One run (`8b4c99f9`) had the two strikes (t 1413/1423) with no line at all.
  - The "third lightning" of movement §3.6 is not in the live runs.
- **Other hits on the recorder:**
  - Static Field (10,800 damage): t 105-460, at 14-81 blocks from Storm.
  - Lightning Fireball (75,000): t 100-420, at 15-69 blocks, plus once at 1775.
  - Frenzy (2,000-42,000): only while Storm is free after crush 1 (t 798-1018), at **≤ 6.3
    blocks** from him, about every 10 ticks.

## 4. Movement after the flight (replaces movement §3.6 "Later chase")

**Measured:**

- **Only Purple → Yellow is a scripted flight** (movement §3.5).
  - After a crush on **Yellow or Green**, Storm does not fly to the next pillar. He chases the
    3D-closest living player: head within 3.3° median of them (69% within 10°), and up to 119°
    off Green's point (46, 41) in `8065461d`. 4 Yellow crushes, 1 Green.
  - Parties just walk him to Green. The movement doc's "Yellow → Green, Green → Yellow" flights
    and its `NEXT` table are this chase.
- **The flight ends at the point.** His head leaves the flight line when he is **2.4 blocks
  (median; p10 1.0, p90 4.2)** horizontally from Yellow's point, 3 ticks earlier to allow for the
  head's lag (n 69), not at ~5. From then he chases.
- **The chase at Yellow** (101 of 122 flights arrived before the next crush; median 10 ticks
  there, 26 runs ≥ 20 ticks):
  - Target: the 3D-closest living player. Head within 3.4° median (72% within 10°).
  - Height: he flies ~3.1-3.5 blocks above their feet.
  - Speed: horizontal speed ≈ **0.145 + 0.0205 · d** (d = horizontal distance to them, 3-25
    blocks). That is 0.28 at 5 blocks, 0.37 at 12. The first chase fits 0.21 + 0.021 · d over the
    same distances: the same slope, about 0.065 slower.
  - Within ~3 blocks he circles the player rather than closing in (velocity 80° off the bearing).
- **"79 of 110 never chase between crushes"** (movement §3.5) is right, but only because crush 2
  usually comes within 10 ticks of arriving.
- **After crush 2** he never moves again (at most 0.49 blocks between crush 2 + 5 and death,
  n 84): see §7.

**Conjecture:** the slower speed at Yellow is his velocity easing up from the flight's ~0.4 (the
inertia model of movement §3.7), not a separate speed law. The first chase arrives at 0.9.

## 5. The pin (crush 1 → "⚠ Storm is enraged! ⚠")

**Measured** (130 server-timed crush-1 pins):

- **Length:** median 10, p10 1, p90 25, range 0-183. 52 are ≤ 4 ticks. There is a second bump at
  21-25: 10 pins of exactly 24, ending 3-4 ticks after the pillar's reset at crush + 20.
- **The Mage's beam ends it.** In runs recorded by the party's Mage, take the arm swings with his
  view within 3° of Storm and ≤ 45 blocks away (a Hyperion swing is the Mage's ranged beam).
  - In **35 of 47** pins "Storm is enraged!" came **0-1 ticks after the first such beam after the
    crush**: beam at +2, enraged at +3, and so on.
  - In 9, 1-4 on-target beams did not end it. In 5 of those a later beam at +21 to +35 (after the
    reset) did, 1-2 ticks later.
  - 3 had no on-target beam.
- **Strongly party-dependent**, by the party's Mage:
  - `p3wr` 47 pins, median 3 (37 ≤ 4);
  - `cclc101310` 8, median 3.5;
  - `Noobalic` 18, median 13.5;
  - `FreeZooat` 8, median 18;
  - parties without a Mage: 50 and 183 ticks. The 183 was a solo run: he broke free saying
    "FINALLY! This took way too long."
- **No relation** with how many players are within 10 or 20 blocks (Spearman 0.02 / 0.05),
  living players (-0.17), the nearest player's distance (-0.10) or the crush time (-0.01).
  - The nearest player is typically 23-44 blocks away during the pin (at Yellow): the damage is
    ranged.
  - The correlation with his pinned height (0.46) is the party again: `p3wr`'s lure brings him
    in lower.
- **0-tick pins** (5): all 3 recorded by the Mage had a beam on the crush tick itself.
- **Pre-damage** (hits before the crush): **untestable**. No recorded Mage had an on-target
  beam in the 80 ticks before crush 1 (0 of 44): Storm is 40+ blocks further away then.

**Conjecture:** the pin ends at a damage threshold. One beam from a strong (buffed) Hyperion clears
it; weaker parties need several hits. The pile-up at crush + 24 suggests the threshold drops, or
something else releases him, once his pillar resets at crush + 20. The recorder can't show health,
so the threshold itself is unmeasured.

## 6. Taunts ("BEGONE PILLAR!")

**Measured:**

- **When.**
  - The first taunt comes at t 882-999 (median 899) after his first line, whatever happened
    before. Relative to crush 1 that reads ~200, as in movement §3.4.
  - Later ones come every 60-63 ticks.
  - 18 of 19 runs alive past t 910 had one.
- **The pool** (lines seen): BEGONE PILLAR!, No more adventurers..., FINALLY! This took way too
  long., Not just your land..., This factory is too small for me!, Slowing me down will be your
  greatest accomplishment!, The days are numbered..., Now that you're a Ghost..., The Age of Men is
  over..., THAT WAS ONLY IN MY WAY!
- **"BEGONE PILLAR!" does nothing to a pillar.**
  - 5 cases, 4 with Storm out of view.
  - In `d7133301` he was outside every zone while players stepped Yellow down by pad.
  - No pillar reset within 20 ticks of any of them.

## 7. Death

**Measured:**

- **After crush 2 he is never released.**
  - 173 of 173 runs with two crush lines died pinned: no enrage line after crush 2, no movement.
  - The death line comes 0-30 ticks after crush 2: median 6, p10 3, p90 23, one at 100.
  - The same two bumps as the pin: 0-10 ticks (most) and 22-30 (just after that pillar's reset).
  - The death line is not on the check grid (it arrives 1-10 ticks after one).
- **Every death follows a second crush, sometimes a silent one.**
  - 21 runs died with only one crush line, while flying at Yellow or Green.
  - In all 21 a pillar reset 35 ticks before to 20 after the death line. A reset marks a crush 20
    ticks earlier.
  - In 11 of the 12 with Storm in view, the crush rule held at a check 1-37 ticks before death:
    in the zone, head in the pillar, the pillar lowered ≤ 24 ticks before.
  - In 1 more it missed by 0.03 blocks. So the second crush happened without its "Oof"/"Ouch"
    line.
- 5 runs never reached a death line (wipes, no crush).

**Conjecture:**

- Crush 2 pins him until the same kind of damage threshold as the pin (§5), and crossing it kills
  him instead of enraging him.
- The bursty 2-8-tick deaths are the party's burst landing at Yellow, where players stand close.

## Open

- Health. A recorder that logs the boss bar would settle §5 and §7 (the thresholds, pre-damage,
  the drop at the reset).
- Why the pad/check seems to need the player there before the check (server latency, or a pad
  hitbox inset). The 15 misses were all late arrivals.
- The second lightning's timer (4 runs), and whether it needs a line.
- Green's crush zone and point, and any Red use (never in F7).

## Reproducing

```
python3 tools/boss-movement/extract.py DATA OUT && python3 tools/boss-movement/tracks.py OUT
python3 tools/boss-mechanics/storm/extract.py DATA OUT
python3 tools/boss-mechanics/storm/mech.py OUT            # every section
python3 tools/boss-mechanics/storm/mech.py OUT geometry DATA
```
