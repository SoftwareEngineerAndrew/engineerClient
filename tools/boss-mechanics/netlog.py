"""The boss log (`net` lines, tools/betterpf-viewer/FORMAT.md) of a Better PF recording, as tables.

    python3 netlog.py RECORDING.gz            # what the recording's boss log holds, by kind
    python3 netlog.py RECORDING.gz ENTITY_ID  # one entity's packets, tick by tick

As a module:
    log = NetLog(recording.read_lines(path))
    log.withers()                 # {entity id: first 'a' entry} for every wither the server added
    log.moves(id)                 # [(n, x, y, z, via)] from 'm', 'tp' and 'sy', absolute
    log.heads(id)                 # [(n, headYaw)]
    log.health(id)                # [(n, value)] every float the server synced for the entity
    log.kind('dmg')               # [(n, *fields)] every entry of one kind
    log.missing_ticks(id, a, b)   # server ticks in [a, b] with no position packet for the entity
    log.lag()                     # [(n, gameTime)]: gameTime - n drifting shows the server skipping ticks

Standard library only; recordings without `net` lines give empty tables.
"""
import collections
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'boss-movement'))
import recording as R  # noqa: E402


class NetLog:
    def __init__(self, lines):
        self.entries = []          # (t, n, kind, fields)
        self.chat = []             # (t, n or None, message)
        for l in lines:
            k = l.get('k')
            if k == 'net':
                for e in l['d']:
                    self.entries.append((l['t'], e[0], e[1], e[2:]))
            elif k == 'chat':
                self.chat.append((l['t'], l.get('n'), l['m']))
        self.by_kind = collections.defaultdict(list)
        for t, n, kind, f in self.entries:
            self.by_kind[kind].append((n, *f))

    def kind(self, kind):
        return self.by_kind.get(kind, [])

    def withers(self):
        return {e[1]: e for e in self.kind('a') if e[2] == 'minecraft:wither'}

    def moves(self, eid):
        out = []
        for kind in ('m', 'tp', 'sy'):
            for e in self.kind(kind):
                if e[1] == eid and e[2] is not None:
                    out.append((e[0], e[2], e[3], e[4], kind))
        return sorted(out, key=lambda r: r[0])

    def heads(self, eid):
        return [(e[0], e[2]) for e in self.kind('h') if e[1] == eid]

    def health(self, eid):
        return [(e[0], v) for e in self.kind('d') if e[1] == eid
                for i, v in e[2] if isinstance(v, float)]

    def missing_ticks(self, eid, a, b):
        seen = {n for n, *_ in self.moves(eid)}
        return [n for n in range(a, b + 1) if n not in seen]

    def lag(self):
        return [(e[0], e[1]) for e in self.kind('time')]


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    log = NetLog(R.read_lines(sys.argv[1]))
    if len(sys.argv) == 2:
        if not log.entries:
            print('no boss log in this recording (made before the recorder kept one)')
            return
        for kind, rows in sorted(log.by_kind.items(), key=lambda kv: -len(kv[1])):
            print('%-5s %7d   n %d-%d' % (kind, len(rows), rows[0][0], rows[-1][0]))
        for eid, a in log.withers().items():
            print('wither %d added at n %d, at (%.1f, %.1f, %.1f)' % (eid, a[0], a[3], a[4], a[5]))
        return
    eid = int(sys.argv[2])
    for t, n, kind, f in log.entries:
        if (f and f[0] == eid) or (kind == 'r' and eid in f[0]):
            print(n, kind, *f)


if __name__ == '__main__':
    main()
