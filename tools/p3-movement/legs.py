import json,csv,math,sys,bisect
from load import B,spot
THR=15.0; WIN=5
def parse(s):
    me=[];acts=[];leaps=[];inv=[]
    for l in open(s+'.jsonl'):
        k=l[6:12]
        if l.startswith('{"k":"me"'): me.append(json.loads(l))
        elif l.startswith('{"k":"act"'): acts.append(json.loads(l))
        elif l.startswith('{"k":"leap"'): leaps.append(json.loads(l))
        elif l.startswith('{"k":"inv"'): inv.append(json.loads(l))
    me.sort(key=lambda d:d['seq']); return me,acts,leaps,inv
def itemid(i):
    if not i: return None
    return i.get('sb') or i.get('id')
def run(s):
    me,acts,leaps,inv=parse(s)
    # hotbar timeline by tick
    hot={};hb={}
    invt=[]
    for d in sorted(inv,key=lambda d:d['seq']):
        for sl,it in d['s']:
            if sl<9: hb[sl]=itemid(it)
        invt.append((d['seq'],dict(hb)))
    import bisect
    invseq=[x[0] for x in invt]
    def held(seq,slot):
        j=bisect.bisect_right(invseq,seq)-1
        return invt[j][1].get(slot) if j>=0 else None
    leapticks=[d['t'] for d in leaps if d['via']=='teleported']
    N=len(me)
    seqs=[m['seq'] for m in me]
    pos=[d['pos'] for d in me]; T=[d['t'] for d in me]
    inp=[any(d['in'][:5]) or d['in'][6] for d in me]
    ghost=[d['abil']['mayfly'] or d['hp']<=0 for d in me]
    # aim burst
    da=[0.0]*N
    for i in range(1,N):
        dy=(me[i]['rot'][0]-me[i-1]['rot'][0]+180)%360-180; dp=me[i]['rot'][1]-me[i-1]['rot'][1]
        da[i]=abs(dy)+abs(dp)
    # aim trigger: tick i is trigger if sum da over i..i+WIN-1 > THR and da[i]>0.5 ... first such i
    aim=[False]*N
    cs=[0]
    for x in da: cs.append(cs[-1]+x)
    for i in range(1,N):
        if da[i]>0.3 and cs[min(N,i+WIN)]-cs[i]>THR: aim[i]=True
    jump=[False]*N
    for i in range(1,N):
        jump[i]= math.dist(pos[i],pos[i-1])>3
    spots=[ (spot(p) or [None])[0] for p in pos]
    legs=[];pts=[];excl=[]
    anchor=None; waiting=False; idle=0; leg=None; unsettled=None; lastspot=None
    def finish(leg,iend,kind):
        A,i0,reason=leg
        if reason=='depart' and jump[i0]:
            cand=[a['seq'] for a in acts if a['what']=='startUseItem' and me[max(0,i0-12)]['seq']<=a['seq']<=me[i0]['seq']]
            if cand:
                i0=bisect.bisect_left(seqs,cand[0]); reason='depart(use-click)'
        # exclusions
        i1=iend
        why=None
        if any(ghost[i0:i1+1]): why='ghost/dead'
        # idle >100 ticks no input
        run_=0;mx=0
        for i in range(i0,i1+1):
            if (not inp[i]) and not aim[i] and not jump[i]: run_+=1; mx=max(mx,run_)
            else: run_=0
        if not why and mx>100: why='idle>5s (%d ticks)'%mx
        walked=0.0;tp=0.0;tps=[]
        for i in range(i0+1,i1+1):
            dd=math.dist(pos[i],pos[i-1])
            if dd>3: tp+=dd; tps.append(i)
            else: walked+=dd
        methods=[]
        # teleports
        for i in tps:
            h=held(me[i]['seq'],me[i]['slot'])
            if any(abs(T[i]-lt)<=3 for lt in leapticks): methods.append('leap')
            elif h=='ASPECT_OF_THE_VOID': methods.append('etherwarp/AOTV')
            else: methods.append('teleport(%s)'%h)
        # use items
        seqlo=me[i0]['seq'];seqhi=me[i1]['seq']
        for a in acts:
            if seqlo<=a['seq']<=seqhi and a['what']=='startUseItem':
                # held at that time
                j=bisect.bisect_left(seqs,a['seq'])
                j=min(j,N-1)
                h=held(a['seq'],me[j]['slot'])
                if h=='STARRED_BONZO_STAFF': methods.append('bonzo')
                elif h=='JERRY_STAFF': methods.append('jerry')
                elif h in('ASPECT_OF_THE_VOID',) and 'etherwarp/AOTV' not in methods: methods.append('aotv-use(no tp)')
        lv=[i for i in range(i0,i1+1) if me[i]['lava']]
        if lv:
            methods.append('lava-bounce' if any(me[i]['vel'][1]>0.4 for i in lv) else 'lava-contact')
        from collections import Counter
        mc=Counter(methods)
        spr=sum(1 for i in range(i0,i1+1) if me[i]['sprint'])/max(1,i1-i0+1)
        mv=sum(1 for i in range(i0,i1+1) if inp[i])
        base='sprint' if spr>0.5 else ('walk' if mv>0 else 'no-input')
        if tps and walked<3: base='' 
        mstr='+'.join(([base] if base else [])+[k+('x%d'%v if v>1 else '') for k,v in mc.items()])
        row=dict(run=s,leg_from=A,leg_to=spots[i1],start_n=me[i0]['n'],end_n=me[i1]['n'],ticks=T[i1]-T[i0],seconds=round((T[i1]-T[i0])/20,2),path_blocks=round(walked,1),method=mstr,start_t=T[i0],end_t=T[i1],start_reason=reason,start_kind=kind,tp_blocks=round(tp,1),ticks_n=me[i1]['n']-me[i0]['n'])
        if why: row['why']=why; excl.append(row)
        elif kind=='settled': legs.append(row)
        else: pts.append(row)
    # main loop
    anchor=None;waiting=False;idle=0;leg=None;unsettled=None
    for i in range(N):
        sp=spots[i]
        if leg:
            A=leg[0]
            if sp==A:
                leg=None; anchor=None; waiting=False; idle=0 if not inp[i] else 0; unsettled=A
            elif sp is not None:
                finish(leg,i,leg_kind); leg=None; anchor=None; waiting=False; idle=0; unsettled=sp
            continue
        if sp is None:
            if waiting and anchor:  # left A before any trigger (teleport)
                leg=(anchor,i,'depart'); leg_kind='settled'; waiting=False
            elif unsettled and not waiting:
                leg=(unsettled,i,'passthrough'); leg_kind='pt'
            unsettled_prev=unsettled; unsettled=None; anchor=None if leg else anchor
            idle=0
            if leg: anchor=None
            continue
        # in a spot, no active leg
        if sp!=anchor and sp!=unsettled:
            # switched spot directly
            anchor=None;waiting=False;idle=0;unsettled=sp
        if not inp[i] and not aim[i] and not jump[i]: idle+=1
        else:
            if not waiting: idle=0
        if not waiting and idle>=3 and not ghost[i]:
            anchor=sp; waiting=True; unsettled=None
            continue
        if waiting and anchor==sp and not ghost[i]:
            if inp[i] or aim[i]:
                # backtrack aim burst start is i itself
                leg=(anchor,i,'key' if inp[i] else 'aim'); leg_kind='settled'; waiting=False
    return legs,pts,excl,me
if __name__=='__main__':
    allL=[];allP=[];allE=[]
    for s in sys.argv[1:]:
        L,P,E,me=run(s); allL+=L;allP+=P;allE+=E; print(s,len(L),len(P),len(E))
    cols=['run','leg_from','leg_to','start_n','end_n','ticks','seconds','path_blocks','method','start_t','end_t','start_reason','start_kind','tp_blocks','ticks_n']
    allA=sorted(allL+allP,key=lambda r:(r['run'],r['start_t']))
    for name,rows in (('legs',allA),('legs_settled_only',allL),('excluded',allE)):
        with open(name+'.csv','w',newline='') as f:
            w=csv.DictWriter(f,fieldnames=cols+(['why'] if name=='excluded' else []),extrasaction='ignore'); w.writeheader(); w.writerows(rows)
