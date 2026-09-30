# Terminals (F7 P3): a strategy from the measured mechanics

For a party of five where everyone carries all three invincibility items (Spirit Mask, Bonzo's
Mask, Phoenix). Every rule it rests on is measured in [goldor.md](goldor.md) or in the extra
measurements listed at the end; anything inferred is marked. Times are server ticks after
"[BOSS] Goldor: Who dares trespass into my domain?" (n = 0), 20 a second.

## The rules that decide everything

1. **A section ends at the later of its last completion and its gate.** The door opens in that
   tick. A gate still standing at the last completion costs 4-100 ticks ("The gate will open in 5
   seconds!").
2. **Death ticks come every 60 ticks from n = 0, all phase long** (n = 60, 120, 180 ...; the
   chat line at 60k-1). Each one kills anyone standing in a section *ahead* of the one in
   progress. The section in progress, earlier sections and the core are safe.
3. **One invincibility covers one death tick, never two.** In 169 cases of a proc followed by
   standing ahead through the next tick, all of them procced again or died at that next tick
   (except where the player had left, or stood on a zone edge). So standing ahead costs exactly
   **one item per death tick you are there for**, whichever item it is.
4. **The budget is 3 items a player, 15 a party.** Spirit comes back after its cooldown: six
   players in the data used it twice in one P3, 719-963 ticks apart. Count a second Spirit only in
   a slow P3 (the gap needed is at least 600 ticks, exact value not measured).
5. **What can be done before its section starts:**
   - **Devices of later sections credit early**: S2 Lights and S4 target shooting during S1, S3
     Arrow Align during S2. The line prints the current counter without advancing it, and the
     section later starts at 2/n. The S4 device is always done standing on its plate (63,127,35)
     in S4, so it pays death ticks.
   - **Levers of later sections do not**: they flip back 1-6 ticks later. They credit on the
     first pull after the door (1-3 ticks after it if you are standing there).
   - **Terminals of later sections do not open** ("This Terminal doesn't seem to be
     responsive"), and the first terminal completion of a section is never sooner than 28 ticks
     (S2) or 42 ticks (S3, S4) after the door.
6. **S1's floor is Simon Says**: it never finished before n = 236. The four S1 terminals and both
   levers are done by about n = 120 in fast runs, so S1 lasts as long as Simon Says.
7. **The core**: Goldor leaves the track on the tick the last player enters the core box, and he
   can only be damaged in the core. Everyone in fast is the whole Goldor split.

## The free window

Death ticks are on a fixed, known grid. Enter the next section on the tick **right after** a death
tick and you have 59 ticks there for free. If the door opens before the next death tick, the
pre-entry cost nothing. Every other pre-entry costs one item per death tick crossed.

So pre-entering is about *when*, not whether: go at 60k+1 for a door you expect before 60k+60.
The phase-start line starts a counter everyone can read. A death-tick countdown on screen makes
this exact; Odin or a small Engineer Client HUD can show it (see the end).

## Where time is actually lost (median run vs the fast 8)

| | median | fast 8 | tail (what finishes last) |
|---|---|---|---|
| S1 | 271 | 251 | Simon Says (device last in 37 of 45) |
| S2 | 269 | 192 | a terminal (36 of 45), 66 ticks after the one before |
| S3 | 249 | 182 | terminal 19, lever 16, **Arrow Align 10** |
| S4 | 210 | 139 | terminal 21, **lever 18**, device 6 |

- S3 and S4 levers are never done sooner than 90 and 84 ticks after the door, because they are
  far from it. That is walking, and pre-entry removes it.
- Arrow Align was pre-done in only 3 of 45 runs, yet it was S3's last completion in 10.
- The S3 gate goes late (median 200 ticks into S3). The S1 and S2 gates go early.

## The plan

Roles follow Engineer Client's rotation names (`ss`, `21`, `43`, `i4`, `l+ee2`, `ee3`,
`core`). The item cost of each move assumes the free-window timing; the budget table below
sums them.

### Section 1 (target: door at n = 236-239, before the 240 death tick)

- **ss**: Simon Says from n = 0, nothing else. It is the whole section.
- **l+ee2**: both S1 levers at n = 0. They credit even a tick before the start line. Then
  **enter S2 at n = 181**, do **Lights** (pre-credits S2), and stand at S2's lever
  (23,132,138). Pull it the tick the door opens (it credits 1-3 ticks after).
  - Cost: 0 if the door comes by 239, 1 if not.
- **21, 43**: two S1 terminals each, done by ~120.
  - One of them also blows the S1/S2 gate (x 93-107, z 121-124) early; fast runs had it down by
    ~55.
  - Then both **enter S2 at 181** and stand at their S2 terminals, 0-1 items each.
  - Their first S2 terminal can open 28 ticks after the door anyway. Standing there turns the
    median 49 into that floor.
- **i4**: the S4 target device on the plate at (63,127,35).
  - It is the one job that must sit through death ticks: in the data it finished at n = 171-232
    after standing there from the start, costing 2-3 items.
  - Go at n = 1 and plan to spend 2 of i4's 3 items here, 3 if it runs past 180.
  - i4 then takes no pre-entry until S4.
  - Inferred: going later (after 60) only helps if the device doesn't need that time. Nothing
    in the data shows it can be done faster.

### Section 2 (5 terminals, 2 levers; Lights already credited)

- At the door: the S2 lever (l+ee2, 1-3 ticks), and the three pre-entered players open their
  terminals.
- **Blow the S2/S3 gate (x 16-19, z 125-139) in the first minute of S2.** Median is 87, fast runs
  74; it must never be the last thing.
- **ee3**: does S2's other lever, then **enters S3 at the last free window before the S2 door**.
  There it does **Arrow Align** (credits S3 to 2/7), **blows the S3/S4 gate (x 1-15, z 48-51)**,
  and stands at an S3 lever for the door.
  - Cost: 1-2 items. This is the biggest single saving on the table, because it takes S3's device
    and gate off the critical path at once.
- The **two S3 lever players** (or whoever will do them) enter S3 in the free window before the
  S2 door and stand at the levers (2,123,56) / (14,123,56), 0-1 items each. That removes the
  ≥90 ticks of walking.
- The last S2 terminal is the tail (66 ticks after the one before, median). Put the fastest
  terminal player on the terminal furthest from the S1/S2 door.

### Section 3 (4 terminals, 2 levers; Arrow Align already credited; gate already down)

- Levers go at the door. Terminals can't finish sooner than 42 ticks, so four terminal players
  standing at their terminals set the section's floor.
- The **S4 lever player** (84,122,34) / (86,129,46) enters S4 in the free window before the S3
  door (0-1 items). The S4 levers are otherwise ≥84 ticks from the door, and a lever was S4's last
  completion in 18 of 45.

### Section 4 (4 terminals, 2 levers; target device already credited; no gate)

- Levers at the door, terminals from +42.
- Everyone not doing S4's last terminal stands at the core door (gold at x 52-56, z 54). It turns
  to barrier with "The Core entrance is opening!" and to air **19 ticks later**.
- **Core**: all five in the core box (x 39-71, z 54-118) as the door opens. Goldor leaves the
  tick the last one is in (median 18 ticks after the opening, best 4-10). Kill him in flight.

## Item budget (3 each)

| player's jobs | S1 | S2 | S3 | S4 | total |
|---|---|---|---|---|---|
| i4 (S4 device in S1) | 2-3 | | | | 2-3 |
| l+ee2 (Lights + S2 lever) | 0-1 | | | | 0-1 |
| 21 / 43 (pre-enter S2) | 0-1 | | | | 0-1 each |
| ee3 (Arrow Align, S3/S4 gate, S3 lever) | | 1-2 | | | 1-2 |
| S3 lever players | | 0-1 | | | 0-1 each |
| S4 lever player | | | 0-1 | | 0-1 |

Everyone except i4 stays at 2+ items spare. So a mistimed door (pre-entered players crossing an
extra death tick) is survivable, and the plan still works with one person short an item. Assign
i4 to whoever carries all three off cooldown at the start. The rotation's mask checks already
enforce this for its early-enter roles.

## What it adds up to

- S1 ≈ 236-240 (Simon Says).
- S2 about 110-130: Lights pre-done, three terminals and the lever ready at the door. The best
  recorded is 109.
- S3 about 100-130: device and gate done, levers and terminals staged; the 42-tick terminal floor
  plus the next terminals. Best recorded 129.
- S4 about 95-110 (best 93).
- **P3 ≈ 560-610**, against the fastest recorded 681 and the median 1000. Then the core rush and
  the kill.

This is an estimate that stacks the measured floors. No recorded run did all of it. The inferred
parts are how long Lights and Arrow Align take once you are there, and whether S4's device can be
done in fewer death ticks.

## What would sharpen it

- **A death-tick countdown HUD** (ticks to the next n = 60k), so the free windows are hit exactly.
  Engineer Client can add one next to Storm Phase's counter.
- **Boss Recorder runs** ([boss-recorder.md](../boss-recorder.md)) would give:
  - the exact zone edges (Hypixel's boundary is a little tighter than the section boxes at x ≈ 19
    and z ≈ 121);
  - the invincibility duration (under 58 ticks here);
  - how long the S4 device really needs.

## Sources

goldor.md (sections, doors, gates, death ticks, Goldor, the core), plus measurements from the same
recordings for this page: proc chains at consecutive death ticks (169 cases), procs per player
(320 player-P3s), early-credit tests (levers in 10 runs, Arrow Align in 3), section critical
paths (53 runs with completion lines), and first completions after each door (45-46 runs).
