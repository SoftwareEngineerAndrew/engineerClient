# Ascent

Class-aware blood-rush waypoint profiles for Hypixel Catacombs, built on top of
[Odin](https://github.com/odtheking/Odin)'s dungeon waypoint system. Fabric, MC 26.1.2, client-only.

**Odin (>= 0.3.1) is a required dependency** — it provides room detection, room-relative waypoint
storage, through-wall rendering, and the in-world waypoint editor. Ascent adds the profile layer:
40 pregenerated waypoint packs (5 classes x 2/3/4/5 players-on-rush x dedicated-door), automatic
selection of the right pack from your detected dungeon class, and a GUI for the manual axes.

## How it works

- On first run Ascent creates 40 blank Odin waypoint packs named like `BR Mage 3p Door` /
  `BR Berserker 4p NoDoor` (in `config/odin/dungeon-waypoints/`).
- Your class is read from Odin's tab-list parsing (`DungeonUtils.currentDungeonPlayer`). Players-on-rush
  and dedicated-door are manual choices (they encode team strategy, not game state).
- When the profile resolves, Ascent points Odin's pack selection at the matching pack (plus any
  custom packs you keep enabled) and sets it as Odin's edit pack — so Odin's waypoint editor edits
  the active profile.
- If tab detection misses (roughly 1 run in 200), the last successfully detected class is used and
  the miss is announced in chat and counted (`/ascent status`).

## Usage

- `/ascent` — opens the GUI (class override, players-on-rush, dedicated door, custom packs).
- `/ascent status` — current detection state, active pack, miss count.
- `/ascent class <auto|Mage|Archer|Berserker|Healer|Tank>`, `/ascent players <2-5>`,
  `/ascent door <on|off>`, `/ascent apply` — the same knobs as commands.
- Author waypoints with Odin's editor (enable *Allow Edits* in Odin's Dungeon Waypoints module);
  they land in the active profile pack.

Odin's **Dungeon Waypoints** module must be enabled or nothing renders (Ascent warns in chat if it's off).

## Build / deploy

- `./gradlew build` — needs a JDK 26 at `/usr/lib/jvm/java-26-openjdk` (see `gradle.properties`); bytecode targets Java 25 to match Odin.
- `./deploy.sh` — builds and atomically installs into the `26.1.2 Ascent` Prism instance
  (override with `ASCENT_MODS_DIR`).
- Compiles against `libs/Odin-0.3.1.jar` (BSD-3-Clause). Only public Odin API is used.
