# Changelog

Changes in ADS. The process for adding entries and cutting releases is in
[RELEASE.md](RELEASE.md).

## [Unreleased]

### Added
- ADS solves terminals in the P3 Sim and the dungeon engine's worlds, without needing Odin, with Odin's Terminal Solver options (Odin, Normal and Custom GUI render types, client prediction, first click protection, Rubix mode, melody options, colours) under `/ads` > Terminals and Terminal Colors.
- Show Numbers setting, to hide Click in Order's 1-10.
- `/ads` opens ADS's settings in an Odin-style panel screen, without needing Odin.
- Practice Splits HUD, Practice Time HUD and Practice Start Timer HUD settings to switch the practice HUDs.
- Practice from the ADS Practice page in a solo world: full clear, single room, blood rush, blood camp and boss phases (P1 Maxor, P2 Storm, P4 Necron, P5 Wither King).
- Full clears and blood rushes are seeded: the seed shows in chat (click to copy), and replaying it gives the same map, puzzles, mobs and chest blessings.
- Blood rush practice: start at the entrance with the blood key, timed to the blood door, with an action-bar timer.
- Blood camp practice: the Watcher's camp from the moment the door opens, timed to the portal.
- Boss practice drops you straight into one phase, with a default set of blessings, and prints the phase time.
- `/rs` replays the current practice and `/rs new` gives a new map; new commands `/practice clear [seed]`, `/practice rush [seed]`, `/practice camp`, `/practice boss <p1|p2|p4|p5>`, `/practice seed`.
- A Practice page at the top of the ADS menu: pick a full clear (random map or a fixed seed), a single room and the door you enter from, blood rush, blood camp, or a boss phase (P1 Maxor, P2 Storm, P3 in the P3 Sim, P4 Necron, P5 Wither King), with F7 or M7.
- The ADS logo: the mod's icon (Mod Menu), the ADS title-screen button, the ADS menus' header and the e4mc install prompt.
- Everything from the Rustclear mod (0.8.0): full dungeon runs with every room, puzzle, blood camp and the F7/M7 boss, parties, 1v1 and 2v2 duels, the kit editor and the SkyBlock menus.

### Changed
- With Odin installed, ADS's solver handles the sim's and the engine's terminals and Odin's solver stays out of them; Odin is unchanged on Hypixel.
- Minecraft's tutorial tips ("Destroy the tree"...) never show while ADS is installed.
- Potion effects are no longer shown on screen: no effect icons on the HUD and no effect list beside the inventory.
- The pause (Esc) menu's buttons use the same ADS style as the title screen's.
- e4mc is installed automatically at start-up when it's missing (e4all instead with Krypton), without asking: it downloads into the instance's mods folder, then the game closes once so it loads when you press Play again.
- Odin is now optional: ADS, the P3 Sim, the Practice page and the dungeon engine run without it; the Odin features load only when Odin is installed.
- `/ads menu` opens the sim's menu (or the sim), as `/ads` used to; `/p3sim` still does.
- ADS's settings moved from Odin's config to `config/engineerclient/ads.json` (copied over on first start) and no longer appear in Odin's ClickGUI.
- Without Odin, `/adm` opens ADS's settings.
- Room practice can now run on M7 as well as F7.
- The P3 Sim menu now matches the other ADS menus: tabs in a left rail with a summary of each, the party at the rail's bottom, skill and role on top, Restart P3 / Stop / Done along the bottom, and scrolling when a tab is taller than the window.
- Advanced is now its own tab in the P3 Sim menu, and the menu always opens on a tab (Plan the first time, then the last one you used).
- The title screen has one ADS button (above Singleplayer); it opens the dungeon menu, now branded ADS. The separate Advanced Dungeon Simulator button is gone.
- Releases are now versioned (`vX.Y.Z`, jar `ads-X.Y.Z.jar`) with release notes from this changelog, instead of one rolling release per Minecraft version.

### Fixed
- P3 Sim items (and pet tooltips) no longer show the purple and black missing texture without Hypixel's resource pack: they keep their Minecraft look unless the pack is loaded.

### Removed
- Dragging the practice HUDs in Odin's HUD editor; they stay at their default places for now.
