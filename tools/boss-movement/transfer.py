"""Storm's flight from Purple to Yellow after crush 1, and whether crush 2 can land a check early.

    python3 transfer.py OUT_DIR        # OUT_DIR as for analyze.py (after extract.py and tracks.py)

Works on the per-run timelines (OUT_DIR/tracks/*.json): Storm's recovered server positions
("pkt", n = server ticks), the chat lines and the players. Prints the numbers behind
"Storm's flight between pillars" and "Can crush 2 come a check earlier?" in
docs/maxor-storm-movement.md. Alpha-server runs are left out (recording.ALPHA_RUNS).
"""
import bisect
import glob
import json
import math
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import recording as R  # noqa: E402

MOVE = 0.7157       # Storm's full-speed move between pillars, blocks a server tick (3D)
H_MOVE = 0.712      # its horizontal part, at the height he loses on the way
POINT = (46.0, 172.8, 65.0)


def quantiles(v, ps=(0, 0.1, 0.25, 0.5, 0.75, 0.9), nd=1):
    v = sorted(v)
    return [round(v[min(len(v) - 1, int(len(v) * p))], nd) for p in ps] if v else []


def load_flights(out_dir):
    """Every Purple crush 1 on server ticks: Storm's packets from it to crush 2."""
    out = []
    for path in sorted(glob.glob(os.path.join(out_dir, 'tracks', '*.json'))):
        d = json.load(open(path))
        if any(r in R.ALPHA_RUNS for r in d['recs']) or 'storm' not in d['bosses']:
            continue
        if any(v['server_ticks'] == 0 for v in d['lag'].values()):
            continue
        ev = d['events']
        if R.STORM_START not in ev:
            continue
        s = ev[R.STORM_START][0]
        crushes = sorted(n for k in R.STORM_CRUSHED for n in ev.get(k, []) if n >= s)
        enrage = sorted(n for n in ev.get(R.STORM_ENRAGED, []) if n >= s)
        if not crushes or not enrage:
            continue
        c1 = crushes[0]
        c2 = crushes[1] if len(crushes) > 1 else c1 + 400
        pk = []
        for p in d['bosses']['storm']['pkt']:
            if not (c1 - 2 <= p[0] <= c2 + 2):
                continue
            if pk and p[0] <= pk[-1][0]:
                continue
            # The de-lerp turns the recorder's rounding into tiny fake moves: drop them.
            if pk and math.dist(p[1:4], pk[-1][1:4]) < 0.08 and p[6] != 's':
                continue
            pk.append((p[0], p[1], p[2], p[3]))
        pin = next((p for p in pk if p[0] >= c1 - 1), None)
        if len(pk) < 10 or pin is None or not (97 <= pin[1] <= 103.5 and 62 <= pin[3] <= 68.5):
            continue
        out.append(dict(group=d['group'], party=len(d['party']), s=s, c1=c1, e1=enrage[0], c2=c2, pin=pin, pk=pk, players=d['players']))
    return out


def cross(pk, xv, after=-1e9):
    """Server tick at which Storm's x first passes below xv (interpolated between packets)."""
    for a, b in zip(pk, pk[1:]):
        if a[0] >= after and a[1] > xv >= b[1] and b[0] - a[0] <= 4:
            return a[0] + (b[0] - a[0]) * (a[1] - xv) / (a[1] - b[1])
    return None


def turn_west(f):
    """First packet interval heading west (yaw 65-115) after he starts moving from the pin."""
    pk = [p for p in f['pk'] if p[0] >= f['c1'] - 1]
    for a, b in zip(pk, pk[1:]):
        if math.dist(b[1:4], pk[0][1:4]) < 0.1:
            continue
        yaw = math.degrees(math.atan2(-(b[1] - a[1]), b[3] - a[3]))
        if 65 <= yaw <= 115:
            return a[0] + 1
    return None


def closest_player(f, n, pos):
    best = None
    for name, rows in f['players'].items():
        ts = [q[0] for q in rows]
        i = bisect.bisect_right(ts, n) - 1
        if i < 0 or n - rows[i][0] > 30 or rows[i][2] < 150:
            continue
        dd = math.dist(rows[i][1:4], pos)
        if best is None or dd < best[0]:
            best = (dd, rows[i])
    return best


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    F = load_flights(sys.argv[1])
    print(len(F), 'Purple crush-1 flights on server ticks (alpha runs left out)')

    # 1. When the flight starts.
    turns = [t - f['e1'] for f in F for t in [turn_west(f)] if t is not None]
    print('\nturns west, ticks after "Storm is enraged!":', quantiles(turns, (0.1, 0.25, 0.5, 0.75, 0.9)),
          ' (2-3 in %d of %d)' % (sum(1 for x in turns if 2 <= x <= 3), len(turns)))
    print('pin (crush line -> enrage line):', quantiles([f['e1'] - f['c1'] for f in F]))

    # 2. Full speed: whole moves of 0.7157, one a server tick.
    per_move = []
    for f in F:
        for a, b in zip(f['pk'], f['pk'][1:]):
            if 66 <= b[1] < a[1] <= 97:
                L = math.dist(a[1:4], b[1:4])
                k = round(L / MOVE)
                if k >= 1 and abs(L / k - MOVE) < 0.1:
                    per_move.append(L / k)
    print('\nper-move length far from Yellow (x 66-97):', quantiles(per_move, (0.05, 0.25, 0.5, 0.75, 0.95), 3))

    # 3. Skipped moves on the straight (x 94 -> 74): whole ticks lost against one move a tick.
    lost = []
    for f in F:
        pk = [p for p in f['pk'] if f['c1'] < p[0] < f['c2']]
        n1, n2 = cross(pk, 94), cross(pk, 74)
        if n1 is None or n2 is None:
            continue
        seg = [p for p in pk if n1 <= p[0] <= n2 + 4 and p[1] >= 73]
        if len(seg) < 6 or any(q[0] - p[0] > 4 for p, q in zip(seg, seg[1:])):
            continue
        path = sum(math.dist(p[1:4], q[1:4]) for p, q in zip(seg, seg[1:]))
        lost.append((seg[-1][0] - seg[0][0]) - path / MOVE)
    hist = {}
    for x in lost:
        hist[round(x)] = hist.get(round(x), 0) + 1
    print('ticks lost on x 94->74 (per flight, rounded):', sorted(hist.items()))

    # 4. Slowing down near Yellow: times from x 72, the fastest tenth (few or no skips).
    print('\nfrom x 72, ticks to reach x:')
    for xv in (64, 60, 56, 53, 51, 49):
        v = []
        for f in F:
            pk = [p for p in f['pk'] if f['c1'] < p[0] <= f['c2'] + 1]
            a, b = cross(pk, 72), cross(pk, xv)
            if a is not None and b is not None:
                v.append(b - a)
        print('  x %d: n %d  %s (min p10 p25 median)' % (xv, len(v), quantiles(v, (0, 0.1, 0.25, 0.5))))

    # 5. The last blocks (x 53 -> 49): he chases the closest player there.
    print('\nx 53 -> 49 by where the closest player stands (x):')
    bins = {}
    for f in F:
        pk = [p for p in f['pk'] if f['c1'] < p[0] <= f['c2'] + 1]
        a, b = cross(pk, 53), cross(pk, 49)
        if a is None or b is None:
            continue
        pos = next(p for p in pk if p[0] >= a)[1:4]
        cp = closest_player(f, a, pos)
        if cp is None:
            continue
        x = cp[1][1]
        key = '<30' if x < 30 else '30-37' if x < 37 else '37-43' if x < 43 else '43-50' if x < 50 else '>=50'
        bins.setdefault(key, []).append(b - a)
    for k in ('<30', '30-37', '37-43', '43-50', '>=50'):
        if k in bins:
            print('  %-6s n %3d  min %.1f  median %.1f' % (k, len(bins[k]), min(bins[k]), statistics.median(bins[k])))

    # 6. Crush 2 on the +80 check (t 779): each recorded straight and slow-down, after an ideal
    #    start (pinned at x, enrage on the crush tick, turning west 2.5 ticks later).
    legs = []
    for f in F:
        pk = [p for p in f['pk'] if f['c1'] < p[0] <= f['c2'] + 1]
        n95, n72, n49 = cross(pk, 95), cross(pk, 72), cross(pk, 49)
        if n95 and n72 and n49 and n95 < n72 < n49:
            legs.append((n72 - n95) + (n49 - n72))
    print('\nin Yellow\'s zone (x <= 49) by the t 779 check, given an ideal start:')
    for pinx in (97.5, 97.8, 98.5, 99.5, 100.5):
        arr = [699 + 2.5 + (pinx - 95) / H_MOVE + leg for leg in legs]
        print('  pinned at x %.1f: best %.1f  p10 %.1f  median %.1f  in time %d/%d' % (
            pinx, min(arr), sorted(arr)[len(arr) // 10], statistics.median(arr), sum(1 for a in arr if a <= 779), len(arr)))


if __name__ == '__main__':
    main()
