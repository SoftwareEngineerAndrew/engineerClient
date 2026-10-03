"""Distribution of burst->you distance, click->burst ticks and along-look distance for Bonzo boosts (kb_bonzo2.py output)."""
import ast, math, sys
from collections import Counter
d3 = []; al = Counter(); pit = []
for l in open(sys.argv[1]):
    if not l.startswith('{'):
        continue
    r = ast.literal_eval(l)
    if r['h'] != 1.5:
        continue
    d3.append(round(math.hypot(r['dist_xz'], r['dy']), 1))
    if 'along' in r and r['pitch'] > 45 and r['dy'] > 1:
        pit.append((r['click_to_fw'], r['along']))
d3.sort()
print('n', len(d3), 'dist3d quantiles', [d3[int(len(d3) * q)] for q in (0, .1, .5, .9, .99)], 'max', d3[-1])
c = Counter(p[0] for p in pit)
print('looking down from ground (pitch>45): click->burst', sorted(c.items()))
for k in sorted(c):
    xs = sorted(p[1] for p in pit if p[0] == k)
    print(' ', k, 'ticks: along-look median', xs[len(xs) // 2], 'range', xs[0], xs[-1])
