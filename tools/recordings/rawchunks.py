"""Rebuild a Water Board room's blocks from the raw chunk packets (the JSON lines' section data is lossy: ChunkCapture.indices
returns all zeros). Palette order is taken from the JSON line's `pal` (same container, same palette) and checked against the
id->name pairs the block_update frames give.
usage: rawchunks.py REC X0 Z0 FIRST_SEQ out.json  -> blocks of the 31x31 box (y 48..111) as of just before FIRST_SEQ, world coords"""
import gzip, json, glob, struct, sys, zlib

rec, X0, Z0, first_seq, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]


def varint(b, p):
    v = s = 0
    while True:
        x = b[p]; p += 1
        v |= (x & 0x7F) << s; s += 7
        if not x & 0x80: return v, p


def raw_frames(path):
    data = open(path, 'rb').read()
    text = b''
    d = zlib.decompressobj(31)
    # concatenated gzip members
    while data:
        text += d.decompress(data)
        data = d.unused_data
        d = zlib.decompressobj(31) if data else d
    pos, n = 0, len(text)
    while pos + 4 <= n:
        ln = struct.unpack('>I', text[pos:pos + 4])[0]; pos += 4
        seq, pos = varint(text, pos)
        dr, ph, fl = text[pos], text[pos + 1], text[pos + 2]; pos += 3
        if fl & 1: yield seq, dr, None; continue
        yield seq, dr, text[pos:pos + ln]; pos += ln


def lines():
    for p in sorted(glob.glob(rec + '/part*.jsonl.gz')):
        try:
            for line in gzip.open(p, 'rt'): yield line
        except EOFError: return


inbox = lambda x, z: X0 <= x < X0 + 31 and Z0 <= z < Z0 + 31
chunk_lines = {}       # raw seq -> json f (latest per chunk before first_seq kept later)
upd = []               # (seq, rawseq, kind, f)
for line in lines():
    if not line.startswith('{"k":"in"'): continue
    if 'level_chunk_with_light' in line:
        o = json.loads(line); f = o['f']
        if X0 >> 4 <= f['x'] <= (X0 + 30) >> 4 and Z0 >> 4 <= f['z'] <= (Z0 + 30) >> 4 and o['seq'] < first_seq:
            chunk_lines[(f['x'], f['z'])] = (o['seq'], o['raw'][0], f)
    elif ('block_update' in line or 'section_blocks' in line):
        o = json.loads(line)
        upd.append((o['seq'], o['raw'][0], o['f']))
want = {v[1] for v in chunk_lines.values()} | {u[1] for u in upd}
frames = {}
for p in sorted(glob.glob(rec + '/part*.raw.gz')):
    for seq, dr, body in raw_frames(p):
        if dr == 0 and seq in want and body is not None: frames[seq] = body
print('chunks', len(chunk_lines), 'frames found', sum(1 for v in chunk_lines.values() if v[1] in frames), '/ updates', sum(1 for u in upd if u[1] in frames))

# id -> name from block_update frames (pos long + varint id)
id2name = {}
for seq, rs, f in upd:
    b = frames.get(rs)
    if b is None or 'pos' not in f: continue
    pid, p = varint(b, 0)
    p += 8
    sid, p = varint(b, p)
    id2name.setdefault(sid, f['blockState'])


def read_container(b, p, size, minbits, maxind):
    bits = b[p]; p += 1
    if bits == 0:
        v, p = varint(b, p); return [v], [0] * size, p, bits
    if bits <= maxind:
        bits = max(bits, minbits)
        n, p = varint(b, p); pal = []
        for _ in range(n): v, p = varint(b, p); pal.append(v)
    else:
        pal = None
    per = 64 // bits; nl = (size + per - 1) // per
    longs = struct.unpack('>%dQ' % nl, b[p:p + 8 * nl]); p += 8 * nl
    mask = (1 << bits) - 1; idx = []
    for L in longs:
        for k in range(per):
            if len(idx) == size: break
            idx.append((L >> (k * bits)) & mask)
    return pal, idx, p, bits


blocks = {}
bad = 0
for (ccx, ccz), (seq, rs, f) in chunk_lines.items():
    b = frames.get(rs)
    if b is None: print('missing frame', ccx, ccz); continue
    pid, p = varint(b, 0)
    x, z = struct.unpack('>ii', b[p:p + 8]); p += 8
    assert (x, z) == (ccx, ccz), (x, z, ccx, ccz)
    nh, p = varint(b, p)
    for _ in range(nh):
        t, p = varint(b, p); nl, p = varint(b, p); p += 8 * nl
    size, p = varint(b, p)
    data = b[p:p + size]; q = 0
    for s in f['s']:
        nonempty, fluid = struct.unpack('>hh', data[q:q + 4]); q += 4
        pal, idx, q, bits = read_container(data, q, 4096, 4, 8)
        bpal, bidx, q, _ = read_container(data, q, 64, 1, 3)
        names = s['pal']
        if pal is not None and len(pal) != len(names): bad += 1
        sy = s['y']
        if not (3 <= sy <= 6): continue
        for j, v in enumerate(idx):
            if pal is None:
                nm = id2name.get(v, '#%d' % v)
            else:
                nm = names[v] if v < len(names) else '?'
                gid = pal[v]
                if gid in id2name and id2name[gid].split('[')[0] != nm.split('[')[0]: bad += 1
            lx, lz, ly = j & 15, (j >> 4) & 15, j >> 8
            wx, wz = ccx * 16 + lx, ccz * 16 + lz
            if inbox(wx, wz): blocks['%d,%d,%d' % (wx, sy * 16 + ly, wz)] = nm
# apply block updates in the box before first_seq
for seq, rs, f in upd:
    if seq >= first_seq: break
    ch = [[*f['pos'], f['blockState']]] if 'pos' in f else f.get('changes', [])
    for x, y, z, s in ch:
        k = '%d,%d,%d' % (x, y, z)
        if k in blocks: blocks[k] = s
print('palette mismatches', bad, 'blocks', len(blocks), 'id2name', len(id2name))
json.dump(blocks, open(out, 'w'))
