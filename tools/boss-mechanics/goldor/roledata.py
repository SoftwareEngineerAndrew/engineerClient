"""Per-recording job timelines for the static terminal roles (roles.py). Standard library only.

Everything here is on the server-tick clock, n = server ticks after "Who dares trespass".

Where the times come from:
  - completions: Hypixel's "X activated a terminal! (a/b)" lines (exact tick and who; hidden by
    chat cleaners in about half the recordings) and the status armor stands above each station. A
    terminal's stand goes "Inactive Terminal" -> "Terminal Active" and a device's "Inactive" ->
    "Active" only on a 20-tick name refresh (n = 0 mod 20), so they tell *which* station a line
    was, not when; a lever's "Not Activated" -> "Activated" comes 1-3 ticks after the pull.
  - the recorder's own GUIs (`gui` / `guiclose` lines: terminal windows, the Spirit Leap menu),
    teleports (`tp`: etherwarp, leaps) and its own position track (`p`, exact).
Other players' positions are the recorder's view of them and are not used for timing.
"""
import bisect
import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glib as G  # noqa: E402

# ---------------------------------------------------------------------------------------------
# The arena. Stand positions are the status armor stands (rounded), as in goldor.md.
# Terminal numbers: order from the section's start. S1 starts on the Simon Says platform the party
# leaps to from Storm (108.3, 120, 94); S2-S4 at their door on Goldor's track. The order is the
# distance from the start, except where the recorders' own shortest walked paths from the start (no
# teleports, p10 of >= 5 walks to each of the two) say otherwise: S1's lower two terminals (the walk
# to (90, 111, 92) goes round: 33 blocks vs 20 to (110, 112, 74)) and S3's middle two (the climb to
# (18, 122, 94) is 43 blocks walked vs 37 to (-2, 118, 94)). rolemeasure.numbering()
# re-measures it and roles.py warns if the recordings disagree with this table.
# ---------------------------------------------------------------------------------------------
TERMINALS = {
    # key: (section, stand position)
    'S1 T1': (1, (110, 118, 80)),
    'S1 T2': (1, (90, 121, 102)),
    'S1 T3': (1, (110, 112, 74)),
    'S1 T4': (1, (90, 111, 92)),
    'S2 T1': (2, (68, 108, 122)),
    'S2 T2': (2, (60, 119, 124)),
    'S2 T3': (2, (48, 108, 122)),
    'S2 T4': (2, (40, 123, 124)),
    'S2 T5': (2, (40, 107, 142)),
    'S3 T1': (3, (-2, 108, 112)),
    'S3 T2': (3, (-2, 118, 94)),
    'S3 T3': (3, (18, 122, 94)),
    'S3 T4': (3, (-2, 108, 78)),
    'S4 T1': (4, (42, 108, 30)),
    'S4 T2': (4, (44, 120, 30)),
    'S4 T3': (4, (68, 108, 30)),
    'S4 T4': (4, (72, 114, 48)),
}
LEVERS = {   # compass names (+x east, +z south) or, where they share x and z roughly, height
    'S1 lever E': (1, (106, 125, 114)),
    'S1 lever W': (1, (94, 125, 114)),
    'S2 lever low': (2, (28, 125, 128)),
    'S2 lever high': (2, (24, 133, 138)),
    'S3 lever W': (3, (2, 123, 56)),
    'S3 lever E': (3, (14, 123, 56)),
    'S4 lever low': (4, (84, 122, 34)),
    'S4 lever high': (4, (86, 129, 46)),
}
DEVICES = {
    'S1 Simon Says': (1, (110, 119, 92)),
    'S2 Lights': (2, (60, 131, 142)),
    'S3 Arrow Align': (3, (-2, 119, 74)),
    'S4 target': (4, (64, 126, 34)),
}
# where a player stands to do a device (device stands sit behind the device)
DEVICE_SPOT = {
    'S1 Simon Says': (108.3, 120.0, 94.0),
    'S2 Lights': (61.0, 134.0, 139.0),
    'S3 Arrow Align': (0.5, 120.0, 77.5),
    'S4 target': (63.5, 127.0, 35.5),
}
GATES = {  # gate k sits between section k and k+1, on Goldor's track
    'gate 1/2': (1, (100.0, 118.0, 122.5)),
    'gate 2/3': (2, (17.5, 118.0, 132.0)),
    'gate 3/4': (3, (8.0, 118.0, 49.5)),
}
DOORS = {  # a section's start: the door at its entrance (S1: the Simon Says platform)
    1: (108.3, 120.0, 94.0),
    2: (100.0, 118.0, 122.5),
    3: (17.5, 118.0, 132.0),
    4: (8.0, 118.0, 49.5),
}
CORE_ENTRANCE = (54.5, 115.0, 54.0)
REACH = 6.0          # a player within this of a stand can be the one who did it

STATUS = {'terminal': ('Inactive Terminal', 'Terminal Active'),
          'lever': ('Not Activated', 'Activated'),
          'device': ('Inactive', 'Active')}
TERMINAL_TITLES = ('Click the button on time!', 'Change all to same color!', 'Click in order!',
                   'Correct all the panes!', 'What starts with', 'Select all the')


def stations():
    """{key: (kind, section, stand position)}"""
    s = {}
    for k, (sec, p) in TERMINALS.items():
        s[k] = ('terminal', sec, p)
    for k, (sec, p) in LEVERS.items():
        s[k] = ('lever', sec, p)
    for k, (sec, p) in DEVICES.items():
        s[k] = ('device', sec, p)
    return s


STATIONS = stations()


def spot(key):
    """Where a player stands for a job (stand position for terminals and levers)."""
    if key in DEVICE_SPOT:
        return DEVICE_SPOT[key]
    if key in GATES:
        return GATES[key][1]
    if key in STATIONS:
        return tuple(float(v) for v in STATIONS[key][2])
    if key == 'core':
        return CORE_ENTRANCE
    if key.startswith('door '):
        return DOORS[int(key[5])]
    raise KeyError(key)


def terminal_kind(title):
    for i, t in enumerate(TERMINAL_TITLES):
        if title.startswith(t):
            return ('button on time', 'same colour', 'click in order', 'panes', 'starts with',
                    'select all')[i]
    return None


def station_of(pos, kinds=None, within=1.6):
    best, bd = None, within
    for k, (kind, sec, p) in STATIONS.items():
        if kinds and kind not in kinds:
            continue
        d = G.dist(pos, p)
        if d <= bd:
            best, bd = k, d
    return best


def nearest_terminal(pos, within=REACH):
    best, bd = None, within
    for k, (kind, sec, p) in STATIONS.items():
        if kind != 'terminal':
            continue
        d = G.dist(pos, p)
        if d <= bd:
            best, bd = k, d
    return best


class Track:
    """A position track [(n, x, y, z)] with lookup."""

    def __init__(self, rows):
        self.rows = rows
        self.ns = [r[0] for r in rows]

    def at(self, n):
        i = bisect.bisect_right(self.ns, n) - 1
        return self.rows[i][1:4] if i >= 0 else None

    def first_near(self, p, r, lo, hi):
        """First n in [lo, hi] at which the track is within r of p."""
        i = max(0, bisect.bisect_right(self.ns, lo) - 1)
        for row in self.rows[i:]:
            if row[0] > hi:
                break
            if G.dist(row[1:4], p) <= r:
                return max(row[0], lo)
        return None

    def last_near(self, p, r, lo, hi):
        """Last n in [lo, hi] at which the track is within r of p (the tick it leaves)."""
        out = None
        i = max(0, bisect.bisect_right(self.ns, lo) - 1)
        for j in range(i, len(self.rows)):
            row = self.rows[j]
            if row[0] > hi:
                break
            if G.dist(row[1:4], p) <= r:
                nxt = self.rows[j + 1][0] if j + 1 < len(self.rows) else row[0]
                out = min(nxt, hi)
        return out


def stand_events(x, rel):
    """{station: n} completions seen on the status stands, and {station: n} of any pre-pull flips."""
    done, flips = {}, collections.defaultdict(list)
    for eid, e in x['ents'].items():
        if e['type'] != 'minecraft:armor_stand':
            continue
        names = e['names']
        if not any(n in ('Inactive Terminal', 'Not Activated', 'Inactive', 'Terminal Active',
                         'Activated', 'Active') for _, n in names):
            continue
        pos = next(((v[2], v[3], v[4]) for v in e['ev'] if v[2] is not None), None)
        if pos is None:
            continue
        key = station_of(pos)
        if key is None:
            continue
        kind = STATIONS[key][0]
        off, on = STATUS[kind]
        # spawn / gone ticks: a completion counts only if the stand was seen "off" since it came into view
        ev = sorted([(v[0], v[1]) for v in e['ev'] if v[1] in 'sg'] + [(t, 'n:' + n) for t, n in names],
                    key=lambda a: (a[0], a[1] != 's'))
        seen_off = False
        last_on = None
        for t, what in ev:
            if what == 's' or what == 'g':
                seen_off = False
            elif what == 'n:' + off:
                if last_on is not None and kind == 'lever':
                    flips[key].append(last_on)   # flipped back: an early pull that did not count
                seen_off, last_on = True, None
            elif what in ('n:' + on, 'n:Device Active') and seen_off:
                last_on = rel(t)
                seen_off = False
        if last_on is not None:
            prev = done.get(key)
            done[key] = last_on if prev is None else min(prev, last_on)
    return done, dict(flips)


class Rec:
    """One recording's P3 on the server-tick clock."""

    def __init__(self, x, sec):
        self.id, self.group, self.self = x['id'], x['group'], x['self']
        c = G.Clock(x['st'])
        self.n0 = c.n(x['goldor'])
        rel = lambda t: c.n(t) - self.n0  # noqa: E731
        self.sec = sec
        self.core = sec['core']
        self.doors = {int(k): v for k, v in sec['door'].items()}
        self.gates = {}
        for k in ('1', '2', '3'):
            g = sec['gate'].get(k, sec['gateblk'].get(k))
            if g is not None:
                self.gates[int(k)] = g
        self.flip, self.flips = stand_events(x, rel)
        # Hypixel's completion lines: who did what
        self.lines = []
        for t, m in x['chat']:
            cm = G.COMPLETION.match(m)
            if cm and t >= x['goldor'] - 40:
                self.lines.append((rel(t), cm.group(1), cm.group(3)))
        self.chat = [(rel(t), m) for t, m in x['chat']]
        me = x['players'].get(x['self'], [])
        self.me = Track([(rel(r[0]), r[1], r[2], r[3]) for r in me])
        self.players = {name: Track([(rel(r[0]), r[1], r[2], r[3]) for r in rows])
                        for name, rows in x['players'].items()}
        self.party = x['party']
        # recorder UI: GUI sessions and teleports
        self.guis, self.tps = [], []
        cur = None
        for u in x['ui']:
            n = rel(u['t'])
            if u['k'] == 'gui':
                if cur:
                    cur['close'] = n
                    self.guis.append(cur)
                cur = {'open': n, 'title': u['title'], 'close': None, 'slotclicks': 0}
            elif u['k'] == 'guiclose' and cur:
                cur['close'] = n
                self.guis.append(cur)
                cur = None
            elif u['k'] == 'slotclick' and cur:
                cur['slotclicks'] += 1
            elif u['k'] == 'tp':
                self.tps.append((n, u['x'], u['y'], u['z']))
        if cur:
            self.guis.append(cur)
        for gs in self.guis:
            gs['pos'] = self.me.at(gs['open'])
        self.comps = self._completions()
        self.at = {c['key']: c for c in self.comps if c['key'] is not None}

    def _completions(self):
        """Exact completions [{n, kind, key, actor, src}].

        Hypixel's completion line (exact tick, who) when the recording has them; the station is the
        one of that kind whose stand flips in the next 20-tick name update (terminals, devices) or
        within 5 ticks (levers), else the one the actor stands at. Without the lines: the recorder's
        own terminal windows (the window closes in the tick of the completion: 148 of 149 checked)
        and every lever (the lever stand flips 1-3 ticks after the pull)."""
        out, used = [], set()
        for n, name, kind in self.lines:
            if kind == 'device':
                cand = [k for k, v in self.flip.items() if STATIONS[k][0] == 'device' and n <= v <= n + 21]
            elif kind == 'terminal':
                cand = [k for k, v in self.flip.items() if STATIONS[k][0] == 'terminal' and n <= v <= n + 21]
            else:
                cand = [k for k, v in self.flip.items() if STATIONS[k][0] == 'lever' and n - 1 <= v <= n + 5]
            cand = [k for k in cand if k not in used]
            key = cand[0] if len(cand) == 1 else None
            if key is None:
                tr = self.players.get(name)
                p = tr.at(n) if tr else None
                if p is not None:
                    best = [k for k in (cand or STATIONS) if STATIONS[k][0] == kind
                            and G.dist(p, spot(k)) <= REACH + 2]
                    if len(best) == 1:
                        key = best[0]
            if key is not None:
                used.add(key)
            out.append({'n': n, 'kind': kind, 'key': key, 'actor': name, 'src': 'chat'})
        if not self.lines:
            for gs in self.my_terminal_sessions():
                if gs['done'] is not None and gs['key'] not in used:
                    used.add(gs['key'])
                    out.append({'n': gs['done'], 'kind': 'terminal', 'key': gs['key'], 'actor': self.self,
                                'src': 'gui'})
            for k, v in self.flip.items():
                if STATIONS[k][0] == 'lever':
                    out.append({'n': v - 2, 'kind': 'lever', 'key': k, 'actor': None, 'src': 'stand'})
        return sorted(out, key=lambda c: c['n'])

    def door(self, k):
        """Start of section k (n): 0 for S1, the previous door otherwise."""
        return 0 if k == 1 else self.doors.get(k - 1)

    def end(self, k):
        return self.core if k == 4 else self.doors.get(k)

    def section_times(self):
        if self.core is None or any(self.doors.get(k) is None for k in (1, 2, 3)):
            return None
        e = [0, self.doors[1], self.doors[2], self.doors[3], self.core]
        return [e[i + 1] - e[i] for i in range(4)]

    def my_terminal_sessions(self):
        """The recorder's terminal windows: [{open, close, title, kind, key, done, solve}]."""
        out = []
        for gs in self.guis:
            kind = terminal_kind(gs['title'])
            if kind is None or gs['pos'] is None:
                continue
            key = nearest_terminal(gs['pos'])
            if key is None:
                continue
            mine = [m for m, nm, k in self.lines if nm == self.self and k == 'terminal'
                    and gs['close'] is not None and gs['open'] <= m <= gs['close'] + 1]
            f = self.flip.get(key)
            if self.lines:
                d = mine[0] if mine else None
            else:   # no completion lines: the window closed in the tick the stand's next update shows it done
                d = gs['close'] if (gs['close'] is not None and f is not None
                                    and gs['close'] <= f <= gs['close'] + 21) else None
            out.append(dict(gs, kind=kind, key=key, done=d, solve=(d - gs['open']) if d is not None else None))
        return out

    def leaps(self):
        """The recorder's Spirit Leaps: [(menu open n, arrival n, where)]."""
        out = []
        for gs in self.guis:
            if gs['title'] != 'Spirit Leap' or gs['close'] is None:
                continue
            tp = next((t for t in self.tps if gs['close'] - 1 <= t[0] <= gs['close'] + 3), None)
            if tp:
                out.append((gs['open'], tp[0], tp[1:4]))
        return out


def load(out_dir):
    """[Rec] for every recording with server ticks, the alpha runs left out."""
    X = G.load_extracts(out_dir, need_st=True)
    T = json.load(open(os.path.join(out_dir, 'sections.json')))
    return [Rec(x, T[rid]) for rid, x in X.items()]


def fast_groups(recs, frac=0.25):
    """The fastest `frac` of runs by P3 (start -> "The Core entrance is opening!"), one P3 per run
    (the group's recordings agree to +-1 tick): ({group: P3}, cut, number of runs with a P3)."""
    p3 = {}
    for r in recs:
        if r.core is not None and r.section_times() is not None:
            p3[r.group] = min(p3.get(r.group, 10 ** 9), r.core)
    order = sorted(p3.values())
    cut = order[max(0, int(len(order) * frac) - 1)]
    return {g: v for g, v in p3.items() if v <= cut}, cut, len(order)
