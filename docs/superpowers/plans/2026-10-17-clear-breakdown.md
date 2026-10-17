# Clear Breakdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Engineer Client module that, at the end of a finished dungeon run, posts one chat line per party member (`Andrew: 3-5 12 secrets`) with hover text for their rooms (in clear order) and secrets per room, built from what this client and party members' clients see directly (relayed live through ADR Online), with Odin filling gaps, reconciled against Hypixel API totals; it replaces Odin's Secrets Counter messages.

**Architecture:** Headless classes hold every rule and are unit-tested: `ClearCredit` (room helpers, solo, order), `SecretCredit` (direct secret events + Odin gap batches, merged by key, settled by elimination against API totals), `BreakdownText` (line and hovers) and `RelayCodec` (relay frames). The `ClearBreakdown` module feeds them from Minecraft packets, Odin events, its own listener on Odin's map-sync socket and `OnlineLink`. The site (`C:\Users\graha\adr`) gains one message type that it forwards between mutually-reported party members.

**Tech Stack:** Kotlin 2.4.20, Fabric Loom, Minecraft 26.3, Odin 0.3.7 (compileOnly, `libs/odin`), gson, kotlin-test + JUnit 5; site: Cloudflare Worker (plain JS ES modules), Node 24 `node --test` for its unit test.

**Spec:** `docs/superpowers/specs/2026-10-17-clear-breakdown-design.md`

## Global Constraints

- Module: `object ClearBreakdown : Module(name = "Clear Breakdown", category = Category.custom("Engineer Client", 860, 10), description = …, key = null)`, in `MODULES` (`src/EngineerClient.kt`) after `DungeonSplits`, and in the first-run enable list.
- Package `com.engineerclient.clearbreakdown`, folder `src/clearbreakdown/`; tests in `test/clearbreakdown/`.
- Counted rooms: every `RoomType` except `FAIRY` and `ENTRANCE`. Help = more than 40 client ticks in the room, cumulative, while uncleared, plus anyone present at the clearing checkmark. Cleared = first `WHITE`/`GREEN`; `RED` never credits and restarts that room's helper time.
- "In reach" = within 6 blocks. Secret kinds: chest, item, bat, essence.
- Sources, best first: this client's direct sightings → party members' relayed sightings → Odin (map icons, room counts, map sync) → API totals.
- Post only for a finished run; any other ending posts nothing; all state resets on `LevelEvent.Load`.
- Dead players (`DungeonPlayer.isDead`) are in no room and are never candidates.
- Never call `DungeonMap.syncSocket.onMessage`. Don't change ADR's recording or OnlineLink's existing frames.
- Relay: at most 1 frame per second per client, every frame ≤ 3500 chars (site limit 4096; 30 messages / 10 s shared with OnlineLink's own).
- Chat through `EngineerClient.msg(...)`; handlers in `EngineerClient.safely("clear breakdown …") { }`; packet handlers hop to the client thread with `EngineerClient.mc.execute` (as `src/adr/ADR.kt` ~150-205).
- Skip P3 Sim practice: `P3Sim.inSim && Practice.active`.
- Headless classes import nothing from Minecraft or Odin (gson is fine).
- Comment style: KDoc saying why, short `//` above non-obvious lines, British spelling, no emojis (see `src/splits/SplitsModel.kt`, `src/adr/OnlineLink.kt`).
- `$GRADLE` = `C:\Users\graha\.gradle\wrapper\dists\gradle-9.6.1-bin\4ticwg1pgcbps2hj28r8so764\gradle-9.6.1\bin\gradle.bat`, run from `C:\Users\graha\engineerClient`.
- Site commits go to `main` of `C:\Users\graha\adr` and are pushed straight away (user's standing rule; a push deploys). Mod commits stay local until the user says to push.

## Review Focus

1. **A failed run that still prints the Catacombs end line** (team wiped in boss): must post nothing. The post is gated on the `☠ Defeated ` boss-kill line (ADR's `RUN_END`, `src/adr/UploadPacker.kt` ~88) having been seen this run; verify in game that a wiped run doesn't print it. Test-free (Task 11 checklist).
2. **The same chest seen by two clients, or re-opened later:** counted once; an exact sighting replaces proximity candidates. Tests in Task 3.
3. **A puzzle failed, reset and solved:** helper time restarts at `RED`. Test in Task 2.
4. **A late jump in a room's count:** only the part not already explained by direct events becomes a gap batch, credited to everyone in the room since the previous count. Tests in Task 3.
5. **A relayed frame from someone not in the roster, or malformed:** ignored, never throws. Tests in Task 5; roster filter in Task 9.

---

### Task 1: Test harness

**Files:** Modify `build.gradle.kts`.

**Interfaces:** Produces a `test/` source set run by `$GRADLE test`.

- [ ] **Step 1:** In `build.gradle.kts` add `sourceSets.test { kotlin.setSrcDirs(listOf("test")); java.setSrcDirs(listOf("test")); resources.setSrcDirs(listOf<String>()) }`; dependencies `testImplementation(platform("org.junit:junit-bom:5.13.4"))`, `testImplementation(kotlin("test-junit5"))`, `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`; and `test { useJUnitPlatform() }` in `tasks`. One `//` comment: headless model classes are tested here; nothing in `test/` touches Minecraft.
- [ ] **Step 2:** Add a throwaway `test/clearbreakdown/HarnessTest.kt` (`@Test fun runs() = assertEquals(2, 1 + 1)`).
- [ ] **Step 3:** Run `& $GRADLE test --console=plain`. Expected: `BUILD SUCCESSFUL`, `build/test-results/test/TEST-com.engineerclient.clearbreakdown.HarnessTest.xml` with `failures="0"`. If the main compile fails first, stop and report (pre-existing).
- [ ] **Step 4:** Delete `HarnessTest.kt`; `git add build.gradle.kts; git commit -m "Tests: a test/ source set (kotlin-test, JUnit 5) for headless model classes"`.

---

### Task 2: ClearCredit

**Files:** Create `src/clearbreakdown/ClearCredit.kt`; test `test/clearbreakdown/ClearCreditTest.kt`.

**Interfaces:**
```kotlin
enum class Mark { WHITE, GREEN, RED, OTHER }
data class Clear(val room: String, val solo: Boolean)          // room = display name
class ClearCredit(private val helpTicks: Int = 40) {
    fun tick(presence: Map<String, String?>)                     // name -> room key; absent/null = none
    fun onCheckmark(roomKey: String, roomName: String, mark: Mark) // module calls it for counted types only
    fun clears(): Map<String, List<Clear>>                       // only players with clears; checkmark order
    fun reset()
}
```

- [ ] **Step 1: Failing tests** (helper `fun ClearCredit.ticks(n: Int, vararg p: Pair<String, String?>) = repeat(n) { tick(mapOf(*p)) }`):

```kotlin
@Test fun soloClear() { c.ticks(100, "A" to "r1"); c.onCheckmark("r1", "Mirror", Mark.WHITE)
    assertEquals(mapOf("A" to listOf(Clear("Mirror", true))), c.clears()) }
@Test fun leaverAndFinisherBothCreditedNeitherSolo() {
    c.ticks(200, "B" to "r1"); c.ticks(5, "B" to null); c.ticks(100, "A" to "r1"); c.onCheckmark("r1", "Mirror", Mark.WHITE)
    assertEquals(listOf(Clear("Mirror", false)), c.clears()["A"]); assertEquals(listOf(Clear("Mirror", false)), c.clears()["B"]) }
@Test fun runThroughUnderTwoSecondsDoesNotCount() {
    c.ticks(40, "B" to "r1"); c.ticks(100, "A" to "r1", "B" to null); c.onCheckmark("r1", "Mirror", Mark.WHITE)
    assertEquals(null, c.clears()["B"]); assertEquals(true, c.clears()["A"]!!.single().solo) }
@Test fun timeIsCumulativeAcrossVisits() {
    c.ticks(30, "B" to "r1"); c.ticks(10, "B" to null); c.ticks(30, "B" to "r1"); c.ticks(1, "B" to null)
    c.ticks(5, "A" to "r1"); c.onCheckmark("r1", "Mirror", Mark.WHITE)
    assertEquals(listOf(Clear("Mirror", false)), c.clears()["B"]) }
@Test fun presentAtCheckmarkAlwaysHelps() { c.ticks(3, "A" to "r1"); c.onCheckmark("r1", "Mirror", Mark.WHITE)
    assertEquals(listOf(Clear("Mirror", true)), c.clears()["A"]) }
@Test fun whiteThenGreenCreditedOnce() { c.ticks(50, "A" to "r1"); c.onCheckmark("r1", "M", Mark.WHITE); c.onCheckmark("r1", "M", Mark.GREEN)
    assertEquals(1, c.clears()["A"]!!.size) }
@Test fun redNeverCreditsAndRestartsTime() {
    c.ticks(100, "B" to "r1"); c.onCheckmark("r1", "Water Board", Mark.RED); c.ticks(1, "B" to null)
    assertEquals(emptyMap(), c.clears())
    c.ticks(60, "A" to "r1"); c.onCheckmark("r1", "Water Board", Mark.WHITE)
    assertEquals(null, c.clears()["B"]); assertEquals(listOf(Clear("Water Board", true)), c.clears()["A"]) }
@Test fun orderIsCheckmarkOrder() { c.ticks(50, "A" to "r2"); c.ticks(50, "A" to "r1")
    c.onCheckmark("r1", "One", Mark.WHITE); c.onCheckmark("r2", "Two", Mark.GREEN)
    assertEquals(listOf("One", "Two"), c.clears()["A"]!!.map { it.room }) }
@Test fun timeInClearedRoomDoesNothing() { c.ticks(50, "A" to "r1"); c.onCheckmark("r1", "One", Mark.WHITE); c.ticks(100, "B" to "r1")
    c.onCheckmark("r1", "One", Mark.GREEN); assertEquals(null, c.clears()["B"]) }
@Test fun otherMarksIgnored() { c.ticks(50, "A" to "r1"); c.onCheckmark("r1", "One", Mark.OTHER); assertEquals(emptyMap(), c.clears()) }
```

- [ ] **Step 2:** `& $GRADLE test --tests "com.engineerclient.clearbreakdown.ClearCreditTest" --console=plain` → FAIL (unresolved).
- [ ] **Step 3: Implement.** Per room key: ticks per player; set of credited keys; ordered list of `(roomName, helpers)`; last presence map. `tick` adds 1 per present player to their room unless credited. `RED` clears that room's ticks. `WHITE`/`GREEN` on an uncredited room: helpers = ticks > `helpTicks` ∪ players whose last presence is this room; solo = one helper.
- [ ] **Step 4:** Re-run → PASS (10 tests).
- [ ] **Step 5:** Commit `src/clearbreakdown/ClearCredit.kt test/clearbreakdown/ClearCreditTest.kt`: "Clear Breakdown: who helped clear each room (2 s in it before the checkmark), solo or not, in order".

---

### Task 3: SecretCredit

**Files:** Create `src/clearbreakdown/SecretCredit.kt`; test `test/clearbreakdown/SecretCreditTest.kt`.

**Interfaces:**
```kotlin
data class RoomSecrets(val room: String, val count: Int, val estimate: Boolean)
data class PlayerSecrets(val total: Int?, val rooms: List<RoomSecrets>, val unknown: Int)
class SecretCredit(private val mergeMs: Long = 1000) {
    fun tick(presence: Map<String, String?>)
    /** A secret seen directly (this client or relayed). exact = candidates is the known finder. */
    fun sighting(key: String, roomKey: String, roomName: String, candidates: Set<String>, exact: Boolean, atMs: Long)
    /** A room's found count seen via Odin (action bar, map sync, green = max). */
    fun observe(roomKey: String, roomName: String, found: Int)
    fun resolve(apiTotals: Map<String, Int?>): Map<String, PlayerSecrets>   // one entry per apiTotals key
    fun reset()
}
```

- [ ] **Step 1: Failing tests** (`s = SecretCredit()`):

```kotlin
// Direct sightings
@Test fun exactSightingIsCertain() { s.sighting("item:1", "r1", "Mirror", setOf("A"), true, 0)
    assertEquals(PlayerSecrets(1, listOf(RoomSecrets("Mirror", 1, false)), 0), s.resolve(mapOf("A" to 1, "B" to 0))["A"]) }
@Test fun proximityTieSettledByElimination() { s.sighting("chest:1,2,3", "r1", "Mirror", setOf("A", "B"), false, 0)
    assertEquals(listOf(RoomSecrets("Mirror", 1, false)), s.resolve(mapOf("A" to 0, "B" to 1))["B"]!!.rooms) }
@Test fun exactReplacesProximity() { s.sighting("chest:1", "r1", "M", setOf("A", "B"), false, 0); s.sighting("chest:1", "r1", "M", setOf("A"), true, 5000)
    assertEquals(listOf(RoomSecrets("M", 1, false)), s.resolve(mapOf("A" to 5, "B" to 5))["A"]!!.rooms) }
@Test fun laterProximitySightingOfSameKeyIgnored() { s.sighting("chest:1", "r1", "M", setOf("A"), false, 0); s.sighting("chest:1", "r1", "M", setOf("B"), false, 5000)
    val r = s.resolve(mapOf("A" to 5, "B" to 5)); assertEquals(1, r["A"]!!.rooms.single().count); assertEquals(emptyList(), r["B"]!!.rooms) }
@Test fun sameMomentProximitySightingsUnion() { s.sighting("chest:1", "r1", "M", setOf("A"), false, 0); s.sighting("chest:1", "r1", "M", setOf("B"), false, 400)
    val r = s.resolve(mapOf("A" to 0, "B" to 1)); assertEquals(1, r["B"]!!.rooms.single().count) }
@Test fun emptyCandidatesCreditNobody() { s.sighting("bat:1", "r1", "M", emptySet(), false, 0); assertEquals(emptyList(), s.resolve(mapOf("A" to 1))["A"]!!.rooms) }
// Odin gap batches
@Test fun riseExplainedByDirectEventsMakesNoGap() { s.tick(mapOf("A" to "r1", "B" to "r1"))
    s.sighting("item:1", "r1", "M", setOf("A"), true, 0); s.observe("r1", "M", 1)
    assertEquals(emptyList(), s.resolve(mapOf("A" to 1, "B" to 9))["B"]!!.rooms) }
@Test fun riseBeyondDirectEventsIsAGapForEveryoneSinceLastCount() {
    s.tick(mapOf("B" to "r1")); s.tick(mapOf("B" to null)); s.tick(mapOf("A" to "r1"))
    s.sighting("item:1", "r1", "One", setOf("A"), true, 0); s.observe("r1", "One", 6)
    val r = s.resolve(mapOf("A" to 1, "B" to 5))
    assertEquals(listOf(RoomSecrets("One", 1, false)), r["A"]!!.rooms); assertEquals(listOf(RoomSecrets("One", 5, false)), r["B"]!!.rooms) }
@Test fun leftBeforePreviousCountIsNotAGapCandidate() {
    s.tick(mapOf("B" to "r1")); s.tick(mapOf("B" to null, "A" to "r1")); s.observe("r1", "One", 1)
    s.tick(mapOf("A" to "r1")); s.observe("r1", "One", 2)
    assertEquals(listOf(RoomSecrets("One", 2, false)), s.resolve(mapOf("A" to 2, "B" to 0))["A"]!!.rooms) }
@Test fun lowerOrEqualCountIgnored() { s.tick(mapOf("A" to "r1")); s.observe("r1", "One", 3); s.observe("r1", "One", 2); s.observe("r1", "One", 3)
    assertEquals(3, s.resolve(mapOf("A" to 3))["A"]!!.rooms.sumOf { it.count }) }
@Test fun gapWithNobodyCreditsNobody() { s.observe("r1", "One", 3); assertEquals(emptyList(), s.resolve(mapOf("A" to 3))["A"]!!.rooms) }
// Settling
@Test fun eliminationChains() {
    s.sighting("c:1", "r1", "One", setOf("A", "B"), false, 0); s.sighting("c:2", "r2", "Two", setOf("B", "C"), false, 0)
    val r = s.resolve(mapOf("A" to 0, "B" to 1, "C" to 1))
    assertEquals(listOf(RoomSecrets("One", 1, false)), r["B"]!!.rooms); assertEquals(listOf(RoomSecrets("Two", 1, false)), r["C"]!!.rooms) }
@Test fun tieSplitsProportionallyAsEstimate() { s.tick(mapOf("A" to "r1", "B" to "r1")); s.observe("r1", "One", 4)
    val r = s.resolve(mapOf("A" to 3, "B" to 1))
    assertEquals(listOf(RoomSecrets("One", 3, true)), r["A"]!!.rooms); assertEquals(listOf(RoomSecrets("One", 1, true)), r["B"]!!.rooms) }
@Test fun unseenSecretsAreUnknownRooms() { s.sighting("i:1", "r1", "One", setOf("A"), true, 0)
    assertEquals(PlayerSecrets(5, listOf(RoomSecrets("One", 1, false)), 4), s.resolve(mapOf("A" to 5))["A"]) }
@Test fun certainAboveApiTotalKeepsRoomsAndApiHeadline() { repeat(4) { s.sighting("i:$it", "r1", "One", setOf("A"), true, 0) }
    assertEquals(PlayerSecrets(2, listOf(RoomSecrets("One", 4, false)), 0), s.resolve(mapOf("A" to 2))["A"]) }
@Test fun apiFailureMeansUnlimitedAndNullTotal() { s.sighting("c:1", "r1", "One", setOf("A", "B"), false, 0)
    assertEquals(PlayerSecrets(null, listOf(RoomSecrets("One", 1, false)), 0), s.resolve(mapOf("A" to null, "B" to 0))["A"]) }
@Test fun roomsMergeAndKeepFirstSeenOrder() { s.sighting("i:1", "r2", "Two", setOf("A"), true, 0); s.sighting("i:2", "r1", "One", setOf("A"), true, 0); s.sighting("i:3", "r2", "Two", setOf("A"), true, 0)
    assertEquals(listOf(RoomSecrets("Two", 2, false), RoomSecrets("One", 1, false)), s.resolve(mapOf("A" to 3))["A"]!!.rooms) }
@Test fun everyRosterNameGetsAnEntry() { assertEquals(PlayerSecrets(0, emptyList(), 0), s.resolve(mapOf("A" to 0))["A"]) }
```

- [ ] **Step 2:** `& $GRADLE test --tests "com.engineerclient.clearbreakdown.SecretCreditTest" --console=plain` → FAIL.
- [ ] **Step 3: Implement.** The decisions the tests don't fully pin:
  - **Events by key:** a new key creates a 1-unit event `(roomKey, roomName, candidates, exact, atMs)` and adds 1 to `directSince[roomKey]`. Same key again: if the new one is exact and the stored one isn't → replace candidates, mark exact; else if neither is exact and `|atMs - stored.atMs| <= mergeMs` → union; otherwise ignore.
  - **Presence:** `tick` adds each present player to `visited[room]` and remembers the presence map.
  - **observe:** if `found > lastSeen[room]` (default 0): `gap = found - lastSeen - directSince[room]`; if `gap > 0`, record a gap batch of `gap` units with candidates `visited[room]` (not exact); then `lastSeen = found`, `directSince[room] = 0`, `visited[room] = players present in it now`.
  - **resolve:** units = 1 per event + `gap` per batch. (1) `left[p] = api[p] ?: Int.MAX_VALUE`. (2) units with one candidate are certain; subtract from `left` (floor 0). (3) repeat until a full pass changes nothing: for each unsettled unit, eligible = candidates with `left > 0` (and in `apiTotals`); one → credit, `left -= 1`; none → drop the unit. (4) For each remaining group of units (per event/batch, in creation order), split among eligible in proportion to `left` (equal shares when any is `MAX_VALUE`), largest-remainder rounding, ties to the earlier candidate (sorted by name), each share capped at `left`; credited as estimate. (5) Per player, merge by room name in first-credited order (estimate if any part is). `total = api[p]`; `unknown = max(0, api[p] - credited)` when known, else 0.
- [ ] **Step 4:** Re-run → PASS (19 tests).
- [ ] **Step 5:** Commit: "Clear Breakdown: secrets per room from direct sightings and Odin's counts, settled against the API totals".

---

### Task 4: BreakdownText

**Files:** Create `src/clearbreakdown/BreakdownText.kt`; test `test/clearbreakdown/BreakdownTextTest.kt`.

**Interfaces:**
```kotlin
data class LineParts(val name: String, val rooms: String, val roomsHover: String, val secrets: String, val secretsHover: String)
object BreakdownText { fun line(name: String, colour: Char, clears: List<Clear>, secrets: PlayerSecrets?): LineParts }
```
(`colour` is `DungeonClass.colorCode`, a `Char`; strings use `§` codes; `name` includes its colour and the trailing `: `.)

- [ ] **Step 1: Failing tests** (assert on text with `Regex("§.")` stripped):

```kotlin
@Test fun headline() { val p = BreakdownText.line("Andrew", 'b', listOf(Clear("A", true), Clear("B", false)), PlayerSecrets(12, emptyList(), 12))
    assertEquals("Andrew: ", strip(p.name)); assertEquals("1-2", strip(p.rooms)); assertEquals("12 secrets", strip(p.secrets)) }
@Test fun roomsHoverInOrderMarksSolo() { assertEquals(listOf("1. Mirror (solo)", "2. Cell"),
    strip(BreakdownText.line("A", 'f', listOf(Clear("Mirror", true), Clear("Cell", false)), null).roomsHover).lines()) }
@Test fun noClears() { assertEquals("No rooms cleared", strip(BreakdownText.line("A", 'f', emptyList(), null).roomsHover)) }
@Test fun secretsHover() { assertEquals(listOf("4 Prison Cell", "~2 Mirror", "3 in unknown rooms"), strip(BreakdownText.line("A", 'f', emptyList(),
    PlayerSecrets(9, listOf(RoomSecrets("Prison Cell", 4, false), RoomSecrets("Mirror", 2, true)), 3)).secretsHover).lines()) }
@Test fun apiFailedIsPartial() { val p = BreakdownText.line("A", 'f', emptyList(), PlayerSecrets(null, listOf(RoomSecrets("Mirror", 2, false)), 0))
    assertEquals("? secrets", strip(p.secrets)); assertEquals(listOf("Hypixel API didn't answer; seen in rooms:", "2 Mirror"), strip(p.secretsHover).lines()) }
@Test fun noSecrets() { assertEquals("No secrets", strip(BreakdownText.line("A", 'f', emptyList(), PlayerSecrets(0, emptyList(), 0)).secretsHover)) }
```

- [ ] **Step 2:** Run `--tests "com.engineerclient.clearbreakdown.BreakdownTextTest"` → FAIL.
- [ ] **Step 3: Implement** `BreakdownText.line`; `secrets == null` reads as an API failure with no rooms. Colours: builder's choice (name in `§<colour>`, numbers white, hover headings grey).
- [ ] **Step 4:** Re-run → PASS (6 tests).
- [ ] **Step 5:** Commit: "Clear Breakdown: the chat line (solo-total, secrets) and its two hovers".

---

### Task 5: RelayCodec

**Files:** Create `src/clearbreakdown/RelayCodec.kt`; test `test/clearbreakdown/RelayCodecTest.kt`.

**Interfaces:**
```kotlin
sealed interface Relayed {
    data class Secret(val key: String, val roomKey: String, val roomName: String, val candidates: List<String>, val exact: Boolean) : Relayed
    /** roomKey: a room, "" = seen but in no room, null = no longer seen by the sender. */
    data class Presence(val player: String, val roomKey: String?) : Relayed
}
object RelayCodec {
    /** Client -> site frames: {"t":"cb","d":[…]}, each at most maxChars. */
    fun frames(items: List<Relayed>, maxChars: Int = 3500): List<String>
    /** Site -> client frame {"t":"cb","from":name,"d":[…]}: the sender and its items, or null if it isn't one. */
    fun decode(text: String): Pair<String, List<Relayed>>?
}
```
Wire items: secret `{"s":key,"r":roomKey,"n":roomName,"c":[names],"x":true|false}`, presence `{"p":name,"r":roomKey}` with `r` omitted for `null`.

- [ ] **Step 1: Failing tests:**

```kotlin
@Test fun roundTrip() { val items = listOf(Relayed.Secret("chest:1,2,3", "k1", "Mirror", listOf("A", "B"), false), Relayed.Presence("A", "k1"), Relayed.Presence("B", ""), Relayed.Presence("C", null))
    val f = RelayCodec.frames(items).single()
    val relayed = JsonParser.parseString(f).asJsonObject.apply { addProperty("from", "Bob") }.toString()
    assertEquals("Bob" to items, RelayCodec.decode(relayed)) }
@Test fun splitsUnderLimit() { val items = (1..400).map { Relayed.Secret("item:$it", "k$it", "Room $it", listOf("Player$it"), true) }
    val fs = RelayCodec.frames(items); assertTrue(fs.size > 1); assertTrue(fs.all { it.length <= 3500 })
    assertEquals(items, fs.flatMap { RelayCodec.decode(JsonParser.parseString(it).asJsonObject.apply { addProperty("from", "x") }.toString())!!.second }) }
@Test fun emptyMakesNoFrames() { assertEquals(emptyList(), RelayCodec.frames(emptyList())) }
@Test fun garbageIsNull() { assertNull(RelayCodec.decode("pong")); assertNull(RelayCodec.decode("{\"t\":\"online\"}")); assertNull(RelayCodec.decode("{\"t\":\"cb\",\"d\":[]}")) }
@Test fun badItemsSkipped() { assertEquals("Bob" to listOf(Relayed.Presence("A", "k")),
    RelayCodec.decode("""{"t":"cb","from":"Bob","d":[{"s":1},{"q":2},{"p":"A","r":"k"}]}""")) }
```

- [ ] **Step 2:** Run `--tests "com.engineerclient.clearbreakdown.RelayCodecTest"` → FAIL (unresolved `RelayCodec`). If it fails on `com.google.gson` instead (Loom not putting Minecraft's libraries on the test classpath), add `testImplementation("com.google.code.gson:gson:2.13.1")` to `build.gradle.kts` and include it in this task's commit.
- [ ] **Step 3: Implement** with `com.google.gson` (`JsonParser`, `JsonObject`); `decode` never throws (catch and return null; skip items missing required fields or with wrong types).
- [ ] **Step 4:** Re-run → PASS (5 tests).
- [ ] **Step 5:** Commit: "Clear Breakdown: relay frames (secrets seen, where players are) to and from ADR Online".

---

### Task 6: The module: rooms, Odin counts, API and the post

**Files:** Create `src/clearbreakdown/ClearBreakdown.kt`, `src/clearbreakdown/OdinSync.kt`; modify `src/EngineerClient.kt`.

**Interfaces:**
- Consumes: Tasks 2-4. Odin: `CheckmarkUpdateEvent`, `SecretsUpdateEvent`, `FloorEnterEvent`, `LevelEvent.Load`, `TickEvent.End`, `DungeonUtils.dungeonTeammates/inDungeons/inBoss`, `DungeonScan.tiles/rooms/startX/startY/roomGap`, `RequestUtils.pullSecrets(name): Result<Long>` (suspend), `OdinMod.scope`, `schedule(ticks) {}` (`com.odtheking.odin.utils.handlers`), `webSocket {}`/`WebSocketConnection` (`com.odtheking.odin.utils.network`), `ClickGUIModule.webSocketUrl`, `LocationUtils.lobbyId`, `WebUtils.gson`.
- Produces (used by Tasks 7-9): inside `ClearBreakdown`: `val clears: ClearCredit`, `val secrets: SecretCredit`, `var active: Boolean` (a run is being tracked), `val roster: LinkedHashMap<String, Char>` (name -> class colour), `fun roomKey(room: DungeonRoom): String`, `fun roomAt(x: Double, z: Double): DungeonRoom?`, and `val relayed: HashMap<Pair<String, String>, String>` ((sender, player) -> room key, or `""` for no room; no entry = that sender doesn't see them) read by presence.

- [ ] **Step 1: `OdinSync`** — `object OdinSync { var onPayload: (player: String, tiles: List<Long>, found: Int) -> Unit; fun connect(); fun shutdown() }`: its own `webSocket { onMessage { … } }` to `"${ClickGUIModule.webSocketUrl}${LocationUtils.lobbyId}"` (skip if `lobbyId` null), parsed with `WebUtils.gson` into a private `Msg(playerName: String?, roomName: String?, tiles: List<Tile>?, foundSecrets: Int?)` / `Tile(data: Long)` (gson writes Odin's value class `IVec2` as `{"data": long}`); drop anything with a null field or a parse error. Never sends.
- [ ] **Step 2: The module.** Decisions:
  - **Room key:** `room.tiles.map { it.data }.sorted().joinToString(",")`; display name `room.name ?: room.type.name`. `roomAt`: tile `((x.toInt()+201) shr 5, (z.toInt()+201) shr 5)`, in `0..5`, `DungeonScan.tiles[tx + tz*6].room`.
  - **Chat** off `ClientboundSystemChatPacket` (`priority = 1000, ignoreCancelled = true`, skip `overlay`, strip `§.`, `mc.execute`), as `DungeonSplits` ~245-265; ignored outside `inDungeons` or in P3 Sim practice.
  - **Start** on `SplitsModel.MORT`: reset models, `active = true`, roster from `dungeonTeammates` (self included) with `clazz.colorCode`, then baseline `RequestUtils.pullSecrets` per name in `OdinMod.scope`. Names appearing in `dungeonTeammates` later join the roster and get a baseline then.
  - **Finished:** the `☠ Defeated ` line (regex `^\s*☠ Defeated `, as `UploadPacker.RUN_END`) sets `won = true`. On Odin's end line `^\s*(?:Master Mode )?(?:The )?Catacombs - (?:Entrance|Floor .{1,3})$`: if `active && won`, `schedule(30)` then pull each roster name again; delta `(after - before).coerceAtLeast(0).toInt()`, `null` if either failed; then on the client thread post a `§6Clear Breakdown` header and one `EngineerClient.msg(Component)` per roster name (roster order), built from `BreakdownText.line(name, colour, clears.clears()[name].orEmpty(), secrets.resolve(totals)[name])` with `HoverEvent.ShowText` on the rooms part and the secrets part (idiom: `src/waypoints/BrRoles.kt` ~173-181). Post once, then `active = false`. Without `won`, nothing posts.
  - **Reset** everything (models, roster, baselines, `won`, `active`, `relayed`) on `LevelEvent.Load`.
  - **Presence, every `TickEvent.End`** while `active && inDungeons && !inBoss`: for each roster name: null if not in `dungeonTeammates` or `isDead`; else, in order: own entity (`mc.player` for self, `entity` for others) → `roomAt`; else a relayed sighting from any sender (`relayed` value for that player, `""` → null); else the map icon (`src/adr/RunRecorder.kt` ~581-582, skipped when `mapPos.data == 0L`). Feed the same map to `clears.tick` and `secrets.tick`.
  - **Odin events:** `CheckmarkUpdateEvent` for types other than `FAIRY`/`ENTRANCE` → `clears.onCheckmark` (`WHITE`/`GREEN`/`RED`, else `OTHER`); on `GREEN` with `room.data?.maxSecrets` also `secrets.observe(key, name, max)`. `SecretsUpdateEvent` → `secrets.observe`. `OdinSync.onPayload` for roster teammates (not self) → `mc.execute { secrets.observe(...) }` for the room whose key matches the payload tiles. `OdinSync.connect()` on `FloorEnterEvent`, `shutdown()` on `LevelEvent.Load` and on the first tick in boss.
  - KDoc on the object in the codebase's voice: what it shows, the source order, why it keeps its own Odin socket.
- [ ] **Step 3:** Add `com.engineerclient.clearbreakdown.ClearBreakdown` to `MODULES` after `DungeonSplits` and to the first-run enable list.
- [ ] **Step 4:** `& $GRADLE build --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 5:** Commit: "Clear Breakdown: the module - rooms from where everyone is, Odin's secret counts, the API totals, posted when a run finishes".

---

### Task 7: Direct secret sightings

**Files:** Create `src/clearbreakdown/Sightings.kt`; modify `src/clearbreakdown/ClearBreakdown.kt` (subscribe).

**Interfaces:**
- Consumes: `ClearBreakdown.secrets`, `roomAt`, `roomKey`, `roster`, `active`.
- Produces: `object Sightings { val outbox: MutableList<Relayed> ; fun reset() }` — every sighting this client makes is also appended to `outbox` (Task 8 sends it). A sighting is fed as `secrets.sighting(key, roomKey, roomName, candidates, exact, System.currentTimeMillis())`, only while `active && inClear`.

- [ ] **Step 1: Implement**, each handler hopping to the client thread:
  - **Candidates** = roster names, not dead, whose entity (`mc.player` for self) is within 6 blocks (feet position) of the point. Room = `roomAt` of the secret's position.
  - **Item:** `ClientboundTakeItemEntityPacket`: the item entity (`level.getEntity(itemId) as? ItemEntity`) whose `item.hoverName.string` contains (ignore case) any of Odin's `dungeonItemDrops` (copy the list from Odin's `events/EventDispatcher.kt` ~95-98; it's private), collector `level.getEntity(playerId) as? Player` in the roster → exact `{collector}`, key `item:<itemId>`. Collector unknown → candidates within 6 blocks of the item, not exact.
  - **Chest:** `ClientboundBlockEventPacket` with `b0 == 1`, `b1 > 0`, block `CHEST`/`TRAPPED_CHEST` (as ADR.kt ~197-201), key `chest:<x>,<y>,<z>`. If this client right-clicked that block (`BlockInteractEvent`, `pos`) within the last 1000 ms → exact `{self}`; else proximity.
  - **Essence:** every 20 ticks remember positions of skull block entities whose owner profile id is `2865274b-3097-394e-8149-ec629c72d850` (Odin's `WITHER_ESSENCE_ID`) in loaded chunks around the player (mirror ADR's skull scan, `RunRecorder.kt` ~790-806); a `BlockUpdateEvent` turning a remembered position into a non-skull → key `essence:<x>,<y>,<z>`; exact `{self}` if this client clicked it within 1000 ms, else proximity.
  - **Bat:** `ClientboundSoundPacket` `BAT_HURT`/`BAT_DEATH` at volume `0.1f`, key `bat:<x/2 rounded>,<y/2>,<z/2>:<epoch second>`, proximity.
- [ ] **Step 2:** `& $GRADLE build --console=plain` → `BUILD SUCCESSFUL` (all tests still pass).
- [ ] **Step 3:** Commit: "Clear Breakdown: secrets seen directly - item pickups by name, chests, essence and bats by who was in reach".

---

### Task 8: Relay through ADR Online (mod side)

**Files:** Modify `src/adr/OnlineLink.kt`; modify `src/clearbreakdown/ClearBreakdown.kt`.

**Interfaces:**
- Consumes: `RelayCodec` (Task 5), `Sightings.outbox` (Task 7), `ClearBreakdown.relayed`.
- Produces: in `OnlineLink`: `fun sendRelay(frames: List<String>)` (no-op without a socket) and `@Volatile var onRelay: ((String) -> Unit)?`.

- [ ] **Step 1: OnlineLink.** `sendRelay` queues each frame through the existing `sendText` chain. `Listener.onText` keeps a `StringBuilder`, appends `data`, and on `last` hands the whole text to `onRelay` (inside `EngineerClient.safely`) when it starts with `{"t":"cb"`; everything else is still ignored. Existing frames and lifecycle unchanged.
- [ ] **Step 2: Sending** (in the module): every 20 ticks while `active && inClear`, gather `Sightings.outbox` plus presence changes for roster players this client sees **by entity** (room key, `""` for no room) and players it stopped seeing since the last send (`roomKey = null`); if non-empty, `OnlineLink.sendRelay(RelayCodec.frames(items))` and clear them. Never relay map-icon positions or Odin counts.
- [ ] **Step 3: Receiving:** `OnlineLink.onRelay = { text -> RelayCodec.decode(text)?.let { (from, items) -> mc.execute { … } } }`, applied only while `active` and only if `from` is in the roster and isn't self: `Secret` → `secrets.sighting(...)` with candidates filtered to the roster; `Presence` for roster players → `relayed[from to player] = roomKey` (remove the entry when `roomKey == null`).
- [ ] **Step 4:** `& $GRADLE build --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 5:** Commit: "Clear Breakdown: party members' sightings, live through ADR Online".

---

### Task 9: Silence Odin's Secrets Counter

**Files:** Create `src/mixin/odin/SecretsCounterMixin.java`; modify `src/resources/engineerclient.odin.mixins.json`.

- [ ] **Step 1:** `@Pseudo @Mixin(targets = "com.odtheking.odin.features.impl.dungeon.SecretsCounter", remap = false)`, `@Inject(method = "fetchAndDisplay()V", at = @At("HEAD"), cancellable = true, remap = false)` handler `ec$clearBreakdown(CallbackInfo ci)` cancelling when `ClearBreakdown.INSTANCE.getEnabled()`. Javadoc in the style of `OdinTermTimesMixin`. Add `"SecretsCounterMixin"` to the `client` list.
- [ ] **Step 2:** `& $GRADLE build --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 3:** Commit: "Clear Breakdown replaces Odin's Secrets Counter lines while it is on".

---

### Task 10: Relay on the site (adr repo)

**Files:** Modify `C:\Users\graha\adr\src\online.js`; create `C:\Users\graha\adr\test\online-relay.test.mjs`.

**Interfaces:**
- Produces: `export function relayTargets(from, others)` in `online.js`: `from` and each of `others` are socket attachments (`{name, party:{members:[{n}]}}`); returns the indexes into `others` that should receive: the other's name is in `from`'s party members **and** `from`'s name is in the other's party members (case-insensitive), not the same name.

- [ ] **Step 1: Failing test** (`node:test`, `node:assert/strict`, `import { relayTargets } from '../src/online.js'`):

```js
const p = (name, ...m) => ({ name, party: { members: [name, ...m].map(n => ({ n })) } });
test('mutual party members only', () => {
  assert.deepEqual(relayTargets(p('Andrew', 'Bob', 'Cat'), [p('Bob', 'andrew'), p('Cat'), p('Dan', 'Andrew'), p('Andrew', 'Bob')]), [0]);
});
test('no party report sends nothing', () => {
  assert.deepEqual(relayTargets({ name: 'A' }, [p('B', 'A')]), []);
});
```

- [ ] **Step 2:** `node --test test/` (in `C:\Users\graha\adr`) → FAIL (no export).
- [ ] **Step 3: Implement** `relayTargets`, and in `OnlineHub.gameMessage` a branch before the final `else return true`: `msg.t === 'cb'` with `Array.isArray(msg.d)` and the sender named → for each target in `this.games()` (excluding `ws`) picked by `relayTargets(a, attachments)`, `this.send(g, { t: 'cb', from: a.name, d: msg.d })`; then `ws.serializeAttachment(a)` (so the rate count sticks) and `return true` without `changed()`. Update the header comment (games' message list, and "Nothing goes back to a game" → only relayed `cb` frames).
- [ ] **Step 4:** `node --test test/` → PASS (2 tests).
- [ ] **Step 5:** Commit and push (deploys): `git add src/online.js test/online-relay.test.mjs; git commit -m "ADR Online: relay Clear Breakdown frames between party members who name each other"; git push origin main`. Confirm the deploy workflow run succeeds (`gh run list -R undonecoffee/adr -L 1`).

---

### Task 11: In-game check

- [ ] **Step 1:** Hand the user the built jar (`build/libs/`) and this checklist:
  1. Finish a run with Odin's Secrets Counter on: one `Clear Breakdown` block, a line per party member, no Odin `-> N Secrets` lines.
  2. Room hover: rooms in clear order with `(solo)`. Secrets hover: per-room counts, `~` estimates, `unknown rooms`.
  3. With a teammate also running the mod and ADR on, their item pickups out of your sight appear in their line's rooms.
  4. Wipe or leave a run: nothing posts; the next run starts clean.
  5. Turn Clear Breakdown off: Odin's Secrets Counter lines come back next run.
- [ ] **Step 2:** Fix what they report through the normal workflow.
