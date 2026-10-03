"""Every wither in every Boss Recorder file, classified by the phase it appears in: invulnerable ticks [19],
flags [0], health runs, lifetime, distance moved, distance from the recorder at spawn, and the '﴾ X ﴿' name
stand nearest at spawn. usage: boss_withers_all.py DIR > out.txt"""
import gzip, json, sys, os, collections, glob
PH = [('[BOSS] Maxor', 'P1'), ('[BOSS] Storm', 'P2'), ('[BOSS] Goldor', 'P3'), ('[BOSS] Necron', 'P4'), ('[BOSS] Wither King', 'P5')]
agg = collections.Counter()
for f in sorted(glob.glob(os.path.join(sys.argv[1], '*.jsonl.gz'))):
    try: lines = [json.loads(l) for l in gzip.open(f, 'rt')]
    except Exception as ex: print(f, 'ERR', ex); continue
    phase = 'pre'; W = {}; me = None; stands = {}; pos = {}
    for L in lines:
        if L['k'] == 'chat':
            for p, nm in PH:
                if L['m'].startswith(p) and phase < nm or (phase == 'pre' and L['m'].startswith(p)): phase = nm
        if L['k'] != 'net': continue
        for e in L['d']:
            n, k = e[0], e[1]
            if k == 'me' and e[2] is not None: me = (e[2], e[3], e[4])
            if k == 'a':
                pos[e[2]] = (e[4], e[5], e[6])
                if e[3] == 'minecraft:wither':
                    d = round(sum((a - b) ** 2 for a, b in zip(me, pos[e[2]])) ** .5) if me else None
                    w = W.get(e[2])
                    if w is None:
                        W[e[2]] = dict(phase=phase, add=n, adds=1, at=pos[e[2]], dist=d, inv=None, flags=set(), hp=[], rm=None, path=0.0, last=pos[e[2]])
                    else: w['adds'] += 1; w['last'] = pos[e[2]]
            elif k in ('m', 'tp', 'sy') and e[2] in W and e[3] is not None:
                w = W[e[2]]; p = (e[3], e[4], e[5]); w['path'] += sum((a - b) ** 2 for a, b in zip(p, w['last'])) ** .5; w['last'] = p
            elif k == 'd':
                if e[2] in W:
                    w = W[e[2]]
                    for i, v in e[3]:
                        if i == 19: w['inv'] = v if w['inv'] is None else w['inv'] if w['inv'] == v else f"{w['inv']}->{v}"
                        if i == 0: w['flags'].add(v)
                        if i == 9 and (not w['hp'] or w['hp'][-1][1] != v): w['hp'].append((n, v, phase))
                for i, v in e[3]:
                    if i == 2 and isinstance(v, str) and '﴾' in v: stands[e[2]] = v
            elif k == 'r':
                for i in e[2]:
                    if i in W: W[i]['rm'] = n
    print('##', os.path.basename(f))
    for i, w in W.items():
        hp = collections.OrderedDict()
        for n, v, ph in w['hp']: hp.setdefault(ph, []).append(v)
        hps = {ph: (sorted(set(v)) if len(v) > 6 else v) for ph, v in hp.items()}
        print(f"  {i} {w['phase']} add {w['add']} x{w['adds']} rm {w['rm']} at {tuple(round(x) for x in w['at'])} me {w['dist']} inv {w['inv']} flags {sorted(w['flags'])} path {w['path']:.0f} hp {hps}")
        agg[(w['phase'], w['inv'], tuple(sorted(w['flags'])))] += 1
print('\n## aggregate (phase, inv ticks, flags): count')
for k, v in sorted(agg.items(), key=lambda x: str(x[0])): print(' ', k, v)
