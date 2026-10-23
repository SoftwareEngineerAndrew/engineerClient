// Scores sampler2 on held-out maps of the FLOORS (env, default 7) with simulated discovery order.
import { split, snapshots, score } from './evalcore.mjs';
import { sample, combosFor, OUT } from './sampler2.mjs';
import { makeRng } from './mlp.mjs';
const { train, test } = split();
const combos = combosFor(train);
console.log(Object.entries(combos).map(([f, c]) => `F${f} ${c.length} combos`).join(', '), 'from', train.length, 'train dungeons;', test.length, 'test');
const nTest = +(process.argv[2] || 150), N = +(process.argv[3] || 400);
const TREE = process.argv[4] || 'prim', GROW = +(process.argv[5] ?? 0.3), TABLES = process.argv[6] ? JSON.parse((await import('node:fs')).readFileSync(process.argv[6], 'utf8')) : undefined;
const snaps = snapshots(test.slice(0, nTest));
const rnd = makeRng(5);
let acc = 0, tr = 0, empty = 0;
const t0 = Date.now();
const flat = c => OUT.map(o => o === c ? 1 : 0);
score(`sampler2 N=${N} tree=${TREE} grow=${GROW} tables=${process.argv[6]}`, snaps, s => {
  const r = sample(s.obs, combos[s.L.floor[1]], { samples: N, rnd, tree: TREE, grow: GROW, tables: TABLES });
  acc += r.accepted; tr += r.tries; if (!r.accepted) empty++;
  // the harness's unknown tiles: a puzzle column tile the sampler left out has a wall to its west, so no room
  const W = s.obs.W, byTile = new Map(r.unknown.map((t, i) => [t, r.probs[i]]));
  const probs = s.A.unknown.map(([x, z]) => byTile.get(z * W + x) || flat(x >= s.L.rules.w ? 'x' : 'n'));
  const B = s.ents.map(e => { const j = r.entries.findIndex(q => q.t === e.z * W + e.x); return j >= 0 ? r.branch[j] : { c: new Array(OUT.length).fill(0), tiles: 0 }; });
  return { probs, branch: B };
});
console.log(`acceptance ${(acc / tr).toFixed(3)}, snapshots with no sample ${empty}/${snaps.length}, ${((Date.now() - t0) / snaps.length).toFixed(0)} ms per snapshot`);
