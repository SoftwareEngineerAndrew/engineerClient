"""Peek one Boss Recorder file: self id, tp ids, arrow 'a' rows and what their data refers to."""
import collections, gzip, json, sys
f = sys.argv[1]
L = [json.loads(x) for x in gzip.open(f, 'rt')]
me = L[0]['selfId']; print(L[0])
tp = collections.Counter(); arr = []; types = {}
for l in L:
    if l['k'] != 'net': continue
    for e in l['d']:
        if e[1] == 'tp': tp[e[2] == me] += 1
        if e[1] == 'a': types[e[2]] = e[3]
        if e[1] == 'a' and 'arrow' in e[3]: arr.append(e)
print('tp self/other', tp)
rel = collections.Counter()
for e in arr:
    d = e[-1] if not isinstance(e[-1], str) else e[-2]
    rel[('self' if d == me else 'own-id' if d == e[2] else 'id-1' if d == e[2] - 1 else types.get(d, types.get(d - 1, '?')))] += 1
print('arrow data refers to:', rel)
for e in arr[:3]: print(e)
print([e for l in L if l['k'] == 'net' for e in l['d'] if e[1] == 'tp'][:5])
