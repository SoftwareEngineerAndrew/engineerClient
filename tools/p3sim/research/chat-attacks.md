# F7 boss: chat, sounds and attack entities (ground truth for P3 Sim)

Sources
- **Better PF runs** (client view, coloured chat `c`): 959 files, 669 F7 runs with a boss
  (`scripts/bpf_boss_chat.py`, `bpf_run_tail.py`, `bpf_damage_lines.py`). Tick column `med_t` =
  median client ticks since `[BOSS] Maxor: WELL! WELL! WELL!`. Some older runs have no `c`; their
  uncoloured duplicates are ignored below.
- **Boss Recorder** (server packets): 62 of 77 files contain the boss (`scripts/rec_attacks.py`,
  `rec_volleys.py`, `rec_events.py`, `rec_proj_speed.py`). Phase marks are the chat lines
  P1 `Maxor: WELL!`, P2 `Storm: Pathetic Maxor`, P3 `Goldor: Who dares`, P4 `Necron: You went further`.

**M** = measured, **C** = conjecture. All strings are Python `repr`s of the exact `§` text.
`` and similar are Hypixel resource-pack glyphs (private-use codepoints). They are sent literally.

---------------------------------------------------------------------------------------------------

## 1. Chat lines

### 1.1 Boss lines: format `§4[BOSS] <Name>§r§c: <line>` (M, every line, all four bosses)
`Sim.boss()` matches this exactly. The text of every line, with runs seen out of 669 (the scripted
lines sit 62/63 ticks apart):

| boss | line | runs | med_t |
|---|---|---|---|
| Maxor | `WELL! WELL! WELL! LOOK WHO'S HERE!` | 585 | 0 |
| | `I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU.` | 578 | 62 |
| | `DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.` | 577 | 125 |
| | `THAT BEAM! IT HURTS! IT HURTS!!` (stun) | 421 | 254 |
| | `YOU TRICKED ME!` (stun) | 415 | 314 |
| | `I'M TOO YOUNG TO DIE AGAIN!` | 292 | 497 |
| | `YOUR MOBILITY TRICKS DON'T WORK IN MY DOMAIN!` | 49 | 383 |
| | `MY MINIONS WILL HAVE TO WIPE THE FLOOR AFTER I'M DONE WITH YOU ALL!` | 41 | 379 |
| | `I HOPE YOU LIKE EXPLOSIONS TOO!` | 41 | 392 |
| | `YOUR WEAPONS CAN'T PIERCE THROUGH MY SHIELD!` | 32 | 372 |
| | **`How about you taste some rapid fire Wither Skulls!`** (missing in sim) | 28 | 572 |
| | **`Time for me to blast you away for good!`** (missing) | 25 | 566 |
| | **`Eat Wither Skulls, scum!`** (missing) | 22 | 534 |
| | **`FINALLY! This took way too long.`** (missing, very slow P1) | 3 | 944 |
| Storm | `Pathetic Maxor, just like expected.` | 505 | 525 |
| | `Don't boast about beating this simple-minded Wither.` | 504 | 588 |
| | `My abilities are unparalleled, in many ways I am the last bastion.` | 500 | 653 |
| | `The memory of your death will be your fondest, focus up!` | 489 | 716 |
| | `The power of lightning is quite phenomenal. A single strike can vaporize a person whole.` | 472 | 958 |
| | `I'd be happy to show you what that's like!` | 472 | 1022 |
| | `THUNDER LET ME BE YOUR CATALYST!` / `ENERGY HEED MY CALL!` | 250 / 230 | ~1090 |
| | `Ouch, that hurt!` / `Oof` (crushed) | 365 / 300 | ~1330 |
| | `No more adventurers, no more heroes, death and thunder!` | 29 | 1470 |
| | `Not just your land, but every kingdom will soon be ruled by our army of undead!` | 14 | 1529 |
| | `THAT WAS ONLY IN MY WAY!` | 14 | 1570 |
| | `The Age of Men is over, we are creating tens, hundreds of withers!!` | 11 | 1613 |
| | `This factory is too small for me!` | 10 | 1465 |
| | `The days are numbered until I am finally unleashed again on the world!` | 9 | 1421 |
| | `Slowing me down will be your greatest accomplishment!` | 7 | 1560 |
| | `FINALLY! This took way too long.` | 7 | 2376 |
| | `BEGONE PILLAR!` | 6 | 1700 |
| | `Now that you're a Ghost, can you help me clean up?` | 3 | 3095 |
| | `I should have known that I stood no chance.` | 458 | 1388 |
| | `At least my son died by your hands.` | 458 | 1450 |
| Goldor | `Who dares trespass into my domain?` | 458 | 1493 |
| | `Little ants, plotting and scheming, thinking they are invincible...` | 458 | 1553 |
| | `I won't let you break the factory core, I gave my life to my Master.` | 458 | 1615 |
| | `No one matches me in close quarters.` | 457 | 1679 |
| | `What do you think you are doing there!` (death tick) | 358 | 1740 |
| | `The little ants have a brain it seems.` / `I will replace that gate with a stronger one!` / `YOUR END IS NEAR!!` (gates) | 313 / 313 / 314 | ~2050 |
| | **`Do you really think we won't repair everything? Your impact will be minuscule!`** (missing) | 112 | 1857 |
| | **`Come closer!`** (missing) | 108 | 1777 |
| | **`You are breaking precious materials, unforgivable.`** (missing) | 100 | 1829 |
| | **`CLOSER!`** (missing) | 91 | 1830 |
| | **`There is no stopping me down there!`** (missing) | 87 | 1871 |
| | **`I am the death zone, you are smart to flee.`** (missing) | 86 | 1781 |
| | **`You can't damage me, you can barely slow me down!`** (missing) | 85 | 1827 |
| | **`Slowing me down only prolongs your pain!`** (missing) | 83 | 1909 |
| | `Stop touching those terminals!` | 82 | 1851 |
| | **`Closer to me!`** (missing) | 76 | 1817 |
| | `You have done it, you destroyed the factory...` / `But you have nowhere to hide anymore!` / `YOU ARE FACE TO FACE WITH GOLDOR!` | 116 / 115 / 114 | 2769 / 2832 / 2895 |
| | `....` then `Necron, forgive me.` | 419 | 2647 / 2731 |
| Necron | `You went further than any human before, congratulations.` | 420 | 2708 |
| | `I'm afraid, your journey ends now.` / `Goodbye.` | 419 | 2770 / 2833 |
| | `That's a very impressive trick. I guess I'll have to handle this myself.` | 419 | 2895 |
| | `Sometimes when you have a problem, you just need to destroy it all and start again.` | 325 | 3049 |
| | `WITNESS MY RAW NUCLEAR POWER!` | 294 | 3057 |
| | `ARGH!` (x2) / `Let's make some space!` | 419 | 3149 / 3106 |
| | `All this, for nothing...` | 411 | 3327 |
| | `I understand your words now, my master.` | 37 (5.5 %) | 3442 |
| | `IF YOU WANT TO STAY COOL, DON'T LOOK!` (4 runs, slow) and the WS/"The Eye likes you" lines (4 runs: M7 only, ignore) | | |

The Goldor taunts marked missing are a pool that Hypixel picks from while terminals are being done
(C: triggered by players near him / breaking blocks / hitting him, by the wording). Each run gets
about 1-3 of them, 1777-1909 median ticks, i.e. inside S1-S2.

### 1.2 Other lines, real vs sim

| event | Hypixel (M) | sim |
|---|---|---|
| crystal pickup | `'§b<P>§r§a picked up an §r§bEnergy Crystal§r§a!'` (name in the player's rank colour; when it is `§a`: `'§a<P> picked up an §r§bEnergy Crystal§r§a!'`) | `§a<me> picked up an Energy Crystal!` **mismatch** |
| crystal placed | `'§c1§r§a/2 Energy Crystals are now active!'`, and `'§a2/2 Energy Crystals are now active!'` in 208 runs (C: the full count is all green) | always `§c$x§r§a/2` **mismatch for 2/2** |
| laser | `'§aThe Energy Laser is charging up!'` | match |
| enraged | `'§c⚠ Maxor is enraged! ⚠'`, `'§c⚠ Storm is enraged! ⚠'` | match |
| progress | `'<col><P>§r§a activated a terminal! (§r§c4§r§a/7)'`, `activated a lever!`, `completed a device!`. `<col>` is the rank colour: `§b` MVP+, `§6` MVP++, `§7` none, and with `§a` (VIP) the `§r§a` is dropped: `'§a<P> activated a terminal! (§r§c4§r§a/7)'` | `§a<bot>§r§a ...` for bots **mismatch** (drop the `§r§a` when the colour is `§a`) |
| gate | `'§aThe gate will open in 5 seconds!'` (only when the last part is a lever/device that leaves the gate shut, 96 runs), `'§aThe gate has been destroyed!'` | match |
| core | `'§aThe Core entrance is opening!'` | match |
| lever done | `'§cSomeone has already activated this lever!'` | match |
| terminal off-section | `"§cThis Terminal doesn't seem to be responsive at the moment."` | match |
| terminal done | `'§cThis Terminal has already been completed!'` | match |
| lever off-section / terminal busy | never seen in 669 runs (unverified) | `This lever doesn't seem...`, `Someone is already using this terminal!` |
| Spirit | `'§6Second Wind Activated§r§a! Your Spirit Mask saved your life!'` | `§r§6Second Wind Activated!§r§a Your Spirit Mask saved your life!` **mismatch** |
| Bonzo | `"§aYour §r§9 Bonzo's Mask §r§asaved your life!"` | `§r§aYour §r§9⚚ Bonzo's Mask§r§a saved your life!` **mismatch** |
| Phoenix | `'§eYour §r§cPhoenix Pet §r§esaved you from certain death!'` | `§r§aYour Phoenix Pet saved you from certain death!` **mismatch** |
| you die | `'§c ☠ §r§7You died and became a ghost.'` (59 runs), `'§c ☠ §r§7You were killed by Maxor and became a ghost.'` (14; also `Storm`, `Wither Miner`, `Wither Guard`) | `§r§c ☠ §r§7You were killed by $by§r§7 and became a ghost§r§7.` **mismatch** |
| others die | `'§c ☠ §r§b<P>§r§7 died and became a ghost.'`, `'§c ☠ §r§b<P>§r§7 was killed by Goldor and became a ghost.'`, `'§c ☠ §r§b<P>§r§7 disconnected and became a ghost.'` | (none) |
| revive | `'§a ❣ §r§b<P> §r§7is reviving §r§b<P>§r§7!'`, `'§a ❣ §r§b<P>§r§a was revived by §r§b<P>§r§a!'`, `'§a ❣ §r§7You are reviving §r§b<P>§r§7!'` | (none) |

Damage lines (M, exact colour and number format; numbers vary with armour/EHP):

| line | values (M) | sim |
|---|---|---|
| `"§cMaxor's§r§7 Frenzy hit you for §r§c20,987§r§7 damage."` | ~20-24k, med 5 a run, P1 t127-785 | none |
| `"§7Maxor's Wither TNT hit you for §r§c7200.0§r§7 damage."` (note: `§7` start, **no thousands comma, `.0`**) | 7200.0 | none |
| `"§cMaxor's§r§7 Shadow Wave hit you for §r§c1,800§r§7 damage."` | 1,800 | none |
| `'§7A Crypt Wither Skull exploded, hitting you for §r§c9,000§r§7 damage.'` | 9,000 (23,760 rarely) P1/P2 | none |
| `"§cStorm's§r§7 Giga Lightning hit you for §r§c9,531.6§r§7 true damage."` | 8.3-10.4k | match |
| `"§cStorm's§r§7 Static Field hit you for §r§c10,800§r§7 damage."` | 10,800 (14,400) | none |
| `"§cStorm's§r§7 Lightning Fireball hit you for §r§c75,000§r§7 damage."` | 75,000 always | none |
| `"§cStorm's§r§7 Frenzy hit you for §r§c2,049.1§r§7 damage."` | ~2k | none |
| `"§cStorm's §r§8Shadow Explosion§r§7 hit you for §r§c676.5§r§7 damage."` | rare | none |
| `"§cGoldor's§r§7 Frenzy hit you for §r§c31,973.3§r§7 damage."` | 2.7k-52.8k, one decimal | `§cGoldor's Frenzy hit you for 30000-40000 damage.` **mismatch** |
| `"§cGoldor's§r§7 TNT Trap hit you for §r§c60,000§r§7 damage."` | 60,000 | none |
| `"§cGoldor's§r§7 Greatsword hit you for §r§c120,000§r§7 damage."` | 120,000 (24 runs, P3) | none |
| `"§cNecron's§r§7 Nuclear Frenzy hit you for §r§c57,600§r§7 damage."` | 57,600 always | `§cNecron's Nuclear Frenzy hit you for 57,600 damage.` **mismatch** |
| `"§7Necron's Wither TNT hit you for §r§c10800.0§r§7 damage."` | 10800.0 | none |
| `'§cThe Arrow Trap hit you for 1,906.3 damage!'` | ~2k (P2/P3 arrow traps) | none |

### 1.3 End screen (M, order exact, all on the same tick)
```
'§a§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬'
'§f                        §r§cThe Catacombs §r§8- §r§eFloor VII'
''
'§f                           Team Score: §r§a300 §r§f(§r§b§lS+§r§f)'
'§f     §r§c☠ §r§eDefeated §r§cMaxor, Storm, Goldor, and Necron §r§ein §r§a06m 21s'
'§f                             §6> §e§lEXTRA STATS §6<'
'§f                     §r§8+§r§346,200 Catacombs Experience'
'§f                       §r§8+§r§328,000 Berserk Experience'
'§f                 §r§8+§r§37,000 Tank Experience §r§b(Team Bonus)'   (one per other class)
'§a§l▬▬▬...'
```
then, 2 ticks later, the "Floor VII Stats" block (`'§f                    §r§cThe Catacombs §r§8- §r§eFloor VII Stats'`, `''`,
Team Score, Defeated, `''`, `Total Damage as <Class>: §r§a<n>`, `Ally Healing:`, `Enemies Killed:`, `Deaths: §r§c<n>`,
`Secrets Found: §r§b<n>`, `''`, bar). Grade colours: `§b§lS+`, `§bS` (28 spaces of padding instead of 27), `§aA`, `§cD`.

Sim mismatches: Team Score line `'§f                       §r§fTeam Score: §r§a317 §r§f(§r§6S+§r§f)'` ->
`'§f                           Team Score: §r§a317 §r§f(§r§b§lS+§r§f)'`; the `''` after the title line is missing;
no EXTRA STATS line. The Defeated line matches.

---------------------------------------------------------------------------------------------------

## 2. Titles, action bar, sounds

**Titles / action bar: not measurable.** Neither recorder captures them (BossRecorder drops overlay
chat, `BossRecorder.kt:73`; Better PF has no title kind). C: Hypixel shows no title for
terminals, gates or the core in F7; Odin's titles are client-side and run off the chat lines above.
To settle it, add title/subtitle/action-bar packets to BossRecorder.

**Sounds (M, `rec_events.py`: `snd` packets within +-1 server tick of the chat line, in >=20 % of cases):**

| event | sound (source, volume, pitch as received) | sim |
|---|---|---|
| every progress line (terminal 1011/1012, lever 474/474, device 251/251; so gate/core lines too) | `block.note_block.pling`, BLOCK, vol **8.0**, pitch **4.05** (byte 255 via Via, the client clamps to **2.0**), **at your own position** (dist < 2 in 911/911): everyone hears it for anyone's progress | `NOTE_BLOCK_PLING 0.6, 2.0` at the station, terminals and levers only, **mismatch** |
| `[BOSS]` line | `entity.wither.ambient`, MASTER, vol 5.0, pitch 1.19, at the boss (2335/2424) | none **missing** |
| gate destroyed | `entity.generic.explode` BLOCK vol 0.5 pitch 0.49 (55/179: when close enough) | `GENERIC_EXPLODE 2.0, 1.0` |
| mask proc (Spirit/Bonzo) | `entity.zombie_villager.cure` v1 p2.0 + `entity.wither.ambient` v1 p1.0 + `entity.generic.eat` v0.9 p0.59 (51/51) | `TOTEM_USE 0.4, 1.4` **mismatch** |
| Phoenix proc | `block.lava.extinguish` v1 p1.49 + `entity.zombie.infect` v1 p1.19 + `entity.wither.ambient` v1 p1.0 (+ `ghast.affectionate_scream` p~1.5) | totem |
| Giga Lightning | `entity.lightning_bolt.thunder` v2.0 p1.4 (68/68) | (bolt entity's own) |
| Necron Nuclear Frenzy pulse | `entity.generic.explode` v30 p0.49 + `entity.wither.ambient` v30 p0.70 (+ `wither.death` p0.5) | `GENERIC_HURT` |
| Goldor Frenzy | `entity.generic.explode` v0.5 p0.49 + `entity.player.hurt` | none |
| lever click | `block.lever.click` v0.3 p0.59 | match (0.3, 0.6) |
| crystal pick/place, laser, enrage, death | nothing consistent | |
| run end | `elder_guardian.ambient` v1 p0.49, `glass.place` v0.15 | none |

---------------------------------------------------------------------------------------------------

## 3. Attack entities (M, `rec_attacks.py` / `rec_volleys.py`; 62 recordings)

Projectile `a` velocities are the spawn packet's: fireballs and skulls are always **0.1 b/t**, which
is their per-tick acceleration (vanilla `power`), not their speed. Measured flight speed from
position packets (noisy): ~0.5 b/t at 10 ticks, 1.0-1.6 by 20-40 ticks (skulls), up to ~2 b/t
(fireballs) (`rec_proj_speed.py`). Lifetime median: skull 14 ticks, fireball 26, **TNT 23 (fuse ~20
ticks, not vanilla 80)**. Server sends no `dmg` packets to you; own `hp` drops line up with the chat
lines (40-HP bar: Lightning Fireball -8.5, Maxor Frenzy -5.5, Static Field -0.9, Crypt skull -0.2..0.9).

### P1 Maxor
- **Wither skulls from Maxor**: 2086 in 61 runs, 1-2 a tick (max 5), volleys ~5 ticks apart
  (gap q 4/5/7), t-in-P1 191-983 (median 483): he fires while not stunned. Spawn 2.5 blocks from
  his position (head), pitch median 36 deg down. C: aimed at a player (target = his head yaw).
  Damage line: none of his own; the explosion is the "Crypt Wither Skull" 9,000 line (C).
- **Wither skulls from wither skeletons** (the crypt minions): 1458 + 872 unknown-owner, bursts up
  to 41 a tick, near-flat pitch (median 3 deg).
- **TNT**: 94 in 53 runs, 1 at a time, every ~100 ticks (gap q 84/104/118), t 279-974, at
  (73-75, 226, 72-75) = Maxor's spot and (64, 248, 42); vy 0.2. Line: `Maxor's Wither TNT ... 7200.0`.
- **Frenzy**: no entity; chat ~20-24k, 5 a run median (up to 20), t 127-785 (C: proximity pulse).
- Falling blocks from t 526 (median 600): the floor collapsing at the P1->P2 drop (1429).

### P2 Storm
- **Fireballs from Storm**: 475 in 62 runs, one at a time, **every ~44 ticks** (gap q 40/44/50),
  t-in-P2 78-1875 (median 296); spawn y 184 at 8 spots ~ (51,184,46), (58,184,68), (47,184,57),
  (70,184,79), (62,184,34), (84,184,35), (81,184,75), (96,184,46) (Storm's position), 0.8 blocks
  from him, pitch median 23 deg down at a player (horiz. dist from you median 50). Damage:
  **Lightning Fireball 75,000** flat.
- **Wither skulls from Storm**: 2592, 2 a tick, every 5 ticks, t-in-P2 724-940 mostly (the
  pre-lightning stretch), pitch ~11 deg down. Plus ~13k skeleton skulls (crypt minions, t 3-1200).
- **Lightning bolts**: 15691 (~250 a run), t 99-2573, up to 40 on one tick; median 28 blocks from
  you, some within 0.01 (strikes on players). Sound for the Giga hit: thunder v2 p1.4. Damage: Giga
  Lightning 8.3-10.4k true (sim has it), Static Field 10,800 (C: standing near Storm/the bolt field).
- Falling blocks ~97k (pillar crush / platform) t ~1000-1100.

### P3 Goldor
- **TNT Trap: 27 TNT on one tick, a 3x3x3 cube** (x/y/z each spanning 2 blocks, centred on the
  block centres, y 127.5-131.5), lifetime ~21 ticks. 3024 TNT = 112 volleys in 50 runs. Fixed
  spots (lower corner): (99.5,127.5,85.5), (99.5,127.5,103.5), (53.5,127.5,131.5), (35.5,127.5,131.5),
  (7.5,127.5,85.5), (7.5,127.5,67.5), (71.5,129.5,39.5), (53.5,127.5,39.5), (35.5,129.5,39.5): two a
  side, one per terminal area in S1 at t~250-290 (every run that reaches it), later ones at
  ~470-2240. C: triggered when a player stands under/near that spot. Damage `Goldor's TNT Trap
  60,000`. The sim has none of this.
- **Goldor wither skulls**: 220 in only 16 runs, 2 a tick every 5 ticks, t-in-P3 991-2313: only
  when he is targeting someone (C: the S4/core end). No fireballs.
- **Frenzy**: chat only (2.7-52.8k, one decimal), sound explode v0.5 + hurt. **Greatsword**
  120,000 (24 runs). Lightning in P3 (24k bolts, t 0-316, y ~169) is Storm's platform after his
  death, not an attack on you. 1034 `giant` adds are the Goldor-side terminal giants/decoration.

### P4 Necron
- **Fireballs from Necron**: 896 in 56 runs, 1 every **10 ticks** (gap q 10/10/11), t-in-P4 58-492,
  all from (54, 69, 76) (Necron at (54,66,76)), 2.97 blocks from him, pitch ~11 deg down at a
  player 30-39 blocks away. The sim draws them at 0.8 b/t without damage; real ones start at 0.1
  and accelerate (~1-1.7 b/t by 20-40 ticks). No damage line seen for them (C: they break the
  platform: 842 `ex` explosions in P4, all radius 0, and 13.9k falling blocks at t 435-521).
- **Lightning**: 1680, 1 every ~10 ticks alongside the fireballs (t 60-409), some on players.
- **Wither skulls**: 177 in 53 runs, 2 at once, t 180-497.
- **TNT**: 16 runs, 1 each at t ~395-485, at (54,66,76) = Necron (the `Necron's Wither TNT 10800.0`
  line, 20 runs).
- **Nuclear Frenzy**: 57,600 flat, chat + explode v30 p0.49 + wither.ambient v30 p0.70.

---------------------------------------------------------------------------------------------------

## Fix list (old -> new), most visible first
1. `Masks`: `"§r§c ☠ §r§7You were killed by $by§r§7 and became a ghost§r§7."` -> `"§c ☠ §r§7You were killed by $by and became a ghost."` (or `"§c ☠ §r§7You died and became a ghost."` when there is no named killer, the common P3 case).
2. `Masks` Spirit -> `"§6Second Wind Activated§r§a! Your Spirit Mask saved your life!"`; Bonzo -> `"§aYour §r§9 Bonzo's Mask §r§asaved your life!"`; Phoenix -> `"§eYour §r§cPhoenix Pet §r§esaved you from certain death!"`; sound TOTEM_USE -> zombie_villager.cure (1, 2.0) + wither.ambient (1, 1.0) + generic.eat (0.9, 0.59).
3. `P2Storm.TAUNTS`: five are truncated with `...`: use the full strings in 1.1 (`No more adventurers, no more heroes, death and thunder!`, `Not just your land, but every kingdom will soon be ruled by our army of undead!`, `The days are numbered until I am finally unleashed again on the world!`, `Now that you're a Ghost, can you help me clean up?`, `The Age of Men is over, we are creating tens, hundreds of withers!!`).
4. Crystal pickup `"§a${me} picked up an Energy Crystal!"` -> `"§b${me}§r§a picked up an §r§bEnergy Crystal§r§a!"`; crystals `"§c2§r§a/2 ..."` -> `"§a2/2 Energy Crystals are now active!"`.
5. Progress line: `§a<bot>§r§a activated` -> `§a<bot> activated` (Hypixel drops the reset when the name is green).
6. Goldor Frenzy -> `"§cGoldor's§r§7 Frenzy hit you for §r§c%,.1f§r§7 damage."`; Nuclear Frenzy -> `"§cNecron's§r§7 Nuclear Frenzy hit you for §r§c57,600§r§7 damage."`.
7. Team Score -> `"§f                           Team Score: §r§a<n> §r§f(§r§b§lS+§r§f)"`, add `""` after the Floor VII line and the `§6> §e§lEXTRA STATS §6<` line.
8. Missing lines: Goldor's ~10-line S1-S2 taunt pool, Maxor's 3 skull taunts (~t 534-572).
9. Sounds: pling vol 8 pitch 2 at the player on every progress line (devices too); wither.ambient (5, 1.19) at the boss on each `[BOSS]` line.
10. Attacks not in the sim at all: P3 27-TNT cube traps (60,000), Storm's 44-tick fireballs (75,000), Maxor's TNT (7200.0) and skull volleys, Necron's fireballs should accelerate from 0.1.
