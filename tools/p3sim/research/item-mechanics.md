# Hypixel item mechanics — extracted from PrecisionSnipes (read-only reference)

Source repo: `/home/cam/Projects/PrecisionSnipes` @ `33e02d79`. Paths below are relative to it.
- `EWS` = `src/main/java/com/precisionsnipes/EtherWarpSimulator.java`
- `STS` = `src/main/java/com/precisionsnipes/ShortTeleportSimulator.java`
- `BLM` = `src/main/java/com/precisionsnipes/BlinkLawModel.java`
- `SBT` = `src/main/java/com/precisionsnipes/SweptBodyTeleportModel.java`
- `GT`  = `docs/DUNGEON_GROUND_TRUTHS.md`
- `CFG` = `src/main/java/com/precisionsnipes/PlayerControllerConfig.java`

Confidence tags are PrecisionSnipes' own: **[CONFIRMED]** = in-game/measured; **[UNVERIFIED]** = modelled or second-hand.
Their models are *client-side predictors* of server behaviour, fitted to many thousands of recorded casts. For a server-side sim that's fine: we implement the predictor as the authoritative rule.

---

## 0. Things shared by every teleport

| Fact | Value | Cite |
|---|---|---|
| Landing X/Z | always block centre `x+0.5, z+0.5` | GT:2330 MOVE-Q21 [CONFIRMED] |
| Landing Y | integer for blinks; etherwarp acks at `block.y + 1.05` then client falls to the real surface | GT:2330, GT:2331 MOVE-D1, EWS:84-101, EWS:296-299 |
| Look direction | **not changed** by any teleport (yaw/pitch kept) | GT:1092 MOVE-Q43 [CONFIRMED]; GT:2331 (RustClear "preserves look direction") |
| Velocity | **whole vector zeroed** (H and V) on any teleport that moves you; **sprint state survives**; a refused/no-move cast keeps momentum | GT:1196 MOVE-Q27 [CONFIRMED] |
| Entities | never stop/shorten a teleport — block query only (block entities like chests are blocks and DO stop it) | GT:2665 MOVE-Q46b [CONFIRMED] |
| Rotation used | the yaw/pitch from the **last movement packet the server received**, not the client's camera | GT:1091 MOVE-Q16 [CONFIRMED], GT:3274 SRV-D3 |
| Origin used | server's last-received position (see §5 on the one-tick lag) | GT:1502 MOVE-D12 |
| Delay | **none** — server executes instantly on the use-item packet; ack is a normal `ClientboundPlayerPositionPacket` | GT:3276 SRV-Q11 [CONFIRMED], GT:3275 SRV-D4 |
| Knockback | players are KB-immune in Skyblock | GT:2334 MOVE-Q22 |
| Refusal message | `"There are blocks in the way!"` (exact) | GT:3272 SRV-D1 |

Look vector (vanilla, EWS:496-505):
```
dir = (-sin(yaw)*cos(pitch), -sin(pitch), cos(yaw)*cos(pitch))   // degrees -> radians
```

### Eye heights (GT:1056 MOVE-Q3, GT:1057 MOVE-Q31 [CONFIRMED])
- Etherwarp: **sneaking eye, feet + 1.27** (EWS:84). Explicitly NOT 1.54 (that's 1.8.9 RustClear drift).
- Forward blink (AOTV/Hyperion): **standing eye, feet + 1.62** (STS:51).
- A **crouched Hyperion still blinks** (Hyperion has no etherwarp) and rays from **1.27** (STS:56-90, `eyeHeight(sneaking)`).
- AOTV: sneak ⇒ etherwarp, not sneaking ⇒ blink. Hyperion: always blink.
- For MC 26.1 in our sim: just use `player.getEyeY()` with the real pose — vanilla 1.21+ crouch eye is 1.27, standing 1.62. That is exactly what they measured.

---

## 1. Etherwarp (sneak + right-click AOTV)

### Constants
| Name | Value | Cite |
|---|---|---|
| MAX_RANGE | **61.0** along the 3D ray (= base 57 + the permanent cheap "tuned transmission" upgrades; tooltip-readable) | EWS:56, GT:921 MOVE-Q1 [CONFIRMED, corrected from 57] |
| Range formula (reference) | ZeroPingHyperion derives `56 + tuned_transmission` for etherwarp, `8 + tuned_transmission` for blink | SBT:68-74 (note the 56 vs their 57 base; they hardcode 61) |
| EYE_HEIGHT | 1.27 | EWS:84 |
| LAND_Y_OFFSET | 1.05 (feet = hitBlock.y + 1.05; server ack value) | EWS:91, GT:2331 MOVE-D1 [UNVERIFIED but corroborated by RustClear] |
| SETTLED_FULL_TOP | 1.0 (client then falls to real surface: slab 0.5, carpet 0.0625…) | EWS:101, GT:2332 MOVE-D2 |
| CORNER_EPS | 1e-4 | EWS:252 |
| MIN_STANDABLE_TOP | 0.03 | EWS:762 |
| INTERACT_REACH | 4.5 (vanilla `block_interaction_range`) | EWS:807 |
| SKY_SCAN_UP | 128 (dungeon-only rule) | EWS:438 |

Recommend for the sim: `range = 57 + tunedTransmission` (default tuners = 4 → 61).

### Raycast — voxel DDA, NOT a fixed step (EWS:234-288)
Cell-based Amanatides–Woo DDA from the sneak eye, with a corner guard:
```
eye = feet + (0, 1.27, 0); dir = lookDir(yaw, pitch)
b = floor(eye); step = sign(dir); tDelta = 1/|dir|; tMax = distance to first boundary per axis
repeat up to 250:
    t = min(tMax.x, tMax.y, tMax.z); if t > MAX_RANGE: return MISS
    cX = tMax.x <= t+1e-4; cY = ...; cZ = ...
    if (cX+cY+cZ) >= 2:                       // CORNER/EDGE crossing
        if cX and stops(b.x+sx, b.y, b.z): return that cell
        if cY and stops(b.x, b.y+sy, b.z): return that cell
        if cZ and stops(b.x, b.y, b.z+sz): return that cell
    advance every tied axis together (enter the true diagonal cell)
    if stops(b): return b
```
Note the origin cell itself is never tested by the DDA; a solid block at the eye is handled separately (eye-obstruction, below).
The corner guard ("server clips a solid block on EITHER shoulder of a diagonal") is GT:2333 MOVE-D3 [UNVERIFIED] but was user-reported live (EWS:256-265).

### What stops the ray: `isPermeable` (EWS:671-745) — GT:1074 MOVE-Q4, GT:1075 MOVE-Q5 [CONFIRMED]
```
raySpecialCase(block):
  +1 (PASS THROUGH, never a target): Skull/Head, Ladder, FlowerPot, Button, Lever
  -1 (STOPS despite no collision):   Sign (all), Banner (all), TripWireHook   [tripwire STRING passes]
   0 -> stops iff collisionShape(CollisionContext.empty()) is non-empty
```
So: air, fire, grass, flowers, torches, vines (empty collision), cobweb (empty collision — MOVE-Q6), water/lava (empty collision) all pass. Any non-empty collision (full, slab, stair, fence, pane, bars, carpet, snow layer, hopper…) stops it and is treated as a full cell.

### Landing validity — `landingRefusal` (EWS:383-416), in order
Ray must hit a cell `b`; then refuse if:
1. **`unstableLanding(b)`** (EWS:886-916):
   - `b` is Sign/Banner/TripWireHook (ray stops but nothing to stand on);
   - `b` is Skull/Ladder/FlowerPot (guard; ray passes these anyway);
   - block **above** `b` is Skull/Ladder/FlowerPot ("skull means the block under it is not a valid etherwarp target", GT:2464 MOVE-D7 correction [CONFIRMED]);
   - `blockTop(b) < 0.03` — no collision under the cell centre (open/side trapdoor, zero-height snow). Carpet (0.0625) IS valid (GT:2485 MOVE-D11 [CONFIRMED]).
2. **Headroom** `hasLandingHeadroom` (EWS:425-431): `need = blockTop(b) > 1.0 ? 3 : 2`; cells `b+1 .. b+need` must all be body-passable. This is the "2 air above" rule; walls/fences (top 1.5) need 3. GT:2464 MOVE-D7 [UNVERIFIED].
   - Body passability `isPassable` (EWS:692-757): Sign/Banner/TripWireHook = passable; Skull/Ladder/FlowerPot = NOT passable; otherwise empty collision ⇒ passable. Buttons/levers passable.
3. **Sky escape** (dungeon-only, EWS:451-466, GT:1086 MOVE-Q11): nothing ray-stopping in the 128 cells above `b` ⇒ refuse. This is a bot-safety rule, not a server rule — probably skip in the sim (boss arena may be open-roofed).
4. **Click hijack** (client-side, see below) — the click never becomes a cast.

`blockTop(b)` (EWS:141-151) = max `maxY` over collision AABBs that contain the centre column (x=0.5,z=0.5 inclusive); fallback overall max Y.

### Landing position (EWS:296-299)
```
feet = (b.x + 0.5, b.y + 1.05, b.z + 0.5); yaw/pitch unchanged; velocity = 0
```
Client then falls to `b.y + blockTop(b)`.

### Failure cases
| Case | Behaviour | Cite |
|---|---|---|
| Ray hits nothing within range | no teleport | EWS:370-381 |
| Invalid landing (headroom / unstable) | refused ("blocks in the way" family) | EWS:383-416 |
| Head/eye inside a ray-stopper (bars, pane, fence, sign, tripwire hook) | ray self-hits → "There are blocks in the way!"; no aim fixes it | GT:1078 MOVE-Q8 [CONFIRMED], EWS:540-595 |
| Crosshair on an interactable within 4.5 | vanilla block-use fires instead; item-use never sent (client side; sneaking does not exempt) | GT:1079-1081 MOVE-Q9/Q10/Q48 [CONFIRMED] |

Eye-obstruction sampling (EWS:585-595, GT:2483 MOVE-D9 [UNVERIFIED]): test cells at eye Y ∈ {feet+1.27, feet+1.62} at XZ offsets {(0,0),(±0.32,0),(0,±0.32)}; any `!isPermeable` ⇒ obstructed. Server-side, simpler equivalent: test whether the eye cell is a ray-stopper.

Hijacker set (EWS:861-880, GT:1080 MOVE-Q10): chest/trapped chest, ender chest, hopper, dispenser/dropper, brewing stand, furnace/smoker/blast, barrel, crafting table, enchanting table, anvil, cauldron, doors (incl. iron), trapdoors (except iron), fence gate, repeater, comparator. NOT beacon/jukebox. For blinks also buttons and levers (EWS:857). Skulls/flower pots/ladders hijack via crosshair outline (MOVE-Q48). In our sim this is naturally reproduced if the client's vanilla `useItemOn` takes precedence; the server only needs to cast when it receives `ServerboundUseItemPacket`.

### Other etherwarp facts
- One etherwarp can pass through several open doorways (GT:1089 MOVE-Q14).
- Any visible face point works (GT:1088 MOVE-Q13).
- A partial block (bottom slab) blocks the sightline like a full cube (GT:2299 MOVE-Q30b).
- Hyperion cannot etherwarp (GT:922 MOVE-Q2).

---

## 2. Instant Transmission / forward blink (AOTV right-click, not sneaking)

Model: `STS` header (STS:14-41), shipped law model `BLM.predict` (BLM:217-445), legacy fallback `STS.clampLandingLegacy` (STS:303-386). Fitted on 14,408 rig casts + ~9–38k dungeon casts.

### Constants
| Name | Value | Cite |
|---|---|---|
| AOTV_RANGE | **12** (= 8 + 4 tuners; ZPH: `8 + tuned_transmission`) | STS:47, SBT:68-74, GT:922 MOVE-Q2 [CONFIRMED] |
| HYPERION_RANGE | **10** | STS:48 |
| Eye | 1.62 standing (1.27 if crouched Hyperion) | STS:51, STS:85 |
| Sub-step (segment march) | 0.25 | CFG:7882 `sweptTeleportMarchStepBlocks` |
| Same-column min move | 1.5 | CFG:7938, GT:2346 MOVE-Q23 |
| Quarter-box inflation (graze) | 0.25 | CFG:8058 `blinkQuarterBoxInflation` |

### Ray-stopper for blinks — `levelBlocker` (STS:132-165), different from etherwarp!
```
blocks(cell):
  if Sign or Banner: return false                     // MOVE-Q7: signs pass the blink
  if !isPermeable(cell) and blockTop(cell) >= 0.5: return true   // carpets/sub-half pass (MOVE-D14)
  return blockTop(cell.below) > 1.0                   // fence/wall poking up from below occludes
```
(skull/ladder/flowerpot/button/lever pass, as for etherwarp, via `isPermeable`.) Server stops on the **cell**, not the collision shape: a slab blocks its whole cell (GT:2637 MOVE-D14 [CONFIRMED]).

### Feet passability — `levelPassable` (STS:171-178)
```
passable(cell) = (isPassable(cell) or blockTop(cell) < 0.5) and blockTop(cell.below) <= 1.0
```

### Algorithm (simplest faithful version = legacy + segment march; BLM stage 1 + `resolveAt`)
```
eye = feet + (0, eyeH, 0); dir = lookDir(yaw,pitch); steps = floor(range)
last = none; (px,pz) = floor(eye.x, eye.z)
for i in 1..steps:
    # MOVE-D14: whole segment (i-1, i] clear, sampled every 0.25
    for k in 1..4: q = eye + dir*((i-1)+k*0.25); if blocks(floor q): goto done
    p = eye + dir*i; c = floor(p)
    if c.x != px and c.z != pz and blocks(px,c.y,c.z) and blocks(c.x,c.y,pz): goto done   # corner gate
    last = i; (px,pz) = (c.x,c.z)
done:
if last is none: REFUSE                                # blocked on first step: no move, no packet
p = eye + dir*last; c = floor(p)
feetY = passable(c.x, c.y-1, c.z) ? c.y-1 : c.y          # one below ray-end cell, even mid-air
land = (c.x+0.5, feetY, c.z+0.5)
if c.x == floor(feet.x) and c.z == floor(feet.z):        # MOVE-Q23 same X/Z column
    if feetY == floor(feet.y+0.05) or |feet - land| < 1.5: REFUSE
return land     # velocity zeroed, look unchanged
```
STS:356-386, BLM:236-275, BLM:448-462. Landing distance is always an **integer** number of ray steps (continuous stop is measurably worse: GT:2637, STS:316-322).

Note: BLM's live path ships with `blinkLawSegmentMarch=false` (CFG:7969) because it conflicts with rig punch-through laws; the legacy fallback uses the 0.25 march and the doc rates MOVE-D14 [CONFIRMED] for dungeon geometry. For a sim, the segment march is the better default.

### Additional refusal rules (all [CONFIRMED] unless stated)
- **No floor needed / crosses any gap / may end mid-air** — then you fall (GT:983 MOVE-Q35, GT:1464 MOVE-Q30). Can go up or down (GT:923 MOVE-Q36).
- **Same X/Z column ⇒ refuse** (straight up/down casts) — MOVE-Q23 (GT:2346). Hyperion straight-down: blink refused but Implosion still fires at feet (GT:3040 MOB-Q3).
- **Won't cut a corner** — MOVE-Q24 (GT:2388), captured by the corner gate above. For a caster at a cell centre one block from a corner, boundary is `cos(yaw)*cos(pitch) = 0.5`.
- **Solid caster flank ⇒ always refuse** (618/618): if the cell orthogonally beside the caster, on the side the first step carries into, is solid — GT:2429 MOVE-D5. Implementation hint: if step 1 crosses into a new X (or Z) cell and the orthogonal neighbour cell of the caster in that direction at eye/feet height is solid, refuse.
- **Ring refusal R2** (BLM:318-332, rig-verified, one-sided on world axes): if a cell at (feetX, feetY, feetZ±1) is solid and the first sample lands in that same Z column with |Δx|=1 and is solid ⇒ refuse.
- **Graze/corner laws** (BLM:334-436): when no sample is solid but the ray passes within 0.25 of a solid cell edge, the landing is truncated (corner head-on → `ceil(t_corner)`, receding → one less; graze staircase → `floor(t_face)`). These are second-order (≈ <2% of casts); implement only if you want rig-level fidelity.
- "There are blocks in the way!" is also printed when the blink is merely **clamped short** by blocks, not only on full refusal (GT:3040 MOB-Q3) — informational.
- Sign/banner/tripwire hook at the eye: etherwarp self-hits, blink passes (EWS:634-655).
- Walls/fences 1.5 tall: eye ray (1.62) clears them by 0.12; the predictor ignores it (GT:2713 MOVE-Q47).
- **Partial-block landing**: blink onto carpet seats you at `y + 0.0625` (GT:2509 MOVE-D13); slab/stair/chest/hopper/skull cell is not passable ⇒ you stand on top.

### Click rate / double click
- AOTV has **no server-side cooldown** (GT:1380 SRV-Q15).
- **Two use packets in the same tick both register**: second blink from where the first put you, same look ⇒ up to 24 blocks (GT:2579 MOVE-Q42 [CONFIRMED]). Sim: process each `ServerboundUseItemPacket` sequentially, re-reading position.

---

## 3. Hyperion / Wither Impact (Necron blades: Hyperion, Astraea, Scylla, Valkyrie)

- Blink: **identical algorithm to §2 with range 10** (STS:48, BLM:166 "nothing is AOTV-specific"). Fires regardless of sneak; rays from posture eye (1.27 crouched / 1.62 standing) — MOVE-Q31.
- **Server cooldown 2 server ticks (≈100 ms)**: a second cast inside it is DISCARDED (no blink, no Implosion) — GT:1380 SRV-Q15, GT:1425 SRV-Q16 [CONFIRMED, measured 97.5–102 ms boundary].
- Mana: their latest commits note Hyperion "unanswered clicks were an empty bar" — casts fail silently when mana is insufficient (git log `0913320d`). Numbers not in the doc.
- **Implosion** (GT:42-56 load-bearing, GT:2972 MOB-Q1, GT:2975 MOB-Q2, GT:3192 MOB-Q14):
  - Detonates at your **final position** after the blink (or at your feet if the blink is refused/clamped to zero) — MOB-Q3.
  - AOE is a **box**: 6 blocks from the player's **head** (eye) to the closest point of the mob's **hitbox**, per axis independently. In cells ⇒ ±6 X, ±6 Z, +7 up, −6 down.
    `hit iff  |clamp(eye.x, bb.minX, bb.maxX) - eye.x| <= 6  (same for z)  and  same for y`.
  - **Ignores geometry** (through walls/floors), **no falloff**, **no mob-count split** — every mob in the box takes full damage.
  - Message prefix `"Your Implosion hit …"` (GT:3272 SRV-D1).
  - Exception: sleeping Fels not killable (MOB-Q16) — irrelevant to F7 boss.
- Teleport resets momentum; a no-move cast (into wall/floor) keeps it — so "Hyperion straight down" is a free AOE (MOVE-Q27).

---

## 4. Other items

PrecisionSnipes **does not model** Bonzo staff, Jerry-chine, Gyrokinetic wand, Spirit Leap, Ragnarock axe, Ice Spray, Terminator/bows, Infinileap or spring boots. Only mentions:
- **Ender pearl** (from RustClear reimplementation, [UNVERIFIED] on Hypixel, `analysis/2026-07-30-refrepo-candidate-ground-truths.md:258-262`, GT:3706): spawn at feet+1.62, `v = look * 1.5 b/t`; per tick `v.y -= 0.03; v *= 0.99; pos += v`; 0.25 hitbox colliding with any non-air block as a full cube (checks current+last AABB, no tunnelling); lands at `floor(last_pos) + (0.5, 1.0, 0.5)`; lifetime 200 ticks. (Vanilla pearl physics are similar; prefer vanilla in the sim.)
- Pearls still work in ability-disabled rooms (GT:3349, [UNVERIFIED]).
- Spirit Leap / Inflatable Jerry appear only as secret-pickup item names (GT:61 ENV-Q5).
- Terminator only mentioned as "a different AOE weapon" (docs/annotated/CombatShared.md:5).
- RustClear's "door interior blocks abilities" comment is NOT wired to anything (refrepo:14-20) — open doors don't block abilities.

---

## 5. Packet timing / rubber-band / ping

- **No server-side delay**: both AOTV moves and the Hyperion execute instantly (GT:3276 SRV-Q11 [CONFIRMED]). The measured ~13 t "hop cost" was the bot's own aim wait + RTT + settle (GT:3313 SRV-D5).
- **Ack** = `ClientboundPlayerPositionPacket` (absolute X/Y/Z; rotation unchanged). Their handler ignores corrections with d² < 1.0 as server nudges (GT:3275 SRV-D4). Ack typically within ~1–2 ticks of click; 12 ticks of silence ⇒ the click did nothing (SRV-D5).
- **Refusal sends no position packet** — just chat "There are blocks in the way!" (STS:36-38, MOVE-D4 GT:2428). Chat vs position packet ordering for the same click is not guaranteed (GT:3314 SRV-D6).
- **Rotation**: last rotation received in a movement packet (MOVE-Q16). The client sends a movement/rotation packet then the use packet in the same tick, so server uses the current-tick rotation if the client sent it before the click. Whether a turn AFTER the click can redirect is an OPEN question (GT:1092 MOVE-Q43).
- **Origin lag** (GT:1502 MOVE-D12): server resolves from the position it last received. Old builds: one tick old (94% fit). Newer builds: lag 0 on ground, lag 1 mid-air — attributed to where in the client tick the click is issued relative to the move packet, i.e. purely packet ordering. **For a sim: use the server's current player position at the moment the use packet is processed** — that reproduces both regimes naturally.
- **Click-rate**: AOTV unlimited (two clicks in one tick both apply, MOVE-Q42); Hyperion 2-server-tick cooldown (SRV-Q15/16). Their bot self-limits to 20 CPS with randomised gaps (anti-autoclicker, SRV-Q14) — not a server mechanic.
- **Momentum**: zeroed on a moving teleport; sprint kept (MOVE-Q27). In a sim send the position packet with zero delta movement / set `deltaMovement = 0` server-side.
- Hotbar: never swap more than once per tick (ban risk, SRV-Q13) — client-side bot rule.

Rubber-band: nothing in the repo describes etherwarp rubber-banding beyond the ack being authoritative and the client falling onto partial surfaces after the 1.05 ack.

---

## 6. F7 boss ground truths

`DUNGEON_GROUND_TRUTHS.md` is a **clear-phase** (rooms) document; it contains **no F7 boss (Maxor/Storm/Goldor/Necron, terminals, P3) facts**. Boss-adjacent items only:
- Boss room sits outside the dungeon map box (GT:75 GEO-Q2).
- Boss door is inside the blood room; blood mobs spawn ~80 s after blood opens (GT:871-877).
- Blood giant spawns 14–17 s after blood door opens (GT:3267 SRV-Q8).
- Movement physics are exactly vanilla with movement-speed attribute raised: sprint **1.5434 b/t**, walk 1.1872 b/t (= vanilla × 5.5, speed attribute 0.55 vs 0.10); speed range 100%–650%; air movement near-vanilla; step height 0.6 (GT:58 MOVE-Q26, GT:2328 MOVE-Q19, MOVE-Q32/Q33).
- Acceleration ramp from rest ≈ 1.42 blocks / 1.2 ticks shortfall (GT:1159).
- Players are knockback-immune (MOVE-Q22); survival is not a concern at their gear (ENV-Q9).

---

## Quick discrepancies to be aware of
1. Etherwarp base: they say 57 (+4 = 61); ZPH reference says `56 + tuned_transmission`. Tooltip is authoritative; 61 is CONFIRMED in-game for a fully tuned AOTV.
2. Blink occlusion: ray-stopper differs from etherwarp's (signs/banners pass, sub-half blocks pass, tall block below occludes).
3. Etherwarp uses a true voxel DDA; blinks use **integer distance samples** (with 0.25 sub-samples for occlusion). Don't unify them.
4. Landing Y: etherwarp `b.y + 1.05` (ack); blink integer feet Y (one below ray-end cell if passable, else that cell).
