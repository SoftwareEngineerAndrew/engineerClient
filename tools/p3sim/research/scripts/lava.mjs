// Lava cells of the built arena: per-y summary to stdout, all cells to OUT (default /tmp/lava.json).
// node tools/p3sim/research/scripts/lava.mjs [OUT]
import fs from "fs";
import zlib from "zlib";
import path from "path";
import { fileURLToPath } from "url";

const here = path.dirname(fileURLToPath(import.meta.url));
const buf = zlib.gunzipSync(fs.readFileSync(path.join(here, "../../../../src/main/resources/assets/engineerclient/p3sim/arena.bin")));
let o = 4;
const int = () => { const v = buf.readInt32BE(o); o += 4; return v; };
const [X0, Y0, Z0, W, H, D, n] = [int(), int(), int(), int(), int(), int(), int()];
const pal = [];
for (let i = 0; i < n; i++) { const l = buf.readUInt16BE(o); o += 2; pal.push(buf.toString("utf8", o, o + l)); o += l; }
const cells = new Uint16Array(W * H * D);
const varint = () => { let v = 0, s = 0; for (;;) { const b = buf[o++]; v |= (b & 0x7f) << s; if (!(b & 0x80)) return v >>> 0; s += 7; } };
for (let i = 0; i < cells.length;) { const c = varint(), v = varint(); cells.fill(v, i, i + c); i += c; }
const isL = pal.map(p => /lava/.test(p));
const by = {}, pts = [];
for (let y = 0; y < H; y++) for (let z = 0; z < D; z++) for (let x = 0; x < W; x++) {
  const c = cells[(y * D + z) * W + x];
  if (!isL[c]) continue;
  const Y = y + Y0, b = (by[Y] ??= { n: 0, src: 0, x0: 1e9, x1: -1e9, z0: 1e9, z1: -1e9 });
  b.n++; if (pal[c].includes("level=0")) b.src++;
  b.x0 = Math.min(b.x0, x + X0); b.x1 = Math.max(b.x1, x + X0); b.z0 = Math.min(b.z0, z + Z0); b.z1 = Math.max(b.z1, z + Z0);
  pts.push([x + X0, Y, z + Z0]);
}
for (const y in by) console.log(y, JSON.stringify(by[y]));
fs.writeFileSync(process.argv[2] ?? "/tmp/lava.json", JSON.stringify(pts));
