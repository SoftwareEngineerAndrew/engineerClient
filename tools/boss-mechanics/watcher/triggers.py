"""What the party's kills trigger in the blood camp, for killing as fast as the Watcher allows.

    python3 triggers.py OUT_DIR [slow taunt proven dialogue deadline]

  slow      which moment's "a blood mob alive" decides the Watcher's slow flight (0.44 b/t) and his
            waits: his departure tick, the 20-tick step before it, the head's landing...; and whether
            the Giant / mini-boss count like regulars
  taunt     when a kill taunt is said relative to a death, and which deaths get none (the speech queue)
  proven    what held "You have proven yourself" back: the last death, the grid, a line just before
  dialogue  whether the four dialogue-phase mobs alive change "handle this" or the move
  deadline  per landed mob: how long after landing it can live before the Watcher slows
Times are server ticks after the blood door line (D), as in docs/mechanics/watcher.md.
"""
import collections
import os
import statistics as st
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model as M  # noqa: E402
import wlib as L  # noqa: E402

FAST, SLOW = 0.55, 0.50   # v >= FAST: the idle 0.61 flight; v <= SLOW: the 0.44 one


def q(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p * len(xs)))] if xs else None


def summ(xs):
    return f"n={len(xs)} min={min(xs)} p10={q(xs, .1)} med={q(xs, .5)} p90={q(xs, .9)} max={max(xs)}" if xs else "n=0"


def runs(out):
    for r in M.load_runs(out):
        if not r.get('all_st'):
            continue
        m = M.model(r)
        if m is None:
            continue
        D = m['D']
        m['chatD'] = [(n - D, t, s) for n, t, s in r['chat'] if n is not None]
        for f in m['fl']:
            for k in ('launch', 'arrive', 'mob_sn'):
                if f[k] is not None:
                    f[k] -= D
        for l in m['legs']:
            l['dep'] -= D; l['arr'] -= D
        m['mobsD'] = [{'name': x['name'], 'sn': x['sn'] - D, 'die': (M.death(x) - D) if x['gn'] is not None else None} for x in m['mobs']]
        for k in ('H', 'P', 'L1', 'E'):
            if m[k] is not None:
                m[k] -= D
        m['id'] = r.get('id') or r.get('group')
        yield m


def alive(mobs, n, which=None):
    return [x for x in mobs if x['sn'] <= n and (x['die'] is None or x['die'] > n) and (which is None or which(x))]


def seen_until(mobs):
    """A run is usable for "alive" only while every mob that spawned has a death (or is still visibly alive)."""
    return all(x['die'] is not None for x in mobs)


def slow(ms):
    """Each post-move leg, fast or slow, against "a mob alive" at candidate moments."""
    cands = {
        'dep': lambda l: l['dep'],
        'dep-1': lambda l: l['dep'] - 1,
        'dep-5': lambda l: l['dep'] - 5,
        'dep-10': lambda l: l['dep'] - 10,
        'dep-20': lambda l: l['dep'] - 20,
        'grid20<=dep': lambda l: (int(l['dep']) // 20) * 20,
        'mid-leg': lambda l: (l['dep'] + l['arr']) / 2,
        'arr': lambda l: l['arr'],
    }
    tally = {k: collections.Counter() for k in cands}
    boss = collections.Counter()
    for m in ms:
        if m['H'] is None or not seen_until(m['mobsD']):
            continue
        for l in m['legs']:
            if l['dep'] < m['H'] or l['npk'] < 4 or l['v'] is None:
                continue
            kind = 'fast' if l['v'] >= FAST else 'slow' if l['v'] <= SLOW else None
            if kind is None or l['v'] > 0.8:
                continue
            for k, f in cands.items():
                tally[k][(kind, bool(alive(m['mobsD'], f(l))))] += 1
            a = alive(m['mobsD'], l['dep'])
            if a and all(x['name'] in L.BOSSES for x in a):
                boss[kind] += 1
    print('== slow: leg speed vs "a blood mob alive" at each candidate moment (post-move legs)')
    print('   moment            fast&none fast&alive slow&none slow&alive  agreement')
    for k, c in tally.items():
        agree = c[('fast', False)] + c[('slow', True)]
        tot = sum(c.values())
        print(f"   {k:16} {c[('fast', False)]:9} {c[('fast', True)]:10} {c[('slow', False)]:9} {c[('slow', True)]:10}  {agree}/{tot} = {agree / tot:.3f}" if tot else k)
    print(f"   legs whose only living mobs were the Giant / mini-boss: {dict(boss)}")


def deadline(ms):
    """Per landed mob in the fetch phase: did it make the Watcher's next leg slow, by how long it lived."""
    rows = []
    for m in ms:
        if m['H'] is None or not seen_until(m['mobsD']):
            continue
        legs = [l for l in m['legs'] if l['dep'] >= m['H'] and l['npk'] >= 4 and l['v'] is not None and l['v'] <= 0.8]
        for x in m['mobsD']:
            if x['sn'] < m['H'] + 40 or x['die'] is None or x['name'] not in L.REGULAR:
                continue
            nxt = [l for l in legs if l['dep'] >= x['sn']]
            if not nxt:
                continue
            l = nxt[0]
            others = [y for y in alive(m['mobsD'], l['dep']) if y is not x]
            if others:
                continue
            life = x['die'] - x['sn']
            to_dep = l['dep'] - x['sn']
            kind = 'fast' if l['v'] >= FAST else 'slow' if l['v'] <= SLOW else None
            if kind:
                rows.append((life, to_dep, kind, x['die'] <= l['dep']))
    print('== deadline: a lone regular, its life (landing -> death) and the next leg')
    by = collections.defaultdict(collections.Counter)
    for life, to_dep, kind, before in rows:
        by['died before next departure' if before else 'alive at next departure'][kind] += 1
    for k, c in by.items():
        print(f"   {k:30} fast {c['fast']:4}  slow {c['slow']:4}")
    print('   landing -> next departure (ticks):', summ([round(r[1]) for r in rows]))
    b = collections.Counter()
    for life, to_dep, kind, before in rows:
        b[(min(life // 10 * 10, 100), kind)] += 1
    print('   life bucket: fast / slow')
    for lb in range(0, 110, 10):
        print(f"     {lb:3}-{lb + 9:3}: {b[(lb, 'fast')]:4} / {b[(lb, 'slow')]:4}")


def taunt(ms):
    print('== taunt: kill taunts vs deaths')
    lag, gaps_said, gaps_none = [], [], []
    for m in ms:
        lines = [(n, s) for n, t, s in m['chatD'] if s in L.KILL_LINES + L.SPAWN_LINES or s.startswith("Let's see") or s in L.DIALOG_LINES]
        taunts = [n for n, s in lines if s in L.KILL_LINES]
        deaths = sorted(x['die'] for x in m['mobsD'] if x['die'] is not None)
        for n in taunts:
            d = [n - dd for dd in deaths if -5 <= n - dd <= 80]
            if d:
                lag.append(min(d, key=abs))
        for dd in deaths:
            prev = [(n, s) for n, s in lines if n <= dd]
            if not prev:
                continue
            pn, ps = prev[-1]
            said = any(0 <= n - dd <= 3 for n in taunts)
            (gaps_said if said else gaps_none).append((round(dd - pn), 'taunt' if ps in L.KILL_LINES else 'spawn' if ps in L.SPAWN_LINES or ps.startswith("Let's") else 'dialog'))
    print('   taunt - nearest death (ticks):', summ(lag))
    cl = collections.Counter(round(x) for x in lag)
    print('   most common:', cl.most_common(8))
    for name, g in (('death WITH a taunt (0-3 after)', gaps_said), ('death with NO taunt', gaps_none)):
        for kind in ('taunt', 'spawn', 'dialog'):
            xs = [a for a, k in g if k == kind]
            if xs:
                print(f"   {name}, last line a {kind}: time since it {summ(xs)}")
    # Is a kill taunt said whenever the queue is free?
    free = collections.Counter()
    for a, k in gaps_said:
        free[('said', k, a >= {'taunt': 41, 'spawn': 61, 'dialog': 81}[k])] += 1
    for a, k in gaps_none:
        free[('none', k, a >= {'taunt': 41, 'spawn': 61, 'dialog': 81}[k])] += 1
    print('   queue free at the death (gap since the last line >= its cooldown) -> taunt said?')
    for k in ('taunt', 'spawn', 'dialog'):
        for fr in (True, False):
            print(f"     last {k:6} free={fr!s:5}: said {free[('said', k, fr)]:4}  none {free[('none', k, fr)]:4}")


def proven(ms):
    print('== proven: what it waited for after the last death')
    rows = []
    for m in ms:
        if m['P'] is None or not m['complete']:
            continue
        deaths = sorted(x['die'] for x in m['mobsD'] if x['die'] is not None)
        if len(deaths) < 19:
            continue
        last = deaths[-1]
        prev = [(n, s) for n, t, s in m['chatD'] if n < m['P'] and s not in ('You have proven yourself. You may pass.',)]
        pn, ps = prev[-1] if prev else (None, '')
        kind = 'taunt' if ps in L.KILL_LINES else 'spawn' if ps in L.SPAWN_LINES else 'other'
        rows.append((m['P'] - last, kind, m['P'] - pn if pn is not None else None, last - pn if pn is not None else None, deaths[-1] - deaths[-2]))
    print('   proven - last death:', summ([round(r[0]) for r in rows]))
    for kind in ('taunt', 'spawn', 'other'):
        rs = [r for r in rows if r[1] == kind]
        if not rs:
            continue
        print(f"   line before proven a {kind}: n={len(rs)}  proven-line {summ([round(r[2]) for r in rs])}")
        print(f"      that line came (last death - line): {summ([round(r[3]) for r in rs])}")
    # The taunt that delays: whose kill was it? (the last death, or the one before)
    late = [r for r in rows if r[1] == 'taunt' and r[0] > 12]
    print(f"   delayed by a taunt (proven > last death + 12): {len(late)}; gap between the last two deaths there: {summ([round(r[4]) for r in late])}")
    ok = [r for r in rows if r[0] <= 12]
    print(f"   not delayed: {len(ok)}; gap between the last two deaths there: {summ([round(r[4]) for r in ok])}")


def dialogue(ms):
    print('== dialogue: the four dialogue-phase mobs vs "handle this" and the move')
    rows = []
    for m in ms:
        if m['H'] is None:
            continue
        early = [x for x in m['mobsD'] if x['sn'] < m['H'] + 20]
        if len(early) < 3 or any(x['die'] is None for x in early):
            continue
        post = [l for l in m['legs'] if l['dep'] >= max(m['H'] + 30, 470) and l['npk'] >= 3]
        if not post:
            continue
        mv = post[0]['dep']
        n_alive_H = len(alive(early, m['H']))
        n_alive_mv = len(alive(early, mv - 1))
        last_early = max(x['die'] for x in early)
        rows.append((m['H'], mv, n_alive_H, n_alive_mv, last_early, post[0]['v']))
    for k in range(0, 5):
        rs = [r for r in rows if r[2] == k]
        if rs:
            print(f"   {k} alive at 'handle this': n={len(rs)} H {summ([round(r[0]) for r in rs])}")
    for k in range(0, 5):
        rs = [r for r in rows if r[3] == k]
        if rs:
            print(f"   {k} alive just before the move: n={len(rs)} move-max(480,H+42) {summ([round(r[1] - max(480, r[0] + 42)) for r in rs])}  1st-leg v med {st.median([r[5] for r in rs if r[5]]):.2f}")


def main(out, which):
    ms = list(runs(out))
    print(f"{len(ms)} runs with the door line")
    for w in which:
        globals()[w](ms)
        print()



def mism(ms):
    """The slow-flight mismatches at the departure tick: how near a death or spawn they sit."""
    print('== mism: legs where "alive at departure" got it wrong')
    sn_, fa = [], []
    for m in ms:
        if m['H'] is None or not seen_until(m['mobsD']):
            continue
        for l in m['legs']:
            if l['dep'] < m['H'] or l['npk'] < 4 or l['v'] is None or l['v'] > 0.8:
                continue
            a = alive(m['mobsD'], l['dep'])
            if l['v'] <= SLOW and not a:
                ds = [l['dep'] - x['die'] for x in m['mobsD'] if x['die'] is not None and x['die'] <= l['dep']]
                ss = [x['sn'] - l['dep'] for x in m['mobsD'] if x['sn'] > l['dep']]
                sn_.append((round(min(ds)) if ds else 999, round(min(ss)) if ss else 999))
            if l['v'] >= FAST and a:
                fa.append(round(min(x['die'] - l['dep'] for x in a if x['die'] is not None)))
    print('   slow with none alive: dep - last death', summ([a for a, b in sn_]))
    print('   slow with none alive: next spawn - dep', summ([b for a, b in sn_]))
    print('   slow buckets (dep - last death):', sorted(collections.Counter(min(a // 10 * 10, 200) for a, b in sn_).items()))
    print('   fast with one alive: its death - dep', summ(fa), sorted(collections.Counter(fa).items())[:15])


def cost(ms):
    """What a slowed leg costs: fetch-phase length against the number of slow legs, per head order."""
    print('== cost: last landing - move vs slow legs')
    rows = []
    for m in ms:
        if m['H'] is None or not m['complete']:
            continue
        legs = [l for l in m['legs'] if l['dep'] >= max(m['H'], 470) and l['npk'] >= 4 and l['v'] is not None]
        if len(legs) < 10:
            continue
        mv = legs[0]['dep']
        last = max(x['sn'] for x in m['mobsD'])
        nslow = sum(1 for l in legs if l['v'] <= SLOW)
        alive_t = sum(1 for n in range(int(mv), int(last)) if alive(m['mobsD'], n))
        rows.append((last - mv, nslow, alive_t, mv))
    for k in (0, 1, 2, 3, 4, 6, 9):
        rs = [r for r in rows if (k <= r[1] < k + (1 if k < 4 else 2 if k == 4 else 3 if k == 6 else 99))]
        if rs:
            print(f"   slow legs {k}+: n={len(rs)} last landing - move {summ([round(r[0]) for r in rs])}")
    if len(rows) > 5:
        xs = [r[1] for r in rows]; ys = [r[0] for r in rows]
        mx, my = st.mean(xs), st.mean(ys)
        b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
        print(f"   slope: {b:.1f} ticks per slow leg (n={len(rows)})")
        xs = [r[2] for r in rows]
        mx = st.mean(xs)
        b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
        print(f"   slope: {b:.2f} ticks per tick with a mob alive")
    print('   move tick:', summ([round(r[3]) for r in rows]))


def window(ms):
    """Slow flight vs "a mob alive at any time in the W ticks before departure" (or since he arrived)."""
    print('== window: slow vs a mob alive at any point before departure')
    res = collections.defaultdict(collections.Counter)
    for m in ms:
        if m['H'] is None or not seen_until(m['mobsD']):
            continue
        legs = [l for l in m['legs'] if l['npk'] >= 4 and l['v'] is not None and l['v'] <= 0.8]
        for i, l in enumerate(legs):
            if l['dep'] < m['H'] or i == 0:
                continue
            kind = 'fast' if l['v'] >= FAST else 'slow' if l['v'] <= SLOW else None
            if not kind:
                continue
            for W in (0, 5, 10, 20, 30, 40, 60, 'since arrival', 'since prev dep'):
                lo = legs[i - 1]['arr'] if W == 'since arrival' else legs[i - 1]['dep'] if W == 'since prev dep' else l['dep'] - W
                hit = any(x['sn'] <= l['dep'] and (x['die'] is None or x['die'] > lo) for x in m['mobsD'])
                res[W][(kind, hit)] += 1
    for W, c in res.items():
        agree = c[('fast', False)] + c[('slow', True)]; tot = sum(c.values())
        print(f"   {str(W):15} fast&none {c[('fast', False)]:5} fast&alive {c[('fast', True)]:4} slow&none {c[('slow', False)]:4} slow&alive {c[('slow', True)]:4}  {agree / tot:.3f}")


def handle(ms):
    """What "handle this" waited for: his return to the middle, or the speech gap after a line (and which)."""
    print('== handle: the line before "handle this" and what it cost')
    rows = []
    for m in ms:
        if m['H'] is None:
            continue
        ret = [l for l in m['legs'] if l['arr'] <= m['H'] + 5 and abs(l['to'][0]) < 2 and abs(l['to'][2]) < 2 and l['dep'] > 300]
        prev = [(n, s) for n, t, s in m['chatD'] if n < m['H'] - 1 and not s.startswith("Let's")]
        if not prev or not ret:
            continue
        pn, ps = prev[-1]
        kind = 'taunt' if ps in L.KILL_LINES else 'spawn' if ps in L.SPAWN_LINES else 'dialog' if ps in L.DIALOG_LINES else 'other'
        back = ret[-1]['arr']
        post = [l for l in m['legs'] if l['dep'] >= max(m['H'] + 30, 470) and l['npk'] >= 3]
        rows.append((kind, round(m['H'] - back), round(m['H'] - pn), round(post[0]['dep']) if post else None, round(m['H'])))
    for kind in ('taunt', 'spawn', 'dialog', 'other'):
        rs = [r for r in rows if r[0] == kind]
        if not rs:
            continue
        held = [r for r in rs if r[1] > 4]
        print(f"   line before a {kind}: n={len(rs)}, held past his return in {len(held)}: by {summ([r[1] for r in held])}")
        print(f"      H: {summ([r[4] for r in rs])}  move: {summ([r[3] for r in rs if r[3]])}")
    on = [r for r in rows if r[1] <= 4]
    print(f"   said on arrival: n={len(on)} move {summ([r[3] for r in on if r[3]])}")


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:] or ['slow', 'deadline', 'taunt', 'proven', 'dialogue', 'mism', 'cost'])
