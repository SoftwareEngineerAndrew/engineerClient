# F7 bosses: what the client is actually sent

Ground truth for how Maxor, Storm, Goldor and Necron (plus their props) should look in the P3 Sim.
Source: the Boss Recorder's server packets (77 `bossrecorder/*.jsonl.gz`, ~60 complete F7 boss
fights) and Better PF client recordings (`bpf/runs/*.gz`). **M** = measured, **C** = conjecture.

Scripts (`tools/p3sim/research/scripts/`, run with the nix python3):

| script | what |
|---|---|
| `boss_withers.py FILE` | one fight's timeline: phase chat, wither add/remove + synced data, named stands, boss bar |
| `boss_detail.py FILE` | per wither: health runs, targets, head-vs-body yaw; `﴾ X ﴿` stand data and offset |
| `boss_withers_all.py DIR` | every wither in every file: phase, inv ticks, flags, health per phase, path length |
| `boss_giants.py FILE [type]` | giants (or any type) in the boss: spawn/remove, data, movement |
| `boss_bars.py FILE...` | boss bar name/progress over the fight |
| `bpf_boss_names.py RUN...` | Better PF: names (with colour codes if any) of boss props; wither/giant spawns |

Vanilla 26.1 synced-data indices used below (javap): Entity 0 flags (0x20 invisible), 2 custom name,
3 name visible, 4 silent, 5 no gravity; LivingEntity 9 health; ArmorStand 15 client flags (16 marker);
WitherBoss 16/17/18 targets A/B/C, **19 invulnerable ticks**.

## How a vanilla client draws a wither (javap of `WitherBossRenderer`, `WitherBoss`)

- **Scale** = `2.0 - invulnerableTicks / 220 * 0.5` when inv > 0, else 2.0. The client never counts
  inv down (that is `customServerAiStep`, server only), so whatever the server syncs is permanent.
- **Texture** = `wither_invulnerable` when inv > 0 and (inv > 80 or (inv / 5) % 2 == 1), else normal.
- **Blue armour layer** (`isPowered`) = `health <= maxHealth / 2`. Hypixel drives this with fake
  health values: **1.0 = armour on**, **1000 / 300 / 300000 = armour off** (with the vanilla max
  health 300; the recorder does not capture attribute packets, so the max is **C**, but 1.0 is
  armoured for any max >= 2 and 300000 unarmoured for any max <= 600000).

## The withers themselves (M, all fights)

All four bosses are plain `minecraft:wither`: **visible** (flags 0 in every one of ~240 boss withers),
**no custom name** (index 2 never sent, name visible false), no-gravity false, not silent. Every
wither is spawned with health 300 / inv per row below, yaw = headYaw.

| boss | spawn | inv ticks [19] | => scale / texture | health (=> armour) |
|---|---|---|---|---|
| Maxor | (73, 226, 53), yaw 0, 3-4 ticks after `[BOSS] Maxor: WELL! WELL! WELL!` (55/55 fights) | **200** | **1.545x**, **invulnerable (white/blue-flash) texture the whole fight** | 1.0 (armoured); flips 1.0<->1000 tick by tick while stunned by the laser ("YOU TRICKED ME!" -> unarmoured windows while hit), ~955-966 just before death, 0.0 at death |
| Storm | P2, ~30 ticks after `[BOSS] Storm: Pathetic Maxor` (e.g. 94.7,185.2,61.2) | **1** | 1.998x, normal texture | 1.0 armoured; **1000 (armour off) from each "Ouch, that hurt!" pillar crush** until he is back up (e.g. 6507 -> 1.0 at 6553 -> 1000 at 6651 second crush), 0.0 at death |
| Goldor | P3 start, on his track (e.g. 99.6,119,93.3; ids: wither, then 4 giants, then name stand) | **0** | 2.0x, normal texture | **1000 on the track (no armour)**, sometimes 300000; still 1000 in the core, flipping to 1.0 for single ticks on hits (no lasting armour; goldor-flow.md §3); removed ~290 ticks after Necron's first line |
| Necron | (54, 66, 76) yaw 0, ~0-3 ticks before `[BOSS] Necron: You went further...` (in P3 timing, while Goldor is at the core) | **1** | 1.998x, normal texture | 300 at spawn, 300000 (one fight 1200000) for 1 tick, then **1.0 (armoured) for all of P4**, 0.0 at death (~20 ticks before removal) |

So the sim's `w.invulnerableTicks = 0` is wrong for Maxor (should be 200, giving the smaller, pale
"invulnerable" Maxor) and right-ish for the others (1 vs 0 is invisible). Armour must come from
health: set health 1.0 for Maxor/Storm/Necron (and to >150 during Maxor stuns / Storm crushes);
Goldor unarmoured on the track. (In singleplayer, `setNoAi(true)` stops `customServerAiStep`, so
inv 200 will not tick down and explode - C, from `Mob.isImmobile` with noAi.)

**Other boss-time withers (M, not the bosses):** a wither with **inv 800, health 300** (=> scale 0.18,
invulnerable texture: a tiny pale wither) appears briefly in ~90 fights: in the Watcher camp, near
the core in P3 (56-59, 115, 56), and around the P4 floor; it moves 20-330 blocks and spawns within
~4-60 blocks of the recorder. **C:** a player's cosmetic/ability, not part of the fight - ignore.

Removal/re-add: Storm is removed and re-added 2-7 times and Goldor 1-4 times per fight; they enter
and leave the recorder's tracking range (~45-60 blocks). **C:** server side they exist throughout -
the sim should keep them.

Head yaw: `h` packets equal the body yaw (diff 0 in nearly every sample); the head turns with the
body. Targets [16-18] are set occasionally to player ids (Maxor/Storm/Goldor), which makes the
side heads look at that player client-side; mostly 0.

## Name tags (M)

The visible boss name is **not** on the wither. It is a separate `minecraft:armor_stand`, **not a
passenger** (no `pas` packet ever links it), moved alongside the wither **~3.7 blocks above its feet**
(Maxor (0, 3.70, 0); Storm ~3.4-3.7; Necron (0, 3.70, 0)):

- Text, exactly: `﴾  Maxor ﴿`, `﴾  Storm ﴿`, `﴾  Goldor ﴿`,
  `﴾  Necron ﴿` (Watcher: `﴾  The Watcher ﴿`). `﴾ ﴿` are U+FD3E/U+FD3F;
  ` ` are Hypixel resource-pack glyphs (icons; boxes without their pack).
  **No health in the name and it never changes** over the fight.
- Colour: neither recording keeps one (`Component.string` drops styles; Better PF's `c` field is absent
  for these names, so no legacy `§` codes) - **C:** the colour is in component styling not captured.
- Stand data: flags 32 (invisible), [15] = 16 (marker), name visible true, silent true, health 20.
- Spawn: Maxor's 30 ticks after his wither, Storm's ~23, Necron's 1; Goldor's id is wither+5
  (spawned with him) and is only seen when in range. Removed with/just after the wither.

Dialogue: each `[BOSS] X: ...` chat line also spawns a named armor stand with the line's text
(e.g. `WELL! WELL! WELL! LOOK WHO'S HERE!`, `You can't damage me, you can barely slow me down!`).
Damage numbers are stands named `36,470,429`, `✧24,810,205✧`, `???`, etc.

## Boss bar (M)

One bar (same UUID from the Watcher onward; its `add` happens before the recording starts, so its
**colour/overlay are unknown**, C). The server resends name + progress about **once a second** (20 ticks).

| phase | name | progress |
|---|---|---|
| P1 | `§c§lMaxor` (set 3 ticks before his first line) | 1.0; drops in steps after each stun (e.g. 0.87 / 0.70 -> 0.25 -> 0.02 -> 0.0) |
| P2 | `§c§lStorm` (at/after his first line) | 1.0; drops at each crush (0.95/0.55 -> 0.45 -> 0.0) |
| P3 | `§c§lGoldor` (with his first line) | **1.0 the whole track** and until he leaves for the core; then down in ~20-tick steps to 0.0 at the kill (goldor-flow.md §3) |
| P4 | `§c§lNecron` (at/after his first line) | **0.0 at first**, then ~0.8-0.84 when the fight starts (~"That's a very impressive trick"), 0.25, 0.08/0.05, 0.0 |

## Props

- **Goldor's giants (M):** `minecraft:giant`, **invisible** (flags 32), health 100, yaw 0, no name.
  Four spawn with Goldor (ids right after his) at the S4/S1 corner around (81-87, 111, 33-40); more
  spawn at the other corners (104,111,42-72; 88-104,111,124-130; 9-17,110,36-122) and walk the track,
  often rising to y ~119; some die (health 0). Equipment isn't recorded; per `docs/mechanics/goldor.md`
  they hold **golden swords** ("Goldor's Greatsword", 120k) - so what players see is a **giant floating
  golden sword**, not a giant. The sim's visible Giant as "Goldor" is wrong: Goldor is the visible
  wither above.
- **Maxor crystals:** stands `Energy Crystal` + `CLICK HERE` (pickup), at the pads
  `Energy Crystal Missing` + `CLICK HERE` -> `Crystal Active` once placed.
- **P3:** terminals `Inactive Terminal` + `CLICK HERE` -> `Terminal Active`; levers `Not Activated`
  -> `Activated`; devices `Inactive` / `Device` -> `Device` / `Active` (two stands, top line first);
  dead players `[MVP+] name` / `DEAD`.
- Minions (P1): ` Wither Guard 3.5M❤`, ` Wither Miner 10M❤` (health in the
  name, e.g. `3.2M❤`, `0❤`).
