# P3 Goldor: the fight's flow (dialogue, gates, the core, the hand-off to Necron)

Builds on `docs/mechanics/goldor.md` (track, speeds, sections, death ticks; all re-checked here and
still right) with what it left open. **M** = measured, **C** = conjecture. `n` = server ticks since
`[BOSS] Goldor: Who dares trespass into my domain?` (Better PF chat `n` / `st` records; Boss
Recorder `net` ticks).

Sources: 120 recent complete F7 Better PF runs (114 unique fights after merging party members'
recordings of the same run; 40 of them keep every progress line), the 60 Boss Recorder fights with
a P3. Scripts: `/home/cam/backups/2026-10-03/analysis/goldor/` (`sections.mjs` -> `sections-out.json`,
`sections-agg.mjs`, `taunts.mjs`, `taunts2.mjs`, `end2.mjs`, `seq.mjs`, `br-endwither.mjs`,
`br-path.mjs`, `br-gatesnd.mjs`, `br-agg.mjs`, `br-sounds.mjs`).

## 1. Goldor's lines are a queue (M)

- Every non-death-tick Goldor line is at least **62 ticks** after the one before: of 932 gaps,
  705 are exactly 62 and none shorter (the rest are longer: nothing was waiting). The intro is
  0 / 62 / 124 / 186 in 114 of 114 runs.
- The section line (`The little ants have a brain it seems.` / `I will replace that gate with a
  stronger one!` / `YOUR END IS NEAR!!`) is queued when the section's **door** opens (the later of
  its last completion and its gate; same tick as `The gate has been destroyed!` when the gate is
  last) and said at `max(door, previous line + 62)`: after S1 a median 79 ticks late (0-183,
  behind the taunts), after S2 / S3 a median 1. Exactly 3 per run (one per door S1-S3), and **which
  of the three is random**, not tied to the section: S1 34/46/34, S2 39/27/48, S3 40/38/36.
- **Taunts** (10 lines: `Do you really think we won't repair everything? Your impact will be
  minuscule!`, `Come closer!`, `You are breaking precious materials, unforgivable.`, `CLOSER!`,
  `There is no stopping me down there!`, `I am the death zone, you are smart to flee.`, `You can't
  damage me, you can barely slow me down!`, `Slowing me down only prolongs your pain!`, `Closer to
  me!`, `Stop touching those terminals!`): each 13-26 times in 114 runs, i.e. about equally likely.
  1-3 queue up during S1 so they come right after the intro, at 248 (112 of 114 runs), 310...;
  counts before the first section line: 0: 2, 1: 47, 2: 57, 3: 8 runs. Later ones (S2-S4) in 39
  runs (1: 31, 2: 6, 3: 2). The same taunt can come twice in a run. What triggers them is not
  visible in the recordings (no correlation with gates, own terminals or death ticks) - C.
- The death-tick line `What do you think you are doing there!` is **not** in the queue: n mod 60 =
  59 (190) or 58 (79).

## 2. The two endings (M, 114 runs: 100 killed in flight, 14 reached the core)

- **Killed in flight**: `....` at the kill; 82 ticks later `Necron, forgive me.` and then, in the
  same tick, `[BOSS] Necron: You went further than any human before, congratulations.` (same tick
  in all but 1 of 71 such recordings in `end2.mjs`).
- **Reached the core**: on arrival `You have done it, you destroyed the factory...` is queued with
  `But you have nowhere to hide anymore!` and `YOU ARE FACE TO FACE WITH GOLDOR!` (+62, +124), and
  `....` queues behind them on the kill (so +186, or the kill if later: one run killed at +243 had
  `....` there). `Necron, forgive me.` is still `....` + 82 (82-88), i.e. **well into P4**
  (+268 after arrival), while Necron's first line comes 82 after the kill (+91..+135 after arrival).
  The old sim said `Necron, forgive me.` with Necron's line here: wrong.
- Necron's line is always kill + 82 (81-83).
- After arriving alive Goldor goes on **into the core**: 0.4 blocks/tick along x 53.4-53.8 to z
  ~55.8-56.4 (just inside the door at z 54), then ~0.07/tick, until killed (Boss Recorder, 4 fights).
  Arrival points (53.2-54.5, 117.0, 40.0-40.6).
- Dead, he stops where he is (no death animation, no health 0) and stays until **~290 ticks after
  Necron's first line** (279-307 in the 9 fights that kept him in range).
- The floor under the core (`p3end`, 729 blocks) goes 1-2 ticks before Necron's line.

## 3. Boss bar and armour (M, Boss Recorder)

- `§c§lGoldor`, progress **1.0** from his first line until he leaves the track for the core (not
  at the core opening); then down as the party hits him, in ~20-tick steps (Hypixel resends the bar
  about once a second), e.g. `1.0, 0.86, 0.29, 0.25, 0.21, 0.0`, `1.0, 0.63, 0.0`, reaching 0.0 at
  the kill (sometimes 0.01) and staying there until Necron's bar replaces it.
- His health is 1000 or 300000 (**no blue armour**) the whole phase, the core included; in the
  core it flips to 1.0 for single ticks on some hits (hurt packets every 1-14 ticks from when he
  leaves). The old sim put his armour on at the core opening: wrong.

## 4. Sounds (M, Boss Recorder)

- Every progress line: `block.note_block.pling` v8 p4.048 (1760 of 1760).
- `The gate has been destroyed!`: the same pling (164 of 164 with no progress line within 2 ticks),
  plus `entity.generic.explode` v0.5 p0.49 at the gate (heard in 49).
- Every `[BOSS] Goldor` line: `entity.wither.ambient` master v5 p1.19.
- `The gate will open in 5 seconds!` and `The Core entrance is opening!` always share their tick
  with the last progress line, so their own sounds (if any) can't be told apart.

## 5. Chat order in one tick (M)

`<P> activated a terminal! (7/7)` -> `The gate will open in 5 seconds!`; then on the gate: `The
gate has been destroyed!` -> `[BOSS] Goldor: <section line>`. 5-second gates: players blew it
4-58 ticks later in 9 cases, it went by itself at +100 in 1 (with `The gate has been destroyed!`).

## 6. Not established

- Titles / subtitles: neither recorder captures them.
- What triggers each taunt; Goldor's health/hit count; the TNT trap trigger.
