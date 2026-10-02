"""Dungeon Recorder files (docs/dungeon-recorder.md) as something you can read or hand to an LLM.

    python3 read.py summary  FILE...                       # what the files hold: time span, line kinds, packet types by count
    python3 read.py timeline FILE... [options]             # one readable line per event
    python3 read.py example  FILE... TYPE                  # the first full line of one packet type

Timeline options:
    --from N / --to N      server ticks (n) to keep, inclusive
    --types a,b            only these packet types (short names: set_entity_data, or minecraft:...)
    --skip a,b             leave these packet types out
    --no-movement          leave out other entities' movement and head turns
    --grep TEXT            only lines containing TEXT (case-insensitive)
    --max-chars N          cut each line's fields to N characters (default 400)

FILE can be several parts of one recording (they are read in order). Standard library only.
"""
import gzip
import json
import sys
from collections import Counter

MOVEMENT = {'move_entity_pos', 'move_entity_pos_rot', 'move_entity_rot', 'rotate_head', 'set_entity_motion',
            'entity_position_sync', 'teleport_entity'}


def lines(files):
    for path in files:
        with gzip.open(path, 'rt', encoding='utf-8') as f:
            for raw in f:
                raw = raw.strip()
                if raw:
                    try:
                        yield json.loads(raw)
                    except json.JSONDecodeError:
                        pass


def short(p):
    return p.split(':', 1)[1] if p and p.startswith('minecraft:') else p


def summary(files):
    kinds, types, first, last = Counter(), Counter(), None, None
    for l in lines(files):
        k = l.get('k')
        kinds[k] += 1
        if k in ('in', 'out'):
            types[k + ' ' + short(l['p'])] += 1
        if 'n' in l and k != 'meta':
            first = l['n'] if first is None else first
            last = l['n']
        if k == 'meta':
            print('recording:', {x: l.get(x) for x in ('self', 'server', 'mod', 'part')})
    if first is not None:
        print('server ticks %d-%d (%.1f min)' % (first, last, (last - first) / 1200))
    print('\nline kinds:', dict(kinds.most_common()))
    print('\npacket types:')
    for t, c in types.most_common():
        print('  %7d  %s' % (c, t))


def timeline(files, args):
    lo = int(args.get('--from', -1e18)); hi = int(args.get('--to', 1e18))
    only = {short(x) for x in args['--types'].split(',')} if '--types' in args else None
    skip = {short(x) for x in args['--skip'].split(',')} if '--skip' in args else set()
    if '--no-movement' in args:
        skip |= MOVEMENT
    grep = args.get('--grep', '').lower()
    cut = int(args.get('--max-chars', 400))
    for l in lines(files):
        n = l.get('n')
        if n is not None and not (lo <= n <= hi):
            continue
        k = l.get('k')
        if k in ('in', 'out'):
            t = short(l['p'])
            if (only and t not in only) or t in skip:
                continue
            body = json.dumps(l.get('f'), separators=(',', ':'))
            text = '%s %s %s' % ('<-' if k == 'in' else '->', t, body[:cut] + ('...' if len(body) > cut else ''))
        else:
            if only:
                continue
            rest = {x: y for x, y in l.items() if x not in ('k', 't', 'n', 'ms')}
            text = '** %s %s' % (k, json.dumps(rest, separators=(',', ':'))[:cut])
        if grep and grep not in text.lower():
            continue
        print('%7s %6s  %s' % (n if n is not None else '-', l.get('t', '-'), text))


def example(files, typ):
    typ = short(typ)
    for l in lines(files):
        if l.get('k') in ('in', 'out') and short(l['p']) == typ:
            print(json.dumps(l, indent=1))
            return
    print('no', typ)


def main():
    a = sys.argv[1:]
    if not a or a[0] not in ('summary', 'timeline', 'example'):
        sys.exit(__doc__)
    cmd, rest = a[0], a[1:]
    files = [x for x in rest if x.endswith('.gz')]
    opts, i = {}, 0
    flags = [x for x in rest if not x.endswith('.gz')]
    while i < len(flags):
        if flags[i] == '--no-movement':
            opts[flags[i]] = True; i += 1
        elif flags[i].startswith('--'):
            opts[flags[i]] = flags[i + 1]; i += 2
        else:
            opts.setdefault('_', []).append(flags[i]); i += 1
    if cmd == 'summary':
        summary(files)
    elif cmd == 'timeline':
        timeline(files, opts)
    else:
        example(files, (opts.get('_') or ['?'])[0])


if __name__ == '__main__':
    main()
