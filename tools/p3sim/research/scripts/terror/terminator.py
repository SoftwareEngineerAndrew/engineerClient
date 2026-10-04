"""terror-mosquito.md §8: how other bows' volleys are built, and Hydra Strike on them. For Terminator and Juju shots, every
arrow spawned on the shot tick within 1.5 blocks of the main one: owner (p3wr, or none), height off the main's spawn,
and its launch heading relative to the main's. Reads the recordings (slow-ish).    python3 terminator.py [N per bow]
"""
import collections, gzip, json, math, os, sys
from tlib import R, jsonl

N = int(sys.argv[1]) if len(sys.argv) > 1 else 6
V = jsonl('volleys.jsonl')


def head(v): return math.degrees(math.atan2(v[2], v[0]))


def show(v):
    d = os.path.join(R, v['rec']); rows = []
    for part in sorted(os.listdir(d)):
        if not part.endswith('.jsonl.gz'): continue
        with gzip.open(os.path.join(d, part), 'rb') as f:
            for line in f:
                if b'"minecraft:arrow"' in line or b'set_entity_motion' in line:
                    o = json.loads(line)
                    if o.get('k') == 'in' and 0 <= o.get('n', 0) - v['n'] <= 2: rows.append(o)
    rows.sort(key=lambda o: o['seq'])
    main = v['arrows'][0]; ids = {}
    for o in rows:
        f = o.get('f', {})
        if o.get('p') == 'minecraft:add_entity' and f.get('type') == 'minecraft:arrow' and o['n'] == v['n'] and math.dist([f['x'], f['y'], f['z']], main['spawn']) < 1.5:
            ids[f['id']] = ('p3wr' if f.get('data') != f['id'] else 'none', round(f['y'] - main['spawn'][1], 3), f.get('movement'))
    rel = {}
    for o in rows:
        f = o.get('f', {})
        if o.get('p') == 'minecraft:set_entity_motion' and f.get('id') in ids and f['movement'] not in (ids[f['id']][2], [0.0, 0.0, 0.0]):
            rel.setdefault(f['id'], f['movement'])
    mh = head(rel[main['id']]) if main['id'] in rel else None
    return sorted((own, dy, round(head(r) - mh, 2) if r and mh is not None else None) for i, (own, dy, _) in ids.items() for r in [rel.get(i)])


for bow in ('TERMINATOR', 'JUJU_SHORTBOW'):
    for ten in (False, True):
        cand = [v for v in V if v['held'] == bow and ((v['bar_before'] or {}).get('stacks') == 10) == ten and (len(v['worn']) >= 3) == ten]
        print('== %s, %s: %d volleys' % (bow, '10 stacks, Terror worn' if ten else 'no Terror stacks', len(cand)))
        pats = collections.Counter()
        for v in cand[:N]:
            arrows = show(v)
            print('   %s n %d: %s' % (v['rec'][11:19], v['n'], arrows))
