// Held-out F7 maps revealed room by room, in a plain text form the Kotlin sequence check reads (MapSequenceCheck): for
// each map a line "map <id> <puzzles> <floor> <truth>" (the 36 tiles' classes, n o P T Y B F R, E the entrance, x none)
// and then a line "view <rooms known> <seconds into the run> 6 6 <puzzles> <floor>|<kind:tiles;...>|<a,b,edgekind;...>"
// for each map the game would show on the way (the same line as export-snaps.mjs; tile = z * 6 + x). Run from an
// empty data folder with LAYOUTS=<layouts file> (the maps; default layouts.jsonl there):
//
//   node export-seq.mjs <maps> [offset]        env: SET=test|train (default test: split() as every script),
//                                              ORDER=sim (discoveryOrder, as export-snaps) | real (the order the rooms
//                                              showed on the recorder's map, from TIMELINES=timelines.jsonl; then the
//                                              seconds are real, else they count 3 s a room), FROM=2 (first view's rooms)
//
// Writes seq.txt. Keep the data folder out of the repo.
import { writeFileSync } from 'node:fs';
import { split } from './evalcore.mjs';
import { complete } from './common.mjs';
import { discoveryOrder, observe, CLASSES, CLS, classOf } from './sim.mjs';
import { loadTimelines } from './tl.mjs';
import { makeRng } from './mlp.mjs';

const KIND = { NORMAL: 'NORMAL', ENTRANCE: 'ENTRANCE', PUZZLE: 'PUZZLE', TRAP: 'TRAP', CHAMPION: 'YELLOW', BLOOD: 'BLOOD', FAIRY: 'FAIRY', RARE: 'RARE' };
const EK = { none: 1, n: 2, w: 3, b: 4, same: 5 };
const ORDER = process.env.ORDER || 'sim';
const FROM = +(process.env.FROM || 2);
const { train, test } = split();
const pool = process.env.SET === 'train' ? train : test;
const count = +(process.argv[2] || 20), offset = +(process.argv[3] || 0);
const maps = pool.slice(offset, offset + count);
const rnd = makeRng(99);
let real = null;
if (ORDER === 'real') real = new Map(loadTimelines(process.env.TIMELINES || 'timelines.jsonl').filter(t => t.start !== null).map(t => [t.id, t]));

const TRUTH = { n: 'n', o: 'o', P: 'P', T: 'T', Y: 'Y', B: 'B', F: 'F', R: 'R', E: 'E', x: 'x' };
const lines = []; let nViews = 0, nMaps = 0;
for (const L of maps) {
  let order, secs;
  if (real) {
    const t = real.get(L.id);
    if (!t) continue;
    const seen = [...t.seen.entries()].filter(([i]) => L.rooms[i]).sort((a, b) => a[1] - b[1]);
    order = seen.map(([i]) => i); secs = seen.map(([, tick]) => (tick - t.start) / 20);
  } else { order = discoveryOrder(L, rnd); secs = order.map((_, i) => 3 * i); }
  const E = L.rooms.find(r => r.type === 'ENTRANCE').i;
  if (!order.includes(E)) continue;
  const puzzles = L.rooms.filter(r => r.type === 'PUZZLE').length;
  let truth = '';
  for (let z = 0; z < 6; z++) for (let x = 0; x < 6; x++) {
    const r = L.grid[z]?.[x] >= 0 ? L.rooms[L.grid[z][x]] : null;
    truth += r ? TRUTH[classOf(r)] ?? 'n' : 'x';
  }
  const views = [];
  for (let k = FROM; k < order.length; k++) {
    const known = new Set(order.slice(0, k));
    if (!known.has(E)) continue;
    const obs = observe(L, known);
    const rooms = [...obs.rooms.values()].map(r => { if (!KIND[r.type]) throw new Error('kind ' + r.type); return KIND[r.type] + ':' + r.tiles.map(t => t.z * 6 + t.x).join(','); });
    const edges = [];
    for (const [id, kd] of obs.edge) {
      if (kd === 'unk') continue;
      if (!EK[kd]) throw new Error('edge kind ' + kd);
      const [x, z] = id.slice(1).split(',').map(Number); const a = z * 6 + x, b = id[0] === 'h' ? a + 1 : a + 6; edges.push(`${a},${b},${EK[kd]}`);
    }
    if (obs.W !== 6) throw new Error('not 6 wide');
    views.push(`view ${k} ${secs[k - 1].toFixed(1)} 6 6 ${obs.puzzles} ${L.floor[1]}|${rooms.join(';')}|${edges.join(';')}`);
  }
  if (!views.length) continue;
  lines.push(`map ${L.id} ${puzzles} ${L.floor[1]} ${truth}`, ...views);
  nMaps++; nViews += views.length;
}
writeFileSync('seq.txt', lines.join('\n') + '\n');
console.log(nMaps, 'maps,', nViews, 'views,', ORDER, 'order');
