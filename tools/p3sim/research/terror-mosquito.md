# Terror armor (Hydra Strike) and the Mosquito Shortbow

An exact model of both items for P3 Sim. It covers every arrow a shot makes, tick by tick, plus the Hydra Strike
stacks, the clicks and cooldowns, Nasty Bite, and the sounds. Healing (Nasty Bite's heal, vitality regen) is left out
on purpose.

**Data:** Dungeon Recorder recordings from 2026-10-03 (p3wr):
- 14 F7 runs with Mosquito shots: 863 shots. 851 of them have the full first-two-tick packets.
- 16 recordings with Terror worn.
- 5,236 Terminator volleys and 22 Juju volleys, for Hydra Strike on other bows.

**Ticks:** every tick count is a server tick. That is the recorder's `n` (top-level pings), which matched the
server's `gameTime` to within 8 ticks in 5,640.

**Tags:**
- **[M n]**: measured, with n the sample size.
- **[L]**: Hypixel's lore.
- **[I]**: inferred from the measurements.

Scripts are in `scripts/terror/` (§11).

## 0. Notation

- **N:** the server tick that handles the click.
- **g:** `(0, 0.05, 0)`. Arrow physics are vanilla: each tick `pos += v`, then `v = 0.99·v − g`.
- **yaw/pitch:** the rotation the server has. For a right click that's the `use_item` packet's own rotation; for a left
  click it's the last rotation sent.
- **look(yaw, pitch):** `(−sin yaw·cos pitch, −sin pitch, cos yaw·cos pitch)`.
- **roty(v, a):** v turned a degrees of yaw about the vertical axis, with y kept.
- **"No owner":** the spawn packet's owner field is the arrow's own id. In 1.8 that's what an arrow with no shooter sends.

## 1. The items [L]

**Mosquito Shortbow** (`MOSQUITO_BOW`, `minecraft:bow`, gold name `Mosquito Shortbow`, LEGENDARY BOW, glint):
- `Shot Cooldown: 0.5s`
- **Duplex I:** "Shoot an extra arrow dealing 4% of the first arrow's damage. Targets hit take 1.1x fire damage for 60s."
- **Flame II**
- **Eggsecute**
- **Ability: Nasty Bite (LEFT CLICK):** "Shoot an enhanced shot, healing you for N❤ Health on hit. Vitality Cost: 10"
- "Shortbow: Instantly shoots!"

**Terror armor, Tiered Bonus: Hydra Strike (n/4):** "Every 0.2s, arrow attacks grant 1 stack of ⁑ Hydra Strike. Lose 1 stack
after Ts of not gaining a stack. Each stack grants +D% Damage and +1% Arrow Speed. At 10 stacks shoot +2 arrows that deal
20% Arrow Damage."

| Pieces | T (lore) | D | Loss spacing measured |
|---|---|---|---|
| 0/4 | 4 s | 2% | not measured (predicted 100 ticks, §5) |
| 3/4 | 7 s | 4% | **160 ticks** [M 114] |
| 4/4 | 10 s | 6% | **220 ticks** [M 3] |

The action bar shows the stacks as `§6N⁑` (bold at 10). It is sent every **10 ticks** (±1) [M 546].

## 2. The main arrow (every bow: Mosquito, Juju, and the Terminator's middle arrow)

```
E   = feet + (0, eye − 0.1, 0) + 0.01745·(−cos yaw, 0, −sin yaw)      eye = 1.62 standing, 1.27 crouched
u   = 3.0 · (look(yaw, pitch) + 0.0075·(G1, G2, G3))                   G = independent standard gaussians
tick N:    the arrow starts at E with velocity u and moves:  pos1 = E + u,   v0 = 0.99·u − g
tick N+1:  its velocity becomes k·v0 (k = 1 + 0.01·stacks), then it moves:  pos2 = pos1 + k·v0,  v1 = 0.99·k·v0 − g
then:      vanilla flight, no further packets until it's removed
```

Evidence:
- **The speed-up is exact.** v1 = 0.99·k·v0 − g with an integer stack count: max component error has a median of
  0.00013, which is the packets' 1/8000 velocity rounding [M 851].
- **k is the stack count.** k − 1 equals 0.01 × the action-bar stacks in 726/851 shots. In 119 it's +1/+2, a gain
  between two bars.
- **Packets on Hypixel:** the spawn packet carries `floor32(pos1)` (2,548 of 2,553 components) and v0. On tick N+1 come
  a velocity packet with v1 and an *unrounded* position packet (`entity_position_sync`) with pos2. That exact position
  is what makes the rest of this doc measurable.
- **Height:** E is 1.52 above the position the client last sent when standing (447/448) and 1.17 when crouched
  (322/322) [M]. So Hypixel uses the modern 1.27 crouch eye, not 1.8's 1.54.
- **Sideways:** E sits **0.01742 to your right** [M 546; p10–p90 0.01716–0.01767], turning with your yaw, with nothing
  forward (median 0.00015). That's π/180 within error. Vanilla 1.8 puts 0.16 there, and modern Minecraft puts nothing.
- **|u|:** median 2.99904 (p5 2.961, p95 3.034) [M 851].
- **Aim noise:** on 135 steady-aim shots, `u/3 − look` has sd 0.0074 / 0.0066 / 0.0072 (x/y/z) and kurtosis 2.7–2.8.
  Four components fall beyond 0.01723, the hard limit of modern Minecraft's triangle noise. So it's 1.8's
  `nextGaussian()·0.0075` per component on the unit look vector, then ×3 [M 135].
- **Clicks attributed to the wrong rotation:** a shot that arrives 0–1 ticks after a click came from an earlier click.
  Shots arrive about 1 round trip after their click (§6).

## 3. Hydra Strike arrows (10 stacks, any bow) [M 1,120 = 560 shots]

```
tick N:    two arrows with no owner appear at pos1, still
tick N+1:  launched with roty(k·v0, +8°) and roty(k·v0, −8°), the main's tick-N+1 move turned:
           pos = pos1 + roty(k·v0, ±8°),  v = roty(v1, ±8°)
```

- **Exact match:** velocity error median 0.00012 and position error median 0.000016. The split is 560/560 at
  +8/−8, and all 1,120 launch on the main's relaunch tick.
- **Stacks:** every one came with a 10-stack shot. Only 1 of 561 shots at 10 stacks lacks them, and no shot at 0–9
  stacks has them.
- **Lookalikes:** a few no-owner arrows that look like Hydra at <10 stacks point along the main (0°). They're the
  previous shot's Duplex released from nearly the same point.
- **Other bows:** the same on the Juju and the Terminator. On the Terminator the ±8° is about the **middle (noisy)**
  arrow (§8).
- **Silent:** one loud shoot sound per shot at 10 stacks and below alike [M 271 / 217].

## 4. Duplex (Mosquito only, every shot including Nasty Bite) [M 1,689 arrows]

```
two arrows with no owner appear at pos1, still, 3–5 ticks after the main;
both launch on the same tick, d ticks after the main's tick N+1 (d = 3: 29%, d = 4: 71% [M 844]),
from pos1 with velocity k·v0, so after that tick they are at pos2 with v1, bit-identical to the main's packets
```

- **Same path, delayed:** they replay the main's tick-N+1 move exactly (all 1,689: position and velocity error 0).
  So they fly the main's path 3–4 ticks behind it.
- **Count:** 844/851 shots have exactly two, 1 has one, and 6 have none.
- **Two for one:** the lore says one extra arrow, but Hypixel sends two identical entities. The Terminator's side
  arrows also come in identical pairs (§8). Hydra arrows don't.
- **Release timing:** what decides 3 vs 4 isn't visible, and it doesn't depend on the click type or the stacks.

## 5. Hydra Strike stacks

**Gain [L + M]:** +1 stack when one of your arrows **damages a mob**, at most once per 0.2 s (4 ticks).
- **On a mob:** while hitting Storm, consecutive shots 5 ticks apart gain +1 (113) and sometimes +2 within one gap
  (13) [M].
- **Blocks don't count:** in P3, 30–40 shots at the i4 targets never refresh the stacks [M 4 runs].
- **Clearing:** no gains at all [M 128], with no Terror worn then.
- **At 10 stacks a hit still refreshes the timer.** With 4 pieces, 10 stacks were held up to 350 ticks while hitting.
  Each hold ended 216–228 ticks after the last shot [M 13].

**Loss [M + I]:** a server task runs **once a second on a fixed grid**.
- **The grid:** in every recording all losses fall on one phase mod 20, while gains fall on both 10-tick phases
  [M 14/14 recordings]. In one, a chain shifts once.
- **The rule:** each run counts one more second without a gain. On the run where the count exceeds T (4/7/10 s), lose
  1 stack and restart the count.
- **Spacing:** losses come every (T+1)·20 ticks: 160 at 3/4 [M 114: 159/160/161 = bar jitter, plus 7 one bar off]
  and 220 at 4/4 [M 3]. That predicts 100 at 0/4.
- **First loss after the last gain:** (T·20, (T+1)·20] ticks later [I]. The bar can't show it any closer, because a
  10-stack refresh is invisible.

**Speed:** k = 1 + 0.01·stacks multiplies the arrow's velocity on tick N+1 (§2), on every bow. The Hydra arrows
(stacks = 10) and k always agreed [M 1,120], so both read the stacks at the shot.

## 6. Clicks, cooldowns, Nasty Bite

| | Right click | Left click |
|---|---|---|
| Fires | a normal shot | **Nasty Bite**: the same shot (same arrows, Hydra and Duplex), −10 vitality |
| Cooldown | **5 ticks** with Terror's attack speed (7 without) | **10 ticks**, its own |
| Cooldown packet | `minecraft:bow` 5 (7) | `minecraft:bow` 10 |

Evidence:
- **Kinds:** 654 normal shots at 5 ticks, 21 at 7 (no Terror), 186 Nasty Bites [M].
- **Left click only gives Nasty Bite:** every Nasty Bite had a swing first (186/186). No normal shot ever came from a
  left click alone (0 of 675).
- **Spacing:** normal-to-normal gaps are 4/5/6 (136/310/155; ±1 is arrival jitter). Nasty Bite-to-Nasty Bite gaps
  peak at 10–13.
- **The cooldowns are separate:** a Nasty Bite and a normal shot can fire **on the same tick**, in either order
  (22 + 16), and either can follow the other 0–5 ticks later.
- **A click inside the cooldown fires when it ends.** For normal shots exactly 5 after the last, the last right click
  was 2–6 ticks before it in 199 of 306. That's longer than the 0–1 tick round trip of a fresh shot [M 34], so the
  click waited out the cooldown. The server sends `cooldown 0` and fires on that tick.
- **Vitality:** a Nasty Bite costs exactly 10. Bar pairs show −10 (82) or −3, which is −10 plus a +7 regen bar (71).
  Regen is +6/+7 per 10 ticks [M]. Below 10 vitality was never seen, so what a left click does there isn't measured.
- **Out of scope:** Nasty Bite's heart/dripping-lava particles and the heal on hit.

## 7. Sound and look [M]

- **Shot:** `entity.arrow.shoot`, volume 1.0, pitch vanilla `1/(rand·0.4 + 1.2) + 0.5` (seen 1.13–1.24) [M 17 isolated].
  One per shot; Hydra arrows are silent.
- **Duplex release:** two `entity.arrow.shoot` at **volume 0.5, pitch 0.698** (0.7 on Hypixel's 1/63 pitch steps),
  one per arrow [M 34].
- **Hits:** a block hit is the vanilla `entity.arrow.hit`. A mob hit plays `entity.arrow.hit_player` at pitch 0.794
  to you.
- **Entity data:** identical for main, Hydra and Duplex arrows. Not crit, **not on fire** (Flame II doesn't show), no
  pierce, colour −1 [M 850 / 1,120 / 1,689].

## 8. Other bows: Hydra Strike on them, and the Terminator

**Hydra and k apply to every bow:** the Juju at 10 stacks fires main + 2 at ±8.0° [M 10], and the Terminator at 10
stacks adds the same 2 [M 7].

**The Terminator** [M 4,623 shots; `term_exact.py`]:

```
the main arrow of §2 (owned by you, from E, with the aim noise, speed-up k on N+1)
tick N:    four arrows with no owner appear at pos1 − (0, 0.5, 0), still
tick N+1:  they launch as two identical pairs with  w = roty(|k·v0| · look, ±5.5°) + (0, 0.05, 0)
           (your clean look, no noise; the main arrow's speed; the tick's gravity given back):  pos = start + w,  v = 0.99·w − g
```

- **Exact:** w matches to a median error of 0.000078 on main (504 arrows) and alpha (650) alike.
- **Start point:** exactly 0.5 below pos1. 4,161 shots have 4 side arrows in 2 identical pairs.
- **Angles:** measured against the noisy middle arrow they look asymmetric (−5.07/+5.93, −6.31/+4.69…) but are always
  11.0° apart.
- **Sound:** one `entity.arrow.shoot` per volley, as in §7.
- **Clicks:** it fires on a left click too (1,086 of 5,236 volleys came from a swing).
- **Alpha only:** Terminator shots on **alpha** (01:00–04:12 and 18:57–19:27 runs) often carried Duplex-like
  copies (2 identical replays of the main arrow's tick-2 move, 3–4 ticks later). The rate varies by run, from 0 to
  100%. No main-server Terminator shot has them (0 of 1,053 across 30 runs; alpha: 2,174 of 3,570), and this Terminator has no Duplex.
  The sim follows main.

## 9. The sim (`p3sim/Bows.kt`)

- **`ShotPlan.plan`** builds every arrow of a shot from §2–4 and §8. `ShotPlanTest` checks it against three
  recorded main-server shots: a Mosquito at 10 stacks on the i4 plate, a crouched Mosquito, and a Terminator. One
  vanilla tick from each planned arrow lands on Hypixel's exact position and velocity packets, to within 0.001 /
  0.0003.
- **Spawning:** arrows are spawned at the end of a tick in the state whose next vanilla tick is Hypixel's (26.1's
  arrow tick is the same move → ×0.99 → −0.05). The main arrow's first move, from E to pos1, is checked for a block
  or boss hit when it's spawned, since no vanilla tick sees it.
- **`SimArrow`:** a vanilla arrow that hits only the boss withers (1.8's 0.3 margin, no bounce) and is removed on its
  first hit, as Hypixel's are (only 5 of 851 Mosquito arrows ever showed the in-ground flag).
- **i4:** every arrow (main, Hydra, Duplex, Terminator side, Explosive Shot) counts.
- **Clicks:** right click shoots (left click too), on one Shortbow Cooldown (default 5). The Mosquito's left click
  is Nasty Bite, on its own 10. A click inside a cooldown fires the tick it ends, aimed as the server then has you.
  Cooldown packets come through vanilla item cooldowns (`minecraft:bow`, then 0 at the end), as Hypixel's do.
- **Stacks:** Terror Armor (Off / 3 / 4 pieces; the helmet slot stays your mask) and Hydra Stacks At Start (each
  menu start; going on from one phase to the next keeps them). A boss hit gains a stack. The loss is the
  once-a-second count of §5. The action bar shows `§6N⁑` (bold at 10) every 10 ticks.
- **Duplicates:** Hypixel's duplicated arrows (Duplex ×2, Terminator side pairs) are spawned once each, since they're
  identical. The Duplex still makes both sounds.

## 10. Open

- **i4:** counts every arrow on Hypixel (p3wr), so the sim counts them all. Whether no-owner arrows' mob hits grant
  stacks isn't measured; in the sim every arrow's boss hit does.
- **Low vitality:** a left click with under 10 vitality isn't in the data; Hypixel probably just denies it. The sim
  doesn't model vitality, so Nasty Bite never runs out.
- **Duplex delay:** what decides 3 vs 4 ticks. The sim draws it at 29% / 71%.
- **0/4 decay:** not seen; 100 ticks is predicted.
- **Goldor and Necron:** hits on them grant stacks in the sim, since every boss wither counts. Not measured on
  Hypixel.

## 11. Scripts (`scripts/terror/`)

Set `TERROR_OUT` for the output folder (default `scripts/terror/out/`, git-ignored). Set `TERROR_RECORDINGS` for the
recordings folder.

| Script | Does |
|---|---|
| `volleys.py` | every bow shot of yours, arrow by arrow → `volleys.jsonl` (run first) |
| `trajectories.py` | per shot of `$TERROR_BOW` (default the Mosquito): every arrow −1..+8 ticks / 3 blocks around it with all its packets, plus the server's view of you (position, rotation, recent clicks and rotations) → `trajectories.jsonl` |
| `stacktl.py` | per recording: stack changes, shots with their k, arrow removals, boss lines → `stacktl.jsonl` |
| `cd.py` | per recording: Mosquito clicks, cooldown packets, shots, vitality, sounds, Nasty Bite particles, in packet order → `cd.jsonl` |
| `exact.py` | §2–4: the speed-up, Hydra and Duplex checks |
| `aim.py` | §2: launch point and aim noise |
| `stacks.py` | §5: gains by phase, loss grid, spacing, 10-stack holds |
| `clicks.py` | §6: shot kinds, gaps, buffered clicks, vitality |
| `effects.py` | §7: sounds, entity data |
| `terminator.py` | §8: Terminator / Juju volleys, with and without 10 stacks (reads the recordings) |
| `term_exact.py` | §8: the Terminator's main and side arrows, exactly (`TERROR_BOW=TERMINATOR python3 trajectories.py` first) |
| `tlib.py` | shared paths and helpers |
