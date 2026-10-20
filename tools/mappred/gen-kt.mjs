import { readFileSync } from 'node:fs';
import { complete } from './common.mjs';
import { SHAPES } from './sampler2.mjs';
const T = JSON.parse(readFileSync('tables3.json', 'utf8'));
const all = complete();
const combo = new Map(); let n7 = 0;
for (const L of all) { const c = SHAPES.map(s => L.rooms.filter(r => r.shape === s).length); if (c.reduce((a, b) => a + b) !== 7) continue; n7++; const k = c.join(''); combo.set(k, (combo.get(k) || 0) + 1); }
const rows = [...combo].sort((a, b) => b[1] - a[1]);
const f = (a, d = 3) => a.map(v => +v.toFixed(d) + 'f').join(', ');
console.log(`// ${all.length} complete F7/M7 maps, ${n7} with 7 big rooms`);
console.log(`val COMBOS = arrayOf(\n${rows.map(([k, w]) => `    intArrayOf(${[...k].join(', ')}, ${w}),`).join('\n')}\n)`);
console.log(`val WALK = floatArrayOf(${f(T.WD)})`);
console.log(`val BLOOD_FROM_ENTRANCE = floatArrayOf(${f(T.MW)})`);
console.log(`val FAIRY_FROM_ENTRANCE = floatArrayOf(${f(T.FM)})`);
console.log(`val BIG_TILE = floatArrayOf(\n${[0, 1, 2, 3, 4, 5].map(z => '    ' + f(T.BW.slice(z * 6, z * 6 + 6)) + ',').join('\n')}\n)`);
