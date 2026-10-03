"""S4 target: when the board starts (vs the section start), the 10-tick grid, and arrow -> block timing."""
import collections
import re
import devlib

TG = {(x, y) for x in (64, 66, 68) for y in (126, 128, 130)}
start_gap = collections.Counter(); grid = collections.Counter(); arrow_gap = collections.Counter()
off_gap = collections.Counter(); arrowcount = collections.Counter()
for f in devlib.files():
    meta, chat, blocks, ents = devlib.load(f)
    ends = [n for n, m in chat if re.search(r'\((\d+)/(\d+)\)$', m) and re.search(r'\((\d+)/(\d+)\)$', m).group(1) == re.search(r'\((\d+)/(\d+)\)$', m).group(2)]
    ends = sorted(set(ends))
    s3end = ends[2] if len(ends) >= 3 else None
    tb = sorted([r for r in blocks if r[3] == 50 and (r[1], r[2]) in TG and 'pane' not in r[4]])
    plate = sorted([(n, int(s.split('power=')[1].rstrip(']'))) for n, x, y, z, s in blocks if (x, y, z) == (63, 127, 35)])
    if not tb:
        continue
    lights = [r[0] for r in tb if 'emerald' in r[4]]
    if s3end:
        # plate state at s3end
        pw = 0
        for n, p in plate:
            if n <= s3end: pw = p
        if pw > 0:
            first = min((n for n in lights if n >= s3end), default=None)
            if first: start_gap[first - s3end] += 1
        before = [n for n in lights if n < s3end]
        if before: print(f.split('/')[-1], 'lights before S3 end', len(before), 'S3 end', s3end, 'first', before[0])
    # grid phase within each session: lights mod 10 relative to the first light
    for n in lights:
        grid[(n - lights[0]) % 10] += 1
    # step off: plate 0 while a target lit -> when it goes blue
    lit = None
    ev = sorted([(n, 'P', p) for n, p in plate] + [(r[0], 'T', r[4]) for r in tb])
    for n, k, v in ev:
        if k == 'T': lit = n if 'emerald' in v else None
        elif v == 0 and lit is not None:
            nxt = min((r[0] for r in tb if r[0] >= n and 'terracotta' in r[4]), default=None)
            if nxt is not None: off_gap[nxt - n] += 1
    # arrows: 'a' of minecraft:arrow, their last position packet; blue block within 3 blocks of it
    arrows = {e[2][0]: e for e in ents if e[1] == 'a' and e[2][1] == 'minecraft:arrow'}
    last = {}
    for n, kind, fl in ents:
        if kind in ('m', 'tp', 'sy') and fl[0] in arrows and fl[1] is not None:
            last.setdefault(fl[0], []).append((n, fl[1], fl[2], fl[3]))
    for r in tb:
        if 'terracotta' not in r[4]: continue
        n, x, y = r[0], r[1], r[2]
        best = None
        for aid, ps in last.items():
            for pn, px, py, pz in ps:
                if n - 10 <= pn <= n + 2 and abs(px - (x + .5)) < 1.5 and abs(py - (y + .5)) < 1.5 and 48.5 < pz < 50.6:
                    if best is None or pn < best: best = pn
        arrow_gap[None if best is None else n - best] += 1
print('S3 end -> first light (plate already held)', sorted(start_gap.items()))
print('light tick - first light, mod 10', sorted(grid.items()))
print('plate off -> lit target blue', sorted(off_gap.items()))
print('blue - first arrow position at the target (None: no arrow seen)', sorted(arrow_gap.items(), key=str))
