"""Shortbow arrows from Boss Recorder files: arrows the server adds within 3 blocks of your eye 0..3 ticks
after your swing ('msw', shortbows fire on left click). Volley size, spawn offset, speed, yaw/pitch spread
vs your sent look, the owner field, gravity/drag from consecutive 'v' packets, sounds.

    python3 arrows.py
"""
import collections, glob, gzip, json, math, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'boss-mechanics'))
from netlog import NetLog  # noqa
BR = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/bossrecorder/*.jsonl.gz'
vol = collections.Counter(); spd = collections.Counter(); dyaw = collections.Counter(); dpitch = collections.Counter()
owner = collections.Counter(); dt_c = collections.Counter(); offs = collections.Counter(); snd = collections.Counter()
grav = collections.Counter(); drag = collections.Counter(); spread = collections.Counter(); gapc = collections.Counter()
for f in sorted(glob.glob(BR)):
    L = [json.loads(x) for x in gzip.open(f, 'rt')]
    if L[0].get('k') != 'meta': continue
    me_id = L[0]['selfId']; log = NetLog(L)
    me = log.kind('me'); arrows = [a for a in log.kind('a') if a[2] == 'minecraft:arrow']
    vs = collections.defaultdict(list)
    for v in log.kind('v'): vs[v[1]].append(v)
    pos = None; rot = (0, 0); mi = 0; used = set(); last_volley = None
    for sw in log.kind('msw'):
        n = sw[0]
        while mi < len(me) and me[mi][0] <= n:
            e = me[mi]; mi += 1
            if e[1] is not None: pos = e[1:4]
            if e[4] is not None: rot = (e[4], e[5])
        if not pos: continue
        eye = (pos[0], pos[1] + 1.62, pos[2])
        got = [a for a in arrows if 0 <= a[0] - n <= 3 and a[1] not in used and (a[3] - eye[0]) ** 2 + (a[4] - eye[1]) ** 2 + (a[5] - eye[2]) ** 2 < 9]
        if not got: continue
        k0 = min(a[0] for a in got); got = [a for a in got if a[0] == k0]
        for a in got: used.add(a[1])
        vol[len(got)] += 1; dt_c[k0 - n] += 1
        if last_volley is not None: gapc[min(k0 - last_volley, 20)] += 1
        last_volley = k0
        snd.update((s[1], round(s[6], 2), round(s[7], 2)) for s in log.kind('snd') if s[0] == k0 and (s[3] - eye[0]) ** 2 + (s[5] - eye[2]) ** 2 < 9)
        ys = []
        for a in got:
            owner['self' if a[-1] == me_id else 'own id' if a[-1] == a[1] else 'other'] += 1
            offs[(round(a[3] - pos[0], 1), round(a[4] - pos[1], 2), round(a[5] - pos[2], 1))] += 1
            v = vs.get(a[1]) or []
            v0 = next((x for x in v if x[0] == a[0]), None)
            if not v0: continue
            vx, vy, vz = v0[2:5]; s = math.sqrt(vx * vx + vy * vy + vz * vz)
            if s < 0.1: continue
            spd[round(s, 1)] += 1
            y = math.degrees(math.atan2(-vx, vz)); p = -math.degrees(math.asin(vy / s))
            ys.append(round(((y - rot[0]) + 180) % 360 - 180))
            dyaw[round(((y - rot[0]) + 180) % 360 - 180)] += 1; dpitch[round(p - rot[1])] += 1
            for a_, b_ in zip(v, v[1:]):
                if b_[0] - a_[0] == 1 and abs(a_[3]) > 0.05 and a_[0] - a[0] < 6:
                    grav[round(b_[3] - a_[3] * 0.99, 3)] += 1
                    if abs(a_[2]) > 0.05: drag[round(b_[2] / a_[2], 3)] += 1
        spread[tuple(sorted(ys))] += 1
print('arrows per volley:', vol.most_common()); print('swing -> arrow add (server ticks):', sorted(dt_c.items()))
print('volley spacing (ticks, 20=20+):', sorted(gapc.items()))
print('owner field:', owner); print('spawn offset from feet (x,y,z):', offs.most_common(6))
print('speed b/t:', spd.most_common(6)); print('yaw - your yaw:', sorted(dyaw.items())[:20]); print('pitch - your pitch:', dpitch.most_common(6))
print('per-volley yaw offsets:', spread.most_common(6))
print('gravity (vy1 - 0.99 vy0):', grav.most_common(4), 'h drag:', drag.most_common(4))
print('sounds at shot:', snd.most_common(6))
