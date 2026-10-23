// Scores the Kotlin predictor's answers (snaps.txt.out, MapSnapshotsCheck) on export-snaps.mjs's snapshots (same FLOORS and count).
import { readFileSync } from 'node:fs';
import { split, snapshots, score } from './evalcore.mjs';
import { CLASSES } from './sim.mjs';
const { test } = split();
const snaps = snapshots(test.slice(0, +(process.argv[2] || 150)));
const out = readFileSync('snaps.txt.out', 'utf8').split('\n').filter(Boolean);
snaps.forEach((s, i) => { s.kt = out[i]; });
const flat = c => CLASSES.map(o => o === c ? 1 : 0);
// Kotlin classes: BIG, NORMAL, PUZZLE, TRAP, YELLOW, BLOOD, FAIRY, RARE == CLASSES n o P T Y B F R; on a puzzle
// column tile they add up to the chance of a room, the rest is x (no room), as is a tile it gives nothing for
score('kotlin MapPredictor', snaps, s => {
  const [lvl, probsS, splitsS] = s.kt.split('|');
  const per = (probsS || '').split(';').map(x => x ? x.split(',').map(Number) : null);
  const probs = s.A.unknown.map(([x, z]) => {
    const p = per[z * 6 + x];
    if (!p) return flat(x >= s.L.rules.w ? 'x' : 'n');
    return [...p, Math.max(0, 1 - p.reduce((a, b) => a + b, 0))];
  });
  const sp = splitsS ? splitsS.split(';').map(x => { const a = x.split(',').map(Number); return { tile: a[0], rooms: a[2], tiles: a[3], c: a.slice(4) }; }) : [];
  const branch = s.ents.map(e => sp.find(q => q.tile === e.z * 6 + e.x) || { c: new Array(CLASSES.length).fill(0), tiles: 0 });
  return { probs, branch };
});
