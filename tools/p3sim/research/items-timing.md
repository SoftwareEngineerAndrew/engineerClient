# Item timing and feedback: measured from the recordings

Sources:
- **BPF**: own Better PF runs, 217 files in `f7/.../betterpf/runs/` (client ticks `t`, server ticks from `st`/`n`).
- **BR**: Boss Recorder, 77 files (recorders johnswizzlechang, p3wr, TheBadOne; mod 0.6.15). One packet stream a server tick.

Tags: **[M]** measured here (count given), **[C]** conjecture or outside knowledge, not in the data.
Scripts are in `tools/p3sim/research/scripts/`: `items_survey.py`, `leap.py`, `leap2.py`, `leap3.py`,
`bossrec_items.py`, `bpf_items.py`, `breaks.py`, `gate.py`, `gate2.py`, `cooldowns.py`, `arrows.py`, `files.py`, `peek.py`.

**Limits of the data, read these first:**
- Neither recorder logs your own **use-item** (right-click) packet. Delays measured from the right-click (menu
  open, etherwarp `tp`) cannot be measured. PrecisionSnipes reports no server delay: the cast happens on the
  tick the use packet arrives (item-mechanics.md §5). Container slot clicks (`slotclick`) *are* logged.
- BR 0.6.15 logs **no `tp` for you** (0 in all 77 files). Your teleports are found as jumps of more than 4 blocks between
  consecutive `me` packets. The client confirms a server teleport with a move packet on the same tick it
  arrives, so this gives the tp tick.
- Title and action-bar packets are not recorded. **Mana messages are action-bar text**, so there are none to quote.
- Item lore is not recorded (Better PF `slots` has only the item id, count, skin, plain name and glint).

---

## 1. Spirit Leap (INFINITE_SPIRIT_LEAP)

| Fact | Value | Tag |
|---|---|---|
| Held id when the menu opens | `INFINITE_SPIRIT_LEAP` (499 of the 695 opens; the rest are older runs with no held id). Item name `Infinileap`, `minecraft:player_head`, skin `ewogICJ0aW1lc3RhbXAiIDogMTY1MjE0NjYxMjc0Mi…` (hotbar slot 6 in every boss run, hotbars.md) | M |
| Right-click → menu open | not measurable. Expect 1 RTT (server opens it on the tick the use packet arrives) | C |
| Menu | title **`Spirit Leap`**, `minecraft:generic_9x4` (36 container slots), every non-head slot is `minecraft:black_stained_glass_pane` (empty name, no glint) | M (628 opens) |
| Head slots | 4 teammates → slots **11, 12, 14, 15** (13, the centre, is skipped; 474 menus). 2 teammates → **11, 12** (4 menus). Item `minecraft:player_head` with that player's skin. Name = the bare IGN (`TheBadOne`), no rank or colour in the plain name | M |
| Order | Not party order, not class order and not alphabetical (only 18 and 17 of ~490 menus happen to match those). The order is the same every time within a run. Use a fixed order per run | M / C |
| Dead teammates | **Ghosts are still listed** with a normal head (all 11 menus opened while a teammate was a ghost). `Unknown Player` showed up once in place of a teammate who had become a ghost (GrayPear), probably a disconnect | M |
| Click → teleport | slot click (`PICKUP`, button 0) → `tp`: client ticks {0:1, 1:128, **2:323**, 3:62, 4:9, 5+:11}. In server ticks (`st`): {0:16, 1:142, **2:280**, 3:73, 4+:23}. That is about 1 RTT at roughly 50 ms ping. **The server teleports on the tick the click arrives** | M (527) |
| Menu close | closes with the teleport: click → `guiclose` has the same distribution as click → tp | M |
| Clicks that did nothing | 11 of 538. No chat line, no teleport, menu stays open | M |
| Landing | **exactly the target's server position** (x, y, z). Offset from the target's last server position: (0.00, 0.00, 0.00) in 47 of 70 matched cases; the rest are ≤0.12 (the target's own move in flight) | M (BR) |
| Rotation | **set to the target's yaw and pitch**: yaw and pitch within 2° of the target's in 87 of 170 matches, and the rest are off only because the target's rotation was stale or quantised. It is not your own yaw (1 of 170) | M (BR) |
| Velocity | not measured. Treat it like other teleports (velocity zeroed) | C |
| Your chat | **`You have teleported to <IGN>!`** on the tp tick (BR: chat n − me-jump n ∈ {−1, 0}) | M (362+ in BPF) |
| Party chat | `Party > [MVP+] johnswizzlechang: Leaped to <IGN>!` 1–3 ticks later. This is **sent by a mod** (Odin's leap announce), not Hypixel. Other mods send variants: `Leaped to X` (no `!`), `Leaping to X`, `[Skyblocker] Leaped to X!`. A second mod's party message then fails with `Command Failed: This command is on cooldown! Try again in about a second!` (+1/+2 ticks, 78×). For a sim: optionally print the Odin line with your own name | M |
| Sound | `minecraft:entity.enderman.teleport`, vol 1.0, pitch 1.0, at the landing point, on the tp tick (363 of 366 leaps) | M (BR) |
| Cooldown | **2 s (40 ticks) from the leap.** Using it again prints `This ability is on cooldown for Ns.` with N = ceil(remaining/20): "1s" seen 20–39 ticks after the last leap (91×) and "2s" 3–6 ticks after (15×). The fastest leap-to-leap gap was 44 client ticks | M |
| Cooldown menu | once: title **`On cooldown!`**, 9x4, glass filler, a `player_head` named `On cooldown!` in slot 13 and `minecraft:barrier` named `Close` in slot 31 | M (1) |
| Invulnerability after the leap | no `hp`/`dmg` evidence was gathered. Don't add any | C |

## 2. Etherwarp / AOTV / Hyperion

| Fact | Value | Tag |
|---|---|---|
| Click → server `tp` | not measurable (no use packet in either recorder). PrecisionSnipes: executed on arrival, ack = position packet, no server delay (SRV-Q11) | C (PS-confirmed) |
| Failure chat | **`There are blocks in the way!`** (11,755× in BPF: 9,125 with HYPERION held, 2,522 with ASPECT_OF_THE_VOID). It arrives on the same tick as the failed cast (BR dt −1..0 against any following move) | M |
| Hyperion chat | **`Your Implosion hit 1 enemy for 1,234,567.8 damage.`** / **`Your Implosion hit N enemies for X damage.`** (singular `enemy` when N = 1; damage with thousands commas). Arrives on **the same server tick as the teleport** (dt 0 in 263 of 362). Sent only when ≥1 mob is hit | M |
| Hyperion sounds (tp tick, at the landing) | `entity.enderman.teleport` 1.0/1.0 + **`entity.generic.explode` 1.0/1.0** (the implosion) + `entity.experience_orb.pickup` 1.0/1.492 (hit ding, 746×). `entity.zombie_villager.cure` 1.0/0.698 sometimes | M (BR) |
| Etherwarp / AOTV sounds | `entity.enderman.teleport` 1.0/1.0 + **`entity.ender_dragon.hurt` 1.0/0.54** on the tp tick (dragon.hurt marks etherwarp: 1,098 of 3,365 non-leap/non-hype tps) | M (BR) |
| Particles | not recorded (no particle kind in either recorder) | — |
| Mana | action bar only. Nothing in chat. PrecisionSnipes: casts without enough mana fail **silently** | C |
| Cooldowns | Hyperion has a 2-server-tick discard window (PS SRV-Q15). AOTV printed `This ability is on cooldown for 1s.`/`2s.` 196 times, 0–21 ticks after the last tp (median 12–21). There may be a short etherwarp/AOTV cooldown, but it is not pinned down | M (message) / C (rule) |
| `Warping...` | 67× in BPF. This is the dungeon warp, not an item | M |

## 3. Wither Cloak (Creeper Veil), held id `WITHER_CLOAK` ("Heroic Wither Cloak Sword")

| Fact | Value | Tag |
|---|---|---|
| Activate | right-click → **`Creeper Veil Activated!`** | M (96) |
| Manual off | right-click again → **`Creeper Veil De-activated!`** | M (75) |
| Out of resource | **`Not enough vitality! Creeper Veil De-activated!`** (17). This happens after 0–80 ticks, which suggests an upkeep cost (mana) | M / C |
| Max duration | **200 server ticks (10 s)** → **`Creeper Veil De-activated! (Expired)`** (BR: on at n=4294, off at n=4494; BPF on-times 200–240 client ticks) | M |
| Typical on-time | 10–90 ticks (players toggle it off) | M |
| Cooldown | `This ability is on cooldown for Ns.` with WITHER_CLOAK held: "10s" 1–18 ticks after a de-activation (10×), "5s" (4×), "3s" (2×), "2s", "1s". The shortest observed re-activation was 126 ticks after turning it off. The cooldown seems to be applied when it turns off, and is not always 10 s. Maybe it scales with on-time. Suggestion: 10 s from de-activation | M / C |
| Protection | not measured (no `dmg` analysis while it is on). Hypixel's description is "prevents all damage" from mobs. The sim can treat it as immunity to boss damage while it is on | C |

## 4. Masks / Phoenix / death

Plain text (formatted versions with `§` codes are in chat-attacks.md lines 106-108):
- Spirit Mask: **`Second Wind Activated! Your Spirit Mask saved your life!`** (28 in BPF)
- Bonzo: **`Your  Bonzo's Mask saved your life!`** (the plain text has the private-use glyph U+E068 where 1.8 showed ⚚; 58×)
- Phoenix: **`Your Phoenix Pet saved you from certain death!`** (19). Afterwards: `Autopet equipped your [Lvl 100] Phoenix! VIEW RULE` (+25..45 ticks, an Autopet rule, optional)
- Revive stone (not a proc): `Your Revive Stone revived you and broke!`
- Mod party lines (not Hypixel): `Party > X: Spirit Procced! (n/3)`, `Bonzo Procced! (n/3)`, `Phoenix Procced! (n/3)`; also `Spirit Mask activated! (3s)`, `Popped Bonzo Mask!`, `Popped Phoenix Pet!`. The `(n/3)` counts procs in the run in the order they happened.

Sounds on the proc tick [M, BR, all cases]:
- Bonzo: `entity.generic.eat` (pitch 0.59) + `entity.zombie_villager.cure` (pitch 2.0) + `entity.wither.ambient` (1.0).
- Spirit: the same three, plus `entity.enderman.teleport` pitch 0.0 at −1/+1.
- Phoenix: `block.lava.extinguish` p1.49 + `entity.zombie.infect` p1.19 + `entity.wither.ambient` p1.0 + `ghast.affectionate_scream` p1.52.

Invulnerability and cooldowns are **not measured** (`hp` is always 20 from scaled health, so it can't show them).
[C] Hypixel values: Spirit Mask Second Wind = 3 s invincibility (the mod line `Spirit Mask activated! (3s)` agrees), cooldown 30 s;
Bonzo's Mask = 3 s invincibility, cooldown 360 s (180 s when ⚚ fragged); Phoenix = revives at full HP with ~4 s invulnerability, cooldown 60 s.
All three can proc in one run (`(3/3)`), each once.

Death when nothing procs [M]: **` ☠ You died and became a ghost.`** (leading space) or
` ☠ You were killed by <Mob> and became a ghost.`. Others: ` ☠ <IGN> died and became a ghost.`,
` ☠ <IGN> was killed by Goldor and became a ghost.`, ` ☠ <IGN> disconnected and became a ghost.`.
Revive: ` ❣ <IGN> was revived by <IGN>!`. A ghost stays in the leap menu (§1). Ghost movement and visuals are not recorded [C: spectator-like flying ghost].

## 5. Superboom TNT (SUPERBOOM_TNT) and the P3 gates

- In P3 it is in hotbar slot 1 in 71 of 73 runs (hotbars.md). It is used **at the gates**.
- Gate line: **`The gate has been destroyed!`**. With your own Superboom swing (`sw` while holding SUPERBOOM_TNT) in the 40 ticks before it,
  the line comes **1–2 client ticks after the swing** (1: 43, 2: 14, of 61), so the server destroys the gate **on the use tick** [M].
  Gate destructions across all players: 61 had a Superboom swing in sight; 210 did not (often another player's TNT out of view, or a section completing:
  `The gate will open in 5 seconds!` appears in 26 cases) [M, gate2.py].
- At the gate tick: `entity.generic.explode` vol 0.5 pitch 0.49 (478×) plus the progress `note_block.pling` vol 8 p4.05. Mass block changes to
  air on n+1, and falling_block entities before. No `ex` explosion packet [M, gate.py; also chat-attacks.md:169].
- Goldor's reply lines ~1 tick later: `I will replace that gate with a stronger one!` / `The little ants have a brain it seems.` / `YOUR END IS NEAR!!` (chat-attacks.md).
- On ordinary walls in the boss Superboom breaks almost nothing: 2 blocks in 504 Goldor-phase swings. No knockback (knockback.md).
- Chat while holding it: `There are blocks in the way!` (19), `This ability is on cooldown for 1s./2s.` (17). So there is a ~1–2 s use cooldown [M message / C value].
- `[MVP+] X has obtained Superboom TNT!` / `Moved N Superboom TNT from your Sacks to your inventory.` are clear-phase pickups.

## 6. Bows / arrows (BR `a` adds within 3 blocks of your eye, 0–3 ticks after your `msw`)

The recorders used Spirit Shortbow / Artisanal / Juju. **No Terminator data** (TERMINATOR is in only 2 Mage runs, not recorded by BR).
- One arrow per shot [M, 116 volleys with velocity]. Larger "volleys" were other mobs' arrows nearby (P3 arrow traps, wither skeletons). The `data` (owner) field is often the arrow's own id, so it can't be relied on.
- Speed **3.0 blocks/tick** (82×), which is a vanilla full-draw arrow. Aim = your sent look: yaw within ±3°, pitch +1..2° (vanilla inaccuracy).
- Physics are **vanilla arrow**: `vy' = 0.99·vy − 0.05`, horizontal ×0.99 a tick [M, 59 pairs].
- Sound `entity.arrow.shoot` vol 1.0, pitch 1.14–1.21 (vanilla random pitch) [M].
- Shot spacing: mode 5–6 server ticks between your volleys [M], so ~3–4 shots/s with these bows.
- [C] Terminator: 3 arrows a shot, ±5° yaw fan, same 3 b/t vanilla arrow, ~0.3 s between shots (Hypixel description). Not verified.
- Mosquito Shortbow, Terror armor (Hydra Strike) and Terminator volleys are now measured exactly from Dungeon Recorder data: see **terror-mosquito.md** (the Terminator in §8 there).

## 7. Dungeonbreaker (DUNGEONBREAKER, `minecraft:diamond_pickaxe`)

- An instant break per left-click: the block goes to air 0–3 ticks after the swing [M]. The swing spacing mode is **4 ticks** (1,188 of ~2,000 in P1, 498 in P3), with
  3-tick gaps rare. That suggests one break per 4 ticks (5 blocks/s) at most.
- Each swing breaks 0–4 blocks near you (most swings break 0 in the boss: misses or protected blocks).
- What gets broken in the boss: Maxor (P1): `stone_bricks`, `stone`, `polished_andesite`, `polished_granite`, `cracked_stone_bricks`, `dirt`;
  Storm (P2) / Goldor (P3): mostly **`stone_button`** (80+130: the device/terminal buttons go to air), `polished_granite`, `stone_bricks`, `barrier`;
  Necron: `cyan_terracotta`, `stone_bricks`, `stone_button`.
- Refusal: **`A mystical force prevents you from digging that block!`** (453) / **`A mystical force prevents you digging there!`** (423) [M, mostly clear].
- `This ability is on cooldown for 1s.`/`2s.` while holding it (241). It has charges or a cooldown, value unknown [M message].

## Suggested rules for the sim

1. Leap: right-click → open the 9x4 `Spirit Leap` menu immediately, with heads at 11,12,14,15 and dead teammates included.
   Click → teleport the same tick to the target's exact pos+rot, print `You have teleported to X!` and play enderman.teleport 1/1.
   40-tick cooldown with `This ability is on cooldown for Ns.`.
2. Etherwarp: enderman.teleport + ender_dragon.hurt 1/0.54. Hyperion: enderman.teleport + generic.explode 1/1, plus the Implosion line on the same tick.
3. Cloak: toggle, 200-tick cap with `(Expired)`, damage immunity while on, then a 10 s cooldown.
4. Gate Superboom: destroy on the use tick, then `The gate has been destroyed!`, explode 0.5/0.49, then a Goldor reply 1 tick later.
