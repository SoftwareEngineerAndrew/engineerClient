# HUD module (client channels)

Owner: `hud`. Code: `src/p3sim/clear/hud/`, tests: `test/p3sim/hud/`.

The HUD module is the last module in the tick order. For every real player it sends the packets Hypixel sends, drawn from `RunState`:

| channel | packets | cadence | pure model |
|---|---|---|---|
| sidebar | objective `SBScoreboard` (blank number format), one `team_N` team + score holder per line, scores 15..1 top to bottom | any tick a line's text changed | `SidebarModel`, `SidebarDiff` |
| tab list | 80 fake players `!A-a`..`!D-t` (display name = the row), real players unlisted, bots as unlisted entries, header and footer | once a second, rows that changed | `TabModel` |
| map | `ClientboundMapItemDataPacket` for map id 1024: a full 128x128 frame when a pixel changed, markers otherwise | markers every 5 ticks | `MapModel` |
| action bar | overlay system chat | every 10 ticks | `ActionBarModel`, `BarSegments` |
| chat, titles | styled system chat (no `§` left in the string), title packets | on call | `ChannelService` |

Before Mort speaks (`t < 0`) the sidebar is the lobby layout (`Starting in: 0:04`, the whole party with `[Lv50]`); after, the running layout
(Keys line until the blood door opens, `Time Elapsed`, `Cleared: P% (S)`, up to four teammates with health or `DEAD`). The map starts at T0.

## What each parser needs, and where it is tested

- Odin: floor from `The Catacombs (F7)`; cleared percent from `Cleared: P% (S)` (S is `ScoreState.score`); the tab member regex, stats rows,
  `Puzzles: (n)` rows, `Blessing of Power V` in the footer; `N/M Secrets` in the action bar (M = the room's `secretsMax`); the map: pixel 0 empty,
  entrance run of 16, corner = room type, (8,8) = check mark, door pixel (18,8) with an empty side pixel. `SidebarModelTest`, `TabModelTest`,
  `ActionBarModelTest`, `MapModelTest` carry those patterns.
- Devonian: only `team_N` sidebar teams, `Time Elapsed` shape, the decoration count equal to the living members, player-info regex.
- Blade Addons: `Time Elapsed:` line, the five lobby lines (`[X] Name [LvNN]`) while the party has five members.
- `HudPacketsTest` writes every packet to bytes and reads it back (registries booted headless).

## For other modules

- Post through `Services.channels`: `chat(text, to)`, `title(...)`, `actionBarSegment(key, text, priority, ttlTicks, to)`. A segment of priority 100 or more
  (`ActionBarModel.REPLACES_DEFENSE`) takes the defense slot while it lives (essence found, skill XP); lower ones are appended before the secrets segment.
- Extra sidebar lines, tab rows or map pixels: `HudRegistry.sidebar / tab / map`.
- Optional per-player facts the HUD reads from `PlayerRecord.extras` (all default to nothing): `yaw` (map marker facing for bots), `reviveStones` (Int; a
  bot's count, the HUD counts a human's inventory itself), `ultimateCooldownTicks` (Int; ticks an ultimate still has to cool down, 0 or absent: Ready),
  `milestone` (Int), `milestonePct` (Double). The tab's Ultimate row is `N/A` until T0 and `Ready` from T0 (recorded: the same tick the class names show).
- A failed puzzle's tab row names the player of the `PuzzleFailed` event (`Higher Or Lower: [✖] (name)`); the HUD keeps the name itself and shows it only while
  the puzzle stays failed.
- Health is `PlayerRecord.hpPercent` times the run's SkyBlock preset (`RunConfig.statsPreset`, default `dev-p50`); the tab shows `Team Deaths` from
  `deaths.teamDeaths`, rooms from `counters`, secrets from `secrets` and the players' own counts.

## Unmeasured (named constants in `HudConstants` or the code that uses them)

Sidebar name cut at 12 characters (one sample); teammate health colour bands (every sample is green); the SkyBlock level and class level printed in the tab;
the clock step (166 ticks per 10 SkyBlock minutes); the `?` glyph shape; the colour of an opened plain door (the room found later); the Account Info
filler rows; `Discoveries` (equal to the player's secrets); the milestone rows (static unless a module sets the extras); team healing (never fed); mana
(always full: nothing models it); the elapsed-time form past an hour; the tab layout past the first column.
