# Secret spawn ranges

How near a player has to come before each item, bat and skull secret spawns, measured per secret from ADR recordings. The data
is `src/resources/assets/engineerclient/ads/secret-spawns.json` (ADS reads it, [SpawnRanges](../../src/p3sim/clear/secrets/SpawnRanges.kt));
the table at the end is the same data. `tools/secret-spawns/` rebuilds both.

## The rule

- Hypixel checks every **5 ticks**. A secret spawns at the first check that finds a party member **inside its room** and within
  **its own radius**, measured in 3D from the player's feet (to the item's cell at its floor, the bat, the skull block's centre).
- Every secret has its own radius: items 3.8 to 21 blocks (median 10.5), bats 3.2 to 21.1 (10.2), essence skulls 3.6 to 30.6 (10.4).
  The same secret has the same radius on F7 and M7 (a median 0.4 blocks apart).
- A few skulls appear on an event instead of on approach (radius empty): some in the three key rooms (Redstone Key, Redstone Crypt,
  Golden Oasis), Bridges' two and Grand Library's.

## How it was measured

From the 6,555 public ADR recordings of 2026-10-15 to 22 (F7, M7, M5, F5, M4), 74,197 first sights of a secret:

- **Only real spawns.** An item is new when the recorder first sees it still falling (a fractional height); one seen lying still was
  already there and only came into the recorder's view (the client is told about items in a 32-block square: the 32 the old setting
  had). A bat is new when it is at its block's centre with the recorder in view of it. A skull is new when a block update places it.
- **Every 5 ticks.** When the recorder teleports into range, the secret shows 1 to 5 ticks later about equally often, and far less
  often 6 or later (those are teleports that landed outside the radius and walked in). Spawns do not keep to one 20-tick clock.
- **The room.** A party waiting behind a closed door near a secret does not bring it in; it spawns once they walk into the room.
  Counting only players inside the room explains more spawns than counting everyone.
- **Its radius.** Each spawn says the radius is at least the nearest in-room player's distance at first sight, and less than the
  nearest in-room player's distance 12 ticks before (the check before it, plus the ping). The radius is where the fewest spawns
  disagree: a median 93% agree with their secret's radius (10% of secrets under 84%). `err` is how far it moves when 20 ticks are
  allowed instead of 12.
- **A sphere, not sight.** Fitted from each side separately (players coming from the north, south, above...), a secret's radius
  differs by a median of 1 block between sides (1.5 for skulls), so it is a distance, not a line of sight.

Limits: radii are good to about a block (players move 0.3 to 1 block a tick between checks); secrets in rooms that came up rarely
are missing; teammates' positions are a few ticks behind on the recorder's screen.

## The file

One line per site. `design` and `secret` are the rooms pack's design id and secret id; `pos` (and `packPos`, where the pack's own spot
differs) are in the design's canonical frame (FORMAT-rooms 4). `r` is the radius (null: it appears on an event), `err` its uncertainty,
`n` the spawns it rests on, `agree` the share of them it explains, `f7`/`m7` the radius from that floor alone. `defaults` are the
medians, for secrets without a radius.

- Bats: the pack's spot is often where the bat was killed, up to 12 blocks off. ADS spawns a bat at its measured spot.
- 26 sites have `secret: null`: items, bats and key skulls the rooms pack does not have (8 items with 8 to 16 spawns each, 8 key
  skull spots in the three key rooms, 10 bats with 25 or more spawns each, which may be a second spot of the room's bat). ADS cannot place them until the rooms pack does.

## Rebuilding

See `tools/secret-spawns/README.md`. Runs from the last week download freely; older ones are limited to 30 a day, so a rebuild uses
the last week's.

## The table

`*`: not in the rooms pack.

<!-- TABLE -->
| Room | Secret | Type | Spawn radius | Spawns at (room frame) | Seen |
|---|---|---|---|---|---|
| Altar | s5 | bat | 4.1 ± 0.9 | 36, 55, 35 | 61 |
| Altar | s6 | item | 10.7 ± 0.5 | 14, 71, 48 | 228 |
| Altar | s4 | essence skull | 3.6 ± 0.5 | 17, 93, 27 | 155 |
| Andesite | s1 | item | 12.8 ± 0.5 | 1, 69, 28 | 224 |
| Andesite | s2 | essence skull | 7 ± 0.5 | 6, 69, 22 | 425 |
| Archway | s2 | item | 20.6 ± 1.6 | 10, 83, 27 (pack: 10, 85, 27) | 210 |
| Archway | s3 | item | 15.8 ± 0.9 | 54, 89, 12 | 71 |
| Atlas | s2 | bat | 12.4 ± 0.5 | 6, 72, 57 (pack: 5, 72, 57) | 307 |
| Atlas | - | bat * | 2.8 ± 2.7 | 12, 69, 54 | 25 |
| Atlas | s5 | item | 15 ± 0.5 | 55, 76, 27 (pack: 55, 76, 28) | 193 |
| Atlas | - | item * | 3.3 ± 0.5 | 31, 79, 52 | 9 |
| Atlas | s4 | essence skull | 4.3 ± 0.5 | 38, 78, 4 | 392 |
| Balcony | s2 | bat | 7.8 ± 0.5 | 3, 60, 25 (pack: 4, 60, 25) | 142 |
| Balcony | s3 | item | 4.5 ± 2.3 | 13, 51, 8 (pack: 14, 51, 7) | 65 |
| Basement | s1 | essence skull | 10.6 ± 0.5 | 5, 66, 25 | 465 |
| Beams | s2 | item | 20.6 ± 0.5 | 8, 92, 25 (pack: 8, 92, 24) | 117 |
| Big Red Flag | s1 | item | 8.4 ± 0.5 | 21, 75, 23 (pack: 20, 75, 23) | 204 |
| Big Red Flag | s2 | item | 5.3 ± 1 | 26, 75, 25 | 129 |
| Black Flag | s3 | item | 5.3 ± 0.5 | 22, 60, 27 (pack: 22, 60, 28) | 14 |
| Blue Skulls | s1 | item | 10.2 ± 0.5 | 28, 77, 29 (pack: 27, 77, 29) | 127 |
| Bridges | s3 | bat | 14.9 ± 1.6 | 48, 97, 2 (pack: 48, 93, 4) | 144 |
| Bridges | - | bat * | 8 ± 0.5 | 47, 93, 6 | 30 |
| Bridges | s4 | item | 3.8 ± 0.5 | 55, 81, 17 | 96 |
| Bridges | s1 | essence skull | on an event | 53, 53, 13 | 449 |
| Bridges | s2 | essence skull | on an event | 53, 52, 17 | 449 |
| Cages | s1 | item | 10.6 ± 0.5 | 28, 98, 5 | 43 |
| Cathedral | s6 | bat | 10.2 ± 0.9 | 42, 78, 13 (pack: 37, 74, 15) | 260 |
| Cathedral | s7 | bat | 20.2 ± 0.5 | 59, 86, 2 (pack: 59, 86, 3) | 280 |
| Cathedral | s2 | item | 10.3 ± 0.7 | 33, 93, 48 (pack: 33, 93, 47) | 95 |
| Cathedral | s3 | item | 10.1 ± 0.5 | 21, 90, 44 (pack: 19, 90, 44) | 96 |
| Cathedral | s8 | item | 13.4 ± 1 | 48, 59, 23 (pack: 49, 59, 23) | 216 |
| Catwalk | s4 | item | 20.3 ± 0.7 | 80, 50, 6 | 198 |
| Catwalk | s3 | essence skull | 20.6 ± 0.5 | 80, 50, 8 | 702 |
| Cell | s1 | item | 6.5 ± 0.6 | 10, 69, 22 | 108 |
| Chambers | s3 | bat | 5.7 ± 2 | 33, 83, 57 (pack: 33, 84, 57) | 181 |
| Crypt | s4 | item | 12.2 ± 0.5 | 46, 88, 16 (pack: 46, 88, 15) | 97 |
| Deathmite | s5 | bat | 9.7 ± 0.5 | 84, 96, 25 (pack: 83, 98, 26) | 429 |
| Deathmite | s1 | item | 7.6 ± 0.5 | 88, 69, 6 | 331 |
| Diagonal | s2 | bat | 8.1 ± 0.9 | 32, 72, 5 | 319 |
| Dino Site | s4 | bat | 11 ± 0.6 | 9, 48, 34 | 184 |
| Dino Site | s3 | item | 8.4 ± 0.5 | 47, 57, 36 | 182 |
| Dome | s1 | bat | 16.2 ± 1.2 | 25, 73, 25 (pack: 26, 75, 23) | 264 |
| Doors | s2 | bat | 11.7 ± 4.5 | 48, 64, 16 (pack: 46, 65, 15) | 240 |
| Doors | - | item * | 5.2 ± 0.5 | 8, 59, 17 | 14 |
| Double Diamond | s2 | item | 15.6 ± 0.5 | 25, 65, 23 (pack: 24, 65, 22) | 248 |
| Double Diamond | s1 | essence skull | 8.8 ± 1.5 | 6, 69, 6 | 430 |
| Dueces | s3 | bat | 10.1 ± 0.5 | 8, 74, 7 | 137 |
| Dueces | s2 | item | 9.2 ± 0.5 | 10, 70, 6 | 113 |
| Duncan | s1 | item | 13.7 ± 0.5 | 12, 89, 8 (pack: 12, 89, 7) | 103 |
| End | s1 | essence skull | 21.4 ± 0.5 | 6, 63, 2 | 275 |
| Flags | s4 | bat | 15.1 ± 0.5 | 51, 74, 38 (pack: 52, 77, 38) | 315 |
| Flags | - | bat * | 1.5 ± 2 | 52, 77, 33 | 66 |
| Flags | s3 | item | 15.6 ± 1.1 | 54, 69, 23 (pack: 53, 69, 23) | 254 |
| Gold | s1 | essence skull | 19.8 ± 2.4 | 14, 69, 13 | 439 |
| Golden Oasis | S1 | key skull | on an event | 12, 71, 8 | 157 |
| Golden Oasis | - | key skull * | 4.3 ± 0.5 | 11, 71, 3 | 34 |
| Golden Oasis | - | key skull * | 3.6 ± 0.5 | 10, 71, 4 | 32 |
| Golden Oasis | - | key skull * | 3.9 ± 0.5 | 10, 72, 3 | 8 |
| Golden Oasis | - | key skull * | 5.5 ± 1.5 | 9, 71, 3 | 8 |
| Grand Library | s4 | item | 10.3 ± 0.5 | 28, 57, 18 | 152 |
| Grand Library | s1 | essence skull | on an event | 6, 80, 3 | 416 |
| Granite | s2 | item | 6.8 ± 0.5 | 14, 61, 24 (pack: 14, 61, 25) | 61 |
| Granite | s1 | essence skull | 8.2 ± 0.6 | 13, 61, 29 | 180 |
| Grass Ruin | s2 | essence skull | 8.4 ± 0.5 | 48, 81, 24 | 319 |
| Gravel | s1 | bat | 10.1 ± 2 | 34, 92, 13 (pack: 37, 91, 14) | 286 |
| Gravel | - | bat * | 3.9 ± 0.5 | 31, 87, 12 | 41 |
| Gravel | s3 | item | 20.3 ± 2.5 | 87, 69, 8 | 308 |
| Gravel | s5 | item | 5.9 ± 0.8 | 67, 87, 12 | 212 |
| Gravel | s2 | essence skull | 21 ± 2.4 | 92, 69, 3 | 778 |
| Gravel | s4 | essence skull | 6.5 ± 0.5 | 66, 87, 14 | 632 |
| Jumping Skulls | - | item * | 3.6 ± 0.7 | 26, 63, 23 | 10 |
| Knight | s1 | bat | 13.5 ± 0.5 | 6, 75, 21 (pack: 6, 75, 24) | 110 |
| Lava Ravine | s4 | bat | 10.2 ± 0.5 | 13, 64, 35 (pack: 12, 64, 33) | 328 |
| Lava Ravine | s2 | item | 13.7 ± 0.5 | 15, 51, 32 (pack: 16, 51, 32) | 113 |
| Layers | s1 | item | 7.6 ± 0.6 | 25, 70, 28 | 166 |
| Layers | s3 | item | 7.3 ± 2.3 | 7, 96, 56 | 59 |
| Layers | s4 | item | 5.9 ± 0.6 | 35, 59, 51 | 87 |
| Layers | s5 | item | 9.6 ± 1.2 | 47, 79, 34 | 156 |
| Mage | s1 | bat | 10.4 ± 0.5 | 56, 84, 8 (pack: 57, 83, 7) | 176 |
| Mage | s2 | essence skull | 10.7 ± 0.7 | 58, 82, 5 | 312 |
| Market | s4 | bat | 11.5 ± 1.7 | 8, 63, 47 (pack: 12, 62, 47) | 324 |
| Market | s5 | essence skull | 10.1 ± 0.5 | 7, 59, 44 | 446 |
| Melon | s5 | bat | 5.7 ± 0.5 | 7, 76, 27 | 188 |
| Melon | - | item * | 4.8 ± 0.5 | 26, 80, 1 | 13 |
| Mines | s1 | item | 6.5 ± 1 | 1, 89, 57 | 116 |
| Mines | s5 | item | 10.7 ± 0.5 | 53, 48, 8 | 31 |
| Mines | s7 | item | 9.2 ± 0.5 | 4, 54, 56 | 41 |
| Mossy | s2 | bat | 20.1 ± 0.5 | 77, 87, 9 (pack: 72, 87, 11) | 413 |
| Mossy | - | bat * | 4.2 ± 2.5 | 74, 90, 10 | 31 |
| Museum | s2 | essence skull | 30.6 ± 0.5 | 42, 93, 53 | 526 |
| New Trap | s3 | bat | 5.1 ± 0.5 | 21, 92, 21 | 719 |
| Old Trap | s3 | bat | 7.3 ± 0.5 | 22, 90, 9 | 157 |
| Overgrown | s1 | item | 10.9 ± 0.5 | 4, 70, 2 | 193 |
| Overgrown | s2 | essence skull | 16.1 ± 0.5 | 5, 87, 19 | 320 |
| Overgrown Chains | s2 | item | 6.1 ± 0.5 | 3, 82, 3 | 55 |
| Painting | s2 | item | 8.6 ± 0.5 | 24, 69, 22 (pack: 24, 69, 21) | 228 |
| Painting | s1 | essence skull | 7.9 ± 0.5 | 6, 80, 21 | 419 |
| Pedestal | s3 | item | 11.6 ± 0.5 | 50, 61, 19 (pack: 50, 61, 18) | 180 |
| Pedestal | s2 | essence skull | 13.2 ± 0.5 | 53, 61, 22 | 378 |
| Perch | s1 | item | 20.8 ± 0.5 | 20, 76, 20 | 194 |
| Pipes | s5 | bat | 10.1 ± 0.7 | 122, 57, 18 (pack: 122, 57, 19) | 286 |
| Pipes | s2 | item | 10.5 ± 0.9 | 49, 32, 6 | 97 |
| Pirate | s3 | bat | 5.4 ± 0.8 | 17, 62, 46 (pack: 15, 63, 45) | 199 |
| Pirate | s4 | item | 10.9 ± 0.5 | 4, 60, 58 | 84 |
| Pirate | s5 | essence skull | 17 ± 1.8 | 19, 60, 4 | 509 |
| Pit | s3 | bat | 7.2 ± 0.5 | 8, 78, 28 (pack: 9, 78, 27) | 292 |
| Pit | s4 | item | 10.6 ± 1.8 | 3, 76, 26 | 288 |
| Pressure Plates | s2 | item | 12.3 ± 1.8 | 4, 70, 24 (pack: 4, 70, 23) | 192 |
| Pressure Plates | s4 | item | 9 ± 2.2 | 58, 69, 7 (pack: 57, 69, 7) | 158 |
| Pressure Plates | - | item * | 3.4 ± 1.2 | 60, 95, 7 | 16 |
| Purple Flags | s3 | item | 12.1 ± 0.5 | 27, 49, 28 | 149 |
| Purple Flags | s2 | essence skull | 21.1 ± 0.5 | 6, 57, 28 | 414 |
| Quartz Knight | s3 | bat | 15.7 ± 2.5 | 27, 60, 16 (pack: 29, 61, 17) | 439 |
| Quartz Knight | - | bat * | 2.4 ± 2 | 43, 90, 6 | 29 |
| Quartz Knight | s5 | item | 9.9 ± 1.2 | 67, 84, 6 (pack: 66, 84, 5) | 212 |
| Quartz Knight | s6 | item | 17 ± 0.9 | 95, 79, 26 | 235 |
| Rails | s5 | bat | 12.4 ± 0.5 | 55, 53, 11 (pack: 52, 51, 11) | 176 |
| Rails | s4 | item | 7.9 ± 0.9 | 27, 70, 28 (pack: 27, 70, 29) | 201 |
| Rails | s3 | essence skull | 7.5 ± 0.5 | 25, 70, 28 | 481 |
| Rails | s7 | essence skull | 12.4 ± 1.1 | 6, 49, 10 | 410 |
| Rails | s8 | essence skull | 12.1 ± 1.7 | 3, 49, 10 | 409 |
| Red Blue | - | bat * | 3.3 ± 0.5 | 53, 83, 7 | 70 |
| Red Blue | s3 | item | 13.9 ± 0.5 | 60, 86, 22 | 321 |
| Red Blue | s1 | essence skull | 12.6 ± 0.5 | 79, 83, 15 | 659 |
| Red Green | - | bat * | 3.5 ± 0.5 | 21, 79, 15 | 25 |
| Red Green | s2 | item | 20.9 ± 1.3 | 1, 61, 15 | 251 |
| Red Green | s3 | item | 6.6 ± 0.5 | 13, 77, 28 | 140 |
| Redstone Crypt | D3 | key skull | on an event | 4, 71, 4 | 493 |
| Redstone Crypt | - | key skull * | 3 ± 0.5 | 26, 73, 6 | 330 |
| Redstone Key | s1 | item | 21 ± 1.5 | 26, 69, 5 (pack: 25, 69, 5) | 95 |
| Redstone Key | - | key skull * | 3.9 ± 0.5 | 27, 69, 7 | 173 |
| Redstone Key | - | key skull * | on an event | 10, 70, 26 | 150 |
| Redstone Key | - | key skull * | on an event | 19, 66, 7 | 144 |
| Redstone Warrior | s2 | item | 8.4 ± 0.5 | 58, 78, 29 | 127 |
| Ritual | s3 | bat | 15.3 ± 0.6 | 18, 88, 26 (pack: 15, 86, 25) | 106 |
| Sarcophagus | s3 | bat | 10.6 ± 0.9 | 19, 71, 25 (pack: 19, 71, 26) | 169 |
| Sarcophagus | s2 | essence skull | 12 ± 0.5 | 18, 68, 27 | 273 |
| Scaffolding | s2 | item | 13.1 ± 0.5 | 18, 82, 8 | 161 |
| Slabs | - | item * | 4 ± 2.2 | 22, 86, 3 | 11 |
| Slime | s2 | bat | 8.1 ± 0.6 | 15, 52, 14 (pack: 14, 52, 14) | 309 |
| Slime | s1 | item | 15.5 ± 0.7 | 90, 57, 15 | 283 |
| Slime | s5 | item | 20 ± 0.5 | 4, 86, 10 (pack: 3, 86, 10) | 338 |
| Slime | s4 | essence skull | 9.8 ± 0.5 | 20, 86, 15 | 599 |
| Sloth | s1 | item | 6.9 ± 0.7 | 9, 69, 22 (pack: 8, 69, 22) | 254 |
| Small Stairs | s2 | item | 8 ± 0.8 | 24, 63, 2 (pack: 24, 63, 3) | 129 |
| Small Waterfall | s2 | bat | 5.9 ± 0.7 | 3, 82, 22 | 214 |
| Small Waterfall | s1 | essence skull | 10.9 ± 0.5 | 6, 77, 26 | 404 |
| Spider | s4 | bat | 8.1 ± 0.5 | 55, 86, 35 | 219 |
| Spider | s7 | item | 9.8 ± 1 | 27, 91, 39 (pack: 26, 91, 39) | 174 |
| Spider | s8 | item | 7.7 ± 0.5 | 26, 92, 34 (pack: 24, 92, 34) | 173 |
| Spikes | s3 | bat | 8.5 ± 0.5 | 2, 55, 6 (pack: 2, 54, 7) | 100 |
| Spikes | s2 | essence skull | 7.6 ± 2.9 | 1, 53, 8 | 212 |
| Staircase | s3 | item | 11.7 ± 0.9 | 11, 78, 27 (pack: 12, 78, 26) | 255 |
| Staircase | s2 | essence skull | 10.4 ± 0.5 | 22, 75, 2 | 445 |
| Stairs | s3 | item | 15.5 ± 0.5 | 38, 86, 35 (pack: 37, 86, 36) | 164 |
| Steps | s1 | bat | 10.3 ± 0.5 | 15, 76, 6 (pack: 13, 72, 5) | 172 |
| Supertall | s1 | bat | 13.4 ± 0.7 | 2, 73, 3 (pack: 2, 72, 2) | 274 |
| Supertall | s3 | bat | 15.1 ± 4.4 | 53, 112, 8 | 174 |
| Supertall | s2 | item | 15.4 ± 0.8 | 31, 102, 53 (pack: 31, 101, 53) | 135 |
| Supertall | - | item * | 4.5 ± 3 | 36, 143, 51 | 8 |
| Temple | s3 | bat | 8.5 ± 0.5 | 3, 63, 25 (pack: 2, 65, 25) | 224 |
| Temple | s2 | item | 9 ± 0.5 | 20, 78, 28 | 126 |
| Tic Tac Toe | - | bat * | 3.5 ± 0.5 | 7, 71, 15 | 37 |
| Water | s1 | bat | 17.2 ± 0.5 | 12, 67, 5 (pack: 15, 67, 5) | 165 |
| Waterfall | s4 | bat | 7.1 ± 2 | 70, 61, 3 | 277 |
| Waterfall | s5 | bat | 3.2 ± 0.5 | 88, 46, 27 | 84 |
| Waterfall | - | bat * | 2.8 ± 1.2 | 70, 62, 7 | 37 |
| Waterfall | s7 | item | 7.9 ± 0.7 | 8, 44, 28 | 70 |
| Well | s2 | item | 10.6 ± 0.5 | 59, 91, 36 (pack: 59, 91, 35) | 200 |
| Well | s7 | item | 12.2 ± 0.5 | 15, 57, 16 (pack: 14, 57, 15) | 121 |
| Well | s4 | essence skull | 8.3 ± 0.5 | 7, 95, 56 | 385 |
| Well | s5 | essence skull | 8.2 ± 0.5 | 2, 95, 59 | 380 |
| Withermancer | - | item * | 4.4 ± 0.5 | 59, 56, 45 | 15 |
| Withermancer | s3 | essence skull | 4.9 ± 2 | 1, 111, 15 | 392 |
| Wizard | s1 | bat | 21.1 ± 1 | 22, 54, 8 (pack: 28, 66, 11) | 426 |
| Wizard | s2 | item | 10.3 ± 0.5 | 3, 76, 21 | 328 |
<!-- /TABLE -->
