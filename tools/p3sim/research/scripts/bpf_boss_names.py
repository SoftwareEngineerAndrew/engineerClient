"""Coloured (§) names of boss-fight entities from Better PF recordings: spawn lines and later name changes,
plus every wither / giant spawn. usage: bpf_boss_names.py RUN.gz [RUN.gz ...]"""
import gzip, lzma, json, sys, collections, re
KEYS = ('﴾', 'Crystal', 'CLICK', 'Terminal', 'Device', 'Activated', 'Active', 'Goldor', 'Necron', 'Storm', 'Maxor',
        'Giant', 'Wither Guard', 'Wither Miner', 'Sentry', 'Plasmaflux', 'Lever')
seen = collections.Counter(); wspawn = []; kinds = collections.Counter()
for f in sys.argv[1:]:
    raw = open(f, 'rb').read()
    try: data = lzma.decompress(raw)
    except Exception: data = gzip.decompress(raw)
    ty = {}
    for line in data.decode().splitlines():
        try: L = json.loads(line)
        except Exception: continue
        k = L.get('k'); kinds[k] += 1
        if k == 'spawn':
            ty[L['id']] = L['type']
            if 'wither' == L['type'].split(':')[-1] or 'giant' in L['type']:
                wspawn.append((f.split('/')[-1][:15], L['t'], L['type'], L.get('name'), L.get('c'), L.get('x'), L.get('y'), L.get('z'), L.get('yaw')))
        nm, c = L.get('name'), L.get('c')
        if isinstance(nm, str) and any(s in nm for s in KEYS) and k != 'chat':
            seen[(k, ty.get(L.get('id')), re.sub(r'\d[\d,.]*[kM]?', '#', c or nm))] += 1
for k, v in sorted(seen.items(), key=lambda x: -x[1]): print(v, k)
print('kinds', kinds.most_common(25))
print('withers/giants spawned:', len(wspawn))
for w in wspawn[:60]: print(w)
