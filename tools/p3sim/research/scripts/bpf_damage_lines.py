"""Damage-line numbers in F7 boss chat across all BPF runs: per attack, value histogram (top 8) and tick-since-Maxor range."""
import sys,gzip,lzma,json,glob,re,collections
D='/home/cam/.claude/jobs/fffcf85d/tmp/bpf/runs/'
pat=re.compile(r"^(Maxor's|Storm's|Goldor's|Necron's|A Crypt Wither Skull|The Arrow Trap)(.*?)(?:hit you for|hitting you for) ([\d,.]+)")
vals=collections.defaultdict(collections.Counter); ticks=collections.defaultdict(list); per=collections.defaultdict(list)
for f in glob.glob(D+'*.gz'):
    d=open(f,'rb').read()
    try: t=gzip.decompress(d)
    except Exception: t=lzma.decompress(d)
    t0=None; cur=collections.Counter()
    for l in t.decode('utf-8','replace').splitlines():
        if '"k":"chat"' not in l: continue
        j=json.loads(l); m=j['m']
        if t0 is None and m.startswith('[BOSS] Maxor'): t0=j['t']
        if t0 is None: continue
        mm=pat.match(m)
        if mm:
            k=(mm.group(1)+mm.group(2)).strip(); vals[k][mm.group(3)]+=1; ticks[k].append(j['t']-t0); cur[k]+=1
    for k,v in cur.items(): per[k].append(v)
for k in vals:
    ts=sorted(ticks[k]); pr=sorted(per[k])
    print(k, 'n=',sum(vals[k].values()),'runs=',len(pr),'per-run med/max',pr[len(pr)//2],pr[-1],'t range',ts[0],ts[len(ts)//2],ts[-1])
    print('   ',vals[k].most_common(8))
