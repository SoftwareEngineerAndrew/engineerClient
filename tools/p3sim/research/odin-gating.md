# Odin gating for p3sim (what makes Odin believe "F7 boss, P3")

Sources:
- Vendored jar `libs/Odin-0.3.4-26.1.jar` is version 0.3.4, which is commit `38ddc1b` of `/home/cam/Projects/Odin`.
- The 5 local commits after it only touch `LeapMenu.kt`/`LeapMap.kt`. Everything below matches the jar; I spot-checked it with `javap`. Read LeapMenu with `git show 38ddc1b:...`.
- Packages: `U = com.odtheking.odin.utils.skyblock`, `D = U.dungeon`.

## 0. Key fact: singleplayer already passes most location gates

`U.LocationUtils` (Kotlin object):
- `on<LevelEvent.Load>` runs `currentArea = if (mc.isSingleplayer) Island.SinglePlayer else Island.Unknown`, then sets `isInSkyblock = false` and `lobbyId = null`.
- `fun isCurrentArea(vararg areas: Island) = currentArea == Island.SinglePlayer || areas.any { currentArea == it }`.
- So in any SP world, **every** `isCurrentArea(X)` is true. That includes Dungeon, Kuudra, Garden and the rest, so `DungeonUtils.inDungeons` is already true.

`D.DungeonUtils.getF7Phase()`:
```kotlin
if ((!isFloor(7) || !inBoss) && !LocationUtils.isCurrentArea(Island.SinglePlayer)) return M7Phases.Unknown
y > 210 -> P1; y > 155 -> P2; y > 100 -> P3; y > 45 -> P4; else P5   // mc.player.y
```
In SP the phase is **purely Y-based**. 100 < y ≤ 155 gives P3, with no floor or boss check.

**These are NOT true in SP by default:**

| Value | Default in SP | Why |
|---|---|---|
| `DungeonListener.floor` | `null` | `isFloor(7)` is false and `getBoss()` returns false |
| `DungeonListener.inBoss` | false | needs `floor` set |
| `dungeonTeammates`, `leapTeammates` | empty | Leap menu won't take over |
| `LocationUtils.currentArea == Island.Dungeon` | false | exact-equality checks fail (Odin `SplitsManager.startRun`, engineerClient `OdinSplitsLook.place`) |
| `isInSkyblock` | false | |
| `TickEvent.Server` | never fires | see §1.7 |

## 1. How Odin derives each value (inputs)

### 1.1 Island and Skyblock flag (`U.LocationUtils`)
- **Backing fields** (private static): `isInSkyblock: Z`, `currentArea: Lcom/odtheking/odin/utils/skyblock/Island;`, `lobbyId`.
- **Getters:** `isInSkyblock()Z`, `getCurrentArea()`. The setter `setCurrentArea(Island)` is private and posts `LocationChangeEvent` when the value is not Unknown.
- **Area:** read from `ClientboundPlayerInfoUpdatePacket`, but only while `currentArea == Unknown` and the actions include `UPDATE_DISPLAY_NAME`.
  - Odin finds an entry whose `displayName.string` starts with `"Area: "` or `"Dungeon: "`.
  - The island is the first `Island.entries` value whose `displayName` the line contains, ignoring case. Dungeon's displayName is `"Catacombs"`.
  - **Ignored in SP**, because the area is SinglePlayer rather than Unknown.
- **Skyblock flag:** `ClientboundSetObjectivePacket` with `objectiveName == "SBScoreboard"` sets `isInSkyblock = true`.
- **lobbyId:** a `ClientboundSetPlayerTeamPacket` whose prefix+suffix (control codes stripped) matches `\d\d/\d\d/\d\d (\w{0,6}) *`. Only read while the area is Unknown.

### 1.2 Floor, inBoss and stats (`D.DungeonListener`)
Public `var`s with public setters are `dungeonTeammates: ArrayList<DungeonPlayer>`, `dungeonTeammatesNoSelf: List`, `leapTeammates: List`, `dungeonStats`, `puzzles`, `floor: Floor?`, `inBoss: Boolean` and `paul`.

- **floor:** from `ClientboundSetPlayerTeamPacket`. Prefix+suffix (no control codes) is matched against `The Catacombs \((\w+)\)$`, then `Floor.valueOf(group1)` (`"F7"` / `"M7"`).
  - This only happens while `floor == null`.
  - Side effects: it fires `FloorEnterEvent`, which triggers `DungeonScan.initClient` and a websocket connect in `ClickGUIModule` if `lobbyId` is set. It also starts a web request, `hasBonusPaulScore()`.
- **inBoss:** `on<TickEvent.End>`. If `inDungeons`, then `inBoss = getBoss()`, where `private fun getBoss()` checks floor 7 with `x > -7 && z > -7` (player position). Floor 1: `x>-71&&z>-39`; floors 2–4: `x>-39&&z>-39`; floors 5–6: `x>-39&&z>-7`. It returns false when `floor == null`.
- **Stats:**
  - Tab entries' display names feed puzzles, secrets and so on.
  - Team prefix/suffix strings feed `Cleared: N% (..)` and `Time Elapsed: ...`.
  - Tablist footer feeds blessings.
  - Chat (`MessageEvent.Chat`, gated on `inDungeons`) feeds deaths, the door opener and `Party > ...: mimic killed` and similar lines.
- **Reset:** `on<LevelEvent.Load>` clears everything: `floor = null`, `inBoss = false`, teammates cleared.

### 1.3 Teammates and class (`DungeonUtils.getDungeonTeammates`)
- **Trigger:** `DungeonListener` `onReceive<ClientboundPlayerInfoUpdatePacket>` collects `entries().mapNotNull { it.displayName?.string }`. It returns early if that list is empty, which is the vanilla SP case.
- **Parsing:**
  - Each line must match `^\[(\d+)] (?:\[\w+] )*(\w+) .*?\((\w+)(?: (\w+))*\)$`, for example `[300] [MVP+] Name ♲ (Mage L)`.
  - The class is `DungeonClass.entries.find { name.equals(clazz, true) }`, one of ARCHER/BERSERK/HEALER/MAGE/TANK. `"DEAD"` sets `isDead`.
  - The level is `romanToInt`.
- **New players need `mc.connection.getPlayerInfo(name) != null`**, meaning a real tab entry whose profile name is that name. Skin is `playerInfo.skin`; entity is `level.getPlayerByUUID`.
- **Then, on `mc.execute`:**
  - `dungeonTeammatesNoSelf` is the list without `mc.player.name`.
  - `leapTeammates` is sorted by `LeapMenu.type`:
    - 0 = `odinSorting` (by class `defaultQuadrant`)
    - 1 = class ordinal then name
    - 2 = name
    - 3 = `customLeapOrder`
- **Self entry:** `DungeonUtils.currentDungeonPlayer` is the teammate whose `name == mc.player.name`, or else `DungeonPlayer(name, EMPTY, 0, null)`.
- **Entity linking:** `EntityEvent.Add` links a teammate's `entity` by name, but only when `entity.uuid.version() != 4`.
- **Kotlin constructor:** `DungeonPlayer(name: String, clazz: DungeonClass, clazzLvl: Int, playerSkin: PlayerSkin?, entity: Player? = null, isDead = false, deaths = 0, mapPos: IVec2, yaw = 0f)`. The primary constructor is private on the JVM because `IVec2` is a value class, so call it from Kotlin with defaults.

### 1.4 Inline getters (matters for mixins)
Everything in `DungeonUtils` (`inDungeons`, `inBoss`, `inClear`, `floor`, `dungeonTeammates`, `leapTeammates`, `currentDungeonPlayer`) is an **`inline val`**. Callers compile it inline, so a mixin on `DungeonUtils.getInDungeons()` changes nothing.

The real read sites are:

| Inline value | Real read |
|---|---|
| `inDungeons` | `LocationUtils.isCurrentArea(Island.Dungeon)` |
| `inBoss` | `DungeonListener.getInBoss()` |
| `floor` | `DungeonListener.getFloor()` |
| `dungeonTeammates` | `DungeonListener.getDungeonTeammates()` |
| `leapTeammates` | `DungeonListener.getLeapTeammates()` |

Inside their own classes, `LocationUtils.isCurrentArea` and `DungeonListener.getBoss` read the **static fields directly** (`getstatic`), not the getters.

### 1.5 Chat input
- `MessageEvent.Chat` comes from Fabric `ClientReceiveMessageEvents.ALLOW_GAME` (non-overlay), with `message.string.noControlCodes`.
- So a server `player.sendSystemMessage(Component)` reaches every Odin chat parser.

### 1.6 Block updates and clicks
- `BlockUpdateEvent` is posted by `LevelChunkMixin` on `LevelChunk.setBlockState` HEAD, with **no level check**.
- On the integrated server it fires for **server-thread** chunk changes too, so it fires twice and once off-thread.
- Odin `SimonSays$3` mutates an `ArrayList` from that handler. This is a CME risk while it renders.
- engineerClient's `SimonSaysBlocksMixin` cancels that handler when `OdinSimonSays.active()`.

### 1.7 Server tick
- `TickEvent.Server` is posted only by `ConnectionMixin.channelRead0` for a `ClientboundPingPacket` with `getId() != 0`.
- A vanilla integrated server never sends these. **p3sim must send `new ClientboundPingPacket(n)` with n != 0 every server tick**, as Hypixel does. The client's pong is harmless.
- These features stall without it:
  - Odin `SimonSays$4` grid-reset detection
  - `TerminalHandler.ticksOpened` (first-click tick protection)
  - `SplitsManager` tick counts and Odin `TickTimers`
  - engineerClient `P3Rotation`, `StormPhase`, `DungeonSplits`, `TermInfo`, `OdinSimonSays` tick count and BetterPF server ticks

## 2. Terminal solver

### 2.1 Gate
`D.terminals.TerminalUtils`, `on<ScreenEvent.Open>(HIGHEST)` calls `TerminalTypes.openHandler(screen.title.string)`. **Only the window title matters. There is no location, floor, boss or phase gate.**
- `currentTerm` (a `@JvmStatic` getter) is set and `TerminalEvent.Open` fires.
- `ScreenCloseEvent` clears it.

| Type | Title regex (exact) | windowSize | Handler |
|---|---|---|---|
| PANES | `^Correct all the panes!$` | 45 | PanesHandler |
| RUBIX | `^Change all to same color!$` | 45 | RubixHandler |
| NUMBERS | `^Click in order!$` | 36 | NumbersHandler |
| STARTS_WITH | `^What starts with: '(\w)'\?$` | 45 | StartsWithHandler(letter) |
| SELECT | `^Select all the ([\w ]+) items!$` | 54 | SelectAllHandler(DyeColor matched by name with `_`→space, ignoring case; `SILVER`→`LIGHT GRAY`) |
| MELODY | `^Click the button on time!$` | 54 | MelodyHandler |

### 2.2 Slot input
- `SetSlotEvent` is posted from `AbstractContainerMenuMixin` at `AbstractContainerMenu.setItem(int,int,ItemStack)` TAIL. It only counts when `menu === (mc.screen as AbstractContainerScreen).menu`.
- **`ClientboundContainerSetContentPacket` does NOT post it.** `initializeContents` uses `Slot.set`; I verified this in the 26.1.2 bytecode.
- Vanilla `sendAllDataToRemote()` on open sends only a content packet, so **Odin never solves.**
- **p3sim must send a `ClientboundContainerSetSlotPacket` per slot after opening, as Hypixel does.** Later changes through `broadcastChanges()` already go out as SetSlot packets.

`TerminalHandler.updateSlot`:
- It ignores an update when `slotIndex !in 0 until windowSize-9` or when the new item is `BLACK_STAINED_GLASS_PANE`.
- Otherwise it re-solves over `slots.subList(0, windowSize-9)`.

What each solver reads:

| Terminal | What it reads |
|---|---|
| Panes | `item == RED_STAINED_GLASS_PANE` means needs clicking. Hypixel turns them lime. |
| Numbers | red panes, sorted by `stack.count` (1..14). `canClick` only allows `solution.first()`. |
| Rubix | `StainedGlassPaneBlock.color` of each pane (not BLACK). Cycle order: ORANGE, YELLOW, GREEN, BLUE, RED. **The target colour is locked only when an update arrives for slot index 32 (`LAST_PANE_SLOT`)**, so send 32 last when slots are populated in order. Right-clicks only go to slots with negative counts (setting `rubixMode`). |
| Select | `hoverName.string.lowercase()` starts with a colour prefix, for example blue→`blue`/`lapis`, white→`white`/`bone`/`wool`, black→`black`/`ink`. It also requires `!hasGlint()`. |
| Starts-with | `hoverName.string.startsWith(letter, ignoreCase)` and (`!hasGlint()` or the item is in `enchantOverrides`). `enchantOverrides` = items whose *default* components contain `ENCHANTMENT_GLINT_OVERRIDE`, plus `GOLDEN_APPLE`. It also remembers its own clicks (`clickedOverrides`). |
| Melody | first `MAGENTA_STAINED_GLASS_PANE` (column marker), last `LIME_STAINED_GLASS_PANE` (moving pointer), last `LIME_TERRACOTTA`. `canClick` is only slots 16, 25, 34 and 43. |

- **"Already clicked" is the glint.** `ItemUtils.hasGlint()` is `components.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) != null`. So the server must set `ENCHANTMENT_GLINT_OVERRIDE=true` on clicked select/starts-with items. Real enchantments don't count.

### 2.3 Clicks
- **Input routes:**
  - Normal or Odin render mode: `GuiEvent.SlotClick` (from `AbstractContainerScreenMixin`) calls `currentTerm.click(slot, button, clickPrediction)` and cancels the vanilla click.
  - Custom GUI: `CustomGUIImpl` handles the mouse click, or drop/hotbar keys over the hovered slot.
- **Gates:** `click` returns early if `!canClick` or `shouldProtect()`.
  - `shouldProtect` blocks clicks for `firstClickProt` ms after open (default 500).
  - In non-SP areas it also blocks while `ticksOpened < firstClickProtTicks`, if `shouldFirstClickProtWithTicks` is on. `ticksOpened` counts `TickEvent.Server`, so it needs the pings from §1.7.
- **Button and packet:** the button becomes `MOUSE_BUTTON_RIGHT` (1) for a rubix right-click, and **`MOUSE_BUTTON_MIDDLE` (2) for everything else**. `mc.player.clickSlot` calls `gameMode.handleContainerInput(containerId, slot, button, CLONE if middle else PICKUP)`, which sends a `ServerboundContainerClickPacket`.
  - **The server must treat CLONE/button 2 as a terminal click.** Vanilla survival ignores CLONE, so handle it in your terminal menu's `clicked()`.
- **Click prediction (default on):** the clicked slot is removed from the solution locally. If no SetSlot arrives within `terminalReloadThreshold` (600 ms), `TerminalUtils` clears `clickedSlots` and re-solves.
- **Completion:** chat `^(.{1,16}) activated a terminal! \((\d)/(\d)\)$` with group1 == the player's own name fires `TerminalEvent.Solve` (TerminalTimes and others).
- **Custom GUI requirements:**
  - `TerminalSolver.customGuiEnabled = enabled && renderType == 2 ("Custom GUI") && !(cancelMelodySolver && type==MELODY)`
  - `isActiveTermScreen` also needs `currentTerm.type.getGUI() === this` and `mc.screen is AbstractContainerScreen`.
  - Nothing location-based.

## 3. Leap menu (`features.impl.dungeon.LeapMenu`, 0.3.4)
- **Screen gate:** `currentLeapScreen()` requires the module enabled, `mc.screen is AbstractContainerScreen`, `title.string` equal to `"Spirit Leap"` or `"Teleport to Player"`, and **`leapTeammates` non-empty and not all EMPTY**. That is the only DungeonUtils dependency.
- **Quadrants:** `leapTeammates[0..3]` map to top-left, top-right, bottom-left, bottom-right. Mouse quadrant is picked by screen halves.
  - Class keybinds pick `leapTeammates.indexOfFirst { clazz == ... }`.
  - The box is drawn from `player.playerSkin` (falls back to your own skin), `name`, `clazz` and `isDead`.
- **The click, `leapTo(name)`:**
  - It scans `menu.slots.subList(11, 16)`, i.e. **slots 11–15**.
  - It matches the first where `item.hoverName.string.substringAfter(' ').equals(name, ignoreCase)`, so the head name must be `"Name"` or `"[RANK] Name"`.
  - It then sends `clickSlot(index)` with button 0 and `PICKUP`.
- Item type isn't checked for the click. A `SetSlotEvent` mapping of `PLAYER_HEAD` slots by `^(?:\[.+?] )?(\w{1,16})$` exists but nothing reads it.
- **Leap announce:** chat `^You have teleported to (\w{1,16})!$` sends `/pc Leaped to X!` when `inDungeons`.
- engineerClient hooks this menu:
  - `LeapMenuClickMixin`/`LeapMenuReleaseMixin` → `LeapMenu$3`/`$4` (click delay).
  - `LeapMenuRenderMixin`.
  - `LeapHighlight`, `LeapExtras`, `PovCapture` and `RoleVignette` all read `DungeonUtils.leapTeammates`.

## 4. Item id, etherwarp and simon says
- **Item id:** `ItemStack.itemId = customData.getString("id").orElse("")`, where `customData = DataComponents.CUSTOM_DATA` copied as a tag. The **root** of custom_data is read, not ExtraAttributes. `itemUUID` is `"uuid"`.
- **Etherwarp item check:** `ItemStack.isEtherwarpItem()` returns the tag if `customData.getInt("ethermerge") == 1 || itemId == "ETHERWARP_CONDUIT"`.
- **Etherwarp range:** `57.0 + customData.getInt("tuned_transmission").orElse(0)`.
- **Etherwarp render:** on `RenderEvent.Extract`, only when no screen is open, while sneaking (unless it's the conduit), with no location gate. The traversal is client-side.
- **SP-only Etherwarp behaviour:** `onSend<ServerboundUseItemPacket>` runs only if `isCurrentArea(Island.SinglePlayer)`. Odin itself then sends `ServerboundMovePlayerPacket.PosRot(target+0.5, y+1.05, …)`, sets the client position and plays the sound. This **conflicts with a server-side etherwarp** (double teleport) for as long as the area stays SinglePlayer.
- **Simon Says (`boss.SimonSays`):**
  - Every listener is gated on `getF7Phase() == P3`.
  - The start button is at `(110,121,91)`. Lanterns are at x=111, y 120..123, z 92..95, with the sea-lantern→obsidian transition. Buttons are at x=110.
  - It uses `BlockUpdateEvent`, `TickEvent.Server`, `BlockInteractEvent` (from `MinecraftMixin`) and chat `[BOSS] Goldor: Who dares trespass into my domain?`.
  - It needs Hypixel coordinates.
- **Other P3-gated Odin modules (`getF7Phase()==P3`):** ArrowAlign, ArrowsDevice, InactiveWaypoints, MelodyMessage and RenderOptimizer.
- **Other inDungeons-gated modules:** TerminalSounds, Highlight teammateClassGlow and InvincibilityTimer.
- **Odin Splits:** needs `LocationUtils.currentArea == Island.Dungeon` exactly, `DungeonListener.floor != null`, and chat `Starting in 1 second.`. Splits then advance on chat in order: F7 is Maxor line → `[BOSS] Storm: Pathetic Maxor, just like expected.` → `[BOSS] Goldor: Who dares trespass into my domain?` (start of "Terminals") → `The Core entrance is opening!` (end of "Terminals"). It's preceded by the Mort/Blood/Portal splits.

## 5. Recommendation

### 5.1 Minimal and robust: field writes from a client-side `P3SimOdinBridge`, plus one mixin
`DungeonListener` exposes public setters, so writing the fields reaches every reader, including the internal `getstatic` ones. Mixins on getters don't.
- **When to run:** only while the p3sim world is active (`mc.hasSingleplayerServer()` plus your sim flag).
- **When to re-assert:** on Odin's `TickEvent.Start`, and after `LevelEvent.Load` (Odin resets there; use a low priority so it runs after Odin).
```kotlin
DungeonListener.floor = Floor.F7                 // don't post FloorEnterEvent (websocket + paul web call)
DungeonListener.inBoss = true                    // plus the getBoss mixin below so Odin's TickEvent.End can't flip it
val team = arrayListOf(DungeonPlayer(me, myClass, 50, mc.player?.skin), /* 4 fakes */)
DungeonListener.dungeonTeammates = team
DungeonListener.dungeonTeammatesNoSelf = team.filter { it.name != me }
DungeonListener.leapTeammates = LeapMenu.odinSorting(DungeonListener.dungeonTeammatesNoSelf).toList() // or mirror LeapMenu.type 1/2/3
// Optional fidelity (see 5.2): make it a real "Dungeon" instead of SinglePlayer
LocationUtils::class.java.getDeclaredField("currentArea").apply { isAccessible = true }.set(null, Island.Dungeon)
LocationUtils::class.java.getDeclaredField("isInSkyblock").apply { isAccessible = true }.setBoolean(null, true)
```
Reflection is used because the setters are private. The `access$setCurrentArea` bridges are synthetic, so javac/kotlinc can't call them. An `@Invoker` mixin would also work.

Set fake teammates' `entity` yourself if the server spawns player entities for them; Odin only auto-links entities with a non-v4 UUID. Use your real class for self, because engineerClient's `ClassDetect` writes `EcConfig.lastKnownClass` and calls `RushProfiles.applySelection`.

The mixin, in the existing style (add it to `engineerclient.odin.mixins.json` "client"):
```java
@Pseudo
@Mixin(targets = "com.odtheking.odin.utils.skyblock.dungeon.DungeonListener", remap = false)
public class DungeonListenerBossMixin {
    @Inject(method = "getBoss()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$p3simBoss(CallbackInfoReturnable<Boolean> cir) {
        if (P3Sim.active()) cir.setReturnValue(true);
    }
}
```
If the sim builds the boss at Hypixel coordinates (F7 boss at x>-7, z>-7, which Simon Says and other hardcoded coordinates need anyway), `getBoss()` is naturally true once `floor=F7`. In that case the mixin is just a safety net.

**If you'd rather override than write fields**, the exact targets are listed below. They're weaker, because internal reads use the fields directly.
- `LocationUtils`:
  - `getCurrentArea()Lcom/odtheking/odin/utils/skyblock/Island;`
  - `isCurrentArea([Lcom/odtheking/odin/utils/skyblock/Island;)Z`
  - `isInSkyblock()Z`
- `DungeonListener`:
  - `getFloor()Lcom/odtheking/odin/utils/skyblock/dungeon/Floor;`
  - `getInBoss()Z`
  - `getDungeonTeammates()Ljava/util/ArrayList;`
  - `getDungeonTeammatesNoSelf()Ljava/util/List;`
  - `getLeapTeammates()Ljava/util/List;`
  - `getBoss()Z`
- `DungeonUtils.getF7Phase()Lcom/odtheking/odin/utils/skyblock/dungeon/M7Phases;` is a real, non-inline method and can be overridden directly.

### 5.2 SinglePlayer vs Island.Dungeon
**Keep SinglePlayer (no area write):**
- `getF7Phase` stays Y-only.
- Odin Etherwarp fakes the teleport client-side.
- First-click tick protection is skipped.
- Every other island's features also think they're active.
- Odin Splits and engineerClient `OdinSplitsLook` stay off.

**Set Island.Dungeon (recommended for a faithful sim):**
- All code paths match Hypixel.
- Requirements: the per-tick pings (§1.7), a server-side etherwarp (Odin no longer teleports), and `floor=F7` with `inBoss=true` (otherwise getF7Phase is Unknown).

### 5.3 Server-side inputs p3sim must provide either way
1. A `ClientboundPingPacket(id != 0)` every server tick (§1.7).
2. Terminals:
   - Hypixel window titles (§2.1).
   - Per-slot `ClientboundContainerSetSlotPacket` after open; send rubix slot 32 last.
   - Accept CLONE/button 2 clicks.
   - Mark clicked select/starts-with items with `ENCHANTMENT_GLINT_OVERRIDE`.
   - On completion, chat `"<name> activated a terminal! (n/7)"` (Hypixel uses `(n/7)` or `(n/8)`).
3. Leap: chest titled `Spirit Leap` or `Teleport to Player` with player heads in slots 11–15 named `[RANK] Name`. Handle PICKUP clicks, then chat `You have teleported to Name!`.
4. Register `/pc` (and `/p`, `/party`). Odin and engineerClient send `sendCommand("pc …")` in about 34 places (leap announce, SS progress, melody, `pc brw s1 <role>`); echo them as `Party > [MVP+] Name: msg`. engineerClient P3Rotation parses party chat.
5. Hypixel boss chat lines (Goldor, `The Core entrance is opening!`, and so on) as system messages.
6. Items: Hypixel `custom_data` at the root, with `"id"`, `"ethermerge":1` and `"tuned_transmission":N`.

**The pure fake-input route can't set the area.** Tab `Area:`/`Dungeon:` lines are ignored once the area is SinglePlayer. Floor and teammates can come from a fake team prefix (`The Catacombs (F7)`) and tab entries, but that triggers `FloorEnterEvent`, the websocket and the paul web call. So fields are better.

## 6. engineerClient features that switch on (grep of src/)
- **Floor 7 plus inBoss/P3:**
  - `rotation/P3Rotation` announces the role on entering boss (`inBoss` edge, `floor 7`) with `/pc brw s1 …`, runs SetupCheck, and has its P3 HUD (`getF7Phase()==P3`).
  - `practice/OdinSimonSays` and `SimonSaysPractice` (P3).
  - `misc/RandomStuff`: keeps P3 armor stands visible, mutes the completion ding (P3), mutes the party ping (`inBoss`) and adds terminal click sounds.
  - `misc/AgroLeaderboard` (phase).
  - `splits/DungeonSplits` (`inDungeons`; the pace needs `floor.name == "F7"`) and `splits/OdinSplitsLook` (needs `currentArea == Island.Dungeon` and floor 7).
  - `storm/StormPhase` (server ticks).
- **Teammates:** `leap/LeapExtras`, `rotation/LeapHighlight`, `RoleVignette`, `LeapSignal`, `pov/PovCapture`, `ClassDetect` (writes config).
- **No gate:** `practice/TermInfo` and `TermsimExtras` (`currentTerm`).
- **Recorders, which are a hazard:**
  - `betterpf/BetterPF` + `RunRecorder` confirms a run when `DungeonUtils.inDungeons`. That is **already true in every SP world**, and finished runs go to `BetterPF.upload` when `uploadRuns` is on. With `floor=F7` they'd be labelled F7 runs on undonecoffee.com.
  - `bossrecorder/BossRecorder`, `recorder/DungeonRecorder`/`EcRec` and `debug/ScoreboardRecorder` also start.
  - **Gate these off for p3sim**, for example by skipping `session = RunRecorder(...)` when `mc.hasSingleplayerServer()`.
- **Off in boss:** the clear-only features (`BrWaypoints2`, `BrwSecretWaypoints`, `BrwWaypointEditor`) stay off because `inClear = inDungeons && !inBoss`.
