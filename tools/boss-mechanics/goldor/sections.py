#!/usr/bin/env python3
"""Step 2: phase-3 section timelines -> OUT_DIR/sections.json, and the section statistics.

usage: sections.py OUT_DIR

Needs OUT_DIR/doors.json (doors.py). Per recording, on its server-tick clock with n = 0 at
"Who dares trespass":
  items[s]   Hypixel's completion lines of section s: [n, name, kind, i, total] (only in
             recordings whose chat was not cleaned; sections told apart by the counter restarting)
  done[s]    the "(n/n)" line of S1-S3
  door[s]    the tick S1-S3's door barriers turned to air (blocks: every recording)
  coredoor   the core entrance's gold blocks -> barrier ("opening") and barrier -> air (open)
  gate[s]    "The gate has been destroyed!" (the k-th one is section k's), gate5[s] "... in 5 seconds!"
  gateblk[s] the gate's cracked bricks turning to air (blocks)
  core       "The Core entrance is opening!"
  ends       Goldor's last lines and Necron's first
"""
import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glib as G  # noqa: E402

GATE_BOXES = {1: (93, 107, 113, 135, 121, 124), 2: (16, 19, 114, 138, 125, 139), 3: (1, 15, 114, 138, 48, 51)}
CORE_DOOR = (52, 56, 115, 121, 54, 54)


def inbox(b, x, y, z):
    return b[0] <= x <= b[1] and b[2] <= y <= b[3] and b[4] <= z <= b[5]


def timeline(x, doors):
    c = G.Clock(x['st'])
    g = x['goldor']
    n0 = c.n(g)
    rel = lambda t: c.n(t) - n0
    items = {1: [], 2: [], 3: [], 4: []}
    done, gate, gate5 = {}, {}, {}
    sec, last, core, ngate = 1, None, None, 0
    for t, m in x['chat']:
        if t < g:
            continue
        cm = G.COMPLETION.match(m)
        if cm:
            i, T = int(cm.group(4)), int(cm.group(5))
            if last and (last[0] == last[1] or i < last[0] or T != last[1]):
                sec = min(4, sec + 1)
            items[sec].append([rel(t), cm.group(1), cm.group(3), i, T])
            if i == T and sec < 4:
                done[sec] = rel(t)
            last = (i, T)
        elif m == G.GATE and ngate < 3:
            ngate += 1          # the gates go in order: the k-th one is section k's
            gate[ngate] = rel(t)
        elif m == G.GATE_5S:
            gate5[ngate + 1] = rel(t)
        elif m == G.CORE_OPEN and core is None:
            core = rel(t)
    # blocks
    door, gateblk, coredoor = {}, {}, {}
    cnt = collections.Counter()
    for t, bx, by, bz, s in x['blocks']:
        if t < g:
            continue
        p = (bx, by, bz)
        air = s == 'minecraft:air'
        for k, ps in doors.items():
            if air and p in ps:
                cnt[('door', k, t)] += 1
        for k, b in GATE_BOXES.items():
            if air and inbox(b, *p) and p not in doors.get(k, ()):
                cnt[('gate', k, t)] += 1
        if inbox(CORE_DOOR, *p):
            cnt[('core', s.split('[')[0], t)] += 1
    for (what, k, t), v in sorted(cnt.items(), key=lambda a: a[0][2]):
        if what == 'door' and v >= 40:
            door.setdefault(k, rel(t))
        elif what == 'gate' and v >= 40 and k not in gateblk:
            # the gate goes before its section's door, never in the same tick
            if door.get(k) is None or rel(t) < door[k]:
                gateblk[k] = rel(t)
        elif what == 'core' and v >= 20:
            coredoor.setdefault(k.replace('minecraft:', ''), rel(t))
    ends = {}
    for k, msg in (('dots', G.GOLDOR_DOTS), ('forgive', G.GOLDOR_FORGIVE), ('doneit', G.GOLDOR_DONE),
                   ('nowhere', G.GOLDOR_NOWHERE)):
        t = G.first(x, msg, g)
        ends[k] = rel(t) if t is not None else None
    t = G.first(x, lambda m: m.startswith('[BOSS] Necron'), g)
    ends['necron'] = rel(t) if t is not None else None
    return {'id': x['id'], 'group': x['group'], 'st': G.has_st(x), 'self': x['self'], 'items': items,
            'done': done, 'door': door, 'gate': gate, 'gate5': gate5, 'gateblk': gateblk,
            'coredoor': coredoor, 'core': core, 'ends': ends}


def active_section(sec, n):
    """The section in progress at n (1-4), from the door times (chat as fallback)."""
    k = 1
    for s in (1, 2, 3):
        d = sec['door'].get(str(s), sec['done'].get(str(s)))
        if d is not None and d <= n:
            k = s + 1
    return k


def main():
    out_dir = sys.argv[1]
    dj = json.load(open(os.path.join(out_dir, 'doors.json')))['doors']
    doors = {int(k): set(map(tuple, v)) for k, v in dj.items()}
    X = G.load_extracts(out_dir)
    T = {rid: timeline(x, doors) for rid, x in X.items()}
    json.dump(T, open(os.path.join(out_dir, 'sections.json'), 'w'))
    T = json.load(open(os.path.join(out_dir, 'sections.json')))   # keys as strings from here on
    print('sections.json: %d recordings' % len(T))
    S = [s for s in T.values() if s['st']]

    # --- what a section is made of (recordings with Hypixel's completion lines)
    comp = collections.defaultdict(collections.Counter)
    totals = collections.defaultdict(collections.Counter)
    dev_dup = collections.Counter()
    first_idx = collections.defaultdict(collections.Counter)
    nrec = 0
    for s in T.values():
        if not any(s['items'].values()):
            continue
        nrec += 1
        for k, its in sorted(s['items'].items()):
            if not its:
                continue
            totals[k][its[0][4]] += 1
            first_idx[k][its[0][3]] += 1
            seen = set()
            kinds = collections.Counter()
            for n, name, kind, i, tot in its:
                if i in seen:
                    dev_dup[(k, kind)] += 1   # did not advance the counter
                else:
                    kinds[kind] += 1
                seen.add(i)
            if its[-1][3] == its[-1][4]:
                comp[k][tuple(sorted(kinds.items()))] += 1
    print('\nrecordings with completion lines: %d' % nrec)
    for k in ('1', '2', '3', '4'):
        print('  S%s totals %s; kinds of the counted lines in completed sections: %s' % (
            k, dict(totals[k]), comp[k].most_common(3)))
        print('      first index seen: %s' % sorted(first_idx[k].items()))
    print('  lines that repeat the previous index (did not count):', dict(dev_dup))

    # --- door vs completion line
    lag = collections.Counter()
    for s in S:
        for k in ('1', '2', '3'):
            if k in s['door'] and k in s['done']:
                lag[s['door'][k] - s['done'][k]] += 1
    print('\ndoor barriers -> air minus the (n/n) line (server ticks):', sorted(lag.items()))
    have = sum(1 for s in S if all(k in s['door'] for k in ('1', '2', '3')))
    print('recordings with server ticks: %d, with all three door times: %d' % (len(S), have))

    # --- gates
    print('\ngates (server ticks):')
    g5 = []
    before = collections.Counter()
    for s in S:
        for k in ('1', '2', '3'):
            gt = s['gate'].get(k, s['gateblk'].get(k))
            d = s['door'].get(k)
            if gt is None or d is None:
                continue
            before['gate before door' if gt < d else 'gate after door'] += 1
            if gt > d:
                g5.append(gt - d)
    print('  ', dict(before))
    print('   gate destroyed after its section was done: door -> gate', G.summary(g5, 0))
    lag5 = [s['gate'][k] - s['gate5'][k] for s in S for k in s['gate5'] if k in s['gate']]
    print('   "The gate will open in 5 seconds!" -> destroyed', sorted(lag5))
    gl = [s['gateblk'][k] - s['gate'][k] for s in S for k in s['gate'] if k in s['gateblk']]
    print('   gate bricks -> air minus the chat line:', sorted(collections.Counter(gl).items()))
    cd = collections.Counter()
    for s in S:
        if s['core'] is not None and 'air' in s['coredoor'] and 'barrier' in s['coredoor']:
            cd[(s['coredoor']['barrier'] - s['core'], s['coredoor']['air'] - s['coredoor']['barrier'])] += 1
    print('\ncore door: (gold -> barrier minus the "opening" line, barrier -> air minus gold -> barrier):',
          sorted(cd.items()))
    last = [(s['core'] - max(it[0] for it in s['items']['4'])) for s in S if s['items']['4'] and s['core'] is not None]
    print('"The Core entrance is opening!" minus S4\'s last completion line:', sorted(collections.Counter(last).items()))


if __name__ == '__main__':
    main()
