"""Jerry-chine / Bonzo per-click stats from kb_proj.py output (/tmp/kb_proj.jsonl).

usage: python3 kb_jerry.py /tmp/kb_proj.jsonl HELD_ID
Per armor stand (one per click) fired while holding HELD_ID: life (spawn->removal), the first `v` on you
from the spawn to removal+6 and its tick, your movement delta (last two `me` before that v) vs the v's
horizontal part, and the click spacing (ticks between consecutive stands = fire rate).
"""
import json, math, sys
from collections import Counter

src, held = sys.argv[1], sys.argv[2]
st = Counter(); gaps = Counter(); prev = {}
hx = []
for l in open(src):
    p = json.loads(l)
    if p.get('held') != held or p['type'] != 'minecraft:armor_stand':
        continue
    life = p.get('rem', 0) - p['n']
    if life > 30:  # not a projectile (a stand that stays)
        continue
    st['stands'] += 1; st['life=%d' % life] += 1
    k = p['file']
    if k in prev and p['n'] - prev[k] < 40:
        gaps[p['n'] - prev[k]] += 1
    prev[k] = p['n']
    sv = p.get('selfv') or []
    if not sv:
        st['no_v'] += 1; continue
    dt, v, mepos = sv[0]
    st['v_dt=%d' % dt] += 1
    st['vy=%s' % v[1]] += 1
    mes = [m for m in p['mesince'] if m[0] < p['n'] + dt]
    me0 = p['me']
    d_spawn = math.dist(p['spawn'][:3], (mepos[0], mepos[1], mepos[2]))
    st['dist_bin=%d' % int(d_spawn)] += 1
    if len(mes) >= 2 and mes[-1][1] is not None and mes[-2][1] is not None:
        dx, dz = mes[-1][1] - mes[-2][1], mes[-1][3] - mes[-2][3]
        hx.append((round(v[0], 3), round(v[2], 3), round(dx, 3), round(dz, 3), round(v[0] / dx, 3) if abs(dx) > 0.02 else None,
                   round(v[2] / dz, 3) if abs(dz) > 0.02 else None, mes[-1][0] - mes[-2][0]))
print(sorted(st.items()))
print('click gaps', sorted(gaps.items()))
for h in hx[:40]:
    print('v_xz, me_dxz, ratio, me_gap', h)
