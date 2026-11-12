// Wither doors on the blood rush by puzzle count, on every complete F7/M7 map (F7 and M7 apart too).
//   LAYOUTS=layouts.jsonl node doorsbypuzzle.mjs
// The wither doors are the path's doors entrance -> blood less the first (entrance) and last (blood); the
// recorded 'w' door spots on the path are counted as well, as a check on that.
import { complete, inc, pathBetween } from './common.mjs';

const Ls = complete();
const tab = { all: {}, F7: {}, M7: {} };
let agree = 0;
for (const L of Ls) {
  const E = L.rooms.find(r => r.type === 'ENTRANCE'), B = L.rooms.find(r => r.type === 'BLOOD');
  const path = pathBetween(L, E.i, B.i);
  const w = path.length - 2, seen = path.filter(e => e.what === 'w').length;
  if (w === seen) agree++;
  const P = L.rooms.filter(r => r.type === 'PUZZLE').length;
  for (const k of ['all', L.floor]) { const t = tab[k][P] ||= {}; inc(t, w); inc(t, 'n'); }
}
console.log(`${Ls.length} maps; recorded wither doors match the path's on ${agree}`);
for (const [k, byP] of Object.entries(tab)) {
  console.log(`\n${k}`);
  const doors = [...new Set(Object.values(byP).flatMap(t => Object.keys(t).filter(d => d !== 'n')))].map(Number).sort((a, b) => a - b);
  console.log('puzzles  maps  ' + doors.map(d => `${d} door`.padStart(8)).join(''));
  for (const P of Object.keys(byP).sort()) {
    const t = byP[P];
    console.log(`${P.padStart(7)} ${String(t.n).padStart(5)}  ` + doors.map(d => ((100 * (t[d] || 0) / t.n).toFixed(1) + '%').padStart(8)).join(''));
  }
}
