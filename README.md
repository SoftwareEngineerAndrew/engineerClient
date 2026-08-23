# Ascent

Class-aware blood-rush waypoint profiles for Hypixel Catacombs, built as an
[Odin](https://github.com/odtheking/Odin) addon module. Fabric, MC 26.1.2, client-only, Kotlin.

**Odin (>= 0.3.1) is a required dependency** — it provides room detection, the room-relative
coordinate transforms, the rendering primitives, and the module/ClickGUI framework. Ascent
registers its own **"Ascent Waypoints"** module into Odin's module system (own ClickGUI panel
"Ascent", own config, own pack folder, own edit mode) — a fully separate sibling of Odin's
Dungeon Waypoints module. Both systems store, select, and render waypoints independently:
**a user's existing Odin waypoints are never touched and keep rendering alongside Ascent's.**

## How it works

- On first run Ascent creates 40 blank waypoint packs named like `BR Mage 3p Door` /
  `BR Berserker 4p NoDoor` (5 classes x 2/3/4/5 players-on-rush x dedicated-door), stored in
  `config/ascent/waypoints/`. The file format is identical to Odin's packs, so packs can be
  copied between the two systems.
- Your class is read from Odin's tab-list parsing. Players-on-rush and dedicated-door are manual
  choices (they encode team strategy, not game state).
- When the profile resolves, Ascent selects the matching pack (plus any custom packs you keep
  enabled) in its own module and sets it as the edit pack — so the in-world editor always edits
  the active profile.
- If tab detection misses (roughly 1 run in 200), the last successfully detected class is used and
  the miss is announced in chat and counted (`/ascent status`).

## Usage

- `/ascent` — profile GUI (class override, players-on-rush, dedicated door, custom packs).
- `/ascent status` — detection state, active pack, miss count.
- `/ascent class <auto|Mage|Archer|Berserker|Healer|Tank>`, `/ascent players <2-5>`,
  `/ascent door <on|off>`, `/ascent apply` — the same knobs as commands.
- Waypoint editing, colors, sizes, editor HUD, and the type-cycle keybind live in the
  **Ascent panel of Odin's ClickGUI** (the module mirrors Odin's Dungeon Waypoints controls:
  enable *Allow Edits*, right-click blocks to place/remove, sneak-right-click for titles).
- If both Ascent's and Odin's edit modes are on at once, Ascent's editor stands down and says so,
  so a right-click never places into two systems.

The module is enabled automatically on first install; it can be toggled like any Odin module.

## Build / deploy

- `./gradlew build` — needs a JDK 26 at `/usr/lib/jvm/java-26-openjdk` (see `gradle.properties`);
  bytecode targets Java 25 to match Odin.
- `./deploy.sh` — builds and atomically installs into the `26.1.2 Ascent` Prism instance
  (override with `ASCENT_MODS_DIR`).
- Compiles against `libs/Odin-0.3.1.jar` (BSD-3-Clause). The waypoint module is a vendored copy of
  Odin's DungeonWaypoints — see `src/main/kotlin/com/ascent/waypoints/VENDORED.md` for the
  upstream pin, the deliberate divergences, and the re-vendoring procedure.
