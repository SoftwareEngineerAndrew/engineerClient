"""Can the player always know its next move before the AI's reply arrives?
AI: perfect, random among its minimax-optimal moves (what the recordings show). Player: must never lose (a draw solves).
A pre-fire strategy picks the player's next move m before the AI's reply r is known: m must be free whatever r is (never one
of the AI's possible replies) and keep the draw for every r. G(B) says whether that is possible from B (AI to move) all the
way to the end; P(B) is the best chance that every remaining move can be pre-fired when it can't be guaranteed."""
import functools
from ai import value, winner
def ai_replies(b):
    o = {i: value(b[:i] + 'A' + b[i+1:], 'P') for i in range(9) if b[i] == '.'}
    best = max(o.values()); return [i for i, v in o.items() if v == best]
def safe(b):            # player moves from b (player to move) that don't lose against perfect play
    return [i for i in range(9) if b[i] == '.' and value(b[:i] + 'P' + b[i+1:], 'A') <= 0]
@functools.lru_cache(None)
def G(b):
    """b: AI to move. True when the player can pre-commit every remaining move."""
    if winner(b) or '.' not in b: return True
    R = ai_replies(b)
    after = [b[:r] + 'A' + b[r+1:] for r in R]
    if all(winner(a) or '.' not in a for a in after): return True
    for m in range(9):
        if b[m] != '.' or m in R: continue
        ok = True
        for a in after:
            if winner(a) or '.' not in a: continue
            if m not in safe(a) or not G(a[:m] + 'P' + a[m+1:]): ok = False; break
        if ok: return True
    return False
@functools.lru_cache(None)
def P(b):
    """b: AI to move: the best chance (AI picks uniformly among its optimal moves) that every remaining player move can be
    pre-fired, choosing each pre-fire to maximise it; when a pre-fire isn't possible for some reply, that branch counts 0."""
    if winner(b) or '.' not in b: return 1.0
    R = ai_replies(b); best = 0.0
    for m in range(9):
        if b[m] != '.': continue
        tot = 0.0
        for r in R:
            a = b[:r] + 'A' + b[r+1:]
            if winner(a) or '.' not in a: tot += 1; continue
            if m == r or m not in safe(a): continue
            tot += P(a[:m] + 'P' + a[m+1:])
        best = max(best, tot / len(R))
    return best
name = lambda i: f"{i//3}{i%3}"
if __name__ == '__main__':
    for o in range(9):
        b = '.' * 9; b = b[:o] + 'A' + b[o+1:]
        res = []
        for m in safe(b):
            bb = b[:m] + 'P' + b[m+1:]
            res.append((G(bb), round(P(bb), 3), name(m)))
        res.sort(reverse=True)
        print(f"AI opens {name(o)}: first moves (guaranteed, chance, cell) {res}")
