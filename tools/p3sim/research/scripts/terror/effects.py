"""terror-mosquito.md §7: what a shot sounds and looks like. Arrow sounds near p3wr after isolated Mosquito shots
(main, Duplex release); loud shoot sounds per shot at 10 stacks vs fewer (are Hydra arrows silent?); the entity data
of main / Hydra / Duplex arrows.    python3 effects.py   (needs out/cd.jsonl, out/trajectories.jsonl)
"""
import collections, json, math
from tlib import jsonl, shot_rows, relaunch, roty, sub, mx

R = jsonl('cd.jsonl')
rows = shot_rows()
stacks = {(x['r']['rec'], x['r']['n']): x['s'] for x in rows}
per = collections.defaultdict(collections.Counter); loud = collections.defaultdict(collections.Counter)
for row in R:
    ev = row['events']
    shots = [(i, e) for i, e in enumerate(ev) if e['e'] == 'shot']
    for i, e in shots:
        near = [o for _, o in shots if o is not e and abs(o['n'] - e['n']) <= 8]
        snd = [x for x in ev[max(0, i - 30):i + 200] if x['e'] == 'sound' and 0 <= x['n'] - e['n'] <= 8 and 'arrow' in x['s']]
        if not near:
            for x in snd: per[(x['s'].split('.')[-1], x['n'] - e['n'])][(round(x['pitch'], 3) if 'shoot' in x['s'] and x['vol'] < 1 else 'random', x['vol'])] += 1
        s = stacks.get((row['rec'], e['n']))
        if s is not None and not [o for _, o in shots if o is not e and abs(o['n'] - e['n']) <= 2]:
            loud['10' if s == 10 else '<10'][sum(1 for x in snd if 'shoot' in x['s'] and x['vol'] == 1.0 and x['n'] - e['n'] <= 1)] += 1
print('arrow sounds near p3wr after isolated shots: (sound, ticks after the shot) -> {(pitch, volume): count}')
for k in sorted(per): print('  ', k, dict(per[k]))
print('loud (vol 1.0) shoot sounds on the shot tick, by stacks:', {k: dict(v) for k, v in loud.items()})

dat = collections.defaultdict(collections.Counter)
for x in rows:
    r = x['r']; dat['main'][json.dumps(r['main']['data'], sort_keys=True)] += 1
    for a in r['near']:
        rl = relaunch(a)
        if a['own'] != 'own-id' or a.get('dn') is None or not rl or not rl[2]: continue
        if a['dn'] == 0 and math.dist(a['spawn'], r['main']['spawn']) < 0.05 and min(mx(sub(roty(x['v1'], g), rl[1])) for g in (8, -8)) < 0.002: role = 'hydra'
        elif mx(sub(rl[1], x['v1'])) < 1e-9: role = 'duplex'
        else: continue
        dat[role][json.dumps(a['data'], sort_keys=True)] += 1
print('entity data (26.1 indices: 0 shared flags (1 = on fire), 8 arrow flags (1 = crit), 9 pierce, 10 in ground, 11 colour):')
for role, c in dat.items(): print('  %-6s %s' % (role, c.most_common(2)))
