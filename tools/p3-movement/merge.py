import csv,json,collections,sys,os,bisect
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from load import B
import bpflegs
REPO=os.path.join(os.environ.get('P3_DOCS', os.path.join(os.path.dirname(os.path.abspath(__file__)),'../../docs/mechanics/p3-movement')),'')
EXTRACTS=os.environ.get('P3_EXTRACTS','../poitimes')  # per-recording <6hex>.jsonl extracts
A=json.load(open('bpf_all.json'))
dupstamps={r['stamp'] for r in A['runs'] if r['dup']}
rows=list(csv.DictReader(open(REPO+'legs.csv')))
# the docs legs.csv may already be the merged file: keep only recorder rows, so re-running is idempotent
rows=[r for r in rows if r.get('source','recorder')=='recorder']
for r in rows: r['source']='recorder'
new=[l for l in A['legs'] if l['run'] not in dupstamps]
for l in new: l['server']=''
rows+=new
for r in rows: r['onfoot']=not any(k in r['method'] for k in ('leap','teleport','etherwarp'))
cols=['run','source','server','leg_from','leg_to','start_n','end_n','ticks','seconds','path_blocks','method','start_t','end_t','start_reason','start_kind','tp_blocks','ticks_n']
with open('legs.csv','w',newline='') as f:
    w=csv.DictWriter(f,fieldnames=cols,extrasaction='ignore');w.writeheader();w.writerows(rows)
pairs=collections.defaultdict(list)
for r in rows: pairs[(r['leg_from'],r['leg_to'])].append(r)
key=lambda r:(int(r['ticks']),0 if r['start_kind']=='settled' else 1,0 if r['source']=='recorder' else 1,r['run'],int(r['start_n']))
def fm(r,p): return {p+'_ticks':r['ticks'],p+'_seconds':r['seconds'],p+'_run':r['run'],p+'_source':r['source'],p+'_server':r['server'],p+'_start_n':r['start_n'],p+'_start_kind':r['start_kind'],p+'_path_blocks':r['path_blocks'],p+'_method':r['method']} if r else {p+'_ticks':''}
out=[]
for (a,b),L in sorted(pairs.items()):
    F=[r for r in L if r['onfoot']];S=[r for r in F if r['start_kind']=='settled']
    d=dict(leg_from=a,leg_to=b,count=len(L),n_recorder=sum(r['source']=='recorder' for r in L),n_bpf=sum(r['source']=='bpf' for r in L),n_onfoot=len(F),n_settled=len([r for r in L if r['start_kind']=='settled']))
    d.update(fm(min(F,key=key) if F else None,'foot_pb'));d.update(fm(min(S,key=key) if S else None,'foot_settled_pb'));d.update(fm(min(L,key=key),'any_pb'))
    out.append(d)
fn=sorted({k for d in out for k in d})
order=['leg_from','leg_to','count','n_recorder','n_bpf','n_onfoot','n_settled']+[k for k in fn if k.startswith('foot_pb')]+[k for k in fn if k.startswith('foot_settled')]+[k for k in fn if k.startswith('any_pb')]
w=csv.DictWriter(open('pb_summary.csv','w',newline=''),fieldnames=order,restval='');w.writeheader();w.writerows(out)
# S1 diff
old={(d['leg_from'],d['leg_to']):d for d in csv.DictReader(open(REPO+'pb_summary.csv'))}
newd={(d['leg_from'],d['leg_to']):d for d in out}
S1=['ss','s1 t1','s1 t2','s1 t3','s1 t4','s1 left lever','s1 right lever','s1 right path']
res=dict(changed=[],same=[],nofoot=[],unseen=[],improved_settled=[])
for a in S1:
    for b in S1:
        if a==b: continue
        k=('at '+a,'at '+b);n=newd.get(k);o=old.get(k)
        if not n: res['unseen'].append((a,b));continue
        nf=n['foot_pb_ticks'];of=o['foot_pb_ticks'] if o else ''
        if nf=='': res['nofoot'].append((a,b,n['count']))
        if nf!=of: res['changed'].append((a,b,of,nf,n['foot_pb_run'],n['foot_pb_start_kind'],n['foot_pb_method'],n['foot_pb_source']))
        ns=n['foot_settled_pb_ticks'];os_=o['foot_settled_pb_ticks'] if o else ''
        if ns!=os_: res['improved_settled'].append((a,b,os_,ns,n['foot_settled_pb_run']))
json.dump(res,open('s1_diff.json','w'),indent=1)
for k,v in res.items():
    print(k,len(v))
    for x in v: print('  ',x)
# review.json
_rp=os.path.join(EXTRACTS,'review.json')
oldrev=json.load(open(_rp)) if os.path.exists(_rp) else dict(pairs=[],boxes=[])
oldleg={}
for p in oldrev['pairs']:
    for l in p['legs']: oldleg[(l['run'],l['start_t'],p['from'],p['to'])]=l
trs={}
def bpf_track(r):
    st=r['run']
    if st not in trs:
        f=st+'_F7.jsonl.gz'
        if not os.path.exists(bpflegs.D+f):
            f=[x for x in os.listdir(bpflegs.D) if x.startswith(st)][0]
        info,_,_=bpflegs.process(f);trs.clear();trs[st]=(f,info['_tr'])
    pos,rot,hl,cr=trs[st][1]
    t0=max(0,int(r['start_t'])-10);t1=min(len(pos)-1,int(r['end_t'])+10)
    return [[t,round(pos[t][0],3),round(pos[t][1],3),round(pos[t][2],3),round(rot[t][0],2),round(rot[t][1],2),hl[t] or None,'?????',int(cr[t]==1),None] for t in range(t0,t1+1)]
rec_cache={}
def rec_track(r):
    run=r['run']
    if run not in rec_cache:
        rec_cache.clear()
        me,acts,leaps,inv=bpflegs_parse(run); hb={};invt=[]
        for d in sorted(inv,key=lambda d:d['seq']):
            for sl,it in d['s']:
                if sl<9: hb[sl]=(it.get('sb') or it.get('id')) if it else None
            invt.append((d['seq'],dict(hb)))
        rec_cache[run]=(me,invt,[x[0] for x in invt],[m['t'] for m in me])
    me,invt,iseq,T=rec_cache[run]
    t0=int(r['start_t'])-10;t1=int(r['end_t'])+10
    i0=bisect.bisect_left(T,t0);i1=bisect.bisect_right(T,t1);tr=[]
    for m in me[i0:i1]:
        j=bisect.bisect_right(iseq,m['seq'])-1
        h=invt[j][1].get(m['slot']) if j>=0 else None
        i=m['in'];ins=''.join(c if i[k] else '.' for k,c in enumerate('WSADJ'))
        tr.append([m['t'],round(m['pos'][0],3),round(m['pos'][1],3),round(m['pos'][2],3),round(m['rot'][0],2),round(m['rot'][1],2),h,ins,int(bool(m['shift'] or m['crouch'])),int(bool(m['sprint']))])
    return tr
def bpflegs_parse(s):
    import legs as L
    cwd=os.getcwd();os.chdir(EXTRACTS);
    try: return L.parse(s)
    finally: os.chdir(cwd)
pl=[];bb=[1e9,1e9,1e9,-1e9,-1e9,-1e9]
# order selection so each run's tracks are generated together (cache)
sels={}
for (a,b),L in sorted(pairs.items()):
    F=sorted([r for r in L if r['onfoot']],key=key)[:3]
    lp=sorted([r for r in L if not r['onfoot']],key=key)
    sel=[(r,False) for r in F]
    if lp and (not F or int(lp[0]['ticks'])<int(F[0]['ticks'])): sel.append((lp[0],True))
    sels[(a,b)]=sel
# generate tracks sorted by run
need=collections.defaultdict(list)
for k,sel in sels.items():
    for i,(r,isl) in enumerate(sel): need[(r['source'],r['run'])].append((k,i))
tracks={}
reused=0
for (src,run),lst in sorted(need.items()):
    for k,i in lst:
        r=sels[k][i][0]
        o=oldleg.get((r['run'],int(r['start_t']),k[0],k[1])) if src=='recorder' else None
        if o: tracks[(k,i)]=o['track'];reused+=1
        else: tracks[(k,i)]=rec_track(r) if src=='recorder' else bpf_track(r)
print('reused tracks',reused,'of',len(tracks))
for (a,b),sel in sorted(sels.items()):
    legs=[]
    for k,(r,isl) in enumerate(sel):
        tr=tracks[((a,b),k)]
        for p in tr:
            for q in range(3): bb[q]=min(bb[q],p[1+q]);bb[3+q]=max(bb[3+q],p[1+q])
        legs.append(dict(rank=(k+1 if not isl else 'leap-PB'),leap=isl,run=r['run'],source=r['source'],server=r['server'],start_kind=r['start_kind'],ticks=int(r['ticks']),seconds=float(r['seconds']),method=r['method'],start_n=int(r['start_n']),end_n=int(r['end_n']),start_t=int(r['start_t']),end_t=int(r['end_t']),path_blocks=float(r['path_blocks']),track=tr))
    pl.append({'from':a,'to':b,'legs':legs})
js=dict(note="track sample=[t,x,y,z,yaw,pitch,heldSbId,inputs,sneaking,sprinting]; inputs = W S A D J by position, '.' = not held (BPF legs, source 'bpf': inputs are '?????', sneaking = recorded crouching flag, sprinting = null/unknown); track covers start_t-10..end_t+10 client ticks; leg start/end ticks are start_t/end_t; rank 'leap-PB' = fastest leg containing a leap/teleport, only when faster than the on-foot PB; each leg has source 'recorder' (run = 6-hex recording id) or 'bpf' (run = Better PF file stamp yyyy-MM-dd_HH-mm-ss)",
  pairs=pl,boxes=oldrev['boxes'],tracks_bbox=dict(min=bb[:3],max=bb[3:]))
json.dump(js,open('review.json','w'),separators=(',',':'))
print(os.path.getsize('review.json')/1e6,'MB',len(pl),'pairs')
