"""Boss Recorder files: who recorded, mod version, own tp / me / msw / hp counts, kinds present."""
import collections, glob, gzip, json
for f in sorted(glob.glob('/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/bossrecorder/*.jsonl.gz')):
    c = collections.Counter(); meta = None
    try:
        for x in gzip.open(f, 'rt'):
            l = json.loads(x)
            if l['k'] == 'meta': meta = l
            elif l['k'] == 'net':
                for e in l['d']:
                    c[e[1]] += 1
                    if e[1] == 'tp' and meta and e[2] == meta['selfId']: c['TPSELF'] += 1
    except Exception: pass
    if meta: print(f.split('/')[-1][:19], meta['self'], meta['mod'], 'tpself', c['TPSELF'], 'tp', c['tp'], 'me', c['me'], 'msw', c['msw'], 'hp', c['hp'], 'snd', c['snd'], 'ex', c['ex'])
