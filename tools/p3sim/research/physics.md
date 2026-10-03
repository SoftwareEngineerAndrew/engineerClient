# F7 boss player physics: lava, falls, movement, health, death

Sources: the 77 Boss Recorder files (`bossrecorder/*.jsonl.gz`; recorder `johnswizzlechang` in most,
a different account in the 2026-09-30 00:22–02:31 files), the built arena (`arena.bin`). Scripts in
`research/scripts/`. Pipeline:

```
python3 scripts/selfevents.py /tmp/self.jsonl bossrecorder/*.jsonl.gz   # own-player stream, flat
node scripts/lava.mjs /tmp/lava.json                                   # lava cells of arena.bin
python3 scripts/bounce_stats.py  /tmp/self.jsonl /tmp/lava.json
python3 scripts/bounce_hp.py | bounce_detail.py | bounce_traj.py | movement.py | deaths.py |
        hp_phases.py | goldor_kill.py | falls.py | hpscale.py   /tmp/self.jsonl
python3 scripts/bounce_sounds.py bossrecorder/*.jsonl.gz
```

**M** = measured from the data, **C** = conjecture.

## 1. Lava bounce

| | value | |
|---|---|---|
| count | 579 bounces in 77 files: P3 y106 lava 356, P4 lava 190, P2 27, other 6 | M |
| packet | `ClientboundSetEntityMotion` on yourself, **vx = vz = 0 exactly** (541/579; the 38 others are bounces merged with some other knockback) | M |
| vy | **2.25** (532) or **3.038** (47; 45 of them in the P3 y106 lava, spread over 27 files). **3.038 = looking up**: all 33 clean ones had pitch −37…−90 at the bounce, the 470 normal ones nearly all above −45 (threshold ≈ −43°). It also looked like "longer falls" only because you look up after a bounce | M |
| trigger | your box in lava; you usually have already **landed on the lava's floor** (P3 lava is 1 block deep: feet y=106.000 onGround=true on the tick(s) before, 497/579 "slow/ground"); falling through deep P4 lava (y55–58) is bounced while still sinking | M |
| delay | first tick your sent position overlaps a lava cell's fluid → packet: 0–1 t: 57, **2 t: 157, 3: 130, 4: 98, 5: 42, 6: 26**, 7–15: ~70 (approach ticks where the model counts an edge touch). Mode 2–3 server ticks; reads like a server check every few ticks (C: ~every 2–5 ticks, not on the contact tick) | M (distribution), C (mechanism) |
| repeat | stay in → bounced again every time you come back down: consecutive gaps 4–12 t (25 at 12) when re-touching immediately, ~38–52 t for a full up-and-down. Never a second packet while airborne | M |
| trajectory | median rise per tick after a 2.25 bounce: 1.775, 3.436, 4.985, 6.426, 7.758 … apex **+16.29 at tick 19**, back down by ~tick 38. 3.038: 2.405, 4.684 … apex +27.43 at tick 24. First move = vy·0.8 − 0.025 (one lava tick of fluid drag), then normal air physics (v−0.08)·0.98 | M |
| horizontal after | median horizontal speed 0.10 b/t before → 0.03 after (the packet zeroes it; you only get air control back) | M |
| damage | **hp unchanged** (hp delta 0.0 in 472/476 bounces with hp samples), yet `entity.player.hurt` plays at 430/579 bounces and bone plating procs ("Your bone plating reduced the damage you took by N!") at 115/579 — Hypixel registers a hit (small, absorbed/regenerated or fully reduced) | M |
| sound | `minecraft:entity.player.hurt` (430/579, 0–3 ticks after), `entity.item.break` (114, = bone plating) | M |
| fire | on-fire flag (entity data 0, bit 0x01) set at the bounce tick (0 t: 89, +2 t: 77; earlier ones from the contact ticks), lasts **100–163 ticks** (most 100–125) | M |
| chat | none of its own | M |

Sim: send `setDeltaMovement(0, 2.25, 0)` + `hurtMarked` 2–3 ticks after the player's box enters lava (or
when onGround inside lava), and the client's own physics reproduces the trajectory above; play
`entity.player.hurt`, set fire ~110 ticks, no hp change.

### Where lava is (arena.bin; lava fluid top = cell y + 8/9 for a source with no lava above)

| phase | cells | extent | surface |
|---|---|---|---|
| P1 (Maxor) | y214 (110 cells) + y219–220 + lavafall columns y215–249 | x40–106, z29–85 | ~214.9 |
| P2 (Storm) floor | y162 (3240 cells), lavafall columns y163–191 (~56/layer), y192–194 (392/layer) | x18–126, z0–136 | 162.9 |
| P3 (Goldor) under the sections | **y106, 5345 cells, 1 block deep** (floor at y105) | x −2–111, z30–142 | **106.889** |
| P3 lavafall columns | y107–143, 4–30 cells a layer | x41–109, z31–95 | |
| P4 (Necron) | **y55–58, ~12,700 cells a layer, 4 deep** | x −2–110, z20–132 | 58.889; rises into ~1.5k more cells around the platform during P4 (arena.md) |

## 2. Falls and the void

- **No fall damage** (M): 1,240 landings after falls ≥ 4 blocks (127 ≥ 40 blocks); hp dropped within 5
  ticks on only 18, all explained by other hits.
- In the boss nobody's y went below 51.7 (M): every y < 52 sample is in the clear/blood camp. Under the
  P4 platform is the y55–58 lava, which bounces you; under P3 sections the y106 lava. There is no
  void to fall into anywhere in the boss (C: falling out is impossible, the lava catches you).

## 3. Movement and health

- Ground sprint speed (flat ground, consecutive sent moves): **1.38–1.40 b/t** 95th percentile in all
  johnswizzlechang files, top bucket 1.40 (M) = vanilla sprint at Skyblock speed ~490–500 (model
  0.13·S/100/(1−0.546): 500 → 1.432). The other account's files: 1.73–1.81 (higher speed). Sim's default
  500 → 1.40 matches.
- Jump: first tick +0.42, rise **1.252** (1,271 of ~1,500 jumps) — vanilla jump, no jump boost (M).
- No slowness seen; knockback none apart from the bounce and explosions (C for Necron).
- Health: vanilla hp is **scaled to 40** (20 hearts): 58,035 of 64,776 hp samples are exactly 40.0;
  food always 20 (M). hp = 40 · current / max (C, consistent with Hypixel's health scaling).
- Damage seen per phase (hp drops, M): P1 65 (Maxor's Frenzy, Wither TNT, crypt skulls); P2 356, median
  0.67/40 (Lightning Fireball ~4–9/40, Static Field ~0.8/40, Giga Lightning); **P3 9, P4 1** — for this
  player Goldor/Necron did essentially no visible damage.
- Regeneration (P2): +0.76–0.91 hp every **20 ticks** (≈2% max/s), plus heals (Healer circle, Wish) (M).
- Hypixel's chat damage numbers vs the bar don't give a consistent max HP (implied 7k–3M): the numbers
  are pre-defense / absorbed by Wish shields (M that it's inconsistent; C the reason). Traps take exactly
  4.0 or 6.0 / 40 (10% / 15% of max).

### Death
- Chat: `" ☠ You died and became a ghost."` (no killer named: Goldor's kill) or
  `" ☠ You were killed by <mob> and became a ghost."` (M).
- **P3 deaths (7 own) were instant at full 40 hp** — no hp drop before. Each fell on a
  `[BOSS] Goldor: What do you think you are doing there!` line (+2/+3 ticks). That line appears 72 times
  in P3, every ~65–70 ticks (e.g. P3+138, 207, 276, 342), first at about P3+255–270 in most runs; 12 of
  them coincide with someone's death. Own positions at death: (60.7,132,139), (38.8,109,140.8),
  (2.6,109,80.6), (1.9,109,103.5), (45.4,108.5,139), (0.7,109,78.1), (2.8,108.5,81.8) — the low ledges at
  y108.5–109 along the arena walls just above the y106 lava (M). C: Goldor periodically (~3.4 s) kills
  players standing in forbidden spots (below the section floors / out of bounds); the same spot did not
  always kill on the first line.
- What follows (M): +3..+6 ticks: velocity (0,0,0), entity data flag 0 gains **0x20 invisible**
  (64→96/97), index 17 (absorption) → 0, hp packet stays 40 with saturation 0. You keep moving (ghost;
  game mode not recorded — C: Hypixel ghosts are spectator-like, flying, invisible).
- Revive: `" ❣ <you> was revived by <player>!"` (from ~130–170 ticks later) or
  `"Your Revive Stone revived you and broke!"` (same tick as the death line). Then hp packet with
  saturation 20, +5 t velocity 0 and the invisible bit cleared, absorption 16 (M).
- Masks/Phoenix: "Spirit/Bonzo/Phoenix Procced!" party lines from others around Goldor kills — they do
  proc on these hits for others (M), so the Masks.kt model (masks save from a kill hit) is consistent.

## 4. Health bar scale

Yes: always 40 max (20 hearts), whatever Skyblock max HP; displayed damage numbers are not
proportional to bar loss (see above). For the sim: keep vanilla hp at 40 and express damage as a
fraction of 40 (P2 fireball ~6–9, static field ~0.8, traps 4–6), regen ~0.84 per second.
