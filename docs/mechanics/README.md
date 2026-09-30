# Boss mechanics, from the Better PF recordings

What each F7 boss fight does, measured from the recorded runs (alpha-server runs left out). Each
report says what is measured and what is conjecture; the scripts that print every number are in
`tools/boss-mechanics/<boss>/`.

| fight | report | older reports |
|---|---|---|
| The Watcher (blood camp) | [watcher.md](watcher.md) | |
| Maxor (P1) | [maxor.md](maxor.md) | [../maxor-storm-movement.md](../maxor-storm-movement.md) §2 (corrected in maxor.md §9) |
| Storm (P2) | [storm.md](storm.md) | [../maxor-storm-movement.md](../maxor-storm-movement.md), [../storm-crush.md](../storm-crush.md) |
| Goldor (P3) | [goldor.md](goldor.md), strategy: [terminals-strategy.md](terminals-strategy.md), roles: [terminal-roles.md](terminal-roles.md) | |
| Necron (P4) | [necron.md](necron.md) | |

## What the recordings could not answer, and what now will

Everything above was measured from the client's view: mobs slid over 3 ticks toward where the
server put them, chat and blocks stamped a tick late, other players only as the recorder saw them,
and no health or damage at all. The **Boss Recorder** module ([boss-recorder.md](../boss-recorder.md))
now records the fights from the server's own packets, each stamped with the server tick it arrived
on: in the boss and the Watcher camp every entity, elsewhere the boss withers.
`tools/boss-mechanics/netlog.py` reads its files.

| open question | answered by |
|---|---|
| **All bosses:** is a phase change (Maxor's enrage and kill, Storm's pin, Goldor's death, Necron's return to mid) a health threshold, and how much damage does it take | `bb` boss bar progress, `d` synced health and name tags, `dmg` (who hit, with what) |
| **Maxor, Storm:** the skipped moves (~5-10% of ticks), server hiccup or the boss pausing | `m`/`sy` per server tick (a tick with no packet is a real gap), `time` (the server's clock) and `pg` (each ping) for lag |
| **Maxor, Storm, Necron:** who is targeted, and how fast they re-target | `h` head yaw against every player's `m`/`tp` positions (the server's, not the recorder's view) |
| **Maxor:** what the abilities do, the exact stun area | `a` (skulls, fireballs, with owner and velocity), `ex`, `snd`, `b` (the beacon), `m` of Maxor on the check ticks |
| **Storm:** the lightning, the one unexplained miss (a pillar still coming down), the +z edge | `b` pillar steps to the tick, `dmg`/`hp` for the lightning, Storm's `m` on the check ticks |
| **Goldor:** his health and hits, what slows him, the core "everyone in" box | `dmg`/`an` on Goldor, `d` health, other players' `m` when he leaves the track |
| **Necron:** what gates his return to mid (B), what starts volley 2, the S-platform removal, his target | `bb`/`d`/`dmg`, `a` fireballs (spawn tick, velocity, owner), `b` platform blocks, `h` |
| **Watcher:** exact mob spawn and death ticks, mini-boss choice, the move's timing | `a` (every head and mob, to the tick, wherever the recorder stands), `ev` 3 / `r` deaths, the Watcher's own `m` |
| **Death ticks (P3), Nuclear Frenzy (P4):** exact hit ticks | `hp` (your own health), `dmg` on players, `ex` |

The answers need Boss Recorder files: turn the module on and play F7 (every party member recording
it gives every player's own view too).
