"""Dungeon Recorder recordings (docs/dungeon-recorder.md, format "recorder-2") as something you can
read or hand to an LLM. Standard library only.

    python3 read.py summary  REC                      # manifest, parts, every line kind and packet type by count
    python3 read.py index    REC                      # the member index per part: seq/tick/time ranges, keyframes
    python3 read.py entities REC                      # every entity seen (entities.jsonl, or rebuilt from espawn)
    python3 read.py events   REC                      # marks, worlds, deaths, splits... (events.jsonl and the manifest)
    python3 read.py schema   REC [NAME]               # field names per reflected class, enum constants
    python3 read.py example  REC KIND|TYPE [N]        # the first N full lines of a kind or packet type
    python3 read.py timeline REC [options]            # one readable line per event, in seq order
    python3 read.py state    REC --at N [--box ..]    # the world, entities and HUD rebuilt at server tick N
    python3 read.py raw      REC SEQ                  # a raw frame (or a line's frames) as hex
    python3 read.py context  REC --budget BYTES [--mark N | --around SEQ]   # an LLM context pack

REC is a recording directory, its manifest.json, or one or more part files (partNNNN.jsonl.gz, also
an unfinished .part). Parts are read through their index (partNNNN.idx.jsonl): each gzip member is
found by offset and only the members a filter can match are decompressed. A member cut off by a crash
is read up to the last whole line. Lines that do not parse are counted and reported, never skipped
silently; gap, stopped, crash and connection-error warnings are always printed (to stderr).

Timeline options:
    --kinds a,b         line kinds to keep, globs allowed ('ec.*', 'odin.*', 'in')
    --types a,b         packet types to keep (short names: set_entity_data, or minecraft:...)
    --skip a,b          packet types to leave out
    --entity ID         only lines about entity ID (packet 'e' lists, 'id'/'eid' members, ent/emove rows)
    --seq A:B  --ms A:B --from N --to N (server ticks n)   ranges, inclusive; either end may be empty
    --around SEQ [--window N]   the N lines (default 200) either side of SEQ
    --regex RE          only lines whose JSON matches RE (case-insensitive); --grep TEXT is a plain-text form
    --no-movement       leave out other entities' movement packets and emove/ent rows
    --all               also show what the timeline leaves out by default: chunk data, light, keyframe
                        snapshots (kf*), particles (ptc), frames, cur/gmouse samples
    --json              print the lines as they are in the file
    --max-chars N       cut each line's fields to N characters (default 400; 0 = no limit)
    -j N                worker processes for decompression (default: up to 4)

State options: --at N (server tick n; or --at-seq S / --at-t T), --box x1,y1,z1,x2,y2,z2 (print every
block), --pos x,y,z, --room NAME (Odin's room line), --map [ID] (ASCII render of a map), --entities.
"""
import argparse
import base64
import fnmatch
import glob
import gzip
import heapq
import io
import json
import lzma
import os
import re
import struct
import sys
import zlib
from collections import Counter, OrderedDict, defaultdict

MOVEMENT = {'move_entity_pos', 'move_entity_pos_rot', 'move_entity_rot', 'rotate_head', 'set_entity_motion',
            'entity_position_sync', 'teleport_entity'}
MOVEMENT_KINDS = {'ent', 'emove'}
# Bulky snapshot data a timeline leaves out unless --all: whole chunks, light arrays, keyframe copies,
# particles, per-frame camera rows and cursor samples.
DEFAULT_EXCLUDED_KINDS = ('kf*', 'ptc', 'frames', 'cur', 'gmouse', 'thumb')
DEFAULT_EXCLUDED_TYPES = {'level_chunk_with_light', 'light_update', 'chunks_biomes'}
WARN_KINDS = {'gap', 'stopped', 'conn_error'}
PACKET_KINDS = {'in', 'out', 'config'}


def short(p):
    return p.split(':', 1)[1] if isinstance(p, str) and p.startswith('minecraft:') else p


def warn(msg):
    print('!! ' + msg, file=sys.stderr)


# ---------------------------------------------------------------------------------------------- files

class Part:
    def __init__(self, no, json_path, base):
        self.no = no
        self.json = json_path
        self.idx = base + 'idx.jsonl'
        raw = base + 'raw.gz'
        self.raw = raw if os.path.exists(raw) else (raw + '.part' if os.path.exists(raw + '.part') else None)
        ent = base + 'ent.xz'
        self.ent = ent if os.path.exists(ent) else (ent + '.part' if os.path.exists(ent + '.part') else None)
        self.unfinished = json_path.endswith('.part')

    def index(self):
        """The part's member index; a torn last line (a crash mid-write) is ignored."""
        out = []
        if not os.path.exists(self.idx):
            return out
        with open(self.idx, 'rb') as f:
            for line in f:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    pass
        return out


class Recording:
    def __init__(self, paths):
        files, self.dir = [], None
        for p in paths:
            if os.path.isdir(p):
                self.dir = p
                files += glob.glob(os.path.join(p, 'part*.jsonl.gz')) + glob.glob(os.path.join(p, 'part*.jsonl.gz.part'))
            elif os.path.basename(p) == 'manifest.json':
                self.dir = os.path.dirname(p) or '.'
                files += glob.glob(os.path.join(self.dir, 'part*.jsonl.gz')) + glob.glob(os.path.join(self.dir, 'part*.jsonl.gz.part'))
            elif os.path.exists(p):
                files.append(p)
                self.dir = self.dir or (os.path.dirname(p) or '.')
            else:
                sys.exit('no such file or directory: %s' % p)
        parts = {}
        for f in files:
            m = re.search(r'part(\d+)\.jsonl\.gz(\.part)?$', f)
            if not m:
                sys.exit('not a recorder-2 part: %s (old recorder-1 files are single .jsonl.gz files)' % f)
            no = int(m.group(1))
            base = f[:m.start()] + 'part%s.' % m.group(1)
            if no not in parts or parts[no].unfinished:
                parts[no] = Part(no, f, base)
        self.parts = [parts[k] for k in sorted(parts)]
        self.manifest = self._json('manifest.json') or {}
        self._warned = False

    def _json(self, name):
        if not self.dir:
            return None
        p = os.path.join(self.dir, name)
        try:
            with open(p, 'rb') as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    def side(self, name):
        """Lines of a side file (entities.jsonl, events.jsonl), torn lines counted."""
        out, bad = [], 0
        if not self.dir:
            return out, bad
        p = os.path.join(self.dir, name)
        if not os.path.exists(p):
            return None, 0
        with open(p, 'rb') as f:
            for line in f:
                if not line.strip():
                    continue
                try:
                    out.append(json.loads(line))
                except ValueError:
                    bad += 1
        return out, bad

    def manifest_warnings(self):
        if self._warned:
            return
        self._warned = True
        m = self.manifest
        if not m:
            warn('no manifest.json next to the parts')
            return
        if m.get('format') not in (None, 'recorder-2'):
            warn('format %r, this reader knows recorder-2' % m.get('format'))
        if m.get('crashed'):
            warn('the game did not close this recording (crashed); lines after seq %s were lost' % m.get('lostAfterSeq'))
        if m.get('stoppedReason'):
            warn('recording stopped early: %s %s' % (m.get('stoppedReason'), m.get('ioError', '')))
        if not m.get('complete', True) and not m.get('crashed') and not m.get('stoppedReason'):
            warn('recording not complete (still being written, or not recovered yet)')
        for g in m.get('gaps', []):
            warn('gap: %s lines missing, seq %s, why %s%s' % (g.get('lines'), g.get('range'), g.get('why'), (', types %s' % g['types']) if g.get('types') else ''))
        for k, v in sorted((m.get('errors') or {}).items()):
            warn('%d lines of %s failed to build (written as error lines)' % (v, k))

    def members(self):
        """(part, path, off, len or None, idx entry or None) for every member, indexed or not."""
        out = []
        for part in self.parts:
            idx = part.index()
            end = 0
            for e in idx:
                if e.get('len', 0) > 0:
                    out.append((part.no, part.json, e['off'], e['len'], e))
                end = max(end, e['off'] + e['len'])
            try:
                size = os.path.getsize(part.json)
            except OSError:
                size = 0
            if size > end:
                # Beyond the index: a part still being written, or a crash before recovery. Read as far as it goes.
                out.append((part.no, part.json, end, None, None))
        return out


# ---------------------------------------------------------------------------------------------- decoding

def gunzip(data, stats):
    """Every gzip member in data, concatenated; a truncated last member yields what it had."""
    out, pos = [], 0
    while pos < len(data):
        d = zlib.decompressobj(16 + zlib.MAX_WBITS)
        try:
            out.append(d.decompress(data[pos:]))
        except zlib.error as e:
            stats['truncated'] += 1
            stats['notes'].append('%s+%d: corrupt gzip member: %s' % (stats.get('where', ''), pos, e))
            break
        if not d.eof:
            stats['truncated'] += 1
            stats['notes'].append('%s+%d: gzip member cut off (crash?); read up to its last whole line' % (stats.get('where', ''), pos))
            break
        used = len(data) - pos - len(d.unused_data)
        if used <= 0:
            break
        pos += used
    return b''.join(out)


class Filter:
    """What to keep; picklable, so worker processes can apply it."""

    def __init__(self, kinds=None, types=None, skip=None, entity=None, seq=(None, None), ms=(None, None),
                 n=(None, None), regex=None, no_movement=False, everything=False):
        self.kinds = kinds or []
        self.types = set(types or [])
        self.skip = set(skip or [])
        self.entity = entity
        self.seq, self.ms, self.n = seq, ms, n
        self.regex = regex
        self.no_movement = no_movement
        self.everything = everything
        # Cheap byte prefilters: a line can only match if it contains one of these.
        needles = []
        if self.kinds and all(not any(c in k for c in '*?[') for k in self.kinds):
            needles += [('"k":"%s"' % k).encode() for k in self.kinds] + [('"k": "%s"' % k).encode() for k in self.kinds]
        if self.types and not needles:
            needles += [('%s"' % t).encode() for t in self.types]
        self.needles = needles
        self.entity_needle = str(entity).encode() if entity is not None else None
        self._re = re.compile(regex.encode(), re.I) if regex else None

    def kind_ok(self, k):
        if self.kinds and not any(fnmatch.fnmatchcase(k, g) for g in self.kinds):
            return False
        if not self.everything and not self.kinds and any(fnmatch.fnmatchcase(k, g) for g in DEFAULT_EXCLUDED_KINDS):
            return False
        if self.no_movement and k in MOVEMENT_KINDS:
            return False
        return True

    def member_may_match(self, e):
        """From an index entry alone: can this member hold a matching line?"""
        if e is None:
            return True

        def outside(r, rng):
            return r and ((rng[0] is not None and r[1] < rng[0]) or (rng[1] is not None and r[0] > rng[1]))
        if outside(e.get('seq'), self.seq) or outside(e.get('ms'), self.ms) or outside(e.get('n'), self.n):
            return False
        types = e.get('types') or {}
        if not types or (not self.kinds and not self.types):
            return True
        for key in types:
            if ':' in key:  # a packet line: the index keys packets by their type
                if self.types and short(key) not in self.types:
                    continue
                if self.kinds and not any(fnmatch.fnmatchcase(k, g) for k in PACKET_KINDS for g in self.kinds):
                    continue
                return True
            if self.types and key not in ('bundle',):
                continue
            if self.kind_ok(key):
                return True
        return False

    def raw_ok(self, line):
        if self.needles and not any(nd in line for nd in self.needles):
            return False
        if self.entity_needle is not None and self.entity_needle not in line:
            return False
        if self._re is not None and not self._re.search(line):
            return False
        return True

    def ok(self, o):
        k = o.get('k', '?')
        if not self.kind_ok(k):
            return False
        p = short(o.get('p'))
        if k in PACKET_KINDS:
            if self.types and p not in self.types:
                return False
            if p in self.skip:
                return False
            if self.no_movement and p in MOVEMENT:
                return False
            if not self.everything and not self.types and p in DEFAULT_EXCLUDED_TYPES:
                return False
        elif self.types:
            return False
        for name, rng in (('seq', self.seq), ('ms', self.ms), ('n', self.n)):
            v = o.get(name)
            if isinstance(v, (int, float)) and ((rng[0] is not None and v < rng[0]) or (rng[1] is not None and v > rng[1])):
                return False
        if self.entity is not None and not about_entity(o, self.entity):
            return False
        return True


def about_entity(o, eid):
    if eid in (o.get('e') or []):
        return True
    for key in ('id', 'eid', 'entityId'):
        if o.get(key) == eid:
            return True
    if o.get('k') in ('ent', 'emove', 'kfent'):
        rows = [r for r in o.get('d') or [] if (r[0] if isinstance(r, list) and r else (r.get('id') if isinstance(r, dict) else None)) == eid]
        if rows:
            o['d'] = rows
            return True
    return False


def read_member(args):
    """Worker: one member decompressed and filtered. Returns (lines, stats)."""
    path, off, length, flt = args
    stats = {'truncated': 0, 'bad': 0, 'bad_samples': [], 'notes': [], 'torn': 0, 'where': '%s@%d' % (os.path.basename(path), off)}
    try:
        with open(path, 'rb') as f:
            f.seek(off)
            data = f.read() if length is None else f.read(length)
    except OSError as e:
        stats['notes'].append('cannot read %s: %s' % (path, e))
        return [], stats
    text = gunzip(data, stats)
    lines = text.split(b'\n')
    if text and not text.endswith(b'\n'):
        stats['torn'] += 1  # the last line was cut mid-way
        lines = lines[:-1]
    out = []
    for raw in lines:
        if not raw.strip():
            continue
        if flt is not None and not flt.raw_ok(raw):
            continue
        try:
            o = json.loads(raw)
        except ValueError:
            stats['bad'] += 1
            if len(stats['bad_samples']) < 3:
                stats['bad_samples'].append(raw[:200].decode('utf-8', 'replace'))
            continue
        if flt is None or flt.ok(o):
            out.append(o)
    return out, stats


def ent_lines(part, flt, stats):
    """The per-tick entity rows written to partNNNN.ent.xz (Compact Entity Rows)."""
    if not part.ent:
        return []
    with open(part.ent, 'rb') as f:
        data = f.read()
    d = lzma.LZMADecompressor(format=lzma.FORMAT_XZ)
    try:
        text = d.decompress(data)
    except lzma.LZMAError as e:
        stats['notes'].append('%s: %s (read up to the damage)' % (part.ent, e))
        text = b''
    out = []
    for raw in text.split(b'\n'):
        if not raw.strip() or (flt and not flt.raw_ok(raw)):
            continue
        try:
            o = json.loads(raw)
        except ValueError:
            stats['bad'] += 1
            continue
        if flt is None or flt.ok(o):
            out.append(o)
    return out


def stream(rec, flt=None, jobs=None, sort=True):
    """Every line matching flt, in seq order (the true order things happened in, across threads)."""
    rec.manifest_warnings()
    mem = rec.members()
    todo = [m for m in mem if flt is None or flt.member_may_match(m[4])]
    # Members skipped by the filter may still hold warnings: say so from the index alone.
    for m in mem:
        if m in todo or m[4] is None:
            continue
        t = m[4].get('types') or {}
        for k in WARN_KINDS:
            if t.get(k):
                warn('part %d member seq %s holds %d %s line(s) (outside this filter)' % (m[0], m[4].get('seq'), t[k], k))
    totals = Counter()
    samples = []
    bounds = [m[4]['seq'][0] if m[4] and m[4].get('seq') else None for m in todo]
    args = [(m[1], m[2], m[3], flt) for m in todo]
    results = map_members(args, jobs)
    heap, counter = [], 0

    def emit_ready(limit):
        while heap and (limit is None or heap[0][0] < limit):
            yield heapq.heappop(heap)[2]

    ent_parts = [p for p in rec.parts if p.ent and (flt is None or flt.kind_ok('ent'))]
    for i, (lines, st) in enumerate(results):
        for key in ('truncated', 'bad', 'torn'):
            totals[key] += st[key]
        samples += st['bad_samples']
        for note in st['notes']:
            warn(note)
        for o in lines:
            if o.get('k') in WARN_KINDS:
                warn_line(o)
            if not sort:
                yield o
                continue
            counter += 1
            heapq.heappush(heap, (o.get('seq') if isinstance(o.get('seq'), int) else -1, counter, o))
        if sort and i + 1 < len(bounds):
            # Lines from different threads reach a member a little out of order; everything below the
            # next member's first seq is final (an unindexed tail keeps everything back).
            nxt = bounds[i + 1]
            yield from emit_ready(nxt if nxt is not None else -1)
    for part in ent_parts:
        st = {'notes': [], 'bad': 0}
        for o in ent_lines(part, flt, st):
            counter += 1
            heapq.heappush(heap, (o.get('seq', -1), counter, o))
        totals['bad'] += st['bad']
        for note in st['notes']:
            warn(note)
    yield from emit_ready(None)
    if totals['bad']:
        warn('%d line(s) did not parse as JSON (not shown); first: %s' % (totals['bad'], samples[:3]))
    if totals['truncated'] or totals['torn']:
        warn('%d member(s) cut off, %d torn last line(s): the end of the recording is incomplete' % (totals['truncated'], totals['torn']))


def warn_line(o):
    k = o.get('k')
    if k == 'gap':
        warn('gap at seq %s: %s line(s) not recorded (%s) types %s' % (o.get('range') or o.get('seq'), o.get('lines'), o.get('why'), o.get('types')))
    elif k == 'stopped':
        warn('recording stopped at seq %s: %s %s' % (o.get('seq'), o.get('why', ''), {x: o[x] for x in o if x in ('error', 'freeBytes', 'limitBytes')}))
    elif k == 'conn_error':
        warn('connection error at seq %s: %s' % (o.get('seq'), o.get('error')))


def map_members(args, jobs):
    if jobs is None:
        jobs = min(4, os.cpu_count() or 1)
    if jobs > 1 and len(args) >= 16:
        try:
            from concurrent.futures import ProcessPoolExecutor
            ex = ProcessPoolExecutor(max_workers=jobs)
            try:
                yield from ex.map(read_member, args, chunksize=8)
                return
            finally:
                ex.shutdown(cancel_futures=True)
        except (OSError, ImportError, NotImplementedError, RuntimeError) as e:
            warn('no worker processes (%s); reading serially' % e)
    for a in args:
        yield read_member(a)


# ---------------------------------------------------------------------------------------------- formatting

ENVELOPE = ('k', 'seq', 't', 'n', 'ms', 'ns')


def compact(o, max_chars):
    k = o.get('k', '?')
    head = 'n%-7s t%-7s #%-9s %-10s' % (o.get('n', ''), o.get('t', ''), o.get('seq', ''), k)
    rest = {x: v for x, v in o.items() if x not in ENVELOPE}
    if k in PACKET_KINDS and 'p' in rest:
        head += ' ' + short(rest.pop('p'))
        f = rest.pop('f', None)
        body = json.dumps(f, separators=(',', ':'), ensure_ascii=False) if f is not None else ''
        extra = json.dumps(rest, separators=(',', ':'), ensure_ascii=False) if rest else ''
        text = (extra + ' ' + body).strip()
    else:
        text = json.dumps(rest, separators=(',', ':'), ensure_ascii=False)
    if max_chars and len(text) > max_chars:
        text = text[:max_chars] + '...(+%d)' % (len(text) - max_chars)
    return head + ' ' + text


def parse_range(s, conv=int):
    if s is None:
        return (None, None)
    a, _, b = s.partition(':')
    return (conv(a) if a else None, conv(b) if b else None)


def csv(s):
    return [x.strip() for x in s.split(',') if x.strip()] if s else []


# ---------------------------------------------------------------------------------------------- commands

def cmd_summary(rec, a):
    rec.manifest_warnings()
    m = rec.manifest
    keys = ('id', 'label', 'via', 'self', 'uuid', 'server', 'mod', 'odin', 'mc', 'fabricApi', 'complete', 'crashed',
            'stoppedReason', 'lines', 'gzBytes', 'rawBytes', 'lastGoodSeq')
    print('recording:', json.dumps({k: m[k] for k in keys if k in m}, ensure_ascii=False))
    if m.get('start'):
        end = m.get('end')
        print('started %s ms, %s' % (m['start'], ('%.1f min long' % ((end - m['start']) / 60000)) if end else 'no end time (open or crashed)'))
    types, kinds = Counter(), Counter()
    span = {}
    for part in rec.parts:
        idx = part.index()
        for e in idx:
            for k, v in (e.get('types') or {}).items():
                types[k] += v
            for r in ('seq', 't', 'n', 'ms'):
                if e.get(r):
                    lo, hi = span.get(r, (e[r][0], e[r][1]))
                    span[r] = (min(lo, e[r][0]), max(hi, e[r][1]))
        print('part %04d: %d members%s%s%s' % (part.no, len(idx), ', unfinished (.part)' if part.unfinished else '',
                                              ', raw sidecar' if part.raw else '', ', entity xz' if part.ent else ''))
    for r, (lo, hi) in span.items():
        print('  %-4s %s .. %s' % (r, lo, hi))
    if 'n' in span:
        print('  %.1f minutes of server ticks' % ((span['n'][1] - span['n'][0]) / 1200))
    if not types:
        types = Counter(m.get('counts') or {})
    for marks in m.get('marks') or []:
        print('mark: %s' % json.dumps(marks, ensure_ascii=False))
    packets = Counter({k: v for k, v in types.items() if ':' in k})
    others = Counter({k: v for k, v in types.items() if ':' not in k})
    print('\nline kinds (packets are counted by type below):')
    for k, c in others.most_common():
        print('  %9d  %s' % (c, k))
    print('\npacket types (in, out and config):')
    for k, c in packets.most_common():
        print('  %9d  %s' % (c, short(k)))
    if a.scan:
        n = sum(1 for _ in stream(rec, None, a.j, sort=False))
        print('\nscanned: %d lines decoded' % n)


def cmd_index(rec, a):
    rec.manifest_warnings()
    for part in rec.parts:
        idx = part.index()
        print('part %04d  %s  (%d members)' % (part.no, os.path.basename(part.json), len(idx)))
        for e in idx:
            print('  off %10d len %8d lines %6d seq %-22s n %-16s t %-16s kf %-5s %s' % (
                e.get('off'), e.get('len'), e.get('lines', 0), e.get('seq'), e.get('n'), e.get('t'), e.get('kf'),
                ' '.join('%s:%d' % (short(k), v) for k, v in sorted((e.get('types') or {}).items(), key=lambda x: -x[1])[:a.top])))


def cmd_entities(rec, a):
    lines, bad = rec.side('entities.jsonl')
    if bad:
        warn('%d torn line(s) in entities.jsonl' % bad)
    if lines is None or a.rebuild:
        lines = list(stream(rec, Filter(kinds=['espawn', 'egone']), a.j))
        if not lines:  # no client-side entity lines (older units off): the add_entity packets
            lines = list(stream(rec, Filter(types=['add_entity']), a.j))
    seen = OrderedDict()
    for o in lines:
        k = o.get('k')
        if k in ('espawn', 'entities') and o.get('at', 'load') == 'load':
            seen.setdefault(o.get('id'), o)
        elif k == 'in' and short(o.get('p')) == 'add_entity':
            f = o.get('f') or {}
            seen.setdefault(f.get('id'), {'id': f.get('id'), 'type': f.get('etype') or f.get('type'), 'seq': o.get('seq'), 'n': o.get('n'), 'pos': [f.get('x'), f.get('y'), f.get('z')]})
        elif k == 'egone' and o.get('id') in seen:
            seen[o['id']]['gone'] = o.get('seq')
    for eid, o in seen.items():
        name = o.get('name')
        name = name.get('t') if isinstance(name, dict) else name
        print('%8s  %-32s n%-7s %s%s%s' % (eid, o.get('type'), o.get('n'), o.get('pos'), ('  "%s"' % name) if name else '',
                                       ('  gone@%s' % o['gone']) if o.get('gone') else ''))


def cmd_events(rec, a):
    rec.manifest_warnings()
    lines, bad = rec.side('events.jsonl')
    if bad:
        warn('%d torn line(s) in events.jsonl' % bad)
    if lines is None or a.rebuild:
        lines = list(stream(rec, Filter(kinds=['world', 'end', 'death', 'revive', 'leap', 'ec.split', 'settings', 'mark', 'keyframe', 'gap', 'stopped', 'disconnect']), a.j))
    for o in lines:
        print(compact(o, a.max_chars))


def cmd_schema(rec, a):
    s = rec._json('schema.json')
    if not s:
        sys.exit('no schema.json in %s' % rec.dir)
    for section in ('classes', 'enums'):
        for name, fields in sorted((s.get(section) or {}).items()):
            if a.name and a.name.lower() not in name.lower():
                continue
            print('%s %s: %s' % (section[:-2] if section == 'classes' else 'enum', name, ', '.join(fields)))


def cmd_example(rec, a):
    want = a.what
    keys = set()
    for part in rec.parts:
        for e in part.index():
            keys |= set(e.get('types') or {})
    # A kind when the index knows it as one, else a packet type.
    is_kind = want in keys or (':' not in want and 'minecraft:' + want not in keys and '_' not in want)
    flt = Filter(kinds=[want], everything=True) if is_kind else Filter(types=[short(want)], everything=True)
    shown = 0
    for o in stream(rec, flt, a.j):
        print(json.dumps(o, ensure_ascii=False, indent=None if a.compact else 1))
        shown += 1
        if shown >= a.count:
            break
    if not shown:
        print('no %s line' % want)


def timeline_filter(a):
    types = [short(t) for t in csv(a.types)]
    regex = a.regex or (re.escape(a.grep) if a.grep else None)
    return Filter(kinds=csv(a.kinds), types=types, skip=[short(t) for t in csv(a.skip)], entity=a.entity,
                  seq=parse_range(a.seq), ms=parse_range(a.ms), n=(a.from_, a.to), regex=regex,
                  no_movement=a.no_movement, everything=a.all)


def cmd_timeline(rec, a):
    flt = timeline_filter(a)
    if a.around is not None:
        flt.seq = (None, None)
        before, after = [], []
        for o in stream(rec, flt, a.j):
            s = o.get('seq', -1)
            if s < a.around:
                before.append(o)
                if len(before) > a.window:
                    before.pop(0)
            else:
                after.append(o)
                if len(after) > a.window:
                    break
        lines = before + after
    else:
        lines = stream(rec, flt, a.j)
    for o in lines:
        print(json.dumps(o, ensure_ascii=False, separators=(',', ':')) if a.json else compact(o, a.max_chars))


# ---------------------------------------------------------------------------------------------- state

class World:
    """Blocks, entities, maps and HUD state rebuilt from a keyframe forward."""

    def __init__(self):
        self.sections = {}      # (cx, sy, cz) -> (palette, rle or None)
        self.decoded = {}
        self.chunks = set()
        self.edits = {}         # (x, y, z) -> state, applied after the sections
        self.be = {}            # (x, y, z) -> [type, snbt]
        self.ents = {}          # id -> dict
        self.maps = {}          # id -> bytearray(16384)
        self.board = {}
        self.tab = {}
        self.tab_n = None
        self.last = {}          # kind -> last line (odin.dungeon, rooms, where, team...)
        self.rooms = {}         # id -> last room line
        self.pending = {}       # packets arrived but not yet applied: seq -> line
        self.defer = False      # hold packets until their `applied` line (the recording has them)
        self.notes = Counter()

    # blocks
    def chunk(self, f):
        if not isinstance(f, dict) or f.get('off') or 's' not in f:
            return
        cx, cz = f['x'], f['z']
        self.chunks.add((cx, cz))
        for s in f.get('s') or []:
            key = (cx, s['y'], cz)
            self.sections[key] = (s.get('pal') or [], s.get('rle'))
            self.decoded.pop(key, None)
            for p in [p for p in self.edits if p[0] >> 4 == cx and p[2] >> 4 == cz and p[1] >> 4 == s['y']]:
                del self.edits[p]
        for b in f.get('be') or []:
            self.be[(b[0], b[1], b[2])] = b[3:]

    def unload(self, cx, cz):
        self.chunks.discard((cx, cz))
        for key in [k for k in self.sections if k[0] == cx and k[2] == cz]:
            del self.sections[key]
            self.decoded.pop(key, None)

    def block(self, x, y, z):
        if (x, y, z) in self.edits:
            return self.edits[(x, y, z)]
        key = (x >> 4, y >> 4, z >> 4)
        sec = self.sections.get(key)
        if sec is None:
            return None if (x >> 4, z >> 4) not in self.chunks else '?'
        pal, rle = sec
        if not rle:
            return pal[0] if pal else '?'
        arr = self.decoded.get(key)
        if arr is None:
            arr = []
            for i in range(0, len(rle) - 1, 2):
                arr.extend([rle[i + 1]] * rle[i])
            self.decoded[key] = arr
        i = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15)
        return pal[arr[i]] if i < len(arr) and arr[i] < len(pal) else '?'

    def set_block(self, p, state):
        if isinstance(p, list) and len(p) == 3:
            self.edits[(p[0], p[1], p[2])] = state

    # maps
    def map_full(self, o):
        try:
            self.maps[o['id']] = bytearray(base64.b64decode(o['b64']))
        except (KeyError, ValueError, TypeError):
            self.notes['bad mapfull'] += 1

    def map_patch(self, f):
        cp = f.get('colorPatch') or {}
        mid = f.get('mapId')
        mid = mid.get('id') if isinstance(mid, dict) else mid
        if not cp or mid is None or 'b64' not in cp:
            return
        m = self.maps.setdefault(mid, bytearray(128 * 128))
        data = base64.b64decode(cp['b64'])
        x0, y0, w, h = cp.get('x', 0), cp.get('y', 0), cp.get('w', 0), cp.get('h', 0)
        for dy in range(h):
            for dx in range(w):
                if dx + dy * w < len(data) and 0 <= x0 + dx < 128 and 0 <= y0 + dy < 128:
                    m[x0 + dx + (y0 + dy) * 128] = data[dx + dy * w]

    # entities
    def ent_full(self, o):
        if not isinstance(o, dict) or 'id' not in o:
            return
        e = self.ents.setdefault(o['id'], {})
        e.update({k: v for k, v in o.items() if k not in ENVELOPE})

    def ent_row(self, r):
        if not isinstance(r, list) or len(r) < 4:
            return
        e = self.ents.setdefault(r[0], {'id': r[0]})
        e['pos'] = [r[1], r[2], r[3]]
        if len(r) > 19 and r[19] is not None:
            e['hp'] = r[19]
        if len(r) > 8:
            e['rot'] = [r[7], r[8]]

    def emove(self, r):
        if not isinstance(r, list) or len(r) < 4 or r[0] not in self.ents:
            return
        e = self.ents[r[0]]
        pos = list(e.get('pos') or [None, None, None])
        for i in range(3):
            if r[1 + i] is not None:
                pos[i] = r[1 + i]
        e['pos'] = pos

    def apply(self, o, use_packets):
        k = o.get('k')
        f = o.get('f')
        if k == 'keyframe':
            self.last['keyframe'] = o
        elif k == 'kfchunk':
            self.chunk(o)
        elif k == 'kfbe':
            for b in o.get('d') or []:
                self.be[(b[0], b[1], b[2])] = b[3:]
        elif k == 'kfchunks':
            self.last['kfchunks'] = o
        elif k == 'cchunk':
            if o.get('ev') == 'unload':
                self.unload(o.get('x'), o.get('z'))
        elif k == 'blk':
            self.set_block(o.get('p'), o.get('new'))
        elif k == 'mapfull':
            self.map_full(o)
        elif k in ('espawn',):
            self.ent_full(o)
        elif k == 'kfent':
            for e in o.get('d') or []:
                self.ent_full(e)
        elif k == 'ent':
            for r in o.get('d') or []:
                self.ent_row(r)
        elif k == 'emove':
            for r in o.get('d') or []:
                self.emove(r)
        elif k == 'egone':
            self.ents.pop(o.get('id'), None)
        elif k == 'board':
            self.board[o.get('slot')] = o
        elif k == 'tab':
            self.tab_n = o.get('count', self.tab_n)
            for r in o.get('rows') or []:
                if isinstance(r, list) and r:
                    self.tab[r[0]] = r
            if self.tab_n is not None:
                for i in [i for i in self.tab if i >= self.tab_n]:
                    del self.tab[i]
        elif k == 'room':
            if o.get('gone'):
                self.rooms.pop(o.get('id'), None)
            else:
                self.rooms[o.get('id')] = o
        elif k in ('applied', 'fate'):
            # The packets this line settles: applied ones change the world now, cancelled ones never do.
            for s in o.get('seqs') or []:
                for x in (range(s[0], s[1] + 1) if isinstance(s, list) else (s,)):
                    line = self.pending.pop(x, None)
                    if line is not None and k == 'applied':
                        self.packet(line, use_packets)
        elif k == 'in' and isinstance(f, dict):
            if self.defer:
                self.pending[o.get('seq')] = o
            else:
                self.packet(o, use_packets)
        elif k in ('rooms', 'odin.dungeon', 'team', 'where', 'doors', 'puzzles', 'term', 'sb', 'loc', 'me', 'world', 'env', 'screen',
                   'bars', 'inv', 'effects'):
            self.last[k] = o

    def packet(self, o, use_packets):
        """A server packet taking effect (at its `applied` line when the recording has them)."""
        f = o.get('f')
        if isinstance(f, dict):
            p = short(o.get('p'))
            if p == 'level_chunk_with_light':
                self.chunk(f)
            elif p == 'forget_level_chunk':
                pos = f.get('pos')
                if isinstance(pos, dict):
                    self.unload(pos.get('x'), pos.get('z'))
            elif p == 'map_item_data':
                self.map_patch(f)
            elif use_packets and p == 'section_blocks_update':
                for c in f.get('changes') or []:
                    self.set_block(c[:3], c[3])
            elif use_packets and p == 'block_update':
                self.set_block(f.get('pos'), f.get('blockState'))
            elif use_packets and p == 'add_entity':
                self.ent_full({'id': f.get('id'), 'type': f.get('etype'), 'pos': [f.get('x'), f.get('y'), f.get('z')]})
            elif use_packets and p == 'remove_entities':
                for i in f.get('entityIds') or []:
                    self.ents.pop(i, None)


def rebuild(rec, a, at_seq=None, at_n=None, at_t=None):
    """The world at a moment: from the nearest keyframe at or before it, every line applied in order."""
    def before(o):
        if at_seq is not None:
            return o.get('seq', 0) <= at_seq
        if at_n is not None:
            return o.get('n', 0) <= at_n
        return o.get('t', 0) <= at_t
    # The nearest keyframe, from the index (members carry their keyframe id) and the keyframe lines.
    kf_seq, kf_id = None, None
    for o in stream(rec, Filter(kinds=['keyframe']), a.j):
        if before(o):
            kf_seq, kf_id = o['seq'], o.get('kf')
        else:
            break
    if kf_seq is None:
        warn('no keyframe before that moment; rebuilding from the start of the recording')
    w = World()
    flt = Filter(everything=True, seq=(kf_seq, None))
    blk_seen = False
    buffered = []
    stop_after = None
    for o in stream(rec, flt, a.j):
        if not before(o):
            # Keyframe lines trail their keyframe by a few ticks (chunks are copied 64 a tick): keep
            # applying kf* lines of the chosen keyframe a little past the moment.
            if stop_after is None:
                stop_after = o.get('t', 0) + 200
            if o.get('k', '').startswith('kf') and kf_id is not None and o.get('kf') == kf_id and o.get('t', 0) <= stop_after:
                buffered.append(o)
                continue
            if o.get('t', 0) > stop_after:
                break
            continue
        if o.get('k') == 'blk':
            blk_seen = True
        buffered.append(o)
    use_packets = not blk_seen
    # With `applied` lines (packet fate hooks working) a packet changes the world when it applied,
    # not when it arrived; without them, as it arrives.
    w.defer = any(o.get('k') == 'applied' for o in buffered)
    for o in buffered:
        w.apply(o, use_packets)
    return w, kf_seq


MAP_BASE = ['none', 'grass', 'sand', 'wool', 'fire', 'ice', 'metal', 'plant', 'snow', 'clay', 'dirt', 'stone', 'water', 'wood',
            'quartz', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple', 'blue',
            'brown', 'green', 'red', 'black', 'gold', 'diamond', 'lapis', 'emerald', 'podzol', 'nether', 'tc_white', 'tc_orange',
            'tc_magenta', 'tc_light_blue', 'tc_yellow', 'tc_lime', 'tc_pink', 'tc_gray', 'tc_light_gray', 'tc_cyan', 'tc_purple',
            'tc_blue', 'tc_brown', 'tc_green', 'tc_red', 'tc_black', 'crimson_nylium', 'crimson_stem', 'crimson_hyphae',
            'warped_nylium', 'warped_stem', 'warped_hyphae', 'warped_wart', 'deepslate', 'raw_iron', 'glow_lichen']
MAP_CHARS = ' .,:;-=+*#%@&$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'


def render_map(data):
    """A map's 128x128 packed colours (base colour * 4 + shade) as ASCII, one character per base colour, two columns per row."""
    used = Counter(b >> 2 for b in data)
    order = [c for c, _ in used.most_common()]
    chars = {c: (' ' if c == 0 else MAP_CHARS[1 + i % (len(MAP_CHARS) - 1)]) for i, c in enumerate(order)}
    rows = []
    for y in range(0, 128, 2):
        rows.append(''.join(chars[data[x + y * 128] >> 2] for x in range(128)))
    legend = ', '.join('%r=%s(%d)' % (chars[c], MAP_BASE[c] if c < len(MAP_BASE) else c, used[c]) for c in order)
    return '\n'.join(rows) + '\nlegend: ' + legend + '  (every other row; packed colour = base*4 + shade)'


def describe_state(w, a, out):
    kf = w.last.get('keyframe')
    out.append('keyframe: %s' % (json.dumps({k: kf.get(k) for k in ('kf', 'reason', 'seq', 't', 'n')}) if kf else 'none'))
    out.append('chunks held: %d (sections %d), block edits since: %d, block entities: %d' % (
        len(w.chunks), len(w.sections), len(w.edits), len(w.be)))
    for k in ('world', 'env', 'me', 'odin.dungeon', 'where', 'team', 'loc', 'sb', 'puzzles', 'term', 'doors', 'screen', 'bars', 'effects'):
        if k in w.last:
            o = {x: v for x, v in w.last[k].items() if x not in ('k', 'ms', 'ns')}
            out.append('%s: %s' % (k, trunc(json.dumps(o, ensure_ascii=False, separators=(',', ':')), a.max_chars)))
    if w.board:
        for slot, b in w.board.items():
            lines = [(l[2].get('t') if isinstance(l[2], dict) else l[2]) for l in b.get('lines') or [] if len(l) > 2]
            title = b.get('title')
            out.append('board %s: %s | %s' % (slot, title.get('t') if isinstance(title, dict) else title, ' / '.join(str(x) for x in lines)))
    if w.tab:
        names = []
        for i in sorted(w.tab):
            r = w.tab[i]
            d = r[3] if len(r) > 3 else None
            names.append(str(d.get('t') if isinstance(d, dict) else (d or (r[2] if len(r) > 2 else ''))))
        out.append('tab (%d rows): %s' % (len(w.tab), trunc(' | '.join(names), a.max_chars * 2)))
    out.append('rooms known: %d%s' % (len(w.rooms), ('; ' + ', '.join(sorted(str(r.get('name') or r.get('id')) for r in w.rooms.values()))) if w.rooms else ''))
    types = Counter(str(e.get('type')) for e in w.ents.values())
    out.append('entities: %d  %s' % (len(w.ents), ', '.join('%s x%d' % (short(t), c) for t, c in types.most_common(15))))
    if w.pending:
        out.append('arrived but not yet applied: %d packet(s): %s' % (len(w.pending), ', '.join(sorted(set(short(o.get('p')) for o in w.pending.values()))[:20])))
    if w.maps:
        out.append('maps: %s' % ', '.join(str(m) for m in w.maps))


def trunc(s, n):
    return s if not n or len(s) <= n else s[:n] + '...(+%d)' % (len(s) - n)


def cmd_state(rec, a):
    if a.at is None and a.at_seq is None and a.at_t is None:
        sys.exit('state needs --at N (server tick), --at-seq S or --at-t T')
    w, _ = rebuild(rec, a, at_seq=a.at_seq, at_n=a.at, at_t=a.at_t)
    out = []
    describe_state(w, a, out)
    print('\n'.join(out))
    if a.entities:
        for eid, e in sorted(w.ents.items(), key=lambda x: (x[0] if isinstance(x[0], int) else 0)):
            name = e.get('name')
            print('  %8s %-28s pos %s%s%s' % (eid, short(str(e.get('type'))), e.get('pos'), ' hp %s' % e['hp'] if 'hp' in e else '',
                                            ('  "%s"' % (name.get('t') if isinstance(name, dict) else name)) if name else ''))
    if a.pos:
        x, y, z = [int(float(v)) for v in a.pos.split(',')]
        print('block at %d,%d,%d: %s%s' % (x, y, z, w.block(x, y, z), ('  be %s' % w.be[(x, y, z)]) if (x, y, z) in w.be else ''))
    if a.box:
        x1, y1, z1, x2, y2, z2 = [int(float(v)) for v in a.box.split(',')]
        for y in range(min(y1, y2), max(y1, y2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                for x in range(min(x1, x2), max(x1, x2) + 1):
                    b = w.block(x, y, z)
                    if b not in ('minecraft:air', None) or a.with_air:
                        print('%d,%d,%d %s' % (x, y, z, b))
    if a.room:
        found = [r for r in w.rooms.values() if a.room.lower() in str(r.get('name', '')).lower()]
        for r in found:
            print(json.dumps(r, ensure_ascii=False))
        if not found:
            print('no room named like %r (rooms: %s)' % (a.room, ', '.join(str(r.get('name')) for r in w.rooms.values())))
    if a.map is not None:
        ids = list(w.maps) if a.map == -1 else [a.map]
        for mid in ids:
            if mid in w.maps:
                print('map %s:' % mid)
                print(render_map(w.maps[mid]))
            else:
                print('no map %s (maps: %s)' % (mid, list(w.maps)))


# ---------------------------------------------------------------------------------------------- raw

def raw_records(part, stats):
    """Every record of a part's raw sidecar: (seq, dir, phase, withheld, length, bytes)."""
    if not part.raw:
        return
    with open(part.raw, 'rb') as f:
        data = f.read()
    text = gunzip(data, stats)
    pos, n = 0, len(text)
    while pos + 4 <= n:
        ln = struct.unpack('>I', text[pos:pos + 4])[0]
        pos += 4
        seq, shift = 0, 0
        while pos < n:
            b = text[pos]
            pos += 1
            seq |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
        if pos + 3 > n:
            break
        d, ph, flags = text[pos], text[pos + 1], text[pos + 2]
        pos += 3
        withheld = bool(flags & 1)
        body = b'' if withheld else text[pos:pos + ln]
        if not withheld:
            pos += ln
        yield seq, d, ph, withheld, ln, body


PHASES = {0: 'handshaking', 1: 'status', 2: 'login', 3: 'configuration', 4: 'play'}


def hexdump(b, limit):
    out = []
    for i in range(0, min(len(b), limit), 16):
        chunk = b[i:i + 16]
        out.append('  %06x  %-48s %s' % (i, ' '.join('%02x' % c for c in chunk), ''.join(chr(c) if 32 <= c < 127 else '.' for c in chunk)))
    if len(b) > limit:
        out.append('  ... %d more bytes' % (len(b) - limit))
    return '\n'.join(out)


def cmd_raw(rec, a):
    want = {a.seq}
    line = None
    for o in stream(rec, Filter(seq=(a.seq, a.seq), everything=True), a.j):
        line = o
    if line is not None:
        r = line.get('raw')
        if isinstance(r, list) and len(r) == 2:
            want |= set(range(r[0], r[1] + 1))
        if isinstance(line.get('rawSeq'), int):
            want.add(line['rawSeq'])
        print('line #%d: %s' % (a.seq, compact(line, 300)))
    found = 0
    stats = {'truncated': 0, 'notes': []}
    for part in rec.parts:
        for seq, d, ph, withheld, ln, body in raw_records(part, stats):
            if seq in want:
                found += 1
                print('frame #%d %s %s %d bytes%s' % (seq, 'in' if d == 0 else 'out', PHASES.get(ph, ph), ln, ' (withheld: only its length is kept)' if withheld else ''))
                if body:
                    print(hexdump(body, a.limit))
    for note in stats['notes']:
        warn(note)
    if not found:
        print('no raw frame for seq %d (Raw Packets off, or not a packet seq)' % a.seq)


# ---------------------------------------------------------------------------------------------- context pack

def cmd_context(rec, a):
    m = rec.manifest
    target = a.around
    if a.mark is not None:
        marks = m.get('marks') or []
        if not 1 <= a.mark <= len(marks):
            sys.exit('mark %d does not exist (%d marks)' % (a.mark, len(marks)))
        target = marks[a.mark - 1]['seq']
    out = ['# Dungeon Recorder context pack', '',
           'Format recorder-2 (docs/dungeon-recorder.md). Every line has k (kind), seq (true global order), t (client tick),',
           'n (server ticks seen), ms (wall clock), ns (monotonic since start). Packet lines: p (type), f (fields).', '',
           '## Recording', json.dumps({k: m.get(k) for k in ('label', 'self', 'server', 'mod', 'odin', 'mc', 'complete', 'crashed', 'lines') if k in m}, ensure_ascii=False)]
    budget = a.budget
    if target is not None:
        w, kf_seq = rebuild(rec, a, at_seq=target)
        out += ['', '## State at seq %d (rebuilt from keyframe at seq %s)' % (target, kf_seq)]
        describe_state(w, a, out)
    head = '\n'.join(out)
    remaining = max(0, budget - len(head.encode()) - 600)
    flt = timeline_filter(a)
    lines = []
    if target is None:
        # No moment given: the start of the recording, as far as the budget reaches.
        for o in stream(rec, flt, a.j):
            lines.append(o)
            if len(lines) >= 2 * a.window:
                break
    else:
        before, after = [], []
        for o in stream(rec, flt, a.j):
            if o.get('seq', 0) < target:
                before.append(o)
                if len(before) > a.window:
                    before.pop(0)
            else:
                after.append(o)
                if len(after) > a.window:
                    break
        lines = before + after
    # Nearest the moment first, until the budget is spent; then back into seq order.
    rendered = [(o, compact(o, a.max_chars)) for o in lines]
    if target is not None:
        order = sorted(range(len(rendered)), key=lambda i: abs(rendered[i][0].get('seq', 0) - target))
    else:
        order = list(range(len(rendered)))
    keep, used = set(), 0
    for i in order:
        size = len(rendered[i][1].encode()) + 1
        if used + size > remaining:
            continue
        keep.add(i)
        used += size
    dropped = Counter(rendered[i][0].get('k') if rendered[i][0].get('k') not in PACKET_KINDS else short(rendered[i][0].get('p'))
                      for i in range(len(rendered)) if i not in keep)
    kept = [rendered[i][1] for i in sorted(keep)]
    print(head)
    print('\n## Timeline (%d lines%s)' % (len(kept), (' around seq %d' % target) if target is not None else ''))
    print('\n'.join(kept))
    print('\n## Compaction notes')
    print('- each line cut to %d characters of fields; default exclusions: %s' % (a.max_chars, 'none (--all)' if a.all else
          ', '.join(list(DEFAULT_EXCLUDED_KINDS) + sorted(DEFAULT_EXCLUDED_TYPES))))
    if dropped:
        print('- left out for the budget: ' + ', '.join('%s x%d' % kv for kv in dropped.most_common(30)))
    if m.get('gaps'):
        print('- the recording has gaps: %s' % m['gaps'])


# ---------------------------------------------------------------------------------------------- main

def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0], formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    sub = ap.add_subparsers(dest='cmd', required=True)

    def common(p, recs=True):
        if recs:
            p.add_argument('rec', nargs='+', help='recording directory, manifest.json or part files')
        p.add_argument('-j', type=int, default=None, help='worker processes')
        p.add_argument('--max-chars', type=int, default=400)
        return p

    def timeline_opts(p):
        p.add_argument('--kinds')
        p.add_argument('--types')
        p.add_argument('--skip')
        p.add_argument('--entity', type=int)
        p.add_argument('--seq')
        p.add_argument('--ms')
        p.add_argument('--from', dest='from_', type=int)
        p.add_argument('--to', type=int)
        p.add_argument('--regex')
        p.add_argument('--grep')
        p.add_argument('--no-movement', action='store_true')
        p.add_argument('--all', action='store_true')
        p.add_argument('--window', type=int, default=200)

    p = common(sub.add_parser('summary'))
    p.add_argument('--scan', action='store_true', help='also decompress every member (counts undecodable lines)')
    p = common(sub.add_parser('index'))
    p.add_argument('--top', type=int, default=6)
    p = common(sub.add_parser('entities'))
    p.add_argument('--rebuild', action='store_true', help='from the lines, not entities.jsonl')
    p = common(sub.add_parser('events'))
    p.add_argument('--rebuild', action='store_true', help='from the lines, not events.jsonl')
    p = common(sub.add_parser('schema'))
    p.add_argument('--name')
    p = common(sub.add_parser('example'))
    p.add_argument('--count', type=int, default=1)
    p.add_argument('--compact', action='store_true')
    p = common(sub.add_parser('timeline'))
    timeline_opts(p)
    p.add_argument('--around', type=int)
    p.add_argument('--json', action='store_true')
    p = common(sub.add_parser('state'))
    p.add_argument('--at', type=int)
    p.add_argument('--at-seq', type=int)
    p.add_argument('--at-t', type=int)
    p.add_argument('--box')
    p.add_argument('--with-air', action='store_true')
    p.add_argument('--pos')
    p.add_argument('--room')
    p.add_argument('--map', type=int, nargs='?', const=-1)
    p.add_argument('--entities', action='store_true')
    p = common(sub.add_parser('raw'))
    p.add_argument('--limit', type=int, default=4096)
    p = common(sub.add_parser('context'))
    timeline_opts(p)
    p.add_argument('--budget', type=int, required=True)
    p.add_argument('--mark', type=int)
    p.add_argument('--around', type=int)

    a = ap.parse_args(argv)
    # Trailing positionals that are not paths: example's KIND [N], raw's SEQ, schema's NAME.
    recs = list(a.rec)
    if a.cmd == 'example':
        if len(recs) >= 3 and recs[-1].isdigit() and not os.path.exists(recs[-1]):
            a.count = int(recs.pop())
        if len(recs) < 2:
            sys.exit('example REC KIND|TYPE [N]')
        a.what = recs.pop()
    elif a.cmd == 'raw':
        if len(recs) < 2 or not recs[-1].isdigit():
            sys.exit('raw REC SEQ')
        a.seq = int(recs.pop())
    elif a.cmd == 'schema':
        a.name = a.name or (recs.pop() if len(recs) >= 2 and not os.path.exists(recs[-1]) else None)
    rec = Recording(recs)
    if not rec.parts and a.cmd not in ('schema', 'entities', 'events'):
        sys.exit('no parts found in %s' % ' '.join(recs))
    try:
        {'summary': cmd_summary, 'index': cmd_index, 'entities': cmd_entities, 'events': cmd_events, 'schema': cmd_schema,
         'example': cmd_example, 'timeline': cmd_timeline, 'state': cmd_state, 'raw': cmd_raw, 'context': cmd_context}[a.cmd](rec, a)
    except BrokenPipeError:
        try:
            sys.stdout = open(os.devnull, 'w')
        except OSError:
            pass


if __name__ == '__main__':
    main(sys.argv[1:])
