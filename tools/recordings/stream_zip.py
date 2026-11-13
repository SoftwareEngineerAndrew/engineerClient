import struct, sys, os, zlib, time
out = sys.argv[1]; log = open(sys.argv[2], 'a', buffering=1)
f = sys.stdin.buffer; pos = 0; kept = 0; CAP = 15 * 2**30; n = 0; t0 = time.time()
def rd(k):
    global pos
    b = f.read(k); pos += len(b)
    if len(b) < k: raise EOFError
    return b
def keep(name, us):
    if name.endswith('/'): return False
    base = name.rsplit('/', 1)[-1]
    if base.endswith('.raw.gz') or base.endswith('.raw.gz.part'): return False
    if base.startswith('part') and '.jsonl.gz' in base:
        return 'devgineerclient-recordings/' in name and kept + us < CAP
    return True
try:
    while True:
        h = rd(30)
        if h[:4] != b'PK\x03\x04': log.write(f'end sig {h[:4]!r} at {pos}\n'); break
        _, ver, flag, meth, _, _, crc, cs, us, nl, el = struct.unpack('<IHHHHHIIIHH', h)
        name = rd(nl).decode('utf-8', 'replace'); extra = rd(el); i = 0
        while i + 4 <= len(extra):
            hid, sz = struct.unpack('<HH', extra[i:i+4])
            if hid == 1:
                q = [struct.unpack('<Q', extra[i+4+j:i+12+j])[0] for j in range(0, sz, 8)]
                if us == 0xFFFFFFFF: us = q.pop(0)
                if cs == 0xFFFFFFFF and q: cs = q.pop(0)
            i += 4 + sz
        if flag & 8: log.write(f'data descriptor on {name}, abort\n'); break
        n += 1
        if '..' in name or name.startswith('/'): name = name.replace('..', '_').lstrip('/')
        if keep(name, us):
            p = os.path.join(out, name); os.makedirs(os.path.dirname(p), exist_ok=True)
            d = zlib.decompressobj(-15) if meth == 8 else None; left = cs
            with open(p, 'wb') as o:
                while left:
                    b = rd(min(left, 1 << 20)); left -= len(b)
                    o.write(d.decompress(b) if d else b)
                if d: o.write(d.flush())
            kept += us
        else:
            left = cs
            while left: left -= len(rd(min(left, 1 << 20)))
        if n % 200 == 0: log.write(f'{n} entries, {pos/2**30:.2f} GiB read, {kept/2**30:.2f} GiB kept, {time.time()-t0:.0f}s, at {name}\n')
except EOFError:
    log.write(f'EOF (cut?) at {pos} after {n} entries, last {name}\n')
log.write(f'DONE {n} entries, read {pos}, kept {kept}\n')
