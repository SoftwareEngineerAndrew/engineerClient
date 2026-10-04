import os, json, glob, statistics, collections
from cycle_table import row
from losses import trips
tot = collections.Counter(); n = 0; lines = []
for p in sorted(glob.glob(os.path.join(os.environ.get('MAXOR_OUT', 'out'), '*.json'))):
    r = json.load(open(p))
    if 'events' not in r or r['server'] != 'alpha': continue
    x = row(r)
    if None in (x['kill'], x['hit1'], x['hit2'], x['storm'], x['beacon']): continue
    b, h1, h2, k, st = x['beacon'], x['hit1'], x['hit2'], x['kill'], x['storm']
    ch1 = x['charge1']
    l_c1 = max(0, min(h1 - b, (ch1 + 2 - b) if ch1 else 0))      # crystals not charged by the beacon
    l_c1 = (h1 - b) if ch1 and ch1 > b - 1 else 0
    l_h1 = (h1 - b) - l_c1                                         # charged in time, hit later (Maxor not in the beam)
    tr = [t for t in trips(r, h1) if t['cyc'] == 2 and t['placed']]
    last2 = max([t['placed'] for t in tr] or [None], key=lambda v: -1 if v is None else v)
    l_c2 = max(0, (last2 - h1) - 51) if last2 else 0
    l_c2 = min(l_c2, h2 - h1 - 80) if h2 - h1 - 80 > 0 else 0
    l_h2 = (h2 - h1 - 80) - l_c2
    l_k = k - h2 - 2
    l_s = st - k - 62
    total = st - (b + 80 + 2 + 62)
    lines.append((x['run'][11:], st, total, l_c1, l_h1, l_c2, l_h2, l_k, l_s))
    for key, v in zip(('cycle1 crystals late', 'hit1: Maxor not hit at beacon', 'cycle2 crystals late', 'hit2 late otherwise', 'kill after hit2 (-2)', 'animation'), (l_c1, l_h1, l_c2, l_h2, l_k, l_s)):
        tot[key] += v
    n += 1
print('run       storm  vs-floor  c1-late hit1-late c2-late hit2-late kill   anim')
for l in sorted(lines, key=lambda l: l[1]):
    print('%-9s %5d  %+6d   %6d %8d %8d %8d %6d %5d' % l)
print('runs', n)
for k_, v in tot.most_common(): print('  %-32s %5d ticks total, %.1f per run' % (k_, v, v / n))
