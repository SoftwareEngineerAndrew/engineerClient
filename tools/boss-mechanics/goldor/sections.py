#!/usr/bin/env python3
"""Step 2: phase-3 section timelines -> OUT_DIR/sections.json, and the section statistics.

usage: sections.py OUT_DIR [--print]

Per recording group (siblings merged on the reference recording's clock), from chat:
  start       "Who dares trespass" (server tick n = 0 for everything below)
  items[s]    Hypixel's completion lines of section s (1-4): [n, name, kind, i, total]
  done[s]     the line that made i == total (S4: also "The Core entrance is opening!")
  gate[s]     "The gate has been destroyed!" of section s (S1-S3)
  gate5[s]    "The gate will open in 5 seconds!"
  core        "The Core entrance is opening!"
Sections are told apart by the counter restarting (total change, index drop, or i == total).
Completion lines are hidden by chat cleaners in many recordings; groups without them keep the
gate/core lines only.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glib as G  # noqa: E402


def timeline(x):
    c = G.Clock(x['st'])
    g = x['goldor']
    n0 = c.n(g)
    rel = lambda t: c.n(t) - n0
    items = {1: [], 2: [], 3: [], 4: []}
    done, gate, gate5 = {}, {}, {}
    sec = 1
    last = None
    core = None
    ngate = 0
    for t, m in x['chat']:
        if t < g:
            continue
        cm = G.COMPLETION.match(m)
        if cm:
            i, T = int(cm.group(4)), int(cm.group(5))
            if last and (last[0] == last[1] or i < last[0] or T != last[1]):
                sec = min(4, sec + 1)
            items[sec].append([rel(t), cm.group(1), cm.group(3), i, T])
            if i == T:
                done[sec] = rel(t)
            last = (i, T)
        elif m == G.GATE and ngate < 3:
            ngate += 1          # the gates go in order: the k-th one is section k's
            gate[ngate] = rel(t)
        elif m == G.GATE_5S:
            gate5[ngate + 1] = rel(t)
        elif m == G.CORE_OPEN and core is None:
            core = rel(t)
            done.setdefault(4, core)
    ends = {}
    for k, msg in (('dots', G.GOLDOR_DOTS), ('forgive', G.GOLDOR_FORGIVE), ('doneit', G.GOLDOR_DONE),
                   ('nowhere', G.GOLDOR_NOWHERE)):
        t = G.first(x, msg, g)
        ends[k] = rel(t) if t is not None else None
    t = G.first(x, lambda m: m.startswith('[BOSS] Necron'), g)
    ends['necron'] = rel(t) if t is not None else None
    return {'id': x['id'], 'group': x['group'], 'st': G.has_st(x), 'self': x['self'], 'items': items,
            'done': done, 'gate': gate, 'gate5': gate5, 'core': core, 'ends': ends,
            'n_items': sum(len(v) for v in items.values())}


def main():
    out_dir = sys.argv[1]
    X = G.load_extracts(out_dir)
    T = {rid: timeline(x) for rid, x in X.items()}
    json.dump(T, open(os.path.join(out_dir, 'sections.json'), 'w'))
    print('sections.json: %d recordings' % len(T))


if __name__ == '__main__':
    main()
