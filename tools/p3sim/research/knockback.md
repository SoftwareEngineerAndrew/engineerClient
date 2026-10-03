# Self knockback in the F7 boss: Bonzo, Jerry-chine and everything else

Source: 77 Boss Recorder files (`bossrecorder/*.jsonl.gz`, 1349 velocity packets on yourself) joined by
wall-clock ms with the same sessions' Better PF runs (held Skyblock id per tick). Scripts are in
`scripts/` (see the end). **[M]** means measured, **[C]** means conjecture or inferred.

## The ground rules (all measured)

- **[M] Hypixel gives you knockback only with `v` (set-entity-motion) packets on yourself.** None of the 867
  explosion packets (`ex`) in the files carries knockback (kx/ky/kz are always 0), and every one has
  radius 0.0. Hypixel uses them only for the visual and sound. The sim should never apply vanilla
  explosion knockback.
- **[M] A `v` packet SETS your velocity.** Bonzo's vy is exactly 0.5 and Jerry's exactly 0.6 every time,
  whether you were rising, falling or standing, and Bonzo's horizontal size is exactly 1.5 whatever you
  were doing. Nothing is added to your current motion. Values are rounded to 1/8000 (the protocol) and
  clamped to ±3.9 per axis (vanilla packet clamp; seen as 3.8999 outside the boss).

## 1. Bonzo's Staff (STARRED_BONZO_STAFF / BONZO_STAFF), "Showtime"

Samples: 407 Bonzo boosts (`v` with vy 0.5 and |v_xz| 1.5). 255 of them could be matched to a firework
and a balloon stand while holding the staff (`kb_bonzo2.py`).

| | value | |
|---|---|---|
| Spawned on right click | one **`minecraft:armor_stand`** (the balloon) on the click's server tick: invisible (data 0 = 0x20), small/no-baseplate/marker flags (index 15 = 18), owner data 0, velocity 0. **No move packets are ever sent for it**, so the client sees it stay where it spawned. It spawns about 0.7 to 1.1 below your feet, a little along your look. | [M] |
| Click sound | `entity.ghast.ambient`, hostile, at your feet, volume 1, pitch about 1.5 to 1.75 (random) | [M] |
| Detonation | a **`minecraft:firework_rocket`** is spawned at the burst point with entity event 17 (firework explode) on the same tick. The client draws the burst and plays the blast. There is no `ex` packet and no other sound. The stand and the rocket are removed 1 to 2 ticks later. | [M] |
| Click → burst | 3 ticks (95), 2 (62), 4 (50), 5 (33), 6 (9), 7 to 8 (6), out of 255 | [M] |
| Burst → your `v` | same tick (157) or 1 tick later (90), 2 ticks (8) | [M] |
| Burst point | along your look from the eye, *past* the surface it hits. Looking down (pitch over 45°) while standing, where the floor is 1.62 to 2.3 along the look, the burst is 2.25 to 3.2 along it (median 2.7), about 0.4 to 0.6 below the floor top, about 0.3 off the look line. | [M] |
| Misses | stands that hit nothing are removed after 3 to 6 ticks (mode 5) with no firework and no `v` | [M] |
| Range for the boost | burst-to-you 3D distance: median 2.1, 90th percentile 3.6, max 4.9 | [M] (the true cutoff is not known) |
| Spam | gaps between consecutive balloons while holding the staff are mostly 8 to 9 ticks, with a few 4 to 7 (and 0 to 2, where two stands show up for one click). There is no hard cooldown in the data. | [M] (exact rate [C]) |

**Boost (measured, 255 samples):**

```
d   = (you.x - burst.x, you.z - burst.z)        // horizontal only
v   = (1.5 * d.x/|d|, 0.5, 1.5 * d.z/|d|)        // SET, not added
```

- Horizontal size is exactly 1.5 in all 407 samples, even with |d| = 0.6. Height difference and distance
  make no difference.
- The direction error against `you − burst` (using your last sent position) has a median of 1.3° and a
  90th percentile of 5.4°. That is lag in the position sample. Against `−look` the error is often 90 to 180°,
  so the boost is **not** look-based.
- vy is always 0.5.
- [C] When |d| is about 0, the direction is unknown. A fallback of −look is reasonable.

**Projectile model (conjecture, fits the timings):** it starts at the eye on the click tick and moves
along the look at about 0.75 to 1 block/tick with no gravity. On the first tick its position is inside a
solid block it bursts there (the firework spawns), and you get the `v` that tick or the next. It has
a lifetime of about 5 ticks, which gives a range of about 4 to 5 blocks.

**"Bonzo straight down while running"**: the burst lands where your eye was when you clicked, about 2.7
blocks down the look. In the 3 to 4 ticks it flies, you run on (about 0.28/tick when sprinting, so about
1 block), so `you − burst` points the way you are running. The result is v = 1.5 × run direction plus
0.5 up, set on the tick of the burst (about 4 ticks after the click). Standing still and looking straight
down puts the burst about 0 to 0.3 off, which makes the direction lag-noise.

**Mismatches in SimItems.bonzo today:** it boosts instantly (should be 2 to 5 ticks later, mostly 3 to
4, plus 0 to 1), uses a 4-block instant ray to the hit point (the burst should be about 0.5 into the
block and the boost should be measured from your position *at the burst*), plays `GHAST_SHOOT` (should
be `ghast.ambient`), and has no firework. The magnitudes (1.5 horizontal, 0.5 up, set) are right.

## 2. Jerry-chine Gun (JERRY_STAFF), "Rapid-fire"

Samples: 331 Jerry boosts (vy 0.6). 156 of them were held-item matched (`kb_jerry2.py`).

| | value | |
|---|---|---|
| Spawned on click | an `armor_stand` (the Jerry), owner 0. These are also invisible stands with no movement sent. | [M] |
| Click sound | `entity.villager.trade` at you, pitch about 1.4 to 1.76 (random) | [M] |
| Boost sound | `entity.villager.yes`, volume 1, pitch 1.0, on the boost tick (in 260 of 331) | [M] |
| Click → `v` | 1 to 3 ticks (mostly 2); a few at 4 to 9 | [M] |
| Fire rate | consecutive Jerries are mostly 2 ticks apart (n=17) | [M] small n |
| No firework or explosion packet | 10 of 331 had a firework nearby, but those came from a Bonzo used around the same time | [M] |

**Boost:**

```
vy = 0.6                                   // [M] every sample, SET
v_xz direction = away from the Jerry       // [M] the error to (you - Jerry stand) is under 5 deg in most cases
|v_xz| in [0, 0.5], grows with horizontal offset   // [M] range; max observed 0.499
[C] v_xz = 0.5 * (you - jerry)/|you - jerry| (3D unit vector, xz part)
       // fits roughly: Jerry right under you gives about 0, beside you gives about 0.5
```

- The Jerry is directly below you most of the time (you spam it looking down), so |v_xz| is usually
  0.05 to 0.35. In effect it is "set vy to 0.6 and push a little off the Jerry".
- The horizontal part **replaces** your motion. It is not your own movement: the ratio of v_xz to your
  last move delta is all over the place.
- [C] Range: boosts were seen with the Jerry stand 0 to about 4 blocks away horizontally (most within 2).

**Mismatches in SimItems.jerry today:** it uses vy 0.9 (should be 0.6), keeps your horizontal velocity
(should replace it with up to 0.5 away from the Jerry), boosts instantly (should be 1 to 3 ticks later),
and its trigger is a 5-block ray within 3.5. The sounds are close (villager.trade on click,
villager.yes on the boost).

## 3. Every `v` on yourself in the boss (1349)

| class | count | signature | |
|---|---|---|---|
| Lava bounce | 532 + 47 | `(0, 2.25, 0)` or `(0, 3.0375, 0)` (= 2.25 × 1.35), sometimes with xz ≤ 0.09, plus `entity.player.hurt` or `item.break`. Seen at y=106 (P4/P5 floor lava) and y≈56 (P5). | [M] researched separately |
| Bonzo | 407 | see §1 | [M] |
| Jerry-chine | 331 | see §2 | [M] |
| Zeroed | 18 | `(0,0,0)`: class abilities (Archer ult/ability, 7), Hyperion/leap teleports, a few unattributed. A teleport zeroes your velocity. | [M] |
| Small x-only push | 3 | `(0.25, 0, 0)` at about (70–73, 225, 72–76) with `generic.explode`/`wither.shoot`. This is P1 (Maxor's platform). | [M] cause [C]: Maxor's or a crystal's push |
| One-offs in the boss | 2 | `(-0.80, 0.4, 0.04)` at (19, 113, 132) and `(-0.05, 0.42, 0.50)` at (108, 170, 97), while holding Dungeonbreaker, no explosion | [M] cause unknown |
| Outside the boss (clear/blood) | about 9 | vy 0.5 with xz up to the 3.9 clamp, `generic.explode` + `player.hurt`: explosive mobs before the boss | [M] not boss |

**Not seen at all [M]:** no knockback from Superboom TNT (15 `v`s came while it was held, and every one
falls into the lava, Bonzo or Jerry classes above), from wither skulls (Hyperion/Necron), from Goldor, Storm or Necron hits, or from TNT. No
explosion packet ever pushes you, and none of the bosses' attacks produced a `v` on the recorder.
Spirit Leap / Infinileap is a teleport and zeroes velocity like any teleport.

## Scripts (`scripts/`, run with the nix python)

- `kb_scan.py`: loads a recorder file and lists the `v` on you, with self-owned spawns, sounds and explosions.
- `kb_join.py`: the join with Better PF held items. It writes one JSON line per `v` on you (`> /tmp/kb_events.jsonl`, about 10 min).
- `kb_proj.py`: tracks entities spawned within 2.5 of your eye (balloons, Jerries, skulls) tick by tick (`> /tmp/kb_proj.jsonl`, about 4 min).
- `kb_bonzo2.py /tmp/kb_events.jsonl STARRED_BONZO_STAFF BONZO_STAFF`: Bonzo timings, burst geometry and direction errors.
- `kb_bonzo_dist.py`: the distance and flight-time distribution from that output.
- `kb_bonzo.py`, `kb_jerry.py`: per-click lifetimes and click spacing from `kb_proj` output.
- `kb_jerry2.py`: Jerry direction and size against the Jerry stand.
- `kb_classify.py`: classifies all `v` on you.
- `kb_explosions.py`: every `ex` packet (radius, knockback).
