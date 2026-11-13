"""Water Board ground truth from a Dungeon Recorder recording.
out: <name>.json with room frame, the room's blocks at the moment before the first lever packet in the room,
and every server block write in the room afterwards: [ms, seq, x, y, z, state] in room coordinates."""
import gzip, json, glob, sys
rec, out = sys.argv[1], sys.argv[2]
room = None
def lines():
    for p in sorted(glob.glob(rec + '/part*.jsonl.gz')):
        try:
            for line in gzip.open(p, 'rt'): yield line
        except EOFError: return
# pass 1: room frame
for line in lines():
    if line.startswith('{"k":"room"') and '"Water Board"' in line:
        o = json.loads(line)
        if o.get('rot') and o.get('clay'): room = o; break
rot, (cx, _, cz) = room['rot'], room['clay']
tx, tz = room['tiles'][0]; X0, Z0 = -200 + 32 * tx, -200 + 32 * tz
def to_room(x, y, z):
    dx, dz = x - cx, z - cz
    return {'NORTH': (-dx, y, -dz), 'WEST': (dz, y, -dx), 'SOUTH': (dx, y, dz), 'EAST': (-dz, y, dx)}[rot]
inside = lambda x, z: X0 <= x < X0 + 31 and Z0 <= z < Z0 + 31
world = {}           # (x,y,z) world -> state, current
snap = None; writes = []
for line in lines():
    if not line.startswith('{"k":"in"'): continue
    if 'level_chunk_with_light' in line:
        o = json.loads(line); f = o['f']; ccx, ccz = f['x'], f['z']
        if not (X0 >> 4 <= ccx <= (X0 + 30) >> 4 and Z0 >> 4 <= ccz <= (Z0 + 30) >> 4): continue
        for s in f['s']:
            sy = f['minSy'] + s['i']
            if not (3 <= sy <= 6): continue          # y 48..111
            pal = s['pal']; rle = s.get('rle') or [4096, 0]
            idx = 0
            for k in range(0, len(rle), 2):
                n, pi = rle[k], rle[k + 1]; st = pal[pi]
                for j in range(idx, idx + n):
                    lx = j & 15; lz = (j >> 4) & 15; ly = j >> 8
                    x, z = ccx * 16 + lx, ccz * 16 + lz
                    if inside(x, z): world[(x, sy * 16 + ly, z)] = st
                idx += n
        continue
    if 'block_update' in line or 'section_blocks' in line:
        o = json.loads(line); f = o['f']
        ch = [[*f['pos'], f['blockState']]] if 'pos' in f else f.get('changes', [])
        for x, y, z, s in ch:
            if not inside(x, z): continue
            if snap is None and 'lever' in s and world.get((x, y, z)) != s:
                snap = dict(world)
            world[(x, y, z)] = s
            if snap is not None: writes.append([o['ms'], o['seq'], *to_room(x, y, z), s])
blocks = {','.join(map(str, to_room(*k))): v for k, v in (snap or world).items()}
json.dump({'rec': rec.rstrip('/').split('/')[-1], 'rot': rot, 'clay': room['clay'], 'blocks': blocks, 'writes': writes}, open(out, 'w'))
print(out, rot, len(blocks), 'blocks,', len(writes), 'writes')
