// Held-out snapshots of the FLOORS (env, default 7) in a plain text form the Kotlin check reads:
// "6 6 puzzles floor|kind:t,t;...|a,b,k;..." with tiles on Odin's 6-wide grid (t = z * 6 + x) on every floor.
import { writeFileSync } from 'node:fs';
import { split, snapshots } from './evalcore.mjs';
const KIND = { NORMAL: 'NORMAL', ENTRANCE: 'ENTRANCE', PUZZLE: 'PUZZLE', TRAP: 'TRAP', CHAMPION: 'YELLOW', BLOOD: 'BLOOD', FAIRY: 'FAIRY', RARE: 'RARE' };
const EK = { none: 1, n: 2, w: 3, b: 4, same: 5 };
const { test } = split();
const snaps = snapshots(test.slice(0, +(process.argv[2] || 150)));
const lines = snaps.map(s => {
  const rooms = [...s.obs.rooms.values()].map(r => KIND[r.type] + ':' + r.tiles.map(t => t.z * 6 + t.x).join(','));
  const edges = [];
  for (const [id, k] of s.obs.edge) { if (k === 'unk') continue; const [x, z] = id.slice(1).split(',').map(Number); const a = z * 6 + x, b = id[0] === 'h' ? a + 1 : a + 6; edges.push(`${a},${b},${EK[k]}`); }
  return `6 6 ${s.obs.puzzles} ${s.L.floor[1]}|${rooms.join(';')}|${edges.join(';')}`;
});
writeFileSync('snaps.txt', lines.join('\n') + '\n');
console.log(lines.length, 'snapshots');
