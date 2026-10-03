"""Bonzo / Jerry-chine stats from kb_proj.py output.

usage: python3 kb_bonzo.py /tmp/kb_proj.jsonl [HELD_ID ...]
For each projectile of the held item(s): firework (burst) tick after the click, burst position relative
to the eye at the click along/off the look vector, the next `v` on you after the burst, and how that
velocity's direction compares with (a) burst->you and (b) -look.
"""
import json, math, sys
from collections import Counter

src = sys.argv[1]
held = set(sys.argv[2:] or ['STARRED_BONZO_STAFF', 'BONZO_STAFF'])


def lookv(yaw, pitch):
    y, p = math.radians(yaw), math.radians(pitch)
    return (-math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p))


def ang(a, b):
    na, nb = math.hypot(*a), math.hypot(*b)
    if na < 1e-9 or nb < 1e-9:
        return None
    c = (a[0] * b[0] + a[1] * b[1]) / na / nb
    return math.degrees(math.acos(max(-1, min(1, c))))


rows = []
stats = Counter()
for l in open(src):
    p = json.loads(l)
    if p.get('held') not in held or p['type'] != 'minecraft:armor_stand':
        continue
    stats['stands'] += 1
    stats['life=%s' % (p.get('rem', -1) - p['n'])] += 1
    me = p['me']
    eye = (me[0], me[1] + 1.62, me[2])
    lk = lookv(me[3], me[4])
    off = [p['spawn'][i] - eye[i] for i in range(3)]
    stats['spawn_dy_feet=%.2f' % (p['spawn'][1] - me[1])] += 1
    burst = [b for b in p.get('burst', []) if b[1] == 'minecraft:firework_rocket']
    if not burst:
        stats['noburst'] += 1
        continue
    stats['burst'] += 1
    b = burst[0]
    bn = p['rem'] + b[0] - p['n']
    bp = b[2]
    rel = [bp[i] - eye[i] for i in range(3)]
    along = sum(rel[i] * lk[i] for i in range(3))
    perp = math.sqrt(max(0, sum(r * r for r in rel) - along * along))
    sv = [s for s in p.get('selfv', []) if s[0] >= bn - 1]
    stats['burst_tick=%d' % bn] += 1
    if not sv:
        stats['burst_no_v'] += 1
        rows.append((p['file'][:16], p['n'], bn, round(along, 2), round(perp, 2), None))
        continue
    dt, v, mepos = sv[0]
    stats['v_after_burst=%d' % (dt - bn)] += 1
    away = (mepos[0] - bp[0], mepos[2] - bp[2])
    lk2 = lookv(mepos[3], mepos[4])
    hmag = math.hypot(v[0], v[2])
    rows.append((p['file'][:16], p['n'], bn, round(along, 2), round(perp, 2), dt, v, round(hmag, 3),
                 'away_err', ang((v[0], v[2]), away), 'neglook_err', ang((v[0], v[2]), (-lk2[0], -lk2[2])),
                 'dist', round(math.dist((mepos[0], mepos[1], mepos[2]), bp), 2), 'pitch', mepos[4]))

for r in rows:
    print(r)
print(sorted(stats.items()))
