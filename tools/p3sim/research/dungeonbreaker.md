# Dungeonbreaker and masks: what the recordings show

[M] means measured from recordings. [C] means inferred. [W] means the Hypixel wiki (hypixelskyblock.minecraft.wiki/w/Dungeonbreaker), which is
not verified against data.

**Data.** 76 Boss Recorder files (server packets: own swings `msw`, block packets, sounds, chat; ~16k own swings) and 199 Better PF runs
(held item, client-side block changes, chat, equipment, GUIs; 216 files).

- **Boss Recorder time unit.** Its `n` counter is **not ticks**: it runs about 1.16 per tick.
  Every Boss Recorder number below is converted to server ticks with the recording's world-time packets.
- **Better PF data is unreliable for Dungeonbreaker work, in two ways:**
  - Its `block` lines include the client's **predicted** breaks.
    Of 6,345 Dungeonbreaker "breaks" near you, **5,390 came back 2–4 ticks later**: the server refused them.
  - Its own-swing `sw` lines are sampled every 4 ticks.
  - The old "one break per 4 ticks" rule (items-timing.md §7, and `minedAt` in `SimItems.mine`) **came from that sampling, not from Hypixel.**

Scripts: `/home/cam/.claude/jobs/fffcf85d/tmp/research/*.py` (br.py, extract.py, bran2.py, steady.py, bursts.py, charges*.py, masks.py, procs.py).

## 1. Breaks per swing, timing, holding left-click

- **Holding left-click swings every tick [M].**
  - Gaps between own swings on distinct ticks: 1 t 7,066, 2 t 2,899, 3 t 2,019, 4 t 1,330 (1–3 tick jitter comes from converting the time unit).
  - The vanilla client keeps "destroying" while the button is held. As soon as the targeted block turns to air, it starts on the next block under the crosshair.
- **Exactly one block is broken: the targeted one [M].**
  - Sample: 364 own isolated breaks (one break, under the crosshair, within 4 ticks of your own swing).
  - Angle between the look direction and the block centre: 0–10° in 189 breaks, 10–30° in 149, >30° rarely.
  - Breaks per tick: 1 block on 486 ticks, 2 on 45 (two blocks in a vertical shaft as you fall).
  - One left-click never breaks an area.
- **Delay from swing to the block turning to air, ping included [M]:** 0 t 225, 1 t 102, 2 t 22, 3 t 12, 4 t 3 (n 364).
- **There is no fixed per-break cooldown [M].**
  - Consecutive own breaks 1 tick apart are common: 74 on-aim pairs at 1 t and 43 in the same tick.
  - Example: digging down at P1→P2, (45,149,138) at t+0, (45,148,138) at t+1, (45,147,138) at t+4.
  - **The only limit is charges.**

## 2. Charges

- **What players see [M].**
  - `§cYou don't have enough charges to break this block right now!` appears 102 times in the Better PF runs and 65 in the Boss Recorder files.
  - The Better PF slots record only `minecraft:diamond_pickaxe` / "Dungeonbreaker" / glint, so no lore, charge count or action bar was captured.
- **Message throttle [M].** When you keep trying, the line repeats **every ~20 ticks**: 23 of 39 consecutive gaps are 18–22 t, and none is shorter than 18 t.
- **Refill rate while empty and holding left-click [M]:**
  - Breaks between two such messages ≤25 t apart: **3 breaks** in 13 of 25 intervals, 4 in 5, 5 in 3, 2 in 3, 1 in 1. The mean is 3.2 per ~20 t.
  - These breaks come as a **cluster on 3 consecutive ticks** (e.g. `N, +6 +7 +8, N+20, +26 +27 +28, N+40`). That means a batch refill **once per second**, not a smooth trickle [C].
  - The wiki says 2/s [W]. The recordings show about 3 per second.
- **Pool size [M].**
  - Largest bursts with no message: 24 breaks in 38 t (P3, tunnel at x17-18 y130-131 z117-127, 2026-09-30_23-32-24) and 15 in 32 t (P5).
  - 24 breaks minus the refill during those 38 t suggests ≥18–20 stored charges. The wiki says 20 max [W].
- **Empty at the P1→P2 dig-down [M].**
  - In all 33 runs whose first message came in P1 (Maxor), it came 144–556 t after Maxor's first line (median ~240 t).
  - In those 33 runs there were **0 own server-confirmed breaks in the boss** before it.
  - So the pool is empty early in the boss (likely reset or drained on boss entry, or no refill in the boss room before then) [C].
  - By P3, players dig 13–24 blocks in a burst before running out. So treat the pool as **full at P3 start** [C].
- **`This ability is on cooldown for 1s./2s.` is not from Dungeonbreaker [C].**
  - It appears 241 times while holding Dungeonbreaker, but in the Boss Recorder data most of these lines come 30+ ticks after any swing.
  - The same lines appear while holding AOTV, Leap or Bat Wand.
  - It is the previous item's cooldown, and arrives because players park on slot 4 (Dungeonbreaker).
- **Wiki [W]:** "consume 1 charge to break a block. 20 blocks can be broken at a time, and re-appear after 10s. 2 charges are regenerated each second". "Upon breaking the 21st block, the 1st block broken will regenerate."

## 3. What can and can't be broken in P3

Own server-confirmed P3 breaks (Boss Recorder, isolated): stone 15, stone_bricks 13, **gold_block 8**, nether_bricks 5, sea_lantern 3, chiseled_stone_bricks 2,
cobblestone 1, cracked_stone_bricks 1, polished_andesite 2, mossy_stone_bricks 1.

| Location | Blocks broken [M] |
|---|---|
| **Core entrance gold door** (54,115-116,54) | 7 breaks in 5 runs. This is the arena.md "core door (52-56,115-121,54)". **It can be mined.** |
| (54,113,112), (86,126,45) | gold_block, once each |
| **Into the core from the east**: x69 y109-110 z113-120 | 2-high tunnel of stone + sea_lantern, 1 run, 15 blocks. This is the "mine into core" route (core box x39-69 z99-129) |
| **S2→S3 wall tunnel**: x17-18 y130-131 z117-127, plus (18,122,127) nether_bricks and (12,121,49) cobble | 2-high tunnel, 24 blocks in 38 t |

The sim excludes all gold_block, which is wrong for the core door.

**Refusals in P3** (Boss Recorder, standing position at the time; the target block is known only where the raycast had world data):

| Line | Where (count) |
|---|---|
| `§cA mystical force prevents you from digging that block!` (block-type refusal) | **Core platform bedrock** (54,113,113)/(54,113,115): standing at (54.5,114,110-118) and looking down, ~45 times in ~35 runs [M]. **Command block** (-3,109,77) from (0.4-1.4,109,77-79) at S3, ~10 times [M]. From (38-40,109,140) looking north-down ~30°, ~18 runs; from (92-94,112/121,92-101) at S1, ~15; from (43.6,122.5,32) at S4, 3. The blocks in those last three are unknown, but they are fixed spots: likely bedrock, barrier or device blocks [C] |
| `§cA mystical force prevents you digging there!` (location refusal) | Outside the arena at x≈-5 z≈104.5 (3) [M]. The SS device obsidian (111,121,93) in P2 [M]. In the clear, mostly room borders |
| `§cA mystical force prevents you from leaving the inner chamber!` | 5 in Boss Recorder (9 in Better PF), only in Goldor. At (54.5,115,51) by the core door, and at (54.5,103-106,116) under the core platform. Mining **out of** the core is blocked [M] |
| `§cA mystical force in this room prevents you from using that ability!` | 19. Ability use (not mining) blocked in that room |

- **Silent refusals [M].** Many refusals have no chat line: the client-predicted air simply comes back after 2–4 t. One example is the 70 refused breaks of infested chiseled stone bricks at a clear-room start.
- **Buttons, barriers, gates [C].**
  - The `stone_button`/`barrier` → air lines in items-timing.md §7 are device resets and gate/door events. None of them is an own isolated break.
  - No own Dungeonbreaker break of a gate, lever, button, terminal or barrier was seen in P3.
- **Other phases [M].** P1: stone_bricks, stone, obsidian, cracked bricks, dirt (the dig-down shafts at x44-47 z138 and x12 z72). P5: cyan_terracotta, stone, sea_lantern, carpets.

## 4. Blocks come back

- **[M]** 335 of 364 own Boss Recorder breaks returned as **the same block state**.
  - Delay peak: **210–229 server ticks** (268 breaks).
  - Better PF (client ticks): peak at 220–229 t (710).
  - So it is about **11 s (220 t)**. The wiki says 10 s.
- About 35% came back early (40–170 t). This fits the wiki's 21st-break-restores-the-oldest rule, possibly counted per party, since teammates dig too [C].
- Without regeneration, a P3 tunnel stays open forever in the sim. On Hypixel it closes behind you.

## 5. Sounds and particles

- Out of 364 own breaks, no break sound reached the client from the server (1 unrelated `entity.item.break`). The break sound is played by the client itself [C]. In the sim, play `soundType.breakSound` locally, as now.
- Particles are not recorded. Vanilla block-break particles (the client's `destroyBlockEffect`) are the natural choice [C].
- The refused-break restore has no sound [M: none recorded].

## 6. Reach

Distance from eye to block centre, own on-aim breaks (n 338) [M]: p50 2.12, p90 4.04, p95 4.32, p99 6.0 (the 6.0+ tail is falling-shaft noise).
That is the vanilla 4.5-block block-interaction range: whatever the client can start destroying.

## 7. Masks

- **Helmet worn in P3 (own `eq` lines, all runs) [M]:**
  - Bonzo's Mask is on for 6,274 eq-ticks, Spirit Mask for 322, Wither Goggles for 218.
  - Teammates wear Spirit Mask ✪ (5,420) and Bonzo's Mask (2,561) the most.
  - The clear and P1 are played in the main helmet: Necrotic Wither Goggles for john, Golden Necron Head, Racing Helmet and others for teammates.
- **When players swap [M]:**
  - Goggles→Bonzo in P2: 31 times.
  - Goggles→Spirit in P1/P2: 21.
  - Spirit→Bonzo in P3: 16 total. After a Spirit proc the gap is 19 swaps with a median of 84 t (min 41). After a Phoenix proc while wearing Spirit it is 9 swaps with a median of 35 t.
  - Back to Goggles at P3 end / P4: 27.
- **How players swap [M]:** the inventory. Of 136 swaps (last 60 runs), 124 come with an open `Stats & Equipment` (or `Crafting`) GUI and **two `PICKUP` slot clicks** within 20 t: pick the mask, drop it on the helmet slot.
  - No wardrobe GUI was ever opened.
  - 7 swaps had only a hotbar change (a hotbar right-click equip) [C].
- **Mask items [M]:**

| Item | Name as seen | Vanilla | Skin hash |
|---|---|---|---|
| Bonzo's Mask | ` Bonzo's Mask` (also `Wise`/`Ancient` reforges) | `minecraft:player_head` | `12716ecbf5b8da00b05f316ec6af61e8bd02805b21eb8e440151468dc656549c` |
| Spirit Mask | `Spirit Mask ✪` (also `Ancient`/`Necrotic … ✪✪✪✪✪`) | `minecraft:player_head` | `9bbe721d7ad8ab965f08cbec0b834f779b5197f79da4aea3d13d253ece9dec2` |
| Phoenix pet | `[Lvl 76] Phoenix` | `player_head` | `66b1b59bc890c9c97527787dde20600c8b86f6b9912d51a6bfcdb0e4c2aa3c97` |

  - The full base64 textures are in `/home/cam/.claude/jobs/fffcf85d/tmp/research/masktex.json`.
  - The skyblock ids are not in the recordings. Odin reads `BONZO_MASK`/`STARRED_BONZO_MASK` and `SPIRIT_MASK`/`STARRED_SPIRIT_MASK` [C, odin-features.md].
  - The Bonzo cooldown comes from the helmet lore `Cooldown: Ns`.
- **A proc needs the item [M].**
  - Bonzo procs: 50 in Goldor with Bonzo worn, 1 with Spirit (an equip-lag edge), 3 with the helmet unknown.
  - Spirit procs: 22 with Spirit worn, 0 with Bonzo.
  - **All 19 Phoenix procs had Phoenix as the active pet**, set by an `Autopet` rule: `§cAutopet §eequipped your §7[Lvl 76] §5Phoenix§e! §a§lVIEW RULE` (23 lines). The others had Black Cat.
  - Phoenix procs while the worn mask is already used up: 10 with Spirit worn, 4 with Bonzo worn. So **only the worn mask procs, and Phoenix only if it is the pet** [C, strongly supported].
- **Exact lines [M]:**
  - `§aYour §r§9 Bonzo's Mask §r§asaved your life!`: the sim's line is missing the `` glyph.
  - `§6Second Wind Activated§r§a! Your Spirit Mask saved your life!`
  - `§eYour §r§cPhoenix Pet §r§esaved you from certain death!`
- **Odin** (odin-features.md, InvincibilityTimer):
  - It matches those three lines. The Bonzo regex `(?:. )?` allows the glyph.
  - The "equipped" HUD and slot overlay read the **helmet `itemId`**, so a real helmet item with `custom_data.id` would make them work.
  - It uses Phoenix = 80 t of i-frames, and the Bonzo cooldown from the lore.

## Rules for the sim

**Dungeonbreaker**

1. Drop the 4-tick gate. Every start-destroy on a block (the client re-sends one each time the crosshair reaches a new block while left-click is held) breaks **that one block** after the ping, if allowed and you have ≥1 charge. Reach is the vanilla 4.5 blocks.
2. Charges: max **20**, **full at P3 start**, +**3 every 20 server ticks** in one batch (make it a setting, 2–3; the wiki says 2). Each break costs 1.
3. When out of charges, refuse and resend the block (the client's ghost air comes back in about a ping). Chat `§cYou don't have enough charges to break this block right now!` at most once per 20 t.
4. Regenerate every broken block to its **exact previous state 220 t later**. Keep at most 20 of your blocks broken: the 21st break restores the oldest at once.
5. Breakable: ordinary arena blocks (stone, stone bricks and their variants, andesite, nether bricks, sea lantern, cobble, terracotta, carpet, dirt, obsidian) **and the core-door gold blocks (52-56,115-121,54)**. That means removing the blanket `GOLD_BLOCK` exclusion. Keep refusing the core platform's gold/bedrock (y113 x51-57 z111-117).
6. Refused with `§cA mystical force prevents you from digging that block!`: bedrock, barrier, command_block, levers/buttons/device blocks. Gate blocks themselves can be mined (early enters); the barriers behind them can't.
7. Refused with `§cA mystical force prevents you digging there!`: any position outside the arena interior (x<-3 etc.) and the device blocks (SS panel).
8. Refused with `§cA mystical force prevents you from leaving the inner chamber!`: while you are inside the core box (x39-69 z99-129, below y113), any block on the core's outer wall. Breaking **into** the core from outside (e.g. x69 y109-110) is allowed.
9. Refusals restore the block. There is no sound. Successful breaks play the block's break sound locally (as now) plus vanilla break particles.

**Mask items** (option "Real masks")

1. Put a real helmet on the player: `minecraft:player_head` with the profile texture above, the custom name (`§9 Bonzo's Mask` / `§5Spirit Mask ✪`), and `custom_data.id = BONZO_MASK` / `SPIRIT_MASK`. Put the other mask in the inventory. Start P3 wearing the mask chosen in a setting, Spirit by default; john wears Bonzo.
2. A lethal hit procs **only the worn mask** if it is off cooldown (Spirit 600 t, Bonzo 3,600 t). Otherwise Phoenix procs if the "Phoenix pet" setting is on (1,200 t, 80 t i-frames). Otherwise death.
3. Allow helmet swaps through the normal inventory (pick up and place on the helmet slot, also hotbar right-click). There is no wardrobe. Swapping does not reset cooldowns: cooldowns belong to the item.
4. Put `Cooldown: Ns` in the Bonzo lore so Odin's timer reads it. Fix the Bonzo chat line to include ``.
5. The HUD and menu status should show the worn mask and each item's remaining cooldown.

**My suggestions**

- Implement Dungeonbreaker rules 1–4 first: no 4-tick gate, charges, regeneration. Those three are the "way off" part. The location rules (5–8) can follow.
- Make the charge refill and regen time settings, default 3 per second / 220 t. The wiki disagrees (2 per second / 10 s) and Hypixel may tune them again.
- Open question: the empty pool at the P1 dig-down. If the sim ever covers P1/P2, start the pool at 0 at boss entry.
- Masks: default "Real masks" to Spirit worn + Bonzo in the inventory + Phoenix pet on. Most of the data (19 Spirit→Bonzo swaps after a proc) shows that the swap after a Spirit proc is the skill to practise.
