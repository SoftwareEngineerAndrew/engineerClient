"""Shared helpers for the Goldor (F7 phase 3) analysis. Python 3 standard library only.

Reading recordings, the phase's chat lines, server-tick clocks and sibling alignment. The
de-interpolation of mob positions (`delerp`) and the Clock come from tools/boss-movement/bosslib.py
(imported from the sibling directory, not copied).
"""
import gzip
import json
import lzma
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '..', 'boss-movement'))
import bosslib as B  # noqa: E402,F401  (re-exported: B.delerp, B.Clock, B.median, B.pct)

Clock = B.Clock
delerp = B.delerp
median = B.median
pct = B.pct

GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
CORE_OPEN = "The Core entrance is opening!"
GATE = "The gate has been destroyed!"
GATE_5S = "The gate will open in 5 seconds!"
GOLDOR_DOTS = "[BOSS] Goldor: ...."
GOLDOR_FORGIVE = "[BOSS] Goldor: Necron, forgive me."
GOLDOR_DONE = "[BOSS] Goldor: You have done it, you destroyed the factory..."
GOLDOR_NOWHERE = "[BOSS] Goldor: But you have nowhere to hide anymore!"
COMPLETION = re.compile(r'^(\w{1,16}) (activated|completed) a (terminal|device|lever)! \((\d)/(\d)\)$')

# Recorded on Hypixel's alpha server: left out of everything.
ALPHA_RUNS = {
    '20260927-170801-b120f4c1', '20260928-214732-33c77ab5',
    '20260929-024353-1f5e9ba7', '20260929-025227-24f19367',
}


def read_lines(path):
    raw = open(path, 'rb').read()
    data = lzma.decompress(raw) if raw[:3] == b'\xfd7z' else gzip.decompress(raw)
    return [json.loads(l) for l in data.decode('utf-8').splitlines() if l.strip()]


def run_path(data_dir, run_id):
    return os.path.join(data_dir, 'runs', run_id + '.gz')


def run_list(data_dir):
    return json.load(open(os.path.join(data_dir, 'runs.json')))


def wanted_ids(data_dir):
    p = os.path.join(data_dir, 'ids.txt')
    if os.path.exists(p):
        return [x for x in open(p).read().split() if x]
    return [r['id'] for r in run_list(data_dir)]


def load_extracts(out_dir, need_st=False):
    """{id: extract} without the alpha runs; need_st keeps only recordings with server ticks."""
    X = {}
    d = os.path.join(out_dir, 'extract')
    for f in sorted(os.listdir(d)):
        if not f.endswith('.json'):
            continue
        x = json.load(open(os.path.join(d, f)))
        if 'skip' in x or x['id'] in ALPHA_RUNS:
            continue
        if need_st and len(x['st']) <= 10:
            continue
        X[x['id']] = x
    return X


def has_st(x):
    return len(x['st']) > 10


def groups(X):
    g = {}
    for x in X.values():
        g.setdefault(x['group'], []).append(x)
    return g


def first(x, msg, after=None):
    for t, m in x['chat']:
        if (m == msg if isinstance(msg, str) else msg(m)) and (after is None or t >= after):
            return t
    return None


def completions(x):
    """[(t, name, kind, i, total)] Hypixel's completion lines (hidden by chat cleaners in many)."""
    out = []
    for t, m in x['chat']:
        c = COMPLETION.match(m)
        if c:
            out.append((t, c.group(1), c.group(3), int(c.group(4)), int(c.group(5))))
    return out


def dist(a, b):
    return sum((p - q) ** 2 for p, q in zip(a, b)) ** 0.5


def fmt(v, nd=1):
    return 'NA' if v is None else ('%.*f' % (nd, v))


def summary(v, nd=1):
    """'median [min-max] (n)' of a list."""
    v = [a for a in v if a is not None]
    if not v:
        return 'n=0'
    s = sorted(v)
    return '%s [%s-%s] p10-p90 %s-%s (n=%d)' % (fmt(median(s), nd), fmt(s[0], nd), fmt(s[-1], nd),
                                               fmt(pct(s, 10), nd), fmt(pct(s, 90), nd), len(s))
