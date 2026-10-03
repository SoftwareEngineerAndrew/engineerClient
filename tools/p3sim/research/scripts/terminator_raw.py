"""Raw Terminator volleys: every arrow spawned within 4 blocks of the recording player on a tick
they hold a TERMINATOR, with its spawn line and first tracked positions.

    python3 terminator_raw.py DATA_DIR [VOLLEYS]
"""
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'boss-mechanics', 'goldor'))
import glib as G  # noqa: E402

data_dir = sys.argv[1]
want = int(sys.argv[2]) if len(sys.argv) > 2 else 6
shown = 0
for rid in G.wanted_ids(data_dir):
    path = G.run_path(data_dir, rid)
    if not os.path.exists(path):
        continue
    lines = G.read_lines(path)
    me = next((l['self'] for l in lines if l.get('k') == 'meta'), None)
    pose, spawns, moves = {}, {}, {}
    for l in lines:
        k = l.get('k')
        if k == 'p':
            for row in l['d']:
                if row[0] == me:
                    pose[l['t']] = row
        elif k == 'spawn' and l.get('type') == 'minecraft:arrow':
            spawns.setdefault(l['t'], []).append(l)
        elif k == 'e':
            for row in l['d']:
                moves.setdefault(row[0], [])
                if len(moves[row[0]]) < 3:
                    moves[row[0]].append((l['t'], row[1:]))
    last = None
    for t in sorted(spawns):
        p = None
        for tt in range(t, t - 40, -1):
            if tt in pose:
                p = pose[tt]
                break
        if p is None or len(p) < 7 or p[6] != 'TERMINATOR':
            continue
        near = [s for s in spawns[t] if math.dist((s['x'], s['y'], s['z']), (p[1], p[2] + 1.5, p[3])) < 4]
        if len(near) < 2:
            continue
        print(rid, 't', t, 'player', [round(v, 2) if isinstance(v, float) else v for v in p[1:7]])
        for s in near:
            print('   id', s['id'], 'at', round(s['x'], 2), round(s['y'], 2), round(s['z'], 2), 'yaw', s.get('yaw'), s.get('pitch'), 'moves', moves.get(s['id']))
        shown += 1
        if shown >= want:
            sys.exit()
