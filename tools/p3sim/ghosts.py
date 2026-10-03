"""The P3 Sim's ghost parties: real fast F7 P3s from Better PF, for the sim's other four players to
replay exactly (their paths, held items, skins, and the stations they did when they did them).

    python3 tools/p3sim/ghosts.py DATA_DIR GOLDOR_OUT [COUNT]

DATA_DIR as for tools/boss-mechanics (runs.json, runs/<id>.gz); GOLDOR_OUT the directory
tools/boss-mechanics/goldor's extract.py, doors.py and sections.py wrote. Writes
src/main/resources/assets/engineerclient/p3sim/ghosts.json.gz: the COUNT (default 25) fastest runs
whose recording saw every one of the 29 completions with who did it, five players of five classes.

Everything is on the server-tick clock n of goldor.md (0 = "Who dares trespass into my domain?").
"""
import gzip
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'boss-mechanics', 'goldor'))
import glib as G  # noqa: E402
import roledata as R  # noqa: E402

# roledata's station keys -> the sim's (Station.id).
KEYS = {
    'S1 lever E': 'S1 east lever', 'S1 lever W': 'S1 west lever',
    'S2 lever low': 'S2 low lever', 'S2 lever high': 'S2 high lever',
    'S3 lever W': 'S3 west lever', 'S3 lever E': 'S3 east lever',
    'S4 lever low': 'S4 low lever', 'S4 lever high': 'S4 high lever',
    'S1 Simon Says': 'S1 SS', 'S2 Lights': 'S2 Lights', 'S3 Arrow Align': 'S3 Arrows', 'S4 target': 'S4 Target',
}
CLASSES = {'HEALER', 'BERSERK', 'ARCHER', 'TANK', 'MAGE'}
BEFORE = 40     # ticks of track kept before Goldor's first line (the drop from Storm)
AFTER = 160     # and after the core opens (Goldor flies and dies, everyone in)


def resolve(rec, comps):
    """Completions roledata could not pin to a station: by elimination, the station of that kind left
    in the section that is nearest to the one who did it."""
    used = {c['key'] for c in comps if c['key'] is not None}
    for c in comps:
        if c['key'] is not None:
            continue
        sec = 1 + sum(1 for k in (1, 2, 3) if rec.doors.get(k) is not None and rec.doors[k] <= c['n'])
        left = [k for k, (kind, s, _) in R.STATIONS.items() if kind == c['kind'] and s == sec and k not in used]
        tr = rec.players.get(c['actor'])
        p = tr.at(c['n']) if tr else None
        if not left:
            continue
        if len(left) > 1 and p is None:
            continue
        key = left[0] if len(left) == 1 else min(left, key=lambda k: G.dist(p, R.spot(k)))
        c['key'] = key
        used.add(key)


def main():
    data_dir, out_dir = sys.argv[1], sys.argv[2]
    count = int(sys.argv[3]) if len(sys.argv) > 3 else 25
    classes = {r['group']: dict((n, c) for n, c in r.get('party') or []) for r in G.run_list(data_dir)}
    recs = R.load(out_dir)
    X = {x['id']: x for x in G.load_extracts(out_dir, need_st=True).values()}
    good = []
    for rec in recs:
        st = rec.section_times()
        if st is None or rec.core is None:
            continue
        party = classes.get(rec.group, {})
        if len(party) != 5 or set(party.values()) != CLASSES:
            continue
        comps = [dict(c) for c in rec.comps if c['src'] == 'chat']
        if len(comps) != 29 or any(c['actor'] not in party for c in comps):
            continue
        resolve(rec, comps)
        if any(c['key'] is None for c in comps) or len({c['key'] for c in comps}) != 29:
            continue
        x = X[rec.id]
        if any(name not in x['players'] for name in party):
            continue
        good.append((rec.core, rec, x, party, comps))
    # One recording per run (the fastest view of it), the fastest runs.
    good.sort(key=lambda g: g[0])
    seen, picked = set(), []
    for g in good:
        if g[1].group in seen:
            continue
        seen.add(g[1].group)
        picked.append(g)
        if len(picked) == count:
            break
    items, item_ix = [], {}

    def item(s):
        if s not in item_ix:
            item_ix[s] = len(items)
            items.append(s)
        return item_ix[s]

    runs = []
    for core, rec, x, party, comps in picked:
        clock = G.Clock(x['st'])
        n0 = clock.n(x['goldor'])
        lo, hi = -BEFORE, core + AFTER
        skins = {}
        for l in G.read_lines(G.run_path(data_dir, rec.id)):
            if l.get('k') == 'skin' and l['name'] in party:
                skins.setdefault(l['name'], l['tex'])
        players = []
        for name, cls in party.items():
            rows, last_n = [], None
            for r in x['players'][name]:
                n = clock.n(r[0]) - n0
                if n < lo or n > hi:
                    continue
                row = [n, round(r[1], 2), round(r[2], 2), round(r[3], 2), round(r[4]), round(r[5]), item(r[6] if len(r) > 6 else '')]
                if rows and rows[-1][0] == n:
                    rows[-1] = row   # several client ticks in one server tick: the last
                else:
                    rows.append(row)
            players.append({'name': name, 'class': cls, 'skin': skins.get(name, ''), 'track': rows})
        gates = {}
        for k, n in rec.gates.items():
            centre = R.GATES[('gate 1/2', 'gate 2/3', 'gate 3/4')[k - 1]][1]
            near = sorted((G.dist(rec.players[p].at(n) or (1e9, 1e9, 1e9), centre), p) for p in party if p in rec.players)
            gates[k] = [n, near[0][1] if near and near[0][0] < 12 else None]
        runs.append({
            'id': rec.id, 'self': rec.self,
            'doors': {k: rec.doors[k] for k in (1, 2, 3)}, 'core': rec.core,
            'sections': rec.section_times(), 'gates': gates,
            'comps': [[c['n'], KEYS.get(c['key'], c['key']), c['actor']] for c in sorted(comps, key=lambda c: c['n'])],
            'players': players,
        })
        print(rec.id, 'P3', rec.core, rec.section_times(), {p: c for p, c in party.items()})
    out = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'engineerclient', 'p3sim', 'ghosts.json.gz')
    with gzip.open(out, 'wt') as f:
        json.dump({'_doc': 'tools/p3sim/ghosts.py: real F7 P3s for the sim\'s ghost party (n = server ticks after Goldor\'s first line)',
                   'items': items, 'runs': runs}, f, separators=(',', ':'))
    print(len(runs), 'runs ->', out, os.path.getsize(out), 'bytes')


if __name__ == '__main__':
    main()
