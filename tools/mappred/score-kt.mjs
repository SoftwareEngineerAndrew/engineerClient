import { readFileSync } from 'node:fs';
import { split, snapshots, score } from './evalcore.mjs';
const { test } = split();
const snaps = snapshots(test.slice(0, 150));
const out = readFileSync('snaps.txt.out', 'utf8').split('\n').filter(Boolean);
snaps.forEach((s, i) => { s.kt = out[i]; });
// Kotlin classes: BIG, NORMAL, PUZZLE, TRAP, YELLOW, BLOOD, FAIRY, RARE == CLASSES n o P T Y B F R
score('kotlin MapPredictor', snaps, s => {
  const [lvl, probsS, splitsS] = s.kt.split('|');
  const per = probsS.split(';').map(x => x ? x.split(',').map(Number) : null);
  const probs = s.A.unknown.map(([x, z]) => per[z * 6 + x] || [1, 0, 0, 0, 0, 0, 0, 0]);
  const sp = splitsS ? splitsS.split(';').map(x => { const a = x.split(',').map(Number); return { tile: a[0], rooms: a[2], tiles: a[3], c: a.slice(4) }; }) : [];
  const branch = s.ents.map(e => sp.find(q => q.tile === e.z * 6 + e.x) || { c: new Array(8).fill(0), tiles: 0 });
  return { probs, branch };
});
