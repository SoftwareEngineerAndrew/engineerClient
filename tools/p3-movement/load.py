import os
import json,csv
def boxes():
    out=[]
    for r in csv.DictReader(open(os.environ.get('SPOTS', os.path.join(os.path.dirname(__file__), '../../docs/mechanics/p3-movement/spots.csv')))):
        v=[float(r[k]) for k in ('x1','y1','z1','x2','y2','z2')]
        lo=[min(v[i],v[i+3]) for i in range(3)];hi=[max(v[i],v[i+3]) for i in range(3)]
        out.append((r['name'],lo,hi))
    return out
B=boxes()
def inbox(p,b,ext=True):
    # block-coordinate boxes: inclusive of upper block => hi+1
    return all(b[1][i]<=p[i]<b[2][i]+1 for i in range(3))
def spot(p):
    return [b[0] for b in B if inbox(p,b)]
def load(s):
    me=[]
    for l in open(s+'.jsonl'):
        if l.startswith('{"k":"me"') or l.startswith('{"k": "me"'):
            d=json.loads(l); me.append(d)
    return me
