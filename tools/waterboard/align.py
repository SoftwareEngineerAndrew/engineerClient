"""Tick-exact comparison of the sim with a recording.

Every room write is grouped into arrival batches (same ms). A batch is one server tick's send (or part of one). Board-cell
writes (z=26) are compared with the sim; unchanged solid re-sends (stairs reshaped by the protocol translator) are dropped.
Ticks are found by a DP over (batch, tick): matches score, gaps between ticks that disagree with the gaps between arrival
times cost. A lever flip happens on the tick of the batch its packet came in. Alternates DP and simulation until stable."""
import itertools, sys
from harness import *

MOVING = -3          # a moving piston: solid for water, but a different block than the one that lands


def view2(s):
    b = s.replace('minecraft:', '')
    if b.startswith('moving_piston'): return MOVING
    return view_of(s)


def batches_of(d):
    B = d['blocks']; t0 = d['writes'][0][0]
    last = {}; out = []
    lever_state = {}
    for ms, g in itertools.groupby(d['writes'], key=lambda w: w[0]):
        items = set(); levers = []
        for _, seq, x, y, z, s in g:
            for i, p in enumerate(LEVER_POS):
                if (x, y, z) == p and 'lever' in s:
                    pw = 'powered=true' in s
                    if lever_state.get(i, False) != pw: levers.append(i)
                    lever_state[i] = pw
            if z == 26 and BX0 <= x <= BX1 and BY0 <= y <= BOARD_Y_TOP and (y <= 80 or x == 15):
                c = cell(x, y); v = view2(s)
                prev = last.get(c, view2(B.get(f'{x},{y},26', 'minecraft:air')))
                last[c] = v
                if v == SOLID and prev == SOLID: continue
                items.add((c, v))
        if items or levers: out.append((ms - t0, frozenset(items), levers))   # a re-sent lever (own clicks come back twice) is no tick
    return out


RANDOM = {}

def simulate2(geo, inn, gates, flip_ticks, n):
    s = WaterSim(geo, inn, gates)
    s.random_ticks = RANDOM
    sends = {}; dirty = {}
    orig_write = s.write
    landing = set()

    def write(c, v, send=True):
        old = s.cells[c]
        orig_write(c, v, send)
        if not send: return
        if old != v or c in landing:
            dirty.setdefault(c, None)
    s.write = write
    orig_toggle = s.toggle

    def toggle(k):
        orig_toggle(k)
    s.toggle = toggle

    def on_send():
        out = []
        for c in dirty:
            v = s.cells[c]
            vv = v & 15 if v >= 0 else v
            if v == SOLID and s.moving_at(c): vv = MOVING
            
            out.append((c, vv))
        sends[s.time] = frozenset(x for x in out if cy(x[0]) <= BOARD_Y_TOP)
        dirty.clear()
    s.on_send = on_send

    # moving piston bookkeeping: a pushed slot is MOVING from its block event until it lands (then re-sent as the block)
    def moving_at(c):
        for k, sc in enumerate(geo.slot_cell):
            if sc == c and s.land_at[k] is not None: return True
        return False
    s.moving_at = moving_at
    orig_step = s.step

    def step(flips=0):
        # cells landing this tick are re-sent even though SOLID -> SOLID
        landing.clear()
        for k, t in enumerate(s.land_at):
            if t == s.time + 1: landing.add(geo.slot_cell[k])
        orig_step(flips)
    s.step = step
    for _ in range(n):
        s.step(flip_ticks.get(s.time + 1, 0))
    return s, sends


def dp(batches, sends, off, flipped_batches, W=6, gap_cost=1.5, span=3):
    """tick per batch (the last tick it covers) maximizing matches minus timing inconsistency. A batch may carry up to
    [span] consecutive ticks' packets (a lagging server or network delivers them together)."""
    n = len(batches)
    cands = []
    for ms, items, _ in batches:
        g = round(ms / 50 + off)
        cands.append(list(range(g - W, g + W + 1)))
    def union(a, t):
        u = frozenset()
        for k in range(a, t + 1): u = u | sends.get(k, frozenset())
        return u
    def score(i, si):
        items = batches[i][1]
        if not items: return 0.0
        return len(items & si) * 2 - len(items - si) * 1.0 - 0.3 * len(si - items)
    best = [{t: (score(0, sends.get(t, frozenset())), None) for t in cands[0]}]
    for i in range(1, n):
        dms = (batches[i][0] - batches[i - 1][0]) / 50
        row = {}
        for t in cands[i]:
            bp = None; bv = -1e18
            for tp, (v, _) in best[i - 1].items():
                if tp > t or (tp == t and dms >= 0.4): continue
                # the ticks this batch carries: (tp, t], at most span of them (or just t when tp == t)
                a = max(tp + 1, t - span + 1) if tp < t else t
                sc = score(i, union(a, t)) if batches[i][1] else 0.0
                dt = t - tp
                pen = gap_cost * max(0.0, abs(dt - dms) - 1.0)
                if v + sc - pen > bv: bv, bp = v + sc - pen, tp
            if bp is not None: row[t] = (bv, bp)
        best.append(row)
    t = max(best[-1], key=lambda k: best[-1][k][0])
    path = [t]
    for i in range(n - 1, 0, -1):
        t = best[i][t][1]; path.append(t)
    return path[::-1]


def fit(geo, inn, gates, batches, iters=8):
    best = None
    for off0 in (0.0, 1.0, 2.0, 3.0, 4.0, 5.0):
        r = fit1(geo, inn, gates, batches, iters, off0)
        s, sends, ticks, ft = r
        m = sum(len(it & sends.get(t, frozenset())) for (ms, it, lv), t in zip(batches, ticks))
        if best is None or m > best[0]: best = (m, r)
    return best[1]


def fit1(geo, inn, gates, batches, iters, off0):
    off = off0
    ticks = [round(ms / 50 + off) for ms, _, _ in batches]
    for it in range(iters):
        ft = {}
        for i, (ms, items, levers) in enumerate(batches):
            for lv in levers: ft[ticks[i]] = ft.get(ticks[i], 0) ^ (1 << lv)
        s, sends = simulate2(geo, inn, gates, ft, ticks[-1] + 60)
        new = dp(batches, sends, off, None)
        if min(new) < 1:
            sh = 1 - min(new); new = [t + sh for t in new]
        # keep the global offset sensible: median deviation
        devs = sorted(t - ms / 50 for t, (ms, _, _) in zip(new, batches))
        off = devs[len(devs) // 2]
        if new == ticks: break
        ticks = new
    ft = {}
    for i, (ms, items, levers) in enumerate(batches):
        for lv in levers: ft[ticks[i]] = ft.get(ticks[i], 0) ^ (1 << lv)
    s, sends = simulate2(geo, inn, gates, ft, ticks[-1] + 60)
    return s, sends, ticks, ft


def report(path, show=True):
    d = json.load(open(path))
    rows = rows_from(d['blocks']); geo, inn = Geometry.parse(rows, d['rot'])
    gates = 0
    for o, z in enumerate(GATE_Z):
        if not d['blocks'].get(f'15,56,{z}', 'minecraft:air').endswith(':air'): gates |= 1 << o
    batches = batches_of(d)
    s, sends, ticks, ft = fit(geo, inn, gates, batches)
    fmt = lambda c, v: f'{cx(c)},{cy(c)}={v}'
    tot = sum(len(b[1]) for b in batches); ok = 0; exact = 0; nb = 0; bad = []
    for (ms, items, lv), t in zip(batches, ticks):
        if not items: continue
        si = sends.get(t, frozenset()); nb += 1
        ok += len(items & si); exact += items == si
        if items != si: bad.append((ms, t, items - si, si - items))
    seen = set(ticks)
    unsent = [(t, l) for t, l in sorted(sends.items()) if l and t not in seen and t <= max(ticks)]
    print(f'{d["rec"]} ({d["rot"]}): batches {exact}/{nb} exact, writes {ok}/{tot} matched, sim sends never seen: {len(unsent)} ticks')
    print('  flips:', sorted((t, [LEVERS[i] for i in range(7) if b >> i & 1]) for t, b in ft.items()))
    print('  outlet arrivals (sim):', [(t, COLORS[o]) for t, o in s.arrivals], ' gates closed after:', [COLORS[o] for o in range(5) if s.closed >> o & 1])
    if show:
        for ms, t, a, b in bad: print(f'   ms {ms:6} T{t}: server-only {sorted(fmt(*x) for x in a)} sim-only {sorted(fmt(*x) for x in b)}')
        for t, l in unsent: print(f'   T{t} unseen sim sends {sorted(fmt(*x) for x in l)}')
    return s, sends, ticks, ft, batches


if __name__ == '__main__':
    for p in sys.argv[1:]: report(p)
