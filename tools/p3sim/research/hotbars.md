# F7 boss hotbars from Better PF recordings

Source: `betterpf/runs/*_F7.jsonl.gz`. 105 runs reach `[BOSS] Maxor:`, and 91 of them have `hotbar` lines (the 14 runs from 2026-09-23 predate them).
Boss window: from the first `[BOSS] Maxor:` line to `EXTRA STATS`/`Team Score:`. Phases start at the first `[BOSS] Maxor/Storm/Goldor/Necron:` line.
Vanilla → Skyblock mapping: the recorder's own `p.heldItemId` when `hotbar.items[sel] == p.vanillaItem` at the same tick. Every model-bearing id maps to exactly one Skyblock id.

**Caveat: there are only two recorders.** `johnswizzlechang` has 65 runs (53 Archer, 10 Berserk, 2 Mage), and `TheBadOne` has 26 runs (all Healer). Read "most people" as "these two".

## 1. Per recorder: the hotbar they spend the boss in

The layout held longest in each run (on average it covers 64% of boss time; the rest is P4 swaps, ghost/dead hotbars and leap blips).

### johnswizzlechang (Archer/Berserk/Mage), 65 runs

| Slot | P1–P2 | P3 (swapped in ~end of P2) | Vanilla item / model |
|---|---|---|---|
| 1 | HYPERION | SUPERBOOM_TNT | `minecraft:iron_sword@hypixel_skyblock:item/uncategorized/hyperion` / `minecraft:paper@hypixel_skyblock:item/uncategorized/superboom_tnt` |
| 2 | STARRED_BONZO_STAFF (64/65) | same | `minecraft:blaze_rod@hypixel_skyblock:item/island_relevant/dungeons/bonzos_staff_fragged` |
| 3 | ITEM_SPIRIT_BOW (Spirit Shortbow, 63); TERMINATOR (2, Mage) | same | `minecraft:bow@hypixel_skyblock:item/island_relevant/dungeons/spirit_shortbow` / `minecraft:bow@hypixel_skyblock:item/slayer/enderman/weapons/terminator` |
| 4 | DUNGEONBREAKER | same | `minecraft:diamond_pickaxe` |
| 5 | ENDER_PEARL | same | `minecraft:ender_pearl` |
| 6 | INFINITE_SPIRIT_LEAP | same | `minecraft:player_head` (tex below) |
| 7 | JERRY_STAFF (Jerry-chine Gun, 63) | same | `minecraft:golden_horse_armor@hypixel_skyblock:item/community_center/mayor/jerry/jerrychine_gun` |
| 8 | WITHER_CLOAK (62) | same | `minecraft:stone_sword@hypixel_skyblock:item/uncategorized/wither_cloak_sword` |
| 9 | SKYBLOCK_MENU (60); MAP (5) | same | `minecraft:nether_star` / `minecraft:filled_map` |

Clear (pre-boss) layout: `HYPERION, ASPECT_OF_THE_VOID, ITEM_SPIRIT_BOW, DUNGEONBREAKER, ENDER_PEARL, INFINITE_SPIRIT_LEAP, STARRED_BAT_WAND, SUPERBOOM_TNT, SKYBLOCK_MENU` (46 runs). About 60 ticks after the first Maxor line he swaps AOTV→Bonzo, Bat Wand→Jerry-chine and SBT→Wither Cloak, one slot per tick. In P3 he swaps Hyperion→SBT. In P4, 19 of 38 runs swap back to the clear layout.

### TheBadOne (Healer), 26 runs

| Slot | P1–P2 | P3+ | Vanilla item / model |
|---|---|---|---|
| 1 | STARRED_BAT_WAND (Spirit Sceptre) | SUPERBOOM_TNT | `minecraft:paper@hypixel_skyblock:item/island_relevant/dungeons/spirit_sceptre_fragged` / `minecraft:paper@…/superboom_tnt` |
| 2 | BONZO_STAFF (unstarred) | same | `minecraft:blaze_rod@hypixel_skyblock:item/island_relevant/dungeons/bonzos_staff` |
| 3 | ARTISANAL_SHORTBOW (24); ROGUE_SWORD (2) | same | `minecraft:bow@hypixel_skyblock:item/uncategorized/artisanal_shortbow` / `minecraft:golden_sword@hypixel_skyblock:item/uncategorized/rogue_sword` |
| 4 | DUNGEONBREAKER | same | `minecraft:diamond_pickaxe` |
| 5 | FISHING_ROD (25) | same | `minecraft:fishing_rod` |
| 6 | INFINITE_SPIRIT_LEAP | same | `minecraft:player_head` |
| 7 | JERRY_STAFF | same | jerrychine_gun model |
| 8 | WITHER_CLOAK | same | wither_cloak_sword model |
| 9 | SKYBLOCK_MENU | same | `minecraft:nether_star` |

Clear layout: `STARRED_BAT_WAND, ASPECT_OF_THE_VOID, ARTISANAL_SHORTBOW, DUNGEONBREAKER, ENDER_PEARL, INFINITE_SPIRIT_LEAP, ROGUE_SWORD, SUPERBOOM_TNT, SKYBLOCK_MENU`. He also swaps AOTV→Bonzo, Pearl→Rod, Rogue→Jerry and SBT→Cloak at boss start.

## 2. Most common layouts (the layout each run spends the most boss time in)

| Runs | Layout (slots 1–9) |
|---|---|
| 37 | SUPERBOOM_TNT, STARRED_BONZO_STAFF, ITEM_SPIRIT_BOW, DUNGEONBREAKER, ENDER_PEARL, INFINITE_SPIRIT_LEAP, JERRY_STAFF, WITHER_CLOAK, SKYBLOCK_MENU (john) |
| 20 | SUPERBOOM_TNT, BONZO_STAFF, ARTISANAL_SHORTBOW, DUNGEONBREAKER, FISHING_ROD, INFINITE_SPIRIT_LEAP, JERRY_STAFF, WITHER_CLOAK, SKYBLOCK_MENU (TheBadOne) |
| 19 | HYPERION, STARRED_BONZO_STAFF, ITEM_SPIRIT_BOW, DUNGEONBREAKER, ENDER_PEARL, INFINITE_SPIRIT_LEAP, JERRY_STAFF, WITHER_CLOAK, SKYBLOCK_MENU (john) |
| 5 | as the 37-run layout, with MAP in slot 9 |
| ≤3 each | 7 one-off variants (Bat Wand in slot 1, Terminator in slot 3, SBT/Bat Wand in slots 7–8) |

Per phase (dominant layout per run): **P1/P2** has HYPERION in slot 1 in 56/49 of john's runs (Bat Wand for TheBadOne). **P3** has SUPERBOOM_TNT in slot 1 in 71 of 73 runs.

### Recommended default (P3 practice)

| Slot | Skyblock id | Display name | Vanilla item / model |
|---|---|---|---|
| 1 | SUPERBOOM_TNT (HYPERION outside P3) | Superboom TNT | `minecraft:paper@hypixel_skyblock:item/uncategorized/superboom_tnt` |
| 2 | STARRED_BONZO_STAFF | ⚚ Bonzo's Staff | `minecraft:blaze_rod@hypixel_skyblock:item/island_relevant/dungeons/bonzos_staff_fragged` |
| 3 | ITEM_SPIRIT_BOW | Spirit Shortbow | `minecraft:bow@hypixel_skyblock:item/island_relevant/dungeons/spirit_shortbow` |
| 4 | DUNGEONBREAKER | Dungeonbreaker | `minecraft:diamond_pickaxe` |
| 5 | ENDER_PEARL | Ender Pearl | `minecraft:ender_pearl` |
| 6 | INFINITE_SPIRIT_LEAP | Infinileap | `minecraft:player_head` + leap tex |
| 7 | JERRY_STAFF | Jerry-chine Gun | `minecraft:golden_horse_armor@hypixel_skyblock:item/community_center/mayor/jerry/jerrychine_gun` |
| 8 | WITHER_CLOAK | Wither Cloak Sword | `minecraft:stone_sword@hypixel_skyblock:item/uncategorized/wither_cloak_sword` |
| 9 | SKYBLOCK_MENU | SkyBlock Menu | `minecraft:nether_star` |

**Items in every boss hotbar:** DUNGEONBREAKER (slot 4, 91/91 runs) and INFINITE_SPIRIT_LEAP (slot 6, 91/91). Nearly always present: a Bonzo staff (slot 2, 90/91), JERRY_STAFF (slot 7, 89/91) and WITHER_CLOAK (slot 8, 87/91). SUPERBOOM_TNT is present in P3 in practically every run. HYPERION is in every run of john's. TERMINATOR appears in only 2 Mage runs. ASPECT_OF_THE_VOID is clear-only: it is swapped out at boss start and only reappears when the P4 hotbar is swapped back.

### Selected-slot time in boss (% of each phase's ticks, all 91 runs)

| Item | P1 | P2 | P3 | P4 | Runs used |
|---|---|---|---|---|---|
| DUNGEONBREAKER (idle/rest slot) | 57.5 | 37.6 | 59.3 | 77.2 | 91 |
| INFINITE_SPIRIT_LEAP | 2.4 | 11.8 | **21.9** | 8.8 | 79 |
| HYPERION | 10.8 | **19.9** | 0.5 | 9.6 | 65 |
| Bonzo (starred + plain) | **20.8** | 18.2 | 10.0 | 1.3 | 86 |
| WITHER_CLOAK | 0.2 | **9.6** (Storm lightning) | 0.3 | 0.0 | 64 |
| JERRY_STAFF | 4.9 | 1.3 | 1.5 | 0.0 | 83 |
| SUPERBOOM_TNT | 0.1 | 0.4 | 2.6 | 1.2 | 74 |
| FISHING_ROD (healer) | 0 | 0 | 1.4 | 0 | 20 |
| ASPECT_OF_THE_VOID | 1.5 | 0 | 0 | 1.0 | 47 |
| SKYBLOCK_MENU | 0.9 | 0 | 0 | 0 | 13 |
| ITEM_SPIRIT_BOW / ENDER_PEARL / STARRED_BAT_WAND | <0.6 each | | | | |

This measures how long a slot is selected, not clicks. Both players park on slot 4 (Dungeonbreaker) between actions. P3's real tools are Leap, Bonzo (for movement) and SBT, plus the Rod for the healer. Seeing `ARCHER_DUNGEON_ABILITY_2/3`, `gray_dye` or `HAUNT_ABILITY` heads in slots 1/4/6 means the ghost (dead) hotbar. `MAXOR_ENERGY_CRYSTAL` shows on the nether star in P1.

## 3. By class

Classes differ by player more than by role. john uses the same layout as Archer, Berserk and Mage; only the 2 Mage runs use TERMINATOR in slot 3. The Healer (TheBadOne) uses Bat Wand/plain Bonzo/Artisanal Shortbow/Fishing Rod where john has Hyperion/starred Bonzo/Spirit Shortbow/Pearl. Slots 4, 6, 7, 8 and 9, and the P3 SBT in slot 1, are identical across all classes.

## 4. Player-head textures

- **INFINITE_SPIRIT_LEAP** (slot 6, all 91 runs, both players; also every `held` line for it): skin `377d4a206d7757f479f332ec1a2bbbee57cef97568dd88df81f4864aee7d3d98` (profile Micros1182). The full `tex` value:
  `ewogICJ0aW1lc3RhbXAiIDogMTY1MjE0NjYxMjc0MiwKICAicHJvZmlsZUlkIiA6ICI5ZWU3NTUxOGQyZWE0Y2Q4OGJiNGI1YTZkNmVhNTFjYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaWNyb3MxMTgyIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzM3N2Q0YTIwNmQ3NzU3ZjQ3OWYzMzJlYzFhMmJiYmVlNTdjZWY5NzU2OGRkODhkZjgxZjQ4NjRhZWU3ZDNkOTgiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==`
- Other heads seen in john's slot 1, only in ghost-mode or transient hotbars (HAUNT_ABILITY etc.): `f1d26142…c793bb` (MyUuidIs, 10 runs), `2f2cfaad…596c35` and `12716ecb…56549c` (1 run each).

## Armor (recorder's `eq` at boss start)

Both players always wear a player-head helmet with dyed leather chestplate, leggings and boots.
- Helmet skin `37ceb8f0758e2d8ac49de6f977603c7bfc23fd82a8574810a45f5e97c6436d79` in 83/91 runs (both players). The others are `9bbe721d…` (TheBadOne ×3), `12716ecb…` (john ×3) and `2ce0746b…` (john ×2).
- TheBadOne wears chest `#1793c4`, legs `#17a8c4` and boots `#8969c8`. The chest and legs match Storm's armor colours.
- john's dye hex changes every run (purple→cyan gradients), so it is probably an animated dye. A sim can use any one sample, e.g. `#048f95` for all three pieces.
