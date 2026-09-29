"""Per-run event model built from OUT_DIR/runs.json: the Watcher's legs, the skull launches and the
blood mobs, all in server ticks. Used by analyze.py."""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wlib as L  # noqa: E402


def load_runs(out_dir):
    return json.load(open(os.path.join(out_dir, 'runs.json')))


def line(r, prefix):
    v = [n for n, t, m in r['chat'] if m.startswith(prefix)]
    return v[0] if v else None


def door(r):
    return line(r, L.DOOR)


def best_watcher(r):
    w = [w for w in r['watcher'] if w['pk']]
    return max(w, key=lambda w: len(w['pk']))['pk'] if w else []


def legs(pk, gap=6, eps=0.05):
    """The Watcher's flights: runs of move packets at most `gap` ticks apart (he sends none while
    hovering in place). Each leg: dep (departure, back-extrapolated from the first packet at the
    leg's speed), arr (last packet), frm, to, v (median blocks per tick), npk."""
    segs, cur = [], [pk[0]] if pk else []
    for i in range(1, len(pk)):
        moved = math.dist(pk[i - 1][1:4], pk[i][1:4]) > eps
        if pk[i][0] - pk[i - 1][0] <= gap and moved:
            cur.append(pk[i])
        else:
            if len(cur) > 1:
                segs.append(cur)
            cur = [pk[i - 1], pk[i]] if moved else [pk[i]]
    if len(cur) > 1:
        segs.append(cur)
    out = []
    for sg in segs:
        sp = [math.dist(sg[i - 1][1:4], sg[i][1:4]) / (sg[i][0] - sg[i - 1][0]) for i in range(2, len(sg)) if sg[i][0] > sg[i - 1][0]]
        v = sorted(sp)[len(sp) // 2] if sp else None
        d0 = math.dist(sg[0][1:4], sg[1][1:4])
        dep = sg[1][0] - d0 / v if v else sg[0][0]
        out.append({'dep': dep, 'arr': sg[-1][0], 'frm': sg[0][1:4], 'to': sg[-1][1:4], 'v': v, 'npk': len(sg)})
    return out


def flights(r):
    """Skull flights: launch (back-extrapolated from the first move packet), arrive (removal),
    v, slot, dest, and the mob that appeared on arrival."""
    out = []
    for i, s in r['skulls'].items():
        pk = s['pk']
        if len(pk) < 3:
            continue
        tot = math.dist(pk[0][1:], pk[-1][1:]); dt = pk[-1][0] - pk[0][0]
        if dt <= 0 or tot < 3:
            continue
        v = tot / dt
        d0 = math.dist(s['slot'], pk[0][1:])
        f = {'id': i, 'slot': s['slot'], 'skin': s['skin'], 'launch': pk[0][0] - d0 / v, 'v': v, 'dest': pk[-1][1:],
             'arrive': s['gone'], 'dist': math.dist(s['slot'], pk[-1][1:]), 'mob': None, 'mob_sn': None, 'mob_pos': None}
        if f['arrive'] is not None:
            c = [m for m in r['mobs'] if abs(m['sn'] - f['arrive']) <= 3 and m['name'] in L.REGULAR | L.BOSSES]
            if c:
                c.sort(key=lambda m: abs(m['sn'] - f['arrive']))
                f['mob'], f['mob_sn'], f['mob_pos'] = c[0]['name'], c[0]['sn'], c[0]['pos']
        out.append(f)
    out.sort(key=lambda f: f['launch'])
    return out


def model(r):
    D = door(r)
    if D is None:
        return None
    mobs = [m for m in r['mobs'] if m['name'] in L.REGULAR | L.BOSSES]
    complete = len([m for m in mobs if m['name'] in L.REGULAR]) == 17 and all(m['gn'] for m in mobs)
    return {'D': D, 'L1': line(r, L.DIALOG_LINES[0]), 'H': line(r, "Let's see"), 'P': line(r, 'You have proven'),
            'E': line(r, 'That will be enough'), 'fl': flights(r), 'legs': legs(best_watcher(r)) if r['watcher'] else [],
            'mobs': mobs, 'complete': complete}


def death(m):
    return m['gn'] - L.DEATH_TO_REMOVAL if m['gn'] is not None else None


def alive_at(mobs, n):
    return sum(1 for m in mobs if m['sn'] <= n and m['gn'] is not None and death(m) > n)


def post_legs(m):
    return [l for l in m['legs'] if m['H'] is not None and l['dep'] > m['H'] - 5 and l['npk'] >= 3]


def stops(m):
    """Each arrival with the skull launches from that spot before the next departure."""
    out, Ls = [], m['legs']
    for i, l in enumerate(Ls):
        nxt = Ls[i + 1] if i + 1 < len(Ls) else None
        la = [f for f in m['fl'] if f['launch'] >= l['arr'] - 12 and (nxt is None or f['launch'] < nxt['dep'] + 3)
              and math.dist(f['slot'], l['to']) < 4]
        out.append({'arr': l['arr'], 'to': l['to'], 'dep': nxt['dep'] if nxt else None, 'fl': la, 'leg': l})
    return out
