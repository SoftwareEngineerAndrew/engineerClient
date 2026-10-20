import { split, snapshots, score } from './evalcore.mjs';
import { sample, SHAPES } from './sampler2.mjs';
import { makeRng } from './mlp.mjs';
const { train, test } = split();
const combo = new Map();
for (const L of train) { const c = SHAPES.map(s => L.rooms.filter(r => r.shape === s).length); if (c.reduce((a, b) => a + b) !== 7) continue; const k = c.join(''); combo.set(k, (combo.get(k) || 0) + 1); }
const combos = [...combo].map(([k, w]) => ({ counts: [...k].map(Number), w }));
console.log(combos.length, 'combos from', train.length, 'train dungeons;', test.length, 'test');
const nTest = +(process.argv[2] || 150), N = +(process.argv[3] || 400), gamma = 0;
const TREE = process.argv[4] || 'prim', GROW = +(process.argv[5] ?? 0.3), TABLES = process.argv[6] ? JSON.parse((await import('node:fs')).readFileSync(process.argv[6], 'utf8')) : undefined;
const snaps = snapshots(test.slice(0, nTest));
const rnd = makeRng(5);
let acc = 0, tr = 0, empty = 0;
const t0 = Date.now();
score(`sampler2 N=${N} tree=${TREE} grow=${GROW} tables=${process.argv[6]}`, snaps, s => {
  const r = sample(s.obs, combos, { samples: N, rnd, tree: TREE, grow: GROW, tables: TABLES });
  acc += r.accepted; tr += r.tries; if (!r.accepted) empty++;
  // map unknown tiles to the harness order (row-major either way)
  const probs = r.probs.map(p => p || [1, 0, 0, 0, 0, 0, 0, 0]);
  const B = s.ents.map(e => { const j = r.entries.findIndex(q => q.t === e.z * s.obs.W + e.x); return j >= 0 ? r.branch[j] : { c: new Array(8).fill(0), tiles: 0 }; });
  return { probs, branch: B };
});
console.log(`acceptance ${(acc / tr).toFixed(3)}, snapshots with no sample ${empty}/${snaps.length}, ${((Date.now() - t0) / snaps.length).toFixed(0)} ms per snapshot`);
