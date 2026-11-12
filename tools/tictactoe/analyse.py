import json, glob, os, collections
AI = 30876
games = []
for f in sorted(glob.glob(os.path.dirname(os.path.abspath(__file__)) + '/games/*.json')):
    if os.path.getsize(f) == 0: continue
    d = json.load(open(f))
    # the cell's mark: the map of the frame that ended up there (a cell holds one mark per game; games split at resets)
    marks = {}
    for fid, c, m, _, first in d['frames']:
        if c: marks.setdefault(tuple(c), []).append((first, fid, m))
    seq = [(e[0], tuple(e[2])) for e in d['events'] if e[1] == 'button_gone' and e[2]]
    resets = sorted({e[0] for e in d['events'] if e[1] == 'button_back'})
    if not seq: continue
    # moves before any button vanished in view: frames already there
    pre = sorted((first, c) for c, l in marks.items() for first, fid, m in l if first < seq[0][0])
    moves = [(None, c) for _, c in pre] + seq
    out = []
    for ms, c in moves:
        ms_m = [m for first, fid, m in sorted(marks.get(c, []), key=lambda x: x[1])]
        who = 'AI' if ms_m and ms_m[0] == AI else 'P' if ms_m else '?'
        out.append((ms, c, who))
    games.append({'rec': d['rec'], 'moves': out, 'resets': resets, 'solved': any(e[1] == 'solved' for e in d['events']),
                  'clicks': [(e[0], tuple(e[2]) if e[2] else None) for e in d['events'] if e[1] == 'click']})
if __name__ == '__main__':
    for g in games:
        s = ' '.join(f"{w}{c[0]}{c[1]}" for ms, c, w in g['moves'])
        print(g['rec'][-6:], s, '| solved' if g['solved'] else '', f"| resets {len(g['resets'])}" if g['resets'] else '', f"| own clicks {len(g['clicks'])}" if g['clicks'] else '')
