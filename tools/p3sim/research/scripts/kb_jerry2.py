"""Jerry-chine boost geometry from kb_join.py output (/tmp/kb_events.jsonl).

usage: python3 kb_jerry2.py /tmp/kb_events.jsonl
For each `v` on you (vy 0.6) while holding JERRY_STAFF: each armor stand spawned in the 12 ticks before,
the angle between v's horizontal part and (you - stand), |v_xz|, and the stand's horizontal/vertical offset.
"""
import json, math, sys
from collections import Counter
st = Counter(); fits = []
for l in open(sys.argv[1]):
    e = json.loads(l)
    if e['held'] != 'JERRY_STAFF' or e['v'][1] != 0.6:
        continue
    v = e['v']; pos = e['pos']; hm = math.hypot(v[0], v[2])
    stands = [s for s in e['spawns'] if s[1] == 'minecraft:armor_stand' and s[0] >= -12]
    st['n'] += 1
    if not stands:
        st['no_stand'] += 1; continue
    best = None
    for s in stands:
        dx, dz = pos[0] - s[2][0], pos[2] - s[2][2]
        d = math.hypot(dx, dz)
        a = None
        if hm > 0.05 and d > 0.05:
            a = math.degrees(math.acos(max(-1, min(1, (v[0] * dx + v[2] * dz) / hm / d))))
        if best is None or (a is not None and (best[1] is None or a < best[1])):
            best = (s[0], a, round(d, 2), round(pos[1] - s[2][1], 2))
    fits.append((round(hm, 3),) + best)
    st['snd:' + ','.join(sorted(set(x[1].split('.')[-1] + '@%d' % x[0] for x in e['snd'])))] += 1
fits.sort()
for f in fits:
    print('|vxz| %.3f  stand_dt %d  ang %s  dxz %s  dy %s' % f)
for k, c in st.most_common(25):
    print(k, c)
