"""Bonzo / Jerry boosts from kb_join.py output (/tmp/kb_events.jsonl), keyed on the `v` on you.

usage: python3 kb_bonzo2.py /tmp/kb_events.jsonl HELD_ID [HELD_ID...]
For each self-v while holding the item: the firework (burst) before it, the last armor stand (balloon)
before that (= the click), click->burst and burst->v ticks, burst position vs eye-at-click along the look,
v's horizontal size and direction error against burst->you and -look, and vy.
"""
import json, math, sys
from collections import Counter

src = sys.argv[1]; held = set(sys.argv[2:])


def lookv(yaw, pitch):
    y, p = math.radians(yaw), math.radians(pitch)
    return (-math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p))


def ang(a, b):
    na, nb = math.hypot(*a), math.hypot(*b)
    if na < 1e-9 or nb < 1e-9:
        return None
    return round(math.degrees(math.acos(max(-1, min(1, (a[0] * b[0] + a[1] * b[1]) / na / nb)))), 1)


st = Counter(); out = []
for l in open(src):
    e = json.loads(l)
    if e['held'] not in held:
        continue
    v = e['v']; st['v'] += 1
    st['vy=%s' % v[1]] += 1
    hm = round(math.hypot(v[0], v[2]), 3)
    st['h=%s' % hm] += 1
    fw = [s for s in e['spawns'] if s[1] == 'minecraft:firework_rocket']
    if not fw:
        st['no_firework'] += 1
        out.append(('NOFW', e['file'][:16], e['n'], v, hm, e['pos'], [(s[0], s[1][10:]) for s in e['spawns']], [s[1][10:] for s in e['snd']]))
        continue
    f = fw[-1]
    stands = [s for s in e['spawns'] if s[1] == 'minecraft:armor_stand' and s[0] <= f[0]]
    pos = e['pos']; bp = f[2][:3]
    away = (pos[0] - bp[0], pos[2] - bp[2])
    lk = lookv(pos[3], pos[4])
    row = {'f': e['file'][:16], 'n': e['n'], 'v': v, 'h': hm, 'fw_dt': f[0],
           'err_away': ang((v[0], v[2]), away), 'err_neglook': ang((v[0], v[2]), (-lk[0], -lk[2])),
           'dist_xz': round(math.hypot(*away), 2), 'dy': round(pos[1] - bp[1], 2), 'pitch': pos[4]}
    if stands:
        s = stands[-1]; me = s[4]
        eye = (me[0], me[1] + 1.62, me[2]); lk0 = lookv(me[3], me[4])
        rel = [bp[i] - eye[i] for i in range(3)]
        along = sum(rel[i] * lk0[i] for i in range(3))
        row.update({'click_to_fw': f[0] - s[0], 'along': round(along, 2),
                    'perp': round(math.sqrt(max(0, sum(r * r for r in rel) - along * along)), 2),
                    'stand_rel_feet': [round(s[2][i] - me[i], 2) for i in range(3)], 'fw_minus_stand': [round(bp[i] - s[2][i], 2) for i in range(3)]})
        st['click_to_fw=%d' % (f[0] - s[0])] += 1
    st['fw_to_v=%d' % (-f[0])] += 1
    out.append(row)
for r in out:
    print(r)
for k, c in sorted(st.items()):
    print(k, c)
