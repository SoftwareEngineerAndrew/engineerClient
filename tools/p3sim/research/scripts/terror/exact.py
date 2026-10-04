"""terror-mosquito.md §2-4: a Mosquito shot's first two ticks, rebuilt from the exact position Hypixel sends with
each arrow's relaunch. Main arrow: v1 = 0.99*k*v0 - g, k = 1 + 0.01 x stacks; spawn packet = floor-to-1/32 of
pos1 = sync - k*v0. Hydra: roty(.., +-8 deg) of the main's tick-2 move. Duplex: bit-identical copies of the main's
tick-2 state, released 3-4 ticks later.    python3 exact.py   (needs out/trajectories.jsonl)
"""
import collections, math, statistics
from tlib import shot_rows, relaunch, sub, mul, mx, roty, q

rows = shot_rows()
print('main arrows with relaunch velocity + exact position:', len(rows))
print('  v1 = 0.99*k*v0 - g: max component error median %.6f p99 %.6f' % (statistics.median(x['err_v1'] for x in rows), q([x['err_v1'] for x in rows], .99)))
print('  relaunch packet, server ticks after the spawn packet:', dict(collections.Counter(x['dn'] for x in rows)))
print('  stacks from k minus the action bar before the shot:', dict(collections.Counter((x['s'] - x['bar']) if x['bar'] is not None else None for x in rows).most_common()))
fl = collections.Counter()
for x in rows:
    for d in sub(x['r']['main']['spawn'], x['pos1']):
        fl['floor to 1/32' if -1 / 32 - 1e-4 < d <= 1e-4 else 'other'] += 1
print('  spawn packet position - exact pos1, per component:', dict(fl))

# Hydra Strike: no-owner arrows spawned on the main's tick at its point
hy = collections.Counter(); herr = []; hdn = collections.Counter(); hs = collections.Counter()
for x in rows:
    for a in x['r']['near']:
        if a['own'] != 'own-id' or a.get('dn') != 0 or math.dist(a['spawn'], x['r']['main']['spawn']) > 0.05: continue
        rl = relaunch(a)
        if not rl or rl[2] is None: continue
        ang = min((8, -8), key=lambda g: mx(sub(roty(x['v1'], g), rl[1])))
        if mx(sub(roty(x['v1'], ang), rl[1])) > 0.002: continue   # not +-8 deg: an earlier shot's Duplex released from (nearly) the same point
        hy[ang] += 1; hdn[rl[0] - x['dn']] += 1; hs[x['s']] += 1
        herr.append((mx(sub(roty(x['v1'], ang), rl[1])), mx(sub(sub(rl[2], x['pos1']), roty(mul(x['k'], x['v0']), ang)))))
print('\nHydra arrows:', dict(hy), ' relaunch tick minus the main\'s:', dict(hdn))
print('  velocity vs roty(v1, a): error median %.6f;  position vs pos1 + roty(k*v0, a): error median %.6f' % (
    statistics.median(e[0] for e in herr), statistics.median(e[1] for e in herr)))
print('  stacks (from k) of the shots they came with:', dict(sorted(hs.items())))
def hydra(x, a):
    rl = relaunch(a)
    return (a['own'] == 'own-id' and a.get('dn') == 0 and math.dist(a['spawn'], x['r']['main']['spawn']) <= 0.05 and rl and rl[2] is not None
            and min(mx(sub(roty(x['v1'], g), rl[1])) for g in (8, -8)) <= 0.002)
nohy = collections.Counter(x['s'] for x in rows if not any(hydra(x, a) for a in x['r']['near']))
print('  shots WITHOUT Hydra arrows, by stacks:', dict(sorted(nohy.items())))

# Duplex: no-owner arrows whose relaunch copies the main's exactly
cnt = collections.Counter(); sp = collections.Counter(); rl_dn = collections.Counter(); pair = collections.Counter()
for x in rows:
    d = []
    for a in x['r']['near']:
        if a['own'] != 'own-id' or a.get('dn') is None: continue
        rl = relaunch(a)
        if rl and rl[2] and mx(sub(rl[1], x['v1'])) < 1e-9 and mx(sub(rl[2], x['sync'])) < 1e-9:
            d.append(a['dn'] + rl[0] - x['dn']); sp[a['dn']] += 1
    cnt[len(d)] += 1
    if len(d) == 2: pair[tuple(sorted(d))] += 1
print('\nDuplex arrows (relaunched with exactly the main\'s v1 and position) per shot:', dict(sorted(cnt.items())))
print('  spawned (still) ticks after the main:', dict(sorted(sp.items())), '  released, ticks after the main\'s relaunch (both):', dict(pair))
