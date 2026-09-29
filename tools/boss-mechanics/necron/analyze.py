"""Step 3: the numbers in docs/mechanics/necron.md, from OUT_DIR/events.json.

    python3 analyze.py OUT_DIR [section ...]

Sections: data dialogue movement dps attacks floor end fastest (default: all).
One recording per run: the one with server ticks and the most Necron packets. Timing uses only
runs with server ticks; positions use every run.
"""
import math
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import necronlib as N  # noqa: E402
from necronlib import summ, hist  # noqa: E402

TAUNTS = ('Sometimes when you have a problem, you just need to destroy it all and start again.',
          'WITNESS MY RAW NUCLEAR POWER!')
FIXED = ('You went further than any human before, congratulations.', "I'm afraid, your journey ends now.",
         'Goodbye.', "That's a very impressive trick. I guess I'll have to handle this myself.")
GRID = 20      # ARGH! lands on this grid...
PHASE = 5      # ...at this residue (server ticks after the first line)
AT_MID = 141   # ...no sooner than this many ticks after Necron is put back on mid
# Minecraft yaw of a shot from mid (rounded to 45) -> the lava platform in that direction
PLAT_OF_YAW = {0: 'S', 45: 'SW', -45: 'SE', 135: 'NW', -135: 'NE', 180: 'N', -180: 'N', 90: 'W', -90: 'E'}


def reps(rows, need_st=True):
    best = {}
    for r in rows:
        if need_st and not r['has_st']:
            continue
        k = r['group']
        if k not in best or (r['has_st'], r['npk']) > (best[k]['has_st'], best[k]['npk']):
            best[k] = r
    return list(best.values())


def first(r, text, after=-10 ** 9):
    for n, m in r['lines']:
        if m == text and n > after:
            return n
    return None


def all_of(r, text):
    return [n for n, m in r['lines'] if m == text]


def taunts(r):
    """Lines that are neither the fixed intro, ARGH!, the platform line, nor the death lines."""
    skip = FIXED + ('ARGH!', "Let's make some space!", 'All this, for nothing...',
                    'I understand your words now, my master.')
    return [(n, m) for n, m in r['lines'] if m not in skip]


def exc(r, k):
    return r['exc'][k] if len(r['exc']) > k else None


def argh_slot(b):
    """First grid tick at least AT_MID after b."""
    g = b + AT_MID
    return g + (PHASE - g) % GRID


def sec_data(rows, R):
    print('== data')
    print(len(rows), 'recordings with Necron,', sum(r['has_st'] for r in rows), 'with server ticks;',
          len({r['group'] for r in rows}), 'runs,', len(R), 'with server ticks')
    print('party sizes (runs):', hist(r['party'] for r in R))
    print('died before "All this" (no line):', sum(1 for r in R if first(r, 'All this, for nothing...') is None))


def sec_dialogue(rows, R):
    print('== dialogue (server ticks after the first line)')
    for t in FIXED:
        print('  %-45s %s' % (t[:45], summ([first(r, t) for r in R])))
    gaps = []
    for r in R:
        L = r['lines']
        for a, b in zip(L, L[1:]):
            if a[1] in FIXED[:3]:
                gaps.append(b[0] - a[0])
    print('  gap after each of the first three lines:', summ(gaps))
    g = [r['goldor'] for r in R]
    fg = [[n for n, m in gl if m == 'Necron, forgive me.'] for gl in g]
    dots = [[n for n, m in gl if m == '....'] for gl in g]
    print('  Goldor "Necron, forgive me." relative:', hist(v[0] for v in fg if v))
    print('  Goldor "...." relative:', summ([v[0] for v in dots if v]))
    late = sum(1 for v in dots if v and v[0] > 0)
    print('  runs where Goldor\'s "...." comes after Necron\'s first line (Goldor dialogue backlog):', late)
    core = [r['core'][0] for r in R if r['core']]
    print('  Goldor-core floor (y>=100) removed at:', hist(core))
    t1 = []
    t1ok = 0
    for r in R:
        imp = first(r, FIXED[3])
        e = exc(r, 0)
        tt = taunts(r)
        if imp is None or not e or e[1] is None or not tt:
            continue
        pred = max(imp + 62, e[1])
        t1.append(tt[0][0] - pred)
    print('  first taunt - max(impressive + 62, back-at-mid 1):', hist(t1))
    for k in (0, 1):
        c = {}
        for r in R:
            tt = [m for n, m in taunts(r)]
            if len(tt) > k:
                c[tt[k][:30]] = c.get(tt[k][:30], 0) + 1
        print('  taunt %d:' % (k + 1), c)
    other = {}
    for r in rows:
        for n, m in taunts(r):
            if m not in TAUNTS:
                other[m] = other.get(m, 0) + 1
    print('  other lines (recordings):', other)
    a1 = [all_of(r, 'ARGH!')[0] for r in R if all_of(r, 'ARGH!')]
    a2 = [all_of(r, 'ARGH!')[1] for r in R if len(all_of(r, 'ARGH!')) > 1]
    print('  ARGH! 1:', hist(a1))
    print('  ARGH! 2:', hist(a2))
    sp = [first(r, "Let's make some space!") - all_of(r, 'ARGH!')[0] for r in R
          if all_of(r, 'ARGH!') and first(r, "Let's make some space!") is not None]
    print('  space - ARGH 1:', hist(sp))
    en = [first(r, 'All this, for nothing...') - all_of(r, 'ARGH!')[-1] for r in R
          if first(r, 'All this, for nothing...') is not None]
    print('  All this - last ARGH:', hist(en))
    iu = [first(r, 'I understand your words now, my master.') - first(r, 'All this, for nothing...') for r in rows
          if first(r, 'I understand your words now, my master.') is not None and r['has_st']]
    print('  I understand - All this:', hist(iu))
    tg = []
    for r in R:
        a = all_of(r, 'ARGH!')
        tt = [n for n, m in r['lines'] if a and n < a[0]]
        if a and len(tt) > 0:
            tg.append(a[0] - tt[-1])
    print('  ARGH 1 - the line before it:', summ(tg))
    t2 = []
    for r in R:
        sp = first(r, "Let's make some space!")
        tt = [n for n, m in taunts(r) if sp is not None and n > sp and m in TAUNTS]
        e = [e for e in r['exc'] if sp is not None and e[0] > sp - 30]
        if tt and e and e[0][1] is not None:
            t2.append(tt[0] - max(sp + 62, e[0][1]))
    print('  second taunt - max(space + 62, back-at-mid 2):', hist(t2))
    pair = {}
    for r in R:
        tt = [m[:9] for n, m in taunts(r) if m in TAUNTS]
        if len(tt) >= 2:
            pair[tuple(tt[:2])] = pair.get(tuple(tt[:2]), 0) + 1
    print('  taunt pairs:', pair)
    g = {}
    for r in rows:
        if first(r, 'All this, for nothing...') is not None:
            g.setdefault(r['group'], set()).add(first(r, 'I understand your words now, my master.') is not None)
    print('  runs with "I understand your words now, my master.":', sum(1 for v in g.values() if True in v), 'of', len(g),
          '(siblings disagree in', sum(1 for v in g.values() if len(v) > 1), ')')


def sec_movement(rows, R):
    print('== movement')
    allr = reps(rows, need_st=False)
    sp = [r['spawn'] for r in R if r['spawn'] is not None]
    print('  wither first seen (n):', hist(sp), '(at 54.0, 66.0, 76.0 in every recording)')
    L1 = [exc(r, 0)[0] for r in R if exc(r, 0)]
    print('  leaves mid (L1):', summ(L1), hist(L1))
    # the scripted first steps
    steps = {}
    for r in R:
        e = exc(r, 0)
        if not e:
            continue
        P = e[4]
        for p in P:
            dt = p[0] - e[0]
            if dt <= 12:
                steps.setdefault(dt, []).append(round(math.dist(p[1:], N.MID), 2))
    print('  distance from mid by ticks after L1 (median):',
          ' '.join('%d:%.2f' % (k, sorted(v)[len(v) // 2]) for k, v in sorted(steps.items())))
    # per-tick speed once flying (1-tick packet gaps only, de-interpolated)
    sp1, sp2 = [], []
    for r in allr:
        for k, e in enumerate(r['exc'][:2]):
            P = e[4]
            for a, b in zip(P, P[1:]):
                if b[0] - a[0] in (1, 2) and a[0] - e[0] >= 10:
                    d = math.dist(a[1:], b[1:]) / (b[0] - a[0])
                    if d < 3:
                        (sp1 if k == 0 else sp2).append(round(d, 2))
    print('  speed (blocks/tick) from 10 ticks after leaving, trip 1:', summ(sp1))
    print('  speed trip 2:', summ(sp2))
    for k in (0, 1):
        d = [e[2] for e in (exc(r, k) for r in allr) if e and e[1] is not None]
        print('  trip %d max distance from mid:' % (k + 1), summ(d))
    dive = [(r['id'][-8:], k, e[3]) for r in allr for k, e in enumerate(r['exc']) if e[3] < 50]
    print('  trips below y 50 (down through the arena floor):', dive)
    # heading of trip 1: bearing from mid to where he was put back
    hd = []
    for r in allr:
        e = exc(r, 0)
        if e and e[1] is not None and e[2] > 2:
            p = e[4][-1]
            hd.append(round(math.degrees(math.atan2(-(p[1] - 54), p[3] - 76)) / 10) * 10)
    print('  trip 1 heading (deg, Minecraft yaw; 0 = +z, south):', hist(hd))
    side = []
    for r in allr:
        e = exc(r, 0)
        a = [p for p in e[4] if p[0] - e[0] == 6] if e else []
        if a:
            p = a[0]
            side.append(round(math.degrees(math.atan2(-(p[1] - 54), p[3] - 76)) / 15) * 15)
    print('  opening sidestep direction at L1 + 6 (deg):', hist(side))
    tp = []
    for r in allr:
        for e in r['exc']:
            if e[1] is not None:
                tp.append(round(math.dist(e[4][-1][1:], N.MID), 1))
    print('  jump back to mid (distance covered by the one packet):', summ(tp))


def sec_dps(rows, R):
    print('== what ends each DPS window')
    for k in (0, 1):
        d = [(e[1] - e[0]) for e in (exc(r, k) for r in R) if e and e[1] is not None]
        print('  trip %d duration (B - L):' % (k + 1), summ(d))
    B1 = [(exc(r, 0)[1], all_of(r, 'ARGH!')[0]) for r in R if exc(r, 0) and exc(r, 0)[1] and all_of(r, 'ARGH!')]
    print('  B1 (back at mid):', summ([b for b, a in B1]))
    ok = bad = 0
    for b, a in B1:
        g = argh_slot(b)
        said = max(g, 330)  # the first taunt (>= 248) + 82
        if abs(said - a) <= 2:
            ok += 1
        else:
            bad += 1
    print('  ARGH 1 = max(first %d-grid tick (== %d) >= B1 + %d, 330): %d fit (+-2), %d not' % (GRID, PHASE, AT_MID, ok, bad))
    rows2 = []
    for r in R:
        a = all_of(r, 'ARGH!')
        sp = first(r, "Let's make some space!")
        if len(a) < 2 or sp is None:
            continue
        e = [e for e in r['exc'] if e[0] > sp - 30]
        if not e or e[0][1] is None:
            continue
        rows2.append((e[0][0], e[0][1], a[1]))
    ok = [abs(argh_slot(b) - a) <= 2 for L, b, a in rows2]
    print('  ARGH 2 = first grid tick >= B2 + %d: %d of %d fit (+-2)' % (AT_MID, sum(ok), len(ok)))
    print('    misses (L2, B2, ARGH2):', [x for x, o in zip(rows2, ok) if not o])
    print('  ARGH 2 - B2:', summ([a - b for L, b, a in rows2]))
    th = {}
    for L, b, a in rows2:
        th.setdefault(a // 5 * 5, []).append(b)
    print('  B2 range per ARGH 2 slot:', {k: (min(v), max(v), len(v)) for k, v in sorted(th.items())})
    th = {}
    for b, a in B1:
        th.setdefault(a // 5 * 5, []).append(b)
    print('  B1 range per ARGH 1 slot:', {k: (min(v), max(v), len(v)) for k, v in sorted(th.items())})


def sec_attacks(rows, R):
    print('== attacks')
    b1, b2, gaps, l2 = [], [], [], []
    for r in R:
        fb = [s[0] for s in r['shots'].get('fireball', []) if s[1] < 8]
        a = all_of(r, 'ARGH!')
        f1 = [n for n in fb if 40 <= n <= 150]
        if f1:
            b1.append((f1[0], len(f1)))
            gaps += [b - a for a, b in zip(f1, f1[1:])]
        if a:
            f2 = [n for n in fb if a[0] - 30 <= n <= a[0] + 130]
            if f2:
                b2.append(f2[0] - a[0])
                sp = first(r, "Let's make some space!")
                e = [e for e in r['exc'] if sp is not None and e[0] > sp - 30]
                if e:
                    l2.append(e[0][0] - f2[0])
    print('  fireball volley 1 (first, count):', hist(b1))
    v1 = [PLAT_OF_YAW.get(round(s[3] / 45) * 45) for r in R for s in r['shots'].get('fireball', [])
          if s[1] < 8 and 40 <= s[0] <= 150 and s[3] is not None]
    print('  volley 1 flight direction (platform it points at):', hist(v1))
    match, brk = {}, []
    for r in R:
        a = all_of(r, 'ARGH!')
        sp = first(r, "Let's make some space!")
        if not a or sp is None:
            continue
        f2 = [s for s in r['shots'].get('fireball', []) if s[1] < 8 and a[0] - 30 <= s[0] <= a[0] + 130]
        br = [(n, p) for n, k, p in r['breaks'] if n > sp - 5]
        d = [PLAT_OF_YAW.get(round(s[3] / 45) * 45) for s in f2[:6] if s[3] is not None]
        if f2 and br:
            brk.append(br[0][0] - f2[0][0])
            if d:
                top = max(set(d), key=d.count)
                k = 'volley 2 aimed at the platform that breaks' if top == br[0][1] else 'elsewhere'
                match[k] = match.get(k, 0) + 1
    print('  volley 2 (first 6 shots, majority direction):', match)
    print('  platform break - volley 2 first fireball:', hist(brk))
    print('  fireball spacing:', hist(gaps))
    print('  volley 2 first - ARGH 1:', summ(b2))
    print('  L2 (leaves mid again) - volley 2 first:', hist(l2))
    s1, s2 = [], []
    for r in R:
        sk = sorted({s[0] for s in r['shots'].get('wither_skull', []) if s[2] < 5})
        for k, e in enumerate(r['exc'][:2]):
            s = [n for n in sk if e[0] - 3 <= n <= (e[1] if e[1] is not None else 10 ** 9)]
            if s:
                (s1 if k == 0 else s2).append(s[0] - e[0])
    print('  first skull pair - L1:', hist(s1), ' - L2:', hist(s2))
    tnt1, tntd = [], []
    for r in R:
        sp = first(r, "Let's make some space!")
        en = first(r, 'All this, for nothing...')
        t = [s[0] for s in r['shots'].get('tnt', []) if s[2] < 3]
        if sp is not None:
            tnt1.append(len([n for n in t if sp - 10 <= n <= sp + 80]))
        if en is not None:
            d = [n for n in t if n > en]
            if d:
                tntd.append((d[0] - en, len([n for n in d if n == d[0]])))
    print('  single TNT at Necron near "space" (count per run):', hist(tnt1))
    print('  death TNT (after All this, count):', hist(tntd))
    fr = [n for r in R for n in r['frenzy']]
    print('  Nuclear Frenzy hits on the recorder:', len(fr), 'in', sum(1 for r in R if r['frenzy']), 'runs; times', summ(fr))
    print('  Nuclear Frenzy hit tick mod 20:', hist(n % 20 for n in fr))
    where = {}
    for r in R:
        a = all_of(r, 'ARGH!')
        waits = [(e[1], a[k]) for k, e in enumerate(r['exc'][:2]) if e[1] is not None and len(a) > k]
        for n in r['frenzy']:
            k = 'at mid between B and ARGH' if any(b <= n <= g for b, g in waits) else 'elsewhere'
            where[k] = where.get(k, 0) + 1
    print('  Nuclear Frenzy hits:', where)


def sec_floor(rows, R):
    print('== floor')
    c, dt, s100 = {}, [], []
    for r in R:
        sp = first(r, "Let's make some space!")
        for n, k, p in r['breaks']:
            if sp is not None and n > sp - 5:
                c[p] = c.get(p, 0) + 1
                dt.append(n - sp)
                break
        for n, k, p in r['breaks']:
            if p == 'S' and 90 <= n <= 110:
                s100.append(n)
    print('  platform removed after "Let\'s make some space!":', c)
    print('  break - space line:', summ(dt))
    print('  S platform removed at ~+100 in', len(s100), 'runs:', hist(s100))


def sec_end(rows, R):
    print('== end')
    g = [r['gone'] - first(r, 'All this, for nothing...') for r in R
         if r['gone'] is not None and first(r, 'All this, for nothing...') is not None]
    print('  wither gone - All this:', summ(g))
    s = [r['score'] - first(r, 'All this, for nothing...') for r in R
         if r['score'] is not None and first(r, 'All this, for nothing...') is not None]
    print('  Team Score line - All this:', summ(s))


def sec_fastest(rows, R):
    print('== fastest')
    sp = sorted((first(r, 'All this, for nothing...'), r['id']) for r in R if first(r, 'All this, for nothing...') is not None)
    print('  Necron split (All this - first line):', summ([a for a, _ in sp]))
    print('  best:', sp[:8])
    print('  histogram:', hist([a for a, _ in sp]))
    a1 = [all_of(r, 'ARGH!')[0] for r in R if all_of(r, 'ARGH!')]
    a2 = [all_of(r, 'ARGH!')[1] for r in R if len(all_of(r, 'ARGH!')) > 1]
    print('  ARGH 1 at 327-332:', sum(1 for a in a1 if a <= 332), 'of', len(a1), '; ARGH 2 at 543-547:',
          sum(1 for a in a2 if a <= 547), 'of', len(a2))
    why = {}
    for r in R:
        a = all_of(r, 'ARGH!')
        sp = first(r, "Let's make some space!")
        e = [e for e in r['exc'] if sp is not None and e[0] > sp - 30]
        if len(a) < 2 or not e or e[0][1] is None:
            continue
        L2, B2 = e[0][0], e[0][1]
        if a[1] <= 547:
            k = 'ARGH 2 at 545'
        elif a[0] > 332:
            k = 'late ARGH 1'
        elif L2 + 1 + AT_MID > 547:
            k = 'left mid too late (L2 %s)' % ('> 403')
        else:
            k = 'trip 2 too long'
        why[k] = why.get(k, 0) + 1
    print('  why ARGH 2 missed 545:', why)
    loss1 = sum(a - 330 for a in a1 if a > 332)
    loss2 = sum(a2[i] - a1[i] - 215 for i in range(min(len(a1), len(a2))) if a2[i] - a1[i] > 217)
    print('  ticks lost to a late ARGH 1: %d; to a late ARGH 2 (beyond ARGH 1 + 215): %d' % (loss1, loss2))


SECTIONS = {'data': sec_data, 'dialogue': sec_dialogue, 'movement': sec_movement, 'dps': sec_dps,
            'attacks': sec_attacks, 'floor': sec_floor, 'end': sec_end, 'fastest': sec_fastest}


def main():
    out_dir = sys.argv[1]
    rows = json.load(open(os.path.join(out_dir, 'events.json')))
    R = reps(rows)
    for s in (sys.argv[2:] or list(SECTIONS)):
        SECTIONS[s](rows, R)


if __name__ == '__main__':
    main()
