// Synthetic maps from sampler2 (only the entrance known, each real map's entrance and puzzle count), in
// placelik.mjs's form, to check what its tests give when the procedure is known.
//   LAYOUTS=layouts.jsonl OPTS='{"reserve":5}' node synth.mjs out.json [maps]
import { writeFileSync, readFileSync } from 'node:fs';
import { complete } from './common.mjs';
import { observe } from './sim.mjs';
import { sample, combosFor } from './sampler2.mjs';
import { makeRng } from './mlp.mjs';
const [out, nMaps = '1e9'] = process.argv.slice(2);
const opts = JSON.parse(process.env.OPTS || '{}');
const tables = JSON.parse(readFileSync(process.env.TABLES || 'tables-current.json', 'utf8'));
const Ls = complete(); const combos = combosFor(Ls);
const rnd = makeRng(+(process.env.SEED || 5));
const maps = [];
for (const L of Ls.slice(0, +nMaps)) {
  const E = L.rooms.find(r => r.type === 'ENTRANCE'); const ent = E.tiles[0].z * 6 + E.tiles[0].x;
  let got = null;
  sample(observe(L, new Set([E.i])), combos['7'], { samples: 1, rnd, tables, maxTries: 1e6, ...opts, onSample: s => { got = s; } });
  if (!got) continue;
  const big = [], dead = [], ones = []; let blood = -1;
  got.rooms.forEach((r, i) => { if (r.shape !== 'OneByOne') big.push([r.shape, r.tiles]); else { ones.push(r.tiles[0]); const t = got.type[i]; if (t === 'B') blood = r.tiles[0]; else if (t) dead.push(r.tiles[0]); } });
  maps.push({ ent, big, dead, blood, ones });
}
writeFileSync(out, JSON.stringify(maps));
console.log(`${maps.length} synthetic maps`);
