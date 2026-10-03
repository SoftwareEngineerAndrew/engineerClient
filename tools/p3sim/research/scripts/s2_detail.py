"""Lights device detail: per tick, lever (L) and lamp (o lit / . off) changes; the ON set at each device line;
lamps vs levers at first sight; sounds at completion lines. `python3 s2_detail.py [file-substring]`"""
import collections
import sys
import devlib

at_done = collections.Counter(); start = collections.Counter(); mism = collections.Counter(); pling = collections.Counter()
for f in devlib.files():
    meta, chat, blocks, ents = devlib.load(f)
    lb = sorted(r for r in blocks if 58 <= r[1] <= 62 and 133 <= r[2] <= 136 and r[3] in (142, 143))
    lev = {}; lamp = {}; firstseen = None
    verbose = len(sys.argv) > 1 and sys.argv[1] in f
    devs = {n for n, m in chat if 'completed a device!' in m}
    by_n = collections.defaultdict(list)
    for r in lb: by_n[r[0]].append(r)
    for n in sorted(set(by_n) | {d for d in devs if lb and lb[0][0] <= d <= lb[-1][0] + 5}):
        for _, x, y, z, s in by_n.get(n, []):
            if z == 142: lev[(x, y)] = 'powered=true' in s
            else: lamp[(x, y)] = 'lit=true' in s
        if firstseen is None and len(lev) == 20 and len(lamp) == 20:
            firstseen = n; start[sum(lamp.values())] += 1
            mism[sum(lev[k] != lamp[k] for k in lev)] += 1
        if n in devs and lev:
            at_done[(tuple(sorted(k for k, v in lev.items() if v)), tuple(sorted(k for k, v in lamp.items() if v)))] += 1
        if verbose and n in by_n:
            print(n, ' '.join(('L' if z == 142 else 'o') + f'{x},{y}{"+" if ("true" in s) else "-"}' for _, x, y, z, s in by_n[n]), '| DEVICE' if n in devs else '')
    for n, k, fl in ents:
        if k == 'snd' and 'pling' in fl[0]:
            pling[(fl[1], round(fl[5], 2), round(fl[6], 2), any(abs(n - c) <= 1 for c, m in chat if 'activated a' in m or 'completed a' in m))] += 1
print('lit lamps when all 40 first seen', sorted(start.items())); print('lever != lamp at first sight', sorted(mism.items()))
print('(levers on, lamps lit) at device lines', at_done.most_common(5))
print('pling sounds (source, vol, pitch, within 1 tick of a progress line)', dict(pling))
