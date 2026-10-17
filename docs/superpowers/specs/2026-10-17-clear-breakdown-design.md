# Clear Breakdown — design

A new Engineer Client module. At the end of a dungeon run it posts one chat line per party member
(you included) saying how many rooms they cleared, how many of those they cleared alone, and how
many secrets they found, with hover text giving the detail. It replaces the chat lines from Odin's
Secrets Counter, since it carries the same secret totals.

## Why

After a run you want to know who actually did what: who cleared rooms alone, who only tagged
along, who carried the secrets and where. Nothing in Odin or engineerClient records this today:
Odin knows which room *you* are in and each room's checkmark, but never who was in a room when it
was cleared, and its per-player secret numbers (Secrets Counter) are run totals only.

## What the player sees

When the run ends, a block in chat (local only; hover text can't go through party chat):

```
[EC] Clear Breakdown
 Andrew: 3-5 12 secrets
 Bob: 1-4 9 secrets
 ...
```

- `3-5`: **3** = solo clears, **5** = total clears (see "Room credit").
- `12 secrets`: the player's secrets for the run, from the Hypixel API (see "Secrets").
- **Hover on `3-5`**: the rooms that player helped clear, in the order the rooms got their
  checkmark, marking which were solo.
- **Hover on `12 secrets`**: per-room secret counts, only rooms where they got at least one, e.g.
  `4 Prison Cell`, `~2 Mirror` (estimate), `3 in unknown rooms`.
- One line for every party member, including the local player and anyone who died or left
  mid-run (their numbers so far).

The exact colours, header text and ordering of lines are left to the builder (match the style of
the existing `[EC]` messages; class colours on names would be a nice touch).

## Sources, best first (user's rule)

Where the same fact can come from ADR-style observation or from Odin, use the ADR-style one; Odin
fills gaps and uncertainties, i.e. whatever happens out of render distance of every recording
party member.

1. **What this client sees directly** (the same packets and hooks ADR's `RunRecorder` records:
   player entities' positions, item pickups, chest lids, block changes, bat sounds, your own
   clicks). Clear Breakdown subscribes to these itself; ADR's recording is a file written on
   another thread and can't be read live.
2. **What party members' clients see directly**, relayed live through ADR Online (see "Party
   relay"). Only party members running engineerClient with ADR on contribute.
3. **Odin**: map icons for positions of players nobody sees, room `found/total` counts from the
   action bar, Odin's map-sync websocket ("Odin's webhook") and green checkmarks.
4. **Hypixel API** run totals per player, which every per-room figure is reconciled against.

## Room credit

Decided by the user:

- **Rooms that count**: every room type except Fairy and the green Entrance room. So normal,
  mini-boss (rare/champion), puzzle, trap and Blood all count.
- **Helping a room**: a player helps clear a room if they spend **more than 2 seconds** inside it
  while it is still uncleared (before its checkmark), even if they leave before it is finished.
- **Failed puzzles** (red X on the map) do not count. A failed puzzle can be reset with an item;
  if it is then completed successfully (white/green checkmark) it counts as a clear like any other.
- **Total clears** = rooms the player helped clear. **Solo clears** = rooms where they were the
  *only* helper. Example: Bob fights for 10 s and leaves, Andrew finishes alone: both get +1 total,
  neither gets a solo.

Where a player is, each tick: their entity position if this client or a relaying party member sees
them (source 1 or 2), else their Odin map icon (source 3). Odin occasionally swaps two teammates'
map icons, so the map fallback can now and then credit the wrong person; accepted.

My calls (the user hasn't ruled on these; adjust if they say otherwise):

- The 2 seconds are cumulative across visits.
- Whoever is in the room when the checkmark lands always counts as a helper, even under 2 s, so a
  room that pops the instant someone walks in still gets credited.
- "Cleared" is the first white or green checkmark the room gets (a room that goes white then green
  is credited once, at white; red never credits).
- Odin never reports a failed puzzle going back to normal after a reset (the room stays red in its
  state until it turns white/green), so helper time for a room restarts at its red X: only time
  after the fail counts toward the successful attempt.
- A room's place in the hover order is when it got its checkmark.

## Secrets

Hypixel counts four kinds of secret: chests, items, bats and wither essence (my understanding,
confirmed by the user). Levers and redstone keys aren't secrets on their own.

### Seen directly (sources 1 and 2)

Each sighting becomes a **secret event** with a room, a de-duplication key and a set of candidate
players:

| Kind | Seen as | Candidates | Key |
|---|---|---|---|
| Item | `ClientboundTakeItemEntityPacket` for an item whose name is a dungeon secret drop (Odin's `dungeonItemDrops` list in `EventDispatcher`) | exactly the collector the packet names | item entity id |
| Chest | chest/trapped-chest lid opening (`ClientboundBlockEventPacket`, as ADR's `bev`) | players within 6 blocks of the chest | block position |
| Essence | a wither-essence skull block disappearing (`BlockUpdateEvent`) | players within 6 blocks | block position |
| Bat | bat hurt/death sound at volume 0.1 (as ADR's `batsound` and Odin's `SecretPickupEvent.Bat`) | players within 6 blocks of the sound | rounded position + second |
| Yours | your own right-click on a chest/essence (Odin's `SecretPickupEvent.Interact`) | you | block position |

"Within 6 blocks" was confirmed by the user as the meaning of "in reach". Entity ids and block
positions are the server's, so two clients seeing the same secret produce the same key. My calls on
merging sightings of one key:

- **The first sighting of a key is the secret.** A chest opened again later (re-opened, already
  looted) is not a new secret and changes nothing.
- **Exact beats near.** An exact sighting (the pickup packet's collector, or your own click on a
  chest/essence) replaces the proximity candidates for that key; two proximity sightings of the
  same moment merge by union, and elimination narrows it.
- **Your own click only counts if the secret actually happens**: a chest click is exact only when
  that chest's lid opens within 1 s of the click and the key hasn't been seen before, so clicking a
  chest someone else already looted credits nothing.

### Gaps filled from Odin (source 3)

A room's `found/total` count is watched as before: your action bar (`SecretsUpdateEvent`), Odin's
map-sync payloads from teammates, and a green checkmark (= max). When a room's count rises by more
than the direct secret events already credited in that room since its previous count, the leftover
is a **gap batch**. Its candidates are everyone who was in that room at any point since the room's
previous observed count (not just whoever is there when the jump is seen: you often only see a
room's count when you walk in, long after the secrets were found). A gap batch with no candidates
credits nobody.

### Reconciling against the API (source 4)

**Run totals** per player come from the Hypixel API: lifetime secrets before the run minus after,
fetched as Odin's Secrets Counter does (`RequestUtils.pullSecrets(name)`), but including the local
player. These totals are treated as exact.

Direct events and gap batches are then settled the same way:

1. Exactly one candidate: theirs, certain.
2. Several candidates: **process of elimination** against the API totals (user's rule). Each
   player's "left to place" = API total minus their certain secrets. A secret whose candidates
   include only one with anything left to place goes to that player; repeat until nothing changes,
   since each placement can settle others.
3. Still tied after that (my call): split among the candidates in proportion to what each has left
   to place, rounded to whole secrets, shown with `~` as an estimate.
4. Anything in a player's API total not placed in a room shows as `N in unknown rooms`. Never
   invent rooms.

Further calls of mine:

- If the API fails for someone: their line still posts with rooms and the secrets seen, marked as
  partial, without the total; for elimination they count as unlimited.
- If a player's certain secrets exceed their API total (API lag), the API total wins for the
  headline number and the per-room list is shown as seen.
- Dead players (ghosts) are never present anywhere, for clears or secrets.
- The roster is taken at run start, so players who leave or disconnect still get a line.

## Runs that don't finish

Decided by the user: the breakdown posts only for a run that reaches its end line
(`Catacombs - Floor …`). If the run ends any other way (wipe, leaving, a disconnect or kick, even
with a rejoin) nothing posts, and everything tracked for every player is reset for the next run.
Nothing is carried across a world load.

## Party relay (ADR Online)

Decided by the user: party members' sightings come in **live** through ADR Online, so the breakdown
still posts in chat when the run ends.

**What exists.** Every client with ADR on keeps a WebSocket to `wss://undonecoffee.com/adr/api/online/game`
(`src/adr/OnlineLink.kt`), sending `hello`, `party`, `where`, `stats`. In a dungeon its party is
exactly the dungeon team. The site (`C:\Users\graha\adr`, a Cloudflare Worker; the one `PfMenu`
Durable Object hosts `OnlineHub` in `src/online.js`) keeps each socket's name and party report and
today sends nothing back to games. It closes a socket for frames over 4096 chars or more than 30
messages per 10 s, and ignores unknown `t` types (so a new client works against the old site, just
with nothing relayed).

**What changes.**

- **Client sends** a new frame type, batched: at most one frame per second while in a dungeon clear
  with something to say, each frame under 4096 chars (split if needed). It carries:
  - this client's direct secret events (kind, key, room key, candidate names);
  - this client's sightings of where players are: when a player this client sees *by entity*
    changes room (enter/leave, room key). Room changes only, not positions per tick.
  Map-icon positions and Odin room counts are not relayed; every client has those already.
- **Site relays** each such frame to the sockets of the sender's party, only where the party report
  is **mutual** (the recipient's own party report names the sender and the sender's names the
  recipient), since names and tokens on the site are self-asserted. It adds the sender's name. No
  storage; nothing is kept after forwarding.
- **Client receives**: `OnlineLink`'s listener stops discarding text (reassembling fragmented frames)
  and hands relay frames to Clear Breakdown, which accepts events only about roster members and
  merges them with its own (de-duplicated by key). A relayed room change is applied when it
  arrives (relay latency is well under the 2 s help threshold).
- Without ADR on, or if the site is unreachable, Clear Breakdown still works from its own client
  and Odin.

The site deploys on every push to `main` (`.github/workflows/deploy.yml`).

## Replacing Odin's Secrets Counter

While Clear Breakdown is enabled, Odin's Secrets Counter chat lines are held back; turning Clear
Breakdown off brings them back. My call: cancel its end-of-run fetch and display
(`SecretsCounter.fetchAndDisplay`, a private method; a mixin like `OdinTermTimesMixin`) rather than
switching Odin's module off, so the user's Odin config is untouched. This also stops its end-of-run
API calls.

## Where the data comes from (recon)

Odin source for 26.3 is the `26.3` branch of odtheking/OdinFabric. Compiled classes are in
`libs/odin`.

- **Checkmarks**: `CheckmarkUpdateEvent(room: DungeonRoom, checkmark: MapCheckmark)` from `MapScan`
  (fires on change; WHITE/GREEN/RED). Rooms: `DungeonScan.rooms`; tiles:
  `DungeonScan.tiles[x + z*6].room`. Room type: `DungeonRoom.type`.
- **Room key**: a room's tiles (`DungeonRoom.tiles`, `IVec2` packed longs), identical on every
  client and in Odin's sync payloads.
- **Teammates**: `DungeonUtils.dungeonTeammates` (`DungeonPlayer`: `name`, `clazz`, `entity`,
  `isDead`, `mapPos`). World tile from a position: `(x+201) shr 5`, `(z+201) shr 5`. Map icon to
  world: as in `src/adr/RunRecorder.kt` ~581.
- **Direct sightings**: ADR's hooks in `src/adr/ADR.kt` (~164-201: bat sounds, take-item, block
  events) and `RunRecorder.kt` (players, `pickup`, `bev`); Odin's `EventDispatcher` for the secret
  item list, the bat volume and `SecretPickupEvent.Interact`.
- **Odin counts**: `SecretsUpdateEvent(room, foundSecrets)`; Odin's map sync is `DungeonMap.syncSocket`
  (payload `SyncPayload(playerName, roomName, tiles, foundSecrets)`; gson writes each `IVec2` as
  `{"data": long}`). engineerClient must open **its own** connection to the same URL
  (`ClickGUIModule.webSocketUrl` + `LocationUtils.lobbyId`): `WebSocketConnection.onMessage`
  replaces Odin's single handler and would break Odin's map.
- **API**: `RequestUtils.pullSecrets`; Odin's `SecretsCounter.kt` for its start/end lines and the
  30-tick wait.
- **Run start/end**: chat lines as in `src/splits/SplitsModel.kt` (Mort start) and Odin's
  `Catacombs - Floor` end line, taken straight off the packet as `DungeonSplits` does.

## Constraints

- Fits the codebase idioms: a `Module` in the Engineer Client category, added to `MODULES` in
  `src/EngineerClient.kt`; handlers wrapped in `EngineerClient.safely`; messages through
  `EngineerClient.msg`; reset on `LevelEvent.Load`; pure logic (credit + elimination) in headless
  classes with no Minecraft imports so it can be tested, fed by the module.
- Must not break Odin's map sync, Odin's Secrets Counter when Clear Breakdown is off, ADR's
  recording, or ADR Online's existing messages and limits.
- Ignore P3 Sim / practice runs (as `DungeonSplits` does with `P3Sim.inSim && Practice.active`).
- Site changes go to the `adr` repo and are pushed to `main` straight away (user's standing rule
  for that repo).

## Non-goals

- Party chat output, Discord webhooks, saving breakdowns to disk or the ADR site.
- Reading ADR recording files, during or after the run.
- Boss-phase stats (terminals, deaths, damage). This is the clear only.
- Which individual chest or item someone got in the hover; counts per room only.

## Left to the builder

Module file layout and names, the exact relay frame shape, how candidate distances are measured
(feet vs eyes), message colours and line order, setting(s) beyond the on/off toggle, and how the
Odin sync JSON is parsed.
