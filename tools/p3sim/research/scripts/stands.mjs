// Checks the sim's stand spots against the built arena: a spot is good when the player's box (0.6
// wide) at that y rests on some block's top and the cells for feet and head are free. For a bad one,
// prints the nearest good spot (same x/z if a floor is right there, else the nearest block centre).
// node tools/p3sim/research/scripts/stands.mjs
import fs from "fs";
import zlib from "zlib";
import path from "path";
import { fileURLToPath } from "url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, "../../../..");
const buf = zlib.gunzipSync(fs.readFileSync(path.join(root, "src/main/resources/assets/engineerclient/p3sim/arena.bin")));
let o = 4;
const int = () => { const v = buf.readInt32BE(o); o += 4; return v; };
const [X0, Y0, Z0, W, H, D, n] = [int(), int(), int(), int(), int(), int(), int()];
const pal = [];
for (let i = 0; i < n; i++) { const l = buf.readUInt16BE(o); o += 2; pal.push(buf.toString("utf8", o, o + l)); o += l; }
const cells = new Uint16Array(W * H * D);
const varint = () => { let v = 0, s = 0; for (;;) { const b = buf[o++]; v |= (b & 0x7f) << s; if (!(b & 0x80)) return v >>> 0; s += 7; } };
for (let i = 0; i < cells.length;) { const c = varint(), v = varint(); cells.fill(v, i, i + c); i += c; }
const at = (x, y, z) => {
  x -= X0; y -= Y0; z -= Z0;
  if (x < 0 || y < 0 || z < 0 || x >= W || y >= H || z >= D) return "minecraft:air";
  return pal[cells[(y * D + z) * W + x]].replace("minecraft:", "");
};
const NOCOLL = /^(air|cave_air|void_air|lava|water|.*button|lever|.*sign|.*torch|.*rail|.*banner|.*pressure_plate|tripwire.*|redstone_wire|.*_head|.*skull|ladder|vine|short_grass|tall_grass|.*flower.*|poppy|dandelion|fern|light)(\[|$)/;
/** Tops (within the block) a player can stand at. */
const tops = s => {
  if (NOCOLL.test(s)) return [];
  if (/carpet/.test(s)) return [0.0625];
  if (/_slab/.test(s)) return /type=bottom/.test(s) ? [0.5] : [1.0];
  if (/stairs/.test(s)) return /half=bottom/.test(s) ? [0.5, 1.0] : [1.0];
  if (/(snow\[layers=(\d))/.test(s)) return [(+s.match(/layers=(\d)/)[1] - 1) / 8];
  return [1.0];
};
const free = s => NOCOLL.test(s) || /carpet/.test(s);
const R = 0.3;
const cellsUnder = (x, z) => {
  const out = [];
  for (let bx = Math.floor(x - R); bx <= Math.floor(x + R - 1e-6); bx++)
    for (let bz = Math.floor(z - R); bz <= Math.floor(z + R - 1e-6); bz++) out.push([bx, bz]);
  return out;
};
function good(x, y, z) {
  const cs = cellsUnder(x, z);
  const fb = Math.floor(y + 1e-6);
  // resting: a block under the box with a top exactly at y (in this cell or the one below)
  let rests = false;
  for (const [bx, bz] of cs) {
    for (const by of [fb, fb - 1]) for (const t of tops(at(bx, by, bz))) if (Math.abs(by + t - y) < 0.01) rests = true;
  }
  if (!rests) return false;
  // body free: cells from just above y to y+1.8 (except the block it rests on when partial)
  for (const [bx, bz] of cs) for (let by = Math.floor(y + 0.01); by <= Math.floor(y + 1.79); by++) {
    const s = at(bx, by, bz);
    if (free(s)) continue;
    if (tops(s).some(t => by + t <= y + 0.01)) continue;
    return false;
  }
  return true;
}
function nearest(x, y, z) {
  let best = null;
  const consider = (cx, cy, cz) => {
    if (!good(cx, cy, cz)) return;
    const d = Math.hypot(cx - x, (cy - y) * 1.5, cz - z);
    if (!best || d < best.d) best = { x: cx, y: cy, z: cz, d };
  };
  for (let by = Math.floor(y) - 4; by <= Math.floor(y) + 3; by++) for (const t of [0, 0.0625, 0.5])
    consider(x, by + t, z);
  if (best && best.d < 3.5) return best;
  for (let bx = Math.floor(x) - 3; bx <= Math.floor(x) + 3; bx++) for (let bz = Math.floor(z) - 3; bz <= Math.floor(z) + 3; bz++)
    for (let by = Math.floor(y) - 4; by <= Math.floor(y) + 3; by++) for (const t of [0, 0.0625, 0.5]) consider(bx + 0.5, by + t, bz + 0.5);
  return best;
}

const files = ["Party.kt", "P3Plan.kt", "Spots.kt"];
const re = /(?:"([^"]+)" to Vec3|EarlyEnter\("([^"]+)"[^V]*Vec3|val (\w+) = Spot\("[^"]*", )\(?([-\d.]+), ([-\d.]+), ([-\d.]+)/g;
for (const f of files) {
  const src = fs.readFileSync(path.join(root, "src/main/kotlin/com/engineerclient/p3sim", f), "utf8");
  let m;
  while ((m = re.exec(src))) {
    const id = m[1] ?? m[2] ?? m[3]; const x = +m[4], y = +m[5], z = +m[6];
    if (good(x, y, z)) { console.log(`ok  ${f.padEnd(10)} ${id.padEnd(16)} ${x}, ${y}, ${z}`); continue; }
    const b = nearest(x, y, z);
    console.log(`BAD ${f.padEnd(10)} ${id.padEnd(16)} ${x}, ${y}, ${z}  -> ${b ? `${b.x}, ${b.y}, ${b.z}  (${b.d.toFixed(2)})` : "none found"}  under: ${at(Math.floor(x), Math.floor(y - 0.01), Math.floor(z))}`);
  }
}
