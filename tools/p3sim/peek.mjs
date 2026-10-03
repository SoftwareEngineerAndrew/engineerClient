// Prints a box of the built arena, layer by layer: node tools/p3sim/peek.mjs x0 y0 z0 x1 y1 z1
import fs from "fs";
import zlib from "zlib";
import path from "path";
import { fileURLToPath } from "url";

const here = path.dirname(fileURLToPath(import.meta.url));
const buf = zlib.gunzipSync(fs.readFileSync(path.join(here, "../../src/main/resources/assets/engineerclient/p3sim/arena.bin")));
let o = 4;
const int = () => { const v = buf.readInt32BE(o); o += 4; return v; };
const [X0, Y0, Z0, W, H, D, n] = [int(), int(), int(), int(), int(), int(), int()];
const pal = [];
for (let i = 0; i < n; i++) { const l = buf.readUInt16BE(o); o += 2; pal.push(buf.toString("utf8", o, o + l)); o += l; }
const cells = new Uint16Array(W * H * D);
const varint = () => { let v = 0, s = 0; for (;;) { const b = buf[o++]; v |= (b & 0x7f) << s; if (!(b & 0x80)) return v >>> 0; s += 7; } };
for (let i = 0; i < cells.length;) { const c = varint(), v = varint(); cells.fill(v, i, i + c); i += c; }
const get = (x, y, z) => (x < X0 || y < Y0 || z < Z0 || x >= X0 + W || y >= Y0 + H || z >= Z0 + D) ? "air" : pal[cells[((y - Y0) * D + (z - Z0)) * W + (x - X0)]].replace("minecraft:", "");
const [x0, y0, z0, x1, y1, z1] = process.argv.slice(2).map(Number);
const legend = new Map(); const sym = s => { if (s === "air") return "."; if (!legend.has(s)) legend.set(s, String.fromCharCode(65 + legend.size)); return legend.get(s); };
for (let y = y1; y >= y0; y--) {
  console.log(`y=${y}  (rows z ${z0}..${z1}, cols x ${x0}..${x1})`);
  for (let z = z0; z <= z1; z++) { let row = ""; for (let x = x0; x <= x1; x++) row += sym(get(x, y, z)); console.log(`  z${String(z).padStart(4)} ${row}`); }
}
for (const [s, c] of legend) console.log(c, s);
