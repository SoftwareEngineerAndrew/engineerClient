import json, sys
d = json.load(open(sys.argv[1])); B = d['blocks']
def st(x, y, z): return B.get(f'{x},{y},{z}', '?').replace('minecraft:', '')
ab = {'air': '.', 'water': '~', 'sticky_piston': 'P', 'quartz_block': 'Q', 'gold_block': 'G', 'coal_block': 'C', 'diamond_block': 'D', 'emerald_block': 'E', 'terracotta': 'T'}
def ch(s):
    b = s.split('[')[0]
    return ab.get(b, '#' if b not in ('?',) else '?')
for z in (26, 27, 28):
    print('z', z)
    for y in range(83, 55, -1):
        print(f'{y:3} ' + ''.join(ch(st(x, y, z)) for x in range(4, 27)))
print('levers:', [(p, st(*p)) for p in [(20,61,20),(20,61,15),(20,61,10),(10,61,20),(10,61,15),(10,61,10),(15,60,5)]])
print('gates:', [st(15, 56, z) for z in (19,18,17,16,15)])
