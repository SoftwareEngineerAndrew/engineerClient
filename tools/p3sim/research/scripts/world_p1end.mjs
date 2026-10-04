// Patches anims-p124.json "p1end" with the crystal platforms' random crumble (research/world.md §2).
//   node tools/p3sim/research/scripts/world_p1end.mjs <runs-dir>
// <runs-dir>: per-run block changes extracted by /home/cam/backups/2026-10-03/analysis/world/extract.mjs
// ({marks:{P2}, ch:[[t,x,y,z,state]]}). For every library-solid block of the two platforms (x46-64 /
// x82-100, y213-226, z33-51) it measures the share of runs in which it turns to air within 40 ticks
// before Storm's first line. Shares of 95% or more are certain (p omitted); 10-95% get a 6th frame
// element p (the chance to go), adding a dt -24 frame where the representative run kept it; under
// 10% is noise (ghost blocks) and is left alone. Also adds the three centre-floor blocks
// (73,220,47-49) that go at -15 in 99-100% of runs.
import fs from 'fs'; import path from 'path'; import zlib from 'zlib';
const here = path.dirname(new URL(import.meta.url).pathname);
const res = path.join(here, '../../../../src/main/resources/assets/engineerclient/p3sim/');
const runsDir = process.argv[2] || '/home/cam/backups/2026-10-03/analysis/world/runs/';

// arena.bin lookup
const buf = zlib.gunzipSync(fs.readFileSync(res + 'arena.bin'));
let o = 4; const int = () => { const v = buf.readInt32BE(o); o += 4; return v; };
const [X0, Y0, Z0, W, H, D, n] = [int(), int(), int(), int(), int(), int(), int()];
const pal = []; for (let i = 0; i < n; i++) { const l = buf.readUInt16BE(o); o += 2; pal.push(buf.toString('utf8', o, o + l)); o += l; }
const cells = new Uint16Array(W * H * D);
const varint = () => { let v = 0, s = 0; for (;;) { const b = buf[o++]; v |= (b & 0x7f) << s; if (!(b & 0x80)) return v >>> 0; s += 7; } };
for (let i = 0; i < cells.length;) { const c = varint(), v = varint(); cells.fill(v, i, i + c); i += c; }
const lib = (x, y, z) => pal[cells[((y - Y0) * D + (z - Z0)) * W + (x - X0)]];
const solid = (x, y, z) => !/air$/.test(lib(x, y, z));

const inPlat = (x, y, z) => y >= 213 && y <= 226 && z >= 33 && z <= 51 && ((x >= 46 && x <= 64) || (x >= 82 && x <= 100));
const removed = new Map(); let runs = 0;
for (const f of fs.readdirSync(runsDir).filter(f => f.endsWith('.json'))) {
  const r = JSON.parse(fs.readFileSync(path.join(runsDir, f))); const P2 = r.marks.P2; if (P2 == null) continue;
  const seen = new Set();
  for (const e of r.ch) {
    if (e[0] < P2 - 40 || e[0] > P2 + 5 || !inPlat(e[1], e[2], e[3]) || e[4] !== 'minecraft:air' || !solid(e[1], e[2], e[3])) continue;
    const k = e[1] + ',' + e[2] + ',' + e[3]; if (seen.has(k)) continue; seen.add(k);
    removed.set(k, (removed.get(k) || 0) + 1);
  }
  runs++;
}
const chance = new Map([...removed].map(([k, c]) => [k, c / runs]).filter(([k, p]) => p >= 0.1));

const file = res + 'anims-p124.json';
const lines = fs.readFileSync(file, 'utf8').split('\n');
const start = lines.findIndex(l => l.startsWith('  "p1end": {'));
const fr = lines.findIndex((l, i) => i > start && l.trim() === '"frames": [');
let end = fr + 1; while (!lines[end].trim().startsWith(']')) end++;
const frames = lines.slice(fr + 1, end).map(l => JSON.parse(l.trim().replace(/,$/, '')));
const inAnim = new Set();
let withP = 0;
for (const f of frames) {
  const k = f[1] + ',' + f[2] + ',' + f[3];
  f.length = 5;
  if (!inPlat(f[1], f[2], f[3]) || f[4] !== 'minecraft:air' || !solid(f[1], f[2], f[3])) continue;
  inAnim.add(k);
  const p = chance.get(k);
  if (p != null && p < 0.95) { f.push(+p.toFixed(2)); withP++; }
}
let added = 0;
for (const [k, p] of chance) {
  if (inAnim.has(k)) continue;
  const [x, y, z] = k.split(',').map(Number);
  frames.push(p < 0.95 ? [-24, x, y, z, 'minecraft:air', +p.toFixed(2)] : [-24, x, y, z, 'minecraft:air']); added++;
}
for (const z of [47, 48, 49]) if (!frames.some(f => f[1] === 73 && f[2] === 220 && f[3] === z && f[4] === 'minecraft:air')) { frames.push([-15, 73, 220, z, 'minecraft:air']); added++; }
frames.sort((a, b) => a[0] - b[0]); // stable: file order kept within a dt
const out = frames.map((f, i) => '      ' + JSON.stringify(f) + (i < frames.length - 1 ? ',' : ''));
lines.splice(fr + 1, end - fr - 1, ...out);
fs.writeFileSync(file, lines.join('\n'));
console.log(`runs ${runs}: ${chance.size} platform blocks go in >=10% of runs; ${withP} frames got a chance, ${added} frames added`);
