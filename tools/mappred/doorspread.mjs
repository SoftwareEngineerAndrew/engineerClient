// Where a big room's other doors are: by how many cells in from the cell it's entered by (the walk from the
// entrance), real maps against a generator.mjs DUMP. Real rooms put them near their entry cell.
//   LAYOUTS=layouts.jsonl node doorspread.mjs dump.json
import { readFileSync } from 'node:fs';
import { complete } from './common.mjs';
import { walkFrom } from './walk.mjs';
const W = 6, N = 36;
const md = (a, b) => Math.abs(a % W - b % W) + Math.abs(((a / W) | 0) - ((b / W) | 0));
const c = { real: {}, gen: {} };
const inc = (s, k, v = 1) => { c[s][k] = (c[s][k] || 0) + v; };
for (const L of complete()) {
  const E = L.rooms.find(r => r.type === 'ENTRANCE'); const wf = walkFrom(L, E.i);
  for (const r of L.rooms) {
    if (r.shape === 'OneByOne' || r === E) continue;
    const { at } = wf.get(r.i); const entry = at[1] * W + at[0];
    // the room's side of each of its doors; the one it's entered by is the door whose far side is its parent
    const doors = L.doors.filter(d => d.what !== '-' && d.a >= 0 && d.b >= 0 && d.a !== d.b && (d.a === r.i || d.b === r.i))
      .map(d => d.a === r.i ? d.az * W + d.ax : d.bz * W + d.bx);
    let skipped = false;
    for (const t of doors) { if (t === entry && !skipped) { skipped = true; continue; } inc('real', `${r.shape} n`); inc('real', `${r.shape} in ${md(entry, t)}`); }
    inc('real', `${r.shape} rooms`);
  }
}
const dump = JSON.parse(readFileSync(process.argv[2], 'utf8'));
for (const { owner, shape, parent, depth } of dump[Object.keys(dump)[0]]) {
  const R = Math.max(...owner) + 1;
  for (let r = 1; r < R; r++) {
    if (shape[r] === 'OneByOne') continue;
    const cells = []; for (let t = 0; t < N; t++) if (owner[t] === r) cells.push(t);
    const entry = cells.reduce((a, t) => depth[t] < depth[a] ? t : a, cells[0]);
    for (let t = 0; t < N; t++) {
      const p = parent[t]; if (p < 0 || owner[p] === owner[t]) continue;
      // the door t - p; skip the one r is entered by (r's entry cell from its parent)
      if (owner[t] === r && t === entry) continue;
      const mine = owner[t] === r ? t : owner[p] === r ? p : -1; if (mine < 0) continue;
      inc('gen', `${shape[r]} n`); inc('gen', `${shape[r]} in ${md(entry, mine)}`);
    }
    inc('gen', `${shape[r]} rooms`);
  }
}
console.log("each big room's other doors by cells in from its entry cell (% of its doors), real | generated");
for (const s of ['TwoByOne', 'ThreeByOne', 'FourByOne', 'TwoByTwo', 'L']) {
  const row = src => [0, 1, 2, 3].map(d => ((c[src][`${s} in ${d}`] || 0) / (c[src][`${s} n`] || 1) * 100).toFixed(0).padStart(4)).join('') + `   doors/room ${((c[src][`${s} n`] || 0) / (c[src][`${s} rooms`] || 1)).toFixed(2)}`;
  console.log(`  ${s.padEnd(11)} ${row('real')}   | ${row('gen')}`);
}
