#!/usr/bin/env python3
"""Static terminal roles for F7 phase 3: the measurements and the schedule search.

usage: roles.py OUT_DIR [--iters N] [--restarts N] [--seed N]

Needs OUT_DIR/extract/ and OUT_DIR/sections.json (extract.py, doors.py, sections.py). Prints the
measurements (terminal numbering, solve / open / lever / gate / leap / device times, walking
between job points, the core rush) from the fastest runs, then searches static five-player plans
with the schedule model (roleplan.py) and prints the best plan, its predicted timeline against the
fastest recorded runs, the item use per role and the sensitivity. Writes OUT_DIR/roles.json.

"Fast" = the fastest 25% of runs by P3 (start -> "The Core entrance is opening!"); per-action
measurements that are thin there also use the fastest 50% (both are printed).
"""
import collections
import json
import os
import random
import sys
import time
from multiprocessing import Pool

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import glib as G  # noqa: E402
import roledata as RD  # noqa: E402
import rolemeasure as M  # noqa: E402
import roleplan as RP  # noqa: E402


def arg(name, default):
    return type(default)(sys.argv[sys.argv.index(name) + 1]) if name in sys.argv else default


def measure(recs):
    fast, cut, nruns = RD.fast_groups(recs, 0.25)
    half, cut2, _ = RD.fast_groups(recs, 0.5)
    nrec = lambda F: sum(1 for r in recs if r.group in F)  # noqa: E731
    print('recordings with server ticks: %d; runs with all section ends: %d' % (len(recs), nruns))
    print('fast = P3 <= %d: %d runs, %d recordings (%d with completion lines); '
          'top half = P3 <= %d: %d runs, %d recordings' % (
              cut, len(fast), nrec(fast), sum(1 for r in recs if r.group in fast and r.lines),
              cut2, len(half), nrec(half)))
    out = {'cut': cut, 'cut2': cut2, 'nfast': len(fast), 'nhalf': len(half)}

    print('\n== 1. terminal numbering (order from the section start)')
    num = M.numbering(recs)
    rows = []
    for k, lst in num.items():
        for i, (key, d, wk, ar) in enumerate(lst):
            want = 'S%d T%d' % (k, i + 1)
            flag = '' if key == want else '   <-- table says %s' % key
            print('  S%d #%d %-6s stand %-15s start->stand %4.0f blocks | walked p10 %s (n=%d) | first arrival %s%s' % (
                k, i + 1, key, RD.STATIONS[key][2], d, G.fmt(wk.get('p10'), 0), wk['n'], M.fmt(ar), flag))
            rows.append((key, d, wk, ar))
    out['numbering'] = [(k, round(d, 1), w, a) for k, d, w, a in rows]

    print('\n== 2. terminals')
    for lab, F in (('fast', fast), ('top half', half)):
        tt = M.terminal_times(recs, F)
        print('  [%s] solve (window open -> done) %s' % (lab, M.fmt(tt['solve_fast'])))
        print('  [%s] open (within reach -> window) %s' % (lab, M.fmt(tt['open_delay'])))
        for k, v in sorted(tt['by_kind'].items(), key=lambda a: -a[1]['n']):
            print('      %-16s %s' % (k, M.fmt(v)))
        print('      by terminal: ' + '; '.join('%s %s (n=%d)' % (k, G.fmt(v['med'], 0), v['n'])
                                                 for k, v in sorted(tt['by_term'].items())))
        if lab == 'fast':
            out['terminals'] = {k: v for k, v in tt.items() if k != 'solve_fast_list'}
            out['solve_list'] = tt['solve_fast_list']
    fa, stg = M.first_after_door(recs, fast)
    out['first'] = {}
    for k in (2, 3, 4):
        a = M.stats([v.get('terminal') for v in fa[k].values()])
        b = M.stats([v.get('lever') for v in fa[k].values()])
        c = M.stats([x for x, y in stg[k]])
        d = M.stats([y for x, y in stg[k]])
        print('  S%d door -> first terminal done %s | first lever %s | standing at a terminal: '
              'door -> window %s, -> done %s' % (k, M.fmt(a), M.fmt(b), M.fmt(c), M.fmt(d)))
        out['first'][k] = (a, b, c, d)

    print('\n== 3. levers, gates, leaps, devices, core')
    lv = M.lever_times(recs, half)
    print('  lever [top half]: arrival -> pull %s; standing there at the door: door -> pull %s' % (
        M.fmt(lv['arrive']), M.fmt(lv['at_door'])))
    gt = M.gate_times(recs, half)
    print('  gate [top half]: recorder at the gate (<= 8 blocks, nearest) -> destroyed %s' % M.fmt(gt['blow']))
    gtf = M.gate_times(recs, fast)
    for k in (1, 2, 3):
        print('    gate %d/%d destroyed, from its section start [fast]: %s' % (
            k, k + 1, M.fmt(M.stats(list(gtf['rel'][k].values())))))
    lp = M.leap_times(recs, fast)
    print('  Spirit Leap [fast]: menu open -> landing %s; from -> to section: %s' % (
        M.fmt(lp['menu']), dict(lp['where'].most_common(6))))
    dl = M.door_leaps(recs, half)
    for k in (2, 3, 4):
        print('  leap at the S%d door [top half]: door -> landing %s' % (k, M.fmt(M.stats(dl[k]))))
    out['leap_door'] = M.stats([v for k in dl for v in dl[k]])
    print('  leap at a door, all [top half]: %s' % M.fmt(out['leap_door']))
    dv = M.s1_devices(recs, half)
    dvf = M.s1_devices(recs, fast)
    for k in ('ss', 'target', 'lights', 'lights_dur'):
        print('  %-10s [fast] %s | [top half] %s' % (k, M.fmt(M.stats(dvf[k])), M.fmt(M.stats(dv[k]))))
    arrow = [b - a for a, b in M.device_onspot(recs, 'S3 Arrow Align')]
    lights_on = [b - a for a, b in M.device_onspot(recs, 'S2 Lights')]
    target_on = M.device_onspot(recs, 'S4 target')
    print('  Arrow Align, recorder at the frames -> done [all runs] %s' % M.fmt(M.stats(arrow)))
    print('  Lights, recorder at the levers -> done [all runs] %s' % M.fmt(M.stats(lights_on)))
    print('  target, recorder on the plate from .. to done [all runs] %s' % target_on)
    cr = M.core_rush(recs, fast)
    print('  core [fast]: opening -> recorder inside %s; -> last player inside %s' % (
        M.fmt(cr['me']), M.fmt(cr['last'])))
    st = M.section_times(recs, fast)
    for i in range(4):
        print('  S%d [fast] %s' % (i + 1, M.fmt(M.stats([v[i] for v in st.values()]))))
    print('  P3 [fast] %s' % M.fmt(M.stats([sum(v) for v in st.values()])))
    out.update(lever=lv, gate=gt['blow'], leap=lp['menu'], dev=dv, devfast=dvf, arrow=arrow,
               lights_on=lights_on, core=cr, sections=st)

    print('\n== 4. moving between job points [top half, recorder only; walk = no teleport, no stop]')
    mv, pts = M.travel(recs, half)
    fit = M.travel_model(mv, pts)
    pairs = {}
    for (a, b), m in sorted(mv.items()):
        w = m.get('walk', [])
        if len(w) >= 3:
            s = M.stats(w)
            pairs[(a, b)] = s['med']
            print('  %-14s -> %-14s %5.1f blocks  walk %s' % (a, b, G.dist(pts[a], pts[b]), M.fmt(s)))
    wf = fit['walk']
    print('  walk fit over %d pairs (%d walks): ticks = %.1f + %.2f x horizontal blocks + %.2f x blocks '
          'climbed, rmse %.0f' % (wf['pairs'], wf['moves'], wf['a'], wf['b'], wf['c'], wf['rmse']))
    out['walk_fit'] = wf
    out['pairs'] = {'%s|%s' % k: v for k, v in pairs.items()}
    return out, pairs


def params(meas, pairs, spots):
    P = RP.Params()
    P.spots = spots
    P.solve = meas['solve_list']
    P.open_walk = meas['terminals']['open_delay']['med']
    P.open_door = meas['first'][2][2]['med'] if meas['first'][2][2]['n'] else 4
    P.lever = meas['lever']['arrive']['med']
    P.leap = meas['leap']['med']
    P.leap_door = meas['leap_door']['med']
    P.gate = meas['gate']['med']
    P.ss = meas['dev']['ss']
    P.target = meas['dev']['target']
    P.lights = meas['dev']['lights_dur']
    P.arrow = meas['arrow']
    P.walk_fit = (meas['walk_fit']['a'], meas['walk_fit']['b'], meas['walk_fit']['c'])
    P.core_in = meas['core']['last']['min']
    ren = lambda k: 'strip' if k == 'core' else k  # noqa: E731
    P.pairs = {(ren(a), ren(b)): v for (a, b), v in pairs.items() if not a.startswith('door')
               and not b.startswith('door')}
    # the core route: S2 wall -> strip, from the measured S2 terminal -> core-door walks
    via = [v - P._w(a, RP.HOLE) for (a, b), v in P.pairs.items() if b == 'strip' and RP.sec_of(a) == 2]
    if via:
        P.hole_to_strip = G.median(sorted(via))
    return P


def seed_plan():
    """A starting point shaped like the team's M7 rotation (ss, 21/43, i4, l+ee2): also the plan
    the model is calibrated with against the recorded fast runs."""
    names = ['A', 'B', 'C', 'D', 'E']
    jobs = [
        [(1, 'S1 Simon Says'), (2, 'S2 T5'), (3, 'S3 Arrow Align'), (3, 'S3 T4'), (4, 'S4 T4')],
        [(1, 'S1 T1'), (1, 'S1 T3'), (1, 'gate 1/2'), (2, 'S2 T1'), (2, 'S2 T3'), (3, 'S3 T1'),
         (4, 'S4 T1')],
        [(1, 'S1 T2'), (1, 'S1 T4'), (2, 'S2 T2'), (2, 'S2 T4'), (3, 'S3 T2'), (3, 'S3 T3'),
         (4, 'S4 T2')],
        [(1, 'S4 target'), (2, 'gate 2/3'), (2, 'S2 lever low'), (3, 'S3 lever W'), (3, 'S3 lever E'),
         (3, 'gate 3/4'), (4, 'S4 T3')],
        [(1, 'S1 lever E'), (1, 'S1 lever W'), (1, 'S2 Lights'), (2, 'S2 lever high'),
         (3, 'strip'), (4, 'S4 lever low'), (4, 'S4 lever high')],
    ]
    enter = [{}, {}, {}, {}, {2: 181}]
    return RP.Plan(names, jobs, enter)


def staged_plan():
    """A second start: pre-staging in every section (terminal players enter S2 at 181, the lever
    player does Lights, Arrow Align and the gates ahead, one player holds the strip for S4)."""
    names = ['A', 'B', 'C', 'D', 'E']
    jobs = [
        [(1, 'S1 Simon Says'), (2, 'S2 T5'), (3, 'S3 T4'), (4, 'S4 T4')],
        [(1, 'S4 target'), (2, 'S2 T3'), (3, 'S3 T1'), (4, 'S4 T3')],
        [(1, 'S1 T1'), (1, 'S1 T3'), (2, 'S2 T1'), (2, 'strip'), (4, 'S4 lever low'),
         (4, 'S4 lever high')],
        [(1, 'S1 T2'), (1, 'S1 T4'), (2, 'S2 T2'), (2, 'S2 T4'), (3, 'S3 T2'), (3, 'S3 T3'),
         (4, 'S4 T1')],
        [(1, 'S1 lever E'), (1, 'S1 lever W'), (1, 'gate 1/2'), (1, 'S2 Lights'), (2, 'S2 lever high'),
         (2, 'S2 lever low'), (2, 'gate 2/3'), (2, 'S3 Arrow Align'), (3, 'S3 lever W'),
         (3, 'S3 lever E'), (3, 'gate 3/4'), (4, 'S4 T2')],
    ]
    enter = [{}, {}, {2: 181}, {2: 181}, {2: 121, 3: 361}]
    return RP.Plan(names, jobs, enter)


def random_plan(rng):
    """Random valid plan: SS and the target fixed to two players, the rest dealt out."""
    jobs = [[(1, 'S1 Simon Says')], [(1, 'S4 target')], [], [], []]
    rest = [j for j in RP.ALL_JOBS if j not in ('S1 Simon Says', 'S4 target')]
    rng.shuffle(rest)
    s1 = [j for j in rest if RP.sec_of(j) == 1]
    for p, j in zip((2, 3, 4), s1):          # everyone starts on an S1 job
        jobs[p].append((1, j))
        rest.remove(j)
    for j in rest:
        jobs[rng.randrange(5)].append((RP.sec_of(j), j))
    for p in range(5):
        jobs[p].sort(key=lambda a: a[0])
    return RP.Plan(['A', 'B', 'C', 'D', 'E'], jobs, [{} for _ in range(5)])


def describe(plan, P, res):
    """Per-player timeline of the median draw."""
    lines = []
    for p, nm in enumerate(plan.names):
        steps = ['%s@%d%s' % (k, round(t), '' if how in ('there', 'walk', '') else ' (%s)' % how)
                 for t, k, how in res['log'][p]]
        enter = {s: v for s, v in plan.enter[p].items() if v is not None}
        lines.append('  %s  items %d  enter %s\n     %s' % (nm, res['items'][p], enter, ' -> '.join(steps)))
    return '\n'.join(lines)


def critical(res, plan):
    """Per section: the last job and the one before it."""
    out = {}
    owner = {k: plan.names[p] for p in range(5) for _, k in plan.jobs[p]}
    for s in (1, 2, 3, 4):
        ks = RP.jobs_of(s)
        v = sorted(((res['done'][k], k) for k in ks), reverse=True)
        out[s] = [(round(t), k, owner[k]) for t, k in v[:3]]
    return out


def sensitivity(plan, P, draws, base):
    """Mean cost of one player being slower: +20 ticks on each of his terminals (~p75 - p25 of
    the solve times), +50% on his devices."""
    out = {}
    for p, nm in enumerate(plan.names):
        mine = {k for _, k in plan.jobs[p]}
        tot = 0.0
        for d in draws:
            d2 = RP.Draw.__new__(RP.Draw)
            d2.__dict__ = dict(d.__dict__)
            d2.solve = {k: v + (20 if k in mine else 0) for k, v in d.solve.items()}
            if 'S1 Simon Says' in mine:
                d2.ss = d.ss   # Simon Says ends on its own sequence
            if 'S4 target' in mine:
                d2.target = d.target * 1.5
            if 'S2 Lights' in mine:
                d2.lights = d.lights * 1.5
            if 'S3 Arrow Align' in mine:
                d2.arrow = d.arrow * 1.5
            r = RP.simulate(plan, P, d2)
            tot += r['core'] + r['allin']
        out[nm] = tot / len(draws) - base
    return out


def _chain(args):
    """One annealing chain (starting in turn from the rotation-shaped seed, the staged plan and a
    random plan), then a cold polish."""
    i, seed, iters, P, draws = args
    rng = random.Random(seed * 1000 + i)
    start = (seed_plan(), staged_plan(), random_plan(rng))[i % 3]
    b, s = RP.anneal(start, P, draws, iters, rng)
    b, s = RP.anneal(b, P, draws, iters // 4, rng, temp0=2.0)
    return s, b


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    out_dir = sys.argv[1]
    iters, restarts, seed = arg('--iters', 12000), arg('--restarts', 4), arg('--seed', 1)
    t0 = time.time()
    recs = RD.load(out_dir)
    meas, pairs = measure(recs)
    sp = M.spots(recs)
    P = params(meas, pairs, {k: pos for k, (pos, n) in sp.items()})
    print('  core route, S2 wall -> strip: %.0f ticks (S2 terminal -> core door walks minus the walk to '
          'the wall)' % P.hole_to_strip)

    print('\n== 5. schedule search (%d chains x %d steps, 40 draws)' % (restarts, iters))
    draws = RP.make_draws(P, 40, seed)
    work = [(i, seed, iters, P, draws) for i in range(restarts)]
    with Pool(min(restarts, os.cpu_count() or 2)) as pool:
        res = pool.map(_chain, work)
    res.sort(key=lambda a: a[0])
    for i, (s, _) in enumerate(res):
        print('  chain %d: score %.1f' % (i, s))
    best = RP.prune(res[0][1], P, draws)
    big = RP.make_draws(P, 400, seed + 1)
    _, res = RP.score(best, P, big)
    med = RP.simulate(best, P, big[0], detail=True)
    tot = sorted(r['core'] + r['allin'] for r in res)
    core = sorted(r['core'] for r in res)
    print('\nbest plan (median draw):')
    print(describe(best, P, med))
    e = med['doors']
    print('  predicted doors (median draw): S1 %d, S2 %d, S3 %d, core %d; everyone in +%d' % (
        e[1], e[2], e[3], med['core'], med['allin']))
    sec = [[r['doors'][1], r['doors'][2] - r['doors'][1], r['doors'][3] - r['doors'][2],
            r['core'] - r['doors'][3]] for r in res]
    for i in range(4):
        print('  S%d predicted %s' % (i + 1, M.fmt(M.stats([s[i] for s in sec]))))
    print('  P3 predicted %s' % M.fmt(M.stats(core)))
    print('  P3 + everyone in predicted %s' % M.fmt(M.stats(tot)))
    over = [sum(1 for r in res if r['items'][p] > 3) / len(res) for p in range(5)]
    for p, nm in enumerate(best.names):
        v = [r['items'][p] for r in res]
        print('  %s items: %s; share of draws needing > 3: %.3f' % (nm, dict(sorted(collections.Counter(v).items())), over[p]))
    # calibration: the rotation-shaped seed plan against the recorded fast runs
    _, sres = RP.score(seed_plan(), P, big)
    ssec = [[r['doors'][1], r['doors'][2] - r['doors'][1], r['doors'][3] - r['doors'][2],
             r['core'] - r['doors'][3]] for r in sres]
    rec = list(meas['sections'].values())
    print('  calibration, rotation-shaped seed plan vs recorded fast runs (median [p25-p75]):')
    for i in range(4):
        a, b = M.stats([x[i] for x in ssec]), M.stats([x[i] for x in rec])
        print('    S%d model %.0f [%.0f-%.0f]  recorded %.0f [%.0f-%.0f]' % (
            i + 1, a['med'], a['p25'], a['p75'], b['med'], b['p25'], b['p75']))
    a, b = M.stats([r['core'] for r in sres]), M.stats([sum(x) for x in rec])
    print('    P3 model %.0f [%.0f-%.0f]  recorded %.0f [%.0f-%.0f]' % (
        a['med'], a['p25'], a['p75'], b['med'], b['p25'], b['p75']))
    a = M.stats([r['allin'] for r in sres])
    print('    everyone in: model %.0f [%.0f-%.0f]  recorded %s' % (a['med'], a['p25'], a['p75'],
                                                               M.fmt(meas['core']['last'])))
    calib = {'model': [M.stats([x[i] for x in ssec]) for i in range(4)],
             'recorded': [M.stats([x[i] for x in rec]) for i in range(4)]}
    cp = critical(med, best)
    for s, v in cp.items():
        print('  S%d last jobs (median draw): %s' % (s, v))
    sens = sensitivity(best, P, big[:100], sum(tot[:0]) + sum(r['core'] + r['allin'] for r in res[:100]) / 100)
    print('  one player slower (+20 per terminal, devices x1.5): %s' % {k: round(v, 1) for k, v in sens.items()})
    last = collections.Counter()
    for r in res:
        for s in (2, 3, 4):
            ks = RP.jobs_of(s)
            last[(s, max(ks, key=lambda k: r['done'][k]))] += 1
    print('  last job per section over the draws: %s' % sorted(last.items()))
    json.dump({'measure': {k: v for k, v in meas.items() if k not in ('numbering',)},
               'plan': {'names': best.names, 'jobs': best.jobs,
                        'enter': [{str(k): v for k, v in e.items()} for e in best.enter]},
               'median': {'doors': med['doors'], 'core': med['core'], 'allin': med['allin'],
                          'items': med['items'], 'log': med['log']},
               'predicted': {'P3': M.stats(core), 'P3+in': M.stats(tot),
                             'sections': [M.stats([s[i] for s in sec]) for i in range(4)]},
               'sensitivity': sens, 'critical': cp, 'calibration': calib},
              open(os.path.join(out_dir, 'roles.json'), 'w'), default=str, indent=1)
    print('\nwrote %s (%.0f s)' % (os.path.join(out_dir, 'roles.json'), time.time() - t0))


if __name__ == '__main__':
    main()
