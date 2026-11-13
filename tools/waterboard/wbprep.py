"""One recording -> board fixture json (extract.py + rawchunks.py + merge.py), or nothing if it has no usable Water Board."""
import gzip, glob, json, os, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__))
rec, out = sys.argv[1], sys.argv[2]
room = None
for p in sorted(glob.glob(rec + '/part*.jsonl.gz')):
    try:
        for line in gzip.open(p, 'rt'):
            if line.startswith('{"k":"room"') and '"Water Board"' in line:
                o = json.loads(line)
                if o.get('rot') and o.get('clay'): room = o; break
                room = room or o
    except (EOFError, OSError): pass
    if room and room.get('rot'): break
if not room: print('no water board'); sys.exit(0)
if not room.get('rot'): print('water board, rotation never known'); sys.exit(0)
tmp = out + '.tmp.json'
r = subprocess.run([sys.executable, os.path.join(HERE, 'extract.py'), rec, tmp], capture_output=True, text=True)
if r.returncode != 0: print('extract failed', r.stderr[-300:]); sys.exit(0)
d = json.load(open(tmp))
if not d['writes']: os.remove(tmp); print('water board, no lever packets in view'); sys.exit(0)
tx, tz = room['tiles'][0]
r = subprocess.run([sys.executable, os.path.join(HERE, 'rawchunks.py'), rec, str(-200 + 32 * tx), str(-200 + 32 * tz), str(d['writes'][0][1]), out + '.blocks.json'], capture_output=True, text=True)
print(r.stdout.strip().replace('\n', ' | '))
if r.returncode != 0: print('rawchunks failed', r.stderr[-300:]); sys.exit(0)
subprocess.run([sys.executable, os.path.join(HERE, 'merge.py'), tmp, out + '.blocks.json'], check=True)
os.replace(tmp, out)
print('ok', len(d['writes']), 'writes')
