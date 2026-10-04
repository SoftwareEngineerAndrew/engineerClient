"""One row per run: the crystal cycle step by step, ticks after s0 (corrected server clock)."""
import os, json, glob, collections, statistics, sys


def hits_of(ev):
    hits = []; last = None
    for s, k, v in ev:
        if k == 'mhp':
            if v == 1000.0 and last != 1000.0 and (not hits or s - hits[-1] >= 30):
                hits.append(s)
            last = v
    return hits


def row(r):
    ev = r['events']
    g = lambda kind, val=None, after=-10**6, before=10**6: [s for s, k, v in ev if k == kind and (val is None or v == val) and after <= s < before]
    beacon = (g('col221', 'beacon') or [None])[0]
    kill = (g('col221', 'bedrock') or [None])[0]
    storm = (g('storm') or [None])[0]
    hits = hits_of(ev)
    if kill is not None:
        hits = [h for h in hits if h <= kill + 2]
    h1 = hits[0] if hits else None
    h2 = hits[1] if len(hits) > 1 else None
    pyl = sorted(g('crystal+', 'W') + g('crystal+', 'E'))
    c1 = pyl[:2]
    c2 = [s for s in pyl if h1 is not None and s > h1][:2]
    tops = g('crystal+', 'topW') + g('crystal+', 'topE')
    respawn = min([s for s in tops if h1 is not None and s > h1] or [None], key=lambda x: 10**9 if x is None else x)
    picks1 = sorted(g('pick', before=h1 or 10**6))[:2]
    picks2 = sorted(g('pick', after=(h1 or 10**6)))[:2]
    ch = g('charging')
    ch1 = (ch or [None])[0]
    ch2 = ([c for c in ch if h1 is not None and c > h1] or [None])[0]
    bars = [(s, v) for s, k, v in ev if k == 'bar']
    bar_before_h2 = [v for s, v in bars if h1 is not None and h1 < s < (h2 or kill or 10**6)]
    enr = g('enrage')
    stuns = g('stun')
    return dict(run=r['run'][:19], srv=r['server'], beacon=beacon, place1=c1, charge1=ch1, hit1=h1,
                stun1=stuns[0] if stuns else None, enrage1=enr[0] if enr else None,
                bar_after1=min(bar_before_h2) if bar_before_h2 else None,
                respawn=respawn, pick2=picks2, place2=c2, charge2=ch2, hit2=h2, kill=kill, storm=storm,
                hits=hits[:4])


if __name__ == '__main__':
    rows = []
    for p in sorted(glob.glob(os.path.join(os.environ.get('MAXOR_OUT', 'out'), '*.json'))):
        r = json.load(open(p))
        if 'events' not in r or r['server'] not in ('alpha', 'main'):
            continue
        rows.append(row(r))
    for x in rows:
        if x['kill'] is None: continue
        d = lambda a, b: None if a is None or b is None else a - b
        print('%-5s %s beacon %-4s pl1 %-10s ch1 %-4s hit1 %-4s en %-4s bar %-5s | resp+%-3s pick2+%-10s pl2+%-10s ch2-pl2 %-4s hit2-hit1 %-4s kill-hit2 %-3s storm-kill %-4s storm %s' % (
            x['srv'], x['run'][11:], x['beacon'], x['place1'], x['charge1'], x['hit1'], d(x['enrage1'], x['hit1']), x['bar_after1'],
            d(x['respawn'], x['hit1']), [d(p, x['hit1']) for p in x['pick2']], [d(p, x['hit1']) for p in x['place2']],
            d(x['charge2'], max(x['place2']) if x['place2'] else None), d(x['hit2'], x['hit1']), d(x['kill'], x['hit2']),
            d(x['storm'], x['kill']), x['storm']))
    
