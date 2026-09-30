"""The measurements behind the static terminal roles (roles.py). Standard library only.

Every function takes the [roledata.Rec] list and the set of fast run groups and returns plain
dicts/lists; roles.py prints them and feeds them to the schedule model (roleplan.py).
All times are server ticks. "Recorder" = the player whose client made the recording: only its own
position track, windows and teleports are used for timing.
"""
import collections
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glib as G  # noqa: E402
import roledata as RD  # noqa: E402
import deathtick as DT  # noqa: E402

ARRIVE_R = {'terminal': 4.5, 'lever': 4.0, 'device': 4.0, 'gate': 8.0, 'door': 6.0, 'core': 4.0}


def stats(v):
    """{n, med, p25, p10, min, max} of a list (None-free)."""
    v = sorted(a for a in v if a is not None)
    if not v:
        return {'n': 0}
    return {'n': len(v), 'med': G.median(v), 'p25': G.pct(v, 25), 'p10': G.pct(v, 10),
            'p75': G.pct(v, 75), 'min': v[0], 'max': v[-1]}


def fmt(s, nd=0):
    if not s or s.get('n', 0) == 0:
        return 'n=0'
    f = lambda a: ('%.*f' % (nd, a))  # noqa: E731
    return '%s (p25 %s, p10 %s, %s-%s; n=%d)' % (f(s['med']), f(s['p25']), f(s['p10']), f(s['min']),
                                                 f(s['max']), s['n'])


def section_of_key(key):
    if key in RD.STATIONS:
        return RD.STATIONS[key][1]
    if key in RD.GATES:
        return RD.GATES[key][0]
    return None


def kind_of_key(key):
    if key in RD.STATIONS:
        return RD.STATIONS[key][0]
    if key in RD.GATES:
        return 'gate'
    if key.startswith('door'):
        return 'door'
    return key


# --------------------------------------------------------------------------------------------
# 1. order: which terminal a player coming through the door reaches first
# --------------------------------------------------------------------------------------------
def jumps(tr, lo, hi, far=6.0):
    """Teleports on a track: [(n, landing)] where it moved > far blocks between rows <= 3 ticks apart."""
    out = []
    rows = [row for row in tr.rows if lo - 3 <= row[0] <= hi]
    for a, b in zip(rows, rows[1:]):
        if b[0] - a[0] <= 3 and G.dist(a[1:4], b[1:4]) > far:
            out.append((b[0], b[1:4]))
    return out


def first_leap(r, name, lo, hi):
    """First tick in [lo, hi] a player teleported onto a teammate (a Spirit Leap), else None."""
    for n, p in jumps(r.players[name], lo, hi):
        for other, tr in r.players.items():
            q = tr.at(n - 1) if other != name else None
            if q is not None and G.dist(p, q) < 2.5:
                return n
    return None


def terminal_order(recs, fast=None):
    """Per section and terminal: time from the section's start to a player's first arrival
    (within 4.5 blocks of the stand), for players walking in from the start: in the previous
    section within 30 blocks of the door when it opened (S1: on the Simon Says platform, arriving
    from Storm, from their arrival). All five players' tracks (the recorder's own, and its view of
    the others: good enough for which-comes-first, not used for timing elsewhere); one recording
    per run. Returns ({section: {terminal: [arrival]}}, {section: {terminal: [(rank, arrival)]}})."""
    out = collections.defaultdict(lambda: collections.defaultdict(list))
    ranks = collections.defaultdict(lambda: collections.defaultdict(list))
    seen = set()
    for r in recs:
        if (fast is not None and r.group not in fast) or r.group in seen:
            continue
        seen.add(r.group)
        for name, tr in r.players.items():
            for k in (1, 2, 3, 4):
                if k == 1:
                    t0 = tr.first_near(RD.DOORS[1], 4, -400, 0)
                    end = r.doors.get(1)
                else:
                    t0 = r.doors.get(k - 1)
                    end = r.end(k)
                    p = tr.at(t0) if t0 is not None else None
                    if p is None or DT.section_at(*p) != k - 1 or G.dist(p, RD.DOORS[k]) > 30:
                        continue
                if t0 is None or end is None:
                    continue
                lp = first_leap(r, name, t0, end)
                if lp is not None:
                    end = lp      # a leap is not a path from the start: arrivals after it don't count
                arr = {}
                for key, (sec, pos) in RD.TERMINALS.items():
                    if sec != k:
                        continue
                    a = tr.first_near(pos, ARRIVE_R['terminal'], t0, end)
                    if a is not None and name != r.self:
                        # the recorder's view of a teammate is only current within render range
                        me0, me1, p0 = r.me.at(t0), r.me.at(a), tr.at(t0)
                        if None in (me0, me1, p0) or G.dist(me0, p0) > 48 or G.dist(me1, pos) > 48:
                            continue
                    if a is not None:
                        arr[key] = a - t0
                        out[k][key].append(a - t0)
                for i, key in enumerate(sorted(arr, key=arr.get)):
                    ranks[k][key].append((i + 1, arr[key]))
    return out, ranks


def walked_paths(recs):
    """Length of the path the recorder walked (no teleport) from a section's start to each terminal
    of that section, and the time it took: {terminal: ([length], [ticks])}. S1: from leaving the
    Simon Says platform; S2-S4: from the door, for a recorder standing in the previous section
    within 30 blocks of it."""
    out = collections.defaultdict(lambda: ([], []))
    for r in recs:
        tr = r.me
        for k in (1, 2, 3, 4):
            if k == 1:
                t0 = tr.first_near(RD.DOORS[1], 4, -400, 0)
                end = r.doors.get(1)
                if t0 is not None and end is not None:
                    t0 = max(t0, tr.last_near(RD.DOORS[1], 3, t0, end) or t0)
            else:
                t0, end = r.doors.get(k - 1), r.end(k)
                p = tr.at(t0) if t0 is not None else None
                if p is None or DT.section_at(*p) != k - 1 or G.dist(p, RD.DOORS[k]) > 30:
                    continue
            if t0 is None or end is None:
                continue
            length, prev, seen = 0.0, None, set()
            for row in tr.rows:
                if row[0] < t0 or row[0] > end:
                    continue
                if prev is not None:
                    d = G.dist(prev[1:4], row[1:4])
                    if d > 6:
                        break      # a teleport: the rest is not walked
                    length += d
                prev = row
                for key, (sec, pos) in RD.TERMINALS.items():
                    if sec == k and key not in seen and G.dist(row[1:4], pos) <= ARRIVE_R['terminal']:
                        seen.add(key)
                        out[key][0].append(length)
                        out[key][1].append(row[0] - t0)
    return out


def numbering(recs):
    """The terminal order per section: distance from the section's start, except where the shortest
    walked paths of both terminals (p10 of >= 5 walks each: the direct walks; the median includes
    walks that did something else first) put them the other way round. Also returns the
    all-player first-arrival times from the start (terminal_order) as a check.
    {section: [(key, distance, walked stats, arrival stats)]} in the measured order."""
    walked = walked_paths(recs)
    arrive, _ = terminal_order(recs, None)
    res = {}
    for k in (1, 2, 3, 4):
        keys = [key for key, (sec, _) in RD.TERMINALS.items() if sec == k]
        dist = {key: G.dist(RD.DOORS[k], RD.TERMINALS[key][1]) for key in keys}
        wk = {key: stats(walked[key][0]) for key in keys}

        def before(a, b):
            if wk[a]['n'] >= 5 and wk[b]['n'] >= 5:
                return wk[a]['p10'] < wk[b]['p10']
            return dist[a] < dist[b]
        order = []
        for key in sorted(keys, key=dist.get):
            i = len(order)
            while i > 0 and before(key, order[i - 1]):
                i -= 1
            order.insert(i, key)
        res[k] = [(key, dist[key], wk[key], stats(arrive[k][key])) for key in order]
    return res


# --------------------------------------------------------------------------------------------
# 2. terminals: solve time, open delay, first completion after the door
# --------------------------------------------------------------------------------------------
def terminal_times(recs, fast):
    """Recorder's terminal windows that ended in a completion: solve = window open -> completion,
    by terminal GUI kind and by terminal; open delay = arrival within 4.5 blocks -> window open."""
    solve_kind = collections.defaultdict(list)
    solve_term = collections.defaultdict(list)
    solve_all, solve_fast = [], []
    open_delay, reopen = [], []
    first_click = []
    for r in recs:
        isfast = r.group in fast
        sess = r.my_terminal_sessions()
        for i, s in enumerate(sess):
            if s['solve'] is None:
                continue
            solve_all.append(s['solve'])
            if not isfast:
                continue
            solve_fast.append(s['solve'])
            solve_kind[s['kind']].append(s['solve'])
            solve_term[s['key']].append(s['solve'])
            pos = RD.STATIONS[s['key']][2]
            # arrival: the last time the recorder came within reach before opening
            lo = s['open'] - 200
            prev = [q for q in sess[:i] if q['key'] == s['key']]
            if prev:
                reopen.append(s['open'] - (prev[-1]['close'] or s['open']))
                continue      # re-opened after a failed/closed try: not a first open
            a = None
            rows = [row for row in r.me.rows if lo <= row[0] <= s['open']]
            for row in reversed(rows):
                if G.dist(row[1:4], pos) > ARRIVE_R['terminal']:
                    break
                a = row[0]
            if a is not None and s['open'] - a < 200 and a > lo:
                open_delay.append(s['open'] - a)
    return {'solve_all': stats(solve_all), 'solve_fast': stats(solve_fast),
            'by_kind': {k: stats(v) for k, v in solve_kind.items()},
            'by_term': {k: stats(v) for k, v in solve_term.items()},
            'open_delay': stats(open_delay), 'reopen_gap': stats(reopen),
            'solve_fast_list': sorted(solve_fast)}


def first_after_door(recs, fast):
    """Door -> first completion of the new section (chat lines, one per run), and door -> the
    recorder's first terminal window when it stood at a terminal of the new section at the door."""
    first = collections.defaultdict(dict)
    staged = collections.defaultdict(list)
    for r in recs:
        if r.group not in fast:
            continue
        for k in (2, 3, 4):
            d = r.doors.get(k - 1)
            if d is None:
                continue
            c = [x for x in r.comps if x['src'] == 'chat' and x['n'] >= d and x['kind'] != 'device'
                 and (x['key'] is None or RD.STATIONS[x['key']][1] == k)]
            ter = [x['n'] - d for x in c if x['kind'] == 'terminal']
            lev = [x['n'] - d for x in c if x['kind'] == 'lever']
            if ter:
                first[k].setdefault(r.group, {})['terminal'] = min(ter)
            if lev:
                first[k].setdefault(r.group, {})['lever'] = min(lev)
            p = r.me.at(d)
            if p is not None and DT.section_at(*p) == k:
                key = RD.nearest_terminal(p, ARRIVE_R['terminal'])
                if key and RD.STATIONS[key][1] == k:
                    s = [q for q in r.my_terminal_sessions() if q['key'] == key and q['open'] >= d - 5]
                    if s:
                        staged[k].append((s[0]['open'] - d, s[0]['done'] - d if s[0]['done'] else None))
    return first, staged


# --------------------------------------------------------------------------------------------
# 3. movement between jobs
# --------------------------------------------------------------------------------------------
def job_points():
    """Every place a job is done at: {key: position}."""
    pts = {}
    for key in RD.STATIONS:
        pts[key] = RD.spot(key)
    for key, (k, p) in RD.GATES.items():
        pts[key] = p
    for k in (2, 3, 4):
        pts['door %d' % k] = RD.DOORS[k]
    pts['core'] = RD.CORE_ENTRANCE
    return pts


def recorder_visits(r, pts, lo, hi):
    """The recorder's visits to job points: [(key, arrive n, leave n)], in order. A visit is a
    stretch within the point's arrival radius (terminals 4.5, levers 4, devices 3, gates 8)."""
    rows = [row for row in r.me.rows if lo <= row[0] <= hi]
    visits = []
    cur = {}
    for j, row in enumerate(rows):
        p = row[1:4]
        nxt = rows[j + 1][0] if j + 1 < len(rows) else row[0]
        for key, q in pts.items():
            rad = ARRIVE_R[kind_of_key(key)]
            if abs(p[0] - q[0]) > rad or abs(p[2] - q[2]) > rad:
                if key in cur:
                    visits.append((key, cur.pop(key), row[0]))
                continue
            inside = G.dist(p, q) <= rad
            if inside and key not in cur:
                cur[key] = row[0]
            elif not inside and key in cur:
                visits.append((key, cur.pop(key), row[0]))
        del nxt
    for key, a in cur.items():
        visits.append((key, a, rows[-1][0] if rows else a))
    return sorted(visits, key=lambda v: v[1])


def idle(tr, lo, hi, w=10, eps=0.6):
    """Longest stretch in [lo, hi] (ticks) during which the track moved less than eps per w ticks."""
    rows = [row for row in tr.rows if lo <= row[0] <= hi]
    best, start = 0, None
    for i, row in enumerate(rows):
        j = i
        while j + 1 < len(rows) and rows[j + 1][0] - row[0] <= w:
            j += 1
        still = G.dist(row[1:4], rows[j][1:4]) < eps and rows[j][0] > row[0]
        if still:
            start = row[0] if start is None else start
            best = max(best, rows[j][0] - start)
        else:
            start = None
    return best


def travel(recs, fast):
    """Recorder moves between job points in fast runs: the recorder's own job done at A (else the
    tick it left A's radius) -> arriving within reach of B, with no other job point in between. Split by how: walk (no
    teleport), etherwarp (a teleport not from the leap menu) or leap (Spirit Leap menu).
    Returns {(A, B): {'walk': [...], 'ether': [...], 'leap': [...]}} and distances."""
    pts = job_points()
    moves = collections.defaultdict(lambda: collections.defaultdict(list))
    for r in recs:
        if r.group not in fast or r.core is None:
            continue
        leaps = r.leaps()
        vis = recorder_visits(r, pts, -50, r.core + 60)
        # keep visits that last >= 3 ticks or are a job done there (short fly-bys are passing through)
        did = {c['key']: c['n'] for c in r.comps if c['actor'] == r.self and c['key']}
        vis = [v for v in vis if v[2] - v[1] >= 3 or (v[0] in did and v[1] <= did[v[0]] <= v[2] + 2)]
        for (a, a0, a1), (b, b0, b1) in zip(vis, vis[1:]):
            if a == b or b0 <= a1:
                continue
            # from the job done at A (if the recorder did one there), else from leaving A
            if a in did and a0 <= did[a] <= a1 + 2:
                a1 = min(a1, did[a])
            dt = b0 - a1
            if dt > 300:
                continue
            tps = [t for t in r.tps if a1 - 1 <= t[0] <= b0]
            lp = [lp for lp in leaps if a1 - 2 <= lp[1] <= b0 + 1]
            how = 'leap' if lp else ('ether' if tps else 'walk')
            if how == 'walk' and idle(r.me, a1, b0) > 10:
                continue        # stood somewhere in between (waiting for a door, a gate...): not travel
            moves[(a, b)][how].append(dt)
    return moves, pts


def _solve3(A, y, w):
    """Weighted least squares for 3 unknowns (normal equations, Gaussian elimination)."""
    n = len(A[0])
    M = [[sum(wi * ai[r] * ai[c] for ai, wi in zip(A, w)) for c in range(n)] for r in range(n)]
    v = [sum(wi * ai[r] * yi for ai, yi, wi in zip(A, y, w)) for r in range(n)]
    for i in range(n):
        piv = max(range(i, n), key=lambda r: abs(M[r][i]))
        M[i], M[piv], v[i], v[piv] = M[piv], M[i], v[piv], v[i]
        for r in range(i + 1, n):
            f = M[r][i] / M[i][i]
            for c in range(i, n):
                M[r][c] -= f * M[i][c]
            v[r] -= f * v[i]
    x = [0.0] * n
    for i in reversed(range(n)):
        x[i] = (v[i] - sum(M[i][c] * x[c] for c in range(i + 1, n))) / M[i][i]
    return x


def travel_model(moves, pts):
    """Weighted least-squares fit of the pair medians (weights = walks, pairs with >= 2 walks):
    ticks = a + b x horizontal distance + c x blocks climbed (straight line between the points)."""
    A, ys, ws = [], [], []
    for (a, b), m in moves.items():
        v = m.get('walk')
        if not v or len(v) < 2:
            continue
        p, q = pts[a], pts[b]
        h = math.hypot(p[0] - q[0], p[2] - q[2])
        up = max(0.0, q[1] - p[1])
        A.append((1.0, h, up))
        ys.append(G.median(sorted(v)))
        ws.append(len(v))
    x = _solve3(A, ys, ws)
    res = [y - sum(c * f for c, f in zip(x, row)) for row, y in zip(A, ys)]
    return {'walk': {'a': x[0], 'b': x[1], 'c': x[2], 'pairs': len(A), 'moves': sum(ws),
                     'rmse': math.sqrt(sum(e * e for e in res) / len(res))}}


# --------------------------------------------------------------------------------------------
# 4. Spirit Leap
# --------------------------------------------------------------------------------------------
def leap_times(recs, fast):
    """Menu open -> arrival, and menu open -> arrival measured from the previous tick the recorder
    stood still (the whole cost of a leap in the flow of a job)."""
    menu, where = [], collections.Counter()
    for r in recs:
        if r.group not in fast:
            continue
        for o, a, p in r.leaps():
            if 0 <= o and (r.core is None or o <= r.core + 40):
                menu.append(a - o)
                s = DT.section_at(*p)
                from_s = DT.section_at(*(r.me.at(o) or p))
                where[(from_s, s)] += 1
    return {'menu': stats(menu), 'where': where}


# --------------------------------------------------------------------------------------------
# 5. levers, devices, gates
# --------------------------------------------------------------------------------------------
def lever_times(recs, fast):
    """Recorder's lever pulls (its own completion line or the lever flipping while it stood there):
    arrival within 4 blocks -> the pull; and for a player standing at the lever when its section's
    door opened: door -> pull."""
    arrive, at_door = [], []
    for r in recs:
        if r.group not in fast:
            continue
        for c in r.comps:
            if c['kind'] != 'lever' or c['key'] is None:
                continue
            k = RD.STATIONS[c['key']][1]
            pos = RD.spot(c['key'])
            p = r.me.at(c['n'])
            if p is None or G.dist(p, pos) > ARRIVE_R['lever'] + 1:
                continue
            if c['actor'] not in (r.self, None):
                continue
            d = r.door(k)
            a = None
            for row in reversed([row for row in r.me.rows if c['n'] - 200 <= row[0] <= c['n']]):
                if G.dist(row[1:4], pos) > ARRIVE_R['lever'] + 1:
                    break
                a = row[0]
            if d is not None and a is not None and a <= d:
                at_door.append(c['n'] - d)
            elif a is not None:
                arrive.append(c['n'] - a)
    return {'arrive': stats(arrive), 'at_door': stats(at_door)}


def device_times(recs, fast):
    """Devices: completion tick (from the chat "completed a device" line whose station is known),
    the recorder's time on the device (arrival within 3 blocks of where it is done -> completion)
    when the recorder did it, and when players started (arrival)."""
    done = collections.defaultdict(dict)
    onspot = collections.defaultdict(list)
    for r in recs:
        if r.group not in fast:
            continue
        for c in r.comps:
            if c['kind'] != 'device':
                continue
            key = c['key']
            if key is None:
                # the station of an unattributed device line: the one its actor stands at
                continue
            done[key].setdefault(r.group, c['n'])
            if c['actor'] == r.self:
                pos = RD.DEVICE_SPOT[key]
                a = None
                for row in reversed([row for row in r.me.rows if c['n'] - 600 <= row[0] <= c['n']]):
                    if G.dist(row[1:4], pos) > 5:
                        break
                    a = row[0]
                if a is not None:
                    onspot[key].append((a, c['n']))
    return done, onspot


def ss_done(recs, fast):
    """S1's Simon Says: its completion (the S1 door when SS was last) per fast run."""
    out = {}
    for r in recs:
        if r.group not in fast:
            continue
        c = [x for x in r.comps if x['key'] == 'S1 Simon Says']
        if c:
            out[r.group] = c[0]['n']
    return out


def gate_times(recs, fast):
    """"The gate has been destroyed!" relative to the recorder reaching the gate (within 8 blocks)
    when the recorder was the one standing nearest when it went; and the gate tick relative to its
    section's start in fast runs."""
    blow, rel = [], collections.defaultdict(dict)
    items = collections.Counter()
    for r in recs:
        if r.group not in fast:
            continue
        for k, g in r.gates.items():
            st = r.door(k)
            if st is not None:
                rel[k][r.group] = g - st
            pos = RD.GATES['gate %d/%d' % (k, k + 1)][1]
            p = r.me.at(g)
            if p is None or G.dist(p, pos) > 10:
                continue
            others = [tr.at(g) for nm, tr in r.players.items() if nm != r.self]
            if any(q is not None and G.dist(q, pos) < G.dist(p, pos) for q in others):
                continue
            a = None
            for row in reversed([row for row in r.me.rows if g - 300 <= row[0] <= g]):
                if G.dist(row[1:4], pos) > 10:
                    break
                a = row[0]
            if a is not None and a > g - 300:
                blow.append(g - a)
    return {'blow': stats(blow), 'rel': rel}


def core_rush(recs, fast):
    """"The Core entrance is opening!" -> the recorder inside the core box, and -> the last
    party member inside (recorder's view of the others)."""
    me, last = [], {}
    for r in recs:
        if r.group not in fast or r.core is None:
            continue
        a = None
        for row in r.me.rows:
            if row[0] >= r.core - 40 and DT.section_at(*row[1:4]) == 'core':
                a = row[0]
                break
        if a is not None:
            me.append(max(0, a - r.core))
        ins = []
        for nm, tr in r.players.items():
            b = None
            for row in tr.rows:
                if row[0] >= r.core - 40 and DT.section_at(*row[1:4]) == 'core':
                    b = row[0]
                    break
            ins.append(b)
        if len(ins) == 5 and all(b is not None for b in ins):
            last[r.group] = min(last.get(r.group, 999), max(0, max(ins) - r.core))
    return {'me': stats(me), 'last': stats(list(last.values()))}


def section_times(recs, fast):
    """Per fast run: [S1, S2, S3, S4] and P3."""
    out = {}
    for r in recs:
        if r.group in fast and r.group not in out:
            st = r.section_times()
            if st:
                out[r.group] = st
    return out


def s1_devices(recs, fast):
    """Devices done while S1 is in progress, one recording (with completion lines) per run:
    Simon Says, Lights (S2) and the target device (S4: the S1-time device line that is neither of
    the other two - its stand is usually out of view), plus Lights' entering S2 -> done for its
    doer (recorder's view of him) and target completions of recorders standing on the plate."""
    ss, lights, target, lights_dur = [], [], [], []
    seen = set()
    for r in recs:
        if r.group not in fast or r.group in seen or not r.lines or r.doors.get(1) is None:
            continue
        seen.add(r.group)
        d1 = r.doors[1]
        dev = [c for c in r.comps if c['kind'] == 'device' and 30 <= c['n'] <= d1]
        got = set()
        for c in dev:
            if c['key'] == 'S1 Simon Says' and 'ss' not in got:
                ss.append(c['n'])
                got.add('ss')
            elif c['key'] == 'S2 Lights' and 'l' not in got:
                lights.append(c['n'])
                got.add('l')
                tr = r.players.get(c['actor'])
                if tr:
                    ent = next((row[0] for row in tr.rows if row[0] > -300
                                and DT.section_at(*row[1:4]) == 2), None)
                    if ent is not None and ent < c['n']:
                        lights_dur.append(c['n'] - ent)
        for c in dev:
            if c['key'] in (None, 'S4 target') and c['n'] < d1 - 3 and 't' not in got \
                    and c['actor'] not in [x['actor'] for x in dev if x['key'] in ('S1 Simon Says', 'S2 Lights')]:
                target.append(c['n'])
                got.add('t')
    return {'ss': ss, 'lights': lights, 'lights_dur': lights_dur, 'target': target}


def device_onspot(recs, key, fast=None, far=5.0):
    """The recorder's own completions of a device: arrival within `far` of where it is done ->
    completion (it stood there the whole time)."""
    out = []
    pos = RD.DEVICE_SPOT[key]
    for r in recs:
        if fast is not None and r.group not in fast:
            continue
        for c in r.comps:
            if c['key'] != key or c['actor'] != r.self:
                continue
            a = None
            for row in reversed([row for row in r.me.rows if c['n'] - 600 <= row[0] <= c['n']]):
                if G.dist(row[1:4], pos) > far:
                    break
                a = row[0]
            if a is not None and a > c['n'] - 600:
                out.append((a, c['n']))
    return out


def spots(recs):
    """Where players stand for each job: terminals at window open, levers at the pull, gates when
    they went (the recorder nearest), devices: roledata.DEVICE_SPOT. Median positions."""
    sp = collections.defaultdict(list)
    for r in recs:
        for gs in r.my_terminal_sessions():
            if gs['pos'] is not None:
                sp[gs['key']].append(gs['pos'])
        for c in r.comps:
            if c['actor'] == r.self and c['key'] and c['kind'] == 'lever':
                p = r.me.at(c['n'])
                if p and G.dist(p, RD.spot(c['key'])) < 6:
                    sp[c['key']].append(p)
        for k, g in r.gates.items():
            key = 'gate %d/%d' % (k, k + 1)
            p = r.me.at(g)
            if p and G.dist(p, RD.GATES[key][1]) < 8:
                sp[key].append(p)
    out = {}
    for k, v in sp.items():
        out[k] = (tuple(round(G.median(sorted(p[i] for p in v)), 1) for i in range(3)), len(v))
    return out


def door_leaps(recs, fast):
    """Leaps landing within 60 ticks after a section door: door -> landing, per section entered
    (the leap menu is often opened before the door)."""
    out = collections.defaultdict(list)
    for r in recs:
        if r.group not in fast:
            continue
        for o, a, p in r.leaps():
            for k in (1, 2, 3):
                d = r.doors.get(k)
                if d is not None and d <= a <= d + 60:
                    out[k + 1].append(a - d)
    return out
