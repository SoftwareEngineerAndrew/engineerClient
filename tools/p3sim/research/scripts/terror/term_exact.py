"""terror-mosquito.md §8: the Terminator's arrows, exactly. Main arrow as §2 (speed-up, launch point); the four no-owner
side arrows spawned on its tick: their launch velocity w = (v + g)/0.99 and start point P = sync - w (both from their
relaunch packets), against the main's pos1/E and against your clean look (steady-aim shots).
    TERROR_BOW=TERMINATOR python3 trajectories.py; python3 term_exact.py
"""
import collections, math, statistics
from tlib import jsonl, relaunch, sub, add, mul, mx, spd, look_dir, roty, wrap, q, G

T = jsonl('trajectories_TERMINATOR.jsonl')


def yawpitch(v):
    return math.degrees(math.atan2(-v[0], v[2])), -math.degrees(math.atan2(v[1], math.hypot(v[0], v[2])))


rows = []; v1err = []; ks = collections.Counter(); nside = collections.Counter(); pairs = collections.Counter()
side_pts = []; heights = collections.Counter()
for r in T:
    m = r['main']
    if not m['v0'] or spd(m['v0']) < 1e-9: continue
    rl = relaunch(m)
    if not rl or rl[2] is None: continue
    dn, v1, sync = rl; v0 = m['v0']
    s = min(range(0, 11), key=lambda s: mx(sub(add(mul(0.99 * (1 + 0.01 * s), v0), [0, -G, 0]), v1)))
    k = 1 + 0.01 * s; ks[s] += 1
    v1err.append(mx(sub(add(mul(0.99 * k, v0), [0, -G, 0]), v1)))
    pos1 = sub(sync, mul(k, v0)); u = mul(1 / 0.99, add(v0, [0, G, 0])); E = sub(pos1, u)
    srv = m.get('srv') or {}
    if srv.get('pos'):
        dy = E[1] - srv['pos'][1]
        heights[(round(dy, 2) if min(abs(dy - 1.52), abs(dy - 1.17)) < 0.003 else 'moving', (m.get('me') or {}).get('crouch'))] += 1
    rs = r.get('rots') or []
    steady = len(rs) >= 2 and all(abs(wrap(rr[0] - rs[-1][1][0])) < 1e-5 and abs(rr[1] - rs[-1][1][1]) < 1e-5 for _, rr in rs)
    sides = []
    for a in r['near']:
        if a['own'] != 'own-id' or a.get('dn') not in (0,): continue
        if abs(a['spawn'][1] - m['spawn'][1] + 0.5) > 0.02 or math.hypot(a['spawn'][0] - m['spawn'][0], a['spawn'][2] - m['spawn'][2]) > 0.02: continue
        arl = relaunch(a)
        if not arl or arl[2] is None: continue
        w = mul(1 / 0.99, add(arl[1], [0, G, 0])); P = sub(arl[2], w)
        sides.append({'w': w, 'P': P, 'dn': arl[0] - dn, 'v': arl[1], 'sync': arl[2]})
    nside[len(sides)] += 1
    # identical pairs
    uniq = []
    for sd in sides:
        if not any(mx(sub(sd['v'], o['v'])) < 1e-9 and mx(sub(sd['sync'], o['sync'])) < 1e-9 for o in uniq): uniq.append(sd)
    pairs[(len(sides), len(uniq))] += 1
    for sd in uniq:
        side_pts.append({'P_minus_pos1': sub(sd['P'], pos1), 'P_minus_E': sub(sd['P'], E), 'w': sd['w'], 'u': u, 'k': k, 'v0': v0,
                         'rot': rs[-1][1] if steady else None, 'srv': srv, 'dn': sd['dn'], 'E': E, 'pos1': pos1})

print('Terminator main arrows with relaunch + exact position:', len(v1err), ' stacks from k:', dict(sorted(ks.items())))
print('  v1 = 0.99*k*v0 - g error median %.6f p99 %.6f' % (statistics.median(v1err), q(v1err, .99)))
print('  launch height (E - sent position) by crouch:', sorted(heights.items(), key=str))
print('side arrows per shot (spawned on its tick, 0.5 below):', dict(nside), '  (all, distinct):', dict(pairs))
print('side relaunch tick minus main relaunch:', dict(collections.Counter(sp['dn'] for sp in side_pts)))
for key in ('P_minus_pos1', 'P_minus_E'):
    xs = [sp[key] for sp in side_pts]
    print('  start point %s: median %s  p5 %s p95 %s' % (key, [round(statistics.median(x[i] for x in xs), 4) for i in range(3)],
          [round(q([x[i] for x in xs], .05), 4) for i in range(3)], [round(q([x[i] for x in xs], .95), 4) for i in range(3)]))
print('  |w| median %.5f p5 %.5f p95 %.5f;  |w| / |k*v0| median %.5f;  |w| / (3k) median %.5f' % (
    statistics.median(spd(sp['w']) for sp in side_pts), q([spd(sp['w']) for sp in side_pts], .05), q([spd(sp['w']) for sp in side_pts], .95),
    statistics.median(spd(sp['w']) / spd(mul(sp['k'], sp['v0'])) for sp in side_pts), statistics.median(spd(sp['w']) / (3 * sp['k']) for sp in side_pts)))
st = [sp for sp in side_pts if sp['rot']]
print('steady-aim side arrows:', len(st))
dy = collections.Counter(); dp = []; cand = collections.defaultdict(list)
for sp in st:
    yaw, pitch = sp['rot']
    wy, wp = yawpitch(sp['w'])
    dy[round(wrap(wy - yaw), 2)] += 1; dp.append(wp - pitch)
    look = look_dir(yaw, pitch)
    for name, base in (('k*(0.99*3*look - g)', lambda d: mul(sp['k'], add(mul(0.99 * 3, d), [0, -G, 0]))),
                       ('k*0.99*3*look', lambda d: mul(sp['k'] * 0.99 * 3, d)),
                       ('k*3*look', lambda d: mul(sp['k'] * 3, d))):
        best = min((mx(sub(roty(base(look), a), sp['w'])), a) for a in (5.5, -5.5, 5.0, -5.0, 6.0, -6.0))
        cand[name].append(best)
print('  yaw of w minus your yaw:', sorted(dy.items())[:12], '...')
print('  pitch of w minus your pitch: median %.4f p5 %.4f p95 %.4f' % (statistics.median(dp), q(dp, .05), q(dp, .95)))
for name, xs in cand.items():
    print('  w vs roty(%s, a): error median %.5f p90 %.5f  angles %s' % (name, statistics.median(e for e, a in xs), q([e for e, a in xs], .9), dict(collections.Counter(a for e, a in xs))))
