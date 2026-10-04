"""terror-mosquito.md §2: where the main arrow starts and how its aim is spread.
E = pos1 - u against p3wr's last sent position: height by crouch, the sideways offset in the yaw frame; then the aim
noise u/3 - look on shots where his rotation didn't change for 10 ticks (so whichever click fired it, the look is known).
    python3 aim.py   (needs out/trajectories.jsonl)
"""
import collections, math, statistics
from tlib import shot_rows, sub, mul, spd, look_dir, wrap, q

rows = shot_rows()
h = collections.Counter(); right = []; fwd = []
for x in rows:
    if not x['srv'].get('pos') or not x['rot']: continue
    off = sub(sub(x['pos1'], x['u']), x['srv']['pos'])
    crouch = (x['r']['main'].get('me') or {}).get('crouch')
    h[(round(off[1], 2) if min(abs(off[1] - 1.52), abs(off[1] - 1.17)) < 0.003 else 'moving', crouch)] += 1
    if min(abs(off[1] - 1.52), abs(off[1] - 1.17)) > 0.002: continue
    yaw = x['rot'][0]; sr = x['srv'].get('rot')
    if not sr or abs(wrap(sr[0] - yaw)) > 1e-3: continue
    c, s = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    right.append(-(off[0] * c + off[2] * s)); fwd.append(-off[0] * s + off[2] * c)
print('launch height above the sent position, by crouch:', sorted(h.items(), key=str))
print('sideways, toward your right -(cos yaw, sin yaw): median %.5f [p10 %.5f p90 %.5f]  (pi/180 = %.5f);  forward: median %.5f' % (
    statistics.median(right), q(right, .1), q(right, .9), math.pi / 180, statistics.median(fwd)))
print('|u| median %.5f p5 %.5f p95 %.5f' % (statistics.median(spd(x['u']) for x in rows), q([spd(x['u']) for x in rows], .05), q([spd(x['u']) for x in rows], .95)))

cols = [[], [], [], []]
for x in rows:
    rs = x['r'].get('rots') or []
    if len(rs) < 2: continue
    r0 = rs[-1][1]
    if any(abs(wrap(rr[0] - r0[0])) > 1e-5 or abs(rr[1] - r0[1]) > 1e-5 for _, rr in rs): continue
    n = sub(mul(1 / 3.0, x['u']), look_dir(*r0))
    for i in range(3): cols[i].append(n[i])
    cols[3].append(spd(x['u']) / 3 - 1)
print('\naim noise on %d steady shots (gaussian kurtosis 3.0, triangle 2.4; 1.8 vanilla sd 0.0075, modern triangle sd 0.00703 and |.| <= 0.01723):' % len(cols[0]))
for i, ax in enumerate(['x', 'y', 'z', '|u|/3-1']):
    xs = cols[i]; m = statistics.mean(xs)
    print('  %-8s mean %+.5f sd %.5f  min %+.5f max %+.5f  |.|>0.01723: %d  kurtosis %.2f' % (ax, m, statistics.pstdev(xs), min(xs), max(xs),
          sum(abs(c) > 0.01723 for c in xs), statistics.mean([(c - m) ** 4 for c in xs]) / statistics.pvariance(xs) ** 2))
