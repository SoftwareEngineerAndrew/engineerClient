# P2 Storm: what the client sees (measured for the P3 Sim)

Extends `docs/mechanics/storm.md`, `docs/storm-crush.md` and `docs/maxor-storm-movement.md`
(movement, pads, crush rule, pin, taunts: re-checked, still right). Scripts:
`/home/cam/backups/2026-10-03/analysis/p2storm/*.mjs`. Better PF = 150 complete F7 runs (newest
first); BossRecorder = the 77 packet files (~60 with Storm). Times are server ticks after
"Pathetic Maxor, just like expected." unless said otherwise. **M** measured, **C** conjecture.

## Chat (timeline.mjs, deathgap.mjs, hits.mjs; 150 runs)

- Scripted lines at 0/62/124/186/424/486, lightning line 548, all `§4[BOSS] Storm§r§c: <line>` (M).
- Giga strikes at line +9/10/11 and **+19 (34) / +20 (31)**; 2nd lightning strikes +35/+44 (3 runs).
- Death -> "At least my son died by your hands." **+62 in 150/150**; Goldor's line **+102 in 140/150**
  (83-100 in 7 runs dying late, 302 in 3 recordings of one run).
- First taunt 884-943 here; taunts never come after the death line.
- Hit lines, exact: `§cStorm's§r§7 <Giga Lightning|Static Field|Lightning Fireball|Frenzy> hit you
  for §r§c<n>§r§7 [true ]damage.` Numbers: thousands commas, one decimal, **no `.0`** ("10,047",
  "9,292.1"). Static Field 10,800; Lightning Fireball 75,000; Frenzy mostly 1,990-2,170 (to 13.6k).

## His look (stormvis.mjs, speech.mjs, armour.mjs)

- Name stand, exact: `§e﴾ §r§8§r§5 §r§c§l<Name>§r §e﴿`, 3.69 above, same for
  Maxor/Storm/Goldor (the sim had `§c§l﴾ .. $name ﴿`).
- **Speech stands:** every `[BOSS]` line spawns a marker stand `§4§l<line>` 4.1 above him the next
  tick, following him, gone after ~40 (p90 43) (65 stands, 20 runs).
- wither.ambient (5, 1.19) of a line plays **at him** (deathsnd/brdeath: at his body, recorder 30
  blocks off), not at you.
- Health/armour: 1 before crush 1, 1000 from each crush, back to 1 after the enrage in most
  fights (97 vs 29 packets), 1000 from crush 2.
- Boss bar (resent ~every 20 ticks, so changes land 0-24 late): `§c§lStorm` 1.0 from ~+8; at
  crush 1 ~0.95 then falling (0.81, 0.72, 0.60, 0.47 in a long pin) to **0.45 at the enrage**;
  0.45 while free; from crush 2 ~0.40 down; 0 at the death line.

## Attacks before the lightning (fireballs.mjs, fields.mjs, fieldsrc.mjs, shots.mjs, giga.mjs)

- **Lightning Fireball**: vanilla large fireball from his position +0.8 y at a player, every ~44
  (p10 37, p90 58), first 71-252 (median 113), last ≤ ~427: only on the opening route. Push 0.15
  a tick with 0.95 inertia (avg speed 0.74 / 1.55 / 2.0 over ticks 1-10 / 10-20 / 20-30).
- **Static Field** = a fireball's impact (50 of 91 fields start 1 tick before a fireball's removal
  there; the rest had the fireball out of range). ~4 a run (fireballs ~7.5): about half the
  impacts seed one (C: what decides it is unknown). Shape: 4 bolts on the spot, then 6 rings of 4 at
  ±1.8·k at +3, 7, 12, 16, 21, 25, all X or all + (599/726). You're hit (once) when a bolt lands
  within 3 blocks (26 hits: 0.6-2.9 away, same tick).
- **Giga Lightning visuals**: a player in the open gets 31 bolts within ±8 (one on them); anyone
  under a pillar gets a **40-bolt ring of radius 7 round (minX + 4, minZ + 4)**; thunder (2, 1.4).
- **Skulls**: pairs from the side heads (+2.2 y, ±1.3), every 5 ticks, power 0.1, **from 6-47
  after the enrage (median 17) until crush 2** (1226 of 1283 volleys; 57 in long first chases).
- Frenzy only while free after crush 1, ≤ 6.3 blocks (storm.md §3).

## Pillars (piston.mjs)

- piston.extend / piston.contract, **volume 10, pitch 0.49**, at (minX, layer y, minZ + 2), one
  per layer stepped down / drawn up (the sim had extend 0.6/0.8 at the middle, no contract).
- Re-laid blocks are all polished diorite; crushed pillars hang at bottom ~183 into P3 (world.md).

## Death and the "storm end" (corpseend.mjs, postdeath.mjs, deathsnd.mjs, corpsepk.mjs, postbolts.mjs)

- No fall at the death line: the body **spins 40°/tick** where it was pinned; wither.hurt (hostile,
  15, 1.0) every 10-13 ticks; explode (2, ~0.6) at +0/+4; zombie wooden-door break (3, ~0.9) ~+12.
- **Health 0 at +226** (215-236, n 15), **removed at +249** (235-257): the body lasts into P3.
- **480 bolts** over ~400 ticks (every fight exactly 480): 24 spirals from the body on a fixed
  schedule (0, 22, 46, 70, 85, 100, 116, 132, 147, 162, 173, 186, 200, 210, 220, 231, 242, 257,
  270, 282, 292, 302, 312, 323; two fights agree to 1-3 ticks), each 20 bolts 2 blocks apart out
  to 38, ~4.5 ticks apart, turning +90° a bolt; vanilla thunder (10000, 0.8-1.0).

## The party (dropspot.mjs, p3pos.mjs, ppos.mjs)

- Players leave the arena 5-20 ticks after the death line, mostly by leaping onto the player
  already at the SS (108.3, 120, 94); at Goldor's line 122 of 131 are in P3 (110 at S1).
- At the lightning +10: under Yellow 27, under Purple 11, in P3 27, elsewhere 36.

## Not measurable here

Titles (neither recorder keeps them); the fireball's own burst (no `ex`/sound pinned to it);
what makes an impact seed a field; Red's height.
