# Engineer Client

Andrew's [Odin](https://github.com/odtheking/Odin) addon for Hypixel Catacombs speedrunning.
Fabric, MC 26.1.2, client-only, Kotlin. Built for one team whose clients are all set up the
same way; `/ec setup` reads the live Odin config and says what is wrong.

**Odin (>= 0.3.2) is a required dependency.** Sodium and EntityCulling are expected on every
client (the POV previews drive Sodium's terrain pass directly) but the mod runs without them.

Modules, all under the "Blood Rush" panel in Odin's ClickGUI:

| Module | What it does |
|---|---|
| Blood Rush Waypoints | Class-aware blood-rush waypoint profiles (see below). |
| Blood Rush Roles | M7 phase-3 dynamic role rotation driven by party/system chat, leap-target highlight in Odin's leap menu, per-role colour vignette + sound. Strategy is data: `src/main/resources/rotation/p3.json`. |
| POV Previews | Each quarter of Odin's Spirit Leap menu rendered from that teammate's eyes. Design notes in `docs/pov-preview-plan.md`. |
| Party Finder Stats | Cata level, secrets and floor PB in-line on every member row of a Party Finder listing, cached on disk for a day. The `Members:` header says which classes the party is missing, yours bolded. |
| No Enchant Glint | Removes the enchantment glint from items, held/dropped items and worn armour, each switchable. Leaves inventory glint alone while a terminal is open. |

Commands: `/ec` (also `/engineerclient`; `/brw` still works) — `setup`, `roles`, `role <role>`,
`debug`, `log`, `log mark [note]`, plus the waypoint-profile commands below.

## Blood Rush Waypoints

Engineer Client registers its own **"Blood Rush Waypoints"** module into Odin's module system
(own ClickGUI panel, own config, own pack folder, own edit mode) — a fully separate sibling of
Odin's Dungeon Waypoints module. Both systems store, select, and render waypoints
independently: **a user's existing Odin waypoints are never touched and keep rendering
alongside this mod's.**

## How it works

- On first run the mod creates 40 blank waypoint packs named like `BR Mage 3p Door` /
  `BR Berserker 4p NoDoor` (5 classes x 2/3/4/5 players-on-rush x dedicated-door), stored in
  `config/engineerclient/waypoints/`. The file format is identical to Odin's packs, so packs
  can be copied between the two systems.
- Your class is read from Odin's tab-list parsing. Players-on-rush and dedicated-door are manual
  choices (they encode team strategy, not game state).
- When the profile resolves, the mod selects the matching pack (plus any custom packs you keep
  enabled) in its own module and sets it as the edit pack — so the in-world editor always edits
  the active profile.
- If tab detection misses (roughly 1 run in 200), the last successfully detected class is used and
  the miss is announced in chat and counted (`/ec status`).

## Usage

- `/EngineerClient` (or `/engineerclient`, aliases `/ec` and `/brw`) — profile GUI (class override,
  players-on-rush, dedicated door, custom packs).
- `/ec status` — detection state, active pack, miss count.
- `/ec class <auto|Mage|Archer|Berserker|Healer|Tank>`, `/ec players <2-5>`,
  `/ec door <on|off>`, `/ec apply` — the same knobs as commands.
- Waypoint editing, colors, sizes, editor HUD, and the type-cycle keybind live in the
  **Blood Rush panel of Odin's ClickGUI** (the module mirrors Odin's Dungeon Waypoints controls:
  enable *Allow Edits*, right-click blocks to place/remove, sneak-right-click for titles).
- If both this mod's and Odin's edit modes are on at once, this mod's editor stands down and says
  so, so a right-click never places into two systems.

The module is enabled automatically on first install; it can be toggled like any Odin module.
Installs that ran the mod under its original "ascent" name migrate automatically (config folder,
waypoint packs, and Odin addon settings are moved on first launch).

## Build / deploy

- `./gradlew build` — needs a JDK 26 at `/usr/lib/jvm/java-26-openjdk` (see `gradle.properties`);
  bytecode targets Java 25 to match Odin.
- `./deploy.sh` — builds and atomically installs into the `26.1.2 Blood Rush` Prism instance
  (override with `EC_MODS_DIR`).
- Compiles against `libs/Odin-0.3.1.jar` (BSD-3-Clause). The waypoint module is a vendored copy of
  Odin's DungeonWaypoints — see `src/main/kotlin/com/engineerclient/waypoints/VENDORED.md` for
  the upstream pin, the deliberate divergences, and the re-vendoring procedure.

## Phase-3 rotation (M7 terminals)

The `Blood Rush Roles` module runs the team's phase-3 role rotation. The strategy is **data**:
`src/main/resources/rotation/p3.json`, authored in the rotation editor and shipped inside the jar so
every client provably runs the same graph. Roles hold task lists; pots hand out the next role in
finish order; exits carry `cond` (role just finished), `last` (reserved for the final arrival — must
sit at position 1), and `mask` (invincibilities the taker needs, scaled to what the party has).

- Every decision derives from party/system chat that all five clients see in the same order —
  completions, `brw s1 <role>` starting-role announcements, Odin's proc and leap announcements, and
  the `/posmsg` arrival texts. Nothing local is used directly, so the clients cannot disagree.
- `/ec role <role>` sets **your** starting role (announced on entering the boss room). `/ec setup`
  reads Odin's live settings and reports what is wrong. `/ec debug` dumps the engine's state.
- HUD: **Your Role**, **Debug HUD**. The leap menu rings who to leap to — soft until they have
  announced arrival, solid after — with a sound and screen-edge flash.
- Each session writes `logs/engineerclient/ec-<stamp>.log`; `./gradlew replay -Plog=<file>` replays it through
  the real engine and diffs against what ran live. `/ec log mark <note>` stamps a note into it.
- `./gradlew test` drives 20,000 random finish schedules through the engine (~3s).
