"""Giants (and any other non-armor-stand, non-player entity type) seen during the boss: spawn/remove ticks,
synced data, how far they moved, nearest wither. usage: boss_giants.py FILE.jsonl.gz [type]"""
import gzip, json, sys, collections
want = sys.argv[2] if len(sys.argv) > 2 else 'minecraft:giant'
lines = [json.loads(l) for l in gzip.open(sys.argv[1], 'rt')]
start = None; G = {}; types = {}; pos = {}
for L in lines:
    if L['k'] == 'chat' and start is None and L['m'].startswith('[BOSS] Maxor'): start = L['n']
    if L['k'] != 'net': continue
    for e in L['d']:
        n, k = e[0], e[1]
        if k == 'a':
            types[e[2]] = e[3]; pos[e[2]] = (e[4], e[5], e[6])
            if e[3] == want and start and n >= start:
                G[e[2]] = dict(add=n, at=(e[4], e[5], e[6]), yaw=e[10], data={}, moves=0, last=(e[4], e[5], e[6]), rm=None, pas=None)
        elif k in ('m', 'tp', 'sy') and e[2] in G and e[3] is not None:
            G[e[2]]['moves'] += 1; G[e[2]]['last'] = (e[3], e[4], e[5])
        elif k == 'd' and e[2] in G:
            for i, v in e[3]: G[e[2]]['data'].setdefault(i, []).append((n, v)) if not G[e[2]]['data'].get(i) or G[e[2]]['data'][i][-1][1] != v else None
        elif k == 'r':
            for i in e[2]:
                if i in G: G[i]['rm'] = n
        elif k == 'pas' and (e[2] in G or any(p in G for p in e[3])):
            print(n, 'PAS', e[2], types.get(e[2]), [(p, types.get(p)) for p in e[3]])
print('boss start n', start)
for i, g in G.items():
    print(i, 'add', g['add'], 'rm', g['rm'], 'at', tuple(round(x, 2) for x in g['at']), 'yaw', g['yaw'], 'moves', g['moves'], 'last', tuple(round(x, 2) for x in g['last']))
    print('    data', {k: v[:4] for k, v in g['data'].items()})
