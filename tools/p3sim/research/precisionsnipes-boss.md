# PrecisionSnipes vs p3sim: F7 boss and movement physics

Source: `~/Projects/PrecisionSnipes` (Andrew's mod, read-only). Compared against
`src/main/kotlin/com/engineerclient/p3sim/`. Companion to `item-mechanics.md`, which covers
etherwarp, blink and Hyperion in detail. This file doesn't repeat that material. It only checks
that the sim uses those rules.

Abbreviations: **GT** = `docs/DUNGEON_GROUND_TRUTHS.md`, **PS** = `src/main/java/com/precisionsnipes/`.

---

## 0. Headline: PrecisionSnipes has no F7 boss code or data

This is a negative result, and I checked it several ways because the assumption was that "a lot of time
was put in there for boss":

- **The boss room is excluded on purpose.** In `PS/RoomCaptureAudit.java:157-160`, `NEVER_CAPTURABLE`
  says: *"Andrew, 2026-08-20: the boss room is an ENTIRELY different case and belongs to a different
  part of the project ... nothing reading this corpus wants it."* → `"Bossroom", "the boss room — a
  different subsystem entirely, deliberately out of this corpus"`. `PS/RegionYClamp.java:96-101` and
  `PS/DungeonComponentScanner.java:1016-1032` treat the boss room only as "left the dungeon grid".
  Neither runs any logic there.
- **No boss vocabulary anywhere** (Java, Markdown, JSON, resources, excluding `.git`/`build`):
  - Zero hits for `goldor`, `pylon`, `simon says`, `arrow align`, `lights device`.
  - The only `maxor` hits are `AimPurpose.maxOrdinaryPriority()`.
  - The only `necron` hits are "Necron's blade" = the Hyperion family (GT:60 ENV-Q1).
  - The only `wither king` hit is a mob-height filter (`PS/VerifyCapture.java:222`).
  - Every `storm` hit means a "retry storm".
- **"terminal" means terminal velocity** in all ~590 hits (`sprintTerminalBpt`, `TERMINAL_BPT`,
  `groundTerminalBpt`, `terminalClearNodeId` = the last node of a route). It never refers to an F7
  terminal GUI.
- **Leap, Bonzo and Jerry appear only as secret-item names.** "spirit leap" and "inflatable jerry"
  are listed in `PS/DungeonSecrets.java:41` and GT:61. Bonzo has zero hits.
- **No boss work in the history either.** `git log --all --grep 'boss|goldor|terminal|necron'`
  returns only miniboss and router commits.
- "F7" (906 hits) is the floor the *clear* bot runs. The sidebar-floor parse is at GT:3261 SRV-Q2.

So for every P1/P2/P3/P4 item in the brief (terminal, lever, device and safe-spot positions; device
solver expectations for arrow align, i4, lights and Simon Says; terminal click and GUI timing; leap
behaviour; P1 Maxor, P2 Storm and P4 Necron mechanics), **PS lacks it entirely**. There is nothing
to compare. The sim's own sources (Better PF recordings, `goldor.md`, `terminals.md`, `arena.md`,
Odin) are the only references, and PS can't confirm or contradict them.

What PS *does* hold that applies in the boss fight is player and item physics. That comparison follows.

---

## 1. Movement constants

| Mechanic | PS value (file:line) | Status | Sim |
|---|---|---|---|
| Physics are vanilla, only the speed attribute differs | GT:1147 MOVE-Q25 [CONFIRMED]; GT:1163 MOVE-Q32 [CONFIRMED] (derived from MC 26.2 source) | **Matches** | The real player runs vanilla server physics. `Fight.kt:102` sets `MOVEMENT_SPEED = speed/1000`. |
| Vanilla constants | `PS/VanillaMovement.java:104-314`: friction 0.6, horizontal air drag 0.91, input scale 0.98, sprint ×1.3, sneak 0.3, base speed 0.1, jump 0.42, gravity 0.08, vertical drag 0.98, step 0.6, half-width 0.3, height 1.8, air accel 0.02 walk / 0.026 sprint, sprint-jump boost 0.2 | **Matches** (implicitly) | Vanilla engine. Step height is set explicitly to 0.6 at `Fight.kt:104`. |
| Measured ground speed | Sprint **1.5434 b/t**, walk **1.1872 b/t** at attribute 0.55 (×5.5). Sprint attribute reads 0.715 = 1.3×. GT:63, GT:1149-1156 MOVE-Q26 [CONFIRMED] | **Matches in scaling** | At the sim default of 500 → 0.2806×5 = **1.403 b/t**. That agrees with the slider text "1.40 blocks a tick" (`P3Sim.kt:50`). |
| Speed range | **100 % to 650 %** hard bounds. 4.0-6.5 in 0.5 steps are all played, and 5.5 is a choice, not a cap. GT:2280-2298 MOVE-Q33 [CONFIRMED] | **Differs** | `P3Sim.kt:50`: slider 100..600, default 500, described as "500 (the cap)". PS says 650 is reachable and that 500/550 is not a cap. Note that PS measured in the clear, not the boss. |
| Acceleration ramp | `0.539 0.833 0.994 1.082 1.130 …` → terminal in ~10 t, shortfall ≈ 1.42 blocks ≈ 1.2 ticks per standing start. GT:1159 [CONFIRMED] | Player **matches** (vanilla). Bots **differ** | Bots: `Party.kt:165-169` uses `5.3 + 1.06·h + 0.78·climb` ticks, i.e. a steady **0.94 b/t** plus 5.3 t overhead. PS's straight-line sprint at 500 is 1.40 b/t with only a ~1.2 t ramp. Caveat: the sim's fit is from Better PF paths, which include turns, so it isn't necessarily wrong. Coincidentally, 0.94 b/t is exactly the offline constant PS rejected as "39 % low" (GT:1162). |
| Velocity field under-reads | `getDeltaMovement()` × 1/0.546 = real travel. Fit against position only. GT:1160 MOVE-Q32 | n/a | This only matters if anyone fits speeds from `deltaMovement` in recordings. |
| Gaits | Only sprint and sneak. Sprint is forward-only, so strafing drops to walk speed (−23 %). GT:66 MOVE-Q28, GT:1161 | **Matches** (vanilla) | — |
| Knockback | **Players are knockback-immune in SkyBlock**, from any source. GT:2334-2344 MOVE-Q22 **[UNVERIFIED]** | **Matches** | `Fight.kt:103` sets `KNOCKBACK_RESISTANCE = 1.0`. Bonzo and Jerry set `deltaMovement` and `hurtMarked` directly (`SimItems.kt:409-412`), so they bypass the resistance, as intended. |
| Survival | Not a concern at end-game gear; every death is "stuck". GT:68 ENV-Q9 | Compatible | `player.isInvulnerable = true` (`Fight.kt:105`). |

## 2. Fluids, lava, falling

| Mechanic | PS (file:line) | Status | Sim |
|---|---|---|---|
| Vertical per-tick, by medium | Air `vy'=0.98vy−0.0784` (terminal −3.92). Water `0.80vy−0.005` (−0.025). **Lava `0.50vy−0.02` (settles −0.040 b/t, 25 t/block)**. Feet-only lava ×0.8, 0.025 → −0.125. Climbables clamp to −0.15. `PS/MovementRecorder.java:167-169`; `analysis/2026-08-06-vertical-mechanics.md:85-94` | Vanilla, so the sim **matches** where the arena has these blocks | — |
| Momentum carried into lava | Lava "forgets" ×0.5/tick: 14 ticks and 8.3 blocks. `vertical-mechanics.md:360` | Vanilla | — |
| Lava is walkable but slow | GT:2621-2636 MOVE-Q45 [CONFIRMED]. The multiplier is left open (unmeasured). | Vanilla | — |
| **Hypixel lava bounce** (lava launches you upward) | **Not in PS.** PS models lava only as vanilla sinking. | **Both lack it** | The sim has no lava handling (grep: the only "lava" is a P4 timing comment at `P4Necron.kt:67`). If the boss arena's lava is meant to bounce, it has to be sourced elsewhere (Odin, recordings). |
| Fall distance | Ladders reset fall distance (`vertical-mechanics.md:130`) | n/a | `Sim.tp` zeroes `fallDistance` (`Sim.kt:69`). The player is invulnerable anyway. |

## 3. Teleports and items in the sim, checked against PS rules

The detail lives in `item-mechanics.md`. This table only checks that the sim follows each rule.

| Rule | PS | Sim | Status |
|---|---|---|---|
| A moving teleport zeroes the *whole* velocity vector; sprint state survives | GT:67, GT:1196-1212 MOVE-Q27 [CONFIRMED twice] | `Sim.kt:66-69`: `deltaMovement = ZERO` after `teleportTo` (used by etherwarp, blink and leap). Sprint is client-side and untouched. | **Matches** |
| A cast that *doesn't* move you keeps momentum | GT:1212 | `blink` returns before `Sim.tp` when `last == 0` or same-column (`SimItems.kt:369-373`) | **Matches** |
| "Momentum doesn't carry through teleports of any kind" (Jonah) | GT:1201-1202 | The Spirit Leap also goes through `Sim.tp` (`SimItems.kt:545-548`) | **Matches** (extrapolated to leap) |
| Etherwarp range 61, sneak eye 1.27, land at +0.5/+1.05/+0.5 | GT:28 MOVE-Q1, GT:1056 MOVE-Q3, GT:2331 MOVE-D1 | `SimItems.kt:298-316` | **Matches** |
| Blink AOTV 12 / Hyperion 10; integer distance; quarter-block occlusion | GT:29 MOVE-Q2, GT:2637-2663 MOVE-D14 | `SimItems.kt:353-379` (`k*0.25` sub-samples, integer `last`) | **Matches** |
| Blink refuses on the same X/Z column with total move < 1.5 | GT:2346-2386 MOVE-Q23 (`sameXZ AND move<1.5`) | `SimItems.kt:369` | **Matches** |
| Blink won't cut a corner | GT:2388 MOVE-Q24 | `SimItems.kt:365` (both shoulder cells must block) | **Matches** PS's prediction model, which gates on BOTH shoulders being solid (`PS/ShortTeleportSimulator.java:314-317, 384`). PS's "ANY shoulder" check (`:403-427`) is only a conservative *planning* filter. **The sim lacks the "solid caster flank ⇒ always refuse" rule (618/618, GT:2429 MOVE-D5)**: `blink` never looks at the caster's own orthogonal neighbour. |
| Landings are cell-centred X/Z with integer Y | GT:2330 MOVE-Q21 | Blink and etherwarp do this. **The leap doesn't**: `SimItems.kt:546` teleports to `bot.pos` exactly. | n/a for leap (PS has no leap data) |
| A blink needs no floor | GT:983 MOVE-Q35 | No floor check in `blink` | **Matches** |
| Entities don't stop a teleport | GT:2665 MOVE-Q46b | Ray checks blocks only | **Matches** |
| Crouched Hyperion still blinks, from the crouch eye | GT:1057 MOVE-Q31 | `blink(p,10)` uses `p.eyePosition`, which is the crouch eye when sneaking | **Matches** |
| Hyperion 2-server-tick cooldown; AOTV none; a double AOTV click registers twice | GT:1380 SRV-Q15, GT:1425 SRV-Q16, GT:2579 MOVE-Q42 | `hypeReady()` < 2 ticks (`SimItems.kt:382-389`). AOTV is unthrottled, with each packet handled by `afterPing`. | **Matches** |
| Implosion ignores geometry; it fires even when the blink is refused (a straight-down cast) | GT:51 MOB-Q2/Q3/Q14 | `implode(p)` runs after `blink` regardless (`SimItems.kt:212`). The box check has no line of sight. | **Matches** |
| Server uses the last *sent* rotation | GT:59, GT:1091 MOVE-Q16 | `asClicked` captures the rotation at packet time and replays it after the ping (`SimItems.kt:191-199`) | **Matches** |
| Teleports are instant server-side; latency is not an ability cost | GT:3276-3282 SRV-Q11 | The only delay is `Fight.afterPing`, i.e. the network | **Matches** |
| Never swap the hotbar more than once a tick; no fixed-interval clicking | GT:64 SRV-Q13, GT:65 SRV-Q14 | Client-side bot rules, not server mechanics | n/a |

## 4. Items PS doesn't model (the sim's values have no PS cross-check)

| Item | Sim | PS |
|---|---|---|
| Bonzo's Staff | Ray of 4 blocks; on a hit, horizontal 1.5 b/t away from the pop plus 0.5 up (`SimItems.kt:414-424`) | **Lacks** (0 hits) |
| Jerry-chine Gun | Ray of 5; pop within 3.5 of the feet → `vy = 0.9`, horizontal kept (`SimItems.kt:426-433`) | **Lacks** ("inflatable jerry" is only a secret name) |
| Spirit Leap / Infinileap | Menu slots 11-15, tp to the bot after ping. Bots land 8 t after, or 14 at a door (`Party.kt:174-181`, `SimItems.kt:527-549`). | **Lacks** |
| Wither Cloak, Superboom, Spirit Bow / Terminator, ender pearl | The sim's own values | Lacks, except an [UNVERIFIED] pearl model from RustClear (see `item-mechanics.md` §4) |

## 5. Boss phases (P1 to P4)

| Area | Sim file | PS |
|---|---|---|
| P1 Maxor: crystals, pylons, movement steps (`Phases.kt:166`) | `Phases.kt` | **Lacks** |
| P2 Storm: pads, beam, movement steps 0.40-0.9 (`P2Storm.kt:189-212`) | `P2Storm.kt` | **Lacks** |
| P3 Goldor: track walk 0.06 / sprint 0.60 / fly 0.80 b/t (`GoldorPhase.kt:421-423`), sections, gates | `GoldorPhase.kt` | **Lacks** |
| P3 terminal, lever and device positions; stand, safe and pre-enter spots | `Spots.kt`, `Station.kt`, `Devices.kt` | **Lacks** |
| Terminal GUIs and click timing | `Terminals.kt` | **Lacks** |
| Device solvers (arrow align, i4, lights, Simon Says) | `Devices.kt` | **Lacks** |
| Bot plan (leaps, levers 4 t, gates 9 t) | `Party.kt` | **Lacks** |
| P4 Necron | `P4Necron.kt` | **Lacks** |

## 6. Mismatches worth acting on

1. **Speed slider cap and wording** (`P3Sim.kt:50`): PS's confirmed range is 100-650 %, and 500 is not "the cap". Raise the max to 650 and drop "(the cap)", unless boss-specific evidence says otherwise.
2. **Bot walk speed** (`Party.kt:165-169`): the implied 0.94 b/t steady-state is about 33 % under the vanilla sprint at 500 (1.40 b/t), and PS's ramp is only ~1.2 t, not 5.3 t. Fine if the fit really includes path detours. Otherwise, model bots as ramp plus 1.40 × path length.
3. **Blink caster-flank refusal missing** (`SimItems.kt:353-379`): PS's MOVE-D5 refused 618/618 casts where the caster's orthogonal neighbour in the first step's direction is solid. It's likely to come up when blinking from next to a P3 wall. The corner gate itself (both shoulders) matches PS.
4. **Lava bounce**: neither PS nor the sim has it. If the boss arena's lava matters (P1/P2 floor, P4/P5), it needs another source.
5. **Leap landing**: the sim lands at the bot's exact position, not cell-centred. PS has no leap data, so a recording is the only check.
