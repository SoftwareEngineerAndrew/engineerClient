"""Pre-fire with ignored collisions. After the player's move (AI to move, board b), a pre-fire m is SAFE when for every AI
reply r != m, m doesn't lose after r (if r == m the click is ignored and the player answers normally). The player wants to
pre-fire as often as possible without ever risking the draw. E(b): the expected number of player moves still to come that
get pre-fired (AI uniform over its optimal replies), playing to maximise it; N(b): moves still to come."""
import functools
from ai import value, winner
from prefire import ai_replies, safe, name
def put(b, i, t): return b[:i] + t + b[i+1:]
def done(b): return bool(winner(b)) or '.' not in b
def safe_prefires(b):
    R = ai_replies(b)
    return [m for m in range(9) if b[m] == '.' and all(r == m or done(put(b, r, 'A')) or m in safe(put(b, r, 'A')) for r in R)], R
@functools.lru_cache(None)
def E(b):
    """b: AI to move. (expected pre-fired player moves, expected player moves) from here, best play."""
    if done(b): return (0.0, 0.0)
    pre, R = safe_prefires(b)
    best = None
    for m in pre + [None]:
        e = n = 0.0
        for r in R:
            a = put(b, r, 'A')
            if done(a): continue
            if m is not None and m != r:
                x = E(put(a, m, 'P')); e += 1 + x[0]; n += 1 + x[1]
            else:   # react: the best safe move after seeing r
                xs = [E(put(a, q, 'P')) for q in safe(a)]
                x = max(xs); e += x[0]; n += 1 + x[1]
        e /= len(R); n /= len(R)
        if best is None or e > best[0]: best = (e, n, m)
    return best[:2]
if __name__ == '__main__':
    for o in (0, 1, 4):
        b = put('.' * 9, o, 'A')
        res = sorted(((E(put(b, m, 'P')), name(m)) for m in safe(b)), reverse=True)
        (e, n), m = res[0]
        print(f"AI opens {name(o)} ({'corner' if o in (0,2,6,8) else 'centre' if o == 4 else 'edge'}): best first move {m}: "
              f"{e:.2f} of {n:.2f} later moves pre-fired on average ({100*e/n:.0f}%)  | others {[(mm, round(x[0]/x[1]*100)) for x, mm in res[1:]]}")
