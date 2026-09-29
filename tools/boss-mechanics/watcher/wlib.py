"""Shared helpers for the Watcher (F7 blood room) analysis. Python 3 standard library only.

Recordings: tools/betterpf-viewer/FORMAT.md. Timelines:
  t  client ticks of one recording
  n  server ticks as that client counted them (Odin's per-tick ping; `st` lines). A line written
     at client tick t gets the n of the last `st` line at or before t (the recorder writes `st`
     first in each tick, so this is the same as "the last st line before it in file order").
"""
import bisect
import gzip
import json
import lzma
import math
import os
import statistics

W = '[BOSS] The Watcher: '
DOOR = 'The BLOOD DOOR has been opened!'
FIRST = W + 'Things feel a little more roomy now, eh?'
HANDLE = W + "Let's see how you can handle this."
ENOUGH = W + 'That will be enough for now.'
PROVEN = W + 'You have proven yourself. You may pass.'
MAXOR = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
SPAWN_LINES = ("You'll do.", 'Go, fight!', 'Go and live again!', 'Hmmm... this one!', 'This guy looks like a fighter.')
KILL_LINES = ('Not bad.', 'Aw, I liked that one.', 'That one was weak anyway.', "I'm impressed.", 'Very nice.')
DIALOG_LINES = ('Things feel a little more roomy now, eh?',
                "I've knocked down those pillars to go for a more...open concept.",
                'Plus I needed to give my new friends some space to roam...')
WATCHER_SKIN = '5662b6fb4b8b'   # the only Watcher head seen on F7 (texture hash prefix)

# The 17 blood mobs every run has, and the mini-bosses (the Giant plus one of four).
REGULAR = {'Parasite', 'Freak', 'Revoker', 'Mr. Dead', 'Leech', 'Psycho', 'Ooze', 'Reaper', 'Putrid',
           'Cannibal', 'Mute', 'Frost', 'Vader', 'Tear', 'Flamer', 'Walker', 'Skull'}
BOSSES = {'Giant', 'Scarf', 'Bonzo', 'Livid', 'Spirit Bear'}

# Hypixel alpha-server recordings (given), plus one that shares their server (mini5J) and their
# alpha-only one-line dialogue "Ah, we meet again. As I foresaw..." - left out as suspected alpha.
ALPHA_RUNS = {'20260927-170801-b120f4c1', '20260928-214732-33c77ab5',
              '20260929-024353-1f5e9ba7', '20260929-025227-24f19367'}
SUSPECT_ALPHA = {'20260928-204121-d4bd4083'}

DEATH_TO_REMOVAL = 20   # server ticks from a mob's 0-health name tag to its removal (measured 19-21)


def read_lines(path):
    raw = open(path, 'rb').read()
    data = lzma.decompress(raw) if raw[:3] == b'\xfd7z' else gzip.decompress(raw)
    return [json.loads(l) for l in data.decode('utf-8').splitlines() if l.strip()]


def run_path(data_dir, rid):
    return os.path.join(data_dir, 'runs', rid + '.gz')


def run_list(data_dir):
    return json.load(open(os.path.join(data_dir, 'runs.json')))


def recording_ids(data_dir):
    p = os.path.join(data_dir, 'ids.txt')
    ids = open(p).read().split() if os.path.exists(p) else [r['id'] for r in run_list(data_dir)]
    return [i for i in ids if os.path.exists(run_path(data_dir, i))]


def load_extract(out_dir, rid):
    return json.load(open(os.path.join(out_dir, 'extract', rid + '.json')))


def extract_ids(out_dir):
    return sorted(f[:-5] for f in os.listdir(os.path.join(out_dir, 'extract')) if f.endswith('.json'))


class Clock:
    """n(t): server tick count at client tick t. Recordings with no `st` lines fall back to t."""

    def __init__(self, st):
        st = st if len(st) > 10 else []
        self.ok = bool(st)
        self.ts = [a for a, _ in st]
        self.ns = [b for _, b in st]

    def n(self, t):
        if not self.ts:
            return t
        i = bisect.bisect_right(self.ts, t) - 1
        if i < 0:
            return self.ns[0] - (self.ts[0] - t)
        return self.ns[i]


def center(x):
    """World x/z of the blood room's middle (the corner between its four middle blocks)."""
    b = x['blood']
    return (b[0] + b[2] + 1) / 2, (b[1] + b[3] + 1) / 2


def watcher_ids(x):
    return [i for i, e in x['ents'].items()
            if e['type'] == 'minecraft:zombie' and any(s[2].startswith(WATCHER_SKIN) for s in e['eq'] if s[2])]


def decimals(x):
    """3 for recordings that wrote 1/1000-block positions, 2 for the newer 1/100 ones."""
    for e in x['ents'].values():
        for ev in e['ev'][:50]:
            if ev[1] == 'e':
                for v in (ev[2], ev[4]):
                    s = repr(v)
                    if '.' in s and len(s.split('.')[1]) == 3:
                        return 3
    return 2


def delerp(ev, decimals=3):
    """Server packet positions from the client's 3-step lerp (as tools/boss-movement/bosslib.py).

    On a packet tick pos_t = pos_{t-1} + (T - pos_{t-1}) / 3, so T = pos_{t-1} + 3 (pos_t - pos_{t-1});
    ticks that just continue the running lerp are not packets. ev: [[t, 's'|'e'|'g', x, y, z], ...].
    Returns [(t, x, y, z, 's'|'p')]."""
    tol = 0.0026 if decimals >= 3 else 0.011
    out, segs = [], []
    for e in ev:
        if e[1] == 's':
            segs.append([e])
        elif segs:
            segs[-1].append(e)
    for seg in segs:
        P = list(seg[0][2:5]); T = P[:]; s = 0
        out.append((seg[0][0], P[0], P[1], P[2], 's'))
        ob = {e[0]: e[2:5] for e in seg[1:] if e[1] == 'e'}
        if not ob:
            continue
        t, tend = seg[0][0], max(ob)
        while t < tend:
            t += 1
            pred = [p + (q - p) / s for p, q in zip(P, T)] if s > 0 else P[:]
            o = ob.get(t)
            if o is None:
                P = pred; s = max(0, s - 1); continue
            if max(abs(a - b) for a, b in zip(o, pred)) <= tol:
                P = list(o); s = max(0, s - 1); continue
            Tn = [p + 3 * (a - p) for p, a in zip(P, o)]
            out.append((t, Tn[0], Tn[1], Tn[2], 'p'))
            T = Tn; P = list(o); s = 2
    return out


def median(v):
    return statistics.median(v) if v else None


def pct(v, p):
    if not v:
        return None
    s = sorted(v)
    return s[min(len(s) - 1, max(0, int(round(p / 100 * (len(s) - 1)))))]


def summary(v, nd=0):
    v = [a for a in v if a is not None]
    if not v:
        return 'n=0'
    f = (lambda a: '%.*f' % (nd, a))
    return 'n=%d min %s p10 %s median %s p90 %s max %s' % (len(v), f(min(v)), f(pct(v, 10)), f(median(v)), f(pct(v, 90)), f(max(v)))


def dist(a, b):
    return math.sqrt(sum((p - q) ** 2 for p, q in zip(a, b)))
