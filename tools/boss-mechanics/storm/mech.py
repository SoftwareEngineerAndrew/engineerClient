#!/usr/bin/env python3
"""Storm (F7 phase 2) mechanics from Better PF recordings: the numbers in docs/mechanics/storm.md.

usage: mech.py OUT_DIR [section ...]     sections: pillars pads lightning taunts later pin death
       mech.py OUT_DIR geometry DATA_DIR  the pads' blocks, from the first recording that captured them

OUT_DIR must hold tools/boss-movement's tracks (OUT_DIR/tracks, from its extract.py + tracks.py) and
this tool's extract (OUT_DIR/storm-mech, from extract.py). The joined runs are cached in
OUT_DIR/storm-mech/runs.pkl (delete it after re-extracting). Only runs whose every recording has
server ticks are used for timing.
"""
import collections
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stormlib import (B, R, PILLARS, PADS, POINT, med, pct, spearman, load, ev, evs, crushes, grid_of,  # noqa: E402
                      storm_pos, headyaw_at, living, self_at, run_bottoms, bottom_at, resets, batches,
                      pad_starts, pillar_state, crush_pillar, rule_at, on_pad, segments, mage_beams)

SCRIPTED = {R.STORM_START, R.STORM_DEAD, '[BOSS] Storm: At least my son died by your hands.', *R.STORM_LIGHTNING,
            *R.STORM_CRUSHED, "[BOSS] Storm: Don't boast about beating this simple-minded Wither.",
            '[BOSS] Storm: My abilities are unparalleled, in many ways I am the last bastion.',
            '[BOSS] Storm: The memory of your death will be your fondest, focus up!',
            '[BOSS] Storm: The power of lightning is quite phenomenal. A single strike can vaporize a person whole.',
            "[BOSS] Storm: I'd be happy to show you what that's like!"}


def dist_line(name, v, nd=0):
    if not v:
        print('  %s: none' % name)
        return
    f = '%.' + str(nd) + 'f'
    print(('  %s: n %d, median ' + f + ', p10 ' + f + ', p90 ' + f + ', min ' + f + ', max ' + f) %
          (name, len(v), med(v), pct(v, 10), pct(v, 90), min(v), max(v)))


def hist(v, width=1, cap=None):
    c = collections.Counter(min(x, cap) // width * width if cap is not None else x // width * width for x in v)
    return ' '.join('%s:%d' % (k, c[k]) for k in sorted(c))


# ------------------------------------------------------------------ pillars

def pillars(runs):
    print('== pillars (server-timed runs: %d)' % len(runs))
    first = collections.Counter()
    moved = collections.Counter()
    for run in runs:
        bt = run_bottoms(run)
        for p in PILLARS:
            s = bt[p]
            moved[(p, bool(s and any(b is not None and b < 175 for _, b in s)))] += 1
            if s and s[0][1] == 174:
                first[(p, (s[0][0] + 1) % 20)] += 1
    print('  pillars that ever went below 175 (runs):', {p: moved[(p, True)] for p in PILLARS})
    print('  first step from the initial 175, phase of its first layer (n+1 mod 20):',
          {p: hist([k[1] for k, v in first.items() if k[0] == p for _ in range(v)]) for p in ('purple', 'yellow', 'green')})
    # down-step batches
    lens = collections.Counter()
    phase = collections.Counter()
    gaps = []
    for run in runs:
        bt = run_bottoms(run)
        for p in PILLARS:
            for b in batches(bt[p]):
                if b[1] in (189, 190):
                    continue
                lens[b[3]] += 1
                phase[(b[0] + 1) % 20] += 1
            s = bt[p]
            for (a, b0), (c, b1) in zip(s, s[1:]):
                if b0 is not None and b1 is not None and b1 == b0 - 1 and c - a <= 6:
                    gaps.append(c - a)
    print('  down-step gaps (ticks):', hist(gaps))
    print('  descents, steps each:', hist([k for k, v in lens.items() for _ in range(v)]))
    print('  descents, phase of the first layer (n+1 mod 20):', hist([k for k, v in phase.items() for _ in range(v)]))
    # resets after crushes
    rel = []
    after = 0
    spent_moves = 0
    for run in runs:
        bt = run_bottoms(run)
        cr = crushes(run)
        for p in PILLARS:
            for r in resets(bt, p):
                before = [c for c in cr if c <= r]
                if before:
                    rel.append(r - before[-1])
                later = [n for n, b in bt[p] if n > r + 2]
                after += 1
                spent_moves += bool([n for n in later if bottom_at(bt, p, n) is not None and bottom_at(bt, p, n) < 186])
    dist_line('pillar reset (layers vanish at once), ticks after the last crush line', rel)
    print('  resets: %d; pillars that went below 186 again afterwards: %d' % (after, spent_moves))
    # the floor cycle
    a, b, c_, d_ = [], [], [], []
    for run in runs:
        bt = run_bottoms(run)
        for p, s in bt.items():
            for i, (n, bb) in enumerate(s):
                if bb == 169 and i > 0 and s[i - 1][1] == 170 and i + 1 < len(s) and s[i + 1][1] == 170:
                    a.append(s[i + 1][0] - n)
                    k = next((k for k in range(i + 1, len(s)) if s[k][1] is None), None)
                    if k is None:
                        continue
                    b.append(s[k][0] - s[i + 1][0])
                    if k + 1 < len(s) and s[k + 1][1] == 188:
                        c_.append(s[k + 1][0] - s[k][0])
                        m = next((m for m in range(k + 1, len(s)) if s[m][1] == 186), None)
                        if m:
                            d_.append(s[m][0] - n)
    dist_line('floor (169) -> first layer drawn up', a)
    dist_line('first layer drawn up -> fewer than 20 blocks left in layer 177+ (reads "drawn up")', b)
    dist_line('drawn up -> first step back down (to 188)', c_)
    dist_line('floor -> back at 186', d_)
    up = []
    for run in runs:
        bt = run_bottoms(run)
        for p, s in bt.items():
            for (a1, b0), (c1, b1) in zip(s, s[1:]):
                if b0 is not None and b1 is not None and b1 == b0 + 1 and b0 >= 169:
                    up.append(c1 - a1)
    print('  drawing-up gaps (ticks):', hist(up, cap=10))


# ------------------------------------------------------------------ pads

def pads(runs):
    print('== pads')
    print('  pad squares (x0, z0) 7x7:', PADS)
    table = collections.Counter()
    conf = {}
    for run in runs:
        bt = run_bottoms(run)
        d = ev(run, R.STORM_DEAD) or 2000
        starts = {p: pad_starts(bt, p) for p in PILLARS}
        for g in range(19, d + 40, 20):
            for p in PADS:
                st, _ = pillar_state(bt, p, g)
                for lag in range(0, 5):
                    for box in ((0, 7), (-1, 8)):
                        on = False
                        near = False
                        for rec in run['recs']:
                            q = self_at(run, rec, g - lag)
                            if not q:
                                continue
                            x0, z0 = PADS[p]
                            if x0 - 2 <= q[2] <= x0 + 9 and z0 - 2 <= q[4] <= z0 + 9 and 165 < q[3] < 175:
                                near = True
                            if x0 + box[0] <= q[2] <= x0 + box[1] and z0 + box[0] <= q[4] <= z0 + box[1] and 169.9 <= q[3] <= 170.6:
                                on = True
                        if not near:
                            continue
                        if lag == 0 and box == (0, 7):
                            table[(p, st, on, g in starts[p])] += 1
                        if st in ('armed', 'init') and p != 'red':
                            k = (lag, box)
                            c = conf.setdefault(k, [0, 0, 0, 0])
                            c[(0 if on else 2) + (0 if g in starts[p] else 1)] += 1
    print('  a recorder near a pad at a check: (pillar, state, on the pad, a descent began) -> count')
    for k in sorted(table, key=str):
        print('   ', k, table[k])
    print('  armed/initial Purple/Yellow/Green: recorder position lag, pad box -> on&start, on&none, off&start, off&none')
    for k in sorted(conf):
        print('   ', k, conf[k])


def geometry(data_dir):
    print('== pad geometry (y 169, from the arena capture)')
    names = sorted(os.listdir(os.path.join(data_dir, 'runs')))
    W = None
    for f in names:
        L = R.read_lines(os.path.join(data_dir, 'runs', f))
        libs = [l for l in L if l['k'] == 'lib' and l['key'].startswith('Boss|F7')]
        if not libs:
            continue
        W = {}
        for l in libs:
            x0, y0, z0, w, d = l['x0'], l['y0'], l['z0'], l['w'], l['d']
            i = 0
            for k in range(0, len(l['rle']), 2):
                c, pi = l['rle'][k], l['rle'][k + 1]
                for _ in range(c):
                    y = i // (w * d)
                    if pi and y0 + y == 169:
                        W[(x0 + i % w, z0 + (i // w) % d)] = l['pal'][pi]
                    i += 1
        print('  from', f)
        break
    if W is None:
        print('  no recording captured the arena')
        return
    code = [('terracotta', 'T'), ('stained_glass', 'G'), ('stairs', 's')]
    for p, (x0, z0) in PADS.items():
        print('  %s pad, x %d..%d (T terracotta, G stained glass, s stairs):' % (p, x0 - 2, x0 + 8))
        for z in range(z0 - 2, z0 + 9):
            row = ''
            for x in range(x0 - 2, x0 + 9):
                s = W.get((x, z), 'air').split('[')[0]
                row += next((c for k, c in code if s.endswith(k)), '.')
            print('    z %3d %s' % (z, row))


# ------------------------------------------------------------------ lightning and attacks

def under_pillar(q):
    return any(x0 <= q[2] < x1 + 1 and z0 <= q[4] < z1 + 1 for x0, x1, z0, z1 in PILLARS.values())


def lightning(runs):
    print('== lightning and other attacks')
    L1 = [ev(r, R.STORM_LIGHTNING) for r in runs if ev(r, R.STORM_LIGHTNING) is not None]
    dist_line('lightning line (first), ticks after his first line', L1)
    offs = []
    per_rec = collections.Counter()
    safe = collections.Counter()
    dmg = []
    for run in runs:
        Ls = evs(run, R.STORM_LIGHTNING)
        for rec in run['recs']:
            hits = [(n, float(re.search(r'for ([\d,.]+)', m).group(1).replace(',', '')))
                    for n, m, r in run['chat'] if r == rec and m.startswith("Storm's Giga Lightning")]
            per_rec[len(hits)] += 1
            dmg += [h[1] for h in hits]
            for L in Ls[:1]:
                offs += [n - L for n, _ in hits if L <= n <= L + 40]
                for k, off in ((0, 10), (1, 20)):
                    hit = any(L + off - 5 <= n <= L + off + 5 for n, _ in hits)
                    q = self_at(run, rec, L + off)
                    if q:
                        safe[(k, 'under a pillar, on the floor' if under_pillar(q) and 168.5 <= q[3] <= 170 else 'elsewhere', hit)] += 1
    print('  Giga Lightning lines per recording:', dict(sorted(per_rec.items())))
    print('  Giga Lightning, ticks after the lightning line:', hist(offs))
    dist_line('Giga Lightning damage per strike', dmg)
    print('  (strike, where the recorder stood, hit) -> recordings:')
    for k in sorted(safe, key=str):
        print('   ', k, safe[k])
    print('  runs with a second lightning line (all runs, client ticks where no st):')
    for run in ALL:
        Ls = evs(run, R.STORM_LIGHTNING)
        strikes = [n for n, m, r in run['chat'] if m.startswith("Storm's Giga Lightning") and n > (Ls[0] + 60 if Ls else 0)]
        if len(Ls) > 1 or strikes:
            print('    %s st=%s lines %s (+%s) late strikes %s crushes %s enrage %s' %
                  (run['group'][-8:], run['has_st'], Ls, [b - a for a, b in zip(Ls, Ls[1:])], sorted(set(round(s) for s in strikes)),
                   crushes(run), evs(run, R.STORM_ENRAGED)))
    for kind in ('Static Field', 'Lightning Fireball', 'Frenzy'):
        ts, ds = [], []
        for run in runs:
            for n, m, rec in run['chat']:
                if m.startswith("Storm's " + kind):
                    ts.append(n)
                    q, P = self_at(run, rec, n), storm_pos(run, n, 20)
                    if q and P:
                        ds.append(math.dist(q[2:5], P))
                    if kind != 'Frenzy':
                        dmg_ = m
        dist_line("Storm's %s: tick" % kind, ts)
        dist_line("Storm's %s: recorder's distance from Storm" % kind, ds, 1)


# ------------------------------------------------------------------ taunts

def taunts(runs):
    print('== taunts')
    c = collections.Counter()
    first, gaps, alive, said = [], [], 0, 0
    for run in runs:
        t = sorted((v, m) for m, vs in run['events'].items() if m.startswith('[BOSS] Storm') and m not in SCRIPTED for v in vs)
        for v, m in t:
            c[m[14:]] += 1
        d = ev(run, R.STORM_DEAD) or 10 ** 9
        if d > 910 and ev(run, R.STORM_LIGHTNING) is not None:
            alive += 1
            said += bool(t)
        if t and crushes(run):
            first.append(t[0][0])
        gaps += [b[0] - a[0] for a, b in zip(t, t[1:])]
    for m, k in c.most_common():
        print('  %3d  %s' % (k, m))
    dist_line('first taunt, ticks after his first line (runs with a crush)', first)
    print('  gaps between taunts:', sorted(gaps))
    print('  runs alive past t 910: %d, with a taunt: %d' % (alive, said))
    print('  "BEGONE PILLAR!": pillar changes within 20 ticks, Storm, the crush rule:')
    for run in ALL:
        for v in run['events'].get('[BOSS] Storm: BEGONE PILLAR!', []):
            bt = run_bottoms(run)
            ch = {p: [(n - v, b) for n, b in bt[p] if abs(n - v) <= 20] for p in PILLARS}
            P = storm_pos(run, v, 10)
            print('    %s t %d storm %s rule %s changes %s' % (run['group'][-8:], v, P and tuple(round(x, 1) for x in P),
                                                          rule_at(run, grid_of(v + 2)), {k: x for k, x in ch.items() if x}))


# ------------------------------------------------------------------ after the flight

def later(runs):
    print('== after the flight (and the non-scripted moves)')
    seg_n = collections.Counter()
    lens = []
    for run in runs:
        s = segments(run)
        for ph, a, b, info in s:
            if ph == 'flight':
                seg_n[('Purple->Yellow flight', any(x[0] == 'later' and x[1] == b for x in s))] += 1
            if ph == 'later' and info['at']:
                lens.append(b - a)
            if ph == 'later' and not info['at']:
                seg_n[('chase after a crush on %s' % info['from'],)] += 1
    print('  (flight, arrived before the next crush) / chases after non-Purple crushes:', dict(seg_n))
    dist_line('time at Yellow before the next crush/death, ticks from arriving within 3 blocks', lens)
    print('  of those >= 20 ticks: %d' % sum(x >= 20 for x in lens))
    # where the head leaves the flight line
    dsw = []
    for run in runs:
        for ph, a, b, info in segments(run, arrive=0.01):
            if ph != 'flight':
                continue
            pt = POINT[info['to']]
            locked, cnt, first, sw = False, 0, None, None
            for n in range(int(a) + 5, int(min(b, a + 400))):
                hy, p = headyaw_at(run, n), storm_pos(run, n, 10)
                if hy is None or not p:
                    continue
                e2 = abs(B.angdiff(hy, B.bearing(pt[0] - p[0], pt[1] - p[2])))
                if e2 < 8:
                    locked, cnt = True, 0
                    continue
                if locked and e2 > 25:
                    cnt += 1
                    first = n if cnt == 1 else first
                    if cnt >= 3:
                        sw = first
                        break
                else:
                    cnt = 0
            if sw is not None:
                p = storm_pos(run, sw - 3, 10)
                if p:
                    dsw.append(math.hypot(p[0] - pt[0], p[2] - pt[1]))
    dist_line('distance (horizontal) from the point 3 ticks before his head leaves the flight line', dsw, 2)
    # heading and speed by phase
    rows = collections.defaultdict(list)
    for run in runs:
        pk = run['pkt']
        for ph, a, b, info in segments(run):
            for i, p in enumerate(pk):
                if not a + 2 <= p[0] < b - 2:
                    continue
                v = B.velocity(pk, i, 4)
                L = living(run, p[0])
                if v is None or len(L) < 1:
                    continue
                P = p[1:4]
                L.sort(key=lambda z: math.dist(z[1], P))
                q = L[0][1]
                if len(L) > 1 and math.dist(L[1][1], P) - math.dist(q, P) < 1:
                    continue
                hy = headyaw_at(run, p[0])
                hsp = math.hypot(v[0], v[2])
                brg = B.bearing(q[0] - P[0], q[2] - P[2])
                key = ph if ph != 'later' else ('later at Yellow' if info['at'] else 'chase after a crush on ' + str(info['from']))
                rows[key].append(dict(d=math.dist(q, P), hd=math.hypot(q[0] - P[0], q[2] - P[2]), dy=q[1] - P[1], sp=math.sqrt(sum(x * x for x in v)), hsp=hsp,
                                      eh=abs(B.angdiff(hy, brg)) if hy is not None else None,
                                      ev=abs(B.angdiff(B.bearing(v[0], v[2]), brg)) if hsp > 0.1 else None))
    for key, v in rows.items():
        eh = [x['eh'] for x in v if x['eh'] is not None and x['hd'] > 3]
        print('  %s: %d packets; head to the 3D-closest player: median %.1f deg, %.0f%% within 10 deg' %
              (key, len(v), med(eh) or -1, 100 * sum(e < 10 for e in eh) / max(1, len(eh))))
        for lo in (2, 5, 8, 11, 14, 17, 20, 30, 40):
            hi = {20: 30, 30: 40, 40: 50}.get(lo, lo + 3)
            xs = [x for x in v if lo <= x['hd'] < hi and x['ev'] is not None and x['ev'] < 20]
            if len(xs) >= 5:
                print('     closest %2d-%2d blocks away (horizontal), moving at him: n %3d, speed %.2f (horizontal %.2f), him %.1f below' %
                      (lo, hi, len(xs), med([x['sp'] for x in xs]), med([x['hsp'] for x in xs]), -med([x['dy'] for x in xs])))
        xs = [x for x in v if 3 < x['hd'] < 25 and x['ev'] is not None and x['ev'] < 20]
        if len(xs) > 10:
            n = len(xs)
            mx, my = sum(x['hd'] for x in xs) / n, sum(x['hsp'] for x in xs) / n
            bb = sum((x['hd'] - mx) * (x['hsp'] - my) for x in xs) / sum((x['hd'] - mx) ** 2 for x in xs)
            print('     fit (3-25 blocks): horizontal speed = %.3f + %.4f * horizontal distance' % (my - bb * mx, bb))


# ------------------------------------------------------------------ the pin

def pin(runs):
    print('== the pin after crush 1 (crush line -> "Storm is enraged!")')
    rows = []
    for run in runs:
        cr = crushes(run)
        en = evs(run, R.STORM_ENRAGED)
        if not cr:
            continue
        c = cr[0]
        e = next((x for x in en if x >= c), None)
        if e is None or (len(cr) > 1 and e > cr[1]):
            continue
        P = storm_pos(run, c + 4, 10)
        L = living(run, c + 4)
        beams = mage_beams(run, c - 80, e + 2)
        mage = [n for n, k in run['classes'].items() if k == 'MAGE']
        bt = run_bottoms(run)
        pil = crush_pillar(run, c)
        rs = [r for r in resets(bt, pil) if r > c] if pil else []
        rows.append(dict(pin=e - c, c=c, P=P, beams=beams, mage=mage[0] if mage else '-', alive=len(L), run=run['group'][-8:],
                         near10=sum(1 for _, q, _, _ in L if P and math.dist(q, P) < 10),
                         near20=sum(1 for _, q, _, _ in L if P and math.dist(q, P) < 20),
                         nearest=min((math.dist(q, P) for _, q, _, _ in L), default=None) if P else None,
                         reset=rs[0] - c if rs else None, y=P[1] if P else None))
    v = [r['pin'] for r in rows]
    dist_line('pin, ticks', v)
    print('  histogram (2-tick bins, 60 = 60+):', hist(v, 2, 60))
    print('  pins <= 4: %d of %d' % (sum(x <= 4 for x in v), len(v)))
    for k in ('near10', 'near20', 'alive', 'nearest', 'c', 'y'):
        xs = [r for r in rows if r[k] is not None]
        print('  Spearman(pin, %s) = %.2f (n %d)' % (k, spearman([r[k] for r in xs], [r['pin'] for r in xs]), len(xs)))
    print('  by the party\'s Mage:')
    by = collections.defaultdict(list)
    for r in rows:
        by[r['mage']].append(r['pin'])
    for m, ps in sorted(by.items(), key=lambda kv: -len(kv[1])):
        print('    %-18s n %2d median %5.1f  %s' % (m, len(ps), med(ps), sorted(ps)))
    print('  the recording Mage\'s beams (arm swings aimed within 3 deg of Storm, <= 45 blocks):')
    c = collections.Counter()
    for r in rows:
        for rec, bs in r['beams'].items():
            pre = [n - r['c'] for n, hit in bs if hit and n < r['c']]
            post = [n - r['c'] for n, hit in bs if hit and n >= r['c']]
            if not post:
                c['no beam on target after the crush'] += 1
                continue
            k = 'pin = first beam + 0/1' if r['pin'] - post[0] in (0, 1) else ('pin ended before any beam' if r['pin'] < post[0] else 'earlier beams did not end it')
            c[k] += 1
            c['beams on target in the 80 ticks before the crush: %s' % bool(pre)] += 1
            if k == 'earlier beams did not end it':
                print('    %s pin %d, beams at %s, pillar reset at +%s' % (r['run'], r['pin'], post, r['reset']))
    for k in sorted(c):
        print('   ', k, c[k])
    zero = [r for r in rows if r['pin'] == 0]
    print('  0-tick pins:', [(r['run'], {k: [n - r['c'] for n, h in b if h] for k, b in r['beams'].items()}) for r in zero])
    late = [r['pin'] - r['reset'] for r in rows if r['reset'] is not None and r['pin'] >= r['reset'] - 3]
    print('  pins outlasting the pillar reset (crush + ~20): enrage - reset:', hist(late, cap=40))


# ------------------------------------------------------------------ death

def death(runs):
    print('== death')
    c = collections.Counter()
    after2, silent = [], []
    for run in ALL:
        cr = crushes(run)
        en = evs(run, R.STORM_ENRAGED)
        d = ev(run, R.STORM_DEAD)
        if d is None:
            c[('no death line', len(cr))] += 1
            continue
        crb = [x for x in cr if x <= d]
        pinned = len([x for x in en if x <= d]) < len(crb)
        c[(len(crb), 'pinned at death' if pinned else 'free at death')] += 1
        if len(crb) >= 2 and run['has_st']:
            after2.append(d - crb[1])
            if any(e > crb[1] for e in en):
                c['enrage after crush 2'] += 1
        if len(crb) == 1:
            bt = run_bottoms(run)
            rs = [(p, r - d) for p in PILLARS for r in resets(bt, p) if d - 45 <= r <= d + 30 and r > crb[0] + 25]
            rule = [(g - d, rule_at(run, g)[1]) for g in (grid_of(d) - 20, grid_of(d))]
            silent.append((run['group'][-8:], run['has_st'], d, rs, rule))
    for k in sorted(c, key=str):
        print('  ', k, c[k])
    dist_line('death line - crush 2 line (server-timed)', after2)
    print('  histogram:', hist(after2, 2, 60))
    print('  phase of the death line (n+1 mod 20):', hist([(ev(r, R.STORM_DEAD) + 1) % 20 for r in runs if ev(r, R.STORM_DEAD) is not None]))
    moved = []
    for run in runs:
        cr, d = crushes(run), ev(run, R.STORM_DEAD)
        if len(cr) >= 2 and d:
            a, b = storm_pos(run, cr[1] + 5, 10), storm_pos(run, d - 1, 10)
            if a and b:
                moved.append(math.dist(a, b))
    print('  moved between crush 2 + 5 and death: max %.2f blocks (n %d)' % (max(moved), len(moved)))
    print('  deaths with one crush line: pillar resets near death (pillar, reset - death) and the crush rule at the checks before:')
    for s in silent:
        print('   ', s)


ALL = []


def main():
    global ALL
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    secs = sys.argv[2:] or ['pillars', 'pads', 'lightning', 'taunts', 'later', 'pin', 'death']
    if secs[0] == 'geometry':
        geometry(secs[1])
        return
    ALL = load(out)
    runs = [r for r in ALL if r['has_st']]
    print('runs with Storm: %d, server-timed: %d' % (len(ALL), len(runs)))
    for s in secs:
        globals()[s](runs)


if __name__ == '__main__':
    main()
