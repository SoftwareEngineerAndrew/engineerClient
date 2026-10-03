"""Every `ex` (explosion packet) in the Boss Recorder files: count, radius, and any knockback given to you."""
import gzip, json, glob
from collections import Counter
D = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/bossrecorder/'
n = 0; kb = []; rad = Counter()
for f in sorted(glob.glob(D + '*.jsonl.gz')):
    try:
        for l in gzip.open(f, 'rt'):
            if '"ex"' not in l:
                continue
            o = json.loads(l)
            for d in o.get('d', []):
                if d[1] == 'ex':
                    n += 1; rad[d[5]] += 1
                    if len(d) > 7 and any(d[7:10]):
                        kb.append((f.split('/')[-1][:16], d))
    except Exception:
        pass
print('explosions', n, 'radius', rad.most_common(5), 'with knockback on you', len(kb))
for k in kb[:15]:
    print(k)
