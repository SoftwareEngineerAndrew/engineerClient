"""S4 target device ("i4"): plate, lit targets, hits, completion, across all Boss Recorder files.

    python3 s4_target.py            # per-file timelines + summary
"""
import collections
import re
import devlib

TG = {(x, y) for x in (64, 66, 68) for y in (126, 128, 130)}
stats = collections.Counter(); gaps = collections.Counter(); first = collections.Counter(); repeats = 0
after_done = collections.Counter(); hitsper = collections.Counter(); litper = collections.Counter()
plate2first = collections.Counter(); off_reset = []; done_vs_hit = collections.Counter(); cells = collections.Counter()
for f in devlib.files():
    meta, chat, blocks, ents = devlib.load(f)
    dev = [(n, m) for n, m in chat if 'completed a device' in m]
    tb = [r for r in blocks if r[3] == 50 and (r[1], r[2]) in TG and 'pane' not in r[4]]
    plate = [r for r in blocks if (r[1], r[2], r[3]) == (63, 127, 35)]
    others = sorted({r[4].split('[')[0] for r in blocks if r[3] == 50 and 62 <= r[1] <= 70 and 124 <= r[2] <= 132})
    stats['kinds:' + ','.join(others)] += 1
    if not tb:
        continue
    stats['files with targets'] += 1
    ev = sorted([(n, 'P', s) for n, x, y, z, s in plate] + [(n, 'T', (x, y), s.split(':')[1]) for n, x, y, z, s in tb])
    lastE = max(n for n, x, y, z, s in tb if 'emerald' in s)
    done = [(n, m) for n, m in dev if lastE - 5 <= n <= lastE + 40]
    print('\n##', f.split('/')[-1], meta.get('self'), 'done:', done[:1])
    line = []
    for e in ev:
        if e[1] == 'P':
            line.append(f"{e[0]} plate={e[2].split('power=')[1].rstrip(']')}")
        else:
            line.append(f"{e[0]} {e[2][0]},{e[2][1]}{'E' if e[3] == 'emerald_block' else ('B' if e[3] == 'blue_terracotta' else e[3])}")
    print('  ' + ' | '.join(line))
    lit = None; prevlit = None; nhit = 0; nlit = 0; lastplate_on = None
    for e in ev:
        if e[1] == 'P':
            p = int(re.search(r'power=(\d+)', e[2]).group(1))
            if p > 0 and lastplate_on is None: lastplate_on = e[0]
            if p == 0: lastplate_on = None
            continue
        if e[3] == 'emerald_block':
            nlit += 1; cells[e[2]] += 1
            if lit and lit[1] == 'hit': gaps[min(e[0] - lit[0], 40)] += 1
            elif lastplate_on is not None and (lit is None or lit[1] != 'lit'): plate2first[e[0] - lastplate_on] += 1
            if prevlit == e[2]: repeats += 1
            prevlit = e[2]; lit = (e[0], 'lit')
        elif e[3] == 'blue_terracotta':
            lit = (e[0], 'hit'); nhit += 1
    hitsper[nhit] += 1; litper[nlit] += 1
    if done:
        lastB = max((n for n, x, y, z, s in tb if 'terracotta' in s), default=None)
        done_vs_hit[lastB - done[0][0] if lastB else None] += 1
        for n, x, y, z, s in tb:
            if n > done[0][0] + 1: after_done[s] += 1
print('\nstats', dict(stats))
print('blue (hit/reset) per file', sorted(hitsper.items())); print('lit per file', sorted(litper.items()))
print('consecutive same target', repeats); print('lit cell counts', sorted(cells.items()))
print('hit->next light gap', sorted(gaps.items())); print('plate on -> first light', sorted(plate2first.items()))
print('last blue - completion chat', sorted(done_vs_hit.items(), key=str)); print('target blocks set after completion', dict(after_done))
