"""Merge finished chunks into the shipped plans.json; print a summary per objective and against Odin's table."""
import json, glob, os, collections, sys
import os as _o; S = _o.environ.get("OUT") or _o.path.join(_o.path.dirname(_o.path.abspath(__file__)), "chunks")
chunks = sorted(f for f in glob.glob(S + '/*.json') if not f.endswith('.odin.json'))
root = None; plans = []; odin = []
for f in chunks:
    d = json.load(open(f)); root = root or d; plans += d['plans']
    o = f[:-5] + '.odin.json'
    if os.path.exists(o): odin += json.load(open(o))
print(len(chunks), 'chunks,', len(plans), 'plans')
root['plans'] = plans
if len(sys.argv) > 1:
    json.dump(root, open(sys.argv[1], 'w'), separators=(',', ':')); print('wrote', sys.argv[1], os.path.getsize(sys.argv[1]), 'bytes')
st = collections.defaultdict(list)
for p in plans + odin: st[p['objective']].append(p)
for k, v in sorted(st.items()):
    sv = [p for p in v if p['median'] > 0]
    print(f"{k:12} n={len(v):3}  solve rate mean {100*sum(p['solveRate'] for p in v)/len(v):5.1f}%  worst {100*min(p['solveRate'] for p in v):5.1f}%  "
          f"median solve {sum(p['median'] for p in sv)/max(1,len(sv))/20:5.2f}s  flips {sum(len(p['flips']) for p in v)/len(v):.1f}")
