# Necron (F7 phase 4): mechanics measured from Better PF recordings

Measured from the Better PF recordings of 2026-09-23 to 09-29. Scripts: `tools/boss-mechanics/necron/`
(see its README); every number here is printed by `analyze.py`. The fight is taken from Necron's
first line ("You went further than any human before, congratulations.") to "All this, for
nothing..." and the end-of-run stats.

**Data.** 232 recordings (189 runs) reach Necron; 150 recordings (126 runs, all 5-player bar one
6-name party list) have server ticks (`st` lines) and are the only ones used for timing. The four
alpha-server recordings are left out. One recording per run is used (the one with server ticks and
the most Necron packets); siblings agree on chat times to +-3.

**Conventions.** `n` = server ticks after Necron's first line (20 a second), each line getting the
`st` value last seen before it in file order. Chat is +-1 tick. Necron's positions are
de-interpolated (`bosslib.delerp`). "p10-p90" is the middle 80% of runs. **Measured** statements are
what the recordings show; **Conjecture** marks an explanation the data does not prove.

## Summary

- **Everything is on one script with two player-controlled gates.** Necron spawns on mid
  (54.0, 66.0, 76.0), talks for 186 ticks, leaves mid at n = 159 and is put back on mid by a
  teleport (B) when the players have done something to him (conjecture: a damage threshold). That
  happens twice; everything else follows from B on fixed delays and a 20-tick grid:
  - a taunt ("Sometimes when you have a problem..." or "WITNESS MY RAW NUCLEAR POWER!", 50/50) is
    said at B, or 62 ticks after the previous line if that is later;
  - **"ARGH!" comes on the first tick of a 20-tick grid (n = 5 mod 20) that is at least 141
    ticks after B**, and never earlier than 80-82 ticks after the taunt (that makes the first one
    330, not 325);
  - "Let's make some space!" is ARGH 1 + 62, "All this, for nothing..." is ARGH 2 + 62.
- **Fastest possible Necron: 607 server ticks (30.35 s)** from the first line to "All this, for
  nothing...": ARGH 1 at 330 needs B1 <= ~184 (trip 1 at most ~25 ticks), ARGH 2 at 545 needs
  B2 <= ~404 (trip 2 at most ~15 ticks, and Necron leaving mid on time). ARGH 2 at 525 is out of
  reach: he only leaves mid for the second time at n >= 387. **68 of 125 runs (54%) did it**
  (606-609); the median run is 608. Nearly every slower run is slower by a multiple of 20 ticks.
- Necron's own attacks: two volleys of 8 fireballs 10 ticks apart, pairs of wither skulls while he
  is off mid, **Nuclear Frenzy damage pulses every 20 ticks while he is locked on mid (on the ARGH
  grid)**, a TNT on himself near "Let's make some space!", and ten TNT 38-45 ticks after "All this,
  for nothing...".
  - The first volley is always at n = 60-130, due south.
  - **The second volley is aimed at the lava platform he is about to destroy.** That platform
    (one of N, NW, NE, SW, SE, about equally often; never S) is set to air 98-99 ticks after the
    volley's first fireball, which is 45 (37-54) after "Let's make some space!".

## 1. Timeline at a glance

| n (median; range) | what | status |
|---|---|---|
| -2..0 | Goldor-core floor (x 39-69, y 101-125, z 99-129, ~730 blocks) set to air | measured |
| 0 | first line; Necron's wither spawns at (54.0, 66.0, 76.0) (first seen -2..0) | measured |
| 60, 70, ... 130 | fireball volley 1: 8 fireballs from his centre head, 10 ticks apart | measured |
| 62, 124, 186 | "I'm afraid, your journey ends now." / "Goodbye." / "That's a very impressive trick..." | measured |
| 159 (158-162) | **leaves mid (L1)** | measured |
| L1 + 22 | first pair of wither skulls; more pairs while off mid | measured |
| **B1** 177 (166-372; p10-p90 170-189) | teleported back to mid; trip 1 lasted 17 (7-212) | measured; trigger = conjecture |
| B1 .. ARGH 1 (205, 225, ...) | Nuclear Frenzy pulses at mid, every 20 ticks | measured |
| max(248, B1) | taunt 1 | measured |
| **ARGH 1** 330 (327-332 in 105/126) | first grid tick >= B1 + 141, but >= taunt + ~82 | measured |
| ARGH-event + 3..24 | fireball volley 2 (8 fireballs, 10 apart), aimed at the platform to be removed | measured |
| volley 2 + 60 (387-409) | **leaves mid again (L2)**; a skull pair at L2 | measured |
| ARGH 1 + 62 = 392 | "Let's make some space!" | measured |
| volley 2 + 98 (96-100); space + 45 (33-100) | that platform set to air | measured |
| **B2** L2 + 5 (1-166) | teleported back to mid | measured; trigger = conjecture |
| max(space + 62, B2) | taunt 2 | measured |
| **ARGH 2** 545 (543-547 in 69/126) | first grid tick >= B2 + 141 | measured |
| ARGH 2 + 62 = 607 | "All this, for nothing..." | measured |
| All this +38..45 | ten TNT at his position | measured |
| All this +61 (59-65) | wither removed | measured |
| All this +62 | "I understand your words now, my master." (27 of 187 runs) | measured |
| All this +86 (81-92) | "Team Score" / "Defeated Maxor, Storm, Goldor, and Necron in ..." | measured |

## 2. Dialogue

**Measured.**

- The start: "You went further than any human before, congratulations." in every run bar one
  ("Finally, I heard so much about you. The Eye likes you very much.", a recording without server
  ticks). The Goldor-core floor is removed 0-2 ticks before it and the wither appears in the same
  tick (-2..0).
- It is not tied to Goldor's lines. In 79 runs Goldor's "...." comes before it (81 ticks, -87..-80,
  in all but two) and "Necron, forgive me." in the same tick as Necron's first line (-2..0, 77
  runs). In the other 47 runs Goldor still has lines queued ("YOU ARE FACE TO FACE WITH GOLDOR!"
  and so on, 62 ticks apart), and his "...." comes *after* Necron's first line (+23..+104), with
  "Necron, forgive me." 80-84 ticks after that (+106..+186). Necron's schedule is the same either way. Conjecture: both are timed from
  Goldor's death, which is 81-82 ticks before Necron's first line. Goldor's "...." is that moment
  only when his dialogue queue is empty.
- **Boss lines are queued 62 ticks apart** (61-63 for 378 of 378 of the three fixed gaps). The
  first four lines are at 0, 62, 124 and 186 in every run.
- **Taunt 1** = max(186 + 62, B1) within +-2 in 125 of 126 runs. **Taunt 2** = max(space + 62, B2)
  within +-2 in 107 of 112 (two are 3 ticks off; in three stalled runs an extra line took the slot). Each taunt is "Sometimes when you have a problem, you just need to
  destroy it all and start again." or "WITNESS MY RAW NUCLEAR POWER!", 64/61 and 59/63.
  The two are independent: the pairs SS 36, SW 29, WS 27, WW 34. Which one is said changed nothing
  measurable.
- **ARGH!** (twice per run): see section 4. ARGH 1 is never less than 80 ticks after the line
  before it (median 82 when that is the binding limit), where the other lines follow 62 apart.
- "Let's make some space!" is ARGH 1 + 62 (60-64) in 121 of 126. In three runs it was 67, 96 and 101
  ticks later: unexplained.
- "All this, for nothing..." is ARGH 2 + 62 (59-65) in 122 of 125. One run shows 55, which is a
  glitch in that recording's tick count.
- "I understand your words now, my master." comes 62 ticks after that in 27 of 187 runs. Siblings
  always agree, so it is chosen per run, not per recording.
- Rare lines, only in slow runs (a trip lasting 40+ ticks, or ARGH delayed): "Show me how you beat
  Storm!!!", "Fight for your life!", "I - Necron - was destined to rule over Mankind! Dead or
  Alive!", "You merely adopted the Catacombs! I was molded by them!", "IF YOU WANT TO STAY COOL,
  DON'T LOOK!", "BOOOOOOOOOOOOOOOOOOOOOOOOOM" (1-3 recordings each). They take a taunt's place or
  come between B2 and a late ARGH 2. Conjecture: these are further attacks he runs while the fight
  stalls.

## 3. Movement

**Measured.**

- **Mid** is (54.0, 66.0, 76.0), about 2 blocks above the centre platform. He appears there, sits
  there through the intro (body yaw turning between players), and every return is to exactly that
  point (within 0.001).
- **Leaving mid, trip 1: n = 159** (158-162, 126 runs). The DungeonSplits comment's "148-223" was
  measured in client ticks, while he was in view.
- **The opening sidestep is scripted.** He moves 0.25 blocks a tick (0.5 per 2-tick packet) for
  7 ticks, 1.75 blocks, at 45-75° to the left or right of south (yaw -75..-45 or +45..+75; never
  straight). He holds for 2-3 ticks, then flies. The distances from mid at L1 + 0..8 are
  0.25, 0.75, 0.75, 1.23, 1.25, 1.72, 1.75, 1.75, 1.75 in every run. **Nobody put him back during
  those 7 ticks: the earliest B1 is L1 + 7** (n = 166).
- **Flight.** From 10 ticks after leaving he moves 0.49 blocks/tick (median; p10-p90 0.26-0.84,
  max about 1.7) on trip 1 and 0.63 (0.32-1.32) on trip 2. Packets mostly come 2 ticks apart.
  Trip 1 heads roughly south, toward the S platform where the party lands from the core (yaw
  0 +-30° in 80% of runs). His farthest point is 5 blocks from mid (median; p10-p90 1.8-11,
  up to 37). Trip 2 reaches 2.3 (0.7-8) in any direction. In two slow runs (6579d67a, and
  42bb2849 / c11bae5c) trip 2 dived straight down from mid, through the centre platform, to y ≈ 8,
  about 0.9 blocks/tick. He flew along the level below until B2, 141-166 ticks after leaving.
- **Return: a single teleport packet**, 3.2 blocks (median; up to 52), to exactly mid.
- **Leaving mid, trip 2: L2 = first fireball of volley 2 + 60** (57-61, 112 runs), which is
  388-409 when ARGH 1 is 330. It is near "Let's make some space!" (-6..+24) but not tied to it.
- **Targeting (conjecture).** His flight direction is 5° (median) from *some* player's bearing. It
  is 16-18° from the closest player, the recorder or the party's centre, which all lie in about the
  same direction at that moment. The recordings cannot tell a nearest-player rule from others,
  because the party is bunched and other players' positions are the recorder's delayed view.

## 4. What ends each DPS window

The chat-driven splits call the steps Animation, Mid, Dps, Dps, Mid, Dps, Animation. Measured,
the fight has two trips off mid, each followed by a fixed wait:

| window | starts | ends | length |
|---|---|---|---|
| trip 1 (DPS 1) | L1 = 159 | B1, teleport to mid | 17 (7-212); p10-p90 11-30 |
| wait at mid | B1 | ARGH 1 | first grid tick >= B1 + 141 (min 330) |
| trip 2 (DPS 2) | L2 = volley 2 + 60 | B2, teleport to mid | 5 (1-166); p10-p90 2-23 |
| wait at mid | B2 | ARGH 2 | first grid tick >= B2 + 141 |

**Measured.**

- **B (back to mid)** varies from 1 to 212 ticks after he leaves. Fast parties do it in 1-5 ticks
  on trip 2, and arrows and swings are already in flight before he leaves. It is neither a time
  limit nor a place: his farthest point before B1 ranges from 1.7 to 37 blocks from mid.
- **ARGH is on a 20-tick grid, not on damage timing.** ARGH 2 is 543-547 (69 runs), 561-567 (34),
  582-587 (11) or 604-605 (3); ARGH 1 is 327-332, 344-347 or 365. B is spread evenly. Which slot
  depends only on B:
  - ARGH 2 = 545 for B2 389-405, 565 for B2 405-423, 585 for B2 425-442 and 605 for B2 449-458.
    That is 102 of 112 runs within +-2 of "first tick = 5 (mod 20) at least 141 after B2". The ten
    misses are the stalled runs of section 2 plus three that are 3-4 ticks off.
  - ARGH 1 = 330 for B1 166-186 and 344-347 for B1 184-204. That is 120 of 126 within +-2 of
    max(first grid tick >= B1 + 141, 330). All 6 misses are B1 = 184-186, right on the edge, or
    3 ticks early.
  - The grid is anchored to the first line (+-1): 325, 345, 365 ... 505, 525, 545, 565 ... The
    first slot, 325, is said at 330 because the taunt at 248 holds the next line until 248 + 82.
  - It is a repeating timer, not an absolute clock. In one slow run (92f33058) the Nuclear Frenzy
    pulses and ARGH 2 of the second wait all sat 5 ticks later (589, 610, 629, ARGH 730) than those
    of the first (403, ARGH 504).
  - The same grid carries the Nuclear Frenzy pulses (section 5), so ARGH is "the first Frenzy
    pulse at least 141 ticks after B".
- **Conjecture.** B is a damage threshold: he returns to mid when his health crosses a third (or
  so) of the bar. The recordings have no boss health: no health in the name tag ("﴾ Necron ﴿"),
  no damage numbers. After B he is locked at mid for at least 141 ticks, and the phase change (ARGH)
  is checked every 20 ticks. Whether the players must also deal damage during the wait cannot be
  seen: every run with a normal B reached the first slot, and the stalled ones (section 2) are too
  few to tell.

## 5. Attacks (entity spawns)

**Measured.**

- **Fireballs** (`minecraft:fireball`) spawn 2.8 above his position (his centre head) and
  0.4-0.6 in front of it. They fly at about 0.8 blocks/tick (client view).
  - **Volley 1:** 8 fireballs, the first at n = 60 (59-62, 126 runs), 10 ticks apart (9-11 in 94%
    of gaps): 60-130. Every one (985 seen) flies due south, toward the S platform, whatever the
    players do.
  - **Volley 2:** 8 fireballs, 10 apart, pitched about 10° down, starting 3-24 ticks after the
    ARGH-1 grid tick (median +8 after the line; p10-p90 0-18). A few started late: +43..+102.
    **All of them fly toward one side platform (yaw ±45°, ±135° or 180°), the one that is removed
    98-99 ticks after the volley's first shot**: 122 of 122 runs with both seen. Only the 8th,
    fired in flight, strays. His head yaw often already points there before the first shot.
    **He leaves mid for trip 2 with the 7th** (L2 = first + 60). Nothing else fires between the
    volleys while he is at mid.
- **Wither skulls** come in pairs from the side heads (1-1.5 to the side, 2.1-2.4 up), at about
  0.4 blocks/tick. On trip 1 the first pair is at L1 + 22 (20-24). On trip 2 it is at L2 + 0
  (-2..0). Further pairs come 14-16 ticks apart (mixed with 5-tick gaps) as long as he is off mid.
- **TNT** (`minecraft:tnt`): one at his own position within 10 ticks of "Let's make some space!"
  (44 of 126 runs; the rest may simply not have seen it), and ten at his position 38-45 ticks after
  "All this, for nothing..." (the death explosion).
- **Nuclear Frenzy.** "Necron's Nuclear Frenzy hit you for 57,600 damage." reached recorders 54
  times in 16 runs. **Every hit came while Necron was locked on mid between B and ARGH (54 of 54),
  on the same 20-tick grid as ARGH** (hit tick mod 20: 3-6 in 50 of 54; the other 4 are in the
  shifted run below). So the wait at mid is a pulsing area attack, one pulse every 20 ticks, and
  ARGH ends it on a pulse. There are no entity spawns for it. Conjecture: it only hits players
  within some range of mid, since recorders standing elsewhere never report it.
- "Necron's Wither TNT hit you for 10800.0 damage." was seen 7 times.

## 6. Arena and block updates

**Measured.**

- The arena is a lava lake (lava y 55-58) with six platforms 5 blocks thick (y 59-63): N (x 39-69,
  z 32-52), S (x 39-69, z 100-120), NW (14-42, 36-64), NE (66-94, 36-64), SW (14-42, 88-116), SE
  (66-94, 88-116). There is a centre platform under mid. Below the lava floor (bedrock y 52) is a
  lower level whose floor is at y 4-5.
- **Goldor-core floor**: ~730 blocks at x 39-69, y 101-125, z 99-129 go to air in one tick, 0-2
  ticks before Necron's first line. That is what drops the party into phase 4, onto the S side.
- **"Let's make some space!"**: one whole platform (656-697 blocks, y 59-63) is set to air in a
  single tick, and lava block updates follow. The platform is SW 31, NW 30, NE 24, SE 24, N 16
  times (125 runs), S never; it is the one fireball volley 2 was aimed at (section 5).
  - **Timing: the break is 98-99 ticks (96-100) after volley 2's first fireball.** It is not tied
    to the line, which comes 33-100 ticks before it (median 45), because the line is on ARGH's
    clock and the volley starts a variable 3-24 ticks after ARGH's grid tick.
  - Conjecture: the platform is chosen at random among the five that are not S, when volley 2
    starts or before.
- The S platform itself went to air at n = 99-101 in 5 runs (12 recordings), with the party
  standing at y 69-70 above it. Unexplained.
- A sea-lantern / iron-block light show at x 23-74, y 64-161, z 94-136 changes 36 blocks every
  2 ticks from about n = 60 to the end. It is scenery and is only counted in the extracts.

## 7. End of the fight

**Measured.** After "All this, for nothing..." Necron stays at mid. Ten TNT spawn on him at
+38..45, the wither is removed at +61 (59-65), and in 14% of runs he says "I understand your words
now, my master." at +62. The end-of-run stats ("Team Score: ...", "☠ Defeated Maxor, Storm, Goldor,
and Necron in mm:ss", same tick) arrive at +86 (81-92). There is no further boss phase on F7.

## 8. Tick-grid mechanics

- **62-tick dialogue queue**: each boss line is held at least 62 ticks after the previous one;
  a taunt holds the next line about 82 ticks. Goldor's queue works the same way and runs
  independently (section 2).
- **20-tick phase grid** anchored at the first line (n = 5 mod 20, +-1). The Nuclear Frenzy
  pulses at mid run on it, and ARGH lands on it at least 141 ticks after the teleport to mid. In
  one run it re-phased by 5 ticks between the two waits.
- **10-tick fireball cadence** within a volley (8 shots); 14-16-tick skull pairs.
- Fixed offsets:
  - L1 = 159; the sidestep is 7 ticks at 0.25 b/t.
  - Volley 1 = 60..130.
  - L2 = volley 2 + 60; platform break = volley 2 + 98.
  - Space = ARGH 1 + 62; end = ARGH 2 + 62; wither gone = end + 61.
- The platform break, B and the start of volley 2 are *not* on the 20-tick grid.

## 9. Fastest possible Necron, and what the best runs did

- ARGH 1: the first grid slot, 325, is said at 330 because of the taunt queue (248 + 82). Getting
  it needs B1 + 141 <= 325, so **B1 <= ~184: trip 1 at most ~25 ticks**, of which the first 7 are
  the untouchable sidestep. Measured boundary: B1 184-186 went either way.
- ARGH 2 at 545 needs **B2 <= ~404**. L2 = volley 2 + 60, and volley 2 starts at least 2-3 ticks
  after the 325 grid tick, so L2 >= 387 at best. The 525 slot would need B2 <= 384, before he can
  leave, so **545 is the floor, and the fight's fastest is 545 + 62 = 607 ticks (30.35 s)** after
  the first line. The recordings: 606 (11 runs), 607 (35), 608 (20), 609 (2). Every other run is a
  whole number of grid steps slower: 624-628 (+20), 645-648 (+40), 666-668 (+60) ...
- What the 606-608 runs did: trip 1 ended at B1 166-184 (7-25 ticks after he left), and he left
  mid for trip 2 at 387-401, with trip 2 lasting 1-11 ticks.
- Where the other runs lost time (112 runs with both trips seen):

  | outcome | runs | cost |
  |---|---|---|
  | ARGH 2 at 545 (optimal) | 56 | - |
  | trip 1 too long (B1 > ~184, ARGH 1 at 345 or later) | 21 | 15+ ticks, then ARGH 2 at 565+ |
  | trip 2 too long (B2 > 404 with L2 on time) | 26 | 20 ticks |
  | he left mid late (volley 2 started 21+ ticks after the grid tick, L2 406-409) | 9 | 20 ticks, even with 1-9-tick trips |

  In total, late ARGH 1s cost 835 ticks over the dataset, and ARGH 2s beyond ARGH 1 + 215 cost
  2,562. What delays volley 2 is not known, so the last row may be luck, or something the players
  do (for example, where they stand).

## 10. Measured vs conjecture, open questions

- **Measured**: every time, position and count in sections 1-9 unless marked. In particular, the
  62-tick queue, L1 = 159, the scripted sidestep, the teleports back to exactly mid, the ARGH
  20-tick grid at B + 141, space/end = ARGH + 62, taunt = max(queue, B), both fireball volleys,
  volley 2 aiming at the platform removed 98 ticks later, L2 = volley 2 + 60, and the Nuclear
  Frenzy pulses at mid on the ARGH grid.
- **Conjecture**:
  - B is a health threshold.
  - The 141-tick lock and the 20-tick check are server timers.
  - The platform choice (seen in volley 2's aim) is random among the five that are not S.
  - The Nuclear Frenzy only reaches players near mid.
  - The rare lines are extra attacks in stalled fights.
  - The first line is tied to Goldor's death (81-82 ticks before).
- **Open**:
  - Necron's exact target rule.
  - What starts volley 2 (3-24 ticks after the grid tick; sometimes 40-100). This also sets L2
    and the platform break. It showed no link to how far he had to turn.
  - The S platform removal at n ≈ 100 in 5 runs. Volley 1 does aim at S, but that removal comes
    40 ticks after the volley's first shot, not 98.
  - The three late "Let's make some space!" lines.
  - Whether damage is also needed during the 141-tick wait.
  - Recorder builds from 2026-09-29 on keep the boss log (`net` lines, FORMAT.md): every packet in the boss by server tick.
    Recordings made with them would pin B, L and the head targeting to the tick.

## 11. Caveats

- One recording (104ff380) has "All this" 55 ticks after ARGH 2 and a 599 split. Its `st` count
  runs ahead there, so it is left out of the "fastest" claim.
- 63 runs (82 recordings, before the recorder wrote `st`) are used only for positions and platform
  choice.
- B and L are the ticks the recording received the packet (+-1-2). Chat is +-1. Boundaries quoted
  as "~184" and "~404" carry that uncertainty.
