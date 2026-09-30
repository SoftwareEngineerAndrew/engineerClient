"""Schedule model and search for static F7 phase-3 roles (roles.py). Standard library only.

A plan gives each of the five players a fixed script: which jobs (terminals, levers, devices, gates)
they do, in which order, in which section's time, and when they cross into a section ahead of the
one in progress. `simulate()` plays a plan against one draw of the measured random parts (terminal
solve times, Simon Says, the target device, Lights, Arrow Align) and returns the door ticks, the
core opening, everyone-in and each player's invincibility use. `search()` looks for the plan with
the lowest mean (core opening + everyone in the core) under the item budget.

Model rules (each is measured in goldor.md / terminals-strategy.md / roles.py unless marked):
- A section ends (its door opens) at the later of its last job and its gate.
- Terminals and levers of a section work only from its door; a terminal of a later section can't
  be opened. A player standing at a terminal opens it `open_door` ticks after the door, a player
  arriving later `open_walk` ticks after arriving within reach. The first completion of a section
  is never sooner than 28 (S2) / 42 (S3, S4) ticks after the door.
- Gate k is blown by a player standing at it, `gate` ticks after he gets there, never before its
  section starts (455 of 455 gates went after their section's start).
- Devices of later sections credit early: Lights (S2) and the target device (S4) during S1, Arrow
  Align (S3) during S2. The target device is done from the start (its completion is drawn from
  the fast runs' completions); Simon Says is done by the ss player (drawn likewise).
- Death ticks at n = 60, 120, ...: a player standing in a section ahead of the one in progress
  uses one invincibility per tick. The core, and the strip in front of the core door (x 45-65,
  z 50-54.5: 0 hits in 246 grid ticks there), are safe. A pre-entry is made at a planned tick
  (the free window after a death tick is 60k+1); where the door comes later than planned the
  player pays for the extra ticks.
- Moving: measured walk time between two points where the fast runs have >= 3 walks, else the
  fitted walk line; a Spirit Leap lands on a teammate who is standing still (at a job or waiting)
  `leap` ticks after the menu opens. The core (entered from S2 through the wall next to S2's
  terminal 2, the team's "core" route) leads to the strip without passing through S3.
- Goldor leaves when the last player is inside the core box; a player on the strip is counted in
  at the opening (+`core_in`), anyone else when he gets to the strip (walk or leap).
"""
import heapq
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glib as G  # noqa: E402
import roledata as RD  # noqa: E402

SAFE = 0
# where players cross into a section (median crossing of the recorders' tracks, roles.py)
ENTRY = {2: (89.8, 115.0, 132.9), 3: (8.4, 115.0, 122.1), 4: (9.0, 114.5, 50.0)}
STRIP = (54.5, 115.0, 52.0)          # in front of the core door, S4 side: safe
HOLE = (60.0, 110.0, 121.0)          # the S2 side of the core wall the core route goes through
PRE_OK = {'S2 Lights': 1, 'S3 Arrow Align': 2, 'S4 target': 1}   # device: earliest phase
ENTRY_TICKS = [None] + [60 * j + 1 for j in range(1, 15)]


def jobs_of(section):
    out = [k for k, (kind, s, _) in RD.STATIONS.items() if s == section]
    if section < 4:
        out.append('gate %d/%d' % (section, section + 1))
    return out


ALL_JOBS = [j for s in (1, 2, 3, 4) for j in jobs_of(s)]


def sec_of(key):
    if isinstance(key, tuple):      # a section entry point
        return next(k for k, v in ENTRY.items() if v == key)
    if key in RD.STATIONS:
        return RD.STATIONS[key][1]
    if key in RD.GATES:
        return RD.GATES[key][0]
    if key == 'strip':
        return SAFE
    raise KeyError(key)


def kind_of(key):
    if key in RD.STATIONS:
        return RD.STATIONS[key][0]
    if key in RD.GATES:
        return 'gate'
    return key


class Params:
    """Measured inputs (roles.py fills them from the recordings)."""

    def __init__(self, **kw):
        self.solve = [38]          # terminal solve times (window open -> completion), fast runs
        self.open_walk = 6         # arrival within reach -> window open
        self.open_door = 4         # standing at it when the door opens -> window open
        self.lever = 5             # arrival -> pull (and door -> pull when standing there)
        self.leap = 8              # leap menu open -> landing
        self.leap_door = 12        # door -> landing, for a leap made when a door opens
        self.gate = 9              # arrival at the gate -> "The gate has been destroyed!"
        self.ss = [251]            # Simon Says completion (n)
        self.target = [150]        # target device completion, standing on the plate from n <= 0
        self.lights = [60]         # entering S2 -> Lights done
        self.arrow = [10]          # at the frames -> Arrow Align done
        self.floor = {2: 28, 3: 42, 4: 42}
        self.core_in = 11          # opening -> the first players counted inside (fastest everyone-in)
        self.hole_to_strip = 52    # through the core, S2 wall -> strip (inferred, see roles.py)
        self.walk_fit = (0.0, 1.2, 0.0)
        self.pairs = {}            # (a, b) -> measured median walk
        self.spots = {}            # key -> standing position
        self.__dict__.update(kw)

    def point(self, key):
        if isinstance(key, tuple):
            return key
        if key == 'strip':
            return STRIP
        return self.spots.get(key) or RD.spot(key)

    def walk(self, a, b):
        """Walk time from a to b (keys or positions), routed through the section entry points
        (and the core for S2 -> strip / S4), with where the zone changes: (ticks, zone switch
        offset from the start or None)."""
        if a == b:
            return 0
        sa = sec_of(a) if isinstance(a, str) else None
        sb = sec_of(b) if isinstance(b, str) else None
        if sa is not None and sb is not None and sa != sb:
            if sb == SAFE and sa == 4:
                return self._w(a, b)
            if sb == SAFE or (sb == 4 and sa in (SAFE, 2)):
                # through the core (from S2) or from the strip
                t = 0.0
                if sa == 2:
                    t += self._w(a, HOLE) + self.hole_to_strip
                elif sa not in (SAFE,):
                    return 10 ** 6   # no safe way to the strip from S1 / S3 except a leap
                if sb == SAFE:
                    return t
                return t + self._w('strip', b)
            if sa == SAFE:
                return 10 ** 6
            if sb > sa:
                p = ENTRY[sb] if sb in ENTRY else None
                return self._w(a, p) + self._w(p, b)
            return self._w(a, b)
        return self._w(a, b)

    def _w(self, a, b):
        m = self.pairs.get((a, b)) if isinstance(a, str) and isinstance(b, str) else None
        if m is not None:
            return m
        p, q = self.point(a), self.point(b)
        h = ((p[0] - q[0]) ** 2 + (p[2] - q[2]) ** 2) ** 0.5
        up = max(0.0, q[1] - p[1])
        a0, b0, c0 = (tuple(self.walk_fit) + (0.0,))[:3]
        return max(1.0, a0 + b0 * h + c0 * up)


class Draw:
    """One draw of the random parts."""

    def __init__(self, P, rng=None, median=False):
        pick = (lambda v: sorted(v)[len(v) // 2]) if median else (lambda v: rng.choice(v))
        self.solve = {k: (G.median(sorted(P.solve)) if median else rng.choice(P.solve))
                      for k in RD.TERMINALS}
        self.ss = pick(P.ss)
        self.target = pick(P.target)
        self.lights = pick(P.lights)
        self.arrow = pick(P.arrow)


class Plan:
    """jobs[p] = [(phase, key)]; enter[p] = {section: planned tick or None}; names[p]."""

    def __init__(self, names, jobs, enter):
        self.names = list(names)
        self.jobs = [list(j) for j in jobs]
        self.enter = [dict(e) for e in enter]

    def copy(self):
        return Plan(self.names, self.jobs, self.enter)

    def valid(self):
        seen = [k for js in self.jobs for _, k in js if k != 'strip']
        if sorted(seen) != sorted(ALL_JOBS):
            return False
        for p, js in enumerate(self.jobs):
            if [ph for ph, _ in js] != sorted(ph for ph, _ in js):
                return False
            # everyone starts in S1 (arriving from Storm), the target player on its plate
            if js and js[0][1] != 'S4 target' and (js[0][1] == 'strip' or sec_of(js[0][1]) != 1):
                return False
            for ph, k in js:
                if k == 'strip':
                    if ph not in (2, 3):
                        return False
                    continue
                s = sec_of(k)
                if ph > s:
                    return False
                if ph < s and (k not in PRE_OK or ph < PRE_OK[k]
                               or (self.enter[p].get(s) is None and k != 'S4 target')):
                    return False
                if k in ('S4 target', 'S1 Simon Says') and js[0][1] != k:
                    return False      # done from the start by a player standing there
        return True


def simulate(plan, P, D, detail=False):
    """Play the plan once. Returns dict(doors, core, allin, items[5], done{key: n}, log)."""
    n = len(plan.jobs)
    t = [0.0] * n                  # time the player is free
    at = [None] * n                # where he stands (key or position)
    zone = [[(-10 ** 6, 1)] for _ in range(n)]
    parked = [[] for _ in range(n)]  # [start, end or None, key]
    entered = [set() for _ in range(n)]
    idx = [0] * n
    done = {}
    doors = {0: 0}
    log = [[] for _ in range(n)]
    # start: every player at his first job's spot (they leap in from Storm around n = -85)
    for p in range(n):
        first = plan.jobs[p][0][1] if plan.jobs[p] else 'S1 Simon Says'
        at[p] = first
        if sec_of(first) == 4:
            zone[p].append((-100, 4))
            entered[p].add(4)
        parked[p].append([-100, None, first])

    def set_zone(p, when, z):
        if zone[p][-1][1] != z:
            zone[p].append((when, z))

    def unpark(p, when):
        if parked[p] and parked[p][-1][1] is None:
            parked[p][-1][1] = when

    def park(p, when, key):
        unpark(p, when)
        parked[p].append([when, None, key])

    def leap_options(p, depart, allowed):
        land = depart + P.leap
        out = []
        for q in range(n):
            if q == p or not parked[q]:
                continue
            s0, s1, key = parked[q][-1]
            if s0 <= land and (s1 is None or s1 >= land + 2):
                zq = sec_of(key) if isinstance(key, str) else None
                if zq is None:
                    continue
                if zq == SAFE or zq in allowed:
                    out.append((q, key, zq))
        return out

    def travel(p, dest, depart, allowed, at_door=False):
        """Best way from at[p] to dest leaving at depart: (arrival, zone change, how). A leap
        made as a door opens costs leap_door (door -> landing), else leap (menu -> landing)."""
        a = at[p]
        best = (depart + P.walk(a, dest), None, 'walk')
        lc = P.leap_door if at_door else P.leap
        for q, key, zq in leap_options(p, depart + lc - P.leap, allowed):
            arr = depart + lc + P.walk(key, dest)
            if arr < best[0] - 0.5:
                best = (arr, (depart + lc, zq, key), 'leap to %s' % plan.names[q])
        return best

    def in_progress(when):
        k = 1
        for s in (1, 2, 3):
            if s in doors and doors[s] <= when:
                k = s + 1
        return k

    for phase in (1, 2, 3, 4):
        D0 = doors[phase - 1]
        heap = [(max(t[p], D0 if idx[p] < len(plan.jobs[p]) else t[p]), p) for p in range(n)]
        heapq.heapify(heap)
        while heap:
            now, p = heapq.heappop(heap)
            if idx[p] >= len(plan.jobs[p]):
                continue
            ph, key = plan.jobs[p][idx[p]]
            if ph > phase:
                # next job is a later phase: stage for it now if a pre-entry is planned
                s = sec_of(key) if key != 'strip' else SAFE
                if s != SAFE and s == phase + 1 and plan.enter[p].get(s) is not None and s not in entered[p]:
                    tick = plan.enter[p][s]
                    if s == 4 and isinstance(at[p], str) and sec_of(at[p]) == SAFE:
                        go = max(t[p], tick)          # step off the strip
                        set_zone(p, go, 4)
                        arr = go + P.walk('strip', key)
                    else:
                        allowed = {phase}
                        arr0, lp, how = travel(p, ENTRY[s], max(t[p], tick - P.walk(at[p], ENTRY[s])), allowed)
                        if lp:
                            set_zone(p, lp[0], lp[1])
                        go = max(arr0, tick)
                        set_zone(p, go, s)
                        at[p] = ENTRY[s]
                        arr = go + P.walk(ENTRY[s], key)
                    entered[p].add(s)
                    unpark(p, t[p])
                    at[p] = key
                    t[p] = arr
                    park(p, arr, key)
                    log[p].append((arr, 'stage', key))
                continue
            # a job of this phase: not before the section starts unless already standing there
            at_door = False
            if key != at[p] and not (key in PRE_OK and ph < sec_of(key)):
                at_door = t[p] <= D0
                t[p] = max(t[p], D0)
            idx[p] += 1
            if key == 'strip':
                depart = t[p]
                arr, lp, how = travel(p, 'strip', depart, {phase} | entered[p], False)
                if arr > 10 ** 5:
                    return None       # no way there (the strip is reached through the core from S2, or by leap)
                unpark(p, depart)
                set_zone(p, arr - 1, SAFE)
                at[p], t[p] = 'strip', arr
                park(p, arr, 'strip')
                log[p].append((arr, 'strip', ''))
                heapq.heappush(heap, (t[p], p))
                continue
            s = sec_of(key)
            kind = kind_of(key)
            pre = ph < s
            if pre and s not in entered[p]:
                # planned early entry for an early device
                tick = plan.enter[p][s]
                arr0 = t[p] + P.walk(at[p], ENTRY[s]) if sec_of(at[p]) != s else t[p]
                go = max(arr0, tick)
                set_zone(p, go, s)
                entered[p].add(s)
                unpark(p, t[p])
                at[p] = ENTRY[s] if sec_of(at[p]) != s else at[p]
                t[p] = go
            allowed = {phase} | {x for x in entered[p]}
            if key == at[p]:
                arr, how = t[p], 'there'
            else:
                depart = t[p]
                arr, lp, how = travel(p, key, depart, allowed, at_door and phase > 1)
                if arr > 10 ** 5:
                    return None
                unpark(p, depart)
                if lp:
                    set_zone(p, lp[0], lp[1])
                if s != SAFE and zone[p][-1][1] != s:
                    set_zone(p, arr - min(arr - depart, 3), s)
            at[p] = key
            park(p, arr, key)
            start = D0 if s == phase else doors.get(s - 1, 0)
            if kind == 'terminal':
                opened = max(arr + P.open_walk, start + P.open_door) if arr > start else start + P.open_door
                fin = opened + D.solve[key]
                if s >= 2:
                    fin = max(fin, start + P.floor[s])
            elif kind == 'lever':
                fin = max(arr, start) + P.lever
            elif kind == 'gate':
                fin = max(arr, start) + P.gate
            elif key == 'S1 Simon Says':
                fin = max(arr, D.ss)
            elif key == 'S4 target':
                fin = max(arr, 0) + D.target      # measured from n = 0 with the player there
            elif key == 'S2 Lights':
                fin = arr + max(5, D.lights - P.walk(ENTRY[2], 'S2 Lights'))
            else:   # Arrow Align
                fin = arr + D.arrow
            done[key] = fin
            t[p] = fin
            log[p].append((fin, key, how))
            # done ahead of the section in progress and nothing more to do there: get back out
            nxt = plan.jobs[p][idx[p]] if idx[p] < len(plan.jobs[p]) else None
            cur = zone[p][-1][1]
            if cur != SAFE and cur > phase and (nxt is None or nxt[1] == 'strip'
                                                or sec_of(nxt[1]) != cur):
                back = [(q, k2) for q, k2, zq in leap_options(p, fin, {phase}) if zq == phase]
                unpark(p, fin)
                if back:
                    q, k2 = back[0]
                    t[p] = fin + P.leap
                    at[p] = k2
                    how2 = 'leap to %s' % plan.names[q]
                else:
                    t[p] = fin + P.walk(at[p], ENTRY[cur]) if cur in ENTRY else fin + 60
                    at[p] = ENTRY[cur] if cur in ENTRY else at[p]
                    how2 = 'walk'
                set_zone(p, t[p], phase)
                park(p, t[p], at[p])
                log[p].append((t[p], 'back to S%d' % phase, how2))
            heapq.heappush(heap, (t[p], p))
        # the section's door
        if phase < 4:
            keys = jobs_of(phase)
            if any(k not in done for k in keys):
                return None
            doors[phase] = max(done[k] for k in keys)
        else:
            keys = jobs_of(4)
            if any(k not in done for k in keys):
                return None
            core = max(done[k] for k in keys)
        # players whose next job is in the next phase wait for the door: their clock moves on
        if phase < 4:
            for p in range(n):
                if idx[p] < len(plan.jobs[p]) and plan.jobs[p][idx[p]][0] == phase + 1:
                    ph, key = plan.jobs[p][idx[p]]
                    if key != 'strip' and sec_of(key) == phase + 1 and phase + 1 not in entered[p]:
                        t[p] = max(t[p], doors[phase])
    # core rush: walk to the strip; once the first player is inside, the rest may leap to him
    walk_in = []
    for p in range(n):
        if at[p] == 'strip':
            walk_in.append(core + P.core_in)
        else:
            depart = max(t[p], core)
            walk_in.append(max(depart + P.walk(at[p], 'strip'), core + P.core_in))
    first_in = min(walk_in)
    ins = []
    for p in range(n):
        depart = max(t[p], core)
        v = walk_in[p]
        if at[p] != 'strip':
            v = min(v, max(first_in, depart) + P.leap)
        ins.append(v)
        log[p].append((v, 'core', 'leap' if v < walk_in[p] else ''))
    allin = max(ins) - core
    # invincibilities: death ticks spent ahead
    items = []
    end = min(core, 3000)
    prog = [in_progress(60 * j) for j in range(int(end // 60) + 1)]
    for p in range(n):
        c, zi, zl = 0, 0, zone[p]
        for j in range(1, int(end // 60) + 1):
            tick = 60 * j
            while zi + 1 < len(zl) and zl[zi + 1][0] <= tick:
                zi += 1
            z = zl[zi][1]
            if z != SAFE and z > prog[j]:
                c += 1
        items.append(c)
    out = {'doors': doors, 'core': core, 'allin': allin, 'items': items, 'done': done}
    if detail:
        out['log'] = log
        out['zone'] = zone
    return out


def score(plan, P, draws, budget=3, planned=2):
    """Mean (core opening + everyone in) over the draws, plus penalties: 400 per item over
    `planned` for any player in the median draw, and 20000 x the share of draws in which a player
    would need more than `budget`."""
    res = [simulate(plan, P, d) for d in draws]
    if any(r is None for r in res):
        return 10 ** 9, None
    obj = sum(r['core'] + r['allin'] for r in res) / len(res)
    over = sum(1 for r in res if max(r['items']) > budget) / len(res)
    med = res[0]   # draws[0] is the median draw
    pen = 400 * sum(max(0, i - planned) for i in med["items"]) + 20000 * over
    return obj + pen, res


# ------------------------------------------------------------------------------------------
# search
# ------------------------------------------------------------------------------------------
def neighbours(plan, rng):
    """A random small change: move a job to another player, swap two jobs, reorder, change a
    phase (early device), change a planned entry tick, add/remove the strip."""
    q = plan.copy()
    n = len(q.jobs)
    r = rng.random()
    movable = [(p, i) for p in range(n) for i, (ph, k) in enumerate(q.jobs[p])
               if k not in ('S1 Simon Says', 'S4 target')]
    if r < 0.30 and movable:
        p, i = rng.choice(movable)
        ph, k = q.jobs[p].pop(i)
        p2 = rng.randrange(n)
        q.jobs[p2].append((ph, k))
    elif r < 0.55 and len(movable) > 1:
        (p, i), (p2, i2) = rng.sample(movable, 2)
        a, b = q.jobs[p][i], q.jobs[p2][i2]
        q.jobs[p][i], q.jobs[p2][i2] = (a[0], b[1]) if a[0] == sec_of(b[1]) or b[1] not in PRE_OK else b, \
            (b[0], a[1]) if b[0] == sec_of(a[1]) or a[1] not in PRE_OK else a
    elif r < 0.70:
        p = rng.randrange(n)
        if len(q.jobs[p]) > 1:
            i = rng.randrange(len(q.jobs[p]) - 1)
            q.jobs[p][i], q.jobs[p][i + 1] = q.jobs[p][i + 1], q.jobs[p][i]
    elif r < 0.78:
        for p in range(n):
            for i, (ph, k) in enumerate(q.jobs[p]):
                if k in ('S2 Lights', 'S3 Arrow Align') and rng.random() < 0.5:
                    s = sec_of(k)
                    q.jobs[p][i] = (s - 1 if ph == s else s, k)
    elif r < 0.95:
        p = rng.randrange(n)
        s = rng.choice((2, 3, 4))
        cur = q.enter[p].get(s)
        if cur is None or rng.random() < 0.3:
            q.enter[p][s] = rng.choice(ENTRY_TICKS)
        else:
            q.enter[p][s] = min(841, max(61, cur + rng.choice((-60, 60))))
    else:
        p = rng.randrange(n)
        has = [i for i, (ph, k) in enumerate(q.jobs[p]) if k == 'strip']
        if has:
            q.jobs[p].pop(has[0])
        else:
            q.jobs[p].append((rng.choice((2, 3)), 'strip'))
    for p in range(n):
        q.jobs[p].sort(key=lambda a: a[0])
        # an early device only in the phase before its section, with an entry tick
    for p in range(n):
        q.jobs[p] = [(min(ph, sec_of(k)) if k != 'strip' else ph, k) for ph, k in q.jobs[p]]
        q.jobs[p] = [(sec_of(k) if (k != 'strip' and ph < sec_of(k) and k not in PRE_OK) else ph, k)
                     for ph, k in q.jobs[p]]
        q.jobs[p].sort(key=lambda a: a[0])
        for ph, k in q.jobs[p]:
            if k != 'strip' and ph < sec_of(k) and q.enter[p].get(sec_of(k)) is None:
                q.enter[p][sec_of(k)] = rng.choice(ENTRY_TICKS[1:])
    return q


def anneal(start, P, draws, iters, rng, temp0=15.0):
    cur = start
    cs, _ = score(cur, P, draws)
    best, bs = cur, cs
    for i in range(iters):
        T = temp0 * (1 - i / iters) + 0.5
        cand = neighbours(cur, rng)
        if not cand.valid():
            continue
        s, _ = score(cand, P, draws)
        if s < cs or rng.random() < math.exp(-(s - cs) / T):
            cur, cs = cand, s
            if s < bs:
                best, bs = cand, s
    return best, bs


def make_draws(P, k, seed):
    rng = random.Random(seed)
    return [Draw(P, median=True)] + [Draw(P, rng) for _ in range(k - 1)]


def prune(plan, P, draws):
    """Drop planned entries that change nothing (the score is not worse without them)."""
    base, _ = score(plan, P, draws)
    for p in range(len(plan.jobs)):
        for sct in list(plan.enter[p]):
            if plan.enter[p][sct] is None:
                del plan.enter[p][sct]
                continue
            q = plan.copy()
            del q.enter[p][sct]
            if q.valid():
                s2, _ = score(q, P, draws)
                if s2 <= base + 0.01:
                    plan, base = q, s2
    return plan
