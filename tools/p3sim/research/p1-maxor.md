# P1 Maxor: what the client sees (props, stands, blocks, bar, flow)

Complements `docs/mechanics/maxor.md` (timings, laser rules, movement), which the sim already
follows. Source: Better PF runs (`/home/cam/backups/2026-10-03/analysis/p1maxor/*.mjs`; 489 runs
reach P1 and P2, 174 where the recorder carried a crystal, mostly 2026-09-29..10-04) and the
Boss Recorder's server packets (63 P1 fights). Ticks are after Maxor's first line (s0).
**M** measured, **C** conjecture.

## Stands (M)

| stand | text (§ exact) | where | when |
|---|---|---|---|
| boss name | `§e﴾ §r§8§r§5 §r§c§lMaxor§r §e﴿` (same for Storm/Goldor/Necron; Watcher has no ) | wither + 3.6875 | ~17 after the wither (s0 + 17-24) |
| speech | `§4§l<line>` for every `[BOSS]` line, all four bosses | wither + 4.09375, follows him | 1 tick after the line, gone 41-42 later; Maxor's first line comes with the name stand, gone ~48 |
| top crystal | `§bEnergy Crystal` (y 238.5) + `§e§lCLICK HERE` (y 238.125), invisible, not marker (flags 2) | (64.5 / 82.5, -, 50.5) | first pair ~23 after the crystals; respawned ones with them (hit + 41); gone with the crystal on pickup |
| pylon | `§cEnergy Crystal Missing` (y 223.5) + `§e§lCLICK HERE` (y 223.125) | (52.5 / 94.5, -, 41.5) | from the laser start (s0 + 166); `§aCrystal Active` + "" 3-22 after a placement; back to Missing at hit + 41; removed at the kill |

Hypixel blanks some names for far players (they show "" to the recorder until it comes near): not modelled.

## Crystals (M)

- Pickup puts the crystal (a nether star, SkyBlock id `MAXOR_ENERGY_CRYSTAL`) in the SkyBlock Menu
  slot (9) and **selects that slot**: 51 of 54 own pickups selected slot 9 0-2 ticks after the line.
  The menu comes back once placed. The item's display name is not recorded (sim: `§bEnergy Crystal`, C).
- Clicking a pylon without one: `§cYou do not current have an §r§bEnergy Crystal§r§c! Find one and bring it here!` (sic).
- Placement lines: `§c1§r§a/2 ...`; 2 and 3 are all green (`§a2/2`, `§a3/2`).
- After the kill the top crystals still respawn at hit + 41 and stay (seen in P3).

## Power lines (M, 8 carrier runs, every cycle)

- West: (56..64, 221, 41) then (64, 221, 42..51); east: (94, 221, 45..48), (93..82, 221, 48),
  (82, 221, 49..51). 19 coal blocks each → sea lantern from the placement line's tick, pylon end
  first, ~1 a tick; then that side's half of the ceiling T (68..72 / 78..74, 236, 64), outer end first;
  the cycle's first crystal then lights (73, 236, 64) and the column (73, 236, 65..69).
- All go dark from **hit + 40** in the same order (floor, halves, centre + column). The old
  `p1lamps` note's "beam line + 57/58" is this + the 19-block floor.

## Beam column (M)

- (73, 223-224, 73) colour on the checks as maxor.md says; (73, 222, 73) is air from 166 and joins
  the colour from **beacon + ~120** (322-331 in 8/8 runs, i.e. the s0 + 326 check).
- The `p1strip` recording also holds one run's column changes (its beacon/bedrock times): they must
  not be replayed (the sim excludes 73,221-224,73 from it).
- (73, 225-226, 73) glass/carpet changes follow neither the checks nor the hits clearly; left to the recording.

## Wither health / armour (M, Boss Recorder)

- Spawn health 1 (armoured), inv 200. At the stun line health → 1000 (armour off) and stays off
  for the whole stun, with occasional 1-3 tick flickers back to 1. At "enraged" → 1. Stun 2: 1000,
  then ~957-999 before death; 0 at **Storm − 43 = kill + 59**; removed at kill + 80.
- So the old "flips tick by tick while stunned" (bosses.md) is wrong: off, with rare flickers.

## Boss bar (M, 63 fights)

`§c§lMaxor`, resent every ~20 ticks. 1.00 until the first hit; **0.95** on the first resend after
a hit (also for a silent hit), falling while stunned (to 0.3-0.66 by the end); **0.25** on the
resend after "enraged" whatever it was; ~0.03-0.20 after the second hit; 0.00 by the kill; then
Storm's `§c§lStorm` 1.00. So the enrage is not a "bar reaches 0.25" threshold (C: the stun end
sets him to 25%).

## Players' flow (M, 6 carrier runs + party.md §6)

Start at (74, 221, 15). Berserk/Mage carry: top crystal at s0 + 50-105, then wait on the pylon
(51.5-52, 224, 40.5) / (96, 224, 40.5); after hit 1 back up to (62-64, 238-250, 49) / (84-86, 238-248,
48-50), pick up at hit + 40-50, place again at hit + 60-150. Tank lures from (73.6, 225, 77.2) from
~s0 + 60. Healer drops to Storm's floor early, Archer at ~+15 s. Pressure plates on the pylons
(52/94, 224, 41) go powered while someone stands on them (vanilla).

## Not modelled (known)

Wither Guards / Miners (wither skeletons with `§#§r§f §r§cWither Miner§r §a#§r§c❤` stands), Maxor's
skull volleys / Wither TNT / Frenzy damage, the "§c§l???" damage stands, the hidden arrow
dispensers under the floor at x67/70/76/79 (arrows every 40 ticks, invisible), blessings picked
up at ~s0 + 200 (not a P1 mechanic).
