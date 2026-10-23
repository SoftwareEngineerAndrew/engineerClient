// ADR run file reading, ported from the viewer (public/adr/worker.js): xz/gzip, JSON lines, and the
// upload packer's columns ("cols", "grp", "blocks", "cams") back into ordinary lines.
import { spawn } from 'node:child_process';

const undelta = a => { let s = 0; return a.map(v => (s += v)); };
function decodeCol(c, max) {
  const out = [];
  if (Array.isArray(c)) { const sc = 10 ** c[0]; let q = 0; for (let i = 1; i < c.length && out.length < max; i++) { q += c[i]; out.push(sc === 1 ? q : q / sc); } return out; }
  for (let i = 0; i < c.r.length && out.length < max; i += 2) for (let n = Math.min(c.r[i + 1], max - out.length); n > 0; n--) out.push(c.r[i]);
  return out;
}
function decodeCols(l) {
  if (l.of !== 'e' && l.of !== 'p') return [];
  const ts = undelta(l.t), idx = l.i ? undelta(l.i) : [], ord = l.o ? undelta(l.o) : [];
  const lines = ts.map((t, j) => ({ idx: idx[j], ord: ord[j], line: { k: l.of, t, d: [] } }));
  let id = 0;
  for (let [key, lis, lensC, cols] of l.tr) {
    if (l.kd) key = id += key;
    const at = undelta(lis), max = Math.min(at.length, 1e6);
    const lens = decodeCol(lensC, max), vals = cols.map(c => decodeCol(c, max)), ptr = new Array(cols.length).fill(0);
    at.forEach((li, j) => {
      if (!lines[li]) return;
      const row = [key], n = Math.min(lens[j], cols.length + 1);
      for (let c = 1; c < n; c++) row.push(vals[c - 1][ptr[c - 1]++]);
      lines[li].line.d.push(row);
    });
  }
  return lines;
}
const GRP_KINDS = new Set(['spawn', 'tag', 'stand', 'frame', 'eq', 'name', 'gone', 'st', 'mp', 'sw']);
function decodeGrp(l, out) {
  if (!GRP_KINDS.has(l.of) || !l.c || typeof l.c !== 'object') return;
  const n = Math.min(Math.max(0, l.n | 0), 200000), cols = [];
  for (const [f, c] of Object.entries(l.c)) if (c && typeof c === 'object') cols.push([f, decodeCol(c, n)]);
  for (let j = 0; j < n; j++) {
    const o = { k: l.of };
    for (const [f, v] of cols) if (v[j] != null) o[f] = v[j];
    out(o);
  }
}
function decodeBlocks(l, out) {
  let t = 0, x = 0, y = 0, z = 0;
  for (const b of l.d) { t += b[0]; x += b[1]; y += b[2]; z += b[3]; out({ k: 'block', t, x, y, z, s: b[4] }); }
}

/** A function taking each parsed line and calling [out] with the original lines, in order (cams dropped). */
export function colsReader(out) {
  let pending = [], count = 0, inCols = false;
  const flush = () => { for (const p of pending) out(p.line); pending = []; };
  const feed = l => {
    if (l.k === 'grp' || l.k === 'blocks' || l.k === 'cams' || (l.k === 'cols' && !l.i)) {
      if (l.k === 'cols') for (const p of decodeCols(l)) out(p.line);
      else if (l.k === 'grp') decodeGrp(l, out);
      else if (l.k === 'blocks') decodeBlocks(l, out);
      return;
    }
    if (l.k === 'cols') {
      if (!inCols) { flush(); count = 0; }
      for (const p of decodeCols(l)) pending.push(p);
      pending.sort((a, b) => a.idx - b.idx || a.ord - b.ord);
      inCols = true;
      return;
    }
    if (pending.length) { let n = 0; while (n < pending.length && pending[n].idx <= count) out(pending[n++].line); if (n) pending = pending.slice(n); }
    out(l); count++; inCols = false;
  };
  feed.end = flush;
  return feed;
}

/** Every original line of a run file's bytes (a Buffer), through [out]. */
export async function readRun(buf, out) {
  const xz = buf[0] === 0xfd && buf[1] === 0x37;
  const child = spawn(xz ? 'xz' : 'gzip', ['-dc'], { stdio: ['pipe', 'pipe', 'ignore'] });
  const feed = colsReader(out);
  let rest = '';
  child.stdout.setEncoding('utf8');
  child.stdin.on('error', () => {});
  child.stdin.end(buf);
  for await (const chunk of child.stdout) {
    rest += chunk;
    let i;
    while ((i = rest.indexOf('\n')) >= 0) {
      const line = rest.slice(0, i); rest = rest.slice(i + 1);
      if (!line) continue;
      let l; try { l = JSON.parse(line); } catch { continue; }
      try { feed(l); } catch {}
    }
  }
  if (rest) { try { feed(JSON.parse(rest)); } catch {} }
  feed.end();
}
