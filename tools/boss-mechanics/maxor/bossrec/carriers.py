"""Follow each crystal carrier from pickup to placement: where they were on every check until it registered."""
import os, json, glob, collections, sys, math

PYL = {'W': (52.5, 224.0, 41.5), 'E': (94.5, 224.0, 41.5)}


def phase(ev):
    col = [s for s, k, v in ev if k in ('col224', 'col223')]
    return collections.Counter(s % 10 for s in col).most_common(1)[0][0] if col else None


def hd(p, q):
    return math.hypot(p[0] - q[0], p[2] - q[2])


def at(track, s):
    best = None
    for row in track:
        if row[0] <= s:
            best = row
        else:
            break
    return best


def carrier_rows(r):
    ev = r['events']; ph = phase(ev)
    if ph is None:
        return []
    picks = [(s, v) for s, k, v in ev if k == 'pick']
    places = [(s, v) for s, k, v in ev if k == 'crystal+' and v in ('W', 'E')]
    hits = [s for s, k, v in ev if k == 'col221' and v == 'beacon'][:1]
    out = []
    used = set()
    for ps, who in picks:
        tr = r['players'].get(who)
        if not tr:
            out.append({'who': who, 'pick': ps, 'note': 'untracked'}); continue
        # the placement this pick led to: first placement after the pick whose pylon the carrier is near then
        got = None
        for s, side in places:
            if s < ps or (s, side) in used:
                continue
            p = at(tr, s)
            if p and hd(p[1:], PYL[side]) < 4 and s - p[0] < 20:
                got = (s, side); used.add(got); break
        row = {'who': who, 'pick': ps, 'placed': got}
        if got:
            side = got[1]
            first_on = None; first_near = None
            for t in tr:
                if t[0] < ps or t[0] > got[0]:
                    continue
                d = hd(t[1:], PYL[side])
                if first_near is None and d <= 3.5 and 223 <= t[2] <= 227:
                    first_near = t[0]
                if first_on is None and d <= 0.8 and 223.95 <= t[2] <= 224.6:
                    first_on = t[0]
            row.update(first_on=first_on, first_near=first_near)
            # every check between pick and placement: carrier's distance and height
            chk = []
            c = ps + ((ph - ps) % 10)
            while c <= got[0] + 1:
                p = at(tr, c)
                if p:
                    chk.append((c, round(hd(p[1:], PYL[side]), 2), round(p[2], 2), c - p[0]))
                c += 10
            row['checks'] = chk
        out.append(row)
    return out


if __name__ == '__main__':
    sel = sys.argv[1] if len(sys.argv) > 1 else 'alpha'
    for p in sorted(glob.glob(os.path.join(os.environ.get('MAXOR_OUT', 'out'), '*.json'))):
        r = json.load(open(p))
        if 'events' not in r or r['server'] != sel:
            continue
        for row in carrier_rows(r):
            print(r['run'][:19], row)
