"""Every coloured chat line Better PF saw in F7 boss (from the first '[BOSS] Maxor' line to end of run),
templated: party names -> <P>, digit runs -> #. Prints count, #runs, template (coloured), one raw example.
Usage: python3 bpf_boss_chat.py [maxruns]"""
import sys,gzip,lzma,collections,json,glob,re,os
D='/home/cam/.claude/jobs/fffcf85d/tmp/bpf/runs/'
def load(f):
    d=open(f,'rb').read()
    try: t=gzip.decompress(d)
    except Exception: t=lzma.decompress(d)
    return t.decode('utf-8','replace').splitlines()
cnt=collections.Counter(); runs=collections.defaultdict(set); ex={}; first={}
nrun=0
for f in sorted(glob.glob(D+'*.gz')):
    L=load(f); floor=None; names=set(); inb=False; t0=None
    for l in L:
        if '"k":"floor"' in l: floor=json.loads(l)['floor']
        elif '"k":"party"' in l:
            for m in json.loads(l)['m']: names.add(m[0])
        elif '"k":"meta"' in l: names.add(json.loads(l)['self'])
    if floor!='F7': continue
    for l in L:
        if '"k":"chat"' not in l: continue
        j=json.loads(l); m=j['m']; c=j.get('c',m)
        if not inb and m.startswith('[BOSS] Maxor'): inb=True; t0=j['t']; nrun+=1
        if not inb: continue
        tpl=c
        for n in sorted(names,key=len,reverse=True):
            if n: tpl=tpl.replace(n,'<P>')
        tpl=re.sub(r'(?<!§)\d[\d,.]*','#',tpl)
        cnt[tpl]+=1; runs[tpl].add(f)
        if tpl not in ex: ex[tpl]=c
        first.setdefault(tpl,[]).append(j['t']-t0)
print('F7 runs with boss:',nrun)
for tpl,n in sorted(cnt.items(),key=lambda x:-len(runs[x[0]])):
    if len(runs[tpl])<3: continue
    ts=sorted(first[tpl]); med=ts[len(ts)//2]
    print(f'{n:6d} {len(runs[tpl]):4d} med_t={med:6d} | {tpl!r}\n                 ex: {ex[tpl]!r}')
