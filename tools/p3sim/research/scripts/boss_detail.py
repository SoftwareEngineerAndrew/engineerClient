"""Per-wither detail: health runs, target changes, body vs head yaw, and the '﴾ X ﴿' name stand's data and
offset from its wither. usage: boss_detail.py FILE.jsonl.gz"""
import gzip, json, sys, collections
lines = [json.loads(l) for l in gzip.open(sys.argv[1], 'rt')]
types, withers, pos, yaw, head = {}, set(), {}, {}, {}
hrun = collections.defaultdict(list)   # wither -> [(n, health)]
tg = collections.defaultdict(list)
stands = {}                             # name-stand id -> name
sdata = collections.defaultdict(list)
offs = collections.defaultdict(collections.Counter)
yawdiff = collections.defaultdict(collections.Counter)
bbs = []
for L in lines:
    if L['k'] != 'net': continue
    for e in L['d']:
        n, k = e[0], e[1]
        if k == 'a':
            types[e[2]] = e[3]; pos[e[2]] = (e[4], e[5], e[6]); yaw[e[2]] = e[10]; head[e[2]] = e[12]
            if e[3] == 'minecraft:wither': withers.add(e[2])
            if e[2] in stands or e[3] == 'minecraft:armor_stand': pass
        elif k in ('m', 'tp', 'sy'):
            id_ = e[2]
            if e[3] is not None and id_ in pos: pos[id_] = (e[3], e[4], e[5])
            if e[6] is not None: yaw[id_] = e[6]
            if id_ in stands:
                # nearest wither
                best = None
                for w in withers:
                    if w in pos:
                        p, q = pos[id_], pos[w]
                        d = sum((a - b) ** 2 for a, b in zip(p, q)) ** .5
                        if best is None or d < best[0]: best = (d, w, tuple(round(a - b, 2) for a, b in zip(p, q)))
                if best and best[0] < 8: offs[(stands[id_], best[1])][best[2]] += 1
        elif k == 'h':
            head[e[2]] = e[3]
            if e[2] in withers and e[2] in yaw:
                yawdiff[e[2]][round(((e[3] - yaw[e[2]] + 180) % 360) - 180)] += 1
        elif k == 'd':
            id_ = e[2]
            for i, v in e[3]:
                if id_ in withers:
                    if i == 9 and (not hrun[id_] or hrun[id_][-1][1] != v): hrun[id_].append((n, v))
                    if i in (16, 17, 18): tg[id_].append((n, i, v))
                if i == 2 and isinstance(v, str) and '﴾' in v: stands[id_] = v
                if id_ in stands: sdata[id_].append((n, i, v))
        elif k == 'bb' and e[2] in ('add', 'remove'):
            bbs.append((n, e[2:]))
        elif k == 'pas' and (e[2] in stands or e[2] in withers or any(p in stands for p in e[3])):
            print(n, 'PAS', e[2], types.get(e[2]), e[3])
for w in sorted(withers):
    h = hrun[w]
    print(f'\nwither {w}: {len(h)} health changes; first {h[:12]}; last {h[-6:]}')
    print('  targets', tg[w][:8], '... n changes', len(tg[w]))
    print('  head-body yaw diff (deg: count)', yawdiff[w].most_common(6))
for s, nm in stands.items():
    print(f'\nstand {s} {nm!r} data', [(n, i, v) for n, i, v in sdata[s] if i != 2][:12])
    print('   names', sorted(set(v for n, i, v in sdata[s] if i == 2)))
for k, c in offs.items(): print('offset', k, c.most_common(4))
for b in bbs: print('BB', b)
