"""Wither boss timeline from a Boss Recorder file: phase chat lines, each wither's synced-data changes
(health flicker summarised), boss bars, and every named entity / passenger near a wither.
usage: boss_withers.py FILE.jsonl.gz [--all-names]"""
import gzip, json, sys, collections
f = sys.argv[1]
ALL = '--all-names' in sys.argv
PH = ['[BOSS] Maxor', '[BOSS] Storm', '[BOSS] Goldor', '[BOSS] Necron', '[BOSS] Wither King', 'The Core entrance', 'activated a', 'completed a', '(7/7)', '(8/8)', 'Gate', 'Crystal']
lines = [json.loads(l) for l in gzip.open(f, 'rt')]
ev = []  # (n, text)
types, names, withers, last = {}, {}, set(), {}
health = collections.defaultdict(collections.Counter)
for L in lines:
    if L['k'] == 'chat':
        if any(p in L['m'] for p in PH): ev.append((L['n'], 'CHAT ' + L['m'][:110]))
    if L['k'] != 'net': continue
    for e in L['d']:
        n, k = e[0], e[1]
        if k == 'a':
            id_, ty = e[2], e[3]
            types[id_] = ty
            if ty == 'minecraft:wither':
                withers.add(id_); ev.append((n, f'ADD wither {id_} at {e[4]:.1f},{e[5]:.1f},{e[6]:.1f} yaw {e[10]} head {e[12]}'))
        elif k == 'r':
            for id_ in e[2]:
                if id_ in withers: ev.append((n, f'REMOVE wither {id_}'))
        elif k == 'd':
            id_ = e[2]
            for i, v in e[3]:
                if id_ in withers:
                    if i == 9: health[id_][v] += 1; continue
                    if i in (16, 17, 18): continue  # targets: summarised separately
                    if last.get((id_, i)) != v:
                        ev.append((n, f'D wither {id_} [{i}]={v!r}'))
                        last[(id_, i)] = v
                elif i == 2 and v and (ALL or True):
                    if names.get(id_) != v:
                        names[id_] = v
                        if ALL or '❤' not in v or any(s in v for s in ('Maxor', 'Storm', 'Goldor', 'Necron', 'Wither King', 'Giant')):
                            ev.append((n, f'NAME {types.get(id_, "?")} {id_} = {v!r}'))
        elif k == 'pas':
            if e[2] in withers or ALL: ev.append((n, f'PAS {types.get(e[2], "?")} {e[2]} <- {[(p, types.get(p)) for p in e[3]]}'))
        elif k == 'bb':
            ev.append((n, 'BB ' + ' '.join(map(str, e[2:]))))
        elif k == 'ev' and e[2] in withers:
            ev.append((n, f'EV wither {e[2]} {e[3]}'))
# collapse repeated boss bar lines
prev = None
for n, s in sorted(ev, key=lambda x: x[0]):
    if s.startswith('BB') and s == prev: continue
    prev = s
    print(n, s)
for w in withers: print('health values', w, dict(health[w].most_common(8)))
