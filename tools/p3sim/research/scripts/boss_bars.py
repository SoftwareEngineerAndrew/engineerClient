"""Boss bar over the fight: add/remove/name lines, and progress per bar name (first, last, the distinct values in
order, and when each value starts relative to the name's first tick). usage: boss_bars.py FILE.jsonl.gz..."""
import gzip, json, sys, collections
for f in sys.argv[1:]:
    print('##', f.split('/')[-1])
    name = {}; prog = collections.OrderedDict(); first = {}; ops = collections.Counter()
    for l in gzip.open(f, 'rt'):
        L = json.loads(l)
        if L['k'] == 'chat' and L['m'].startswith('[BOSS]') and L['m'].split(':')[0] not in first:
            first[L['m'].split(':')[0]] = L['n']; print('  ', L['n'], 'first line of', L['m'].split(':')[0])
        if L['k'] != 'net': continue
        for e in L['d']:
            if e[1] != 'bb': continue
            op, u = e[2], e[3]; ops[op] += 1
            if op == 'add': name[u] = e[4]; print('  ', e[0], 'ADD', e[3:])
            elif op == 'remove': print('  ', e[0], 'REMOVE', name.get(u))
            elif op == 'name':
                if name.get(u) != e[4]: print('  ', e[0], 'NAME', repr(e[4]))
                name[u] = e[4]
            elif op == 'progress':
                p = prog.setdefault(name.get(u), [])
                if not p or p[-1][1] != e[4]: p.append((e[0], e[4]))
            else: print('  ', e[0], op, e[3:])
    print('   ops', dict(ops))
    for nm, p in prog.items():
        print(f'   {nm!r}: {len(p)} changes:', p[:6], '...', p[-6:])
