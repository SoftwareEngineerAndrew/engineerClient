"""Boss Recorder: measured speed (blocks/server tick) of fireballs and wither skulls by age, from their
position packets (m/tp/sy), and lifetime (spawn -> remove). Usage: python3 rec_proj_speed.py"""
import gzip,json,glob,math,collections
D='/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/bossrecorder/'
sp=collections.defaultdict(lambda: collections.defaultdict(list)); life=collections.defaultdict(list)
for f in sorted(glob.glob(D+'*.jsonl.gz'))[-20:]:
    L=[json.loads(l) for l in gzip.open(f,'rt')]
    born={}; last={}
    for l in L:
        if l['k']!='net': continue
        for e in l['d']:
            n,k=e[0],e[1]
            if k=='a' and e[3] in('minecraft:fireball','minecraft:wither_skull','minecraft:tnt'):
                born[e[2]]=(n,e[3],e[5]); last[e[2]]=(n,(e[4],e[5],e[6]))
            elif k in('m','tp','sy') and e[2] in born and e[3] is not None:
                n0,t,y0=born[e[2]]; pn,pp=last[e[2]]; p=(e[3],e[4],e[5])
                if n>pn: sp[(t,'y<80' if y0<80 else 'y>150' if y0>150 else 'mid')][min((n-n0)//5*5,40)].append(math.dist(p,pp)/(n-pn))
                last[e[2]]=(n,p)
            elif k=='r':
                for i in e[2]:
                    if i in born: life[born[i][1]].append(n-born[i][0]); del born[i]
for key,d in sp.items():
    print(key, {a:round(sorted(v)[len(v)//2],3) for a,v in sorted(d.items()) if len(v)>5})
for t,v in life.items():
    v=sorted(v); print('lifetime',t,len(v),[v[int(len(v)*p)] for p in (.1,.25,.5,.75,.9)])
