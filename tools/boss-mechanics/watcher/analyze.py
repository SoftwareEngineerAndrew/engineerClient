"""Step 3: the numbers in docs/mechanics/watcher.md.

    python3 analyze.py OUT_DIR [data dialogue mobs watcher end fastest]   (default: all)

Everything is in server ticks relative to the blood door opening ("The BLOOD DOOR has been
opened!", D). Timing sections use only runs whose every recording has `st` lines.
"""
import collections
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wlib as L  # noqa: E402
import model as M  # noqa: E402


def hdr(s):
    print('\n== ' + s)


def timed(R):
    for r in R:
        if not r['all_st']:
            continue
        m = M.model(r)
        if m:
            yield r, m


def sec_data(out_dir, R):
    hdr('data')
    ids = L.extract_ids(out_dir)
    xs = [L.load_extract(out_dir, i) for i in ids]
    ok = [x for x in xs if 'skip' not in x]
    print('recordings:', len(ids), '| with a Watcher window:', len(ok), '| with st lines:', sum(1 for x in ok if len(x['st']) > 10))
    print('left out: alpha', sorted(L.ALPHA_RUNS), '+ suspected alpha', sorted(L.SUSPECT_ALPHA))
    print('runs:', len(R), '| recordings per run:', dict(collections.Counter(len(r['recs']) for r in R)))
    print('runs with st in every recording:', sum(1 for r in R if r['all_st']),
          '| of those with "proven":', sum(1 for r in R if r['all_st'] and M.line(r, 'You have proven')))
    sp = [s for r in R for rc in r['recs'] if rc['off'] != 0 or rc['spread'] != [0, 0] for s in rc['spread']]
    print('sibling alignment residuals on shared chat lines (ticks): min', min(sp) if sp else None, 'max', max(sp) if sp else None)


def sec_dialogue(R):
    hdr('dialogue (offsets from D)')
    off = collections.defaultdict(list)
    alt = 0
    for r, m in timed(R):
        for n, t, s in r['chat']:
            if s in L.DIALOG_LINES:
                off[s[:30]].append(n - m['D'])
            if s.startswith('Ah, we meet again'):
                alt += 1
        if m['H']:
            off['handle this'].append(m['H'] - m['D'])
    for k, v in off.items():
        print('%-32s %s' % (k, L.summary(v)))
    print('"Ah, we meet again. As I foresaw..." (alpha-only dialogue) in kept runs:', alt)

    def typ(s):
        return ('spawn' if s in L.SPAWN_LINES else 'kill' if s in L.KILL_LINES else 'handle' if s.startswith("Let's")
                else 'proven' if s.startswith('You have') else 'enough' if s.startswith('That will') else
                'dialog' if s in L.DIALOG_LINES else 'other')
    gaps = collections.defaultdict(list)
    for r, m in timed(R):
        ws = [(n, s) for n, t, s in r['chat'] if not s.startswith(('The BLOOD', '[BOSS]'))]
        for (a, sa), (b, sb) in zip(ws, ws[1:]):
            gaps[(typ(sa), typ(sb))].append(b - a)
    print('gap to the next Watcher line, by line types (the shortest gaps show the speech spacing):')
    for k in sorted(gaps, key=lambda k: -len(gaps[k])):
        v = gaps[k]
        if len(v) >= 10:
            print('  %-20s n=%4d  p5 %4s  p25 %4s  median %4s' % ('%s->%s' % k, len(v), L.pct(v, 5), L.pct(v, 25), L.median(v)))
    # handle this vs the Watcher arriving back at the middle, and the previous line
    rows = []
    for r, m in timed(R):
        if not m['H']:
            continue
        back = [l for l in m['legs'] if l['npk'] >= 3 and l['dep'] < m['H'] - 5 and abs(l['to'][0]) < 3 and abs(l['to'][2]) < 3]
        prev = [n for n, t, s in r['chat'] if not s.startswith(('The BLOOD', '[BOSS]')) and n < m['H']]
        if back and prev:
            rows.append((round(m['H'] - back[-1]['arr']), m['H'] - prev[-1], round(back[-1]['arr'] - prev[-1])))
    on_arr = [x for x in rows if x[0] <= 2]
    late = [x for x in rows if x[0] > 2]
    print('"handle this" vs the Watcher back in the middle: n=%d; said on arrival (-3..+2): %d, previous line %s-%s before'
          % (len(rows), len(on_arr), min(x[1] for x in on_arr), max(x[1] for x in on_arr)) if on_arr else '')
    if late:
        print('  said later: %d, then %s ticks after the previous line (arrival was only %s-%s after it)'
              % (len(late), sorted(x[1] for x in late), min(x[2] for x in late), max(x[2] for x in late)))


def sec_mobs(out_dir, R):
    hdr('blood mobs')
    reg = collections.Counter(); extra = collections.Counter(); names = collections.Counter()
    for r in R:
        m = M.model(r)
        if not m:
            continue
        reg[len([x for x in m['mobs'] if x['name'] in L.REGULAR])] += 1
        for x in m['mobs']:
            names[x['name']] += 1
        extra[tuple(sorted(x['name'] for x in m['mobs'] if x['name'] in L.BOSSES))] += 1
    print('regular mobs seen per run:', dict(reg))
    print('mini-bosses seen per run (recorder in view early enough):', extra.most_common(12))
    print('mob names:', dict(names))
    # skull layout and skins
    slots = collections.Counter(); per = collections.Counter(); skin2mob = collections.defaultdict(collections.Counter)
    for r in R:
        ss = r['skulls']
        per[len(ss)] += 1
        for s in ss.values():
            x, y, z = s['slot']
            a, b = sorted((abs(x + 0.5), abs(z + 0.5)))
            slots[(a, b, y)] += 1
        for f in M.flights(r):
            if f['mob']:
                skin2mob[f['skin']][f['mob']] += 1
    print('wall skulls recorded per run:', sorted(per.items()))
    print('skull slots (|dx|,|dz| from the middle block sorted, y):', sorted(slots.items()))
    amb = {s: c for s, c in skin2mob.items() if len(c) > 1}
    print('skull skins matched to the mob that appeared: %d skins, %d mobs, ambiguous %s'
          % (len(skin2mob), len({c.most_common(1)[0][0] for c in skin2mob.values()}), amb))
    print('  skin -> mob:', sorted((c.most_common(1)[0][0], s) for s, c in skin2mob.items()))
    # flights
    order = collections.Counter(); dsn = collections.Counter(); fl_pre = []; fl_post = []; dy = []; dxz = []; ys = []
    for r, m in timed(R):
        fl = m['fl']
        pre = [f for f in fl if m['H'] and f['launch'] < m['H']]
        if len(fl) >= 18:
            order[tuple('Giant' if f['mob'] == 'Giant' else 'boss' if f['mob'] in L.BOSSES else 'regular' for f in pre)] += 1
        for f in fl:
            if f['arrive'] is None:
                continue
            (fl_pre if m['H'] and f['launch'] < m['H'] else fl_post).append((f['arrive'] - f['launch'], f['v']))
            if f['mob_sn'] is not None:
                dsn[f['mob_sn'] - f['arrive']] += 1
                dy.append(round(f['mob_pos'][1] - f['dest'][1], 2))
            dxz.append(math.hypot(f['dest'][0] + 0.5, f['dest'][2] + 0.5)); ys.append(f['dest'][1])
    print('launch order before "handle this" (runs with >=18 flights):', order.most_common(6))
    print('skull flight before "handle this": ticks', L.summary([a for a, v in fl_pre]), '| speed', L.summary([v for a, v in fl_pre], 3))
    print('skull flight after:                ticks', L.summary([a for a, v in fl_post]), '| speed', L.summary([v for a, v in fl_post], 3))
    print('mob first seen - skull removed (ticks):', sorted(dsn.items()))
    print('mob y - skull y at arrival:', L.median(dy))
    print('skull destination: distance from the middle block', L.summary(dxz, 2), '| y', L.summary(ys, 2))
    # most alive at once
    mx = []
    for r in R:
        m = M.model(r)
        if m and m['complete']:
            ev = sorted([(x['sn'], 1) for x in m['mobs']] + [(M.death(x), -1) for x in m['mobs']])
            a = b = 0
            for t, d in ev:
                a += d; b = max(b, a)
            mx.append(b)
    print('most blood mobs alive at once, per run:', L.summary(mx))
    # death (0 health name tag) -> removal, from the recordings with name tags
    d = collections.Counter()
    for rid in L.extract_ids(out_dir):
        x = L.load_extract(out_dir, rid)
        if 'skip' in x or len(x['st']) <= 10:
            continue
        clk = L.Clock(x['st'])
        pg = collections.defaultdict(list)
        for t, nm in x['pgone']:
            pg[nm.split('#')[0].strip()].append(t)
        for e in x['ents'].values():
            mm = re.search(r'(?:Healthy|Speedy|Stealth|Golden|Boomer|Stormy) (.+?) [\d.,]+[kM]?❤', e['name']) if e['type'] == 'minecraft:armor_stand' else None
            if not mm:
                continue
            z = [t for t, s in e['names'] if s.endswith(' 0❤')]
            g = [t for t in pg.get(mm.group(1), []) if z and t >= z[0]]
            if z and g:
                d[clk.n(g[0]) - clk.n(z[0])] += 1
    print('0-health name tag -> player entity removed (server ticks):', sorted(d.items()))


def sec_watcher(R):
    hdr('the Watcher')
    first = collections.Counter(); pre30 = collections.Counter(); prev = []
    for r, m in timed(R):
        D = m['D']
        L0 = [l for l in m['legs'] if l['npk'] >= 3 and abs(l['frm'][0] + 0.5) < 1 and abs(l['frm'][2] + 0.5) < 1 and l['dep'] - D < 400]
        if L0:
            first[round(L0[0]['dep'] - D)] += 1
        for l in m['legs']:
            if m['H'] and l['npk'] >= 3 and l['dep'] < m['H'] - 5:
                pre30[round(l['dep'] - D) % 30] += 1
                back = abs(l['to'][0]) < 3 and abs(l['to'][2]) < 3
                prev.append((back, l['v']))
    print('first move off the middle (D+):', sorted(first.items()))
    print('dialogue-phase departures, (dep - D) mod 30:', sorted(pre30.items()))
    print('dialogue-phase speed to a skull:', L.summary([v for b, v in prev if not b and v], 3), '| back to the middle:', L.summary([v for b, v in prev if b and v], 3))
    # the move after "handle this"
    mv = []
    for r, m in timed(R):
        pl = M.post_legs(m)
        if pl and m['H']:
            mv.append((round(pl[0]['dep'] - m['D']), m['H'] - m['D'], r['party']))
    v = [a for a, h, p in mv if a < 800]
    print('first move after "handle this" (D+):', L.summary(v), '| exact values:', sorted(collections.Counter(v).items()))
    print('  minus "handle this":', L.summary([a - h for a, h, p in mv if a < 800]))
    for lo, hi in ((0, 425), (425, 450), (450, 500), (500, 600)):
        s = sorted(a for a, h, p in mv if lo <= h < hi and a < 800)
        print('  "handle this" at D+%d..%d -> move at %s' % (lo, hi - 1, s))
    # post-move departures: grid and speed by blood mobs alive
    grid = collections.Counter(); spd = collections.defaultdict(list)
    for r, m in timed(R):
        if not m['complete']:
            continue
        for l in M.post_legs(m):
            if l['npk'] < 4 or not l['v']:
                continue
            a = min(M.alive_at(m['mobs'], l['dep']), 1)
            ph = round(l['dep'] - m['D']) % 40
            grid[(a, 'on 40' if ph in (39, 0, 1, 2) else 'on 20' if ph in (19, 20, 21, 22) else 'off')] += 1
            if not (abs(l['to'][0]) < 3 and abs(l['to'][2]) < 3):
                spd[a].append(l['v'])
    print('after the move, departures by (mobs alive? , phase from D):', dict(grid))
    for a in (0, 1):
        v = spd[a]
        cls = collections.Counter('0.61' if 0.58 < x < 0.66 else '0.44' if 0.41 < x < 0.48 else '0.92' if 0.85 < x < 0.95 else 'other' for x in v)
        print('  speed with %s mobs alive: %s | classes %s' % ('no' if a == 0 else '>=1', L.summary(v, 3), dict(cls)))
    for c in ('0.61', '0.44', '0.92'):
        lo, hi = {'0.61': (0.58, 0.66), '0.44': (0.41, 0.48), '0.92': (0.85, 0.95)}[c]
        print('  speed class %s: median %.3f' % (c, L.median([x for a in spd for x in spd[a] if lo < x < hi]) or 0))
    # departure = first 40-tick step after arrival (no mobs alive)
    k40 = collections.Counter(); la = []
    for r, m in timed(R):
        pl = M.post_legs(m)
        for a, b in zip(pl, pl[1:]):
            g = m['D'] + math.ceil((a['arr'] + 0.5 - m['D']) / 40) * 40
            alive = M.alive_at(m['mobs'], b['dep']) if m['complete'] else -1
            if alive == 0:
                k40[round((b['dep'] - g) / 20)] += 1
        for s in M.stops(m):
            if len(s['fl']) == 1 and m['H'] and s['arr'] > m['H']:
                la.append(round(s['fl'][0]['launch'] - s['arr']))
    print('no mobs alive: next departure vs the first 40-tick step (D+40k) after arriving, in 20-tick steps:', sorted(k40.items()))
    print('skull launch - Watcher arrival at its niche:', sorted(collections.Counter(la).items()))
    # waiting steps while mobs are alive
    w = collections.Counter()
    for r, m in timed(R):
        if not m['complete']:
            continue
        pl = M.post_legs(m)
        for a, b in zip(pl, pl[1:]):
            g = m['D'] + math.ceil((a['arr'] + 0.5 - m['D']) / 20) * 20
            while g < b['dep'] + 3:
                if M.alive_at(m['mobs'], g) > 0:
                    w['departs' if abs(g - b['dep']) < 3 else 'waits'] += 1
                g += 20
    print('idle 20-tick steps with mobs alive:', dict(w))
    # target order
    rank = collections.Counter()
    for r in R:
        m = M.model(r)
        if not m or len(m['fl']) < 19:
            continue
        fl = m['fl']
        for i in range(4, len(fl) - 2):
            d = [math.dist(fl[i]['slot'], f['slot']) for f in fl[i + 1:]]
            rank[round(sorted(d).index(d[0]) / (len(d) - 1) * 4)] += 1
    print('next skull\'s distance rank among the remaining targets (0 nearest .. 4 farthest):', sorted(rank.items()))


def sec_end(out_dir, R):
    hdr('end of the camp')
    rows = []
    for r, m in timed(R):
        if not m['P'] or not m['complete']:
            continue
        lk = max(M.death(x) for x in m['mobs'])
        ws = [(n, s) for n, t, s in r['chat'] if not s.startswith(('The BLOOD', '[BOSS]')) and n < m['P']]
        pn, ps = ws[-1]
        rows.append((m['P'] - lk, (m['P'] - m['D']) % 10, ps, m['P'] - pn))
    grid = [x for x in rows if x[1] in (9, 0)]
    off = [x for x in rows if x[1] not in (9, 0)]
    print('"proven" - last death:', L.summary([x[0] for x in rows]))
    print('  on the D+10k grid (mod 10 = 9/0): %d, delay after the last death %s' % (len(grid), sorted(x[0] for x in grid)))
    print('  off it: %d, gap after the previous Watcher line %s (after a kill line / spawn line)' % (len(off), sorted((x[3], 'kill' if x[2] in L.KILL_LINES else 'spawn' if x[2] in L.SPAWN_LINES else x[2][:8]) for x in off)))
    e = collections.Counter()
    for r in R:
        ch = [s for n, t, s in r['chat']]
        if 'That will be enough for now.' in ch:
            i, p = ch.index('That will be enough for now.'), [j for j, s in enumerate(ch) if s.startswith('You have proven')]
            e['before proven' if p and i < p[0] else 'after proven' if p else 'no proven'] += 1
    print('"That will be enough for now.":', dict(e))
    # portal
    d = collections.Counter(); ph = collections.Counter()
    for rid in L.extract_ids(out_dir):
        if rid in L.ALPHA_RUNS or rid in L.SUSPECT_ALPHA:
            continue
        x = L.load_extract(out_dir, rid)
        if 'skip' in x or len(x['st']) <= 10:
            continue
        clk = L.Clock(x['st'])
        P = [t for t, s in x['chat'] if s == L.PROVEN]; D = [t for t, s in x['chat'] if s == L.DOOR]
        pb = [b for b in x['blocks'] if 'nether_portal' in b[4] and P and b[0] >= P[0] - 5]
        if P and D and pb:
            d[clk.n(pb[0][0]) - clk.n(P[0])] += 1
            ph[(clk.n(pb[0][0]) - clk.n(D[0])) % 10] += 1
    print('portal blocks appear after "proven":', L.summary([k for k, v in d.items() for _ in range(v)]), '| (portal - D) mod 10:', sorted(ph.items()))


def sec_fastest(R):
    hdr('fastest')
    rows = []
    for r, m in timed(R):
        if not m['P'] or not m['L1']:
            continue
        pl = M.post_legs(m)
        mv = round(pl[0]['dep'] - m['D']) if pl else None
        ls = max(x['sn'] for x in m['mobs']) - m['D'] if m['mobs'] else None
        al = None
        if m['complete'] and mv:
            al = sum(1 for g in range(mv, ls) if M.alive_at(m['mobs'], g + m['D']) > 0)
        rows.append({'run': r['group'], 'party': r['party'], 'split': m['P'] - m['L1'], 'H': m['H'] - m['D'] if m['H'] else None,
                     'move': mv, 'last_spawn': ls, 'P': m['P'] - m['D'], 'alive_ticks_after_move': al, 'complete': m['complete']})
    rows.sort(key=lambda r: r['split'])
    print('Watcher split ("Things feel..." -> "proven", server ticks):', L.summary([r['split'] for r in rows]))
    print('5-player runs:', L.summary([r['split'] for r in rows if r['party'] >= 5]), '| solo:', L.summary([r['split'] for r in rows if r['party'] == 1]))
    for r in rows[:10]:
        print('  ', r)
    c = [r for r in rows if r['complete'] and r['move'] and r['party'] >= 5 and r['split'] < 1600]

    def corr(a, b):
        ma, mb = sum(a) / len(a), sum(b) / len(b)
        return sum((x - ma) * (y - mb) for x, y in zip(a, b)) / math.sqrt(sum((x - ma) ** 2 for x in a) * sum((y - mb) ** 2 for y in b))
    for k in ('H', 'move', 'alive_ticks_after_move'):
        cc = [r for r in c if r[k] is not None]
        print('corr(%s, split) = %.2f (n=%d)' % (k, corr([r[k] for r in cc], [r['split'] for r in cc]), len(cc)))
    v = sorted(r['last_spawn'] - r['move'] for r in c if r['alive_ticks_after_move'] < 130)
    print('last spawn - move, 5-player runs that killed fast (<130 ticks with a mob alive):', L.summary(v))
    print('"proven" - last spawn, same runs:', L.summary([r['P'] - r['last_spawn'] for r in c if r['alive_ticks_after_move'] < 130]))


def main():
    out_dir = sys.argv[1]
    secs = sys.argv[2:] or ['data', 'dialogue', 'mobs', 'watcher', 'end', 'fastest']
    R = M.load_runs(out_dir)
    for s in secs:
        if s in ('data', 'mobs', 'end'):
            globals()['sec_' + s](out_dir, R)
        else:
            globals()['sec_' + s](R)


if __name__ == '__main__':
    main()
