# P4 Necron: what the sim reproduces, measured

Builds on `docs/mechanics/necron.md` (timings of lines, trips, ARGH grid, volleys; 126 runs with
`st`). New here: Boss Recorder packet recordings (`config/engineerclient/bossrecorder`, 57 that
reach "All this, for nothing...", server ticks counted from pings) and Better PF chat (80 recent
complete F7 runs). `n` = server ticks after Necron's first line. Scripts:
`/home/cam/backups/2026-10-03/analysis/p4/` (bar.mjs, hurt.mjs, storm.mjs, storm2.mjs,
brattacks.mjs, brfalls.mjs, endsounds.mjs, endblocks.mjs, endvalues.mjs, centre.mjs, p4chat.mjs,
buildres.mjs -> `p4-necron.json`).

## Chat
- Every Necron line is `§4[BOSS] Necron§r§c: <text>` (60/60 runs), each with wither.ambient v5
  p1.19 at the player. Frenzy: `§cNecron's§r§7 Nuclear Frenzy hit you for §r§c57,600§r§7 damage.`
- End of run (80 runs): two blocks. At "All this" + 86 (BPF median 88, 66-115):
  `▬` bar (`§a§l` + 64), `The Catacombs - Floor VII`, empty, `Team Score: §r§a<298-311> §r§f(§r§b§lS+§r§f)`,
  `☠ Defeated Maxor, Storm, Goldor, and Necron in mm ss`, `§6> §e§lEXTRA STATS §6<`, `+<45-122> Bits`
  (58/80), `+<46-69k> Catacombs Experience`, `+<0.76 of that> <Class> Experience`, the four other
  classes' `(Team Bonus)` lines (a quarter of the class XP, random order), bar. 1-9 ticks later
  (median 3): bar, `... Floor VII Stats`, empty, score, defeated, empty, `Total Damage as <Class>`,
  `Ally Healing` (78/80), `Enemies Killed`, `Deaths: §r§c#`, `Secrets Found: §r§b#`, empty, bar.
- Centring: `§f` + spaces, spaces = ceil((160 - width/2) / 4), width with Hypixel's glyph widths
  (6 default; 2 for `!,.:;'|i`, 3 `l\``, 4 space `I[]"`, 5 `tfk()<>*{}☠`, bold +1): 337 of 338 lines.
- No titles are recorded by either recorder; none could be checked.

## Boss bar (57 runs)
Goldor's bar, renamed `§c§lNecron` at the first line; Hypixel re-sends it every ~20 ticks (19-23)
on its own phase. Value = his health: 0 through the intro; at the first update after L1 1.0 or
0.8-0.97; **0.8 by B1** (first 0.8 = B1 + 4, -29..20); **≤ 0.25** first shown ARGH 1 + 15 (0..51),
often via 0.3-0.7 (he is hit at mid); **0.05** (30/47; 0.04 in 12) first shown B2 - 13 (-51..20);
**0** at ARGH 2 + 11 (0..32), kept to the end.

## When he can be hit (hurt-animation packets, 47 runs)
None in the intro; the first 7-38 (median 9) ticks after L1 (the sidestep is untouchable); hits in
every window after that, heaviest during the waits at mid; a few after ARGH 2. (The sim has no
boss damage; this is not reproduced - see the report.)

## Sounds at him
- Teleport back to mid (B1, B2): enderman.teleport v10 p0.49.
- Frenzy pulse: explode v30 p31/63 + wither.ambient v30 p44/63 at mid, every pulse, whoever is near.
- No sound when he fires. Fireball impacts: explode v4 p0.6-0.8.
- Death throes: from ARGH 2 + 2..7, 24 wither.hurt v15 p1 five ticks apart and 12 explode v15
  p31/63 ten apart, to "All this" + ~56.

## Fireballs (Boss Recorder velocities)
Spawned at his position + (0, 2.97, 0) (no forward offset), velocity 0.5 b/t pitched 11.8° down,
then each tick v = (v + 0.1·dir)·0.95 (vanilla). Volley 1 south onto S (impacts ~(54, 63, 100));
volley 2 at the yaw of the platform to break (±45°, ±135°, 180°). The platform goes **100** ticks
after volley 2's first fireball (min 100, up to 112; the `st` count in necron.md said 98-99).

## Platform break
One of N, NW, NE, SW, SE (never S); ~656-697 blocks y 59-63 to air in one tick, matching the sim
arena block for block (`p4-necron.json` platforms, consensus of the runs).

## Lightning and light columns (57 runs)
From n 60-73 (median 61): 30 lightning bolts, gaps 9-11 (10), at y 63 (62-64) around one of seven
3x3 light columns: N (53,48), NE (72,56), E (80,75), SE (72,94), SW (34,94), W (26,75), NW (34,56)
(min corners; about equally often; unrelated to the broken platform). The column (iron blocks,
y 64-90, or 59-91 for E/W) lights sea lanterns that climb two ticks a step, each row lit 5 ticks,
from the first bolt to 13-14 after the last. Bolt distance from the column: median 8.6, p10 2.9,
p90 14.3.

## Lava pillars
A source column falls from y 85 at one of 7 pillars ~225 before "Let's make some space!", a block
every ~10 ticks, then eats into the platform it lands on (lava replaces platform blocks, ~1,800 by
the end). At the line the others start (-4..+26), each with odds 52-75%.

## Death
Health 0 at "All this" + 38-50 (median 40), wither removed +58-68 (61). Ten primed TNT (vanilla:
vy 0.2, ±0.02 sideways, fuse 80) at his position at +37..+50 (median 40), all in one tick,
removed ~62 later, never exploding.

## Not reproduced / unknown
- B1 / B2 depend on the party's damage (health thresholds 0.8 / ~0.05 per the bar); the sim uses
  the fast-run medians.
- The S platform went to air at n ≈ 100 in 7 of 57 runs: unexplained.
- The TNT near "Let's make some space!" (44 of 126 runs) and the wither-skull pairs while off mid
  in long trips (the fast script only has the pair at L2).
