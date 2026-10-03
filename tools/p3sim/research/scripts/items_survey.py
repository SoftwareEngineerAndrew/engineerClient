"""Survey own Better PF runs: GUI titles, and chat lines matching item keywords (counts).

    python3 items_survey.py [glob]
"""
import collections, glob, gzip, json, re, sys
RUNS = sys.argv[1] if len(sys.argv) > 1 else \
    '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/betterpf/runs/*.gz'
KW = re.compile(r'leap|warp|Implosion|mana|cloak|Creeper|Veil|Bonzo|Spirit|Phoenix|Mask|died|ghost|revive'
                r'|blocks in the way|TNT|Superboom|Dungeonbreaker|cooldown|exploded|Wither Impact|Withered|saved|Second Wind', re.I)
titles = collections.Counter(); chats = collections.Counter(); ex = {}
def norm(m): return re.sub(r'\d[\d,.]*', '#', m)
for f in sorted(glob.glob(RUNS)):
    try:
        for line in gzip.open(f, 'rt'):
            if '"gui"' in line or '"chat"' in line:
                l = json.loads(line)
                if l['k'] == 'gui': titles[l['title']] += 1
                elif l['k'] == 'chat' and KW.search(l['m']):
                    k = norm(l['m']); chats[k] += 1; ex.setdefault(k, (f.split('/')[-1], l['t'], l['m']))
    except Exception as e: print('ERR', f, e, file=sys.stderr)
print('== GUI titles'); [print(c, repr(t)) for t, c in titles.most_common(60)]
print('== chat'); [print(c, repr(k)[:220]) for k, c in chats.most_common(200)]
