// ADR run files as their original lines, for runs uploaded in the current format: what
// ../secret-spawns/decode.mjs reads, plus
//  - uploads packed after the shared dictionary ("ADRd" + version: UploadPacker.DictCut), put back
//    together with dict-<v>.bin from the site and its text skipped;
//  - "grp" lines of every grouped kind (rooms, room, tp, pgone, use, held, hotbar, ether, slotclick
//    as well as the mob kinds);
//  - tabled values (eq, headTex, c, pose, type, item, itemId on eq/spawn/name/stand lines): a
//    negative number is the site's catalogue entry -(n + 1), a non-negative one the n-th value this
//    run first wrote whole (UploadPacker.Table);
//  - "flat" cols lines (mob "e" and player "p" moves): as {k: "eflat"/"pflat", t, key, x, y, z}.
// The catalogue files (dict-1.bin, values-3.json.xz) are fetched into the current folder once.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const SITE = 'https://undonecoffee.com/adr/cat/';
const DICT = 1, VALUES = 3;

export async function loadCatalogue() {
  for (const f of [`dict-${DICT}.bin`, `values-${VALUES}.json.xz`]) {
    if (existsSync(f)) continue;
    const res = await fetch(SITE + f);
    if (!res.ok) throw new Error(`${f}: HTTP ${res.status}`);
    writeFileSync(f, Buffer.from(await res.arrayBuffer()));
  }
  const d = readFileSync(`dict-${DICT}.bin`);
  if (d.subarray(0, 4).toString('latin1') !== 'ADRD') throw new Error('not a dictionary');
  const values = JSON.parse(execFileSync('xz', ['-dc'], { input: readFileSync(`values-${VALUES}.json.xz`), maxBuffer: 1 << 28 }).toString('utf8'));
  return { dictLen: d.readUInt32BE(4), dictPacked: d.subarray(8), values: values.fields };
}

const undelta = a => { let s = 0; return a.map(v => (s += v)); };
function decodeCol(c, max) {
  const out = [];
  if (Array.isArray(c)) { const sc = 10 ** c[0]; let q = 0; for (let i = 1; i < c.length && out.length < max; i++) { q += c[i]; out.push(sc === 1 ? q : q / sc); } return out; }
  for (let i = 0; i < c.r.length && out.length < max; i += 2) for (let n = Math.min(c.r[i + 1], max - out.length); n > 0; n--) out.push(c.r[i]);
  return out;
}
function decodeGrp(l, out) {
  if (!l.c || typeof l.c !== 'object') return;
  const n = Math.min(Math.max(0, l.n | 0), 200000), cols = [];
  for (const [f, c] of Object.entries(l.c)) if (c && typeof c === 'object') cols.push([f, decodeCol(c, n)]);
  for (let j = 0; j < n; j++) {
    const o = { k: l.of, grouped: true };
    for (const [f, v] of cols) if (v[j] != null) o[f] = v[j];
    out(o);
  }
}
function decodeBlocks(l, out) {
  let t = 0, x = 0, y = 0, z = 0;
  for (const b of l.d) { t += b[0]; x += b[1]; y += b[2]; z += b[3]; out({ k: 'block', t, x, y, z, s: b[4] }); }
}
/** A "flat" cols line (UploadPacker.encode): x, y, z of every row, keyed by mob id or player name. */
function decodeFlat(l, out) {
  const ts = undelta(l.t);
  const cols = l.cols.slice(0, 3).map(c => (Array.isArray(c) ? c : null));
  const ptr = [1, 1, 1];
  let row = 0, id = 0;
  for (let k = 0; k < l.keys.length; k++) {
    const key = l.kd ? (id += l.keys[k]) : l.keys[k];
    const q = [0, 0, 0], started = [false, false, false];
    let at = 0;
    for (let j = 0; j < l.n[k]; j++, row++) {
      at = j === 0 ? l.li[row] : at + l.li[row];
      const v = [];
      for (let c = 0; c < 3; c++) {
        const col = cols[c];
        if (l.sz[row] <= c + 1 || !col || ptr[c] >= col.length) { v.push(null); continue; }
        const d = col[ptr[c]++]; q[c] = started[c] ? q[c] + d : d; started[c] = true;
        v.push(q[c] / 10 ** col[0]);
      }
      out({ k: l.of + 'flat', t: ts[at], key, x: v[0], y: v[1], z: v[2] });
    }
  }
}

const TABLED_KINDS = new Set(['eq', 'spawn', 'name', 'stand']);
const TABLED_FIELDS = ['eq', 'headTex', 'c', 'pose', 'type', 'item', 'itemId'];

/** Every line of the run file [file], through [out]. [cat] from loadCatalogue(). */
export function readRun(file, cat, out) {
  let buf = readFileSync(file), skip = 0;
  if (buf.subarray(0, 4).toString('latin1') === 'ADRd') {
    if (buf[4] !== DICT) throw new Error(`packed after dictionary ${buf[4]}`);
    const b = buf.subarray(5), head = 12 + (b[12] + 1) * 4; // stream header, then the block's
    buf = Buffer.concat([b.subarray(0, head), cat.dictPacked, b.subarray(head)]);
    skip = cat.dictLen;
  }
  const xz = buf[0] === 0xfd && buf[1] === 0x37;
  const text = execFileSync(xz ? 'xz' : 'gzip', ['-dc'], { input: buf, maxBuffer: 1 << 30 }).subarray(skip).toString('utf8');
  const tables = Object.fromEntries(TABLED_FIELDS.map(f => [f, []]));
  const emit = l => {
    if (l.grouped && TABLED_KINDS.has(l.k)) for (const f of TABLED_FIELDS) {
      const v = l[f];
      if (v === undefined || v === null || v === '') continue;
      if (Number.isInteger(v)) l[f] = v < 0 ? cat.values[f]?.[-1 - v] : tables[f][v];
      else tables[f].push(v);
    }
    out(l);
  };
  for (const line of text.split('\n')) {
    if (!line) continue;
    let l; try { l = JSON.parse(line); } catch { continue; }
    try {
      if (l.k === 'grp') decodeGrp(l, emit);
      else if (l.k === 'blocks') decodeBlocks(l, emit);
      else if (l.k === 'cols' && l.flat) decodeFlat(l, emit);
      else if (l.k === 'cols' || l.k === 'cams') continue; // older non-flat columns: not needed here
      else emit(l);
    } catch {}
  }
}
