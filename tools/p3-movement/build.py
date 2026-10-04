import os
import csv,json,os,collections,bisect
from load import B
from legs import parse,itemid
R=os.environ.get("RECORDINGS", os.path.expanduser("~/.local/share/PrismLauncher/instances/26.1.2 BRW/minecraft/engineerclient-recordings/"))
srv={}
for d in os.listdir(R):
    if d.startswith(('2026-10-03','2026-10-04')):
        try: m=json.load(open(R+d+'/manifest.json')); srv[d[-6:]]=((m.get('meta') or {}).get('server') or m.get('server') or 'None')
        except: srv[d[-6:]]='?'
rows=list(csv.DictReader(open('legs.csv')))
for r in rows: r['server']=srv.get(r['run'],'?'); r['onfoot']=not any(k in r['method'] for k in ('leap','teleport','etherwarp'))
cols=['run','server','leg_from','leg_to','start_n','end_n','ticks','seconds','path_blocks','method','start_t','end_t','start_reason','start_kind','tp_blocks','ticks_n']
with open('legs.csv','w',newline='') as f:
    w=csv.DictWriter(f,fieldnames=cols,extrasaction='ignore'); w.writeheader(); w.writerows(rows)
pairs=collections.defaultdict(list)
for r in rows: pairs[(r['leg_from'],r['leg_to'])].append(r)
key=lambda r:(int(r['ticks']),0 if r['start_kind']=='settled' else 1,r['run'],int(r['start_n']))
out=[]
def fm(r,p): return {p+'_ticks':r['ticks'],p+'_seconds':r['seconds'],p+'_run':r['run'],p+'_server':r['server'],p+'_start_n':r['start_n'],p+'_start_kind':r['start_kind'],p+'_path_blocks':r['path_blocks'],p+'_method':r['method']} if r else {p+'_ticks':''}
for (a,b),L in sorted(pairs.items()):
    F=[r for r in L if r['onfoot']]; S=[r for r in F if r['start_kind']=='settled']; AL=[r for r in L]
    d=dict(leg_from=a,leg_to=b,count=len(L),n_onfoot=len(F),n_settled=len([r for r in L if r['start_kind']=='settled']))
    d.update(fm(min(F,key=key) if F else None,'foot_pb')); d.update(fm(min(S,key=key) if S else None,'foot_settled_pb')); d.update(fm(min(AL,key=key),'any_pb'))
    out.append(d)
fn=sorted({k for d in out for k in d})
order=['leg_from','leg_to','count','n_onfoot','n_settled']+[k for k in fn if k.startswith('foot_pb')]+[k for k in fn if k.startswith('foot_settled')]+[k for k in fn if k.startswith('any_pb')]
w=csv.DictWriter(open('pb_summary.csv','w',newline=''),fieldnames=order,restval=''); w.writeheader(); w.writerows(out)
# S1
S1=['ss','s1 t1','s1 t2','s1 t3','s1 t4','s1 left lever','s1 right lever','s1 right path']
S1=[x if x=='ss' else x for x in S1]; nm=lambda x:'at '+x
D={(d['leg_from'],d['leg_to']):d for d in out}
lines=[];missing=[];noseen=[]
for a in S1:
    for b in S1:
        if a==b: continue
        d=D.get((nm(a),nm(b)))
        if not d: noseen.append((a,b)); continue
        if d['foot_pb_ticks']=='': missing.append((a,b,d['count'])); continue
        lines.append((a,b,d))
json.dump(dict(S1=[(a,b,{k:v for k,v in d.items()}) for a,b,d in lines],missing_onfoot=missing,never_seen=noseen),open('s1_report.json','w'),indent=1)
print(len(lines),len(missing),len(noseen))
for a,b,d in lines:
    print(f"{a}>{b}|n{d['count']}/{d['n_onfoot']}|{d['foot_pb_ticks']}t {d['foot_pb_run']} {d['foot_pb_start_kind'][:4]} {d['foot_pb_method']}|settled {d['foot_settled_pb_ticks']}t {d.get('foot_settled_pb_run','')}|any {d['any_pb_ticks']}")
print('NO ONFOOT',missing);print('NEVER',noseen)
# review.json
runs=sorted({r['run'] for r in rows})
cache={}
def runinfo(s):
    if s in cache: return cache[s]
    me,acts,leaps,inv=parse(s)
    hb={};invt=[]
    for d in sorted(inv,key=lambda d:d['seq']):
        for sl,it in d['s']:
            if sl<9: hb[sl]=itemid(it)
        invt.append((d['seq'],dict(hb)))
    iseq=[x[0] for x in invt]
    T=[m['t'] for m in me]
    cache[s]=(me,invt,iseq,T); return cache[s]
def track(r):
    me,invt,iseq,T=runinfo(r['run'])
    t0=int(r['start_t'])-10;t1=int(r['end_t'])+10
    i0=bisect.bisect_left(T,t0);i1=bisect.bisect_right(T,t1)
    tr=[]
    for m in me[i0:i1]:
        j=bisect.bisect_right(iseq,m['seq'])-1
        h=invt[j][1].get(m['slot']) if j>=0 else None
        i=m['in']; ins=''.join(c if i[k] else '.' for k,c in enumerate('WSADJ'))
        tr.append([m['t'],round(m['pos'][0],3),round(m['pos'][1],3),round(m['pos'][2],3),round(m['rot'][0],2),round(m['rot'][1],2),h,ins,int(bool(m['shift'] or m['crouch'])),int(bool(m['sprint']))])
    return tr
pl=[];bb=[1e9,1e9,1e9,-1e9,-1e9,-1e9]
for (a,b),L in sorted(pairs.items()):
    F=sorted([r for r in L if r['onfoot']],key=key)[:3]
    lp=sorted([r for r in L if not r['onfoot']],key=key)
    sel=[(r,False) for r in F]
    if lp and (not F or int(lp[0]['ticks'])<int(F[0]['ticks'])): sel.append((lp[0],True))
    legs=[]
    for k,(r,isl) in enumerate(sel):
        tr=track(r)
        for p in tr:
            for q in range(3): bb[q]=min(bb[q],p[1+q]);bb[3+q]=max(bb[3+q],p[1+q])
        legs.append(dict(rank=(k+1 if not isl else 'leap-PB'),leap=isl,run=r['run'],server=r['server'],start_kind=r['start_kind'],ticks=int(r['ticks']),seconds=float(r['seconds']),method=r['method'],start_n=int(r['start_n']),end_n=int(r['end_n']),start_t=int(r['start_t']),end_t=int(r['end_t']),path_blocks=float(r['path_blocks']),track=tr))
    pl.append({'from':a,'to':b,'legs':legs})
    if len(pl)%40==0: print(len(pl),flush=True)
js=dict(note="track sample=[t,x,y,z,yaw,pitch,heldSbId,inputs,sneaking,sprinting]; inputs = W S A D J by position, '.' = not held; track covers start_t-10..end_t+10 client ticks; leg start/end ticks are start_t/end_t; rank 'leap-PB' = fastest leg containing a leap/teleport, only when faster than the on-foot PB",
  pairs=pl,boxes=[dict(name=n,lo=lo,hi=[h+1 for h in hi]) for n,lo,hi in B],tracks_bbox=dict(min=bb[:3],max=bb[3:]))
json.dump(js,open('review.json','w'),separators=(',',':'))
print(os.path.getsize('review.json')/1e6,'MB',len(pl),'pairs')
