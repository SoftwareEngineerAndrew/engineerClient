// Every hard rule, checked on every complete F7/M7 map.
import { complete, inc } from './common.mjs';
import { prep } from './sim.mjs';
import { walkFrom } from './walk.mjs';
import { SHAPES } from './sampler2.mjs';
const Ls = complete();
const c = { maps: Ls.length, tree: 0, big7: 0, max2: 0, tiles22: 0, specials1x1dead: 0, normal1x1NotDead: 0, deadCount: 0, rareOnly5: 0, fairyOnPath: 0, fairy4: 0, walk910: 0, witherOnlyPath: 0, entranceEdge: 0 };
const walkLen = {}, rooms = {};
for (const L of Ls) {
  const big = L.rooms.filter(r => r.shape !== 'OneByOne');
  const counts = SHAPES.map(s => L.rooms.filter(r => r.shape === s).length);
  if (big.length === 7) c.big7++;
  if (counts.every(v => v <= 2)) c.max2++;
  if (big.reduce((a, r) => a + r.tiles.length, 0) === 22) c.tiles22++;
  inc(rooms, L.rooms.length);
  c.tree++; // complete() only keeps trees
  const deg = r => L.g.get(r.i).length;
  if (L.rooms.filter(r => !['NORMAL', 'FAIRY'].includes(r.type)).every(r => r.shape === 'OneByOne' && deg(r) === 1)) c.specials1x1dead++;
  if (L.rooms.filter(r => r.type === 'NORMAL' && r.shape === 'OneByOne').every(r => deg(r) >= 2)) c.normal1x1NotDead++;
  const P = L.rooms.filter(r => r.type === 'PUZZLE').length, R = L.rooms.filter(r => r.type === 'RARE').length;
  const dead = L.rooms.filter(r => r.shape === 'OneByOne' && deg(r) === 1 && !['ENTRANCE', 'BLOOD'].includes(r.type)).length;
  if (dead === P + 2 + R) c.deadCount++;
  if (R === 0 || P === 5) c.rareOnly5++;
  const { path, E, B } = prep(L);
  const F = L.rooms.find(r => r.type === 'FAIRY');
  if (path.includes(F.i)) c.fairyOnPath++;
  const w = walkFrom(L, E.i);
  const D = w.get(B.i).dist, fd = w.get(F.i).dist;
  if (D - fd === 4) c.fairy4++;
  inc(walkLen, D);
  if (D === 9 || D === 10) c.walk910++;
  const e = E.tiles[0]; if ((e.x === 0 || e.x === 5 || e.z === 0 || e.z === 5) && !((e.x === 0 || e.x === 5) && (e.z === 0 || e.z === 5))) c.entranceEdge++;
}
for (const [k, v] of Object.entries(c)) console.log(k.padEnd(18), v, k === 'maps' ? '' : (100 * v / c.maps).toFixed(2) + '%');
console.log('walk lengths', walkLen, 'rooms', rooms);
