#!/usr/bin/env python3
"""Every number in docs/mechanics/maxor.md.

usage: analyze.py MAXOR_OUT MOVE_OUT [section ...]

  MAXOR_OUT  this directory's extract.py output (MAXOR_OUT/extract/)
  MOVE_OUT   ../../boss-movement output (MOVE_OUT/tracks/, from its extract.py + tracks.py)
sections: data timeline grid crystals laser stun death targeting movement fastest (default: all)

Times are server ticks (the `st` lines, 20 a second) after "WELL! WELL! WELL! LOOK WHO'S HERE!"
unless a line says otherwise. Only runs whose every recording has server ticks are used for
anything timed ("server-timed"); alpha-server recordings are never read.
"""
import collections
import math
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import maxorlib as M  # noqa: E402

S = M.stats
H = M.hist


def title(t):
    print('\n' + '=' * 8 + ' ' + t + ' ' + '=' * (70 - len(t)))


# ---------------------------------------------------------------------------------------- data
def sec_data(runs, T):
    title('data')
    recs = sum(len(r.recs) for r in runs)
    print('runs with Maxor\'s first line: %d (%d recordings)' % (len(runs), recs))
    print('server-timed runs: %d, of them 5-player: %d' % (len(T), sum(r.size == 5 for r in T)))
    print('party sizes (server-timed):', H([r.size for r in T]))
    print('with Maxor\'s move packets: %d' % sum(bool(r.pkt()) for r in T))


# ------------------------------------------------------------------------------------ timeline
def sec_timeline(T):
    title('timeline (ticks after WELL! WELL! WELL!)')
    rows = collections.defaultdict(list)
    for r in T:
        s = r.summary()
        rows['I\'VE BEEN TOLD ...'].append(r.rel(r.first(M.INTRO2)))
        rows['DON\'T DISAPPOINT ME ...'].append(r.rel(r.first(M.INTRO_END)))
        for kind, side, n0, n1 in r.crystals():
            if kind == 'top' and r.rel(n0) < 60:
                rows['top crystals spawn'].append(r.rel(n0))
                break
        ws = None
        for rec in r.recs:
            e = r.X[rec]['ents'].get(r.maxor_id)
            if e:
                for ev in e['ev']:
                    if ev[1] == 's' and abs(ev[2] - 73) < .1 and abs(ev[4] - 53) < .1 and abs(ev[3] - 226) < .1:
                        ws = r.N(rec, ev[0])
                        break
            if ws is not None:
                break
        rows['Maxor\'s wither spawns (73, 226, 53)'].append(r.rel(ws))
        rows['beacon slot cleared (pylons open)'].append(r.rel(s['P']))
        if s['acts']:
            rows['first placement line'].append(r.rel(s['acts'][0]))
        i3 = r.first(M.INTRO_END)
        pk = [p for p in r.pkt() if i3 is not None and i3 + 30 <= p[0] and math.dist(p[1:4], M.MAXOR_SPAWN) > 0.05]
        if pk and i3 is not None:
            rows['first move packet (after DON\'T DISAPPOINT)'].append(pk[0][0] - i3)
        rows['beacon placed at (73, 221, 73)'].append(r.rel(s['beacon']))
        if s['chg']:
            rows['first "charging up"'].append(r.rel(s['chg'][0]))
        if s['stuns']:
            rows['first stun line'].append(r.rel(s['stuns'][0]))
        if len(s['stuns']) > 1:
            rows['second stun line'].append(r.rel(s['stuns'][1]))
        if s['bedrock']:
            rows['kill (beacon -> bedrock)'].append(r.rel(s['bedrock']))
        rows['wither despawns'].append(r.rel(s['gone']))
        rows['Storm\'s first line'].append(r.rel(s['storm']))
    for k, v in rows.items():
        print('%-44s %s' % (k, S(v, 0)))
    print('laser phase start P (beacon slot cleared) - DON\'T DISAPPOINT:',
          H([r.summary()['P'] - r.first(M.INTRO_END) for r in T if r.summary()['P'] and r.first(M.INTRO_END)]))
    print('beacon placed - P:', H([r.summary()['beacon'] - r.summary()['P'] for r in T if r.summary()['P'] and r.summary()['beacon']]))
    odd = [(r.group[-8:], r.size, r.rel(r.first(M.INTRO2))) for r in T if r.first(M.INTRO2) and not 58 <= r.rel(r.first(M.INTRO2)) <= 66]
    print('runs with an odd intro:', odd)


# ---------------------------------------------------------------------------------------- grid
def sec_grid(T):
    title('the 10-tick checks')
    C = collections.defaultdict(collections.Counter)
    for r in T:
        g = r.grid()
        if g is None:
            continue
        s = r.summary()
        C['WELL! WELL! WELL! line'][r.gdev(r.s0)] += 1
        for key, ns in (('placement lines', s['acts']), ('"charging up" lines', s['chg']), ('stun lines', s['stuns']),
                        ('enrage lines', s['enr']), ('death line (I\'M TOO YOUNG)', [s['dead']] if s['dead'] else []),
                        ('pickup lines', [p[0] for p in s['picks']]), ('Storm\'s first line', [s['storm']] if s['storm'] else []),
                        ('beacon placed', [s['beacon']] if s['beacon'] else []),
                        ('kill (beacon -> bedrock)', [s['bedrock']] if s['bedrock'] else []),
                        ('column glass black', s['black'])):
            for n in ns:
                C[key][r.gdev(n)] += 1
        for kind, side, n0, n1 in r.crystals():
            if r.rel(n0) > 60:
                C['%s crystal appears' % kind][r.gdev(n0)] += 1
    for k, c in C.items():
        tot = sum(c.values())
        near = sum(c[i] for i in (-1, 0, 1, 2))
        print('%-32s n=%4d  ' % (k, tot) + ' '.join('%+d:%d' % (i, c[i]) for i in range(-5, 5)) + '   (within -1..+2: %.0f%%)' % (100 * near / tot))
    same = collections.Counter()
    for r in T:
        g = r.grid()
        if g is None:
            continue
        for n, y, nm in r.column():
            if 222 <= y <= 224 and 'glass' in nm and 0 < r.rel(n) < 1500:
                same[r.gdev(n)] += 1
    print('column glass changes vs their own run\'s check residue:', sorted(same.items()))
    print('check residue relative to the first line (mod 10):', H([(r.grid() - r.s0) % 10 for r in T if r.grid() is not None]))


# ------------------------------------------------------------------------------------ crystals
def sec_crystals(T):
    title('energy crystals')
    top_after_hit, pyl_after_hit, black_after_hit, tops_first = [], [], [], []
    pick_match = [0, 0]
    for r in T:
        s = r.summary()
        hits = [h for h, L in r.hits()]
        picks = [p[0] for p in s['picks']]
        for kind, side, n0, n1 in r.crystals():
            prev = [h for h in hits if h < n0]
            if kind == 'top':
                if prev and n0 - prev[-1] < 80:
                    top_after_hit.append(n0 - prev[-1])
                elif not prev:
                    tops_first.append(r.rel(n0))
                if n1 is not None:
                    pick_match[0] += 1
                    pick_match[1] += any(abs(p - n1) <= 2 for p in picks)
            if kind == 'pylon' and n1 is not None:
                ph = [h for h in hits if h <= n1 + 1]
                if ph and n1 - ph[-1] < 60:
                    pyl_after_hit.append(n1 - ph[-1])
        for L in s['stuns']:
            b = [x for x in s['black'] if 0 < x - L < 90]
            if b:
                black_after_hit.append(b[0] - L)
    print('top crystals first appear:', S(tops_first, 0))
    print('a top crystal disappearing matches a pickup line (+-2 ticks): %d of %d' % (pick_match[1], pick_match[0]))
    print('top crystals reappear, ticks after a laser hit:', H([x for x in top_after_hit]))
    print('placed (pylon) crystals removed, ticks after a laser hit:', H(pyl_after_hit))
    print('column goes black, ticks after a stun line:', H(black_after_hit))
    # counter: X in "X/2 ... active" = 1 + earlier placements this cycle whose chain has finished
    tab = collections.Counter()
    for r in T:
        s = r.summary()
        acts = [(n, m) for n, m, *_ in r.chat if m in M.ACTIVE]
        for i, (n, m) in enumerate(acts):
            reset = max([b for b in s['black'] if b <= n] or [-10 ** 9])
            prev = [a for a, _ in acts[:i] if a >= reset]
            done = sum(1 for a in prev if a + 28 <= n)
            tab[(int(m[0]), 1 + done)] += 1
    ok = sum(v for (x, y), v in tab.items() if x == y)
    print('"X/2 Energy Crystals are now active!": X = 1 + earlier placements this cycle whose 28-tick chain is done: %d of %d' % (ok, sum(tab.values())), dict(tab))
    # placement -> charging line, and charging -> laser armed (glass red) on the next check
    ch, red = [], []
    for r in T:
        s = r.summary()
        for c in s['chg']:
            a = [x for x in s['acts'] if c - 60 < x <= c]
            if a:
                ch.append(c - a[-1])
            rd = [x for x in s['red'] if c - 2 <= x <= c + 12]
            if rd:
                red.append(rd[0] - c)
    print('"charging up" after the last placement line:', S(ch, 0), H([x for x in ch if x < 35]))
    print('column turns red after "charging up":', H(red))
    # placements: on the checks?
    ph = collections.defaultdict(collections.Counter)
    after = []
    for r in T:
        if r.grid() is None:
            continue
        s = r.summary()
        hits = [h for h, L in r.hits()]
        for n in s['acts']:
            prev = [h for h in hits if h < n]
            if not prev:
                k = 'cycle 1'
            else:
                k = 'after black' if any(prev[-1] < b <= n + 1 for b in s['black']) else 'after a hit, before black'
                after.append(n - prev[-1])
            ph[k][r.gdev(n)] += 1
    for k, c in ph.items():
        print('placement lines, %-26s' % k, ' '.join('%+d:%d' % (i, c[i]) for i in range(-5, 5)))
    print('placement lines after a hit, ticks after it:', S(after, 0))
    first = [r.rel(r.summary()['acts'][0]) for r in T if r.summary()['acts']]
    print('first placement line:', H(first))
    # sea-lantern chains
    lens = collections.Counter()
    for r in T:
        rec = r.ref()
        on = [(n, x, z) for n, x, y, z, st in r.blocks(rec) if y == 221 and 'sea_lantern' in st and 0 < r.rel(n) < 260 and x != 73]
        if on:
            lens[len(set((x, z) for n, x, z in on))] += 1
    print('lit sea lanterns in the y 221 chains during the first charge (both sides):', dict(lens))


# --------------------------------------------------------------------------------------- laser
def region_points(T):
    pts = []
    for r in T:
        s = r.summary()
        g = r.grid()
        if g is None or s['beacon'] is None:
            continue
        hits = r.hits()
        end = s['gone'] or s['storm'] or r.s0 + 3000
        if s['bedrock']:
            end = min(end, s['bedrock'])                   # killed: no more hits
        abil = [(n, n + (62 if k == 'A' else 82)) for n, k in s['taunts']]
        for n in range(s['beacon'] - 1, end):
            if (n - g) % 10:
                continue
            chg = [c for c in s['chg'] if c <= n + 1]
            prevh = [h for h, L in hits if h < n - 1]
            if not chg or (prevh and chg[-1] < prevh[-1]):
                continue                                   # laser not charged
            if any(a <= n < b for a, b in abil):
                continue                                   # during an ability (hits are silent then)
            if prevh:
                a, b = r.chat_ms(dict((h, L) for h, L in hits)[prevh[-1]], M.STUN), r.ms(n)
                if prevh[-1] != dict((h, L) for h, L in hits)[prevh[-1]]:
                    a = r.ms(prevh[-1])
                if a is None or b is None or b - a < 10050:
                    continue                               # cooldown
            p = r.pos_interp(n)
            if p is None:
                continue
            hit = any(n - 1 <= h <= n + 2 for h, L in hits)
            pts.append((hit, p, r.group[-8:], r.rel(n)))
    return pts


def sec_laser(T):
    title('the laser: when a hit lands')
    # cooldown: wall-clock time between a hit and the next check that found Maxor in the beam area
    rows = []
    for r in T:
        s = r.summary()
        hits = r.hits()
        if not hits:
            continue
        g = r.grid()
        if g is None:
            continue
        abil = [(n, n + (62 if k == 'A' else 82)) for n, k in s['taunts']]
        for i, (h, L) in enumerate(hits[:-1] + [hits[-1]]):
            nxt = hits[i + 1][0] if i + 1 < len(hits) else None
            hms = r.ms(h) if h != L else r.chat_ms(L, M.STUN)
            if hms is None:
                continue
            stop = nxt if nxt is not None else (s['bedrock'] or s['gone'] or h + 400)
            for n in range(h + 10 - ((h + 10 - g) % 10), stop + 1, 10):
                chg = [c for c in s['chg'] if h < c <= n + 1]
                if not chg:
                    continue
                if any(a <= n < b for a, b in abil):
                    continue
                p = r.pos_interp(n)
                if p is None or not (math.hypot(p[0] - 73.5, p[2] - 73.2) < 1.6 and 224.5 < p[1] < 227.5):
                    continue
                m = r.chat_ms(nxt, M.STUN) if (nxt is not None and n - 1 <= nxt <= n + 2) else r.ms(n)
                if m is None:
                    continue
                rows.append(((m - hms) / 1000, nxt is not None and n - 1 <= nxt <= n + 2, n - h, r.group[-8:]))
    tab = collections.defaultdict(lambda: [0, 0])
    for w, hit, d, g in rows:
        tab[round(w * 4) / 4][hit] += 1
    print('checks with Maxor inside the beam area, the laser charged, not in an ability: wall-clock since the previous hit')
    for k in sorted(tab):
        if 6 <= k <= 13:
            print('   %5.2f s: hit %3d of %3d' % (k, tab[k][1], sum(tab[k])))
    print('   smallest wall-clock gap with a hit: %.2f s; in ticks those hits came after:' % min(w for w, h, *_ in rows if h),
          H([d for w, h, d, g in rows if h and w < 10.3]))
    # hit-to-hit gaps (all), in ticks and wall clock
    gaps = []
    for r in T:
        hits = r.hits()
        for (h1, L1), (h2, L2) in zip(hits, hits[1:]):
            a = r.ms(h1) if h1 != L1 else r.chat_ms(L1, M.STUN)
            b = r.ms(h2) if h2 != L2 else r.chat_ms(L2, M.STUN)
            if a and b:
                gaps.append((h2 - h1, (b - a) / 1000, r.group[-8:]))
    print('hit -> next hit: ticks', S([g[0] for g in gaps], 0), '| wall s', S([g[1] for g in gaps], 2))
    print('   under 195 ticks:', sorted((g[0], round(g[1], 2), g[2]) for g in gaps if g[0] < 195))
    # region
    pts = region_points(T)
    print('\nbeam area: checks with the laser charged, off cooldown, no ability: %d, hits %d' % (len(pts), sum(p[0] for p in pts)))
    tab = collections.defaultdict(lambda: [0, 0])
    for hit, p, g, rel in pts:
        dh = math.hypot(p[0] - 73.5, p[2] - 73.5)
        if dh < 6:
            tab[(round(dh * 2) / 2, math.floor(p[1]))][hit] += 1
    ds = sorted(set(k[0] for k in tab))
    print('   y \\ horizontal distance from (73.5, 73.5): ' + ' '.join('%6.1f' % d for d in ds))
    for y in sorted(set(k[1] for k in tab)):
        print('   %5d ' % y + ' '.join(('%2d/%-3d' % (tab[(d, y)][1], sum(tab[(d, y)]))) if (d, y) in tab else '   .  ' for d in ds))
    hx = [p[1][0] for p in pts if p[0]]
    hz = [p[1][2] for p in pts if p[0]]
    print('   hit positions: x %.2f..%.2f, z %.2f..%.2f, y %.2f..%.2f' % (min(hx), max(hx), min(hz), max(hz),
          min(p[1][1] for p in pts if p[0]), max(p[1][1] for p in pts if p[0])))
    print('   hits 3+ blocks out:', [(g, rel, tuple(round(v, 2) for v in p)) for hit, p, g, rel in pts
                                   if hit and math.hypot(p[0] - 73.5, p[2] - 73.5) >= 3])
    print('   misses within 3.5 blocks:', [(g, rel, tuple(round(v, 2) for v in p)) for hit, p, g, rel in pts
                                           if not hit and math.hypot(p[0] - 73.5, p[2] - 73.5) < 3.5])
    # abilities and deferred stun lines
    print('\nabilities (taunt lines):')
    ga = collections.defaultdict(list)
    for r in T:
        s = r.summary()
        for n, k in s['taunts']:
            e = [x for x in s['enr'] if x < n]
            refs = [(s['beacon'], 'laser phase (beacon)')] if s['beacon'] else []
            if e:
                refs.append((e[-1], 'enrage'))
            prev = [(m, 'taunt ' + kk) for m, kk in s['taunts'] if m < n]
            refs += prev[-1:]
            ref = max(refs)
            ga['%s after %s' % (k, ref[1])].append(n - ref[0])
    for k in sorted(ga):
        print('   %-32s %s' % (k, H(ga[k])))
    d = []
    for r in T:
        s = r.summary()
        for h, L in r.hits():
            if L - h > 3:
                t = [(n, k) for n, k in s['taunts'] if n <= L]
                d.append((L - h, (L - t[-1][0], t[-1][1]) if t else None, r.group[-8:]))
    print('   stun lines 4+ ticks after their hit (hit from the discharge): %d; line - last taunt:' % len(d),
          H([x[1][0] for x in d if x[1]]), [x[1][1] for x in d if x[1]])
    silent = []
    for r in T:
        s = r.summary()
        if s['bedrock'] and len(s['stuns']) >= 2:
            s2 = [x for x in s['stuns'] if x > s['stuns'][0]][0]
            if s2 > s['bedrock']:
                t = [(n, k) for n, k in s['taunts'] if n < s2]
                silent.append((r.group[-8:], r.rel(s['bedrock']), r.rel(s2), (s2 - t[-1][0], t[-1][1]) if t else None))
    print('   runs killed before their second stun line (the second hit silent, in an ability): %d; line - last taunt:' % len(silent),
          H([x[3][0] for x in silent if x[3]]))
    print('   stun line variants:', collections.Counter(m for r in T for n, m, *_ in r.chat if m in M.STUN))


# ---------------------------------------------------------------------------------------- stun
def sec_stun(T, heads):
    title('stun and enrage')
    dur, dur_ms, first_d = [], [], []
    for r in T:
        s = r.summary()
        hits = r.hits()
        for i, (h, L) in enumerate(hits):
            e = [x for x in s['enr'] if x > L]
            if not e:
                continue
            nxt = hits[i + 1][1] if i + 1 < len(hits) else 10 ** 9
            dur.append(e[0] - L)
            if i == 0 and e[0] < nxt:
                first_d.append(e[0] - L)
    print('stun line -> next enrage line:', S(dur, 0))
    print('   first stun (no other hit before the enrage), in 10s:', H([x // 10 * 10 for x in first_d]))
    print('   longest:', sorted(first_d)[-6:])
    c = collections.Counter(r.gdev(n) for r in T if r.grid() is not None for n in r.summary()['enr'])
    print('enrage lines vs the 10-tick checks:', ' '.join('%+d:%d' % (i, c[i]) for i in range(-5, 5)))
    move = []
    for r in T:
        for e in r.summary()['enr']:
            before = r.pos_interp(e - 1)
            pk = [p for p in r.pkt() if e - 1 <= p[0] <= e + 15]
            if before and pk and r.in_view(e):
                mv = [p for p in pk if math.dist(p[1:4], before) > 0.1]
                if mv and len(pk) >= 2:
                    move.append(mv[0][0] - e)
    print('first move packet after an enrage line:', S(move, 0))
    # head yaw while stunned
    st = [x for x in heads if x['state'] == 'stunned']
    zero = sum(1 for x in st if abs(M.wrap(x['hy'])) < 0.8)
    print('head yaw while stunned: %d of %d samples at 0 (south)' % (zero, len(st)))


# --------------------------------------------------------------------------------------- death
def sec_death(T):
    title('death')
    gone_b, storm_gone, b_hit2, dead_s2, by_size = [], [], [], [], collections.defaultdict(list)
    b_resid = collections.Counter()
    rule = collections.Counter()
    for r in T:
        s = r.summary()
        if s['bedrock'] and s['gone']:
            gone_b.append(s['gone'] - s['bedrock'])
        if s['gone'] and s['storm']:
            storm_gone.append(s['storm'] - s['gone'])
        if s['bedrock'] and r.grid() is not None:
            b_resid[r.gdev(s['bedrock'])] += 1
        hits = r.hits()
        if s['bedrock'] and len(hits) >= 2:
            last = [L for h, L in hits if L <= s['bedrock'] + 1]
            if len(last) >= 2 and s['bedrock'] - last[-1] <= 30:
                b_hit2.append(s['bedrock'] - last[-1])
                by_size[r.size].append(s['bedrock'] - last[-1])
        if len(s['stuns']) >= 2 and s['dead']:
            dead_s2.append(s['dead'] - s['stuns'][1])
        if len(s['stuns']) >= 2 and s['gone']:
            rule[(s['dead'] is not None, s['gone'] - s['stuns'][1] > 84)] += 1
    print('wither despawns - kill (beacon -> bedrock):', H(gone_b))
    print('Storm\'s first line - despawn:', H(storm_gone))
    print('kill vs the 10-tick checks:', ' '.join('%+d:%d' % (i, b_resid[i]) for i in range(-5, 5)))
    print('kill - the stun line before it (second hit):', S(b_hit2, 0), H(b_hit2))
    for k in sorted(by_size):
        print('   %d-player: %s' % (k, sorted(by_size[k])))
    print('"I\'M TOO YOUNG TO DIE AGAIN!" - second stun line:', H(dead_s2))
    print('   (death line said?, despawned more than 84 ticks after the second stun line?):', dict(rule))


# ----------------------------------------------------------------------------------- targeting
def head_samples(T):
    rows = []
    for r in T:
        s = r.summary()
        spans = r.free_spans()
        stunned = []
        for st in s['stuns']:
            e = [x for x in s['enr'] if x > st]
            stunned.append((st, e[0] if e else (s['gone'] or st + 300)))
        by = collections.defaultdict(list)
        for o in r.obs:
            by[o[6]].append(o)
        for rec, O in by.items():
            prev = None
            for o in O:
                n, hy = o[0], o[5]
                if hy is None:
                    prev = None
                    continue
                stable = prev is not None and abs(M.wrap(prev - hy)) < 0.01   # the client's head lerp has finished
                prev = hy
                if not stable:
                    continue
                if r.rel(n) < 165:
                    state = 'intro'
                elif any(a + 2 <= n <= b for a, b in spans):
                    state = 'free'
                elif any(a + 3 <= n <= b for a, b in stunned):
                    state = 'stunned'
                else:
                    continue
                p = r.pos_interp(n) or r.maxor_at(n, 6)
                if not p:
                    continue
                c = {}
                for nm in r.party:
                    q = r.player(nm, n)
                    if q and r.alive(nm, n):
                        c[nm] = q[1:4]
                if c:
                    rows.append(dict(state=state, hy=hy, p=p, c=c))
    return rows


def sec_targeting(heads):
    title('targeting (head yaw)')
    for state in ('free', 'intro', 'stunned'):
        acc = collections.Counter()
        e3 = []
        for x in heads:
            if x['state'] != state:
                continue
            p, c = x['p'], x['c']
            dh = {k: math.hypot(v[0] - p[0], v[2] - p[2]) for k, v in c.items()}
            if any(v < 4 for v in dh.values()):
                continue
            err = {k: abs(M.wrap(x['hy'] - M.bearing(v[0] - p[0], v[2] - p[2]))) for k, v in c.items()}
            d3 = {k: math.dist(v, p) for k, v in c.items()}
            c3, ch = min(d3, key=d3.get), min(dh, key=dh.get)
            e3.append(err[c3])
            A = [k for k in err if err[k] < 4]
            Hh = A[0] if len(A) == 1 and all(err[k] > 10 for k in err if k != A[0]) else None
            if Hh is None:
                acc['head on no single player'] += 1
                continue
            acc['head on one player'] += 1
            acc['...the 3D-closest'] += Hh == c3
            if c3 != ch:
                acc['3D- and horizontally-closest differ'] += 1
                acc['...head on the 3D-closest'] += Hh == c3
                acc['...head on the horizontally-closest'] += Hh == ch
        print('%-8s %s' % (state, dict(acc)))
        if e3:
            print('         error to the 3D-closest: median %.1f deg, within 5 deg %.0f%%' % (statistics.median(e3), 100 * sum(v < 5 for v in e3) / len(e3)))


# ------------------------------------------------------------------------------------ movement
def move_samples(T):
    out = []
    for r in T:
        spans = r.free_spans()
        pk = r.pkt()
        for i in range(1, len(pk)):
            a, b = pk[i - 1], pk[i]
            if a[5] != b[5] or not (1 <= b[0] - a[0] <= 3):
                continue
            if not any(x + 2 <= a[0] and b[0] <= y - 1 for x, y in spans):
                continue
            c = r.closest(a[0], a[1:4])
            if not c:
                continue
            d, name, q = c[0]
            out.append(dict(run=r.group[-8:], n=a[0], dn=b[0] - a[0], pos=a[1:4], disp=math.dist(a[1:4], b[1:4]),
                            v=[(b[k] - a[k]) / (b[0] - a[0]) for k in (1, 2, 3)], tpos=q[1:4], tage=a[0] - q[0],
                            exact=r.own(name), margin=(c[1][0] - d) if len(c) > 1 else 99))
    return out


def aimd(x, h=1.0):
    t, p = x['tpos'], x['pos']
    return math.dist((t[0], t[1] + h, t[2]), p)


def linfit(xs, ys):
    mx, my = statistics.mean(xs), statistics.mean(ys)
    k = sum((a - mx) * (b - my) for a, b in zip(xs, ys)) / sum((a - mx) ** 2 for a in xs)
    c = my - k * mx
    rms = math.sqrt(statistics.mean([(b - c - k * a) ** 2 for a, b in zip(xs, ys)]))
    return c, k, rms


def sec_movement(T):
    title('movement')
    # start from rest
    firsts, steps = [], collections.defaultdict(list)
    for r in T:
        i3 = r.first(M.INTRO_END)
        if i3 is None:
            continue
        mv = [p for p in r.pkt() if i3 + 30 <= p[0] <= i3 + 70 and math.dist(p[1:4], M.MAXOR_SPAWN) > 0.05]
        if len(mv) < 6:
            continue
        firsts.append(mv[0][0] - i3)
        prev = M.MAXOR_SPAWN
        for k, p in enumerate(mv[:6]):
            steps[k].append(math.dist(p[1:4], prev))
            prev = p[1:4]
    print('first move packet, ticks after DON\'T DISAPPOINT:', H(firsts))
    print('distance carried by the k-th move packet after he starts:',
          ', '.join('%d: %.3f' % (k + 1, statistics.median(v)) for k, v in sorted(steps.items())), '(n=%d)' % len(steps[0]))
    D = move_samples(T)
    E = [x for x in D if x['exact'] and x['margin'] > 3 and x['tage'] <= 1 and 3 < aimd(x) < 40]
    print('packet pairs while free: %d, with the target exactly known (own recording, 3+ blocks clear): %d' % (len(D), len(E)))
    # steps carried per packet (with a first law), then the law from two-step packets
    c0, k0 = 0.23, 0.020
    two = [(aimd(x), x['disp'] / 2) for x in E if 1.6 < x['disp'] / (c0 + k0 * aimd(x)) < 2.9]
    c, k, rms = linfit([a for a, b in two], [b for a, b in two])
    print('per-tick step from two-step packets: %.3f + %.4f * d  (d: 3D to target feet + 1), rms %.3f, n=%d' % (c, k, rms, len(two)))
    for h in (0.0, 0.5, 1.0, 1.5, 2.0):
        cc, kk, rr = linfit([aimd(x, h) for x in E if 1.6 < x['disp'] / (c0 + k0 * aimd(x)) < 2.9],
                            [x['disp'] / 2 for x in E if 1.6 < x['disp'] / (c0 + k0 * aimd(x)) < 2.9])
        print('   aim +%.1f: %.3f + %.4f d, rms %.4f' % (h, cc, kk, rr))
    bins = collections.defaultdict(list)
    for a, b in two:
        bins[int(a // 2) * 2].append(b)
    for kk in sorted(bins):
        if len(bins[kk]) >= 5:
            print('   d %2d-%2d  n=%4d  median step %.3f  (law %.3f)' % (kk, kk + 2, len(bins[kk]), statistics.median(bins[kk]), c + k * (kk + 1)))
    far = collections.defaultdict(list)
    for x in D:
        if x['margin'] > 3 and aimd(x) > 20 and 1.6 < x['disp'] / min(c + k * aimd(x), 0.9) < 2.9:
            far[int(aimd(x) // 4) * 4].append(x['disp'] / 2)
    print('far (any target view): ' + ', '.join('d %d-%d: median %.3f max %.3f (n=%d)' % (kk, kk + 4, statistics.median(v), max(v), len(v))
                                               for kk, v in sorted(far.items())))
    # steps per packet
    per = collections.defaultdict(collections.Counter)
    for x in E:
        per[x['dn']][round(x['disp'] / min(0.9, c + k * aimd(x)))] += 1
    for dn in sorted(per):
        print('   packets %d tick(s) apart carry (moves: count) %s' % (dn, dict(sorted(per[dn].items()))))
    # lost moves over chains of consecutive packets
    by = collections.defaultdict(list)
    for x in E:
        by[x['run']].append(x)
    tot = lost = 0
    chains = 0
    for run, v in by.items():
        v.sort(key=lambda x: x['n'])
        ch = []
        for x in v + [None]:
            if x is None or (ch and x['n'] != ch[-1]['n'] + ch[-1]['dn']):
                if len(ch) >= 6:
                    t = sum(y['dn'] for y in ch)
                    m = sum(y['disp'] / min(0.9, c + k * aimd(y)) for y in ch)
                    tot += t
                    lost += t - m
                    chains += 1
                ch = []
            if x is not None and aimd(x) > 5:
                ch.append(x)
    print('chains of 6+ consecutive packets: %d, %d ticks, moves missing %.1f (%.1f%% of ticks)' % (chains, tot, lost, 100 * lost / tot))
    # heading and vertical
    err = collections.defaultdict(list)
    vy = collections.defaultdict(list)
    for x in E:
        dx, dz = x['tpos'][0] - x['pos'][0], x['tpos'][2] - x['pos'][2]
        hv = math.hypot(x['v'][0], x['v'][2])
        if hv > 0.15 and math.hypot(dx, dz) > 4:
            err[int(math.hypot(dx, dz) // 8) * 8].append(abs(M.wrap(M.bearing(x['v'][0], x['v'][2]) - M.bearing(dx, dz))))
        if x['dn'] == 2:
            vy[round(x['tpos'][1] - x['pos'][1])].append(x['v'][1])
    print('heading error to the target by horizontal distance: ' + ', '.join('%d-%d: %.1f' % (kk, kk + 8, statistics.median(v)) for kk, v in sorted(err.items()) if len(v) > 20))
    print('vertical speed by (target y - Maxor y): ' + ', '.join('%+d: %+.3f' % (kk, statistics.median(v)) for kk, v in sorted(vy.items()) if len(v) >= 10))
    # where he stops (free, still >= 12 ticks, target known exactly and still)
    stops = []
    for r in T:
        spans = r.free_spans()
        pk = r.pkt()
        for a, b in zip(pk, pk[1:]):
            if b[0] - a[0] < 12 or a[5] != b[5] or not any(x + 3 <= a[0] and a[0] + 10 <= y for x, y in spans) or not r.in_view(a[0] + 10):
                continue
            c = r.closest(a[0] + 6, a[1:4])
            if not c or not r.own(c[0][1]) or (len(c) > 1 and c[1][0] - c[0][0] < 2):
                continue
            q0 = r.player(c[0][1], a[0] + 1)
            if q0 is None or math.dist(q0[1:4], c[0][2][1:4]) > 0.3:
                continue
            q = c[0][2]
            stops.append((math.hypot(q[1] - a[1], q[3] - a[3]), a[2] - q[2], c[0][0]))
    print('standing still while free (n=%d): horizontal %s | above feet %s | 3D %s' % (
        len(stops), S([s[0] for s in stops], 2), S([s[1] for s in stops], 2), S([s[2] for s in stops], 2)))


# ------------------------------------------------------------------------------------- fastest
def sec_fastest(T):
    title('fastest Maxor (5-player, server-timed)')
    rows = []
    for r in T:
        if r.size != 5:
            continue
        s = r.summary()
        if not s['storm'] or not s['stuns']:
            continue
        h1 = r.hits()[0][0]
        m0, m1 = r.chat_ms(r.s0, M.START), r.chat_ms(s['storm'], M.STORM)
        rows.append(dict(run=r.group[-8:], split=s['storm'] - r.s0, wall=(m1 - m0) / 1000 if m0 and m1 else None,
                         h1=r.rel(h1), B=r.rel(s['bedrock']), BmH=(s['bedrock'] - h1) if s['bedrock'] else None,
                         tail=(s['storm'] - s['bedrock']) if s['bedrock'] else None, acts=[r.rel(a) for a in s['acts'][:2]],
                         enr=r.rel(s['enr'][0]) if s['enr'] else None, taunt=bool([t for t in s['taunts'] if s['bedrock'] and t[0] < s['bedrock']])))
    print('split (first line -> Storm\'s first line), ticks:', S([x['split'] for x in rows], 0))
    print('split, wall-clock s:', S([x['wall'] for x in rows], 2))
    print('first hit:', S([x['h1'] for x in rows], 0), '| at 204-208: %d of %d' % (sum(204 <= x['h1'] <= 208 for x in rows), len(rows)))
    print('kill - first hit:', S([x['BmH'] for x in rows if x['BmH'] is not None and x['BmH'] < 400], 0))
    print('Storm\'s line - kill:', H([x['tail'] for x in rows]))
    loss = collections.defaultdict(list)
    for x in rows:
        if x['BmH'] is None:
            continue
        loss['first hit after 206'].append(max(0, x['h1'] - 206))
        loss['kill after first hit + 201'].append(max(0, x['BmH'] - 201))
        loss['Storm after kill + 102'].append(max(0, x['tail'] - 102))
        loss['split - 509'].append(x['split'] - 509)
    for k, v in loss.items():
        print('   %-28s median %4.0f  mean %5.1f  runs > 5: %3d  total %5d' % (k, statistics.median(v), statistics.mean(v), sum(a > 5 for a in v), sum(a for a in v if a > 0)))
    rows.sort(key=lambda x: x['split'])
    print('fastest by ticks:')
    for x in rows[:14]:
        print('   ', x)
    print('fastest by wall clock:')
    for x in sorted([x for x in rows if x['wall']], key=lambda x: x['wall'])[:8]:
        print('    %s %d ticks %.2f s  hit1 %d kill %s' % (x['run'], x['split'], x['wall'], x['h1'], x['B']))


def main():
    mo, vo, secs = M.args()
    runs = M.load(mo, vo)
    T = [r for r in runs if r.server]
    want = secs or ['data', 'timeline', 'grid', 'crystals', 'laser', 'stun', 'death', 'targeting', 'movement', 'fastest']
    heads = head_samples(T) if ('stun' in want or 'targeting' in want) else []
    for s in want:
        if s == 'data':
            sec_data(runs, T)
        elif s == 'timeline':
            sec_timeline(T)
        elif s == 'grid':
            sec_grid(T)
        elif s == 'crystals':
            sec_crystals(T)
        elif s == 'laser':
            sec_laser(T)
        elif s == 'stun':
            sec_stun(T, heads)
        elif s == 'death':
            sec_death(T)
        elif s == 'targeting':
            sec_targeting(heads)
        elif s == 'movement':
            sec_movement(T)
        elif s == 'fastest':
            sec_fastest(T)


if __name__ == '__main__':
    main()
