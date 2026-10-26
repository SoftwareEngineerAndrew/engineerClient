// Straight big rooms (1x2, 1x3, 1x4) lying side by side in the same orientation, in neighbouring rows (or
// columns): how far one is shifted along the other. Real maps against the model's placement - one random spot
// per room, again on a clash (every complete layout of the mix alike), the entrance where the real map has it.
//   LAYOUTS=layouts.jsonl node sidebyside.mjs [draws per map]
import { complete } from './common.mjs';
import { makeRng } from './mlp.mjs';
const W = 6, N = 36;
const rnd = makeRng(+(process.env.SEED || 3));
const LEN = { TwoByOne: 2, ThreeByOne: 3, FourByOne: 4 };
const ORIENT = {
  TwoByOne: [[[0, 0], [1, 0]], [[0, 0], [0, 1]]], ThreeByOne: [[[0, 0], [1, 0], [2, 0]], [[0, 0], [0, 1], [0, 2]]],
  FourByOne: [[[0, 0], [1, 0], [2, 0], [3, 0]], [[0, 0], [0, 1], [0, 2], [0, 3]]], TwoByTwo: [[[0, 0], [1, 0], [0, 1], [1, 1]]],
  L: [[[0, 0], [1, 0], [0, 1]], [[0, 0], [1, 0], [1, 1]], [[0, 0], [0, 1], [1, 1]]],
};
const PL = {}; for (const [s, os] of Object.entries(ORIENT)) { PL[s] = []; for (const o of os) for (let a = 0; a < N; a++) { const cs = []; for (const [dx, dz] of o) { const x = a % W + dx, z = ((a / W) | 0) + dz; if (x < W && z < W) cs.push(z * W + x); } if (cs.length === o.length) PL[s].push(cs); } }
/** a straight room as { len, along (true = east-west), line (its row or column), from (first cell along it) } */
function straight(shape, cells) {
  if (!LEN[shape]) return null; const xs = cells.map(c => c % W), zs = cells.map(c => (c / W) | 0);
  const ew = new Set(zs).size === 1;
  return { shape, len: LEN[shape], ew, line: ew ? zs[0] : xs[0], from: ew ? Math.min(...xs) : Math.min(...zs) };
}
/** each side-by-side pair: the two lengths and how they sit: the cells they share a side along, the shift of their starts */
function pairs(rooms) {
  const st = rooms.map(r => straight(r.shape, r.cells)).filter(Boolean), out = [];
  for (let i = 0; i < st.length; i++) for (let j = i + 1; j < st.length; j++) {
    const a = st[i], b = st[j]; if (a.ew !== b.ew || Math.abs(a.line - b.line) !== 1) continue;
    const lo = Math.max(a.from, b.from), hi = Math.min(a.from + a.len, b.from + b.len); const shared = hi - lo; if (shared < 1) continue;
    const [s, l] = a.len <= b.len ? [a, b] : [b, a];
    // inside: the shorter one's whole length along the longer one; flush: also sharing an end with it
    const inside = shared === s.len, flush = inside && (s.from === l.from || s.from + s.len === l.from + l.len);
    out.push({ key: `${s.len}+${l.len}`, shared, inside, flush, shift: Math.abs(a.from - b.from) });
  }
  return out;
}
const tally = () => ({ n: 0, inside: 0, flush: 0, over: 0, byShift: {} });
const add = (T, ps) => { for (const p of ps) { const t = T[p.key] ||= tally(); t.n++; if (p.flush) t.flush++; if (p.inside) t.inside++; else t.over++; t.byShift[p.shift] = (t.byShift[p.shift] || 0) + 1; } };
const real = {}, model = {}; let nReal = 0, nModel = 0;
const maps = complete().filter(L => L.rooms.filter(r => r.shape !== 'OneByOne').length >= 6);
const D = +(process.argv[2] || 20);
for (const L of maps) {
  const E = L.rooms.find(r => r.type === 'ENTRANCE'); const ent = E.tiles[0].z * W + E.tiles[0].x;
  const big = L.rooms.filter(r => r.shape !== 'OneByOne').map(r => ({ shape: r.shape, cells: r.tiles.map(t => t.z * W + t.x) }));
  add(real, pairs(big)); nReal++;
  for (let d = 0; d < D; d++) for (let tr = 0; tr < 100000; tr++) {
    const used = new Uint8Array(N); used[ent] = 1; const out = []; let ok = true;
    for (const r of big) { const cs = PL[r.shape][Math.floor(rnd() * PL[r.shape].length)]; if (cs.some(c => used[c])) { ok = false; break; } for (const c of cs) used[c] = 1; out.push({ shape: r.shape, cells: cs }); }
    if (ok) { add(model, pairs(out)); nModel++; break; }
  }
}
// GAME=n: also the game-process predictor's own maps (sampler3, only the entrance known, n samples a map, weighted)
const game = {}; let nGame = 0;
if (process.env.GAME) {
  const { observe } = await import('./sim.mjs'); const { sample, mixesFor } = await import('./sampler3.mjs');
  const { readFileSync } = await import('node:fs'); const zmix = JSON.parse(readFileSync(process.env.ZMIX || 'zmix.json', 'utf8'));
  const combos = mixesFor(complete());
  for (const L of maps.slice(0, +(process.env.GMAPS || 300))) {
    const E = L.rooms.find(r => r.type === 'ENTRANCE'); const got = [];
    sample(observe(L, new Set([E.i])), combos, { rnd, samples: +process.env.GAME, zmix, onSample: x => got.push(x) });
    const tot = got.reduce((a, x) => a + x.weight, 0); if (!tot) continue;
    for (const x of got) { const ps = pairs(x.rooms.filter(r => r.big).map(r => ({ shape: ({ 2: 'TwoByOne', 3: r.cells.length === 3 && new Set(r.cells.map(c => c % W)).size !== 2 && new Set(r.cells.map(c => (c / W) | 0)).size !== 2 ? 'ThreeByOne' : 'L', 4: new Set(r.cells.map(c => c % W)).size === 2 && new Set(r.cells.map(c => (c / W) | 0)).size === 2 ? 'TwoByTwo' : 'FourByOne' })[r.cells.length], cells: r.cells })));
      const w = x.weight / tot; for (const p of ps) { const t = game[p.key] ||= tally(); t.n += w; if (p.flush) t.flush += w; if (p.inside) t.inside += w; else t.over += w; t.byShift[p.shift] = (t.byShift[p.shift] || 0) + w; } }
    nGame++;
  }
  console.log(`game-process predictor: ${nGame} maps, side by side per map and flush share:`);
  for (const k of Object.keys(game).sort()) { const g = game[k]; console.log(`  ${k.padEnd(6)} ${(g.n / nGame).toFixed(3)}   flush ${(g.flush / g.n * 100).toFixed(0)}%   sticking out ${(g.over / g.n * 100).toFixed(0)}%`); }
}
console.log(`${nReal} real maps, ${nModel} model layouts (one random spot per room, the same mix and entrance)`);
console.log('pair      per map (real / model)   shorter one alongside, flush with an end | alongside, not flush | sticking out past the end (real / model)');
for (const k of Object.keys(real).sort()) {
  const r = real[k], m = model[k] || tally(); const pc = (a, b) => (a / Math.max(b, 1) * 100).toFixed(0).padStart(3) + '%';
  console.log(`  ${k.padEnd(6)}  ${(r.n / nReal).toFixed(3)} / ${(m.n / nModel).toFixed(3)}        ${pc(r.flush, r.n)} / ${pc(m.flush, m.n)}       |  ${pc(r.inside - r.flush, r.n)} / ${pc(m.inside - m.flush, m.n)}      |  ${pc(r.over, r.n)} / ${pc(m.over, m.n)}`);
  const sh = [...new Set([...Object.keys(r.byShift), ...Object.keys(m.byShift)])].sort((a, b) => a - b);
  console.log('          start shift ' + sh.map(s => `${s}: ${((r.byShift[s] || 0) / nReal).toFixed(3)} / ${((m.byShift[s] || 0) / nModel).toFixed(3)}`).join('   ') + '  (per map)');
}
