import functools, analyse
LINES = [(0,1,2),(3,4,5),(6,7,8),(0,3,6),(1,4,7),(2,5,8),(0,4,8),(2,4,6)]
def winner(b):
    for a, c, d in LINES:
        if b[a] != '.' and b[a] == b[c] == b[d]: return b[a]
    return None
@functools.lru_cache(None)
def value(b, turn):
    """minimax value for 'A' (the AI): +1 AI wins, 0 draw, -1 player wins; shorter wins better."""
    w = winner(b)
    if w: return (10 - b.count('.')) * (1 if w == 'A' else -1)
    if '.' not in b: return 0
    vals = [value(b[:i] + turn + b[i+1:], 'P' if turn == 'A' else 'A') for i in range(9) if b[i] == '.']
    return max(vals) if turn == 'A' else min(vals)
def options(b):
    return {i: value(b[:i] + 'A' + b[i+1:], 'P') for i in range(9) if b[i] == '.'}
idx = lambda c: c[0] * 3 + c[1]
for g in analyse.games:
    seen = []; order = []
    for ms, c, w in g['moves']:
        if c in seen: continue
        seen.append(c); order.append(c)
    b = '.' * 9; turn = 'A'; notes = []
    for k, c in enumerate(order):
        i = idx(c)
        if turn == 'A' and k > 0:
            o = options(b); best = max(o.values()); opt = sorted(j for j, v in o.items() if v == best)
            notes.append(f"AI{c[0]}{c[1]}{'' if o[i] == best else '!SUBOPT'} of opt{opt}")
        elif turn == 'A':
            notes.append(f"AI-open{c[0]}{c[1]}")
        else:
            notes.append(f"P{c[0]}{c[1]}")
        b = b[:i] + turn + b[i+1:]; turn = 'P' if turn == 'A' else 'A'
    print(g['rec'][-6:], ' '.join(notes), '| result', winner(b) or ('draw-ish' if True else ''))
