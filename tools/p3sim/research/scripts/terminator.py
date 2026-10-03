"""Terminator volleys in Better PF runs: arrows spawned at the recording player while they hold a
TERMINATOR. Per volley: how many arrows, each arrow's heading (from its first tracked moves)
against the player's yaw/pitch, and the ticks between volleys.

    python3 terminator.py DATA_DIR [MAX_RUNS]
"""
import collections
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'boss-mechanics', 'goldor'))
import glib as G  # noqa: E402

data_dir = sys.argv[1]
cap = int(sys.argv[2]) if len(sys.argv) > 2 else 400
counts = collections.Counter()
dyaw_by_rank = collections.defaultdict(list)
dpitch = collections.defaultdict(list)
gaps = collections.Counter()
shapes = collections.defaultdict(list)
speeds = []
done = 0
for rid in G.wanted_ids(data_dir):
    path = G.run_path(data_dir, rid)
    if not os.path.exists(path):
        continue
    try:
        lines = G.read_lines(path)
    except Exception:
        continue
    me = next((l['self'] for l in lines if l.get('k') == 'meta'), None)
    if not me:
        continue
    pose = {}      # t -> (x, y, z, yaw, pitch, item)
    arrows = {}    # id -> [t, x, y, z, later positions]
    for l in lines:
        k = l.get('k')
        if k == 'p':
            for row in l['d']:
                if row[0] == me:
                    pose[l['t']] = (row[1], row[2], row[3], row[4], row[5], row[6] if len(row) > 6 else '')
        elif k == 'spawn' and l.get('type') == 'minecraft:arrow':
            arrows[l['id']] = [l['t'], l['x'], l['y'], l['z'], []]
        elif k == 'e':
            for row in l['d']:
                a = arrows.get(row[0])
                if a is not None and len(a[4]) < 3:
                    a[4].append((l['t'], row[1], row[2], row[3]))
    if not arrows:
        continue
    ts = sorted(pose)

    def at(t):
        best = None
        for tt in ts:
            if tt > t:
                break
            best = pose[tt]
        return best

    volleys = collections.defaultdict(list)
    for aid, (t, x, y, z, later) in arrows.items():
        p = at(t)
        if p is None or p[5] != 'TERMINATOR':
            continue
        if math.dist((x, y, z), (p[0], p[1] + 1.5, p[2])) > 2.5:
            continue
        if len(later) < 2:
            continue
        (t0, x, y, z), (t1, x1, y1, z1) = later[0], later[1]
        dt = max(1, t1 - t0)
        dx, dy, dz = (x1 - x) / dt, (y1 - y) / dt, (z1 - z) / dt
        h = math.hypot(dx, dz)
        if h < 0.5:
            continue
        speeds.append(math.sqrt(dx * dx + dy * dy + dz * dz))
        yaw = math.degrees(math.atan2(-dx, dz))
        pitch = -math.degrees(math.atan2(dy, h))
        d = (yaw - p[3] + 540) % 360 - 180
        if abs(d) < 25 and abs(pitch - p[4]) < 10:
            volleys[t].append((d, pitch - p[4], p[4]))
    prev = None
    for t in sorted(volleys):
        v = sorted(volleys[t])
        counts[len(v)] += 1
        if len(v) in (3, 4):
            shapes[len(v)].append([(round(a[0], 1), round(a[1], 1)) for a in v])
        if len(v) == 3:
            for i, (d, dp, pitch) in enumerate(v):
                dyaw_by_rank[i].append(d)
                dpitch[i].append(dp)
        if prev is not None and t - prev <= 20:
            gaps[t - prev] += 1
        prev = t
    done += 1
    if done >= cap:
        break


def q(xs):
    xs = sorted(xs)
    return [round(xs[int(f * (len(xs) - 1))], 2) for f in (0.1, 0.25, 0.5, 0.75, 0.9)] if xs else []


print('runs', done, 'arrows per volley', dict(counts))
for i in range(3):
    print('rank', i, 'dyaw q', q(dyaw_by_rank[i]), 'dpitch q', q(dpitch[i]), 'n', len(dyaw_by_rank[i]))
print('gaps (ticks, <=20)', sorted(gaps.items()))
print('speed q', q(speeds))
for k in (3, 4):
    print(k, 'arrow volleys, (dyaw, dpitch) sorted:', shapes[k][:20])
