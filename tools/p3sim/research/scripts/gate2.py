"""P3 gate: is 'The gate has been destroyed!' caused by a Superboom (any player's swing holding SUPERBOOM_TNT)
or by the section completing ('(n/n)' line)? Own Better PF runs.

    python3 gate2.py
"""
import collections, glob, gzip, json, re
BPF = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/betterpf/runs/*.gz'
cls = collections.Counter(); sb_dt = collections.Counter(); comp_dt = collections.Counter(); order = collections.Counter()
for f in sorted(glob.glob(BPF)):
    L = [json.loads(x) for x in gzip.open(f, 'rt')]
    held = {}; sb = []; comp = []; destroyed = []; will_open = []
    for l in L:
        k = l['k']
        if k == 'p':
            for d in l['d']: held[d[0]] = d[6] if len(d) > 6 else None
        elif k == 'sw':
            for n in l['d']:
                if held.get(n) == 'SUPERBOOM_TNT': sb.append(l['t'])
        elif k == 'chat':
            m = l['m']
            if re.search(r'\((\d)/\1\)$', m): comp.append(l['t'])
            if m == 'The gate has been destroyed!': destroyed.append(l['t'])
            if m == 'The gate will open in 5 seconds!': will_open.append(l['t'])
    for t in destroyed:
        s = [t - x for x in sb if 0 <= t - x <= 40]; c = [t - x for x in comp if 0 <= t - x <= 200]
        w = [t - x for x in will_open if 0 <= t - x <= 200]
        if s: sb_dt[min(s)] += 1
        if c: comp_dt[min(c) // 5 * 5] += 1
        cls[('superboom<=40t' if s else 'no-sb', 'section done before' if c else 'not done', 'will-open line' if w else '')] += 1
print('gate destroyed classes:', cls.most_common())
print('superboom swing -> destroyed (client ticks):', sorted(sb_dt.items()))
print('section complete -> destroyed (5-tick bins):', sorted(comp_dt.items()))
