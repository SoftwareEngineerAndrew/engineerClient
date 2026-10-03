"""Why runs fail ghosts.py's filters (counts per reason): python3 ghosts_diag.py DATA_DIR GOLDOR_OUT"""
import collections
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'boss-mechanics', 'goldor'))
import glib as G  # noqa: E402
import roledata as R  # noqa: E402

data_dir, out_dir = sys.argv[1], sys.argv[2]
classes = {r['group']: dict((n, c) for n, c in r.get('party') or []) for r in G.run_list(data_dir)}
X = {x['id']: x for x in G.load_extracts(out_dir, need_st=True).values()}
why = collections.Counter()
ncomp = collections.Counter()
for rec in R.load(out_dir):
    if rec.section_times() is None:
        why['no section times'] += 1; continue
    party = classes.get(rec.group, {})
    if len(party) != 5:
        why['party not 5'] += 1; continue
    if set(party.values()) != {'HEALER', 'BERSERK', 'ARCHER', 'TANK', 'MAGE'}:
        why['classes repeat'] += 1; continue
    comps = [c for c in rec.comps if c['src'] == 'chat']
    ncomp[len(comps)] += 1
    if len(comps) < 29:
        why['completion lines missing (chat cleaner)'] += 1; continue
    unkeyed = [c for c in comps if c['key'] is None]
    if unkeyed:
        why['station not resolved'] += 1; continue
    if any(c['actor'] not in party for c in comps):
        why['actor not in party'] += 1; continue
    if any(n not in X[rec.id]['players'] for n in party):
        why['a player never seen'] += 1; continue
    why['ok'] += 1
print(dict(why))
print('completion lines per recording:', sorted(ncomp.items()))
