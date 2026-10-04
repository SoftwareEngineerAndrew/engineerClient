# Teammates (the bots) and your own items: measured from the recordings

Sources: Better PF runs (`/home/cam/backups/2026-10-03/betterpf-r2/runs` + local), complete F7 runs
(`END` reached), mod 0.6+, newest first. Scripts in `/home/cam/backups/2026-10-03/analysis/party/`:
`measure.mjs` (40 runs), `move.mjs` / `tele.mjs` (30 runs a phase), `gear.mjs` / `gear-sets.mjs`
(120 runs, 57 distinct teammates), `leapers.mjs` (30 newest + 25 older runs), `where.mjs` (30 runs),
`grep.mjs` / `cloak.mjs` / `autopet.mjs` (60-80 runs), `walk.mjs` / `path.mjs` / `spots*.mjs`
(offline checks of the bots' movement on the sim's `arena.bin`).

Tags: **[M]** measured, **[C]** conjecture.

## 1. How teammates move

| Fact | Value | Tag |
|---|---|---|
| Ground speed, 1 s windows of steady movement | P1 0.63 / 0.99 / 1.39, P2 0.70 / 0.93 / 1.21, **P3 0.60 / 0.85 / 1.21**, P4 0.59 / 0.82 / 1.20 b/t (p10 / median / p90) | M |
| P3 distance | 31,520 blocks moved vs 9,712 by teleport (30 runs) | M |
| P3 teleports | almost all **leaps** (146 of a teammate onto another; most of the rest are leaps onto the recorder). Non-leap teleports with a Hyperion held, ~10 blocks: 3. No etherwarp (the AOTV isn't in the boss hotbar) | M |
| Vertical | rises of 1.03 / 1.64 b/t (p50 / p90) in P3: jumps, Bonzo / Jerry boosts, lava bounces | M |
| Crouching | 5-16 % of P3 by class | M |
| Walking paths on the arena | between almost every pair of P3 job spots there is **no walking path** (islands over lava, climbs of more than a block): players jump gaps and boost | M (path.mjs) |

Sim: bots walk on the blocks at 0.85 b/t (step/jump up to 1.25, fall with vanilla gravity, lava
is a wall) and use a 10-block Hyperion blink where walking can't go on (every 8 ticks), or
when late (every 4; every 2 once due). Before: a straight glide through walls at 0.95 b/t, up to
4 b/t when late. Stand spots all sit on a floor once the body's width (±0.3) is counted
(spots2.mjs; several terminal spots are on stair edges).

## 2. Leaps

| Fact | Value | Tag |
|---|---|---|
| Item before a teammate's leap | `INFINITE_SPIRIT_LEAP` 141 of 146 | M |
| Head out before the tp | 3 / 8 / 39 ticks (p10 / median / p90) newest runs; 4 / 19 / 173 older | M |
| 1 s after | Dungeonbreaker 49, still the leap 35, Hyperion 28, Bonzo 16 (of 146) | M |
| Party line | `§9Party §8> §b[MVP§c+§b] <IGN>§f: Leaped to <IGN>!` (mod announce; also `Leaped to X`, `Leaping to X`, `[Skyblocker] Leaped to X!`). 103 / 146 leaps announced (81 / 81 in older runs), **1 tick** after the tp (p10 -1, p90 4) | M |
| Your line | `§aYou have teleported to §r§b<IGN>§r§a!`: the name in the target's rank colour (`§b` MVP+, `§6` MVP++, `§7` none; VIP's green shows as the plain `§aYou have teleported to <IGN>!`) | M |
| Facing after a leap | the target's (see items-timing.md §1) | M |

## 3. Swings

| Action | Swing | Tag |
|---|---|---|
| Lever | on the line's tick (+1: 57, +5: 15, of 296 activations) | M |
| Device completion | sometimes (+1: 14) | M |
| Terminal | none in the 3 s before its line (141 of the terminals): the swing is the stand click that opened it, earlier | M |
| Held at activation | Dungeonbreaker (terminal 99, lever 61, device 29), leap 36, Bonzo 32 | M |

## 4. Gear (P3, one per teammate)

- Helmet: **Spirit Mask** (skin `9bbe721d…`) 36 of 57, **Bonzo's Mask** (`12716ecb…`) 11, others 10.
- Armour: dyed leather (chainmail chestplate sometimes). Each player's own dyes; most common
  Storm's blues: chest `#1793c4`, legs `#17a8c4`, boots `#8969c8` or `#1cd4e4`. Sets used per class
  in the sim: Healer 1793c4/17a8c4/8969c8, Mage 1793c4/17a8c4/1cd4e4, Tank chainmail/5d2fb9/8969c8,
  Archer e7413c/e75c3c/8969c8, Berserk 3e05af/5d23d1/7c44ec (Bonzo's Mask).
- Skins / nametags: each player's own; nametag colour/prefix is not recorded [—].

## 5. Held items by class and phase (% of the phase, measure.mjs)

| | P1 | P2 | P3 | P4 |
|---|---|---|---|---|
| Archer | Terminator 25, Hyperion 24, Bonzo 23 | Terminator 29, Hyperion 29 | Dungeonbreaker 37, Leap 22, Hyperion 18 | Hyperion 37 |
| Berserk | Dungeonbreaker 38, Hyperion 31 | Hyperion 36 | Dungeonbreaker 37, Leap 17, Bonzo 26 | Hyperion 37, Terminator 23 |
| Healer | Dungeonbreaker 69 | Dungeonbreaker 53 | Dungeonbreaker 67, Leap 16 | Dungeonbreaker 63 |
| Mage | Hyperion 30 | Hyperion 78 | Dungeonbreaker 61, Ragnarock 12 | Hyperion 80 |
| Tank | Hyperion 49 | Hyperion 37, Leap 20 | Dungeonbreaker 52, Leap 20 | Hyperion 46 |

## 6. Where teammates are outside P3 (where.mjs, 30 runs) — for P1/P2's owners

- **P1** at +5 s: Archer still at the start (~69, 221, 16); Berserk at the west crystal / pylon
  (52-66, 224-240, 40-50); Mage the east one (~88, 238, 46); Tank under Maxor's laser (~74, 225, 77);
  Healer already down at Storm (~96, 165, 41). At +15 s the Archer moves down to (33-40, 170, 86-95).
- **P2** at start: Archer (31, 170, 96) (Yellow pad), Tank (115, 170, 95) (Purple), Healer in P3's
  arena already (56, 114, 90-100: predev), Berserk / Mage moving about mid (60-110, 163-170, 40-100).
- **P4**: everyone drops in from the core over ~3 s (y 69-75 at +3 s) onto y 64 around
  (45-62, 104-120); the Mage often stands at (54, 64, 101). The sim lands the bots there.

## 7. Your items' messages (exact)

| Item | Line | Tag |
|---|---|---|
| Wither Cloak | `§dCreeper Veil §r§aActivated!` / `§dCreeper Veil §r§cDe-activated!` / `§cNot enough vitality! §r§dCreeper Veil §r§cDe-activated!` (the expired one follows the same colours: [C]) | M |
| Hyperion | `§7Your Implosion hit §r§c1 §r§7enemy for §r§c32,710,591.3 §r§7damage.` (30-43M a boss wither; `.0` dropped) + exp-orb ding 1/1.492 | M |
| Pet swap (rod cast, Autopet rule) | `§cAutopet §eequipped your §7[Lvl 100] §5Phoenix§e! §a§lVIEW RULE` / `… §6Black Cat§5 ✦§e! §a§lVIEW RULE`, 2-3 ticks after the rod comes out | M |
| Masks | as chat-attacks.md / items-timing.md §4 (the sim's lines match) | M |
| Etherwarp | enderman.teleport 1/1 + ender_dragon.hurt 1/0.54 on the tp tick | M (items-timing.md) |
