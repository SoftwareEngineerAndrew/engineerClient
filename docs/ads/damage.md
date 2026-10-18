# Damage, the player's items and the secret bat (work item `damage`)

Code: `src/p3sim/clear/damage/` (the hub, `ClearItems`, `SimPearl`, the pure rules), plus the item files the work item owns: `SimItems.kt`, `Bows.kt`, `Mage.kt`, `Masks.kt`.
Tests: `test/p3sim/damage/`. The numbers and lists live in `ClearItems` and `ImplosionRules` so they test without Minecraft.

## What the clear does with the player's items

| item (custom-data id) | what a right click does in the clear | measured from |
|---|---|---|
| Ender Pearl (`ENDER_PEARL`) | throws a real pearl: `entity.arrow.shoot` (neutral, volume 0.5, pitch 0.4 / (random * 0.4 + 0.8)) at your feet, 1.5 blocks a tick, one pearl off the stack. On landing 32 portal puffs, you are put at the pearl's previous position (look kept, no damage), one enderman teleport sound. Trap rooms do not stop it. The boss room still refuses it (one off the stack, Hypixel's red "mystical force" line). | recorded pearl throws: sound and entity 0 to 2 ticks after the click, the stack count down by one in 133 of 135 throws |
| Spirit Leap (`SPIRIT_LEAP`, the stack of 5 on a room's floor) | opens the same Spirit Leap window as the InfiniLeap; its own 5 second cooldown; a completed leap uses up one | the item's own lore (cooldown 5 s); that a leap uses one up is not seen in any recording (UNMEASURED, the item is a stack of 5 next to an "infinite" version) |
| Decoy, Inflatable Jerry, Trap, Defuse Kit, Training Weights, Healing splash potion | the click is eaten and a short note says the ability is not in ADS yet, so vanilla never throws, spawns or splashes anything | the abilities themselves are missing (listed in the report) |

Arrows (the first leg of a shot and the flying arrow) hit a secret bat and kill it; Explosive Shot and Guided Sheep blasts kill every secret bat inside their radius; an
Implosion counts each bat in its box as an enemy, so the line and the ding fire even when the bat is all it killed. A bat has 100 HP in the recordings and dies in one
packet, so any hit is enough. The only recorded arrow kill is one sound-matched death; the rest rests on that one-hit fact.

## What is not here

* The abilities of the Decoy, Inflatable Jerry, Trap, Defuse Kit, Training Weights and the Healing VIII splash potion.
* The one second of immunity after a leap that the leap items' lore promises.
* The clear's ambient particles and block-break level events. Real Hypixel sends several thousand particle packets in a clear, but none of the clusters measured so far belong to the
  items this module owns: the five-type trails (dust, enchant, entity_effect, large_smoke, witch, one point every 0.1 block) drift at about 0.28 blocks a tick from a point
  ten blocks away from the player, so they are not the player's arrows (an arrow covers about 3 blocks a tick) and most likely come from a mob's slow projectile (the mobs module's);
  the firework and smoke bursts sit at fixed block positions (doors, secrets, mobs). The block-break level events (type 2001) in the clear's area are mostly a redstone-block
  state around mobs, then room-wide bursts of 30 to 50 stone breaks together with a villager-cure sound; only one of about 500 comes within a few ticks of a Dungeonbreaker use. What this module does own already matches the recordings: the Implosion's puff (8 explosion particles, speed 8, always shown, at the
  caster's feet), the Superboom's burst, and now the pearl's portal puffs.

## Testing in game

`/ads dev item <leap|decoy|jerry|trap|kit|weights|potion> [count]` puts the item in your inventory.

1. Start a run, move the stack of 12 pearls from your inventory onto the hotbar, hold it and right click in the clear: a pearl flies, a quiet shoot sound plays, the count goes down by one, you land where it hit with portal puffs
   and an enderman sound, and your health bar does not move. In the boss stage the same click prints the red line and throws nothing. In a trap room the pearl still flies.
2. `/ads dev item leap`, right click it: the Spirit Leap window opens. Leap: you move, the stack goes from 5 to 4. Click it again inside 5 seconds: the cooldown line with the
   seconds left. The InfiniLeap's own 2 second cooldown is separate.
3. `/ads dev item decoy` (and jerry, trap, kit, weights, potion), right click in the air and on a block: nothing is thrown or spawned, a grey note appears, the stack does not shrink.
4. In a bat room (`/ads dev secrets`, `/ads dev secretgo <n>` to the bat) shoot the bat with a bow or the Terminator: it dies, the bat secret counts once and the bonus point is scored.
   Also try an Explosive Shot next to it, and an Implosion with only the bat in range: the "Your Implosion hit 1 enemy" line and the ding appear.
