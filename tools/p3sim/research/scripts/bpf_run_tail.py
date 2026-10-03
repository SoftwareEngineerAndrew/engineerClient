"""Print a coloured F7 run's boss chat in order (repr), with ticks since Maxor's first line.
Usage: python3 bpf_run_tail.py RUNFILE [grep]"""
import sys,gzip,lzma,json
d=open(sys.argv[1],'rb').read()
try: t=gzip.decompress(d)
except Exception: t=lzma.decompress(d)
g=sys.argv[2] if len(sys.argv)>2 else None
t0=None
for l in t.decode('utf-8','replace').splitlines():
    if '"k":"chat"' not in l: continue
    j=json.loads(l)
    if t0 is None and j['m'].startswith('[BOSS] Maxor'): t0=j['t']
    if t0 is None: continue
    if g and g not in j['m']: continue
    print(j['t']-t0, repr(j.get('c',j['m'])))
