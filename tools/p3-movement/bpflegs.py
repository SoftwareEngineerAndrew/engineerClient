import gzip,json,os,math,sys,bisect,csv
from collections import Counter
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from load import B,spot
D=os.path.join(os.environ.get("BPF_RUNS", os.path.expanduser("~/.local/share/PrismLauncher/instances/26.1.2 BRW/minecraft/config/engineerclient/betterpf/runs")),"")
THR=15.0;WIN=5
LEAPITEMS=('INFINITE_SPIRIT_LEAP','SPIRIT_LEAP')
def load(f):
    meta=None;P={};SW=[];CH=[];ST=[];end=None;crouch={}
    held={}
    for l in gzip.open(D+f,'rt'):
        if l.startswith('{"k":"p"'):
            d=json.loads(l)
            P_=d['d']
            for e in P_:
                if e[0]==meta['self']:
                    P[d['t']]=e
        elif l.startswith('{"k":"sw"'):
            d=json.loads(l)
            if meta['self'] in d['d']: SW.append(d['t'])
        elif l.startswith('{"k":"chat"'):
            d=json.loads(l); CH.append((d.get('t',0),d['m']))
        elif l.startswith('{"k":"st"'):
            d=json.loads(l); ST.append((d['t'],d['n']))
        elif l.startswith('{"k":"meta"'): meta=json.loads(l)
        elif l.startswith('{"k":"end"'): end=json.loads(l)
    return meta,P,SW,CH,ST,end
def process(f):
    meta,P,SW,CH,ST,end=load(f)
    info=dict(file=f,stamp=f[:19],startMs=meta['startMs'],has_p=bool(P))
    if not P: info['why']='no self p lines'; return info,[],[]
    tend=end['t'] if end else max(P)
    tres=next((t for t,m in CH if 'Who dares trespass' in m),None)
    t0=tres if tres is not None else 0
    info.update(tres=tres,tend=tend)
    # per-tick arrays
    N=tend+1
    pos=[None]*N;rot=[None]*N;hl=[None]*N;cr=[0]*N
    cur=None
    for t in range(N):
        if t in P:
            e=P[t]; cur=((e[1],e[2],e[3]),(e[4],e[5]),e[6],e[9] if len(e)>9 else None)
        if cur: pos[t],rot[t],hl[t],c=cur; cr[t]=c if c is not None else -1
    first=next(t for t in range(N) if pos[t])
    for t in range(first): pos[t]=pos[first];rot[t]=rot[first];hl[t]=hl[first];cr[t]=cr[first]
    # server ticks
    stt=[x[0] for x in ST];stn=[x[1] for x in ST]
    def sn(t):
        j=bisect.bisect_right(stt,t)-1
        return stn[j] if j>=0 else 0
    tele=[t for t,m in CH if m.startswith('You have teleported to')]
    # death intervals
    dead=[];dstart=None
    for t,m in CH:
        if dstart is None and (m.startswith(' ☠ You died') or m.startswith(' ☠ You were killed') or m.startswith('☠ You ')): dstart=t
        elif dstart is not None and ('p3wr was revived' in m or 'revived you' in m): dead.append((dstart,t)); dstart=None
    if dstart is not None: dead.append((dstart,10**9))
    ghost=[False]*N
    for a,b in dead:
        for t in range(a,min(b,N-1)+1): ghost[t]=True
    swset=Counter(SW)
    T=list(range(N))
    mv=[False]*N;jump=[False]*N;da=[0.0]*N
    for i in range(1,N):
        a,b=pos[i],pos[i-1]
        dd=math.dist(a,b);dh=math.hypot(a[0]-b[0],a[2]-b[2])
        jump[i]=dd>3
        mv[i]=dh>0.05 and not jump[i]
        dy=(rot[i][0]-rot[i-1][0]+180)%360-180;dp=rot[i][1]-rot[i-1][1]
        da[i]=abs(dy)+abs(dp)
    cs=[0]
    for x in da: cs.append(cs[-1]+x)
    aim=[False]*N
    for i in range(1,N):
        if da[i]>0.3 and cs[min(N,i+WIN)]-cs[i]>THR: aim[i]=True
    spots=[(spot(p) or [None])[0] for p in pos]
    legs=[];pts=[];excl=[]
    def finish(leg,iend,kind):
        A,i0,reason=leg
        if reason=='depart' and jump[i0]:
            c=[t for t in range(max(0,i0-12),i0+1) if swset.get(t) and hl[t] in LEAPITEMS]
            if c: i0=c[0]; reason='depart(use-click)'
        i1=iend
        why=None
        if any(ghost[i0:i1+1]): why='ghost/dead'
        run_=0;mx=0
        for i in range(i0,i1+1):
            if (not mv[i]) and not aim[i] and not jump[i]: run_+=1;mx=max(mx,run_)
            else: run_=0
        if not why and mx>100: why='idle>5s (%d ticks)'%mx
        walked=0.0;tp=0.0;tps=[]
        for i in range(i0+1,i1+1):
            dd=math.dist(pos[i],pos[i-1])
            if dd>3: tp+=dd;tps.append(i)
            else: walked+=dd
        methods=[]
        for i in tps:
            h=hl[i] ; hp=hl[max(0,i-1)]
            near=any(abs(i-lt)<=3 for lt in tele)
            if near or h in LEAPITEMS or hp in LEAPITEMS: methods.append('leap')
            elif 'ASPECT_OF_THE_VOID' in (h,hp): methods.append('etherwarp/AOTV')
            else: methods.append('teleport(%s)'%(h or hp))
        for t in range(i0,i1+1):
            if swset.get(t):
                h=hl[t]
                if h=='STARRED_BONZO_STAFF' or h=='BONZO_STAFF': methods.append('bonzo')
                elif h=='JERRY_STAFF': methods.append('jerry')
        mc=Counter(methods)
        sn_=sum(1 for i in range(i0,i1+1) if cr[i]==1)/max(1,i1-i0+1)
        mvn=sum(1 for i in range(i0,i1+1) if mv[i])
        base='sneak' if sn_>0.5 else ('move' if mvn>0 else 'no-input')
        if tps and walked<3: base=''
        mstr='+'.join(([base] if base else [])+[k+('x%d'%v if v>1 else '') for k,v in mc.items()])
        row=dict(run=f[:19],server='',leg_from=A,leg_to=spots[i1],start_n=sn(i0),end_n=sn(i1),ticks=i1-i0,seconds=round((i1-i0)/20,2),path_blocks=round(walked,1),method=mstr,start_t=i0,end_t=i1,start_reason=reason,start_kind=kind,tp_blocks=round(tp,1),ticks_n=sn(i1)-sn(i0),source='bpf',start_ms=meta['startMs']+50*i0)
        if why: row['why']=why;excl.append(row)
        elif kind=='settled': legs.append(row)
        else: pts.append(row)
    anchor=None;waiting=False;idle=0;leg=None;unsettled=None;leg_kind=None
    inp=mv
    for i in range(t0,N):
        sp=spots[i]
        if leg:
            A=leg[0]
            if sp==A:
                leg=None;anchor=None;waiting=False;idle=0;unsettled=A
            elif sp is not None:
                finish(leg,i,leg_kind);leg=None;anchor=None;waiting=False;idle=0;unsettled=sp
            continue
        if sp is None:
            if waiting and anchor:
                leg=(anchor,i,'depart');leg_kind='settled';waiting=False
            elif unsettled and not waiting:
                leg=(unsettled,i,'passthrough');leg_kind='pt'
            unsettled=None;anchor=None if leg else anchor
            idle=0
            if leg: anchor=None
            continue
        if sp!=anchor and sp!=unsettled:
            anchor=None;waiting=False;idle=0;unsettled=sp
        if not inp[i] and not aim[i] and not jump[i]: idle+=1
        else:
            if not waiting: idle=0
        if not waiting and idle>=3 and not ghost[i]:
            anchor=sp;waiting=True;unsettled=None
            continue
        if waiting and anchor==sp and not ghost[i]:
            if inp[i] or aim[i]:
                leg=(anchor,i,'move' if inp[i] else 'aim');leg_kind='settled';waiting=False
    info['nlegs']=len(legs)+len(pts);info['nexcl']=len(excl)
    info['p3_spots']=sum(1 for t in range(t0,N) if spots[t])
    # cache track data for review
    info['_tr']=(pos,rot,hl,cr)
    return info,legs+pts,excl
if __name__=='__main__':
    import pickle
    runs=[];allL=[];allE=[]
    fs=[f for f in sorted(os.listdir(D)) if f.endswith('.jsonl.gz')]
    for f in fs:
        try: info,L,E=process(f)
        except Exception as e:
            print('ERR',f,e); continue
        info.pop('_tr',None); runs.append(info); allL+=L; allE+=E
    json.dump(dict(runs=runs,legs=allL,excl=allE),open('bpf_all.json','w'))
    print(len(runs),len(allL),len(allE))
