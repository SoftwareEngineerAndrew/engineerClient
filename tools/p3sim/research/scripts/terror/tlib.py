"""Shared paths and helpers for the Terror armor / Mosquito Shortbow research (terror-mosquito.md).

Recordings: Dungeon Recorder (recorder-2) folders. Outputs go to $TERROR_OUT (default: out/ next to these
scripts, git-ignored). Every tick count is a server tick (`n`: the recorder's count of top-level pings).
"""
import json, math, os

R = os.path.expanduser(os.environ.get('TERROR_RECORDINGS', '~/.local/share/PrismLauncher/instances/26.1.2 BRW/minecraft/engineerclient-recordings'))
OUT = os.environ.get('TERROR_OUT', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out'))
G = 0.05   # arrow gravity a tick (drag 0.99)


def spd(v): return math.sqrt(sum(c * c for c in v))
def sub(a, b): return [a[i] - b[i] for i in range(3)]
def add(a, b): return [a[i] + b[i] for i in range(3)]
def mul(k, a): return [k * c for c in a]
def mx(a): return max(abs(c) for c in a)
def wrap(a): return (a + 180) % 360 - 180
def q(xs, f): xs = sorted(xs); return xs[min(len(xs) - 1, int(f * len(xs)))]


def look_dir(yaw, pitch):
    y, p = math.radians(yaw), math.radians(pitch)
    return [-math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p)]


def roty(v, deg):
    """v turned [deg] of yaw (+ turns the way +yaw does: from +z toward -x)."""
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    return [v[0] * c - v[2] * s, v[1], v[0] * s + v[2] * c]


def jsonl(name):
    with open(os.path.join(OUT, name)) as f:
        return [json.loads(l) for l in f]


def relaunch(x):
    """(dn, v1, sync) of an arrow: its first nonzero velocity that isn't its spawn velocity, and the exact position sent with it."""
    ups = [u for u in x['upd'] if len(u) > 3]
    for i, u in enumerate(ups):
        if u[1] == 'v' and u[2] and spd(u[2]) > 1e-9 and u[2] != x['v0']:
            sync = next((w[2] for w in ups[i:i + 3] if w[1] == 'sync'), None)
            return u[3], u[2], sync
    return None


def shot_rows():
    """Every Mosquito main arrow with a relaunch velocity and exact position (out/trajectories.jsonl), solved:
    s = Hydra stacks from the speed-up k = 1 + 0.01 s; pos1 = sync - k*v0 (exact position after tick 1);
    u = (v0 + g) / 0.99 (the launch velocity); plus the server's view of p3wr (srv) and the firing rotation."""
    rows = []
    for r in jsonl('trajectories.jsonl'):
        m = r['main']
        if not m['v0'] or spd(m['v0']) < 1e-9: continue
        rl = relaunch(m)
        if not rl or rl[2] is None: continue
        dn, v1, sync = rl
        v0 = m['v0']
        s = min(range(0, 11), key=lambda s: mx(sub(add(mul(0.99 * (1 + 0.01 * s), v0), [0, -G, 0]), v1)))
        k = 1 + 0.01 * s
        srv = m.get('srv') or {}
        trig = r['trigger'] or {}
        rot = trig.get('rot') if trig.get('kind') == 'use_item' else (srv.get('rot') or trig.get('rot'))
        rows.append({'r': r, 'k': k, 's': s, 'bar': r['stacks'], 'dn': dn, 'v0': v0, 'v1': v1, 'sync': sync,
                     'err_v1': mx(sub(add(mul(0.99 * k, v0), [0, -G, 0]), v1)),
                     'pos1': sub(sync, mul(k, v0)), 'u': mul(1 / 0.99, add(v0, [0, G, 0])),
                     'srv': srv, 'rot': rot, 'trig': trig.get('kind')})
    return rows
