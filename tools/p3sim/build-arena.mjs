// Builds the P3 Sim arena resource from the Better PF room library's F7 boss columns.
//
//   node tools/p3sim/build-arena.mjs
//
// Input: tools/p3sim/cache/Boss_F7_<cx>_<cz>.json (the library's `Boss|F7|cx,cz` volumes, fetched
// from /betterpf/api/room), plus tools/p3sim/arena-fixes.json: the blocks the library caught
// mid-run, put back to how they are when the boss starts ({"x,y,z": "state"}).
// Output: src/main/resources/assets/engineerclient/p3sim/arena.bin, gzipped:
//   "P3A1", int x0, y0, z0, w, h, d, int palette size, palette (u16 length + utf8, index 0 = air),
//   then run-length pairs (varint count, varint index) over w*h*d cells, y then z then x (x fastest).
import fs from "fs";
import zlib from "zlib";
import path from "path";
import { fileURLToPath } from "url";

const here = path.dirname(fileURLToPath(import.meta.url));
const cacheDir = path.join(here, "cache");
const out = path.join(here, "../../src/main/resources/assets/engineerclient/p3sim/arena.bin");

const cols = fs.readdirSync(cacheDir).filter(f => /^Boss_F7_.*\.json$/.test(f)).map(f => JSON.parse(fs.readFileSync(path.join(cacheDir, f))));
let x0 = Infinity, z0 = Infinity, x1 = -Infinity, z1 = -Infinity;
for (const c of cols) { x0 = Math.min(x0, c.x0); z0 = Math.min(z0, c.z0); x1 = Math.max(x1, c.x0 + c.w); z1 = Math.max(z1, c.z0 + c.d); }
const y0 = 0, h = 256, w = x1 - x0, d = z1 - z0;

const pal = ["minecraft:air"], palIdx = new Map([["minecraft:air", 0], ["minecraft:void_air", 0], ["minecraft:cave_air", 0]]);
const id = s => { let i = palIdx.get(s); if (i === undefined) { i = pal.length; pal.push(s); palIdx.set(s, i); } return i; };
const cells = new Uint16Array(w * h * d);
const at = (x, y, z) => ((y - y0) * d + (z - z0)) * w + (x - x0);

for (const c of cols) {
  const local = c.pal.map(s => (s === "" ? -1 : id(s)));
  let i = 0;
  for (let k = 0; k < c.rle.length; k += 2) {
    const n = c.rle[k], v = local[c.rle[k + 1]];
    for (let q = 0; q < n; q++, i++) {
      if (v < 0) continue;
      const x = c.x0 + (i % c.w), z = c.z0 + (Math.floor(i / c.w) % c.d), y = c.y0 + Math.floor(i / (c.w * c.d));
      cells[at(x, y, z)] = v;
    }
  }
}

const fixesPath = path.join(here, "arena-fixes.json");
let fixed = 0;
if (fs.existsSync(fixesPath)) {
  for (const [k, s] of Object.entries(JSON.parse(fs.readFileSync(fixesPath)))) {
    if (k.startsWith("_")) continue;
    const [x, y, z] = k.split(",").map(Number);
    if (x < x0 || x >= x1 || z < z0 || z >= z1 || y < 0 || y >= h) continue;
    cells[at(x, y, z)] = id(s); fixed++;
  }
}

const parts = [];
const header = Buffer.alloc(4 + 7 * 4);
header.write("P3A1", 0);
[x0, y0, z0, w, h, d, pal.length].forEach((v, i) => header.writeInt32BE(v, 4 + i * 4));
parts.push(header);
for (const s of pal) { const b = Buffer.from(s, "utf8"); const l = Buffer.alloc(2); l.writeUInt16BE(b.length); parts.push(l, b); }
const bytes = [];
const varint = v => { while (v >= 0x80) { bytes.push((v & 0x7f) | 0x80); v >>>= 7; } bytes.push(v); };
for (let i = 0; i < cells.length;) {
  let j = i + 1;
  while (j < cells.length && cells[j] === cells[i]) j++;
  varint(j - i); varint(cells[i]); i = j;
}
parts.push(Buffer.from(bytes));
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, zlib.gzipSync(Buffer.concat(parts), { level: 9 }));
console.log(`arena ${w}x${h}x${d} at ${x0},${y0},${z0}: ${pal.length} states, ${fixed} fixes, ${fs.statSync(out).size} bytes`);
