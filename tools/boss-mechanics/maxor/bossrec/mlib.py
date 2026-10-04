"""Maxor (F7 P1) from Boss Recorder files (format bosses-1), on a corrected server-tick clock.

Clock: Hypixel wraps every packet that changes your own entity flags (set_entity_data, index 0)
in two extra pings (pg, d(self), pg), so the raw ping count `n` runs 9-18% ahead of the server.
`srv` is the server's own tick: anchored on the `time` packets' gameTime (every 20 ticks) and
counted by the remaining pings inside each second when exactly 20 of them are left; otherwise by
client ticks since the anchor.
"""
import gzip, json, os, re, collections, bisect

MC = os.path.expanduser('~/.local/share/PrismLauncher/instances/26.1.2 BRW/minecraft')
BOSS = os.path.join(MC, 'config/engineerclient/bossrecorder')

WELL = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
STORM = '[BOSS] Storm: Pathetic Maxor, just like expected.'
STUN = ('[BOSS] Maxor: YOU TRICKED ME!', '[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!')
ENRAGE = '⚠ Maxor is enraged! ⚠'
PICK = re.compile(r'^(\S+) picked up an Energy Crystal!$')
PLACED = re.compile(r'^(\d)/2 Energy Crystals are now active!$')
CHARGING = 'The Energy Laser is charging up!'

WEST, EAST = (52.5, 224.0, 41.5), (94.5, 224.0, 41.5)
TOPS = {(64.5, 50.5): 'W', (82.5, 50.5): 'E'}


def read(path):
    lines = []
    with gzip.open(path, 'rt') as f:
        try:
            for l in f:
                try:
                    lines.append(json.loads(l))
                except ValueError:
                    break
        except EOFError:
            pass
    return lines


class Run:
    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)
        self.lines = read(path)
        self.meta = next((l for l in self.lines if l['k'] == 'meta'), {})
        self.self_name = self.meta.get('self')
        self.self_id = self.meta.get('selfId')
        self.pal = {}
        self.chat = []      # (n, t, m)
        self.stream = []    # (n, t, ms, kind, fields)
        self.party = []
        for l in self.lines:
            k = l['k']
            if k == 'pal':
                self.pal[l['i']] = l['s']
            elif k == 'chat':
                self.chat.append((l.get('n'), l['t'], l['m']))
            elif k == 'net':
                for e in l['d']:
                    self.stream.append((e[0], l['t'], l['ms'], e[1], e[2:]))
            elif k == 'party':
                self.party = l.get('m', [])
        self._build_clock()

    # ---- clock -------------------------------------------------------------------------
    def _build_clock(self):
        st = self.stream
        sand = set()
        for i, (n, t, ms, k, f) in enumerate(st):
            if k == 'pg' and len(f) > 1 and f[1] == 1:
                sand.add(i)  # 0.6.17+: a ping inside a bundle, flagged by the recorder
            if k == 'd' and f and f[0] == self.self_id and 0 < i < len(st) - 1 \
                    and st[i - 1][3] == 'pg' and st[i + 1][3] == 'pg':
                sand.add(i - 1); sand.add(i + 1)
        # anchors: (index, gameTime, t)
        anchors = [(i, f[0], t) for i, (n, t, ms, k, f) in enumerate(st) if k == 'time']
        self.srv_of_n = {}
        self.clean_seconds = 0; self.seconds = 0
        self.clean_spans = []
        if not anchors:
            return
        # per stream index -> srv, assigned second by second
        srv_idx = [None] * len(st)
        bounds = anchors + [(len(st), None, None)]
        # before the first anchor: count back by client ticks
        i0, gt0, t0 = anchors[0]
        for j in range(0, i0):
            srv_idx[j] = gt0 - (t0 - st[j][1])
        for (ia, gta, ta), (ib, gtb, tb) in zip(bounds, bounds[1:]):
            ticks = [j for j in range(ia + 1, ib) if st[j][3] == 'pg' and j not in sand]
            span = (gtb - gta) if gtb is not None else None
            clean = span is not None and span > 0 and len(ticks) == span
            if span is not None:
                self.seconds += 1; self.clean_seconds += clean
            cur = gta
            last = gta
            if clean:
                self.clean_spans.append((gta, gtb))
            for j in range(ia, ib):
                if clean:
                    if st[j][3] == 'pg' and j not in sand:
                        cur += 1
                    srv_idx[j] = cur
                else:
                    v = gta + max(0, st[j][1] - ta)
                    if span is not None:
                        v = min(v, gtb)
                    last = max(last, v)
                    srv_idx[j] = last
        self.srv_idx = srv_idx
        # raw ping count n -> srv (the srv of the entry where n first appears)
        for j, (n, t, ms, k, f) in enumerate(st):
            if n not in self.srv_of_n:
                self.srv_of_n[n] = srv_idx[j]
        self._ns = sorted(self.srv_of_n)
        # client tick -> srv fallback (for chat lines whose n has no entry)
        self._t_anchor = [(t, gt) for (i, gt, t) in anchors]

    def srv(self, n, t=None):
        if n in self.srv_of_n:
            return self.srv_of_n[n]
        if self._ns:
            k = bisect.bisect_right(self._ns, n) - 1
            if k >= 0:
                return self.srv_of_n[self._ns[k]] + (n - self._ns[k])
        return None

    # ---- the Maxor window ----------------------------------------------------------------
    def maxor(self):
        s0 = next(((n, t) for n, t, m in self.chat if m == WELL), None)
        if s0 is None:
            return None
        self.s0n = s0[0]
        self.s0 = self.srv(s0[0])
        end = next(((n, t) for n, t, m in self.chat if m == STORM and n >= s0[0]), None)
        self.endn = end[0] if end else None
        return self.s0

    def rel(self, n):
        v = self.srv(n)
        return None if v is None else v - self.s0

    def window_chat(self):
        for n, t, m in self.chat:
            if n is None or n < self.s0n:
                continue
            if self.endn is not None and n > self.endn:
                break
            yield self.rel(n), m

    def window_net(self, lo=-40):
        for j, (n, t, ms, k, f) in enumerate(self.stream):
            if n < self.s0n + lo * 1.3:
                continue
            if self.endn is not None and n > self.endn + 5:
                break
            yield self.srv_idx[j] - self.s0, t, ms, k, f

    def block(self, i):
        return self.pal.get(i)


def runs(since='2026-10-02', only=None):
    out = []
    for name in sorted(os.listdir(BOSS)):
        if not name.endswith('.jsonl.gz') or name[:10] < since:
            continue
        if only and name not in only:
            continue
        out.append(os.path.join(BOSS, name))
    return out


# server per recording, from servers.py's connection timeline (p3wr's client logs)
def server_of(name):
    import datetime as dt
    m = re.search(r'(\d{4}-\d\d-\d\d)_(\d\d)-(\d\d)-(\d\d)', name)
    when = dt.datetime.strptime(''.join(m.groups()), '%Y-%m-%d%H%M%S')
    host = None
    for t, h in _timeline():
        if t > when:
            break
        host = h
    return {'alpha.hypixel.net': 'alpha', 'stuck.hypixel.net': 'main', 'hypixel.net': 'main', 'p3sim.net': 'p3sim'}.get(host, host)


_TL = None


def _timeline():
    global _TL
    if _TL is not None:
        return _TL
    import datetime as dt
    LOGS = os.path.join(MC, 'logs')
    LINE = re.compile(r'^\[(\d\d):(\d\d):(\d\d)\] \[[^\]]+\]: (Connecting to (\S+), \d+|Stopping!|Loading Minecraft)')
    ev = []
    for name in sorted(os.listdir(LOGS)):
        m = re.match(r'(\d{4}-\d\d-\d\d)-\d+\.log\.gz$', name)
        if m:
            day = dt.date.fromisoformat(m.group(1)); op = gzip.open
        elif name == 'latest.log':
            day = dt.datetime.fromtimestamp(os.path.getmtime(os.path.join(LOGS, name))).date(); op = open
        else:
            continue
        prev = None
        with op(os.path.join(LOGS, name), 'rt', errors='replace') as f:
            for l in f:
                mm = LINE.match(l)
                if not mm:
                    continue
                t = dt.time(int(mm.group(1)), int(mm.group(2)), int(mm.group(3)))
                if prev and t < prev:
                    day += dt.timedelta(days=1)
                prev = t
                ev.append((dt.datetime.combine(day, t), mm.group(5)))
    ev.sort(key=lambda e: e[0])
    _TL = ev
    return ev
