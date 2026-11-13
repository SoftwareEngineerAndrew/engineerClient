import json, sys
d = json.load(open(sys.argv[1])); wb = json.load(open(sys.argv[2]))
rot = d['rot']; cx, _, cz = d['clay']
def to_room(x, y, z):
    dx, dz = x - cx, z - cz
    return {'NORTH': (-dx, y, -dz), 'WEST': (dz, y, -dx), 'SOUTH': (dx, y, dz), 'EAST': (-dz, y, dx)}[rot]
d['blocks'] = {','.join(map(str, to_room(*map(int, k.split(','))))): v for k, v in wb.items()}
json.dump(d, open(sys.argv[1], 'w'))
