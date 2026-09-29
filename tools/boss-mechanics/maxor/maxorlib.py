"""Shared helpers for the Maxor (F7 P1) mechanics scripts. Standard library only.

Inputs
  MAXOR_OUT/extract/<rec>.json    this directory's extract.py (crystals, blocks, chat, players)
  MOVE_OUT/tracks/<group>.json    ../../boss-movement/tracks.py (Maxor's de-lerped move packets,
                                  every recording of a run on one server-tick timeline)

Times: `n` is the reference recording's server tick count (Odin's per-tick ping, `st` lines),
exactly as in the boss-movement tracks; `rel` = n - (n of "WELL! WELL! WELL!").
A run is "server-timed" when every recording of it has `st` lines.
"""
import bisect
import json
import math
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '..', 'boss-movement'))
import bosslib as B  # noqa: E402
import recording as R  # noqa: E402

START = R.MAXOR_START
INTRO2 = "[BOSS] Maxor: I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU."
INTRO_END = R.MAXOR_INTRO_END
STUN = R.MAXOR_STUN
BEAM, TRICKED = STUN[1], STUN[0]
ENRAGED = R.MAXOR_ENRAGED
DEAD = R.MAXOR_DEAD
STORM = R.STORM_START
ACTIVE = ('1/2 Energy Crystals are now active!', '2/2 Energy Crystals are now active!',
          '3/2 Energy Crystals are now active!')
CHARGING = 'The Energy Laser is charging up!'
PICKED = ' picked up an Energy Crystal!'
# Maxor's ability announcements. A: the first ability, 161 ticks after the laser phase starts or
# after he breaks free; B: the wither-skull ones that come later.
TAUNT_A = ("[BOSS] Maxor: YOUR WEAPONS CAN'T PIERCE THROUGH MY SHIELD!",
           "[BOSS] Maxor: YOUR MOBILITY TRICKS DON'T WORK IN MY DOMAIN!",
           "[BOSS] Maxor: I HOPE YOU LIKE EXPLOSIONS TOO!",
           "[BOSS] Maxor: MY MINIONS WILL HAVE TO WIPE THE FLOOR AFTER I'M DONE WITH YOU ALL!")
TAUNT_B = ("[BOSS] Maxor: How about you taste some rapid fire Wither Skulls!",
           "[BOSS] Maxor: Eat Wither Skulls, scum!",
           "[BOSS] Maxor: Time for me to blast you away for good!")

TOP = {'W': (64.5, 238.375, 50.5), 'E': (82.5, 238.375, 50.5)}       # where crystals spawn
PYLON = {'W': (52.5, 224.375, 41.5), 'E': (94.5, 224.375, 41.5)}     # where placed crystals sit
BEACON = (73, 221, 73)       # the laser: a beacon at (73, 221, 73) shining up through (73, 222-226, 73)
MAXOR_SPAWN = (73.0, 226.0, 53.0)


def median(v):
    v = [x for x in v if x is not None]
    return statistics.median(v) if v else None


def pct(v, p):
    v = [x for x in v if x is not None]
    return B.pct(v, p)


def stats(v, nd=1):
    v = [x for x in v if x is not None]
    if not v:
        return 'n=0'
    f = '%.' + str(nd) + 'f'
    return ('n=%d median ' + f + ' p10 ' + f + ' p90 ' + f + ' min ' + f + ' max ' + f) % (
        len(v), statistics.median(v), pct(v, 10), pct(v, 90), min(v), max(v))


def hist(v, lo=None, hi=None):
    c = {}
    for x in v:
        if x is None:
            continue
        if lo is not None and x < lo:
            x = '<%s' % lo
        elif hi is not None and x > hi:
            x = '>%s' % hi
        c[x] = c.get(x, 0) + 1
    return ', '.join('%s:%d' % (k, c[k]) for k in sorted(c, key=lambda k: (isinstance(k, str), k)))


def wrap(a):
    return (a + 180) % 360 - 180


def bearing(dx, dz):
    return math.degrees(math.atan2(-dx, dz))


class Run:
    """One run (all its recordings) on the reference recording's server-tick timeline."""

    def __init__(self, tr, X):
        self.tr = tr
        self.group = tr['group']
        self.recs = [r for r in tr['recs'] if r in X]
        self.X = {r: X[r] for r in self.recs}
        self.server = all(len(X[r]['st']) > 10 for r in self.recs) and bool(self.recs)
        self.clock = {r: B.Clock(X[r]['st']) for r in self.recs}
        self.dn = {r: tr['offsets'][r]['dn'] for r in self.recs}
        self.party = tr['party']
        self.self = tr['self']
        # chat lines: [(n, m, rec, t)], every recording (siblings deduplicated by occurrence)
        self.chat = []
        seen = {}
        for r in self.recs:
            occ = {}
            for t, m in X[r]['chat']:
                k = occ.get(m, 0)
                occ[m] = k + 1
                n = self.N(r, t)
                if (m, k) in seen:
                    continue
                seen[(m, k)] = n
                self.chat.append((n, m, r, t))
        self.chat.sort(key=lambda c: c[0])
        self.s0 = self.first(START)
        mx = tr['bosses'].get('maxor', {})
        self.maxor_id = str(mx.get('id')) if mx else None
        self.pkt_raw = mx.get('pkt', [])
        self.obs = mx.get('obs', [])
        self.spans = mx.get('spans', [])
        self.players = tr['players']
        self._pn = {k: [q[0] for q in v] for k, v in self.players.items()}
        self.ghosts = B.ghost_spans(tr)
        self._pk = None
        self._pkn = None

    def N(self, rec, t):
        return int(round(self.clock[rec].n(t) + self.dn[rec]))

    def ms_t(self, rec, t):
        """Wall-clock time (ms, the recorder's clock) of client tick t of recording rec, from
        its `time` lines (one every 20 client ticks), 50 ms per client tick in between."""
        tl = self.X[rec].get('time') or []
        if not tl:
            return None
        i = max(0, bisect.bisect_right([a for a, _ in tl], t) - 1)
        if i + 1 < len(tl):
            (a, ma), (b, mb) = tl[i], tl[i + 1]
            return ma + (t - a) * (mb - ma) / (b - a) if b > a else ma
        a, ma = tl[i]
        return ma + (t - a) * 50

    def ms(self, n, rec=None):
        """Wall-clock time at which server tick n was processed, as seen by a recording: the
        last client tick before tick n + 1 arrived (during a server stall, tick n's own ping
        arrives before the stall and the tick's work after it)."""
        rec = rec or self.ref()
        c = self.clock[rec]
        t = c.t_of_n(int(round(n + 1 - self.dn[rec])))
        if t is None:
            return None
        return self.ms_t(rec, t - 1)

    def chat_ms(self, n, msgs):
        """Wall-clock time of the chat line (one of msgs) recorded at server tick n."""
        msgs = (msgs,) if isinstance(msgs, str) else msgs
        for nn, m, rec, t in self.chat:
            if nn == n and m in msgs:
                return self.ms_t(rec, t)
        return None

    def lines(self, msgs):
        msgs = (msgs,) if isinstance(msgs, str) else msgs
        return [c[0] for c in self.chat if c[1] in msgs]

    def first(self, msgs, after=None):
        v = [n for n in self.lines(msgs) if after is None or n >= after]
        return v[0] if v else None

    def rel(self, n):
        return None if n is None or self.s0 is None else n - self.s0

    @property
    def size(self):
        return len(self.party)

    # ---------------------------------------------------------------- Maxor's movement
    def pkt(self):
        """De-lerped move packets [(n, x, y, z, kind, rec)], one recording's view where they
        overlap (the recording with most packets), siblings fill its gaps."""
        if self._pk is None:
            by = {}
            for p in self.pkt_raw:
                by.setdefault(p[4], []).append(p)
            order = sorted(by, key=lambda r: -len(by[r]))
            out = []
            for i, r in enumerate(order):
                cov = [(a, b) for a, b, rr in self.spans if rr in order[:i]]
                for p in by[r]:
                    if i and any(a <= p[0] <= b for a, b in cov):
                        continue
                    out.append((p[0], p[1], p[2], p[3], p[6], r))
            out.sort()
            self._pk = out
        return self._pk

    def maxor_at(self, n, max_gap=8):
        """Maxor's position at server tick n: the last move packet at or before n, when it is at
        most max_gap ticks old, or when the recording that sent it had him in view from that
        packet through n (no packets while he stands still)."""
        pk = self.pkt()
        if self._pkn is None:
            self._pkn = [p[0] for p in pk]
        i = bisect.bisect_right(self._pkn, n) - 1
        if i < 0:
            return None
        p = pk[i]
        if n - p[0] <= max_gap:
            return p[1:4]
        if any(rr == p[5] and a <= p[0] and n <= b for a, b, rr in self.spans):
            return p[1:4]
        return None

    def in_view(self, n):
        return any(a <= n <= b for a, b, _ in self.spans)

    # ---------------------------------------------------------------- players
    def alive(self, name, n):
        return B.alive(self.ghosts, name, n)

    def player(self, name, n):
        rows = self.players.get(name) or []
        i = bisect.bisect_right(self._pn.get(name, []), n) - 1
        return rows[i] if i >= 0 else None

    def own(self, name):
        """True when the player's positions come from their own recording (exact)."""
        return name in self.self.values()

    # ---------------------------------------------------------------- per-recording things
    def ents(self, rec, typ=None):
        for k, e in self.X[rec]['ents'].items():
            if typ is None or e['type'] == typ:
                yield k, e

    def blocks(self, rec):
        return [(self.N(rec, b[0]), b[1], b[2], b[3], b[4]) for b in self.X[rec]['blocks']]

    def ref(self):
        """The recording with the most arena block lines (all recordings see the same blocks)."""
        return max(self.recs, key=lambda r: len(self.X[r]['blocks']))


    # ---------------------------------------------------------------- the phase's events
    def summary(self):
        """Key events (server ticks n) up to Storm's first line: stuns, enrages, charging lines,
        placements ('X/2 ... active'), pickups, the death line, Maxor's wither despawning."""
        if getattr(self, '_sum', None) is not None:
            return self._sum
        d = {}
        storm = self.first(STORM)
        end = storm if storm else 10 ** 9
        d['storm'] = storm
        d['stuns'] = [n for n in self.lines(STUN) if n < end]
        d['enr'] = [n for n in self.lines(ENRAGED) if n < end]
        d['dead'] = self.first(DEAD)
        d['acts'] = [n for n in self.lines(ACTIVE) if n < end]
        d['chg'] = [n for n in self.lines(CHARGING) if n < end]
        d['picks'] = [(c[0], c[1][:-len(PICKED)]) for c in self.chat if c[1].endswith(PICKED) and c[0] < end]
        d['taunts'] = [(c[0], 'A' if c[1] in TAUNT_A else 'B') for c in self.chat
                       if c[1] in TAUNT_A + TAUNT_B and c[0] < end]
        gone = None
        for rec in self.recs:
            e = self.X[rec]['ents'].get(self.maxor_id)
            if e:
                for ev in e['ev']:
                    if ev[1] == 'g':
                        n = self.N(rec, ev[0])
                        if storm and storm - 60 < n <= storm + 5:
                            gone = n if gone is None else min(gone, n)
        d['gone'] = gone
        col = self.column()
        d['P'] = next((n for n, y, nm in col if y == 221 and nm == 'air' and self.rel(n) > 0), None)
        d['beacon'] = next((n for n, y, nm in col if y == 221 and nm == 'beacon'), None)
        d['bedrock'] = next((n for n, y, nm in col if y == 221 and nm == 'bedrock' and self.rel(n) > 0), None)
        d['black'] = [n for n, y, nm in col if y == 223 and nm == 'black_stained_glass']
        d['yellow'] = [n for n, y, nm in col if y == 223 and nm == 'yellow_stained_glass']
        d['red'] = [n for n, y, nm in col if y == 223 and nm == 'red_stained_glass']
        self._sum = d
        return d

    def column(self):
        """Block changes at the laser column (73, 219-227, 73): [(n, y, block name)]."""
        rec = self.ref()
        return [(n, y, st.split('[')[0].split(':')[-1]) for n, x, y, z, st in self.blocks(rec)
                if x == 73 and z == 73 and 219 <= y <= 227]

    def grid(self):
        """Residue (mod 10) of this run's 10-tick checks: the modal residue of the laser column's
        glass changes (all made on the checks)."""
        if getattr(self, '_grid', None) is None:
            ns = [n for n, y, nm in self.column() if 222 <= y <= 224 and 'glass' in nm and 0 < self.rel(n) < 1500]
            if len(ns) < 3:
                self._grid = -1
            else:
                c = {}
                for n in ns:
                    c[n % 10] = c.get(n % 10, 0) + 1
                self._grid = max(c, key=c.get)
        return None if self._grid == -1 else self._grid

    def gdev(self, n):
        """n relative to the nearest 10-tick check (-5..+4)."""
        g = self.grid()
        return None if g is None else ((n - g + 5) % 10) - 5

    def crystals(self):
        """End crystals seen by the reference recording: [(kind, side, n_spawn, n_gone)],
        kind 'top' (the spawn platforms, y 238.4) or 'pylon' (placed, y 224.4)."""
        rec = self.ref()
        out = []
        for k, e in self.X[rec]['ents'].items():
            if e['type'] != 'minecraft:end_crystal':
                continue
            sp = [ev for ev in e['ev'] if ev[1] == 's']
            gn = [ev for ev in e['ev'] if ev[1] == 'g']
            if not sp:
                continue
            y = sp[0][3]
            kind = 'top' if y > 235 else ('pylon' if 223 < y < 226 else None)
            if kind:
                out.append((kind, 'W' if sp[0][2] < 73 else 'E', self.N(rec, sp[0][0]),
                            self.N(rec, gn[0][0]) if gn else None))
        return sorted(out, key=lambda c: c[2])

    def hits(self):
        """Laser hits: [(hit n, stun line n)]. The hit is taken from its discharge signature
        (the column turns black 70 ticks after a hit) when there is one: a stun line announced
        at the end of one of his abilities comes later than the hit itself. A signature belongs
        to the first stun line at or after it."""
        s = self.summary()
        sig = [x - 70 for x in s['black']]
        out = []
        for i, L in enumerate(s['stuns']):
            prevL = s['stuns'][i - 1] if i else -10 ** 9
            cand = [h for h in sig if prevL + 2 < h <= L + 2 and L - h <= 150]
            out.append((cand[-1] if cand else L, L))
        return out

    def pos_interp(self, n, maxgap=4):
        """Maxor at server tick n, interpolated between the move packets around it (or the last
        packet while he stands still in view)."""
        pk = self.pkt()
        if self._pkn is None:
            self._pkn = [p[0] for p in pk]
        i = bisect.bisect_right(self._pkn, n) - 1
        if i < 0:
            return None
        a = pk[i]
        if a[0] == n:
            return a[1:4]
        if i + 1 < len(pk):
            b = pk[i + 1]
            if b[0] - a[0] <= maxgap:
                f = (n - a[0]) / (b[0] - a[0])
                return tuple(a[k] + (b[k] - a[k]) * f for k in (1, 2, 3))
        if any(rr == a[5] and s0 <= a[0] and n <= s1 for s0, s1, rr in self.spans):
            if i + 1 >= len(pk) or math.dist(pk[i + 1][1:4], a[1:4]) < 0.3:
                return a[1:4]
        return None

    def free_spans(self):
        """[(a, b)] server ticks when Maxor can move: his first move .. a stun line, an enrage
        line .. the next stun line, ..., up to his despawn."""
        s = self.summary()
        i3 = self.first(INTRO_END)
        if i3 is None:
            return []
        a = i3 + 44
        end = s['gone'] or s['storm'] or self.s0 + 3000
        spans = []
        for st in s['stuns']:
            if st > a:
                spans.append((a, st))
            e = [x for x in s['enr'] if x > st]
            a = e[0] if e else end
        if a < end:
            spans.append((a, end))
        return spans

    def closest(self, n, pos):
        """[(3D distance, name, row)] of living players, closest first."""
        out = []
        for name in self.party:
            q = self.player(name, n)
            if q is None or not self.alive(name, n):
                continue
            out.append((math.dist(q[1:4], pos), name, q))
        return sorted(out)


def load(maxor_out, move_out, server_only=False):
    X = {}
    d = os.path.join(maxor_out, 'extract')
    for f in sorted(os.listdir(d)):
        if f.endswith('.json'):
            x = json.load(open(os.path.join(d, f)))
            if 'skip' not in x:
                X[x['id']] = x
    runs = []
    td = os.path.join(move_out, 'tracks')
    for f in sorted(os.listdir(td)):
        tr = json.load(open(os.path.join(td, f)))
        if not any(r in X for r in tr['recs']):
            continue
        r = Run(tr, X)
        if r.s0 is None or (server_only and not r.server):
            continue
        runs.append(r)
    return runs


def args():
    if len(sys.argv) < 3:
        sys.exit('usage: %s MAXOR_OUT MOVE_OUT [section ...]' % os.path.basename(sys.argv[0]))
    return sys.argv[1], sys.argv[2], sys.argv[3:]
