// Is the generator the same in every direction of the map itself (not the entrance's)? Code that scans the
// grid (rows, columns) or anchors rooms at a corner leaves marks in absolute coordinates; code that only
// cares about the entrance doesn't.
//   LAYOUTS=layouts.jsonl node asym.mjs
import { complete, SYM } from './common.mjs';
const W = 6, N = 36;
const Ls = complete();
const pct = (a, b) => (a / Math.max(b, 1) * 100).toFixed(1);
const grid = (f, fmt = v => v.toFixed(2)) => { for (let z = 0; z < W; z++) console.log('   ' + [0, 1, 2, 3, 4, 5].map(x => fmt(f(z * W + x)).padStart(7)).join('')); };

// 1. orientations: a shape's ways round, absolute
const orient = {};
for (const L of Ls) for (const r of L.rooms) {
  if (r.shape === 'OneByOne') continue;
  const xs = r.tiles.map(t => t.x), zs = r.tiles.map(t => t.z); const x0 = Math.min(...xs), z0 = Math.min(...zs);
  let k;
  if (r.shape === 'L') { const has = (x, z) => r.tiles.some(t => t.x === x0 + x && t.z === z0 + z); k = 'missing ' + (!has(0, 0) ? 'NW' : !has(1, 0) ? 'NE' : !has(0, 1) ? 'SW' : 'SE'); }
  else if (r.shape === 'TwoByTwo') k = 'square';
  else k = Math.max(...xs) > x0 ? 'east-west' : 'north-south';
  (orient[r.shape] ||= {})[k] = (orient[r.shape][k] || 0) + 1;
}
console.log(`${Ls.length} maps\n1. shapes' ways round (absolute):`);
for (const [s, o] of Object.entries(orient)) console.log(`   ${s.padEnd(11)} ` + Object.entries(o).sort().map(([k, v]) => `${k} ${v}`).join(', '));

// 2. where the entrance is (absolute)
const ent = new Float64Array(N);
for (const L of Ls) { const E = L.rooms.find(r => r.type === 'ENTRANCE'); ent[E.tiles[0].z * W + E.tiles[0].x]++; }
console.log('\n2. entrance cell (% of maps):'); grid(t => ent[t] / Ls.length * 100, v => v ? v.toFixed(1) : '.');

// 3. the maps turned so the entrance is on the top row, left half: does what's left depend on how they were
// turned? Maps that needed a mirror (odd symmetries 4-7) against those that didn't; if the game doesn't care
// about absolute directions the two groups look alike
const grp = [{ n: 0, big: new Float64Array(N), anc: new Float64Array(N), de: new Float64Array(N) }, { n: 0, big: new Float64Array(N), anc: new Float64Array(N), de: new Float64Array(N) }];
const byRot = Array.from({ length: 8 }, () => ({ n: 0, big: new Float64Array(N) }));
for (const L of Ls) {
  const E = L.rooms.find(r => r.type === 'ENTRANCE'); const ex = E.tiles[0].x, ez = E.tiles[0].z;
  let gi = -1; for (let g = 0; g < 8; g++) { const [x, z] = SYM[g](ex, ez); if (z === 0 && x <= 2) { gi = g; break; } }
  const f = SYM[gi]; const G = grp[gi >= 4 ? 1 : 0]; G.n++; byRot[gi].n++;
  for (const r of L.rooms) for (const t of r.tiles) {
    const [x, z] = f(t.x, t.z); const c = z * W + x;
    if (r.shape !== 'OneByOne') { G.big[c]++; byRot[gi].big[c]++; }
    else if (r.type !== 'ENTRANCE' && r.type !== 'BLOOD' && L.g.get(r.i).length === 1) G.de[c]++;
  }
}
for (const [lab, key] of [['big-room tiles', 'big'], ['1x1 dead ends', 'de']]) {
  console.log(`\n3. ${lab}, entrance frame: share of maps, unmirrored (${grp[0].n}) - mirrored (${grp[1].n}), as z-scores`);
  let chi = 0;
  grid(t => { const a = grp[0][key][t] / grp[0].n, b = grp[1][key][t] / grp[1].n; const p = (grp[0][key][t] + grp[1][key][t]) / (grp[0].n + grp[1].n);
    const se = Math.sqrt(p * (1 - p) * (1 / grp[0].n + 1 / grp[1].n)) || 1; const zv = (a - b) / se; chi += zv * zv; return zv; }, v => v.toFixed(1));
  console.log(`   sum of z^2 ${chi.toFixed(1)} over 36 cells (about 36 if it doesn't matter; the entrance cell's 0)`);
}
// 4. the same by each of the 8 turns: big tiles in the frame's left/right and top/bottom halves
console.log('\n4. by turn (how the map was turned to the frame): maps, big tiles per map in the frame\'s columns 0-2 | 3-5, rows 0-2 | 3-5');
for (let g = 0; g < 8; g++) { const B = byRot[g]; if (!B.n) continue; let l = 0, r = 0, u = 0, d = 0;
  for (let t = 0; t < N; t++) { const x = t % W, z = (t / W) | 0; if (x <= 2) l += B.big[t]; else r += B.big[t]; if (z <= 2) u += B.big[t]; else d += B.big[t]; }
  console.log(`   turn ${g}  ${String(B.n).padStart(5)}   ${(l / B.n).toFixed(2)} | ${(r / B.n).toFixed(2)}    ${(u / B.n).toFixed(2)} | ${(d / B.n).toFixed(2)}`); }

// 5. absolute: big tiles per cell, and the cell against its mirror images (all maps)
const big = new Float64Array(N); for (const L of Ls) for (const r of L.rooms) if (r.shape !== 'OneByOne') for (const t of r.tiles) big[t.z * W + t.x]++;
console.log('\n5. big-room tiles by absolute cell (share of maps):'); grid(t => big[t] / Ls.length);
const half = (sel) => { let a = 0; for (let t = 0; t < N; t++) if (sel(t % W, (t / W) | 0)) a += big[t]; return a / Ls.length; };
console.log(`   west half ${half(x => x <= 2).toFixed(3)}  east ${half(x => x >= 3).toFixed(3)}   north ${half((x, z) => z <= 2).toFixed(3)}  south ${half((x, z) => z >= 3).toFixed(3)}`);
