"""Read P3 Sim's arena.bin (the F7 boss blocks, from build-arena.mjs) -> block(x, y, z) state string."""
import gzip, os, struct
P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../../../../src/main/resources/assets/engineerclient/p3sim/arena.bin')
_A = None


def load():
    global _A
    if _A: return _A
    b = gzip.open(P).read()
    assert b[:4] == b'P3A1'
    x0, y0, z0, w, h, d, np_ = struct.unpack('>7i', b[4:32])
    o = 32; pal = []
    for _ in range(np_):
        l = struct.unpack('>H', b[o:o + 2])[0]; pal.append(b[o + 2:o + 2 + l].decode()); o += 2 + l
    cells = bytearray(); vals = []
    def varint():
        nonlocal o
        r = 0; s = 0
        while True:
            c = b[o]; o += 1; r |= (c & 0x7f) << s; s += 7
            if not c & 0x80: return r
    out = []
    total = w * h * d
    import array
    arr = array.array('H')
    while o < len(b) and len(arr) < total:
        n = varint(); v = varint(); arr.extend([v] * n)
    _A = (x0, y0, z0, w, h, d, pal, arr)
    return _A


def block(x, y, z):
    x0, y0, z0, w, h, d, pal, arr = load()
    if not (x0 <= x < x0 + w and y0 <= y < y0 + h and z0 <= z < z0 + d): return None
    return pal[arr[((y - y0) * d + (z - z0)) * w + (x - x0)]]


if __name__ == '__main__':
    import sys
    for y in range(133, 122, -1):
        print(y, ' '.join((block(x, y, 50) or '?').replace('minecraft:', '').split('[')[0][:12].ljust(12) for x in range(61, 72)))
    print('z=49 row')
    for y in range(133, 122, -1):
        print(y, ' '.join((block(x, y, 49) or '?').replace('minecraft:', '').split('[')[0][:12].ljust(12) for x in range(61, 72)))
    for x in range(62, 71):
        for y in range(125, 132):
            s = block(x, y, 50)
            if 'pane' in (s or ''): print(x, y, 50, s)
