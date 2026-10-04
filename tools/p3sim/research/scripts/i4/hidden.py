"""Hidden completions: per attempt, the timeline (ticks after Goldor's line) of lights L, visible hits H, the
completion line D, and every traced arrow into a cell that never lit (X, at the tick its block change would show:
trace tick + 1, as for arrows into lit cells), to find when a not-yet-shown target counts.
    I4_OUT=~/Cluade/maxor-work/i4/out python3 hidden.py
"""
import glob, json, os, collections
from board import attempts, trace, CELLS, OUT

win = collections.Counter(); first_hidden = collections.Counter(); rows = 0
for f in sorted(glob.glob(os.path.join(OUT, 'rec', '*.json'))):
    d = json.load(open(f))
    if 'stuck' not in (d.get('server') or ''): continue
    ev = attempts(d)
    arrows = []
    for aid, a in d['arrows'].items():
        t = trace(a)
        if t and t[2]: arrows.append((t[0] + 1, t[2], 'me' if a['own'] == 'self' else 'x'))
    p3 = None; lights = []; hits = []
    for n, seq, kind, val in ev:
        if kind == 'p3': p3 = n; lights = []; hits = []
        elif kind == 'light': lights.append((n, val))
        elif kind == 'blue' and lights and lights[-1][1] == val and (not hits or hits[-1][1] != val): hits.append((n, val))
        elif kind == 'done' and val == d.get('self') and lights and p3 is not None:
            shown = {c for _, c in lights}
            hidden = [c for c in CELLS if c not in shown]
            lo = p3; hi = n
            items = [(t - p3, 'L', c) for t, c in lights] + [(t - p3, 'H', c) for t, c in hits] + [(n - p3, 'D', None)]
            items += [(t - p3, 'X' + a[2], c) for t, c, a2 in [(a[0], a[1], a) for a in arrows] for a in [a2] if c in hidden and lo <= t <= hi + 1]
            items.sort(key=lambda x: (x[0], x[1]))
            rows += 1
            name = {c: 'abcdefghi'[i] for i, c in enumerate(CELLS)}
            print('%s hidden %s  ' % (os.path.basename(f)[11:19], ''.join(name[c] for c in hidden)) + ' '.join('%d%s%s' % (t, k, name.get(c, '')) for t, k, c in items))
            lights = []; hits = []
print('cells: a..i = (64,126) (66,126) (68,126) (64,128) (66,128) (68,128) (64,130) (66,130) (68,130); L light, H hit, D done, Xme/Xx arrow (yours / no owner) into a never-lit cell')
