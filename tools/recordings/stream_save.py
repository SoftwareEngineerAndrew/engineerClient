import struct, sys, os, time
out = sys.argv[1]; log = open(sys.argv[2], 'a', buffering=1)
f = sys.stdin.buffer; pos = 0; n = 0; t0 = time.time(); saved = 0
def rd(k):
    global pos
    b = f.read(k); pos += len(b)
    if len(b) < k: raise EOFError
    return b
name = '?'
try:
    while True:
        h = rd(30)
        if h[:4] != b'PK\x03\x04': log.write(f'end sig at {pos}\n'); break
        _, ver, flag, meth, _, _, crc, cs, us, nl, el = struct.unpack('<IHHHHHIIIHH', h)
        name = rd(nl).decode('utf-8', 'replace'); extra = rd(el); i = 0
        while i + 4 <= len(extra):
            hid, sz = struct.unpack('<HH', extra[i:i+4])
            if hid == 1:
                q = [struct.unpack('<Q', extra[i+4+j:i+12+j])[0] for j in range(0, sz, 8)]
                if us == 0xFFFFFFFF: us = q.pop(0)
                if cs == 0xFFFFFFFF and q: cs = q.pop(0)
            i += 4 + sz
        n += 1
        if name.startswith('recordings/engineerclient-recordings/'): log.write(f'reached engineerclient at {pos/2**30:.2f} GiB, stop\n'); break
        p = os.path.join(out, name.replace('..', '_'))
        keep = name.startswith('recordings/devgineerclient-recordings/') and not name.endswith('/') and meth in (0, 8)
        have = keep and os.path.exists(p) and os.path.getsize(p) == cs and os.path.exists(p + '.meth')
        left = cs
        if keep and not have:
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p + '.tmp', 'wb') as o:
                while left: b = rd(min(left, 1 << 20)); left -= len(b); o.write(b)
            os.replace(p + '.tmp', p); open(p + '.meth', 'w').write(str(meth)); saved += cs
        else:
            while left: left -= len(rd(min(left, 1 << 20)))
        if n % 100 == 0: log.write(f'{n} entries {pos/2**30:.2f} GiB {time.time()-t0:.0f}s saved {saved/2**30:.2f}\n')
    log.write(f'COMPLETE {n} entries\n')
except EOFError:
    log.write(f'CUT at {pos/2**30:.2f} GiB after {n} entries ({name})\n')
