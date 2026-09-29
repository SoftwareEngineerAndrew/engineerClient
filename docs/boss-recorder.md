# Boss Recorder

A module (Engineer Client category, on by default) that records the dungeon boss fights packet by packet, for
working out their mechanics: what [`docs/mechanics/`](mechanics/README.md) could not measure from
Better PF's recordings. Better PF records the client's view once a client tick: mobs slid over 3
ticks toward where the server put them, chat and blocks a tick late, other players only as they
are drawn, and no health or damage. This keeps the server's packets themselves, each stamped with
the server tick it arrived on, so a boss's health, a single skipped move, who he is facing and a
projectile's aim are all there to the tick.

Everything is read as it comes off the network, ahead of any mod that could cancel a packet, with
bundled packets (how the server sends a new entity) unpacked.

## Settings

| setting | default | |
|---|---|---|
| Watcher Camp | on | also record the blood camp, from "The BLOOD DOOR has been opened!" until the Watcher's "You have proven yourself" |
| Sounds | on | sounds during the fights |
| Blocks | on | block changes during the fights |
| Hide Private Chats | on | private, guild, officer, co-op and friend lines are left out |
| Recording / Saved Message | on | chat lines when a recording starts and is saved |
| Open Folder | | opens the folder below |

## Files

`.minecraft/config/engineerclient/bossrecorder/<yyyy-MM-dd_HH-mm-ss>_<floor>.jsonl.gz`
(`bosses-*.jsonl.gz.part` while recording). A file is only started the first time a world is **in
focus** (in the boss, or in the Watcher's camp), so a world without a boss fight leaves none. It
ends when the world unloads. Stay in until the end of the run for a complete one.

Gzipped JSON Lines, one object a line with `k` (kind); `t` is client ticks since the world loaded,
`n` the server tick count (one a ping, as Odin and Better PF's `st` count them).

| k | fields | meaning |
|---|---|---|
| `meta` | `format: "bosses-1", mod, mc, self, selfId, startMs, t, n` | first line: your name and entity id (the id `dmg` and `a` use for you) |
| `time` | `t, ms` | wall-clock every 20 ticks |
| `floor` / `party` | as in Better PF | |
| `chat` | `t, n, m` | a chat line (formatting stripped) and the server tick it arrived on |
| `pal` | `i, s` | block-state palette entry, for `b` |
| `net` | `t, ms, d: [[n, kind, ...], ...]` | the packets since the last client tick; `ms` the wall-clock time (Maxor's stun cooldown is real time) |
| `end` | `t, ms` | the world unloaded |

## `net` entries

Always kept: boss withers' packets (every `minecraft:wither`: Maxor, Storm, Goldor, Necron), boss
bars and `time`. In focus, every entity's packets are kept, and the rest of the kinds below too.
Positions are absolute (5 decimals), angles in degrees, velocities in blocks a tick.

| kind | fields | packet |
|---|---|---|
| `time` | `gameTime` | the server's world clock (every second): its own tick count, to tell server lag from a skipped move |
| `pg` | `id` | a ping, the packet server ticks are counted by (as Odin counts them: one a ping with a non-zero id); `n` includes this one |
| `m` | `id, x, y, z, yaw, pitch, onGround` | a relative move; `x, y, z` null when it only turned, `yaw, pitch` null when it only moved |
| `md` | `id, dx, dy, dz, yaw, pitch, onGround` | a relative move for an entity not in the world yet: the raw delta in 1/4096 blocks |
| `tp` | `id, x, y, z, yaw, pitch, onGround, unresolved?` | an entity teleport; `unresolved` 1 if parts of it were relative to an entity not in the world |
| `sy` | `id, x, y, z, yaw, pitch, onGround` | a position sync (the absolute position the server re-sends every so often) |
| `h` | `id, headYaw` | head turn: a wither faces its target |
| `v` | `id, vx, vy, vz` | velocity |
| `a` | `id, type, x, y, z, vx, vy, vz, yaw, pitch, headYaw, data, name?` | entity added, straight from the server: exact spawn tick and velocity; `data` is the type's spawn data: for projectiles (fireballs, wither skulls, arrows) their owner's entity id, for falling blocks the block state id |
| `r` | `[id, ...]` | entities removed |
| `ev` | `id, event` | entity event, as in vanilla `EntityEvent` (3 = death) |
| `dmg` | `id, damageType, causeId, directId, x?, y?, z?` | the entity took damage: type (`minecraft:player_attack`, `minecraft:explosion`...), who caused it and what hit it (entity ids, -1 none) |
| `hurt` | `id, yaw` | hurt animation |
| `an` | `id, action` | animation: 0 swing, 3 swing off hand, 4 crit, 5 magic crit (someone's hit landing) |
| `d` | `id, [[index, value], ...]` | synced entity data: only numbers, flags and names (a living entity's health is one of its floats; the custom name, e.g. Hypixel's health tags, is index 2) |
| `pas` | `vehicle, [id, ...]` | passengers (name tags riding their mob) |
| `bb` | `op, uuid, name?, progress?, colour?` | boss bar: `add` (name, progress 0-1, colour), `progress`, `name`, `remove` |
| `ex` | `x, y, z, radius, blocks, kx?, ky?, kz?` | explosion; `k` the knockback it gave you |
| `snd` | `sound, source, x, y, z, volume, pitch` | a sound at a position |
| `sde` | `sound, id, volume, pitch` | a sound from an entity |
| `hp` | `health, food, saturation` | your own health (death ticks, Nuclear Frenzy) |
| `b` | `x, y, z, palette` | a block change (`pal` index), exact to the tick: pillars, doors, platforms, the Maxor beacon |
| `me` | `x, y, z, yaw, pitch, onGround` | your own movement as sent to the server (it never sends it back); nulls as for `m` |
| `msw` | | you swung (left click, or a right click that hit something) |

## Reading them

```
python3 tools/boss-mechanics/netlog.py FILE.jsonl.gz            # what the file holds, by kind
python3 tools/boss-mechanics/netlog.py FILE.jsonl.gz ENTITY_ID  # one entity's packets, tick by tick
```

As a module, `NetLog` gives the withers, an entity's moves, head yaw, synced health, the server
ticks it has no position packet on, and the server clock against the tick count.
