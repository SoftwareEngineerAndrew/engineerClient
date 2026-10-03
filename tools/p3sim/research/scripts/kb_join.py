"""Join Boss Recorder self-velocity packets with Better PF held items (by wall-clock ms).

usage: python3 kb_join.py > /tmp/kb_events.jsonl
Each output line: file, n, v (velocity packet on you), held (Skyblock id held at that time per Better PF,
plus held 1..10 ticks earlier), pos/yaw/pitch (your last sent `me`), spawns near you in the last 25
server ticks (any owner, within 4 blocks of your position when spawned), sounds within 2 ticks near you.
"""
import gzip, json, glob, lzma, bisect, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from kb_scan import load, DIR

BPF = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/betterpf/runs/'


def open_any(p):
    with open(p, 'rb') as fh:
        head = fh.read(6)
    if head.startswith(b'\xfd7zXZ'):
        return lzma.open(p, 'rt')
    return gzip.open(p, 'rt')


def bpf_runs():
    runs = []
    for p in sorted(glob.glob(BPF + '*')):
        try:
            fh = open_any(p)
            meta = json.loads(fh.readline())
            runs.append((meta.get('startMs', 0), meta.get('self'), p))
            fh.close()
        except Exception:
            pass
    return runs


def held_timeline(path, name):
    """[(ms, held, x,y,z,yaw,pitch)] for `name`."""
    tms = []  # (t, ms)
    pts = []  # (t, entry)
    try:
        with open_any(path) as fh:
            for l in fh:
                try:
                    o = json.loads(l)
                except Exception:
                    break
                if o['k'] == 'time':
                    tms.append((o['t'], o['ms']))
                elif o['k'] == 'p':
                    for e in o['d']:
                        if e[0] == name:
                            pts.append((o['t'], e))
    except EOFError:
        pass
    if len(tms) < 2:
        return []
    ts = [a for a, _ in tms]
    out = []
    for t, e in pts:
        i = min(max(bisect.bisect_left(ts, t), 1), len(ts) - 1)
        (t0, m0), (t1, m1) = tms[i - 1], tms[i]
        ms = m0 + (t - t0) * (m1 - m0) / max(1, t1 - t0)
        out.append((ms, e[6], e[1], e[2], e[3], e[4], e[5]))
    return out


def held_at(tl, ms):
    keys = [a[0] for a in tl]
    i = bisect.bisect_right(keys, ms) - 1
    return tl[i] if i >= 0 else None


def main():
    runs = bpf_runs()
    for f in sorted(glob.glob(DIR + '*.jsonl.gz')):
        meta, rows = load(f)
        if not meta:
            continue
        me = meta['selfId']; name = meta['self']
        endms = max((r[1] or 0) for r in rows) if rows else 0
        cand = [p for s, nm, p in runs if nm == name and s - 600000 < endms and s > meta['startMs'] - 3600000]
        tl = []
        for p in cand:
            tl += held_timeline(p, name)
        tl.sort()
        n2ms = {}
        for n, ms, d in rows:
            if ms and n not in n2ms:
                n2ms[n] = ms
        lastme = [None] * 5
        spawns = []
        for idx, (n, ms, d) in enumerate(rows):
            k = d[1]
            if k == 'me':
                for i in range(5):
                    if d[2 + i] is not None:
                        lastme[i] = d[2 + i]
            elif k == 'a' and lastme[0] is not None:
                if abs(d[4] - lastme[0]) < 4 and abs(d[5] - lastme[1] - 1) < 4 and abs(d[6] - lastme[2]) < 4:
                    spawns.append((n, d[3], d[4:10], d[13], list(lastme)))
            elif k == 'v' and d[2] == me:
                h = held_at(tl, ms) if ms and tl else None
                hist = []
                for back in range(0, 12):
                    m2 = n2ms.get(n - back)
                    hh = held_at(tl, m2) if m2 and tl else None
                    hist.append(hh[1] if hh else None)
                near = [r for r in rows[max(0, idx - 300): idx + 300] if abs(r[0] - n) <= 2]
                print(json.dumps({
                    'file': f.split('/')[-1], 'n': n, 'ms': ms, 'v': d[3:6], 'pos': lastme,
                    'held': h[1] if h else None, 'bpfpos': h[2:] if h else None, 'heldhist': hist,
                    'spawns': [(s[0] - n, s[1], s[2], s[3], s[4]) for s in spawns if n - 25 <= s[0] <= n],
                    'snd': [(r[0] - n, r[2][2], r[2][4:7], r[2][8]) for r in near if r[2][1] == 'snd'
                            and lastme[0] is not None and abs(r[2][4] - lastme[0]) + abs(r[2][6] - lastme[2]) < 10],
                    'ex': [(r[0] - n, r[2][2:]) for r in near if r[2][1] == 'ex'],
                    'hp': [(r[0] - n, r[2][2]) for r in near if r[2][1] == 'hp'],
                    'dmg': [(r[0] - n, r[2][2:]) for r in near if r[2][1] == 'dmg' and r[2][2] == me],
                }))


if __name__ == "__main__":
    main()
