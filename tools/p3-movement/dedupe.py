import json,os,csv,collections
R=os.path.join(os.environ.get("RECORDINGS", os.path.expanduser("~/.local/share/PrismLauncher/instances/26.1.2 BRW/minecraft/engineerclient-recordings")),"")
rec=[]
for d in sorted(os.listdir(R)):
    if not d.startswith(('2026-10-03','2026-10-04')): continue
    m=json.load(open(R+d+'/manifest.json'))
    end=m.get('end') or (m['parts'][-1]['ms'][1] if m.get('parts') else None)
    rec.append((m['start'],end,d[-6:]))
A=json.load(open('bpf_all.json'))
dup={}
for r in A['runs']:
    s=r['startMs']; tres=r.get('tres'); 
    t0=tres if tres is not None else 0
    ms0=s+50*t0; ms1=s+50*r.get('tend',0)
    hit=[x for x in rec if x[0]-5000<=ms0 and ms1<=(x[1] or 0)+5000]
    r['recorder_twin']=hit[0][2] if hit else None
    r['dup']=bool(hit)
json.dump(A,open('bpf_all.json','w'))
runs=A['runs']
print('scanned',len(runs),'with p',sum(r['has_p'] for r in runs))
print('with trespass',sum(1 for r in runs if r.get('tres') is not None))
print('P3 visits(spot ticks>0)',sum(1 for r in runs if r.get('p3_spots',0)>0))
print('dup (recorder twin)',sum(r['dup'] for r in runs),'; with p3 visits',sum(1 for r in runs if r['dup'] and r.get('p3_spots',0)>0))
print('new with legs',sum(1 for r in runs if not r['dup'] and r.get('nlegs',0)>0))
print('legs new',sum(1 for l in A['legs'] if not next(r for r in runs if r['stamp']==l['run'])['dup']))
print(collections.Counter((r['dup'],r.get('tres') is not None) for r in runs))
# dup but recorder has no legs?
rl=set(r['run'] for r in csv.DictReader(open(os.environ.get('RECORDER_LEGS', os.path.join(os.path.dirname(os.path.abspath(__file__)),'../../docs/mechanics/p3-movement/legs.csv')))))
print('dup runs whose twin has no recorder legs but bpf has',sum(1 for r in runs if r['dup'] and r['recorder_twin'] not in rl and r.get('nlegs',0)>0), [ (r['stamp'],r['recorder_twin'],r['nlegs']) for r in runs if r['dup'] and r['recorder_twin'] not in rl and r.get('nlegs',0)>0][:10])
print('new runs w/o tres but legs',[(r['stamp'],r['nlegs']) for r in runs if not r['dup'] and r.get('tres') is None and r.get('nlegs',0)>0])
