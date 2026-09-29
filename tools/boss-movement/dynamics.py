"""Movement models for Maxor and Storm, and a multi-step prediction fitter (standard library).

A model steps the boss one server tick at a time toward a target point. The fitter starts a
simulation at an observed (recovered) server position, runs it forward h ticks against the
real target trajectory (the players' recorded positions, or a fixed point), and compares with the
positions the server actually sent. Errors are reported per horizon, so a model that only gets
the next tick right (constant velocity does) is told apart from one that predicts.

Every model aims at target + (0, dy, 0); dy is a parameter like the others.
"""
import math


def dist(a, b):
    return math.sqrt((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2)


def toward(pos, target):
    dx, dy, dz = target[0] - pos[0], target[1] - pos[1], target[2] - pos[2]
    d = math.sqrt(dx * dx + dy * dy + dz * dz)
    if d < 1e-9:
        return (0.0, 0.0, 0.0), 0.0
    return (dx / d, dy / d, dz / d), d


class Model:
    """params dict; step(pos, vel, target) -> (pos, vel)."""
    name = 'model'

    def __init__(self, **p):
        self.p = p

    def speed(self, d):
        p = self.p
        s = p.get('c', 0.0) + p.get('k', 1e9) * max(0.0, d - p.get('stop', 0.0))
        return min(p['vmax'], s, d)   # never step past the target

    def __repr__(self):
        return '%s(%s)' % (self.name, ', '.join('%s=%g' % kv for kv in sorted(self.p.items())))


class ConstVel(Model):
    """Baseline: keeps its last velocity (no target)."""
    name = 'constant-velocity'

    def step(self, pos, vel, target):
        return (pos[0] + vel[0], pos[1] + vel[1], pos[2] + vel[2]), vel


class Pursuit(Model):
    """Straight at the target point, speed = min(vmax, c + k * max(0, d - stop), d). k = 1e9
    gives a constant speed with a stop radius."""
    name = 'pursuit'

    def step(self, pos, vel, target):
        u, d = toward(pos, target)
        s = self.speed(d) if d > self.p.get('stop', 0.0) else 0.0
        v = (u[0] * s, u[1] * s, u[2] * s)
        return (pos[0] + v[0], pos[1] + v[1], pos[2] + v[2]), v


class Inertia(Model):
    """Vanilla-style steering: the velocity moves a fraction `a` of the way each tick toward the
    pursuit velocity, then the position adds it."""
    name = 'inertia'

    def step(self, pos, vel, target):
        u, d = toward(pos, target)
        s = self.speed(d) if d > self.p.get('stop', 0.0) else 0.0
        a = self.p['a']
        v = (vel[0] + (u[0] * s - vel[0]) * a, vel[1] + (u[1] * s - vel[1]) * a, vel[2] + (u[2] * s - vel[2]) * a)
        return (pos[0] + v[0], pos[1] + v[1], pos[2] + v[2]), v


class VanillaWither(Model):
    """Minecraft 1.8.9 EntityWither.onLivingUpdate + EntityLivingBase.moveEntityWithHeading, with
    the constants as parameters (vanilla: pull 0.5, lerp 0.6, friction 0.91, hover 5 while not
    armoured / 0 when armoured, gravity 0.08, stop 3 blocks horizontally):
        my *= 0.6
        if y < target.y + hover: my = max(my, 0); my += (pull - my) * lerp
        if horizontal distance > stop: m_xz += (unit_xz * pull - m_xz) * lerp
        pos += m
        my = (my - gravity) * 0.98;  m_xz *= friction
    `vel` carries the motion after friction (what the next tick starts from)."""
    name = 'vanilla-wither'

    def step(self, pos, vel, target):
        p = self.p
        mx, my, mz = vel
        my *= 0.6
        if pos[1] < target[1] + p.get('hover', 5.0):
            if my < 0:
                my = 0.0
            my += (p.get('pull', 0.5) - my) * p.get('lerp', 0.6)
        dx, dz = target[0] - pos[0], target[2] - pos[2]
        dh = math.sqrt(dx * dx + dz * dz)
        if dh > p.get('stop', 3.0):
            mx += (dx / dh * p.get('pull', 0.5) - mx) * p.get('lerp', 0.6)
            mz += (dz / dh * p.get('pull', 0.5) - mz) * p.get('lerp', 0.6)
        pos = (pos[0] + mx, pos[1] + my, pos[2] + mz)
        f = p.get('friction', 0.91)
        return pos, (mx * f, (my - p.get('gravity', 0.08)) * 0.98, mz * f)


def simulate(model, pos, vel, targets, h):
    """Positions after 1..h ticks; targets[i] is the target point during tick i (None: hold)."""
    dy = model.p.get('dy', 0.0)
    out = []
    for i in range(h):
        t = targets[i] if i < len(targets) else (targets[-1] if targets else None)
        if t is None:
            out.append(pos)
            continue
        pos, vel = model.step(pos, vel, (t[0], t[1] + dy, t[2]))
        out.append(pos)
    return out


def prediction_error(model, cases, horizons=(4, 8, 12, 16)):
    """cases: [(pos0, vel0, n0, targets, [(n, pos), ...])]. Returns ({h: rms error}, count)."""
    hmax = max(horizons)
    acc = {h: [0.0, 0] for h in horizons}
    for pos0, vel0, n0, targets, obs in cases:
        sim = simulate(model, pos0, vel0, targets, hmax)
        for n, p in obs:
            k = n - n0
            if k < 1 or k > hmax:
                continue
            e = dist(sim[k - 1], p)
            for h in horizons:
                if abs(k - h) <= 1:
                    acc[h][0] += e * e
                    acc[h][1] += 1
    return {h: (math.sqrt(s / c) if c else None) for h, (s, c) in acc.items()}, sum(c for _, c in acc.values())


def score(errs):
    v = [e for e in errs.values() if e is not None]
    return sum(v) / len(v) if v else float('inf')


def grid_search(cls, grid, cases):
    """Exhaustive search over the product of the grid's values; [(score, model, errs)], best first."""
    keys = sorted(grid)
    res = []

    def rec(i, cur):
        if i == len(keys):
            m = cls(**dict(cur))
            errs, _ = prediction_error(m, cases)
            res.append((score(errs), m, errs))
            return
        for v in grid[keys[i]]:
            cur[keys[i]] = v
            rec(i + 1, cur)

    rec(0, {})
    res.sort(key=lambda r: r[0])
    return res


def refine(cls, best_params, cases, steps, rounds=3):
    """Coordinate descent around a grid optimum: each parameter in `steps` is nudged by +-step
    (halving each round) while the score improves."""
    p = dict(best_params)
    cur = score(prediction_error(cls(**p), cases)[0])
    for _ in range(rounds):
        for k, st in steps.items():
            improved = True
            while improved:
                improved = False
                for sgn in (1, -1):
                    q = dict(p)
                    q[k] = p[k] + sgn * st
                    if k in ('vmax', 'a', 'k') and q[k] <= 0:
                        continue
                    s = score(prediction_error(cls(**q), cases)[0])
                    if s < cur - 1e-6:
                        p, cur, improved = q, s, True
                        break
        steps = {k: v / 2 for k, v in steps.items()}
    m = cls(**p)
    return cur, m, prediction_error(m, cases)[0]
