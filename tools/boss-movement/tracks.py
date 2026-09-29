#!/usr/bin/env python3
"""Step 2: merge each run's recordings into one timeline per boss.

usage: tracks.py OUT_DIR

Reads OUT_DIR/extract/*.json (extract.py), writes OUT_DIR/tracks/<group>.json and prints what
was kept/thrown out. Every time is the reference recording's server tick count `n` (see
bosslib). Per boss:
  id        the wither's entity id (the one under the '﴾ Maxor ﴿' / '﴾ Storm ﴿' tag, else the
            wither that spawned at the boss's fixed spawn point, else for Storm the wither first
            seen above y 175 during his phase)
  obs       [[n, x, y, z, yaw, headYaw, rec]] the recorded (client-interpolated) positions
  pkt       [[n, x, y, z, rec, t]] move packets recovered by undoing the client's lerp
  spans     [[n0, n1, rec]] when some recording had him in view
Players: [[n, x, y, z, rec]] from their own recording when there is one (exact), else the
reference recording's view of them (interpolated like mobs).
"""
import json
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bosslib as B  # noqa: E402
import recording as R  # noqa: E402


def boss_events(x, bid):
    e = x['ents'].get(bid)
    return e['ev'] if e else []


def storm_fallback(members, ss_by_rec, gs_by_rec, maxor_id):
    counts = {}
    for x in members:
        ss = ss_by_rec.get(x['id'])
        if ss is None:
            continue
        gs = gs_by_rec.get(x['id']) or x['window'][1]
        for k, e in x['ents'].items():
            if e['type'] != 'minecraft:wither' or k == maxor_id:
                continue
            first = e['ev'][0]
            if ss <= first[0] <= gs and first[3] is not None and first[3] >= 175 and 20 <= first[2] <= 130 and 10 <= first[4] <= 110:
                counts[k] = counts.get(k, 0) + len(e['ev'])
    return max(counts, key=counts.get) if counts else None


def build(members, log):
    # reference = the recording with the most Storm + Maxor events
    def score(x):
        return sum(len(e['ev']) for e in x['ents'].values() if e['type'] == 'minecraft:wither')
    members = sorted(members, key=score, reverse=True)
    ref = members[0]
    clocks = {x['id']: B.Clock(x['st']) for x in members}
    offsets = {ref['id']: {'dn': 0, 'dt': 0, 'spread_n': 0, 'spread_t': 0, 'shared': None}}
    for x in members[1:]:
        dts, dns = B.align(ref, x)
        if len(dns) < 3:
            log.append('%s: sibling %s shares only %d chat lines with %s - left out' % (ref['group'], x['id'], len(dns), ref['id']))
            continue
        dn = statistics.median(dns)
        dt = statistics.median(dts)
        offsets[x['id']] = {'dn': dn, 'dt': dt, 'spread_n': max(abs(v - dn) for v in dns),
                            'spread_t': max(abs(v - dt) for v in dts), 'shared': len(dns)}
    members = [x for x in members if x['id'] in offsets]

    def N(x, t):
        return clocks[x['id']].n(t) + offsets[x['id']]['dn']

    # phase lines on the reference timeline (from the reference recording, siblings fill gaps)
    events = {}
    for x in members:
        for t, m, k in B.chat_events(x):
            events.setdefault(m, {})
            if k not in events[m]:
                events[m][k] = N(x, t)
    events = {m: [v[k] for k in sorted(v)] for m, v in events.items()}

    maxor_id = B.group_boss_id(members, 'Maxor', B.MAXOR_SPAWN)
    storm_id = B.group_boss_id(members, 'Storm', B.STORM_SPAWN)
    how = 'tag/spawn'
    if storm_id is None:
        ss = {x['id']: B.first_line(x, R.STORM_START) for x in members}
        gs = {x['id']: B.first_line(x, R.GOLDOR_START) for x in members}
        storm_id = storm_fallback(members, ss, gs, maxor_id)
        how = 'first wither above y 175 in his phase'
        if storm_id:
            log.append('%s: Storm identified as %s (%s)' % (ref['group'], storm_id, how))

    out = {'group': ref['group'], 'ref': ref['id'], 'recs': [x['id'] for x in members], 'offsets': offsets,
           'self': {x['id']: x['self'] for x in members}, 'party': ref['party'], 'events': events,
           'cleared': any(x.get('cleared') for x in members),
           'lag': {}, 'bosses': {}, 'players': {}, 'tp': {}, 'blocks': [], 'others': []}

    for x in members:
        c = clocks[x['id']]
        w0, w1 = x['window']
        tot_t = w1 - w0
        tot_n = (c.n(w1) or 0) - (c.n(w0) or 0)
        # longest stretch of client ticks without a server tick, and biggest server jump in one tick
        gap = jump = 0
        prev = None
        for t, n in x['st']:
            if prev:
                gap = max(gap, t - prev[0] - 1)
                jump = max(jump, n - prev[1])
            prev = (t, n)
        out['lag'][x['id']] = {'client_ticks': tot_t, 'server_ticks': tot_n, 'max_stall': gap, 'max_jump': jump}

    for name, bid in (('maxor', maxor_id), ('storm', storm_id)):
        if bid is None:
            continue
        obs, pkt, spans = [], [], []
        per_rec = {}
        for x in members:
            ev = boss_events(x, bid)
            if not ev:
                continue
            dec = B.rec_decimals(x)
            for e in ev:
                if e[1] in 'se':
                    obs.append([N(x, e[0]), e[2], e[3], e[4], e[5], e[6], x['id'], e[0]])
            p, rms = B.delerp(ev, dec)
            for t, px, py, pz, k in p:
                pkt.append([N(x, t), px, py, pz, x['id'], t, k])
            for a, b in B.visible_spans(ev):
                spans.append([N(x, a), N(x, b), x['id']])
            per_rec[x['id']] = {'events': len(ev), 'packets': sum(1 for q in p if q[4] == 'p'), 'grid_rms': rms, 'decimals': dec}
        obs.sort(key=lambda r: (r[0], r[6]))
        pkt.sort(key=lambda r: (r[0], r[4]))
        out['bosses'][name] = {'id': bid, 'obs': obs, 'pkt': pkt, 'spans': spans, 'per_rec': per_rec,
                               'how': how if name == 'storm' else 'tag/spawn'}

    # other withers in Storm's arena (not Storm): kept for the report's exclusion list
    for x in members:
        for k, e in x['ents'].items():
            if e['type'] == 'minecraft:wither' and k not in (maxor_id, storm_id):
                f = e['ev'][0]
                if f[3] is not None and 150 <= f[3] <= 200:
                    mv = [q for q in e['ev'] if q[1] == 'e']
                    out['others'].append([k, x['id'], N(x, f[0]), f[2], f[3], f[4], N(x, e['ev'][-1][0]),
                                          [[N(x, q[0]), q[2], q[3], q[4]] for q in mv[::5]]])

    # players: own recording first (exact), else the reference's view
    own = {x['self']: x for x in members}
    for name in ref['party']:
        src = own.get(name)
        rows = []
        if src is not None and name in src['players']:
            rows = [[N(src, r[0]), r[1], r[2], r[3], src['id']] for r in src['players'][name]]
        else:
            for x in members:
                if name in x['players']:
                    rows = [[N(x, r[0]), r[1], r[2], r[3], x['id']] for r in x['players'][name]]
                    break
        out['players'][name] = rows
    for x in members:
        out['tp'][x['self']] = [[N(x, r[0])] + r[1:] for r in x['tp']]
        for b in x['blocks']:
            out['blocks'].append([N(x, b[0]), b[1], b[2], b[3], b[4], x['id']])
    # deaths / revives (ghosts) on the reference timeline, from every recording (deduplicated)
    deaths = []
    for x in members:
        for t, m in x['chat']:
            who = what = None
            mm = B.DEATH.match(m)
            if mm:
                who, what = mm.group(1), 'dead'
            elif B.DEATH_YOU.match(m):
                who, what = x['self'], 'dead'
            else:
                mm = B.REVIVED.match(m)
                if mm:
                    who, what = mm.group(1) or mm.group(2), 'alive'
                elif m.startswith(' \u2763 You were revived'):
                    who, what = x['self'], 'alive'
            if who:
                n = N(x, t)
                if not any(d[1] == who and d[2] == what and abs(d[0] - n) <= 5 for d in deaths):
                    deaths.append([n, who, what])
    out['deaths'] = sorted(deaths)
    out['pgone'] = {}
    for x in members:
        for t, name in x['pgone']:
            out['pgone'].setdefault(name, []).append([N(x, t), x['id']])
    return out


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    out_dir = sys.argv[1]
    X = B.load_extracts(out_dir)
    G = B.groups(X)
    os.makedirs(os.path.join(out_dir, 'tracks'), exist_ok=True)
    log = []
    n_m = n_s = 0
    for gid, members in sorted(G.items()):
        tr = build(members, log)
        json.dump(tr, open(os.path.join(out_dir, 'tracks', gid + '.json'), 'w'), separators=(',', ':'))
        n_m += 'maxor' in tr['bosses']
        n_s += 'storm' in tr['bosses']
    print('recordings with a boss window: %d, runs (groups): %d, with Maxor id: %d, with Storm id: %d' % (len(X), len(G), n_m, n_s))
    for line in log:
        print(line)


if __name__ == '__main__':
    main()
